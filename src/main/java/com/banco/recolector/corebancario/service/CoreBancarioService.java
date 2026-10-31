package com.banco.recolector.corebancario.service;

import com.banco.recolector.corebancario.CrearCuentaRequest;
import com.banco.recolector.corebancario.CuentaDto;
import com.banco.recolector.corebancario.EditarCuentaRequest;
import com.banco.recolector.corebancario.TransaccionCoreRequest;
import com.banco.recolector.corebancario.TransaccionCoreResponse;
import com.banco.recolector.corebancario.entity.CuentaEntity;
import com.banco.recolector.corebancario.entity.TransaccionEntity;
import com.banco.recolector.corebancario.repository.CuentaRepository;
import com.banco.recolector.corebancario.repository.TransaccionRepository;
import com.banco.recolector.iso.PinBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.banco.recolector.corebancario.TransaccionCoreRequest.*;
import static com.banco.recolector.iso.IsoResponseCode.*;

/**
 * Lógica de negocio del Core Bancario: valida cuentas, PIN, saldos y límites, y registra cada
 * débito o crédito en la tabla de transacciones.
 *
 * <p>Concurrencia: las cuentas involucradas se bloquean con un bloqueo pesimista (en orden de id para
 * evitar interbloqueos) y la restricción única (RRN, STAN) garantiza que una transacción se aplique una
 * sola vez aunque lleguen duplicados simultáneos.
 */
@Service
public class CoreBancarioService {

    private static final Logger log = LoggerFactory.getLogger(CoreBancarioService.class);

    private static final String ACTIVA = "ACTIVE";
    private static final String BLOQUEADA = "BLOCKED";
    private static final String VENCIDA = "EXPIRED";
    private static final Set<String> REQUIEREN_PIN = Set.of(RETIRO, CONSULTA, TRANSFERENCIA);
    private static final int MAX_INTENTOS_PIN = 3;
    private static final int MAX_LISTADO = 1000;

    /** Cuentas de demostración (las mismas de init_sqlserver.sql): POST /reset las devuelve a este estado. */
    private record CuentaSemilla(String numeroCuenta, String pan, String titular, String saldo, String limite,
                                 String estado, String pin, String banco) {}

    private static final List<CuentaSemilla> SEMILLAS = List.of(
            new CuentaSemilla("1001-2002-3001", "4532000000000001", "CARLOS PEREZ", "1500.00", "500.00", ACTIVA, "1234", "BANCO INDUSTRIAL"),
            new CuentaSemilla("1001-2002-3002", "5412000000000002", "MARIA GOMEZ", "350.00", "300.00", ACTIVA, "4321", "G&T CONTINENTAL"),
            new CuentaSemilla("1001-2002-3003", "4000000000000003", "JUAN LOPEZ", "500.00", "200.00", VENCIDA, "2222", "BANRURAL"),
            new CuentaSemilla("1001-2002-3004", "4000000000000004", "ANA MORALES", "1000.00", "500.00", BLOQUEADA, "9999", "BAC CREDOMATIC"),
            new CuentaSemilla("1001-2002-3005", "4000000000000005", "PEDRO RAMOS", "10.00", "100.00", ACTIVA, "1111", "BANCO PROMERICA"));

    private final CuentaRepository cuentaRepository;
    private final TransaccionRepository transaccionRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventos;

    private final AtomicLong secuenciaAutorizacion = new AtomicLong(-1);
    private final ReentrantLock lockSecuencia = new ReentrantLock();

    public CoreBancarioService(CuentaRepository cuentaRepository,
                               TransaccionRepository transaccionRepository,
                               PlatformTransactionManager transactionManager,
                               ApplicationEventPublisher eventos) {
        this.cuentaRepository = cuentaRepository;
        this.transaccionRepository = transaccionRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.eventos = eventos;
    }

    // =====================================================================================
    // Procesamiento transaccional
    // =====================================================================================

