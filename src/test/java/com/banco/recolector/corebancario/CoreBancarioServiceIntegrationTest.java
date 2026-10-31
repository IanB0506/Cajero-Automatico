package com.banco.recolector.corebancario;

import com.banco.recolector.IntegracionBase;
import com.banco.recolector.corebancario.entity.CuentaEntity;
import com.banco.recolector.corebancario.service.CoreBancarioService;
import com.banco.recolector.iso.IsoResponseCode;
import com.banco.recolector.iso.PinBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.banco.recolector.corebancario.TransaccionCoreRequest.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Reglas de negocio del Core Bancario (BD H2 real, concurrencia e idempotencia)")
class CoreBancarioServiceIntegrationTest extends IntegracionBase {

    @Autowired
    private CoreBancarioService core;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransaccionCoreRequest req(CuentaEntity c, String tipo, String monto, String pin) {
        return req(c, tipo, monto, pin, nuevoRrn(), null);
    }

    private TransaccionCoreRequest req(CuentaEntity c, String tipo, String monto, String pin, String rrn, String destino) {
        String pinBlock = pin != null ? PinBlock.formato0(pin, c.getPan()) : null;
        return new TransaccionCoreRequest(rrn, nuevoStan(), c.getPan(), c.getNumeroCuenta(), destino,
                new BigDecimal(monto), tipo, "ATM00001", null, pinBlock);
    }

    private static void rc(String esperado, TransaccionCoreResponse resp) {
        assertEquals(esperado, resp.codigoRespuesta(), resp.mensaje());
    }

    @Test
    @DisplayName("Compra sin PIN aprobada: débito exacto, autorización y registro")
    void testCompra() {
        CuentaEntity c = crearCuenta("100.00", "500.00", "1234");
        TransaccionCoreResponse resp = core.procesar(req(c, COMPRA, "25.50", null));
        rc("00", resp);
        assertTrue(resp.codigoAutorizacion().matches("\\d{6}"));
        assertEquals(new BigDecimal("74.50"), saldo(c.getNumeroCuenta()));
        assertEquals(new BigDecimal("74.50"), resp.saldoDisponible());
    }

    @Test
    @DisplayName("Retiro con PIN correcto aprobado y acumulado diario actualizado")
    void testRetiroConPin() {
        CuentaEntity c = crearCuenta("300.00", "500.00", "4321");
        rc("00", core.procesar(req(c, RETIRO, "100.00", "4321")));
        CuentaEntity despues = cuenta(c.getNumeroCuenta());
        assertEquals(new BigDecimal("200.00"), despues.getSaldoDisponible());
        assertEquals(new BigDecimal("100.00"), despues.getRetiradoHoy());
        assertEquals(LocalDate.now(), despues.getFechaUltimoRetiro());
    }

    @Test
    @DisplayName("Retiro, consulta y transferencia exigen PIN: sin PIN -> 55 (sin contar intento)")
    void testPinObligatorio() {
        CuentaEntity c = crearCuenta("300.00", "500.00", "1234");
        rc("55", core.procesar(req(c, RETIRO, "10.00", null)));
        rc("55", core.procesar(req(c, CONSULTA, "0", null)));
        assertEquals(0, cuenta(c.getNumeroCuenta()).getIntentosPinFallidos());
        assertEquals(new BigDecimal("300.00"), saldo(c.getNumeroCuenta()));
    }

    @Test
    @DisplayName("PIN incorrecto: 55, 55 y al tercer intento 75 con la tarjeta bloqueada")
    void testBloqueoPorIntentosDePin() {
        CuentaEntity c = crearCuenta("300.00", "500.00", "1234");
        rc("55", core.procesar(req(c, RETIRO, "10.00", "0000")));
        rc("55", core.procesar(req(c, RETIRO, "10.00", "1111")));
        rc("75", core.procesar(req(c, RETIRO, "10.00", "2222")));
        assertEquals("BLOCKED", cuenta(c.getNumeroCuenta()).getEstado());
        rc("05", core.procesar(req(c, RETIRO, "10.00", "1234")));
        assertEquals(new BigDecimal("300.00"), saldo(c.getNumeroCuenta()));
    }

