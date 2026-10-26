package com.banco.recolector;

import com.banco.recolector.iso.IsoMessageService;
import com.banco.recolector.iso.IsoResponseCode;
import com.solab.iso8583.IsoMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@DisplayName("Simulación de Carga Transaccional de Alto Rendimiento (2,000+ Transacciones con Virtual Threads)")
public class LoadTestSimulationTest {

    private static final Logger log = LoggerFactory.getLogger(LoadTestSimulationTest.class);

    private static final String TCP_HOST = "localhost";
    private static final int TCP_PORT = 15000;
    private static final int CONCURRENT_CLIENTS = 100;
    private static final int TXS_PER_CLIENT = 20;
    private static final int TOTAL_TRANSACTIONS = CONCURRENT_CLIENTS * TXS_PER_CLIENT; // 2000

    @Autowired
    private IsoMessageService isoService;

    @Test
    @DisplayName("Ejecución de Carga Concurrente a 2,000+ Transacciones con Métricas de Latencia p50, p95, p99")
    void testSimulacionCargaConcurrenteVirtualThreads() throws Exception {
        log.info("========================================================================");
        log.info("INICIANDO PRUEBA DE CARGA: {} terminales x {} txs = {} txs con Virtual Threads",
                CONCURRENT_CLIENTS, TXS_PER_CLIENT, TOTAL_TRANSACTIONS);
        log.info("========================================================================");

        List<Long> latenciesMs = Collections.synchronizedList(new ArrayList<>(TOTAL_TRANSACTIONS));
        AtomicInteger exitosas = new AtomicInteger(0);
        AtomicInteger fallidas = new AtomicInteger(0);

        long globalStartTime = System.currentTimeMillis();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(CONCURRENT_CLIENTS);

            for (int c = 0; c < CONCURRENT_CLIENTS; c++) {
                final int clientId = c;
                futures.add(executor.submit(() -> {
                    try (Socket socket = new Socket(TCP_HOST, TCP_PORT)) {
                        socket.setSoTimeout(10000);
                        socket.setTcpNoDelay(true);

                        DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                        DataInputStream dis = new DataInputStream(socket.getInputStream());

                        for (int t = 0; t < TXS_PER_CLIENT; t++) {
                            int txIndex = clientId * TXS_PER_CLIENT + t;
                            long txStart = System.currentTimeMillis();

                            String stan = String.format("%06d", (txIndex + 1) % 999999);
                            String rrn = String.format("2627%08d", txIndex + 1);

                            IsoMessage req = isoService.createFinancialRequest(
                                    "4532000000000001",
                                    "000000",
                                    new BigDecimal("0.01"),
                                    stan,
                                    rrn,
                                    "POS" + String.format("%05d", clientId),
                                    "1001-2002-3001"
                            );

                            byte[] requestBytes = isoService.encode(req);

                            dos.writeShort(requestBytes.length);
                            dos.write(requestBytes);
                            dos.flush();

                            int respLen = dis.readUnsignedShort();
                            byte[] respBytes = new byte[respLen];
                            dis.readFully(respBytes);

                            IsoMessage resp = isoService.parse(respBytes);
                            String rc = isoService.getFieldValue(resp, 39);

                            long txDuration = System.currentTimeMillis() - txStart;
                            latenciesMs.add(txDuration);

                            if (IsoResponseCode.APROBADA.equals(rc)) {
                                exitosas.incrementAndGet();
                            } else {
                                fallidas.incrementAndGet();
                                log.warn("Transacción rechazada con RC={}: RRN={}", rc, rrn);
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error en conexión de terminal {}: {}", clientId, e.getMessage());
                    }
                }));
            }

            // Esperar que todas las terminales completen sus transacciones
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        }

        long globalDurationMs = System.currentTimeMillis() - globalStartTime;
        double durationSec = globalDurationMs / 1000.0;
        double tps = exitosas.get() / (durationSec > 0 ? durationSec : 1.0);

        // Cálculo de Percentiles de Latencia
        Collections.sort(latenciesMs);
        long p50 = latenciesMs.isEmpty() ? 0 : latenciesMs.get((int) (latenciesMs.size() * 0.50));
        long p95 = latenciesMs.isEmpty() ? 0 : latenciesMs.get((int) (latenciesMs.size() * 0.95));
        long p99 = latenciesMs.isEmpty() ? 0 : latenciesMs.get((int) (latenciesMs.size() * 0.99));

        log.info("========================================================================");
        log.info("REPORTE DE RESULTADOS DE LA PRUEBA DE CARGA ISO 8583");
        log.info("========================================================================");
        log.info("Total Transacciones : {}", TOTAL_TRANSACTIONS);
        log.info("Transacciones OK    : {}", exitosas.get());
        log.info("Transacciones Fallo : {}", fallidas.get());
        log.info("Tiempo Total        : {} ms ({} s)", globalDurationMs, String.format("%.2f", durationSec));
        log.info("Throughput Estimado : {} TPS (Transacciones por Segundo)", String.format("%.2f", tps));
        log.info("Latencia p50 (Mediana): {} ms", p50);
        log.info("Latencia p95         : {} ms", p95);
        log.info("Latencia p99         : {} ms", p99);
        log.info("Tasa de Éxito        : {}%", String.format("%.2f", (exitosas.get() * 100.0) / TOTAL_TRANSACTIONS));
        log.info("========================================================================");

        assertEquals(TOTAL_TRANSACTIONS, exitosas.get(), "Todas las transacciones concurrentes deben aprobar");
        assertEquals(0, fallidas.get(), "No debe haber transacciones fallidas por timeout o socket");
        assertTrue(tps > 0, "El throughput debe ser positivo y medible");
    }
}
