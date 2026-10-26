package com.banco.recolector.service;

import com.banco.recolector.corebancario.CoreBancarioClient;
import com.banco.recolector.corebancario.TransaccionCoreRequest;
import com.banco.recolector.corebancario.TransaccionCoreResponse;
import com.banco.recolector.iso.IsoMessageService;
import com.banco.recolector.iso.IsoResponseCode;
import com.banco.recolector.iso.IsoTransactionDto;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Servicio Orquestador Central del Recolector Transaccional.
 * Coordina el parseo ISO 8583, el control de idempotencia, la invocación
 * del Core Bancario vía RestClient y el ensamblado de la respuesta MTI 0210.
 */
@Service
public class RecolectorTransaccionalService {

    private static final Logger log = LoggerFactory.getLogger(RecolectorTransaccionalService.class);

    private final IsoMessageService isoService;
    private final CoreBancarioClient coreClient;
    private final IdempotenciaService idempotenciaService;

    private final AtomicLong contadorProcesadas = new AtomicLong(0);
    private final AtomicLong contadorAprobadas = new AtomicLong(0);
    private final AtomicLong contadorRechazadas = new AtomicLong(0);

    public RecolectorTransaccionalService(
            IsoMessageService isoService,
            CoreBancarioClient coreClient,
            IdempotenciaService idempotenciaService
    ) {
        this.isoService = isoService;
        this.coreClient = coreClient;
        this.idempotenciaService = idempotenciaService;
    }

    /**
     * Procesa una trama ISO 8583 binaria o ASCII entrante y retorna la trama de respuesta codificada.
     */
    public byte[] procesarTrama(byte[] tramaEntrante) {
        contadorProcesadas.incrementAndGet();

        IsoMessage requestMsg;
        try {
            requestMsg = isoService.parse(tramaEntrante);
        } catch (Exception e) {
            log.error("Error al decodificar trama ISO 8583 entrante: {}", e.getMessage());
            // Si el mensaje está corrupto, no es posible construir un 0210 válido con headers
            return ("ISO8583_FORMAT_ERROR_RC30").getBytes();
        }

        IsoMessage responseMsg = procesarMensaje(requestMsg);
        return isoService.encode(responseMsg);
    }

    /**
     * Orquesta el flujo de negocio para un IsoMessage ya deserializado.
     */
    public IsoMessage procesarMensaje(IsoMessage request) {
        int mti = request.getType();
        String mtiHex = String.format("%04x", mti);
        log.info("Procesando mensaje ISO 8583 MTI={}", mtiHex);

        return switch (mtiHex) {
            case "0200" -> procesarFinanciera(request);
            case "0100" -> procesarAutorizacionOConsulta(request);
            case "0420" -> procesarReversa(request);
            case "0800" -> procesarEchoTest(request);
            default -> {
                log.warn("MTI no reconocido o no soportado: {}", mtiHex);
                yield isoService.createResponse(request, IsoResponseCode.ERROR_FORMATO, null);
            }
        };
    }

    /**
     * MTI 0200: Transacción Financiera (Compra o Retiro de Efectivo).
     */
    private IsoMessage procesarFinanciera(IsoMessage req) {
        IsoTransactionDto txDto = isoService.extractTransaction(req);
        log.info("Solicitud Financiera 0200: RRN={} STAN={} Monto={} Cuenta={}",
                txDto.rrn(), txDto.stan(), txDto.amount(), txDto.cuenta());

        // 1. Verificación de Idempotencia por RRN y STAN
        IdempotenciaService.RegistroIdempotente registroPrevio =
                idempotenciaService.obtener(txDto.rrn(), txDto.stan());

        if (registroPrevio != null) {
            log.warn("Respondiendo con registro previo por Idempotencia para RRN={}", txDto.rrn());
            try {
                return isoService.parse(registroPrevio.isoResponseBytes());
            } catch (Exception e) {
                log.error("Error al recuperar respuesta idempotente: {}", e.getMessage());
            }
        }

        // 2. Construir DTO inmutable (Record) para el Core Bancario
        TransaccionCoreRequest coreReq = TransaccionCoreRequest.fromIso(
                txDto.rrn(),
                txDto.stan(),
                txDto.pan(),
                txDto.cuenta(),
                txDto.amount(),
                txDto.processingCode(),
                txDto.terminalId()
        );

        // 3. Invocar al Core Bancario vía RestClient
        TransaccionCoreResponse coreResp = coreClient.procesarTransaccion(coreReq);

        // 4. Mapear a Mensaje ISO de Respuesta MTI 0210
        IsoMessage resp = isoService.createResponse(req, coreResp.codigoRespuesta(), coreResp.codigoAutorizacion());

        // Si la respuesta incluye saldos (típico en retiros ATM o compras con saldo devuelto)
        if (coreResp.saldoDisponible() != null) {
            String saldoFormateado = formatearCampo54(coreResp.saldoDisponible(), coreResp.saldoContable());
            resp.setValue(54, saldoFormateado, IsoType.LLLVAR, 0);
        }

        // Actualizar contadores métricos
        if (coreResp.exitosa()) {
            contadorAprobadas.incrementAndGet();
        } else {
            contadorRechazadas.incrementAndGet();
        }

        // 5. Guardar en Idempotencia para futuras retransmisiones
        idempotenciaService.registrar(txDto.rrn(), txDto.stan(), coreResp, isoService.encode(resp));

        return resp;
    }

