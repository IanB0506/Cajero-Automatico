package com.banco.recolector.corebancario;

import com.banco.recolector.iso.IsoResponseCode;
import com.banco.recolector.iso.IsoTransactionDto;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Pruebas de Integración con WireMock para el Core Bancario")
public class WireMockCoreBancarioIntegrationTest {

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
        String wireMockBaseUrl = "http://localhost:" + wireMockServer.port() + "/api/core";
        coreBancarioClient = new CoreBancarioClient(wireMockBaseUrl, 2000);
    }

    private IsoTransactionDto createTestTransaction(String cuenta, BigDecimal monto, String stan, String rrn) {
        return new IsoTransactionDto(
                "0200",
                "4532000000000001",
                "000000",
                monto,
                stan,
                rrn,
                "POS0001",
                "MERCH001",
                cuenta,
                null,
                null
        );
    }

    @Test
    @DisplayName("Escenario 1: Transacción Exitosa 200 OK -> DE 39='00'")
    void testTransaccionExitosa() {
        stubFor(post(urlEqualTo("/api/core/transacciones"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "exitosa": true,
                                  "codigoRespuesta": "00",
                                  "codigoAutorizacion": "AUTH88",
                                  "saldoDisponible": 874.50,
                                  "saldoContable": 874.50,
                                  "mensaje": "Transacción aprobada",
                                  "rrn": "262722100001",
                                  "timestamp": "2026-10-06T20:00:00"
                                }
                                """)));

        IsoTransactionDto tx = createTestTransaction("1001-2002-3001", new BigDecimal("125.50"), "123456", "262722100001");
        TransaccionCoreResponse resp = coreBancarioClient.procesarTransaccion(tx);

        assertNotNull(resp);
        assertTrue(resp.exitosa());
        assertEquals(IsoResponseCode.APROBADA, resp.codigoRespuesta());
        assertEquals("AUTH88", resp.codigoAutorizacion());
        assertEquals(new BigDecimal("874.50"), resp.saldoDisponible());
    }

    @Test
    @DisplayName("Escenario 2: Fondos Insuficientes 422 Unprocessable Entity -> DE 39='51'")
    void testFondosInsuficientes() {
        stubFor(post(urlEqualTo("/api/core/transacciones"))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "exitosa": false,
                                  "codigoRespuesta": "51",
                                  "codigoAutorizacion": null,
                                  "saldoDisponible": 10.00,
                                  "saldoContable": 10.00,
                                  "mensaje": "Saldo insuficiente para procesar la transacción",
                                  "rrn": "262722100002"
                                }
                                """)));

        IsoTransactionDto tx = createTestTransaction("1001-2002-3001", new BigDecimal("9999.00"), "123457", "262722100002");
        TransaccionCoreResponse resp = coreBancarioClient.procesarTransaccion(tx);

        assertNotNull(resp);
        assertFalse(resp.exitosa());
        assertEquals(IsoResponseCode.FONDOS_INSUFICIENTES, resp.codigoRespuesta());
    }

    @Test
    @DisplayName("Escenario 3: Cuenta No Existe 404 Not Found -> DE 39='14'")
    void testCuentaNoExiste() {
        stubFor(post(urlEqualTo("/api/core/transacciones"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "exitosa": false,
                                  "codigoRespuesta": "14",
                                  "codigoAutorizacion": null,
                                  "saldoDisponible": 0.00,
                                  "saldoContable": 0.00,
                                  "mensaje": "Cuenta inexistente",
                                  "rrn": "262722100003"
                                }
                                """)));

        IsoTransactionDto tx = createTestTransaction("CUENTA-INEXISTENTE", new BigDecimal("50.00"), "123458", "262722100003");
        TransaccionCoreResponse resp = coreBancarioClient.procesarTransaccion(tx);

        assertNotNull(resp);
        assertFalse(resp.exitosa());
        assertEquals(IsoResponseCode.CUENTA_INVALIDA, resp.codigoRespuesta());
    }

    @Test
    @DisplayName("Escenario 4: Cuenta Bloqueada 403 Forbidden -> DE 39='05'")
    void testCuentaBloqueada() {
        stubFor(post(urlEqualTo("/api/core/transacciones"))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "exitosa": false,
                                  "codigoRespuesta": "05",
                                  "codigoAutorizacion": null,
                                  "saldoDisponible": 0.00,
                                  "saldoContable": 0.00,
                                  "mensaje": "Cuenta bloqueada o inactiva",
                                  "rrn": "262722100004"
                                }
                                """)));

        IsoTransactionDto tx = createTestTransaction("1001-2002-3003", new BigDecimal("50.00"), "123459", "262722100004");
        TransaccionCoreResponse resp = coreBancarioClient.procesarTransaccion(tx);

        assertNotNull(resp);
        assertFalse(resp.exitosa());
        assertEquals(IsoResponseCode.TARJETA_BLOQUEADA, resp.codigoRespuesta());
    }

    @Test
    @DisplayName("Escenario 5: Error Interno 500 del Core Bancario -> DE 39='91'")
    void testErrorInternoCore() {
        stubFor(post(urlEqualTo("/api/core/transacciones"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "mensaje": "Internal Database Error"
                                }
                                """)));

        IsoTransactionDto tx = createTestTransaction("1001-2002-3001", new BigDecimal("50.00"), "123460", "262722100005");
        TransaccionCoreResponse resp = coreBancarioClient.procesarTransaccion(tx);

        assertNotNull(resp);
        assertFalse(resp.exitosa());
        assertEquals(IsoResponseCode.HOST_NO_DISPONIBLE, resp.codigoRespuesta());
    }

    @Test
    @DisplayName("Escenario 6: Gateway Timeout 504 del Core Bancario -> DE 39='68'")
    void testTimeoutCore() {
        stubFor(post(urlEqualTo("/api/core/transacciones"))
                .willReturn(aResponse()
                        .withStatus(504)
                        .withFixedDelay(50)));

        IsoTransactionDto tx = createTestTransaction("1001-2002-3001", new BigDecimal("50.00"), "123461", "262722100006");
        TransaccionCoreResponse resp = coreBancarioClient.procesarTransaccion(tx);

        assertNotNull(resp);
        assertFalse(resp.exitosa());
        assertEquals(IsoResponseCode.TIMEOUT, resp.codigoRespuesta());
    }

    @Test
    @DisplayName("Escenario 7: Consulta de Saldo Exitosa via RestClient")
    void testConsultaSaldoExitosa() {
        stubFor(get(urlEqualTo("/api/core/cuentas/1001-2002-3001"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "numeroCuenta": "1001-2002-3001",
                                  "titular": "Juan Perez",
                                  "saldoDisponible": 1000.00,
                                  "saldoContable": 1000.00,
                                  "moneda": "840",
                                  "estado": "ACTIVA"
                                }
                                """)));

        CuentaDto cuenta = coreBancarioClient.consultarCuenta("1001-2002-3001");

        assertNotNull(cuenta);
        assertEquals("1001-2002-3001", cuenta.numeroCuenta());
        assertEquals(new BigDecimal("1000.00"), cuenta.saldoDisponible());
        assertEquals("ACTIVA", cuenta.estado());
    }
}
