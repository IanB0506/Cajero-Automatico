package com.banco.recolector.corebancario.service;

import com.banco.recolector.corebancario.CuentaDto;
import com.banco.recolector.corebancario.TransaccionCoreRequest;
import com.banco.recolector.corebancario.TransaccionCoreResponse;
import com.banco.recolector.corebancario.entity.CuentaEntity;
import com.banco.recolector.corebancario.entity.TransaccionEntity;
import com.banco.recolector.corebancario.repository.CuentaRepository;
import com.banco.recolector.corebancario.repository.TransaccionRepository;
import com.banco.recolector.iso.IsoResponseCode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Servicio de Lógica de Negocio del Core Bancario.
 * Gestiona cuentas, valida saldos y límites, y registra débitos y créditos en la tabla de transacciones.
 */
@Service
public class CoreBancarioService {

    private static final Logger log = LoggerFactory.getLogger(CoreBancarioService.class);

    private final CuentaRepository cuentaRepository;
    private final TransaccionRepository transaccionRepository;
    private final AtomicLong authCodeSeq = new AtomicLong(100001);

    public CoreBancarioService(CuentaRepository cuentaRepository, TransaccionRepository transaccionRepository) {
        this.cuentaRepository = cuentaRepository;
        this.transaccionRepository = transaccionRepository;
    }

    @PostConstruct
    public void verificarConexionBaseDatos() {
        long totalCuentas = cuentaRepository.count();
        log.info("Conexión exitosa a Microsoft SQL Server (CoreBancario5B). Cuentas registradas en BD: {}", totalCuentas);

        // Asignar bancos a las cuentas en base de datos si no lo tienen asignado
        List<CuentaEntity> cuentas = cuentaRepository.findAll();
        for (CuentaEntity c : cuentas) {
            String pan = c.getPan();
            String num = c.getNumeroCuenta();
            if (c.getBanco() == null || c.getBanco().isBlank() || "BANCO INDUSTRIAL".equals(c.getBanco())) {
                if (pan != null && pan.startsWith("5412")) {
                    c.setBanco("G&T CONTINENTAL");
                    cuentaRepository.save(c);
                } else if (pan != null && (pan.endsWith("0003") || (pan.startsWith("4000") && num != null && num.endsWith("3003")))) {
                    c.setBanco("BANRURAL");
                    cuentaRepository.save(c);
                } else if (pan != null && (pan.endsWith("0004") || (num != null && num.endsWith("3004")))) {
                    c.setBanco("BAC CREDOMATIC");
                    cuentaRepository.save(c);
                } else if (pan != null && (pan.endsWith("0005") || (num != null && num.endsWith("3005")))) {
                    c.setBanco("BANCO PROMERICA");
                    cuentaRepository.save(c);
                } else if (pan != null && pan.startsWith("4532")) {
                    c.setBanco("BANCO INDUSTRIAL");
                    cuentaRepository.save(c);
                }
            }
        }
    }

