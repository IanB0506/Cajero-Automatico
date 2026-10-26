package com.banco.recolector.corebancario;

import com.banco.recolector.IntegracionBase;
import com.banco.recolector.corebancario.entity.CuentaEntity;
import com.banco.recolector.iso.PinBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;

@DisplayName("API REST del Core Bancario: seguridad, validaciones y mapeo HTTP")
class CoreBancarioControllerIntegrationTest extends IntegracionBase {

    private Map<String, Object> transaccion(CuentaEntity c, String tipo, String monto, String pin) {
        Map<String, Object> cuerpo = new LinkedHashMap<>(Map.of("rrn", nuevoRrn(), "stan", nuevoStan(), "pan", c.getPan(),
                "cuentaOrigen", c.getNumeroCuenta(), "monto", new BigDecimal(monto), "tipoOperacion", tipo,
                "terminalId", "ATM00001"));
        if (pin != null) {
            cuerpo.put("pinBlock", PinBlock.formato0(pin, c.getPan()));
        }
        return cuerpo;
    }

    @Test
    @DisplayName("POST /transacciones exige API Key o administrador (401 sin credenciales o con clave inválida)")
    void testSeguridadTransacciones() {
        CuentaEntity c = crearCuenta("100.00", "500.00", "1234");
        assertEquals(401, llamar(POST, "/api/core/transacciones", transaccion(c, "COMPRA", "1.00", null)).status());
        assertEquals(401, llamar(POST, "/api/core/transacciones", transaccion(c, "COMPRA", "1.00", null),
                h -> h.set("X-API-Key", "clave-falsa")).status());
        assertEquals(401, llamar(POST, "/api/core/transacciones", transaccion(c, "COMPRA", "1.00", null),
                h -> h.setBasicAuth(adminUsuario, "incorrecta")).status());

        Respuesta servicio = comoServicio(POST, "/api/core/transacciones", transaccion(c, "COMPRA", "1.00", null));
        assertEquals(200, servicio.status());
        assertEquals("00", servicio.json().get("codigoRespuesta").asString());
        assertEquals(200, comoAdmin(POST, "/api/core/transacciones", transaccion(c, "COMPRA", "1.00", null)).status());
        assertEquals(new BigDecimal("98.00"), saldo(c.getNumeroCuenta()));
    }

    @Test
    @DisplayName("Mapeo HTTP de los rechazos: 51->422, 61->429, 54->410, 05/55->403, 14->404, 13->400")
    void testMapeoHttpDeRechazos() {
        CuentaEntity c = crearCuenta("10.00", "5.00", "1234");
        assertEquals(422, comoServicio(POST, "/api/core/transacciones", transaccion(c, "COMPRA", "50.00", null)).status());
        assertEquals(429, comoServicio(POST, "/api/core/transacciones", transaccion(c, "RETIRO", "6.00", "1234")).status());
        assertEquals(403, comoServicio(POST, "/api/core/transacciones", transaccion(c, "RETIRO", "1.00", "0000")).status());
        assertEquals(400, comoServicio(POST, "/api/core/transacciones", transaccion(c, "COMPRA", "0", null)).status());

        CuentaEntity vencida = crearCuenta("10.00", "5.00", "1234");
        vencida.setEstado("EXPIRED");
        cuentaRepository.save(vencida);
        assertEquals(410, comoServicio(POST, "/api/core/transacciones", transaccion(vencida, "COMPRA", "1.00", null)).status());

        Respuesta inexistente = comoServicio(POST, "/api/core/transacciones", Map.of("rrn", nuevoRrn(), "stan", nuevoStan(),
                "cuentaOrigen", "NO-EXISTE", "monto", 1, "tipoOperacion", "COMPRA"));
        assertEquals(404, inexistente.status());
        assertEquals("14", inexistente.json().get("codigoRespuesta").asString());
    }

    @Test
    @DisplayName("Solicitud inválida o ilegible -> 400 con DE 39 = 30 en el cuerpo")
    void testSolicitudInvalida() {
        Respuesta sinRrn = comoServicio(POST, "/api/core/transacciones",
                Map.of("stan", "000001", "cuentaOrigen", "1001-2002-3001", "monto", 1, "tipoOperacion", "COMPRA"));
        assertEquals(400, sinRrn.status());
        assertEquals("30", sinRrn.json().get("codigoRespuesta").asString());

        Respuesta ilegible = llamar(POST, "/api/core/transacciones", "{no es json",
                h -> { h.set("X-API-Key", apiKey); h.set("Content-Type", "application/json"); });
        assertEquals(400, ilegible.status());
        assertEquals("30", ilegible.json().get("codigoRespuesta").asString());
    }