    public TransaccionCoreResponse procesar(TransaccionCoreRequest req) {
        try {
            return transactionTemplate.execute(status -> procesarEnTransaccion(req));
        } catch (DataIntegrityViolationException duplicada) {
            // El RRN/STAN ya estaba registrado (retransmisión o duplicado simultáneo): todo lo hecho en esta
            // transacción se revirtió y se devuelve el resultado original sin reaplicarlo
            TransaccionCoreResponse previa = transactionTemplate.execute(status ->
                    transaccionRepository.findByRrnAndStan(req.rrn(), req.stan())
                            .map(this::respuestaIdempotente)
                            .orElse(null));
            if (previa == null) {
                throw duplicada;
            }
            log.info("Transacción duplicada (idempotencia del Core por RRN/STAN): RRN={} STAN={}", req.rrn(), req.stan());
            return previa;
        }
    }

    private TransaccionCoreResponse procesarEnTransaccion(TransaccionCoreRequest req) {
        String tipo = req.tipoOperacion().toUpperCase(Locale.ROOT);
        String identificador = (req.cuentaOrigen() != null && !req.cuentaOrigen().isBlank()) ? req.cuentaOrigen() : req.pan();
        boolean esTransferencia = TRANSFERENCIA.equals(tipo);

        // 1. Bloqueo pesimista de las cuentas involucradas
        CuentaEntity cuenta;
        CuentaEntity destino = null;
        if (esTransferencia) {
            // Dos cuentas: se bloquean siempre en orden ascendente de id para evitar interbloqueos
            Optional<Long> idOrigen = resolverId(identificador);
            Optional<Long> idDestino = resolverId(req.cuentaDestino());
            Map<Long, CuentaEntity> bloqueadas = bloquear(Stream.of(idOrigen, idDestino)
                    .flatMap(Optional::stream).distinct().sorted().toList());
            cuenta = idOrigen.map(bloqueadas::get).orElse(null);
            destino = idDestino.map(bloqueadas::get).orElse(null);
        } else {
            // Una cuenta: búsqueda y bloqueo en una sola consulta
            cuenta = bloquearCuenta(identificador).orElse(null);
        }

        // 2. Idempotencia: la garantiza la restricción única (RRN, STAN). Ante un duplicado el INSERT falla,
        //    se revierte la transacción completa y procesar() devuelve la respuesta original.

        // 3. Validaciones de cuenta, tarjeta y PIN
        if (cuenta == null) {
            return rechazar(req, tipo, identificador, null, CUENTA_INVALIDA, "Cuenta o tarjeta no existe en el Core Bancario");
        }
        if (req.pan() != null && !req.pan().isBlank() && !req.pan().equals(cuenta.getPan())) {
            return rechazar(req, tipo, cuenta.getNumeroCuenta(), cuenta, CUENTA_INVALIDA, "La tarjeta no corresponde a la cuenta");
        }
        if (BLOQUEADA.equalsIgnoreCase(cuenta.getEstado())) {
            return rechazar(req, tipo, cuenta.getNumeroCuenta(), cuenta, TARJETA_BLOQUEADA, "Cuenta o tarjeta bloqueada");
        }
        if (VENCIDA.equalsIgnoreCase(cuenta.getEstado())) {
            return rechazar(req, tipo, cuenta.getNumeroCuenta(), cuenta, TARJETA_EXPIRADA, "Tarjeta vencida");
        }
        boolean traePin = req.pinBlock() != null && !req.pinBlock().isBlank();
        if (REQUIEREN_PIN.contains(tipo) || traePin) {
            String rechazoPin = verificarPin(cuenta, req.pinBlock());
            if (rechazoPin != null) {
                String motivo = INTENTOS_PIN_EXCEDIDOS.equals(rechazoPin)
                        ? "Intentos de PIN excedidos: tarjeta bloqueada" : "PIN incorrecto o ausente";
                return rechazar(req, tipo, cuenta.getNumeroCuenta(), cuenta, rechazoPin, motivo);
            }
        }

        // 4. Operación
        BigDecimal monto = req.monto() != null ? req.monto() : BigDecimal.ZERO;
        return switch (tipo) {
            case COMPRA -> debitar(req, tipo, cuenta, monto, false);
            case RETIRO -> debitar(req, tipo, cuenta, monto, true);
            case CONSULTA -> aprobar(req, tipo, cuenta, null, BigDecimal.ZERO, cuenta.getSaldoDisponible());
            case TRANSFERENCIA -> transferir(req, cuenta, destino, monto);
            case DEVOLUCION -> acreditarContraOriginal(req, tipo, cuenta, monto, List.of(COMPRA));
            case REVERSA -> acreditarContraOriginal(req, tipo, cuenta, monto, List.of(COMPRA, RETIRO));
            default -> rechazar(req, tipo, cuenta.getNumeroCuenta(), cuenta, TRANSACCION_INVALIDA,
                    "Tipo de operación no soportado: " + tipo);
        };
    }

