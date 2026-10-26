package com.banco.recolector.iso.controller;

import com.banco.recolector.config.TcpServerProperties;
import com.banco.recolector.corebancario.CoreBancarioClient;
import com.banco.recolector.corebancario.CuentaDto;
import com.banco.recolector.iso.IsoMessageService;
import com.banco.recolector.iso.IsoResponseCode;
import com.banco.recolector.iso.IsoTransactionDto;
import com.banco.recolector.service.RecolectorTransaccionalService;
import com.banco.recolector.service.TcpIsoHealthIndicator;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Simulador Interactivo Web ISO 8583 (Cajero ATM / POS).
 * Construye la trama ISO, la despacha por el mismo orquestador que atiende el socket TCP y devuelve el
 * desglose del Bitmap, los Data Elements enviados/recibidos y el estado de la cuenta consultado al Core
 * Bancario mediante el RestClient.
 */
@RestController
@RequestMapping("/api/iso")
public class IsoSimuladorController {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final IsoMessageService isoService;
    private final RecolectorTransaccionalService recolectorService;
    private final CoreBancarioClient coreClient;
    private final TcpIsoHealthIndicator tcpServidor;
    private final TcpServerProperties tcpProps;

    public IsoSimuladorController(
            IsoMessageService isoService,
            RecolectorTransaccionalService recolectorService,
            CoreBancarioClient coreClient,
            TcpIsoHealthIndicator tcpServidor,
            TcpServerProperties tcpProps
    ) {
        this.isoService = isoService;
        this.recolectorService = recolectorService;
        this.coreClient = coreClient;
        this.tcpServidor = tcpServidor;
        this.tcpProps = tcpProps;
    }

    public record SimulacionRequest(
            String mti,
            String pan,
            String cuenta,
            BigDecimal monto,
            String processingCode,
            String terminalId,
            String stan,
            String rrn,
            String moneda, // GTQ (320) o USD (840)
            String pin,
            String cuentaDestino
    ) {}

    public record SimulacionResponse(
            boolean exitosa,
            String mtiSolicitud,
            String mtiRespuesta,
            String rawRequestAscii,
            String rawResponseAscii,
            String codigoRespuestaIso,
            String descripcionCodigo,
            String codigoAutorizacion,
            String panEnmascarado,
            String cuenta,
            String titular,
            String bancoEmisor,
            BigDecimal monto,
            BigDecimal saldoDisponible,
            BigDecimal saldoContable,
            String moneda,
            String rrn,
            String stan,
            long latenciaMs,
            String bitmapHex,
            String bitmapBinario,
            int totalBits,
            List<Integer> bitsActivos,
            Map<String, String> dataElementsEnviados,
            Map<String, String> dataElementsRecibidos,
            String timestamp
    ) {}

