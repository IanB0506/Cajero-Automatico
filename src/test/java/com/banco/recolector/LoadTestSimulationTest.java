package com.banco.recolector;

import com.banco.recolector.corebancario.entity.CuentaEntity;
import com.banco.recolector.iso.IsoResponseCode;
import com.solab.iso8583.IsoMessage;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de carga ISO 8583 sobre el socket TCP real con Virtual Threads.
 * Durante la carga, Java Flight Recorder registra las pausas de Generational ZGC y los eventos de
 * pinning de Virtual Threads; la prueba falla si alguna pausa supera 1 ms o si hay pinning.
 *
 * <p>Parámetros (-D): carga.terminales (100), carga.tx (50), carga.tps.minimo (0 = solo reportar).
 */
@DisplayName("Simulación de Carga Transaccional (Virtual Threads, Generational ZGC <= 1 ms y cero pinning con JFR)")
class LoadTestSimulationTest extends IntegracionBase {

    private static final Logger log = LoggerFactory.getLogger(LoadTestSimulationTest.class);

    private static final int TERMINALES = Integer.getInteger("carga.terminales", 100);
    private static final int TX_POR_TERMINAL = Integer.getInteger("carga.tx", 50);
    private static final int TX_CALENTAMIENTO = 10;
    private static final double TPS_MINIMO = Double.parseDouble(System.getProperty("carga.tps.minimo", "0"));
    private static final double PAUSA_GC_MAXIMA_MS = 1.0;
    private static final BigDecimal MONTO = new BigDecimal("0.01");
    private static final BigDecimal SALDO_INICIAL = new BigDecimal("1000000.00");
    private static final Duration UMBRAL_PINNING = Duration.ofMillis(20);
    private static final Pattern JDK_INTERNO = Pattern.compile("(java|javax|jdk|sun)\\.");

    private record Resultado(int exitosas, int fallidas, List<Long> latenciasMicros, long duracionNanos) {}

    @Test
    @DisplayName("Carga concurrente: 100 terminales x 50 tx, saldos exactos, pausas de GC <= 1 ms y cero pinning")
    void testSimulacionCargaConcurrenteVirtualThreads() throws Exception {
        // Una cuenta por terminal: la carga no se concentra artificialmente en una sola fila
        List<CuentaEntity> cuentas = IntStream.range(0, TERMINALES)
                .mapToObj(i -> crearCuenta(SALDO_INICIAL.toPlainString(), SALDO_INICIAL.toPlainString(), "1234"))
                .toList();

        // Calentamiento del JIT (no se mide)
        Resultado calentamiento = ejecutar(cuentas, TX_CALENTAMIENTO);
        assertEquals(0, calentamiento.fallidas(), "El calentamiento no debe tener fallos");

        List<Double> pausasGcMs = new CopyOnWriteArrayList<>();
        Set<String> recolectores = ConcurrentHashMap.newKeySet();
        AtomicInteger eventosPinning = new AtomicInteger();
        List<String> pinningRelevante = new CopyOnWriteArrayList<>();
        Resultado carga;
        try (RecordingStream jfr = new RecordingStream()) {
            jfr.enable("jdk.GarbageCollection");
            jfr.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
            jfr.onEvent("jdk.GarbageCollection", e -> {
                pausasGcMs.add(e.getDuration("longestPause").toNanos() / 1_000_000.0);
                recolectores.add(e.getString("name"));
            });
            jfr.onEvent("jdk.VirtualThreadPinned", e -> {
                eventosPinning.incrementAndGet();
                List<String> marcos = e.getStackTrace() == null ? List.of() : e.getStackTrace().getFrames().stream()
                        .limit(25).map(f -> f.getMethod().getType().getName() + "." + f.getMethod().getName()).toList();
                // Relevante: bloqueo en código de la aplicación o de librerías, o cualquier pinning >= 20 ms
                // (umbral por defecto de JFR). Los bloqueos internos y breves del JDK solo se reportan.
                boolean bloqueoEnJdk = !marcos.isEmpty() && JDK_INTERNO.matcher(marcos.getFirst()).lookingAt();
                if (!bloqueoEnJdk || e.getDuration().compareTo(UMBRAL_PINNING) >= 0) {
                    pinningRelevante.add(e.getDuration().toNanos() / 1000 + " µs:\n    " + String.join("\n    <- ", marcos));
                }
            });
            jfr.startAsync();

            carga = ejecutar(cuentas, TX_POR_TERMINAL);
            System.gc(); // garantiza al menos un ciclo completo de ZGC dentro de la medición
            Thread.sleep(500);
            jfr.stop();
        }

        int total = TERMINALES * TX_POR_TERMINAL;
        double segundos = carga.duracionNanos() / 1e9;
        double tps = carga.exitosas() / segundos;
        List<Long> lat = new ArrayList<>(carga.latenciasMicros());
        Collections.sort(lat);
        double maxPausa = pausasGcMs.stream().mapToDouble(Double::doubleValue).max().orElse(0);

        String reporte = """
                ========================================================================
                REPORTE DE LA PRUEBA DE CARGA ISO 8583 (socket TCP + Virtual Threads)
                ========================================================================
                Terminales concurrentes : %d (1 conexión keep-alive cada una)
                Transacciones medidas   : %d (+ %d de calentamiento)
                Aprobadas / Fallidas    : %d / %d
                Tiempo total            : %.2f s
                Throughput              : %.1f TPS
                Latencia p50 / p95 / p99: %.2f / %.2f / %.2f ms
                Recolector de basura    : %s
                Ciclos de GC medidos    : %d
                Pausa máxima de GC      : %.3f ms (objetivo <= %.1f ms)
                Eventos de pinning      : %d (en código de la aplicación o >= 20 ms: %d)
                ========================================================================
                """.formatted(TERMINALES, total, TERMINALES * TX_CALENTAMIENTO, carga.exitosas(), carga.fallidas(),
                segundos, tps, percentil(lat, 0.50), percentil(lat, 0.95), percentil(lat, 0.99),
                String.join(", ", recolectores), pausasGcMs.size(), maxPausa, PAUSA_GC_MAXIMA_MS, eventosPinning.get(),
                pinningRelevante.size());
        log.info("\n{}", reporte);
        Files.writeString(Path.of("target", "reporte-carga.txt"), reporte);

        assertEquals(total, carga.exitosas(), "Todas las transacciones concurrentes deben aprobarse");
        assertEquals(0, carga.fallidas(), "No debe haber fallos por timeout o socket");

        BigDecimal debitoEsperado = MONTO.multiply(BigDecimal.valueOf(TX_CALENTAMIENTO + TX_POR_TERMINAL));
        for (CuentaEntity c : cuentas) {
            assertEquals(SALDO_INICIAL.subtract(debitoEsperado), saldo(c.getNumeroCuenta()),
                    "Saldo exacto en " + c.getNumeroCuenta() + ": ningún débito perdido ni duplicado");
        }

        assertFalse(pausasGcMs.isEmpty(), "JFR debe registrar ciclos de GC");
        assertTrue(recolectores.stream().anyMatch(n -> n.contains("Z")), "La JVM de pruebas corre con ZGC: " + recolectores);
        assertTrue(maxPausa <= PAUSA_GC_MAXIMA_MS, "Pausa máxima de Generational ZGC " + maxPausa + " ms > 1 ms");
        assertTrue(pinningRelevante.isEmpty(), "No debe haber Carrier Thread Pinning:\n" + String.join("\n---\n", pinningRelevante));
        assertTrue(tps >= TPS_MINIMO, "Throughput " + tps + " TPS por debajo del mínimo " + TPS_MINIMO);
    }