    private TransaccionCoreResponse debitar(TransaccionCoreRequest req, String tipo, CuentaEntity cuenta,
                                            BigDecimal monto, boolean esRetiro) {
        if (monto.signum() <= 0) {
            return rechazar(req, tipo, cuenta.getNumeroCuenta(), cuenta, MONTO_INVALIDO, "El monto debe ser mayor que cero");
        }
        if (esRetiro) {
            LocalDate hoy = LocalDate.now();
            if (!hoy.equals(cuenta.getFechaUltimoRetiro())) {
                // Nuevo día: el acumulado de retiros vuelve a cero
                cuenta.setRetiradoHoy(BigDecimal.ZERO);
                cuenta.setFechaUltimoRetiro(hoy);
            }
            if (cuenta.getRetiradoHoy().add(monto).compareTo(cuenta.getLimiteDiario()) > 0) {
                return rechazar(req, tipo, cuenta.getNumeroCuenta(), cuenta, LIMITE_EXCEDIDO, "Supera el límite diario de retiros");
            }
        }
        if (cuenta.getSaldoDisponible().compareTo(monto) < 0) {
            return rechazar(req, tipo, cuenta.getNumeroCuenta(), cuenta, FONDOS_INSUFICIENTES, "Fondos insuficientes");
        }
        BigDecimal saldoAnterior = cuenta.getSaldoDisponible();
        cuenta.aplicarMovimiento(monto.negate());
        if (esRetiro) {
            cuenta.setRetiradoHoy(cuenta.getRetiradoHoy().add(monto));
        }
        return aprobar(req, tipo, cuenta, null, monto, saldoAnterior);
    }

    private TransaccionCoreResponse transferir(TransaccionCoreRequest req, CuentaEntity origen, CuentaEntity destino,
                                               BigDecimal monto) {
        if (monto.signum() <= 0) {
            return rechazar(req, TRANSFERENCIA, origen.getNumeroCuenta(), origen, MONTO_INVALIDO, "El monto debe ser mayor que cero");
        }
        if (destino == null || !ACTIVA.equalsIgnoreCase(destino.getEstado())) {
            return rechazar(req, TRANSFERENCIA, origen.getNumeroCuenta(), origen, CUENTA_INVALIDA, "Cuenta destino inexistente o inactiva");
        }
        if (destino.getId().equals(origen.getId())) {
            return rechazar(req, TRANSFERENCIA, origen.getNumeroCuenta(), origen, TRANSACCION_INVALIDA, "La cuenta destino es la misma cuenta origen");
        }
        if (origen.getSaldoDisponible().compareTo(monto) < 0) {
            return rechazar(req, TRANSFERENCIA, origen.getNumeroCuenta(), origen, FONDOS_INSUFICIENTES, "Fondos insuficientes");
        }
        BigDecimal saldoAnterior = origen.getSaldoDisponible();
        origen.aplicarMovimiento(monto.negate());
        destino.aplicarMovimiento(monto);
        return aprobar(req, TRANSFERENCIA, origen, destino.getNumeroCuenta(), monto, saldoAnterior);
    }

