package com.banco.recolector;

import com.banco.recolector.corebancario.entity.CuentaEntity;
import com.banco.recolector.corebancario.repository.CuentaRepository;
import com.banco.recolector.iso.IsoMessageService;
import com.banco.recolector.iso.IsoResponseCode;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Pruebas End-to-End del Recolector Transaccional ISO 8583 sobre Socket TCP y Virtual Threads")
public class RecolectorEndToEndTest {

    private static final String TCP_HOST = "localhost";
    private static final int TCP_PORT = 15000;

    @Autowired
    private IsoMessageService isoService;

    @Autowired
    private CuentaRepository cuentaRepository;

    /**
     * Utilidad para enviar una trama ISO 8583 con enmarcado bancario (prefijo short de 2 bytes)
     * a través del socket TCP atendido por Virtual Threads y recibir la respuesta.
     */
    private IsoMessage enviarTramaTcp(IsoMessage request) throws Exception {
        byte[] requestBytes = isoService.encode(request);

        try (Socket socket = new Socket(TCP_HOST, TCP_PORT)) {
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);

            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            DataInputStream dis = new DataInputStream(socket.getInputStream());

            // Enviar con prefijo de longitud de 2 bytes
            dos.writeShort(requestBytes.length);
            dos.write(requestBytes);
            dos.flush();

            // Leer respuesta con prefijo de 2 bytes
            int respLen = dis.readUnsignedShort();
            assertTrue(respLen > 0, "La longitud de respuesta debe ser positiva");

            byte[] respBytes = new byte[respLen];
            dis.readFully(respBytes);

            return isoService.parse(respBytes);
        }
    }

    @Test
    @Order(1)
    @DisplayName("E2E: Transacción Financiera Exitosa (MTI 0200 -> 0210, DE 39='00', Débito en BD)")
    void testTransaccionExitosaConDebitoReal() throws Exception {
        String numCuenta = "1001-2002-3001";
        BigDecimal saldoInicial = cuentaRepository.findByNumeroCuenta(numCuenta)
                .map(CuentaEntity::getSaldoDisponible)
                .orElse(BigDecimal.ZERO);

        BigDecimal montoCompra = new BigDecimal("100.00");

        // Construir mensaje ISO MTI 0200
        IsoMessage req = isoService.createFinancialRequest(
                "4532000000000001",
                "000000",
                montoCompra,
                "555001",
                "262722555001",
                "POS00001",
                numCuenta
        );

        IsoMessage resp = enviarTramaTcp(req);

        assertNotNull(resp, "La respuesta no debe ser nula");
        assertEquals(0x0210, resp.getType(), "El MTI de respuesta debe ser 0210");
        assertEquals(IsoResponseCode.APROBADA, isoService.getFieldValue(resp, 39), "DE 39 debe ser '00'");
        assertNotNull(isoService.getFieldValue(resp, 38), "DE 38 debe contener el código de autorización");
        assertEquals("555001", isoService.getFieldValue(resp, 11), "STAN debe coincidir");
        assertEquals("262722555001", isoService.getFieldValue(resp, 37), "RRN debe coincidir");

        // Validar que el saldo en la base de datos se decrementó exactamente en $100.00
        Optional<CuentaEntity> cuentaActualizada = cuentaRepository.findByNumeroCuenta(numCuenta);
        assertTrue(cuentaActualizada.isPresent());
        assertEquals(saldoInicial.subtract(montoCompra), cuentaActualizada.get().getSaldoDisponible(),
                "El saldo disponible debe reflejar el débito de $100.00");
    }

    @Test
    @Order(2)
    @DisplayName("E2E: Garantía de Idempotencia (Retransmisión RRN/STAN no duplica débito)")
    void testIdempotenciaRetransmision() throws Exception {
        String numCuenta = "1001-2002-3001";
        BigDecimal saldoAntes = cuentaRepository.findByNumeroCuenta(numCuenta)
                .map(CuentaEntity::getSaldoDisponible)
                .orElseThrow();

        // Mismo RRN y STAN que en el test anterior
        IsoMessage reqDuplicado = isoService.createFinancialRequest(
                "4532000000000001",
                "000000",
                new BigDecimal("100.00"),
                "555001",
                "262722555001",
                "POS00001",
                numCuenta
        );

        IsoMessage resp = enviarTramaTcp(reqDuplicado);

        assertNotNull(resp);
        assertEquals(0x0210, resp.getType());
        assertEquals(IsoResponseCode.APROBADA, isoService.getFieldValue(resp, 39));

        // El saldo NO debe haber cambiado
        BigDecimal saldoDespues = cuentaRepository.findByNumeroCuenta(numCuenta)
                .map(CuentaEntity::getSaldoDisponible)
                .orElseThrow();
        assertEquals(saldoAntes, saldoDespues, "El saldo no debe debitarse dos veces gracias a la idempotencia");
    }

    @Test
    @Order(3)
    @DisplayName("E2E: Fondos Insuficientes (MTI 0200 -> 0210, DE 39='51')")
    void testFondosInsuficientes() throws Exception {
        String numCuenta = "1001-2002-3001";
        BigDecimal montoExcesivo = new BigDecimal("88888.00");

        IsoMessage req = isoService.createFinancialRequest(
                "4532000000000001",
                "000000",
                montoExcesivo,
                "555002",
                "262722555002",
                "POS00001",
                numCuenta
        );

        IsoMessage resp = enviarTramaTcp(req);

        assertNotNull(resp);
        assertEquals(0x0210, resp.getType());
        assertEquals(IsoResponseCode.FONDOS_INSUFICIENTES, isoService.getFieldValue(resp, 39), "DE 39 debe ser '51'");
    }

    @Test
    @Order(4)
    @DisplayName("E2E: Cuenta Inexistente (MTI 0200 -> 0210, DE 39='14')")
    void testCuentaInexistente() throws Exception {
        IsoMessage req = isoService.createFinancialRequest(
                "4532000000000001",
                "000000",
                new BigDecimal("50.00"),
                "555003",
                "262722555003",
                "POS00001",
                "CUENTA-FANTASMA-999"
        );

        IsoMessage resp = enviarTramaTcp(req);

        assertNotNull(resp);
        assertEquals(0x0210, resp.getType());
        assertEquals(IsoResponseCode.CUENTA_INVALIDA, isoService.getFieldValue(resp, 39), "DE 39 debe ser '14'");
    }

    @Test
    @Order(5)
    @DisplayName("E2E: Diagnóstico de Red / Echo Test (MTI 0800 -> 0810, DE 39='00')")
    void testEchoTestRed() throws Exception {
        IsoMessage req = isoService.getMessageFactory().newMessage(0x0800);
        req.setValue(11, "555004", IsoType.NUMERIC, 6);
        req.setValue(70, "301", IsoType.NUMERIC, 3);

        IsoMessage resp = enviarTramaTcp(req);

        assertNotNull(resp);
        assertEquals(0x0810, resp.getType());
        assertEquals(IsoResponseCode.APROBADA, isoService.getFieldValue(resp, 39));
    }
}