    @Test
    @DisplayName("Un PIN correcto reinicia el contador de intentos fallidos")
    void testPinCorrectoReiniciaIntentos() {
        CuentaEntity c = crearCuenta("300.00", "500.00", "1234");
        rc("55", core.procesar(req(c, RETIRO, "10.00", "9999")));
        assertEquals(1, cuenta(c.getNumeroCuenta()).getIntentosPinFallidos());
        rc("00", core.procesar(req(c, RETIRO, "10.00", "1234")));
        assertEquals(0, cuenta(c.getNumeroCuenta()).getIntentosPinFallidos());
    }

    @Test
    @DisplayName("Una compra con PIN block se valida igual que un retiro")
    void testCompraConPinIncorrecto() {
        CuentaEntity c = crearCuenta("300.00", "500.00", "1234");
        rc("55", core.procesar(req(c, COMPRA, "10.00", "4444")));
    }

    @Test
    @DisplayName("Límite diario de retiro: 61 al excederlo y reinicio automático al cambiar de día")
    void testLimiteDiarioYReinicio() {
        CuentaEntity c = crearCuenta("1000.00", "300.00", "1234");
        rc("00", core.procesar(req(c, RETIRO, "250.00", "1234")));
        rc("61", core.procesar(req(c, RETIRO, "100.00", "1234")));

        // Simula que el último retiro fue ayer: el acumulado debe volver a cero
        CuentaEntity ayer = cuenta(c.getNumeroCuenta());
        ayer.setFechaUltimoRetiro(LocalDate.now().minusDays(1));
        cuentaRepository.save(ayer);
        assertEquals(BigDecimal.ZERO, core.obtenerCuenta(c.getNumeroCuenta()).orElseThrow().retiradoHoy());

        rc("00", core.procesar(req(c, RETIRO, "100.00", "1234")));
        assertEquals(new BigDecimal("100.00"), cuenta(c.getNumeroCuenta()).getRetiradoHoy());
    }

    @Test
    @DisplayName("Rechazos por fondos (51), monto inválido (13), bloqueo (05), vencimiento (54) y cuenta inexistente (14)")
    void testRechazos() {
        CuentaEntity c = crearCuenta("50.00", "500.00", "1234");
        rc("51", core.procesar(req(c, COMPRA, "50.01", null)));
        rc("13", core.procesar(req(c, COMPRA, "0", null)));
        rc("13", core.procesar(req(c, RETIRO, "0", "1234")));

        CuentaEntity bloqueada = crearCuenta("50.00", "500.00", "1234");
        bloqueada.setEstado("BLOCKED");
        cuentaRepository.save(bloqueada);
        rc("05", core.procesar(req(bloqueada, COMPRA, "1.00", null)));

        CuentaEntity vencida = crearCuenta("50.00", "500.00", "1234");
        vencida.setEstado("EXPIRED");
        cuentaRepository.save(vencida);
        rc("54", core.procesar(req(vencida, COMPRA, "1.00", null)));

        rc("14", core.procesar(new TransaccionCoreRequest(nuevoRrn(), nuevoStan(), null, "NO-EXISTE-" + nuevoStan(),
                null, BigDecimal.ONE, COMPRA, "POS1", null, null)));
        assertEquals(new BigDecimal("50.00"), saldo(c.getNumeroCuenta()));
    }

    @Test
    @DisplayName("Tarjeta que no corresponde a la cuenta -> 14; operación no soportada -> 12")
    void testTarjetaAjenaYOperacionNoSoportada() {
        CuentaEntity c = crearCuenta("50.00", "500.00", "1234");
        rc("14", core.procesar(new TransaccionCoreRequest(nuevoRrn(), nuevoStan(), "4111111111111111",
                c.getNumeroCuenta(), null, BigDecimal.ONE, COMPRA, "POS1", null, null)));
        rc("12", core.procesar(req(c, NO_SOPORTADA, "1.00", null)));
    }