    /**
     * Reversa (0420) o devolución: acredita contra una transacción original aprobada con el mismo RRN,
     * sin superar el monto pendiente de esa transacción.
     */
    private TransaccionCoreResponse acreditarContraOriginal(TransaccionCoreRequest req, String tipo, CuentaEntity cuenta,
                                                           BigDecimal monto, List<String> tiposOriginales) {
        List<TransaccionEntity> originales =
                transaccionRepository.buscarOriginalesAprobadas(req.rrn(), cuenta.getNumeroCuenta(), tiposOriginales);
        if (originales.isEmpty()) {
            return rechazar(req, tipo, cuenta.getNumeroCuenta(), cuenta, ORIGINAL_NO_ENCONTRADA,
                    "No existe una transacción original aprobada con RRN " + req.rrn());
        }
        TransaccionEntity original = originales.getFirst();
        BigDecimal yaAplicado = transaccionRepository.sumarAprobadas(req.rrn(), cuenta.getNumeroCuenta(), tipo);
        BigDecimal pendiente = original.getMonto().subtract(yaAplicado);
        BigDecimal aAcreditar = monto.signum() > 0 ? monto : pendiente;
        if (aAcreditar.signum() <= 0 || aAcreditar.compareTo(pendiente) > 0) {
            return rechazar(req, tipo, cuenta.getNumeroCuenta(), cuenta, MONTO_INVALIDO,
                    "El monto excede lo pendiente de la transacción original (" + pendiente + ")");
        }
        BigDecimal saldoAnterior = cuenta.getSaldoDisponible();
        cuenta.aplicarMovimiento(aAcreditar);
        if (REVERSA.equals(tipo) && RETIRO.equals(original.getTipoOperacion())
                && LocalDate.now().equals(cuenta.getFechaUltimoRetiro())) {
            cuenta.setRetiradoHoy(cuenta.getRetiradoHoy().subtract(aAcreditar).max(BigDecimal.ZERO));
        }
        return aprobar(req, tipo, cuenta, null, aAcreditar, saldoAnterior);
    }

    /** @return null si el PIN es correcto; 55 si es incorrecto o falta; 75 si se excedieron los intentos. */
    private String verificarPin(CuentaEntity cuenta, String pinBlock) {
        if (pinBlock == null || pinBlock.isBlank()) {
            return PIN_INCORRECTO;
        }
        boolean correcto = PinBlock.extraerPin(pinBlock, cuenta.getPan())
                .map(pin -> PinHasher.coincide(cuenta.getNumeroCuenta(), pin, cuenta.getPinHash()))
                .orElse(false);
        if (correcto) {
            if (cuenta.getIntentosPinFallidos() != 0) {
                cuenta.setIntentosPinFallidos(0);
            }
            return null;
        }
        int intentos = cuenta.getIntentosPinFallidos() + 1;
        cuenta.setIntentosPinFallidos(intentos);
        if (intentos >= MAX_INTENTOS_PIN) {
            cuenta.setEstado(BLOQUEADA);
            log.warn("Tarjeta de la cuenta {} bloqueada por {} intentos de PIN fallidos", cuenta.getNumeroCuenta(), intentos);
            return INTENTOS_PIN_EXCEDIDOS;
        }
        return PIN_INCORRECTO;
    }

    private TransaccionCoreResponse aprobar(TransaccionCoreRequest req, String tipo, CuentaEntity cuenta,
                                            String cuentaDestino, BigDecimal monto, BigDecimal saldoAnterior) {
        String autorizacion = siguienteAutorizacion();
        transaccionRepository.saveAndFlush(new TransaccionEntity(
                req.rrn(), req.stan(), cuenta.getNumeroCuenta(), cuentaDestino, monto, tipo,
                saldoAnterior, cuenta.getSaldoDisponible(), APROBADA, autorizacion,
                req.terminalId(), LocalDateTime.now()));
        log.debug("Aprobada {} RRN={} STAN={} Auth={} Saldo={}", tipo, req.rrn(), req.stan(), autorizacion, cuenta.getSaldoDisponible());
        return TransaccionCoreResponse.aprobada(req.rrn(), autorizacion, cuenta.getSaldoDisponible(), cuenta.getSaldoContable());
    }