    /**
     * MTI 0100: Consulta de Saldo o Pre-autorización.
     */
    private IsoMessage procesarAutorizacionOConsulta(IsoMessage req) {
        IsoTransactionDto txDto = isoService.extractTransaction(req);
        log.info("Solicitud Autorización/Consulta 0100: RRN={} STAN={}", txDto.rrn(), txDto.stan());

        TransaccionCoreRequest coreReq = TransaccionCoreRequest.fromIso(
                txDto.rrn(), txDto.stan(), txDto.pan(), txDto.cuenta(),
                BigDecimal.ZERO, "300000", txDto.terminalId()
        );

        TransaccionCoreResponse coreResp = coreClient.procesarTransaccion(coreReq);
        IsoMessage resp = isoService.createResponse(req, coreResp.codigoRespuesta(), coreResp.codigoAutorizacion());

        if (coreResp.saldoDisponible() != null) {
            resp.setValue(54, formatearCampo54(coreResp.saldoDisponible(), coreResp.saldoContable()), IsoType.LLLVAR, 0);
        }

        return resp;
    }

    /**
     * MTI 0420: Reversa Financiera por fallo de hardware o corte de energía.
     */
    private IsoMessage procesarReversa(IsoMessage req) {
        IsoTransactionDto txDto = isoService.extractTransaction(req);
        log.info("Solicitud de Reversa Financiera 0420: RRN={} STAN={} Monto={}",
                txDto.rrn(), txDto.stan(), txDto.amount());

        TransaccionCoreRequest coreReq = new TransaccionCoreRequest(
                txDto.rrn(), txDto.stan(), txDto.pan(), txDto.cuenta(), null,
                txDto.amount(), "REVERSA", txDto.terminalId(), txDto.processingCode()
        );

        TransaccionCoreResponse coreResp = coreClient.procesarTransaccion(coreReq);
        return isoService.createResponse(req, coreResp.codigoRespuesta(), null);
    }

    /**
     * MTI 0800: Network Management Echo Test de Red (DE 70 = 301).
     */
    private IsoMessage procesarEchoTest(IsoMessage req) {
        log.info("Solicitud de Echo Test 0800 recibida. Generando 0810 con DE 39='00'...");
        IsoMessage resp = isoService.createResponse(req, IsoResponseCode.APROBADA, null);
        if (req.hasField(70)) {
            resp.setValue(70, req.getField(70).getValue(), IsoType.NUMERIC, 3);
        }
        return resp;
    }

    /**
     * Formatea el Campo 54 estándar ISO 8583 con saldos contable y disponible (20 caracteres por saldo).
     */
    private String formatearCampo54(BigDecimal disponible, BigDecimal contable) {
        long dispCents = disponible.multiply(new BigDecimal("100")).longValue();
        long contCents = contable.multiply(new BigDecimal("100")).longValue();

        String saldoCont = String.format("1001840C%012d", contCents);
        String saldoDisp = String.format("1002840C%012d", dispCents);
        return saldoCont + saldoDisp;
    }

    public long getContadorProcesadas() { return contadorProcesadas.get(); }
    public long getContadorAprobadas() { return contadorAprobadas.get(); }
    public long getContadorRechazadas() { return contadorRechazadas.get(); }
}