    @Test
    @DisplayName("La cuenta también se localiza por el PAN (DE 2) cuando no viene DE 102")
    void testCuentaPorPan() {
        CuentaEntity c = crearCuenta("50.00", "500.00", "1234");
        rc("00", core.procesar(new TransaccionCoreRequest(nuevoRrn(), nuevoStan(), c.getPan(), null,
                null, BigDecimal.ONE, COMPRA, "POS1", null, null)));
        assertEquals(new BigDecimal("49.00"), saldo(c.getNumeroCuenta()));
    }

    @Test
    @DisplayName("Consulta de saldo con PIN: aprobada sin movimiento")
    void testConsulta() {
        CuentaEntity c = crearCuenta("80.00", "500.00", "1234");
        TransaccionCoreResponse resp = core.procesar(req(c, CONSULTA, "0", "1234"));
        rc("00", resp);
        assertEquals(new BigDecimal("80.00"), resp.saldoDisponible());
        assertEquals(new BigDecimal("80.00"), saldo(c.getNumeroCuenta()));
    }

    @Test
    @DisplayName("Transferencia (DE 102 -> DE 103): mueve el dinero entre cuentas y valida destino, fondos y monto")
    void testTransferencia() {
        CuentaEntity origen = crearCuenta("200.00", "500.00", "1234");
        CuentaEntity destino = crearCuenta("10.00", "500.00", "1234");

        rc("00", core.procesar(req(origen, TRANSFERENCIA, "75.00", "1234", nuevoRrn(), destino.getNumeroCuenta())));
        assertEquals(new BigDecimal("125.00"), saldo(origen.getNumeroCuenta()));
        assertEquals(new BigDecimal("85.00"), saldo(destino.getNumeroCuenta()));

        rc("14", core.procesar(req(origen, TRANSFERENCIA, "1.00", "1234", nuevoRrn(), "NO-EXISTE")));
        rc("12", core.procesar(req(origen, TRANSFERENCIA, "1.00", "1234", nuevoRrn(), origen.getNumeroCuenta())));
        rc("51", core.procesar(req(origen, TRANSFERENCIA, "999.00", "1234", nuevoRrn(), destino.getNumeroCuenta())));
        rc("13", core.procesar(req(origen, TRANSFERENCIA, "0", "1234", nuevoRrn(), destino.getNumeroCuenta())));
        assertTrue(core.transaccionesDeCuenta(destino.getNumeroCuenta(), 10).orElseThrow().stream()
                .anyMatch(t -> TRANSFERENCIA.equals(t.getTipoOperacion())), "La transferencia aparece en el historial del destino");
    }

    @Test
    @DisplayName("Reversa 0420: exige la transacción original, devuelve el dinero y el acumulado diario")
    void testReversa() {
        CuentaEntity c = crearCuenta("500.00", "500.00", "1234");
        String rrnRetiro = nuevoRrn();
        rc("00", core.procesar(req(c, RETIRO, "200.00", "1234", rrnRetiro, null)));
        assertEquals(new BigDecimal("300.00"), saldo(c.getNumeroCuenta()));

        rc("25", core.procesar(req(c, REVERSA, "200.00", null, nuevoRrn(), null)));
        rc("13", core.procesar(req(c, REVERSA, "250.00", null, rrnRetiro, null)));

        rc("00", core.procesar(req(c, REVERSA, "0", null, rrnRetiro, null))); // sin monto = reversa total
        CuentaEntity despues = cuenta(c.getNumeroCuenta());
        assertEquals(new BigDecimal("500.00"), despues.getSaldoDisponible());
        assertEquals(0, BigDecimal.ZERO.compareTo(despues.getRetiradoHoy()));

        rc("13", core.procesar(req(c, REVERSA, "1.00", null, rrnRetiro, null)));
        assertEquals(new BigDecimal("500.00"), saldo(c.getNumeroCuenta()), "No se puede revertir dos veces");
    }