    private TransaccionCoreResponse rechazar(TransaccionCoreRequest req, String tipo, String numeroCuenta,
                                             CuentaEntity cuenta, String codigo, String motivo) {
        BigDecimal saldo = cuenta != null ? cuenta.getSaldoDisponible() : BigDecimal.ZERO;
        BigDecimal saldoContable = cuenta != null ? cuenta.getSaldoContable() : BigDecimal.ZERO;
        transaccionRepository.saveAndFlush(new TransaccionEntity(
                req.rrn(), req.stan(), recortar(numeroCuenta != null ? numeroCuenta : "DESCONOCIDA", 30),
                recortar(req.cuentaDestino(), 30), req.monto() != null ? req.monto() : BigDecimal.ZERO,
                recortar(tipo, 20), saldo, saldo, codigo, null, recortar(req.terminalId(), 30), LocalDateTime.now()));
        log.info("Rechazada {} RRN={} STAN={} DE39={}: {}", tipo, req.rrn(), req.stan(), codigo, motivo);
        return TransaccionCoreResponse.rechazada(req.rrn(), codigo, motivo, saldo, saldoContable);
    }

    private TransaccionCoreResponse respuestaIdempotente(TransaccionEntity te) {
        return new TransaccionCoreResponse(
                APROBADA.equals(te.getCodigoRespuesta()),
                te.getCodigoRespuesta(),
                te.getCodigoAutorizacion(),
                te.getSaldoPosterior(),
                te.getSaldoPosterior(),
                "Transacción previa recuperada por Idempotencia",
                te.getRrn(),
                te.getFechaHora().toString()
        );
    }

    /**
     * Código de autorización (DE 38) de 6 dígitos. La secuencia continúa desde el último código
     * guardado en la BD, por lo que no se repite tras reiniciar la aplicación.
     */
    private String siguienteAutorizacion() {
        if (secuenciaAutorizacion.get() < 0) {
            lockSecuencia.lock();
            try {
                if (secuenciaAutorizacion.get() < 0) {
                    String ultimo = transaccionRepository.ultimoCodigoAutorizacion();
                    long base = (ultimo != null && ultimo.matches("\\d{6}")) ? Long.parseLong(ultimo) : 100000L;
                    secuenciaAutorizacion.set(base);
                }
            } finally {
                lockSecuencia.unlock();
            }
        }
        long siguiente = secuenciaAutorizacion.incrementAndGet();
        return String.valueOf(100000 + Math.floorMod(siguiente - 100000, 900000));
    }

    private Optional<Long> resolverId(String identificador) {
        if (identificador == null || identificador.isBlank()) {
            return Optional.empty();
        }
        return cuentaRepository.buscarIdPorNumero(identificador)
                .or(() -> cuentaRepository.buscarIdsPorPan(identificador).stream().findFirst());
    }

    private Map<Long, CuentaEntity> bloquear(List<Long> idsOrdenados) {
        return idsOrdenados.stream()
                .map(cuentaRepository::bloquearPorId)
                .flatMap(Optional::stream)
                .collect(Collectors.toMap(CuentaEntity::getId, Function.identity()));
    }

    private static String recortar(String valor, int maximo) {
        return valor == null || valor.length() <= maximo ? valor : valor.substring(0, maximo);
    }

    // =====================================================================================
    // Administración de cuentas
    // =====================================================================================