    @PostMapping("/simular")
    public ResponseEntity<SimulacionResponse> simularTransaccion(@RequestBody SimulacionRequest req) {
        long startTime = System.currentTimeMillis();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        String mti = texto(req.mti(), "0200");
        String cuenta = texto(req.cuenta(), "1001-2002-3001");
        BigDecimal monto = req.monto() != null ? req.monto() : new BigDecimal("100.00");
        String procCode = texto(req.processingCode(), "010000"); // Por defecto retiro ATM
        String terminalId = texto(req.terminalId(), "ATM5B001");
        String stan = texto(req.stan(), String.format("%06d", random.nextInt(100000, 1000000)));
        String rrn = texto(req.rrn(), "2627" + String.format("%08d", random.nextInt(10000000, 100000000)));
        String moneda = texto(req.moneda(), "GTQ");
        String codigoMoneda = "USD".equalsIgnoreCase(moneda) ? "840" : "320";

        // El PAN identifica la tarjeta: si no se envía se usa el de la cuenta (necesario para el PIN block)
        Optional<CuentaDto> cuentaAntes = Optional.empty();
        String pan = req.pan() != null && !req.pan().isBlank() ? req.pan().trim() : null;
        if (pan == null && !"0800".equals(mti)) {
            cuentaAntes = coreClient.consultarCuenta(cuenta);
            pan = cuentaAntes.map(CuentaDto::pan).orElse(null);
        }

        IsoMessage requestIso = switch (mti) {
            case "0800" -> {
                IsoMessage echo = isoService.getMessageFactory().newMessage(0x0800);
                echo.setValue(11, stan, IsoType.NUMERIC, 6);
                echo.setValue(70, "301", IsoType.NUMERIC, 3);
                yield echo;
            }
            case "0100" -> isoService.crearSolicitud(0x0100, pan, "300000", null, stan, rrn, terminalId, cuenta);
            case "0420" -> isoService.crearSolicitud(0x0420, pan, procCode, monto, stan, rrn, terminalId, cuenta);
            default -> isoService.crearSolicitud(0x0200, pan, procCode, monto, stan, rrn, terminalId, cuenta);
        };
        if (!"0800".equals(mti)) {
            requestIso.setValue(49, codigoMoneda, IsoType.NUMERIC, 3);
            if (req.pin() != null && !req.pin().isBlank() && pan != null && !"0420".equals(mti)) {
                isoService.agregarPinBlock(requestIso, req.pin().trim(), pan);
            }
            if (req.cuentaDestino() != null && !req.cuentaDestino().isBlank() && "0200".equals(mti)) {
                requestIso.setValue(103, req.cuentaDestino().trim(), IsoType.LLVAR, 0);
            }
        }

        String rawReq = isoService.encodeToString(requestIso);
        BitmapInfo bitmapInfo = calcularBitmap(requestIso);

        // Despacho por el mismo orquestador que atiende el socket TCP
        IsoMessage responseIso = recolectorService.procesarMensaje(requestIso);
        String rawResp = isoService.encodeToString(responseIso);
        long latencia = System.currentTimeMillis() - startTime;

        String rc = isoService.getFieldValue(responseIso, 39);
        String auth = isoService.getFieldValue(responseIso, 38);
        IsoTransactionDto dto = isoService.extractTransaction(requestIso);

        // Estado actual de la cuenta (vía RestClient) para la pantalla del ATM y el comprobante
        Optional<CuentaDto> cuentaDespues = "0800".equals(mti) ? cuentaAntes : coreClient.consultarCuenta(cuenta);

        SimulacionResponse resp = new SimulacionResponse(
                IsoResponseCode.APROBADA.equals(rc),
                isoService.tipoMti(requestIso),
                isoService.tipoMti(responseIso),
                enmascararTrama(rawReq, requestIso),
                enmascararTrama(rawResp, responseIso),
                rc != null ? rc : IsoResponseCode.ERROR_SISTEMA,
                IsoResponseCode.getDescripcion(rc),
                auth,
                dto.panEnmascarado(),
                cuenta,
                cuentaDespues.map(CuentaDto::titular).orElse("CLIENTE GUATEMALA"),
                cuentaDespues.map(CuentaDto::banco).orElse("BANCO INDUSTRIAL"),
                monto,
                cuentaDespues.map(CuentaDto::saldoDisponible).orElse(BigDecimal.ZERO),
                cuentaDespues.map(CuentaDto::saldoContable).orElse(BigDecimal.ZERO),
                moneda,
                rrn,
                stan,
                latencia,
                bitmapInfo.hex(),
                bitmapInfo.binario(),
                bitmapInfo.totalBits(),
                bitmapInfo.bitsActivos(),
                extraerMapaDataElements(requestIso),
                extraerMapaDataElements(responseIso),
                LocalDateTime.now().format(TIMESTAMP)
        );
        return ResponseEntity.ok(resp);
    }

    record BitmapInfo(String hex, String binario, int totalBits, List<Integer> bitsActivos) {}

    static BitmapInfo calcularBitmap(IsoMessage msg) {
        boolean hasSecondary = false;
        for (int i = 65; i <= 128; i++) {
            if (msg.hasField(i)) {
                hasSecondary = true;
                break;
            }
        }
        int numBits = hasSecondary ? 128 : 64;
        StringBuilder binBuilder = new StringBuilder();
        List<Integer> bitsActivos = new ArrayList<>();

        // Bit 1: indicador de bitmap secundario
        binBuilder.append(hasSecondary ? "1" : "0");
        if (hasSecondary) bitsActivos.add(1);

        for (int b = 2; b <= numBits; b++) {
            boolean active = msg.hasField(b);
            binBuilder.append(active ? "1" : "0");
            if (active) bitsActivos.add(b);
        }

        StringBuilder hexBuilder = new StringBuilder();
        for (int i = 0; i < binBuilder.length(); i += 4) {
            hexBuilder.append(Integer.toHexString(Integer.parseInt(binBuilder.substring(i, i + 4), 2)).toUpperCase());
        }
        return new BitmapInfo(hexBuilder.toString(), binBuilder.toString(), numBits, bitsActivos);
    }