    @Test
    @DisplayName("Devolución: solo contra una compra aprobada y hasta su monto")
    void testDevolucion() {
        CuentaEntity c = crearCuenta("100.00", "500.00", "1234");
        String rrnCompra = nuevoRrn();
        rc("00", core.procesar(req(c, COMPRA, "60.00", null, rrnCompra, null)));
        rc("25", core.procesar(req(c, DEVOLUCION, "10.00", null, nuevoRrn(), null)));
        rc("00", core.procesar(req(c, DEVOLUCION, "40.00", null, rrnCompra, null)));
        rc("13", core.procesar(req(c, DEVOLUCION, "30.00", null, rrnCompra, null)));
        rc("00", core.procesar(req(c, DEVOLUCION, "20.00", null, rrnCompra, null)));
        assertEquals(new BigDecimal("100.00"), saldo(c.getNumeroCuenta()));
    }

    @Test
    @DisplayName("Idempotencia en BD: el mismo RRN/STAN no debita dos veces y devuelve la misma autorización")
    void testIdempotencia() {
        CuentaEntity c = crearCuenta("100.00", "500.00", "1234");
        TransaccionCoreRequest r = req(c, COMPRA, "30.00", null);
        TransaccionCoreResponse primera = core.procesar(r);
        TransaccionCoreResponse segunda = core.procesar(r);
        assertEquals(primera.codigoAutorizacion(), segunda.codigoAutorizacion());
        assertEquals(new BigDecimal("70.00"), saldo(c.getNumeroCuenta()));
        assertTrue(segunda.mensaje().contains("Idempotencia"));
    }

