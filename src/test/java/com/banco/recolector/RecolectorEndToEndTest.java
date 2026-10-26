package com.banco.recolector;

import com.banco.recolector.corebancario.entity.CuentaEntity;
import com.banco.recolector.iso.IsoResponseCode;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import org.junit.jupiter.api.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Pruebas End-to-End del Recolector ISO 8583 sobre Socket TCP (Spring Integration IP) y Virtual Threads")
class RecolectorEndToEndTest extends IntegracionBase {

    private static final String CUENTA_DEMO = "1001-2002-3001";
    private static final String PAN_DEMO = "4532000000000001";

    @Test
    @Order(1)
    @DisplayName("E2E: Transacción Financiera Exitosa (MTI 0200 -> 0210, DE 39='00', Débito en BD)")
    void testTransaccionExitosaConDebitoReal() throws Exception {
        BigDecimal saldoInicial = saldo(CUENTA_DEMO);
        BigDecimal montoCompra = new BigDecimal("100.00");

        IsoMessage req = isoService.createFinancialRequest(PAN_DEMO, "000000", montoCompra,
                "555001", "262722555001", "POS00001", CUENTA_DEMO);
        IsoMessage resp = enviarTcp(req);

        assertEquals(0x0210, resp.getType(), "El MTI de respuesta debe ser 0210");
        assertEquals(IsoResponseCode.APROBADA, isoService.getFieldValue(resp, 39), "DE 39 debe ser '00'");
        assertNotNull(isoService.getFieldValue(resp, 38), "DE 38 debe contener el código de autorización");
        assertEquals("555001", isoService.getFieldValue(resp, 11), "STAN debe coincidir");
        assertEquals("262722555001", isoService.getFieldValue(resp, 37), "RRN debe coincidir");
        assertEquals(saldoInicial.subtract(montoCompra), saldo(CUENTA_DEMO), "El saldo refleja el débito de Q100.00");
    }

    @Test
    @Order(2)
    @DisplayName("E2E: Garantía de Idempotencia (Retransmisión RRN/STAN no duplica débito)")
    void testIdempotenciaRetransmision() throws Exception {
        BigDecimal saldoAntes = saldo(CUENTA_DEMO);
        IsoMessage reqDuplicado = isoService.createFinancialRequest(PAN_DEMO, "000000", new BigDecimal("100.00"),
                "555001", "262722555001", "POS00001", CUENTA_DEMO);

        IsoMessage resp = enviarTcp(reqDuplicado);
        assertEquals(IsoResponseCode.APROBADA, isoService.getFieldValue(resp, 39));
        assertEquals(saldoAntes, saldo(CUENTA_DEMO), "El saldo no debe debitarse dos veces gracias a la idempotencia");
    }

    @Test
    @Order(3)
    @DisplayName("E2E: Fondos Insuficientes (MTI 0200 -> 0210, DE 39='51')")
    void testFondosInsuficientes() throws Exception {
        IsoMessage req = isoService.createFinancialRequest(PAN_DEMO, "000000", new BigDecimal("88888.00"),
                "555002", "262722555002", "POS00001", CUENTA_DEMO);
        IsoMessage resp = enviarTcp(req);
        assertEquals(0x0210, resp.getType());
        assertEquals(IsoResponseCode.FONDOS_INSUFICIENTES, isoService.getFieldValue(resp, 39), "DE 39 debe ser '51'");
        assertNotNull(isoService.getFieldValue(resp, 54), "Se informa el saldo en DE 54");
    }

    @Test
    @Order(4)
    @DisplayName("E2E: Cuenta Inexistente (MTI 0200 -> 0210, DE 39='14')")
    void testCuentaInexistente() throws Exception {
        IsoMessage req = isoService.createFinancialRequest(null, "000000", new BigDecimal("50.00"),
                "555003", "262722555003", "POS00001", "CUENTA-FANTASMA-999");
        assertEquals(IsoResponseCode.CUENTA_INVALIDA, isoService.getFieldValue(enviarTcp(req), 39));
    }

    @Test
    @Order(5)
    @DisplayName("E2E: Diagnóstico de Red / Echo Test (MTI 0800 -> 0810, DE 39='00')")
    void testEchoTestRed() throws Exception {
        IsoMessage req = isoService.getMessageFactory().newMessage(0x0800);
        req.setValue(11, "555004", IsoType.NUMERIC, 6);
        req.setValue(70, "301", IsoType.NUMERIC, 3);
        IsoMessage resp = enviarTcp(req);
        assertEquals(0x0810, resp.getType());
        assertEquals(IsoResponseCode.APROBADA, isoService.getFieldValue(resp, 39));
    }