    @Transactional(readOnly = true)
    public List<CuentaDto> listarCuentas() {
        return cuentaRepository.findAll(Sort.by("id")).stream().map(CoreBancarioService::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Optional<CuentaDto> obtenerCuenta(String id) {
        return buscarCuenta(id).map(CoreBancarioService::toDto);
    }

    @Transactional(readOnly = true)
    public List<TransaccionEntity> listarTransacciones(int limite) {
        return transaccionRepository.buscarRecientes(PageRequest.of(0, acotar(limite)));
    }

    @Transactional(readOnly = true)
    public Optional<List<TransaccionEntity>> transaccionesDeCuenta(String id, int limite) {
        return buscarCuenta(id).map(c -> transaccionRepository.buscarPorCuenta(c.getNumeroCuenta(), PageRequest.of(0, acotar(limite))));
    }

    @Transactional
    public Optional<CuentaDto> recargarSaldo(String id, BigDecimal monto) {
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto de la recarga debe ser mayor que cero");
        }
        return bloquearCuenta(id).map(cuenta -> {
            cuenta.aplicarMovimiento(monto);
            log.info("Recarga aplicada a cuenta {}: +Q. {}", cuenta.getNumeroCuenta(), monto);
            return toDto(cuenta);
        });
    }

    @Transactional
    public Optional<CuentaDto> toggleBloqueo(String id) {
        return bloquearCuenta(id).map(cuenta -> {
            boolean desbloquear = BLOQUEADA.equalsIgnoreCase(cuenta.getEstado());
            cuenta.setEstado(desbloquear ? ACTIVA : BLOQUEADA);
            if (desbloquear) {
                cuenta.setIntentosPinFallidos(0);
            }
            log.info("Estado de cuenta {} cambiado a {}", cuenta.getNumeroCuenta(), cuenta.getEstado());
            return toDto(cuenta);
        });
    }

    @Transactional
    public CuentaDto crearCuenta(CrearCuentaRequest req) {
        String titular = textoONulo(req.titular()) != null ? req.titular().trim().toUpperCase(Locale.ROOT) : "CLIENTE GUATEMALA";
        String numeroCuenta = textoONulo(req.numeroCuenta()) != null ? req.numeroCuenta().trim() : generarNumeroCuenta();
        String pan = textoONulo(req.pan()) != null ? req.pan().trim() : generarPan();
        BigDecimal saldo = req.saldoInicial() != null ? req.saldoInicial() : new BigDecimal("1000.00");
        BigDecimal limite = req.limiteDiario() != null ? req.limiteDiario() : new BigDecimal("500.00");
        String estado = textoONulo(req.estado()) != null ? req.estado().toUpperCase(Locale.ROOT) : ACTIVA;
        String pin = textoONulo(req.pin()) != null ? req.pin() : "1234";
        String banco = textoONulo(req.banco()) != null ? req.banco().trim().toUpperCase(Locale.ROOT) : "BANCO INDUSTRIAL";

        if (cuentaRepository.existsByNumeroCuenta(numeroCuenta)) {
            throw new IllegalArgumentException("Ya existe una cuenta con el número: " + numeroCuenta);
        }
        if (cuentaRepository.existsByPan(pan)) {
            throw new IllegalArgumentException("Ya existe una tarjeta con el PAN: " + pan);
        }

        CuentaEntity entity = cuentaRepository.save(new CuentaEntity(
                numeroCuenta, pan, titular, saldo, limite, estado, PinHasher.hash(numeroCuenta, pin), banco));
        log.info("Nueva cuenta creada: {} Titular: {} Banco: {}", numeroCuenta, titular, banco);
        return toDto(entity);
    }

    @Transactional
    public Optional<CuentaDto> editarCuenta(String id, EditarCuentaRequest req) {
        return bloquearCuenta(id).map(c -> {
            if (textoONulo(req.titular()) != null) {
                c.setTitular(req.titular().trim().toUpperCase(Locale.ROOT));
            }
            if (req.limiteDiario() != null) {
                c.setLimiteDiario(req.limiteDiario());
            }
            if (req.saldoDisponible() != null) {
                c.setSaldoDisponible(req.saldoDisponible());
                c.setSaldoContable(req.saldoDisponible());
            }
            if (textoONulo(req.estado()) != null) {
                c.setEstado(req.estado().toUpperCase(Locale.ROOT));
                if (ACTIVA.equals(c.getEstado())) {
                    c.setIntentosPinFallidos(0);
                }
            }
            if (textoONulo(req.pin()) != null) {
                c.setPinHash(PinHasher.hash(c.getNumeroCuenta(), req.pin()));
                c.setIntentosPinFallidos(0);
            }
            if (textoONulo(req.banco()) != null) {
                c.setBanco(req.banco().trim().toUpperCase(Locale.ROOT));
            }
            log.info("Cuenta {} editada con éxito.", c.getNumeroCuenta());
            return toDto(c);
        });
    }

    @Transactional
    public boolean eliminarCuenta(String id) {
        return bloquearCuenta(id).map(cuenta -> {
            cuentaRepository.delete(cuenta);
            log.info("Cuenta {} eliminada del Core Bancario.", cuenta.getNumeroCuenta());
            return true;
        }).orElse(false);
    }

    /**
     * Restablece el entorno de demostración: vacía las transacciones, reinicia acumulados e intentos de PIN
     * de todas las cuentas y devuelve las cuentas semilla a sus valores iniciales (recreándolas si se borraron).
     */
    @Transactional
    public void resetCuentas() {
        transaccionRepository.deleteAllInBatch();
        for (CuentaEntity c : cuentaRepository.findAll()) {
            c.setRetiradoHoy(BigDecimal.ZERO);
            c.setFechaUltimoRetiro(null);
            c.setIntentosPinFallidos(0);
        }
        for (CuentaSemilla semilla : SEMILLAS) {
            restaurarSemilla(semilla);
        }
        // El historial quedó vacío: las respuestas que el Recolector tenga guardadas ya no corresponden a nada
        eventos.publishEvent(new DatosRestauradosEvent());
        log.info("Reset completado: transacciones vaciadas, acumulados reiniciados y cuentas semilla restauradas.");
    }

    private void restaurarSemilla(CuentaSemilla s) {
        BigDecimal saldo = new BigDecimal(s.saldo());
        Optional<CuentaEntity> existente = cuentaRepository.findByNumeroCuenta(s.numeroCuenta());
        if (existente.isEmpty()) {
            if (cuentaRepository.existsByPan(s.pan())) {
                log.warn("No se recrea la cuenta semilla {}: su PAN ya pertenece a otra cuenta", s.numeroCuenta());
                return;
            }
            cuentaRepository.save(new CuentaEntity(s.numeroCuenta(), s.pan(), s.titular(), saldo,
                    new BigDecimal(s.limite()), s.estado(), PinHasher.hash(s.numeroCuenta(), s.pin()), s.banco()));
            return;
        }
        CuentaEntity c = existente.get();
        c.setTitular(s.titular());
        c.setSaldoDisponible(saldo);
        c.setSaldoContable(saldo);
        c.setLimiteDiario(new BigDecimal(s.limite()));
        c.setEstado(s.estado());
        c.setPinHash(PinHasher.hash(s.numeroCuenta(), s.pin()));
        c.setBanco(s.banco());
    }

    private Optional<CuentaEntity> buscarCuenta(String id) {
        return resolverId(id).flatMap(cuentaRepository::findById);
    }

    /** Busca y bloquea la cuenta por número (una sola consulta) o, si no existe, por PAN. */
    private Optional<CuentaEntity> bloquearCuenta(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return cuentaRepository.bloquearPorNumero(id)
                .or(() -> cuentaRepository.buscarIdsPorPan(id).stream().findFirst().flatMap(cuentaRepository::bloquearPorId));
    }

    private String generarNumeroCuenta() {
        String numero;
        do {
            numero = String.format("1001-2002-%04d", ThreadLocalRandom.current().nextInt(1000, 10000));
        } while (cuentaRepository.existsByNumeroCuenta(numero));
        return numero;
    }

    private String generarPan() {
        String pan;
        do {
            pan = "4532" + String.format("%012d", ThreadLocalRandom.current().nextLong(100_000_000_000L, 1_000_000_000_000L));
        } while (cuentaRepository.existsByPan(pan));
        return pan;
    }

    private static int acotar(int limite) {
        return Math.clamp(limite, 1, MAX_LISTADO);
    }

    private static String textoONulo(String valor) {
        return valor == null || valor.isBlank() ? null : valor;
    }

    static CuentaDto toDto(CuentaEntity c) {
        BigDecimal retiradoHoy = LocalDate.now().equals(c.getFechaUltimoRetiro()) ? c.getRetiradoHoy() : BigDecimal.ZERO;
        return new CuentaDto(
                c.getNumeroCuenta(), c.getTitular(), c.getPan(),
                c.getSaldoDisponible(), c.getSaldoContable(),
                c.getLimiteDiario(), retiradoHoy, c.getEstado(), c.getBanco());
    }
}