    @Test
    @DisplayName("Concurrencia: 100 débitos simultáneos sobre la misma cuenta no pierden ningún débito")
    void testDebitosConcurrentesSinPerdidas() throws Exception {
        CuentaEntity c = crearCuenta("1000.00", "500.00", "1234");
        List<Future<TransaccionCoreResponse>> futuros = new ArrayList<>();
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                futuros.add(vt.submit(() -> core.procesar(req(c, COMPRA, "1.00", null))));
            }
        }
        for (Future<TransaccionCoreResponse> f : futuros) {
            rc("00", f.get());
        }
        assertEquals(new BigDecimal("900.00"), saldo(c.getNumeroCuenta()), "Cada uno de los 100 débitos quedó aplicado");
    }

    @Test
    @DisplayName("Concurrencia: 20 envíos simultáneos del mismo RRN/STAN se aplican una sola vez")
    void testDuplicadosSimultaneos() throws Exception {
        CuentaEntity c = crearCuenta("100.00", "500.00", "1234");
        TransaccionCoreRequest r = req(c, COMPRA, "10.00", null);
        Set<String> autorizaciones = ConcurrentHashMap.newKeySet();
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                vt.submit(() -> autorizaciones.add(core.procesar(r).codigoAutorizacion()));
            }
        }
        assertEquals(1, autorizaciones.size(), "Todas las respuestas comparten la autorización original");
        assertEquals(new BigDecimal("90.00"), saldo(c.getNumeroCuenta()));
        assertTrue(transaccionRepository.findByRrnAndStan(r.rrn(), r.stan()).isPresent());
    }

    @Test
    @DisplayName("Los códigos de autorización continúan tras un reinicio (no se repiten)")
    void testAutorizacionNoSeRepiteTrasReinicio() {
        CuentaEntity c = crearCuenta("100.00", "500.00", "1234");
        String antes = core.procesar(req(c, COMPRA, "1.00", null)).codigoAutorizacion();
        CoreBancarioService reiniciado = new CoreBancarioService(cuentaRepository, transaccionRepository, transactionManager, evento -> {});
        String despues = reiniciado.procesar(req(c, COMPRA, "1.00", null)).codigoAutorizacion();
        assertTrue(Integer.parseInt(despues) > Integer.parseInt(antes), antes + " -> " + despues);
    }

    @Test
    @DisplayName("Administración: alta, edición de PIN, recarga, bloqueo, borrado y validaciones")
    void testAdministracionDeCuentas() {
        String numero = "ADM-" + nuevoStan();
        CuentaDto creada = core.crearCuenta(new CrearCuentaRequest("cliente nuevo", numero, null, null, null, null, "5678", null));
        assertEquals("CLIENTE NUEVO", creada.titular());
        assertEquals(new BigDecimal("1000.00"), creada.saldoDisponible());
        assertEquals("ACTIVE", creada.estado());
        assertTrue(creada.pan().matches("4532\\d{12}"));
        assertThrows(IllegalArgumentException.class, () ->
                core.crearCuenta(new CrearCuentaRequest(null, numero, null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () ->
                core.crearCuenta(new CrearCuentaRequest(null, null, creada.pan(), null, null, null, null, null)));

        CuentaEntity c = cuenta(numero);
        rc("00", core.procesar(req(c, CONSULTA, "0", "5678")));
        core.editarCuenta(numero, new EditarCuentaRequest("Nombre Editado", new BigDecimal("50"), new BigDecimal("20"), null, "9876", "bam"));
        CuentaDto editada = core.obtenerCuenta(numero).orElseThrow();
        assertEquals("NOMBRE EDITADO", editada.titular());
        assertEquals("BAM", editada.banco());
        assertEquals(new BigDecimal("20.00"), editada.saldoDisponible());
        rc("55", core.procesar(req(c, CONSULTA, "0", "5678")));
        rc("00", core.procesar(req(c, CONSULTA, "0", "9876")));

        assertEquals(new BigDecimal("70.00"), core.recargarSaldo(numero, new BigDecimal("50")).orElseThrow().saldoDisponible());
        assertThrows(IllegalArgumentException.class, () -> core.recargarSaldo(numero, new BigDecimal("-5")));
        assertEquals("BLOCKED", core.toggleBloqueo(numero).orElseThrow().estado());
        assertEquals("ACTIVE", core.toggleBloqueo(numero).orElseThrow().estado());
        assertEquals("EXPIRED", core.editarCuenta(numero, new EditarCuentaRequest(null, null, null, "expired", null, null)).orElseThrow().estado());
        assertEquals("ACTIVE", core.editarCuenta(numero, new EditarCuentaRequest(null, null, null, "ACTIVE", null, null)).orElseThrow().estado());
        assertTrue(core.obtenerCuenta(creada.pan()).isPresent(), "También se busca por PAN");
        assertTrue(core.eliminarCuenta(numero));
        assertFalse(core.eliminarCuenta(numero));
        assertTrue(core.obtenerCuenta(numero).isEmpty());
        assertTrue(core.recargarSaldo("NO-EXISTE", BigDecimal.ONE).isEmpty());
        assertTrue(core.transaccionesDeCuenta("NO-EXISTE", 10).isEmpty());
        assertFalse(core.listarCuentas().isEmpty());
        assertFalse(core.listarTransacciones(5).isEmpty());
        assertTrue(core.listarTransacciones(5).size() <= 5);
    }

    @Test
    @DisplayName("Desbloquear una tarjeta reinicia los intentos de PIN")
    void testDesbloqueoReiniciaIntentos() {
        CuentaEntity c = crearCuenta("100.00", "500.00", "1234");
        for (String pin : List.of("0000", "1111", "2222")) {
            core.procesar(req(c, RETIRO, "1.00", pin));
        }
        assertEquals("BLOCKED", cuenta(c.getNumeroCuenta()).getEstado());
        core.toggleBloqueo(c.getNumeroCuenta());
        assertEquals(0, cuenta(c.getNumeroCuenta()).getIntentosPinFallidos());
        rc("00", core.procesar(req(c, RETIRO, "1.00", "1234")));
        rc(IsoResponseCode.APROBADA, core.procesar(req(c, COMPRA, "1.00", null)));
    }
}
