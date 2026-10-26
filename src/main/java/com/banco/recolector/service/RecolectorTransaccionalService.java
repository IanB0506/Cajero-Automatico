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
import java.math.RoundingMode;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orquestador central del Recolector Transaccional: parseo ISO 8583, control de idempotencia,
 * invocación del Core Bancario vía RestClient y ensamblado de la respuesta (0110, 0210, 0430, 0810).
 */
@Service
public class RecolectorTransaccionalService {

    private static final Logger log = LoggerFactory.getLogger(RecolectorTransaccionalService.class);

    /** Errores transitorios: no se guardan en la caché para que la retransmisión vuelva a intentarse. */
    private static final Set<String> TRANSITORIOS = Set.of(
            IsoResponseCode.HOST_NO_DISPONIBLE, IsoResponseCode.TIMEOUT, IsoResponseCode.ERROR_SISTEMA);
    /** Respuestas en las que el saldo devuelto por el Core es significativo para el DE 54. */
    private static final Set<String> CON_SALDO = Set.of(
            IsoResponseCode.APROBADA, IsoResponseCode.FONDOS_INSUFICIENTES, IsoResponseCode.LIMITE_EXCEDIDO);

    private final IsoMessageService isoService;
    private final CoreBancarioClient coreClient;
    private final IdempotenciaService idempotenciaService;

    private final AtomicLong contadorProcesadas = new AtomicLong();
    private final AtomicLong contadorAprobadas = new AtomicLong();
    private final AtomicLong contadorRechazadas = new AtomicLong();
    private final AtomicLong contadorErroresFormato = new AtomicLong();

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
     * Procesa una trama ISO 8583 entrante (TCP) y retorna la trama de respuesta codificada.
     * Una trama ilegible recibe una respuesta ISO válida con DE 39 = 30 (Error de Formato).
     */
    public byte[] procesarTrama(byte[] tramaEntrante) {
        IsoMessage requestMsg;
        try {
            requestMsg = isoService.parse(tramaEntrante);
        } catch (Exception e) {
            contadorProcesadas.incrementAndGet();
            contadorRechazadas.incrementAndGet();
            contadorErroresFormato.incrementAndGet();
            log.warn("Trama ISO 8583 ilegible ({} bytes): {} -> DE 39 = 30",
                    tramaEntrante != null ? tramaEntrante.length : 0, e.getMessage());
            return isoService.encode(isoService.crearRespuestaErrorFormato(tramaEntrante));
        }
        try {
            return isoService.encode(procesarMensaje(requestMsg));
        } catch (RuntimeException e) {
            // La terminal siempre recibe una respuesta ISO (DE 39 = 96) en lugar de un cierre de conexión
            log.error("Error inesperado procesando la trama: {}", e.getMessage(), e);
            return isoService.encode(isoService.createResponse(requestMsg, IsoResponseCode.ERROR_SISTEMA, null));
        }
    }

    /**
     * Orquesta el flujo de negocio para un IsoMessage ya deserializado.
     */
    public IsoMessage procesarMensaje(IsoMessage request) {
        long inicio = System.nanoTime();
        contadorProcesadas.incrementAndGet();
        String mti = isoService.tipoMti(request);

        IsoMessage respuesta = switch (mti) {
            case "0200" -> procesarConCore(request, null);
            case "0100" -> procesarConCore(request, TransaccionCoreRequest.CONSULTA);
            case "0420" -> procesarConCore(request, TransaccionCoreRequest.REVERSA);
            case "0800" -> procesarEchoTest(request);
            default -> {
                log.warn("MTI no soportado: {}", mti);
                yield isoService.createResponse(request, IsoResponseCode.TRANSACCION_INVALIDA, null);
            }
        };

        String rc = isoService.getFieldValue(respuesta, 39);
        if (IsoResponseCode.APROBADA.equals(rc)) {
            contadorAprobadas.incrementAndGet();
        } else {
            contadorRechazadas.incrementAndGet();
        }
        log.debug("ISO {} -> {} STAN={} RRN={} DE39={} ({} ms)", mti, isoService.tipoMti(respuesta),
                isoService.getFieldValue(request, 11), isoService.getFieldValue(request, 37), rc,
                (System.nanoTime() - inicio) / 1_000_000);
        return respuesta;
    }