    @Transactional
    public TransaccionCoreResponse procesar(TransaccionCoreRequest req) {
        log.info("Core Bancario procesando operación [{}] RRN={} STAN={} Monto={}",
                req.tipoOperacion(), req.rrn(), req.stan(), req.monto());

        // 1. Verificar Idempotencia a nivel de base de datos
        Optional<TransaccionEntity> txExistente = transaccionRepository.findByRrnAndStan(req.rrn(), req.stan());
        if (txExistente.isPresent()) {
            TransaccionEntity te = txExistente.get();
            log.warn("Detectada transacción duplicada en Core Bancario (Idempotente): RRN={} STAN={}", req.rrn(), req.stan());
            return new TransaccionCoreResponse(
                    IsoResponseCode.APROBADA.equals(te.getCodigoRespuesta()),
                    te.getCodigoRespuesta(),
                    te.getCodigoAutorizacion(),
                    te.getSaldoPosterior(),
                    te.getSaldoPosterior(),
                    "Transacción previa recuperada por Idempotencia",
                    te.getRrn(),
                    te.getFechaHora().toString()
            );
        }

        // 2. Localizar Cuenta por Número de Cuenta o PAN
        String identificador = req.cuentaOrigen() != null ? req.cuentaOrigen() : req.pan();
        Optional<CuentaEntity> optCuenta = cuentaRepository.findByNumeroCuentaOrPan(identificador, identificador);
        if (optCuenta.isEmpty()) {
            log.warn("Cuenta o Tarjeta no encontrada: {}", identificador);
            registrarTransaccionFallida(req, identificador, IsoResponseCode.CUENTA_INVALIDA);
            return TransaccionCoreResponse.rechazada(
                    req.rrn(), IsoResponseCode.CUENTA_INVALIDA, "Cuenta o Tarjeta no existe en el Core Bancario",
                    BigDecimal.ZERO, BigDecimal.ZERO
            );
        }

        CuentaEntity cuenta = optCuenta.get();
        BigDecimal saldoInicial = cuenta.getSaldoDisponible();

        // 3. Validar Estado de la Cuenta
        if ("BLOCKED".equalsIgnoreCase(cuenta.getEstado())) {
            log.warn("Transacción rechazada: Cuenta/Tarjeta BLOQUEADA: {}", cuenta.getNumeroCuenta());
            registrarTransaccionFallida(req, cuenta.getNumeroCuenta(), IsoResponseCode.TARJETA_BLOQUEADA);
            return TransaccionCoreResponse.rechazada(
                    req.rrn(), IsoResponseCode.TARJETA_BLOQUEADA, "Cuenta o Tarjeta Bloqueada",
                    cuenta.getSaldoDisponible(), cuenta.getSaldoContable()
            );
        }

        if ("EXPIRED".equalsIgnoreCase(cuenta.getEstado())) {
            log.warn("Transacción rechazada: Tarjeta VENCIDA: {}", cuenta.getNumeroCuenta());
            registrarTransaccionFallida(req, cuenta.getNumeroCuenta(), IsoResponseCode.TARJETA_EXPIRADA);
            return TransaccionCoreResponse.rechazada(
                    req.rrn(), IsoResponseCode.TARJETA_EXPIRADA, "Tarjeta Vencida / Expirada",
                    cuenta.getSaldoDisponible(), cuenta.getSaldoContable()
            );
        }

        // 4. Validar Límite Diario para Retiros en Cajero
        if ("RETIRO".equalsIgnoreCase(req.tipoOperacion())) {
            BigDecimal totalRetiros = cuenta.getRetiradoHoy().add(req.monto());
            if (totalRetiros.compareTo(cuenta.getLimiteDiario()) > 0) {
                log.warn("Transacción rechazada: Excede límite diario de retiro: {} > {}", totalRetiros, cuenta.getLimiteDiario());
                registrarTransaccionFallida(req, cuenta.getNumeroCuenta(), IsoResponseCode.LIMITE_EXCEDIDO);
                return TransaccionCoreResponse.rechazada(
                        req.rrn(), IsoResponseCode.LIMITE_EXCEDIDO, "Supera el Límite Diario de Retiros",
                        cuenta.getSaldoDisponible(), cuenta.getSaldoContable()
                );
            }
        }

        // 5. Validar Fondos Disponibles para Débitos
        boolean esDebito = "COMPRA".equalsIgnoreCase(req.tipoOperacion()) || "RETIRO".equalsIgnoreCase(req.tipoOperacion());
        if (esDebito) {
            if (cuenta.getSaldoDisponible().compareTo(req.monto()) < 0) {
                log.warn("Transacción rechazada: Fondos insuficientes: saldo={} monto={}", cuenta.getSaldoDisponible(), req.monto());
                registrarTransaccionFallida(req, cuenta.getNumeroCuenta(), IsoResponseCode.FONDOS_INSUFICIENTES);
                return TransaccionCoreResponse.rechazada(
                        req.rrn(), IsoResponseCode.FONDOS_INSUFICIENTES, "Fondos Insuficientes en la Cuenta",
                        cuenta.getSaldoDisponible(), cuenta.getSaldoContable()
                );
            }

            // Aplicar Débito
            cuenta.setSaldoDisponible(cuenta.getSaldoDisponible().subtract(req.monto()));
            cuenta.setSaldoContable(cuenta.getSaldoContable().subtract(req.monto()));
            if ("RETIRO".equalsIgnoreCase(req.tipoOperacion())) {
                cuenta.setRetiradoHoy(cuenta.getRetiradoHoy().add(req.monto()));
            }
        } else if ("REVERSA".equalsIgnoreCase(req.tipoOperacion()) || "DEVOLUCION".equalsIgnoreCase(req.tipoOperacion())) {
            // Aplicar Crédito / Reintegro
            cuenta.setSaldoDisponible(cuenta.getSaldoDisponible().add(req.monto()));
            cuenta.setSaldoContable(cuenta.getSaldoContable().add(req.monto()));
            if (cuenta.getRetiradoHoy().compareTo(req.monto()) >= 0) {
                cuenta.setRetiradoHoy(cuenta.getRetiradoHoy().subtract(req.monto()));
            }
        }

        cuentaRepository.save(cuenta);

        // 6. Generar Código de Autorización y Persistir Transacción Exitosa
        String authCode = String.valueOf(authCodeSeq.getAndIncrement());
        TransaccionEntity tx = new TransaccionEntity(
                req.rrn(),
                req.stan(),
                cuenta.getNumeroCuenta(),
                req.monto(),
                req.tipoOperacion(),
                saldoInicial,
                cuenta.getSaldoDisponible(),
                IsoResponseCode.APROBADA,
                authCode,
                req.terminalId(),
                LocalDateTime.now()
        );
        transaccionRepository.save(tx);

        log.info("Transacción aprobada: Auth={} SaldoPosterior={}", authCode, cuenta.getSaldoDisponible());
        return TransaccionCoreResponse.aprobada(req.rrn(), authCode, cuenta.getSaldoDisponible(), cuenta.getSaldoContable());
    }

