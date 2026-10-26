package com.banco.recolector.iso.controller;

import com.banco.recolector.corebancario.CuentaDto;
import com.banco.recolector.corebancario.service.CoreBancarioService;
import com.banco.recolector.iso.IsoMessageService;
import com.banco.recolector.iso.IsoResponseCode;
import com.banco.recolector.iso.IsoTransactionDto;
import com.banco.recolector.service.RecolectorTransaccionalService;
import com.banco.recolector.service.TcpIsoServer;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controlador REST para el Simulador Interactivo Web ISO 8583 (Cajero ATM Guatemala / 5B).
 * Incluye desglose completo del Bitmap de 128 bits, inspección de tramas MTI 0200/0210
 * y estado contable en tiempo real.
 */
@RestController
@RequestMapping("/api/iso")
@CrossOrigin(origins = "*")
public class IsoSimuladorController {

    private final IsoMessageService isoService;
    private final RecolectorTransaccionalService recolectorService;
    private final TcpIsoServer tcpServer;
    private final CoreBancarioService coreService;

    public IsoSimuladorController(
            IsoMessageService isoService,
            RecolectorTransaccionalService recolectorService,
            TcpIsoServer tcpServer,
            CoreBancarioService coreService
    ) {
        this.isoService = isoService;
        this.recolectorService = recolectorService;
        this.tcpServer = tcpServer;
        this.coreService = coreService;
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
            String moneda // GTQ (320) o USD (840)
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

        String mti = req.mti() != null && !req.mti().isBlank() ? req.mti() : "0200";
        String pan = req.pan() != null && !req.pan().isBlank() ? req.pan() : "4532000000000001";
        String cuenta = req.cuenta() != null && !req.cuenta().isBlank() ? req.cuenta() : "1001-2002-3001";
        BigDecimal monto = req.monto() != null ? req.monto() : new BigDecimal("100.00");
        String procCode = req.processingCode() != null && !req.processingCode().isBlank() ? req.processingCode() : "010000"; // Por defecto retiro ATM en Guatemala
        String terminalId = req.terminalId() != null && !req.terminalId().isBlank() ? req.terminalId() : "ATM5B001";
        String stan = req.stan() != null && !req.stan().isBlank() ? req.stan() : String.format("%06d", (int)(Math.random() * 900000) + 100000);
        String rrn = req.rrn() != null && !req.rrn().isBlank() ? req.rrn() : "2627" + String.format("%08d", (int)(Math.random() * 90000000) + 10000000);
        String moneda = req.moneda() != null && !req.moneda().isBlank() ? req.moneda() : "GTQ";

        IsoMessage requestIso;

        if ("0800".equals(mti)) {
            requestIso = isoService.getMessageFactory().newMessage(0x0800);
            requestIso.setValue(11, stan, IsoType.NUMERIC, 6);
            requestIso.setValue(70, "301", IsoType.NUMERIC, 3);
        } else if ("0100".equals(mti)) {
            requestIso = isoService.getMessageFactory().newMessage(0x0100);
            requestIso.setValue(2, pan, IsoType.LLVAR, 0);
            requestIso.setValue(3, "300000", IsoType.NUMERIC, 6);
            requestIso.setValue(11, stan, IsoType.NUMERIC, 6);
            requestIso.setValue(37, rrn, IsoType.ALPHA, 12);
            requestIso.setValue(41, terminalId, IsoType.ALPHA, 8);
            requestIso.setValue(49, "GTQ".equalsIgnoreCase(moneda) ? "320" : "840", IsoType.NUMERIC, 3);
            requestIso.setValue(102, cuenta, IsoType.LLVAR, 0);
        } else if ("0420".equals(mti)) {
            requestIso = isoService.getMessageFactory().newMessage(0x0420);
            requestIso.setValue(2, pan, IsoType.LLVAR, 0);
            requestIso.setValue(3, procCode, IsoType.NUMERIC, 6);
            requestIso.setValue(4, monto, IsoType.AMOUNT, 0);
            requestIso.setValue(11, stan, IsoType.NUMERIC, 6);
            requestIso.setValue(37, rrn, IsoType.ALPHA, 12);
            requestIso.setValue(41, terminalId, IsoType.ALPHA, 8);
            requestIso.setValue(49, "GTQ".equalsIgnoreCase(moneda) ? "320" : "840", IsoType.NUMERIC, 3);
            requestIso.setValue(102, cuenta, IsoType.LLVAR, 0);
        } else {
            // MTI 0200 por defecto (Retiro o Compra)
            requestIso = isoService.createFinancialRequest(pan, procCode, monto, stan, rrn, terminalId, cuenta);
            requestIso.setValue(49, "GTQ".equalsIgnoreCase(moneda) ? "320" : "840", IsoType.NUMERIC, 3);
        }

        String rawReq = isoService.encodeToString(requestIso);

        // Análisis del Bitmap de 128 bits
        BitmapInfo bitmapInfo = calcularBitmap(requestIso);

        // Despachar a través del motor orquestador
        IsoMessage responseIso = recolectorService.procesarMensaje(requestIso);
        String rawResp = isoService.encodeToString(responseIso);

        long latencia = System.currentTimeMillis() - startTime;

        String rc = isoService.getFieldValue(responseIso, 39);
        String auth = isoService.getFieldValue(responseIso, 38);
        boolean exitosa = IsoResponseCode.APROBADA.equals(rc);

        // Desglose de Data Elements
        Map<String, String> deReq = extraerMapaDataElements(requestIso);
        Map<String, String> deResp = extraerMapaDataElements(responseIso);

        IsoTransactionDto dto = isoService.extractTransaction(requestIso);

        // Consultar estado actual de la cuenta para mostrar en pantalla de ATM y comprobante
        Optional<CuentaDto> cuentaOpt = coreService.obtenerCuenta(cuenta);
        BigDecimal saldoDisponibleActual = cuentaOpt.map(CuentaDto::saldoDisponible).orElse(BigDecimal.ZERO);
        BigDecimal saldoContableActual = cuentaOpt.map(CuentaDto::saldoContable).orElse(BigDecimal.ZERO);
        String titular = cuentaOpt.map(CuentaDto::titular).orElse("CLIENTE GUATEMALA");
        String bancoEmisor = cuentaOpt.map(CuentaDto::banco).orElse("BANCO INDUSTRIAL");

        String panMasked = dto.panEnmascarado();
        if (panMasked == null || panMasked.isBlank()) {
            String p = pan != null ? pan : cuentaOpt.map(CuentaDto::pan).orElse("4532000000000001");
            panMasked = (p != null && p.length() >= 10) ? p.substring(0, 6) + "*".repeat(p.length() - 10) + p.substring(p.length() - 4) : p;
        }

        SimulacionResponse resp = new SimulacionResponse(
                exitosa,
                String.format("%04x", requestIso.getType()),
                String.format("%04x", responseIso.getType()),
                rawReq,
                rawResp,
                rc != null ? rc : "96",
                IsoResponseCode.getDescripcion(rc),
                auth,
                panMasked,
                cuenta,
                titular,
                bancoEmisor,
                monto,
                saldoDisponibleActual,
                saldoContableActual,
                moneda,
                rrn,
                stan,
                latencia,
                bitmapInfo.hex(),
                bitmapInfo.binario(),
                bitmapInfo.totalBits(),
                bitmapInfo.bitsActivos(),
                deReq,
                deResp,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
        );

        return ResponseEntity.ok(resp);
    }

