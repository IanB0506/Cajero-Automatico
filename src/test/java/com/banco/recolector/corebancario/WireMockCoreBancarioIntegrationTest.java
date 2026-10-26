package com.banco.recolector.corebancario;

import com.banco.recolector.config.CoreBancarioProperties;
import com.banco.recolector.iso.IsoResponseCode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Pruebas de Integración con WireMock para el Core Bancario")
class WireMockCoreBancarioIntegrationTest {

    private static WireMockServer wireMockServer;
    private CoreBancarioClient coreBancarioClient;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
        coreBancarioClient = new CoreBancarioClient(baseUrl(), 1000);
    }

    private static String baseUrl() {
        return "http://localhost:" + wireMockServer.port() + "/api/core";
    }

    private static TransaccionCoreRequest solicitud(String cuenta, String monto, String rrn) {
        return new TransaccionCoreRequest(rrn, "123456", "4532000000000001", cuenta, null,
                new BigDecimal(monto), "COMPRA", "POS0001", "000000", null);
    }

    private static TransaccionCoreResponse procesar(CoreBancarioClient cliente, String rrn) {
        return cliente.procesarTransaccion(solicitud("1001-2002-3001", "50.00", rrn));
    }

    @Test
    @DisplayName("Escenario 1: Transacción Exitosa 200 OK -> DE 39='00' y API Key enviada")
    void testTransaccionExitosa() {
        stubFor(post(urlEqualTo("/api/core/transacciones"))
                .willReturn(okJson("""
                        {"exitosa": true, "codigoRespuesta": "00", "codigoAutorizacion": "AUTH88",
                         "saldoDisponible": 874.50, "saldoContable": 874.50, "mensaje": "Transacción aprobada",
                         "rrn": "262722100001", "timestamp": "2026-10-06T20:00:00"}
                        """)));

        TransaccionCoreResponse resp = coreBancarioClient.procesarTransaccion(
                solicitud("1001-2002-3001", "125.50", "262722100001"));

        assertTrue(resp.exitosa());
        assertEquals(IsoResponseCode.APROBADA, resp.codigoRespuesta());
        assertEquals("AUTH88", resp.codigoAutorizacion());
        assertEquals(new BigDecimal("874.50"), resp.saldoDisponible());
        verify(postRequestedFor(urlEqualTo("/api/core/transacciones"))
                .withHeader("X-API-Key", equalTo("clave-de-prueba"))
                .withRequestBody(matchingJsonPath("$.rrn", equalTo("262722100001")))
                .withRequestBody(containing("\"monto\":125.50")));
    }

    @Test
    @DisplayName("Escenario 2: Fondos Insuficientes 422 con cuerpo -> DE 39='51'")
    void testFondosInsuficientes() {
        stubFor(post(urlEqualTo("/api/core/transacciones"))
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"exitosa": false, "codigoRespuesta": "51", "saldoDisponible": 10.00,
                                 "saldoContable": 10.00, "mensaje": "Saldo insuficiente", "rrn": "262722100002"}
                                """)));

        TransaccionCoreResponse resp = procesar(coreBancarioClient, "262722100002");
        assertFalse(resp.exitosa());
        assertEquals(IsoResponseCode.FONDOS_INSUFICIENTES, resp.codigoRespuesta());
        assertEquals(new BigDecimal("10.00"), resp.saldoDisponible());
    }

    @Test
    @DisplayName("Escenario 3: Cuenta No Existe 404 sin cuerpo -> DE 39='14'")
    void testCuentaNoExiste() {
        stubFor(post(urlEqualTo("/api/core/transacciones")).willReturn(aResponse().withStatus(404)));
        assertEquals(IsoResponseCode.CUENTA_INVALIDA, procesar(coreBancarioClient, "262722100003").codigoRespuesta());
    }

    @Test
    @DisplayName("Escenario 4: Cuenta Bloqueada 403 sin cuerpo -> DE 39='05'")
    void testCuentaBloqueada() {
        stubFor(post(urlEqualTo("/api/core/transacciones")).willReturn(aResponse().withStatus(403)));
        assertEquals(IsoResponseCode.TARJETA_BLOQUEADA, procesar(coreBancarioClient, "262722100004").codigoRespuesta());
    }

    @Test
    @DisplayName("Escenario 5: Error 500 persistente -> 3 intentos y DE 39='91'")
    void testErrorInternoCore() {
        stubFor(post(urlEqualTo("/api/core/transacciones"))
                .willReturn(aResponse().withStatus(500).withBody("{\"mensaje\": \"Internal Database Error\"}")));

        TransaccionCoreResponse resp = procesar(coreBancarioClient, "262722100005");
        assertFalse(resp.exitosa());
        assertEquals(IsoResponseCode.HOST_NO_DISPONIBLE, resp.codigoRespuesta());
        verify(3, postRequestedFor(urlEqualTo("/api/core/transacciones")));
    }

    @Test
    @DisplayName("Escenario 6: Gateway Timeout 504 -> DE 39='68'")
    void testGatewayTimeout504() {
        stubFor(post(urlEqualTo("/api/core/transacciones")).willReturn(aResponse().withStatus(504)));
        assertEquals(IsoResponseCode.TIMEOUT, procesar(coreBancarioClient, "262722100006").codigoRespuesta());
    }

    @Test
    @DisplayName("Escenario 7: Consulta de saldo exitosa y cuenta inexistente vía RestClient")
    void testConsultaSaldo() {
        stubFor(get(urlEqualTo("/api/core/cuentas/1001-2002-3001"))
                .willReturn(okJson("""
                        {"numeroCuenta": "1001-2002-3001", "titular": "Juan Perez", "saldoDisponible": 1000.00,
                         "saldoContable": 1000.00, "estado": "ACTIVE"}
                        """)));
        stubFor(get(urlEqualTo("/api/core/cuentas/NO-EXISTE")).willReturn(aResponse().withStatus(404)));

        Optional<CuentaDto> cuenta = coreBancarioClient.consultarCuenta("1001-2002-3001");
        assertTrue(cuenta.isPresent());
        assertEquals(new BigDecimal("1000.00"), cuenta.get().saldoDisponible());
        assertEquals("ACTIVE", cuenta.get().estado());
        assertTrue(coreBancarioClient.consultarCuenta("NO-EXISTE").isEmpty());
    }

    @Test
    @DisplayName("Escenario 8: Fallo transitorio 503 seguido de 200 -> el reintento aprueba")
    void testReintentoTrasFalloTransitorio() {
        stubFor(post(urlEqualTo("/api/core/transacciones")).inScenario("caida")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recuperado"));
        stubFor(post(urlEqualTo("/api/core/transacciones")).inScenario("caida")
                .whenScenarioStateIs("recuperado")
                .willReturn(okJson("{\"exitosa\": true, \"codigoRespuesta\": \"00\", \"codigoAutorizacion\": \"100777\"}")));

        TransaccionCoreResponse resp = procesar(coreBancarioClient, "262722100008");
        assertTrue(resp.exitosa());
        assertEquals("100777", resp.codigoAutorizacion());
        verify(2, postRequestedFor(urlEqualTo("/api/core/transacciones")));
    }

    @Test
    @DisplayName("Escenario 9: El Core tarda más que el read-timeout -> DE 39='68'")
    void testTimeoutDeLectura() {
        stubFor(post(urlEqualTo("/api/core/transacciones"))
                .willReturn(okJson("{\"exitosa\": true, \"codigoRespuesta\": \"00\"}").withFixedDelay(1500)));
        CoreBancarioClient clienteImpaciente = new CoreBancarioClient(RestClient.builder(),
                new CoreBancarioProperties(baseUrl(), "k", 500, 300, 0, 10));
        assertEquals(IsoResponseCode.TIMEOUT, procesar(clienteImpaciente, "262722100009").codigoRespuesta());
    }

    @Test
    @DisplayName("Escenario 10: Core caído (conexión rechazada) -> reintentos y DE 39='91'")
    void testCoreCaido() throws Exception {
        int puertoLibre;
        try (ServerSocket s = new ServerSocket(0)) {
            puertoLibre = s.getLocalPort();
        }
        CoreBancarioClient sinCore = new CoreBancarioClient("http://localhost:" + puertoLibre + "/api/core", 500);
        assertEquals(IsoResponseCode.HOST_NO_DISPONIBLE, procesar(sinCore, "262722100010").codigoRespuesta());
        assertTrue(sinCore.consultarCuenta("1001-2002-3001").isEmpty());
    }

    @Test
    @DisplayName("Escenario 11: Solicitud rechazada por formato 400 -> DE 39 del cuerpo o '30'")
    void testErrorDeFormato() {
        stubFor(post(urlEqualTo("/api/core/transacciones")).willReturn(aResponse().withStatus(400).withBody("no-json")));
        assertEquals(IsoResponseCode.ERROR_FORMATO, procesar(coreBancarioClient, "262722100011").codigoRespuesta());

        stubFor(post(urlEqualTo("/api/core/transacciones")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"exitosa\": false, \"codigoRespuesta\": \"13\", \"mensaje\": \"Monto inválido\"}")));
        assertEquals(IsoResponseCode.MONTO_INVALIDO, procesar(coreBancarioClient, "262722100012").codigoRespuesta());
    }

    @Test
    @DisplayName("Escenario 12: Mapeo de estados HTTP sin cuerpo ISO a DE 39")
    void testMapeoEstadosHttp() {
        assertEquals("30", CoreBancarioClient.codigoPorEstadoHttp(400));
        assertEquals("05", CoreBancarioClient.codigoPorEstadoHttp(423));
        assertEquals("54", CoreBancarioClient.codigoPorEstadoHttp(410));
        assertEquals("51", CoreBancarioClient.codigoPorEstadoHttp(422));
        assertEquals("61", CoreBancarioClient.codigoPorEstadoHttp(429));
        assertEquals("68", CoreBancarioClient.codigoPorEstadoHttp(408));
        assertEquals("91", CoreBancarioClient.codigoPorEstadoHttp(502));
        assertEquals("96", CoreBancarioClient.codigoPorEstadoHttp(401));

        stubFor(post(urlEqualTo("/api/core/transacciones")).willReturn(aResponse().withStatus(401)));
        assertEquals(IsoResponseCode.ERROR_SISTEMA, procesar(coreBancarioClient, "262722100013").codigoRespuesta());
    }
}
