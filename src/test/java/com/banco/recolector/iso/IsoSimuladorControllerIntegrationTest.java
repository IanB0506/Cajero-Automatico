package com.banco.recolector.iso;

import com.banco.recolector.IntegracionBase;
import com.banco.recolector.corebancario.entity.CuentaEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

@DisplayName("Simulador Web ISO 8583 (/api/iso): flujo completo Simulador -> Recolector -> RestClient -> Core")
class IsoSimuladorControllerIntegrationTest extends IntegracionBase {

    private JsonNode simular(Map<String, Object> cuerpo) {
        Respuesta r = llamar(POST, "/api/iso/simular", cuerpo);
        assertEquals(200, r.status(), r.cuerpo());
        return r.json();
    }

    private static Map<String, Object> cuerpo(Object... pares) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            m.put((String) pares[i], pares[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("Retiro ATM con PIN: 0200 -> 0210 aprobado, bitmap secundario, PAN y PIN block enmascarados")
    void testRetiroConPin() {
        CuentaEntity c = crearCuenta("500.00", "500.00", "1234");
        JsonNode r = simular(cuerpo("mti", "0200", "cuenta", c.getNumeroCuenta(), "monto", 100,
                "processingCode", "010000", "pin", "1234"));

        assertTrue(r.get("exitosa").asBoolean());
        assertEquals("0200", r.get("mtiSolicitud").asString());
        assertEquals("0210", r.get("mtiRespuesta").asString());
        assertEquals("00", r.get("codigoRespuestaIso").asString());
        assertTrue(r.get("codigoAutorizacion").asString().matches("\\d{6}"));
        assertEquals(128, r.get("totalBits").asInt(), "DE 102 activa el bitmap secundario");
        assertTrue(r.get("bitmapHex").asString().startsWith("F"));
        assertEquals(0, new BigDecimal("400.00").compareTo(r.get("saldoDisponible").decimalValue()));
        assertEquals("499900******" + c.getPan().substring(12), r.get("panEnmascarado").asString());
        assertEquals("****************", r.get("dataElementsEnviados").get("DE 52").asString());
        assertFalse(r.get("rawRequestAscii").asString().contains(c.getPan()), "La trama mostrada no expone el PAN");
        assertNull(r.get("dataElementsRecibidos").get("DE 52"), "La respuesta no contiene PIN block");
        assertNotNull(r.get("dataElementsRecibidos").get("DE 54"));
    }

    @Test
    @DisplayName("Retiro con PIN incorrecto -> DE 39 = 55 sin débito")
    void testRetiroPinIncorrecto() {
        CuentaEntity c = crearCuenta("500.00", "500.00", "1234");
        JsonNode r = simular(cuerpo("cuenta", c.getNumeroCuenta(), "monto", 100, "pin", "9999"));
        assertEquals("55", r.get("codigoRespuestaIso").asString());
        assertFalse(r.get("exitosa").asBoolean());
        assertEquals(new BigDecimal("500.00"), saldo(c.getNumeroCuenta()));
    }

    @Test
    @DisplayName("Reversa 0420 del último retiro (mismo RRN) -> 0430 aprobada y saldo restituido")
    void testReversaDelUltimoRetiro() {
        CuentaEntity c = crearCuenta("500.00", "500.00", "1234");
        JsonNode retiro = simular(cuerpo("cuenta", c.getNumeroCuenta(), "monto", 200, "pin", "1234"));
        assertEquals("00", retiro.get("codigoRespuestaIso").asString());

        JsonNode reversa = simular(cuerpo("mti", "0420", "cuenta", c.getNumeroCuenta(), "monto", 200,
                "processingCode", "010000", "rrn", retiro.get("rrn").asString()));
        assertEquals("0430", reversa.get("mtiRespuesta").asString());
        assertEquals("00", reversa.get("codigoRespuestaIso").asString());
        assertEquals(new BigDecimal("500.00"), saldo(c.getNumeroCuenta()));

        JsonNode sinOriginal = simular(cuerpo("mti", "0420", "cuenta", c.getNumeroCuenta(), "monto", 50));
        assertEquals("25", sinOriginal.get("codigoRespuestaIso").asString());
    }

    @Test
    @DisplayName("Consulta de saldo 0100 -> 0110 con saldos y transferencia con DE 103")
    void testConsultaYTransferencia() {
        CuentaEntity origen = crearCuenta("300.00", "500.00", "1234");
        CuentaEntity destino = crearCuenta("0.00", "500.00", "1234");

        JsonNode consulta = simular(cuerpo("mti", "0100", "cuenta", origen.getNumeroCuenta(), "pin", "1234", "moneda", "USD"));
        assertEquals("0110", consulta.get("mtiRespuesta").asString());
        assertEquals("00", consulta.get("codigoRespuestaIso").asString());
        assertTrue(consulta.get("dataElementsRecibidos").get("DE 54").asString().contains("840C"));

        JsonNode transferencia = simular(cuerpo("cuenta", origen.getNumeroCuenta(), "cuentaDestino", destino.getNumeroCuenta(),
                "monto", 120, "processingCode", "400000", "pin", "1234"));
        assertEquals("00", transferencia.get("codigoRespuestaIso").asString());
        assertEquals(destino.getNumeroCuenta(), transferencia.get("dataElementsEnviados").get("DE 103").asString());
        assertEquals(new BigDecimal("180.00"), saldo(origen.getNumeroCuenta()));
        assertEquals(new BigDecimal("120.00"), saldo(destino.getNumeroCuenta()));
    }

    @Test
    @DisplayName("Echo test 0800 -> 0810 y compra POS sin PIN en cuenta inexistente -> 14")
    void testEchoYCuentaInexistente() {
        JsonNode echo = simular(cuerpo("mti", "0800"));
        assertEquals("0810", echo.get("mtiRespuesta").asString());
        assertEquals("00", echo.get("codigoRespuestaIso").asString());

        JsonNode inexistente = simular(cuerpo("cuenta", "NO-EXISTE", "pan", "4111111111111111", "monto", 10,
                "processingCode", "000000"));
        assertEquals("14", inexistente.get("codigoRespuestaIso").asString());
    }

    @Test
    @DisplayName("Métricas reales (GC, modo, RSS) y ráfaga concurrente con Virtual Threads")
    void testMetricasYRafaga() {
        JsonNode rafaga = llamar(POST, "/api/iso/rafaga?cantidad=40", null).json();
        assertEquals(40, rafaga.get("totalEnviadas").asInt());
        assertEquals(40, rafaga.get("exitosas").asInt());
        assertEquals(0, rafaga.get("fallidas").asInt());

        JsonNode metricas = llamar(GET, "/api/iso/metricas", null).json();
        assertTrue(metricas.get("transaccionesProcesadas").asLong() >= 40);
        assertEquals("ACTIVO (Listening)", metricas.get("tcpEstado").asString());
        assertTrue(metricas.get("recolectorBasura").asString().contains("ZGC"), "La suite corre sobre Generational ZGC");
        assertTrue(metricas.get("memoriaRssMb").asLong() > 0);
        assertTrue(metricas.get("modoEjecucion").asString().startsWith("JVM"));
    }
}