    private record BitmapInfo(String hex, String binario, int totalBits, List<Integer> bitsActivos) {}

    private BitmapInfo calcularBitmap(IsoMessage msg) {
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
            String nibble = binBuilder.substring(i, Math.min(i + 4, binBuilder.length()));
            int val = Integer.parseInt(nibble, 2);
            hexBuilder.append(Integer.toHexString(val).toUpperCase());
        }

        return new BitmapInfo(hexBuilder.toString(), binBuilder.toString(), numBits, bitsActivos);
    }

    @GetMapping("/metricas")
    public ResponseEntity<Map<String, Object>> obtenerMetricas() {
        Runtime rt = Runtime.getRuntime();
        long totalMemMb = rt.totalMemory() / (1024 * 1024);
        long freeMemMb = rt.freeMemory() / (1024 * 1024);
        long usedMemMb = totalMemMb - freeMemMb;
        long uptimeSec = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("transaccionesProcesadas", recolectorService.getContadorProcesadas());
        metrics.put("transaccionesAprobadas", recolectorService.getContadorAprobadas());
        metrics.put("transaccionesRechazadas", recolectorService.getContadorRechazadas());
        metrics.put("tcpPuerto", 15000);
        metrics.put("tcpEstado", tcpServer.isRunning() ? "ACTIVO (Listening)" : "INACTIVO");
        metrics.put("modoConcurrencia", "Virtual Threads (Project Loom - Thread.ofVirtual)");
        metrics.put("virtualThreadsHabilitado", true);
        metrics.put("recolectorBasura", "Generational ZGC (-XX:+UseZGC -XX:+ZGenerational)");
        metrics.put("pausasGcObjetivo", "<= 1 ms");
        metrics.put("memoriaUsadaMb", usedMemMb);
        metrics.put("memoriaTotalMb", totalMemMb);
        metrics.put("memoriaLibreMb", freeMemMb);
        metrics.put("tiempoActivoSegundos", uptimeSec);
        metrics.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        return ResponseEntity.ok(metrics);
    }

    @PostMapping("/rafaga")
    public ResponseEntity<Map<String, Object>> dispararRafagaVirtualThreads(@RequestParam(defaultValue = "50") int cantidad) {
        int txCount = Math.min(Math.max(cantidad, 5), 200);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger okCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        long start = System.currentTimeMillis();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(txCount);

            for (int i = 0; i < txCount; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    long tStart = System.currentTimeMillis();
                    try {
                        String stan = String.format("%06d", (idx + 100) % 999999);
                        String rrn = "2627" + String.format("%08d", (int)(Math.random() * 90000000));
                        IsoMessage req = isoService.createFinancialRequest(
                                "4532000000000001",
                                "010000",
                                new BigDecimal("0.01"),
                                stan,
                                rrn,
                                "ATM5B001",
                                "1001-2002-3001"
                        );
                        IsoMessage resp = recolectorService.procesarMensaje(req);
                        String rc = isoService.getFieldValue(resp, 39);
                        if (IsoResponseCode.APROBADA.equals(rc)) {
                            okCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    } finally {
                        latencies.add(System.currentTimeMillis() - tStart);
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }

        long totalDurationMs = System.currentTimeMillis() - start;
        double sec = totalDurationMs / 1000.0;
        double tps = okCount.get() / (sec > 0 ? sec : 1.0);

        Collections.sort(latencies);
        long p50 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.5));
        long p95 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.95));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("totalEnviadas", txCount);
        res.put("exitosas", okCount.get());
        res.put("fallidas", failCount.get());
        res.put("tiempoTotalMs", totalDurationMs);
        res.put("tpsEstimado", Math.round(tps * 100.0) / 100.0);
        res.put("latenciaP50Ms", p50);
        res.put("latenciaP95Ms", p95);
        res.put("concurrencia", "Virtual Threads (Loom)");

        return ResponseEntity.ok(res);
    }

    private Map<String, String> extraerMapaDataElements(IsoMessage msg) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 2; i <= 128; i++) {
            if (msg.hasField(i)) {
                String val = isoService.getFieldValue(msg, i);
                map.put("DE " + i, val);
            }
        }
        return map;
    }
}