    private void registrarTransaccionFallida(TransaccionCoreRequest req, String cuenta, String codigoRespuesta) {
        TransaccionEntity tx = new TransaccionEntity(
                req.rrn(),
                req.stan(),
                cuenta != null ? cuenta : "UNKNOWN",
                req.monto(),
                req.tipoOperacion(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                codigoRespuesta,
                null,
                req.terminalId(),
                LocalDateTime.now()
        );
        transaccionRepository.save(tx);
    }

    public List<CuentaDto> listarCuentas() {
        return cuentaRepository.findAll().stream()
                .map(c -> new CuentaDto(
                        c.getNumeroCuenta(), c.getTitular(), c.getPan(),
                        c.getSaldoDisponible(), c.getSaldoContable(),
                        c.getLimiteDiario(), c.getRetiradoHoy(), c.getEstado(),
                        c.getPin(), c.getBanco()
                )).toList();
    }

    public Optional<CuentaDto> obtenerCuenta(String id) {
        return cuentaRepository.findByNumeroCuentaOrPan(id, id)
                .map(c -> new CuentaDto(
                        c.getNumeroCuenta(), c.getTitular(), c.getPan(),
                        c.getSaldoDisponible(), c.getSaldoContable(),
                        c.getLimiteDiario(), c.getRetiradoHoy(), c.getEstado(),
                        c.getPin(), c.getBanco()
                ));
    }

    public List<TransaccionEntity> listarTransacciones() {
        return transaccionRepository.findAll();
    }

    @Transactional
    public Optional<CuentaDto> recargarSaldo(String id, BigDecimal monto) {
        return cuentaRepository.findByNumeroCuentaOrPan(id, id).map(cuenta -> {
            cuenta.setSaldoDisponible(cuenta.getSaldoDisponible().add(monto));
            cuenta.setSaldoContable(cuenta.getSaldoContable().add(monto));
            cuentaRepository.save(cuenta);
            log.info("Recarga aplicada a cuenta {}: +Q. {}", id, monto);
            return new CuentaDto(
                    cuenta.getNumeroCuenta(), cuenta.getTitular(), cuenta.getPan(),
                    cuenta.getSaldoDisponible(), cuenta.getSaldoContable(),
                    cuenta.getLimiteDiario(), cuenta.getRetiradoHoy(), cuenta.getEstado(),
                    cuenta.getPin(), cuenta.getBanco()
            );
        });
    }

    @Transactional
    public Optional<CuentaDto> toggleBloqueo(String id) {
        return cuentaRepository.findByNumeroCuentaOrPan(id, id).map(cuenta -> {
            String nuevoEstado = "BLOCKED".equalsIgnoreCase(cuenta.getEstado()) ? "ACTIVE" : "BLOCKED";
            cuenta.setEstado(nuevoEstado);
            cuentaRepository.save(cuenta);
            log.info("Estado de cuenta {} cambiado a {}", id, nuevoEstado);
            return new CuentaDto(
                    cuenta.getNumeroCuenta(), cuenta.getTitular(), cuenta.getPan(),
                    cuenta.getSaldoDisponible(), cuenta.getSaldoContable(),
                    cuenta.getLimiteDiario(), cuenta.getRetiradoHoy(), cuenta.getEstado(),
                    cuenta.getPin(), cuenta.getBanco()
            );
        });
    }

    @Transactional
    public CuentaDto crearCuenta(com.banco.recolector.corebancario.CrearCuentaRequest req) {
        String titular = (req.titular() != null && !req.titular().isBlank()) ? req.titular().trim().toUpperCase() : "CLIENTE GUATEMALA";
        
        String numeroCuenta = (req.numeroCuenta() != null && !req.numeroCuenta().isBlank()) 
                ? req.numeroCuenta().trim() 
                : String.format("1001-2002-%04d", (int)(Math.random() * 9000) + 1000);

        String pan = (req.pan() != null && !req.pan().isBlank())
                ? req.pan().replaceAll("\\s+", "")
                : String.format("4532%012d", (long)(Math.random() * 900000000000L) + 100000000000L);

        BigDecimal saldo = req.saldoInicial() != null ? req.saldoInicial() : new BigDecimal("1000.00");
        BigDecimal limite = req.limiteDiario() != null ? req.limiteDiario() : new BigDecimal("500.00");
        String estado = (req.estado() != null && !req.estado().isBlank()) ? req.estado().toUpperCase() : "ACTIVE";
        String pin = (req.pin() != null && !req.pin().isBlank()) ? req.pin().trim() : "1234";
        String banco = (req.banco() != null && !req.banco().isBlank()) ? req.banco().trim().toUpperCase() : "BANCO INDUSTRIAL";

        if (cuentaRepository.findByNumeroCuenta(numeroCuenta).isPresent()) {
            throw new IllegalArgumentException("Ya existe una cuenta con el número: " + numeroCuenta);
        }
        if (cuentaRepository.findByPan(pan).isPresent()) {
            throw new IllegalArgumentException("Ya existe una tarjeta con el PAN: " + pan);
        }

        CuentaEntity entity = new CuentaEntity(
                numeroCuenta, pan, titular, saldo, saldo, limite, BigDecimal.ZERO, estado, pin, banco
        );
        cuentaRepository.save(entity);
        log.info("Nueva cuenta bancaria creada: {} Titular: {} PAN: {} Banco: {}", numeroCuenta, titular, pan, banco);

        return new CuentaDto(
                entity.getNumeroCuenta(), entity.getTitular(), entity.getPan(),
                entity.getSaldoDisponible(), entity.getSaldoContable(),
                entity.getLimiteDiario(), entity.getRetiradoHoy(), entity.getEstado(),
                entity.getPin(), entity.getBanco()
        );
    }

    @Transactional
    public Optional<CuentaDto> editarCuenta(String id, com.banco.recolector.corebancario.EditarCuentaRequest req) {
        return cuentaRepository.findByNumeroCuentaOrPan(id, id).map(c -> {
            if (req.titular() != null && !req.titular().isBlank()) {
                c.setTitular(req.titular().trim().toUpperCase());
            }
            if (req.limiteDiario() != null) {
                c.setLimiteDiario(req.limiteDiario());
            }
            if (req.saldoDisponible() != null) {
                c.setSaldoDisponible(req.saldoDisponible());
                c.setSaldoContable(req.saldoDisponible());
            }
            if (req.estado() != null && !req.estado().isBlank()) {
                c.setEstado(req.estado().toUpperCase());
            }
            if (req.pin() != null && !req.pin().isBlank()) {
                c.setPin(req.pin().trim());
            }
            if (req.banco() != null && !req.banco().isBlank()) {
                c.setBanco(req.banco().trim().toUpperCase());
            }
            cuentaRepository.save(c);
            log.info("Cuenta {} editada con éxito.", id);
            return new CuentaDto(
                    c.getNumeroCuenta(), c.getTitular(), c.getPan(),
                    c.getSaldoDisponible(), c.getSaldoContable(),
                    c.getLimiteDiario(), c.getRetiradoHoy(), c.getEstado(),
                    c.getPin(), c.getBanco()
            );
        });
    }

    @Transactional
    public boolean eliminarCuenta(String id) {
        Optional<CuentaEntity> opt = cuentaRepository.findByNumeroCuentaOrPan(id, id);
        if (opt.isPresent()) {
            cuentaRepository.delete(opt.get());
            log.info("Cuenta {} eliminada del Core Bancario.", id);
            return true;
        }
        return false;
    }

    @Transactional
    public void resetCuentas() {
        transaccionRepository.deleteAll();
        List<CuentaEntity> cuentas = cuentaRepository.findAll();
        for (CuentaEntity c : cuentas) {
            c.setRetiradoHoy(BigDecimal.ZERO);
            cuentaRepository.save(c);
        }
        log.info("Reset completado en SQL Server: acumulados de retiro diario restablecidos a 0 y transacciones vaciadas.");
    }
}