    @GetMapping("/metricas")
    public ResponseEntity<Map<String, Object>> obtenerMetricas() {
        Runtime rt = Runtime.getRuntime();
        long totalMemMb = rt.totalMemory() / (1024 * 1024);
        long freeMemMb = rt.freeMemory() / (1024 * 1024);
        boolean nativo = "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("transaccionesProcesadas", recolectorService.getContadorProcesadas());
        metrics.put("transaccionesAprobadas", recolectorService.getContadorAprobadas());
        metrics.put("transaccionesRechazadas", recolectorService.getContadorRechazadas());
        metrics.put("tramasConErrorFormato", recolectorService.getContadorErroresFormato());
        metrics.put("tcpPuerto", tcpProps.port());
        metrics.put("tcpEstado", tcpServidor.escuchando() ? "ACTIVO (Listening)" : "INACTIVO");
        metrics.put("tcpConexionesAbiertas", tcpServidor.conexionesAbiertas());
        metrics.put("modoEjecucion", nativo ? "Binario nativo GraalVM Native Image" : "JVM " + Runtime.version());
        metrics.put("modoConcurrencia", "Virtual Threads (Project Loom)");
        metrics.put("recolectorBasura", ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).collect(Collectors.joining(", ")));
        metrics.put("memoriaUsadaMb", totalMemMb - freeMemMb);
        metrics.put("memoriaTotalMb", totalMemMb);
        metrics.put("memoriaRssMb", memoriaRssMb());
        metrics.put("tiempoActivoSegundos", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        metrics.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return ResponseEntity.ok(metrics);
    }

    /** Memoria residente real del proceso (Linux /proc/self/status); -1 si no está disponible. */
    static long memoriaRssMb() {
        try {
            return Files.readAllLines(Path.of("/proc/self/status")).stream()
                    .filter(l -> l.startsWith("VmRSS:"))
                    .map(l -> Long.parseLong(l.replaceAll("\\D", "")) / 1024)
                    .findFirst().orElse(-1L);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * Ráfaga de compras concurrentes (Q0.01) con Virtual Threads contra la cuenta de demostración.
     */
    @PostMapping("/rafaga")
    public ResponseEntity<Map<String, Object>> dispararRafagaVirtualThreads(@RequestParam(defaultValue = "50") int cantidad) {
        int txCount = Math.clamp(cantidad, 5, 200);
        List<Long> latencies = new CopyOnWriteArrayList<>();
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        String prefijoRrn = String.format("%04d", ThreadLocalRandom.current().nextInt(10000));

        long start = System.currentTimeMillis();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(txCount);
            for (int i = 0; i < txCount; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    long tStart = System.currentTimeMillis();
                    IsoMessage req = isoService.createFinancialRequest(
                            "4532000000000001", "000000", new BigDecimal("0.01"),
                            String.format("%06d", idx + 1), prefijoRrn + String.format("%08d", idx + 1),
                            "POS5B001", "1001-2002-3001");
                    String rc = isoService.getFieldValue(recolectorService.procesarMensaje(req), 39);
                    (IsoResponseCode.APROBADA.equals(rc) ? okCount : failCount).incrementAndGet();
                    latencies.add(System.currentTimeMillis() - tStart);
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }

        long totalDurationMs = System.currentTimeMillis() - start;
        double sec = totalDurationMs / 1000.0;
        List<Long> ordenadas = latencies.stream().sorted().toList();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("totalEnviadas", txCount);
        res.put("exitosas", okCount.get());
        res.put("fallidas", failCount.get());
        res.put("tiempoTotalMs", totalDurationMs);
        res.put("tpsEstimado", Math.round(okCount.get() / (sec > 0 ? sec : 1.0) * 100.0) / 100.0);
        res.put("latenciaP50Ms", ordenadas.get((int) (ordenadas.size() * 0.5)));
        res.put("latenciaP95Ms", ordenadas.get((int) (ordenadas.size() * 0.95)));
        res.put("concurrencia", "Virtual Threads (Loom)");
        return ResponseEntity.ok(res);
    }

    /** Desglose de Data Elements con el PAN enmascarado (PCI-DSS) y el PIN block oculto. */
    private Map<String, String> extraerMapaDataElements(IsoMessage msg) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 2; i <= 128; i++) {
            if (msg.hasField(i)) {
                String val = isoService.getFieldValue(msg, i);
                if (i == 2) val = enmascararPan(val);
                if (i == 52) val = "*".repeat(16);
                map.put("DE " + i, val);
            }
        }
        return map;
    }

    /** Trama para mostrar en pantalla: PAN enmascarado y PIN block oculto (misma longitud que la original). */
    private String enmascararTrama(String raw, IsoMessage msg) {
        String resultado = raw;
        String pan = isoService.getFieldValue(msg, 2);
        if (pan != null) resultado = resultado.replace(pan, enmascararPan(pan));
        String pinBlock = isoService.getFieldValue(msg, 52);
        if (pinBlock != null) resultado = resultado.replace(pinBlock, "*".repeat(pinBlock.length()));
        return resultado;
    }

    private static String enmascararPan(String pan) {
        if (pan == null || pan.length() < 10) return pan;
        return pan.substring(0, 6) + "*".repeat(pan.length() - 10) + pan.substring(pan.length() - 4);
    }

    private static String texto(String valor, String porDefecto) {
        return valor != null && !valor.isBlank() ? valor.trim() : porDefecto;
    }
}
