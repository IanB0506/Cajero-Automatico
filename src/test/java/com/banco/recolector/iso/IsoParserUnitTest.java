package com.banco.recolector.iso;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Pruebas Unitarias del Motor ISO 8583 (j8583 Engine)")
public class IsoParserUnitTest {

    private IsoMessageService isoService;

    @BeforeEach
    public void setUp() {
        isoService = new IsoMessageService();
        isoService.init();
    }

    @Test
    @DisplayName("Codificación y Decodificación de Solicitud Financiera MTI 0200")
    public void testEncodeAndParseMti0200() throws Exception {
        BigDecimal monto = new BigDecimal("125.50");
        IsoMessage req = isoService.createFinancialRequest(
                "4532000000000001",
                "000000",
                monto,
                "123456",
                "262722100001",
                "POS00001",
                "1001-2002-3001"
        );

        assertNotNull(req);
        assertEquals(0x0200, req.getType());

        byte[] encoded = isoService.encode(req);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        // Parsear de regreso
        IsoMessage parsed = isoService.parse(encoded);
        assertEquals(0x0200, parsed.getType());
        assertEquals("4532000000000001", isoService.getFieldValue(parsed, 2));
        assertEquals("000000", isoService.getFieldValue(parsed, 3));
        assertEquals("123456", isoService.getFieldValue(parsed, 11));
        assertEquals("262722100001", isoService.getFieldValue(parsed, 37));
        assertEquals("POS00001", isoService.getFieldValue(parsed, 41));
        assertEquals("1001-2002-3001", isoService.getFieldValue(parsed, 102));

        // Validación de monto DE 4
        BigDecimal parsedAmount = isoService.getAmountValue(parsed);
        assertEquals(0, monto.compareTo(parsedAmount));
    }

    @Test
    @DisplayName("Construcción de Respuesta Financiera MTI 0210 con DE 39='00' y DE 38")
    public void testCreateResponseMti0210() throws Exception {
        IsoMessage req = isoService.createFinancialRequest(
                "4532000000000001",
                "000000",
                new BigDecimal("50.00"),
                "100001",
                "262722100001",
                "POS00001",
                "1001-2002-3001"
        );

        IsoMessage resp = isoService.createResponse(req, IsoResponseCode.APROBADA, "AUTH99");

        assertNotNull(resp);
        assertEquals(0x0210, resp.getType(), "La respuesta debe tener MTI 0210");
        assertEquals("00", isoService.getFieldValue(resp, 39), "DE 39 debe ser 00");
        assertEquals("AUTH99", isoService.getFieldValue(resp, 38), "DE 38 debe contener el código de autorización");
        assertEquals("100001", isoService.getFieldValue(resp, 11), "STAN debe preservarse");
        assertEquals("262722100001", isoService.getFieldValue(resp, 37), "RRN debe preservarse");
    }

    @Test
    @DisplayName("Extracción Segura de Data Elements y Enmascaramiento PCI-DSS")
    public void testSafeExtractionAndPciDssMasking() {
        IsoMessage req = isoService.createFinancialRequest(
                "4532001234560001",
                "010000",
                new BigDecimal("100.00"),
                "654321",
                "262722654321",
                "ATM00001",
                "CUENTA-999"
        );

        IsoTransactionDto dto = isoService.extractTransaction(req);
        assertEquals("0200", dto.mti());
        assertEquals("4532001234560001", dto.pan());
        assertEquals("453200******0001", dto.panEnmascarado(), "Debe enmascarar dígitos intermedios");
        assertEquals("262722654321:654321", dto.claveIdempotencia());
        assertEquals("ATM00001", dto.terminalId());
    }

    @Test
    @DisplayName("Network Management Echo Test (0800 -> 0810)")
    public void testEchoTest0800() {
        IsoMessage req = isoService.getMessageFactory().newMessage(0x0800);
        req.setValue(70, "301", IsoType.NUMERIC, 3);

        IsoMessage resp = isoService.createResponse(req, IsoResponseCode.APROBADA, null);
        assertEquals(0x0810, resp.getType());
        assertEquals("00", isoService.getFieldValue(resp, 39));
    }

    @Test
    @DisplayName("Rechazo de trama nula o vacía")
    public void testInvalidInputHandling() {
        assertThrows(IllegalArgumentException.class, () -> isoService.parse((byte[]) null));
        assertThrows(IllegalArgumentException.class, () -> isoService.parse(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> isoService.parse("   "));
    }
}