    /**
     * 0200 (financiera), 0100 (consulta/autorización) y 0420 (reversa): todas pasan por el Core Bancario.
     */
    private IsoMessage procesarConCore(IsoMessage req, String tipoForzado) {
        IsoTransactionDto tx = isoService.extractTransaction(req);
        log.debug("Solicitud {}", tx);

        // 1. Idempotencia por RRN y STAN (retransmisiones de la terminal)
        IdempotenciaService.RegistroIdempotente previo = idempotenciaService.obtener(tx.rrn(), tx.stan());
        if (previo != null) {
            try {
                return isoService.parse(previo.isoResponseBytes());
            } catch (Exception e) {
                log.warn("No se pudo reutilizar la respuesta idempotente: {}", e.getMessage());
            }
        }

        // 2. Data Elements obligatorios: sin STAN, RRN o cuenta la trama tiene error de formato
        if (vacio(tx.stan()) || vacio(tx.rrn()) || vacio(tx.cuenta())) {
            return isoService.createResponse(req, IsoResponseCode.ERROR_FORMATO, null);
        }

        // 3. DTO inmutable hacia el Core Bancario vía RestClient
        String tipo = tipoForzado != null ? tipoForzado : TransaccionCoreRequest.tipoPorCodigoProcesamiento(tx.processingCode());
        TransaccionCoreResponse coreResp = coreClient.procesarTransaccion(TransaccionCoreRequest.fromIso(tx, tipo));

        // 4. Respuesta ISO con DE 38 (autorización), DE 39 (código) y DE 54 (saldos)
        String rc = coreResp.codigoRespuesta() != null ? coreResp.codigoRespuesta() : IsoResponseCode.ERROR_SISTEMA;
        IsoMessage resp = isoService.createResponse(req, rc, coreResp.codigoAutorizacion());
        if (CON_SALDO.contains(rc) && coreResp.saldoDisponible() != null) {
            String moneda = tx.moneda() != null && tx.moneda().matches("\\d{3}") ? tx.moneda() : isoService.getMonedaPorDefecto();
            resp.setValue(54, formatearCampo54(coreResp.saldoDisponible(), coreResp.saldoContable(), moneda), IsoType.LLLVAR, 0);
        }

        // 5. Solo las respuestas definitivas se guardan para futuras retransmisiones
        if (!TRANSITORIOS.contains(rc)) {
            idempotenciaService.registrar(tx.rrn(), tx.stan(), isoService.encode(resp));
        }
        return resp;
    }

    /**
     * MTI 0800: Network Management Echo Test (DE 70 = 301).
     */
    private IsoMessage procesarEchoTest(IsoMessage req) {
        return isoService.createResponse(req, IsoResponseCode.APROBADA, null);
    }

    /**
     * Campo 54 (Additional Amounts): tipo de cuenta (10) + tipo de monto (01 contable / 02 disponible)
     * + moneda (DE 49) + signo (C crédito / D débito) + 12 dígitos en centavos.
     */
    static String formatearCampo54(BigDecimal disponible, BigDecimal contable, String moneda) {
        return saldo("01", contable != null ? contable : disponible, moneda) + saldo("02", disponible, moneda);
    }

    private static String saldo(String tipoMonto, BigDecimal monto, String moneda) {
        long centavos = monto.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return "10" + tipoMonto + moneda + (centavos < 0 ? "D" : "C") + String.format("%012d", Math.abs(centavos));
    }

    private static boolean vacio(String valor) {
        return valor == null || valor.isBlank();
    }

    public long getContadorProcesadas() { return contadorProcesadas.get(); }
    public long getContadorAprobadas() { return contadorAprobadas.get(); }
    public long getContadorRechazadas() { return contadorRechazadas.get(); }
    public long getContadorErroresFormato() { return contadorErroresFormato.get(); }
}