    @Test
    @DisplayName("Consultas GET públicas y sin PIN; administración protegida con HTTP Basic")
    void testConsultasPublicasSinPin() {
        Respuesta cuentas = llamar(GET, "/api/core/cuentas", null);
        assertEquals(200, cuentas.status());
        assertTrue(cuentas.json().size() >= 5);
        assertFalse(cuentas.cuerpo().toLowerCase().contains("pin\""), "Nunca se expone el PIN ni su hash");

        assertEquals(200, llamar(GET, "/api/core/cuentas/1001-2002-3001", null).status());
        assertEquals(200, llamar(GET, "/api/core/cuentas/4532000000000001", null).status());
        assertEquals(404, llamar(GET, "/api/core/cuentas/NO-EXISTE", null).status());
        assertEquals(200, llamar(GET, "/api/core/cuentas/1001-2002-3001/transacciones?limite=5", null).status());
        assertEquals(404, llamar(GET, "/api/core/cuentas/NO-EXISTE/transacciones", null).status());
        assertEquals(200, llamar(GET, "/api/core/transacciones?limite=3", null).status());

        assertEquals(401, llamar(GET, "/api/core/admin/sesion", null).status());
        Respuesta sesion = comoAdmin(GET, "/api/core/admin/sesion", null);
        assertEquals(200, sesion.status());
        assertEquals(adminUsuario, sesion.json().get("usuario").asString());
        assertEquals(401, llamar(GET, "/api/core/admin/sesion", null, h -> h.setBasicAuth("otro", "x")).status());
    }

    @Test
    @DisplayName("Alta de cuentas: 401 sin admin, 201 creada, 409 duplicada y 400 con datos inválidos")
    void testAltaDeCuentas() {
        Map<String, Object> nueva = Map.of("titular", "Ana Prueba", "numeroCuenta", "API-" + nuevoStan(),
                "saldoInicial", 250, "limiteDiario", 100, "pin", "2468", "banco", "Banrural");
        assertEquals(401, llamar(POST, "/api/core/cuentas", nueva).status());

        Respuesta creada = comoAdmin(POST, "/api/core/cuentas", nueva);
        assertEquals(201, creada.status());
        JsonNode json = creada.json();
        assertEquals("ANA PRUEBA", json.get("titular").asString());
        assertEquals("BANRURAL", json.get("banco").asString());
        assertNull(json.get("pin"));

        assertEquals(409, comoAdmin(POST, "/api/core/cuentas", nueva).status());
        Respuesta pinCorto = comoAdmin(POST, "/api/core/cuentas", Map.of("pin", "12"));
        assertEquals(400, pinCorto.status());
        assertTrue(pinCorto.json().get("error").asString().contains("PIN"));
        assertEquals(400, comoAdmin(POST, "/api/core/cuentas", Map.of("saldoInicial", -1)).status());
        assertEquals(400, comoAdmin(POST, "/api/core/cuentas", Map.of("estado", "INVENTADO")).status());
    }

    @Test
    @DisplayName("Edición, recarga, bloqueo y borrado: validaciones y protección de administrador")
    void testOperacionesAdministrativas() {
        CuentaEntity c = crearCuenta("100.00", "500.00", "1234");
        String base = "/api/core/cuentas/" + c.getNumeroCuenta();

        assertEquals(401, llamar(PUT, base, Map.of("titular", "X")).status());
        assertEquals(200, comoAdmin(PUT, base, Map.of("titular", "Editado", "limiteDiario", 50)).status());
        assertEquals(400, comoAdmin(PUT, base, Map.of("saldoDisponible", -10)).status());
        assertEquals(404, comoAdmin(PUT, "/api/core/cuentas/NO-EXISTE", Map.of("titular", "X")).status());

        assertEquals(401, llamar(POST, base + "/recargar?monto=10", null).status());
        assertEquals(400, comoAdmin(POST, base + "/recargar?monto=-5000", null).status(), "No se permiten recargas negativas");
        assertEquals(400, comoAdmin(POST, base + "/recargar?monto=0", null).status());
        Respuesta recarga = comoAdmin(POST, base + "/recargar?monto=25.50", null);
        assertEquals(200, recarga.status());
        assertEquals(new BigDecimal("125.50"), saldo(c.getNumeroCuenta()));

        assertEquals("BLOCKED", comoAdmin(POST, base + "/toggle-bloqueo", null).json().get("estado").asString());
        assertEquals(404, comoAdmin(POST, "/api/core/cuentas/NO-EXISTE/toggle-bloqueo", null).status());

        assertEquals(401, llamar(DELETE, base, null).status());
        assertEquals(204, comoAdmin(DELETE, base, null).status());
        assertEquals(404, comoAdmin(DELETE, base, null).status());
    }

    @Test
    @DisplayName("Reset del entorno de demostración solo para administrador")
    void testReset() {
        assertEquals(401, llamar(POST, "/api/core/reset", null).status());
        assertEquals(200, comoAdmin(POST, "/api/core/reset", null).status());
    }

    @Test
    @DisplayName("Actuator: health público con el socket TCP y DB; métricas solo para administrador")
    void testActuator() {
        Respuesta health = llamar(GET, "/actuator/health", null);
        assertEquals(200, health.status());
        JsonNode json = health.json();
        assertEquals("UP", json.get("status").asString());
        assertEquals("UP", json.get("components").get("tcpIso").get("status").asString());
        assertEquals("UP", json.get("components").get("db").get("status").asString());

        assertEquals(401, llamar(GET, "/actuator/metrics", null).status());
        assertEquals(200, comoAdmin(GET, "/actuator/metrics", null).status());
        Respuesta conDetalle = comoAdmin(GET, "/actuator/health", null);
        assertNotNull(conDetalle.json().get("components").get("tcpIso").get("details").get("puerto"));
    }
}