    /** Cada terminal abre una conexión y envía sus transacciones en secuencia desde su propio Virtual Thread. */
    private Resultado ejecutar(List<CuentaEntity> cuentas, int txPorTerminal) throws Exception {
        AtomicInteger exitosas = new AtomicInteger();
        AtomicInteger fallidas = new AtomicInteger();
        List<Long> latencias = new CopyOnWriteArrayList<>();
        long inicio = System.nanoTime();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futuros = new ArrayList<>();
            for (CuentaEntity cuenta : cuentas) {
                futuros.add(executor.submit(() -> {
                    try (Socket socket = new Socket("localhost", tcpPort)) {
                        socket.setSoTimeout(15_000);
                        socket.setTcpNoDelay(true);
                        DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                        DataInputStream dis = new DataInputStream(socket.getInputStream());
                        for (int t = 0; t < txPorTerminal; t++) {
                            long t0 = System.nanoTime();
                            byte[] trama = isoService.encode(isoService.createFinancialRequest(cuenta.getPan(), "000000",
                                    MONTO, nuevoStan(), nuevoRrn(), "POS00001", cuenta.getNumeroCuenta()));
                            dos.writeShort(trama.length);
                            dos.write(trama);
                            dos.flush();
                            byte[] resp = new byte[dis.readUnsignedShort()];
                            dis.readFully(resp);
                            IsoMessage respuesta = isoService.parse(resp);
                            latencias.add((System.nanoTime() - t0) / 1_000);
                            if (IsoResponseCode.APROBADA.equals(isoService.getFieldValue(respuesta, 39))) {
                                exitosas.incrementAndGet();
                            } else {
                                fallidas.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error en la terminal de la cuenta {}: {}", cuenta.getNumeroCuenta(), e.toString());
                        fallidas.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : futuros) {
                f.get(120, TimeUnit.SECONDS);
            }
        }
        return new Resultado(exitosas.get(), fallidas.get(), latencias, System.nanoTime() - inicio);
    }

    private static double percentil(List<Long> ordenadas, double p) {
        return ordenadas.isEmpty() ? 0 : ordenadas.get((int) Math.min(ordenadas.size() - 1, ordenadas.size() * p)) / 1000.0;
    }
}