    @Test
    @Order(6)
    @DisplayName("E2E: Retiro ATM con PIN (DE 52) y Reversa 0420 que devuelve el dinero")
    void testRetiroYReversaPorTcp() throws Exception {
        CuentaEntity c = crearCuenta("400.00", "500.00", "2468");
        String rrn = nuevoRrn();

        IsoMessage retiro = isoService.createFinancialRequest(c.getPan(), "010000", new BigDecimal("150.00"),
                nuevoStan(), rrn, "ATM00001", c.getNumeroCuenta());
        isoService.agregarPinBlock(retiro, "2468", c.getPan());
        assertEquals("00", isoService.getFieldValue(enviarTcp(retiro), 39));
        assertEquals(new BigDecimal("250.00"), saldo(c.getNumeroCuenta()));

        IsoMessage reversa = isoService.crearSolicitud(0x0420, c.getPan(), "010000", new BigDecimal("150.00"),
                nuevoStan(), rrn, "ATM00001", c.getNumeroCuenta());
        IsoMessage resp = enviarTcp(reversa);
        assertEquals(0x0430, resp.getType());
        assertEquals("00", isoService.getFieldValue(resp, 39));
        assertEquals(new BigDecimal("400.00"), saldo(c.getNumeroCuenta()));
    }

    @Test
    @Order(7)
    @DisplayName("E2E: Varias transacciones por la misma conexión TCP (keep-alive)")
    void testVariasTramasPorConexion() throws Exception {
        CuentaEntity c = crearCuenta("10.00", "500.00", "1234");
        try (Socket socket = new Socket("localhost", tcpPort)) {
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            for (int i = 0; i < 5; i++) {
                byte[] trama = isoService.encode(isoService.createFinancialRequest(c.getPan(), "000000", BigDecimal.ONE,
                        nuevoStan(), nuevoRrn(), "POS00001", c.getNumeroCuenta()));
                dos.writeShort(trama.length);
                dos.write(trama);
                dos.flush();
                byte[] resp = new byte[dis.readUnsignedShort()];
                dis.readFully(resp);
                assertEquals("00", isoService.getFieldValue(isoService.parse(resp), 39));
            }
        }
        assertEquals(new BigDecimal("5.00"), saldo(c.getNumeroCuenta()));
    }

    @Test
    @Order(8)
    @DisplayName("E2E: Trama ilegible -> respuesta ISO 0210 con DE 39='30' (no un texto ni un cierre)")
    void testTramaIlegible() throws Exception {
        IsoMessage resp = isoService.parse(enviarTcpBytes("ISO0150000500200XYZ".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0x0210, resp.getType());
        assertEquals(IsoResponseCode.ERROR_FORMATO, isoService.getFieldValue(resp, 39));

        IsoMessage basura = isoService.parse(enviarTcpBytes("HOLA!".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(IsoResponseCode.ERROR_FORMATO, isoService.getFieldValue(basura, 39));
    }

    @Test
    @Order(9)
    @DisplayName("E2E: Prefijo de longitud mayor al máximo permitido -> el servidor cierra la conexión")
    void testTramaDemasiadoGrande() throws Exception {
        try (Socket socket = new Socket("localhost", tcpPort)) {
            socket.setSoTimeout(5000);
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            dos.writeShort(60000);
            dos.write(new byte[100]);
            dos.flush();
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            assertThrows(Exception.class, dis::readUnsignedShort, "No hay respuesta: la conexión se cierra");
        }
    }

    @Test
    @Order(10)
    @DisplayName("E2E: DE obligatorio ausente (sin STAN) -> DE 39='30'")
    void testSinStan() throws Exception {
        IsoMessage req = isoService.createFinancialRequest(PAN_DEMO, "000000", BigDecimal.ONE,
                null, "262722555010", "POS00001", CUENTA_DEMO);
        assertEquals(IsoResponseCode.ERROR_FORMATO, isoService.getFieldValue(enviarTcp(req), 39));
    }

    @Test
    @Order(11)
    @DisplayName("Healthcheck del binario (--healthcheck): 0 con la app arriba, 1 sin servidor")
    void testHealthcheck() throws Exception {
        assertEquals(0, RecolectorApplication.verificarSalud(String.valueOf(httpPort)));
        int libre;
        try (var s = new java.net.ServerSocket(0)) {
            libre = s.getLocalPort();
        }
        assertEquals(1, RecolectorApplication.verificarSalud(String.valueOf(libre)));
    }
}
