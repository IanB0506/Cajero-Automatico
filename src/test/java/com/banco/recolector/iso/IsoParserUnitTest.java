package com.banco.recolector.iso;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Pruebas Unitarias del Motor ISO 8583 (j8583 Engine)")
class IsoParserUnitTest {

    private IsoMessageService isoService;

    @BeforeEach
    void setUp() {
        isoService = new IsoMessageService();
        isoService.init();
    }

    private IsoMessage solicitud0200() {
        return isoService.createFinancialRequest("4532000000000001", "000000", new BigDecimal("125.50"),
                "123456", "262722100001", "POS00001", "1001-2002-3001");
    }

    @Test
    @DisplayName("Codificación y decodificación de MTI 0200 con DE 3, 4, 11, 37 y 102")
    void testEncodeAndParseMti0200() throws Exception {
        IsoMessage req = solicitud0200();
        assertEquals(0x0200, req.getType());

        byte[] encoded = isoService.encode(req);
        String ascii = new String(encoded, StandardCharsets.US_ASCII);
        assertTrue(ascii.startsWith("ISO0150000500200"), "Header ISO + MTI 0200");

        IsoMessage parsed = isoService.parse(encoded);
        assertEquals(0x0200, parsed.getType());
        assertEquals("4532000000000001", isoService.getFieldValue(parsed, 2));
        assertEquals("000000", isoService.getFieldValue(parsed, 3));
        assertEquals("123456", isoService.getFieldValue(parsed, 11));
        assertEquals("262722100001", isoService.getFieldValue(parsed, 37));
        assertEquals("POS00001", isoService.getFieldValue(parsed, 41));
        assertEquals("320", isoService.getFieldValue(parsed, 49), "Moneda por defecto: Quetzal");
        assertEquals("1001-2002-3001", isoService.getFieldValue(parsed, 102));
        assertEquals(0, new BigDecimal("125.50").compareTo(isoService.getAmountValue(parsed)), "DE 4 en centavos sin pérdida");
    }

    @Test
    @DisplayName("DE 4: el monto viaja como 12 dígitos en centavos sin separador decimal")
    void testFormatoMontoDe4() {
        String ascii = isoService.encodeToString(solicitud0200());
        assertTrue(ascii.contains("000000012550"), "125.50 -> 000000012550");
    }

    @Test
    @DisplayName("Parseo sin header ISO (trama que empieza directamente con el MTI)")
    void testParseSinHeader() throws Exception {
        String conHeader = isoService.encodeToString(solicitud0200());
        IsoMessage parsed = isoService.parse(conHeader.substring(12));
        assertEquals(0x0200, parsed.getType());
        assertEquals("123456", isoService.getFieldValue(parsed, 11));
    }

    @Test
    @DisplayName("Respuesta 0210 con DE 38, DE 39, DE 54 y DE 103 se puede parsear de regreso")
    void testCreateResponseMti0210RoundTrip() throws Exception {
        IsoMessage req = solicitud0200();
        req.setValue(103, "1001-2002-3002", IsoType.LLVAR, 0);
        IsoMessage resp = isoService.createResponse(req, IsoResponseCode.APROBADA, "100123");
        resp.setValue(54, "1001320C0000001000001002320C000000100000", IsoType.LLLVAR, 0);

        IsoMessage parsed = isoService.parse(isoService.encode(resp));
        assertEquals(0x0210, parsed.getType());
        assertEquals("00", isoService.getFieldValue(parsed, 39));
        assertEquals("100123", isoService.getFieldValue(parsed, 38));
        assertEquals("123456", isoService.getFieldValue(parsed, 11), "STAN se preserva");
        assertEquals("262722100001", isoService.getFieldValue(parsed, 37), "RRN se preserva");
        assertEquals("1001-2002-3002", isoService.getFieldValue(parsed, 103));
        assertNotNull(isoService.getFieldValue(parsed, 54));
    }

    @Test
    @DisplayName("La respuesta nunca devuelve el PIN block (DE 52) ni campos fuera de su guía")
    void testRespuestaSinPinBlockNiCamposExtra() throws Exception {
        IsoMessage req = solicitud0200();
        isoService.agregarPinBlock(req, "1234", "4532000000000001");
        req.setValue(14, "2812", IsoType.DATE_EXP, 4);
        assertTrue(req.hasField(52));

        IsoMessage resp = isoService.createResponse(req, IsoResponseCode.APROBADA, null);
        assertFalse(resp.hasField(52), "DE 52 nunca se devuelve");
        assertFalse(resp.hasField(14), "DE 14 no está en la guía de la 0210");
        assertEquals(0x0210, isoService.parse(isoService.encode(resp)).getType());
    }

    @ParameterizedTest(name = "MTI {0} -> respuesta parseable")
    @ValueSource(ints = {0x0100, 0x0200, 0x0420})
    @DisplayName("Todas las respuestas (0110, 0210, 0430) incluyen DE 12/13 y se pueden parsear")
    void testRoundTripRespuestas(int mti) throws Exception {
        IsoMessage req = isoService.crearSolicitud(mti, "4532000000000001", "000000", new BigDecimal("10.00"),
                "000777", "262722000777", "ATM00001", "1001-2002-3001");
        IsoMessage resp = isoService.createResponse(req, IsoResponseCode.APROBADA, "100001");
        IsoMessage parsed = isoService.parse(isoService.encode(resp));
        assertEquals(mti + 0x10, parsed.getType());
        assertEquals("00", isoService.getFieldValue(parsed, 39));
        assertNotNull(isoService.getFieldValue(parsed, 12));
        assertNotNull(isoService.getFieldValue(parsed, 13));
    }

    @Test
    @DisplayName("Extracción segura de Data Elements y enmascaramiento PCI-DSS")
    void testSafeExtractionAndPciDssMasking() {
        IsoMessage req = isoService.createFinancialRequest("4532001234560001", "010000", new BigDecimal("100.00"),
                "654321", "262722654321", "ATM00001", "CUENTA-999");
        isoService.agregarPinBlock(req, "4321", "4532001234560001");
        req.setValue(103, "CUENTA-DESTINO", IsoType.LLVAR, 0);

        IsoTransactionDto dto = isoService.extractTransaction(req);
        assertEquals("0200", dto.mti());
        assertEquals("010000", dto.processingCode());
        assertEquals("453200******0001", dto.panEnmascarado());
        assertEquals("262722654321:654321", dto.claveIdempotencia());
        assertEquals("ATM00001", dto.terminalId());
        assertEquals("CUENTA-999", dto.cuenta());
        assertEquals("CUENTA-DESTINO", dto.cuentaDestino());
        assertEquals("320", dto.moneda());
        assertEquals(PinBlock.formato0("4321", "4532001234560001"), dto.pinBlock());
        assertFalse(dto.toString().contains(dto.pinBlock()), "El toString no expone el PIN block");
        assertFalse(dto.toString().contains("4532001234560001"), "El toString no expone el PAN");
    }

    @Test
    @DisplayName("Sin DE 102 la cuenta se toma del PAN (DE 2); sin DE 4 el monto es cero")
    void testCuentaPorPanYMontoAusente() {
        IsoMessage req = isoService.crearSolicitud(0x0100, "4532000000000001", "300000", null,
                "000001", "262722000001", "ATM00001", null);
        IsoTransactionDto dto = isoService.extractTransaction(req);
        assertEquals("4532000000000001", dto.cuenta());
        assertEquals(BigDecimal.ZERO, dto.amount());
    }

    @Test
    @DisplayName("Network Management Echo Test (0800 -> 0810)")
    void testEchoTest0800() throws Exception {
        IsoMessage req = isoService.getMessageFactory().newMessage(0x0800);
        req.setValue(11, "000123", IsoType.NUMERIC, 6);
        req.setValue(70, "301", IsoType.NUMERIC, 3);

        IsoMessage resp = isoService.parse(isoService.encode(isoService.createResponse(req, IsoResponseCode.APROBADA, null)));
        assertEquals(0x0810, resp.getType());
        assertEquals("00", isoService.getFieldValue(resp, 39));
        assertEquals("301", isoService.getFieldValue(resp, 70));
    }

    @Test
    @DisplayName("Trama ilegible con MTI 0200 -> respuesta 0210 con DE 39 = 30")
    void testRespuestaErrorFormatoConMtiLegible() throws Exception {
        byte[] basura = "ISO0150000500200ZZZZ-no-es-un-bitmap".getBytes(StandardCharsets.US_ASCII);
        IsoMessage resp = isoService.parse(isoService.encode(isoService.crearRespuestaErrorFormato(basura)));
        assertEquals(0x0210, resp.getType());
        assertEquals(IsoResponseCode.ERROR_FORMATO, isoService.getFieldValue(resp, 39));

        IsoMessage resp0110 = isoService.crearRespuestaErrorFormato("0100XYZ".getBytes(StandardCharsets.US_ASCII));
        assertEquals(0x0110, resp0110.getType());
    }

    @Test
    @DisplayName("Trama sin MTI reconocible -> respuesta 0210 con DE 39 = 30")
    void testRespuestaErrorFormatoSinMti() {
        assertEquals(0x0210, isoService.crearRespuestaErrorFormato("HOLA!".getBytes(StandardCharsets.US_ASCII)).getType());
        assertEquals(0x0210, isoService.crearRespuestaErrorFormato("9999abc".getBytes(StandardCharsets.US_ASCII)).getType());
        assertEquals(0x0210, isoService.crearRespuestaErrorFormato(null).getType());
    }

    @Test
    @DisplayName("Rechazo de trama nula, vacía o con bitmap inválido")
    void testInvalidInputHandling() {
        assertThrows(IllegalArgumentException.class, () -> isoService.parse((byte[]) null));
        assertThrows(IllegalArgumentException.class, () -> isoService.parse(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> isoService.parse("   "));
        String completa = isoService.encodeToString(solicitud0200());
        assertThrows(Exception.class, () -> isoService.parse(completa.substring(0, completa.length() / 2)),
                "Una trama truncada no se puede parsear");
    }

    @Test
    @DisplayName("getAmountValue tolera valores no numéricos y getFieldValue campos ausentes")
    void testValoresAusentesONoNumericos() {
        IsoMessage msg = isoService.getMessageFactory().newMessage(0x0200);
        assertNull(isoService.getFieldValue(msg, 37));
        assertEquals(BigDecimal.ZERO, isoService.getAmountValue(msg));
        msg.setValue(4, "abc", IsoType.ALPHA, 12);
        assertEquals(BigDecimal.ZERO, isoService.getAmountValue(msg));
        msg.setValue(4, " 15.25 ", IsoType.ALPHA, 7);
        assertEquals(new BigDecimal("15.25"), isoService.getAmountValue(msg));
        msg.setValue(4, 42L, IsoType.NUMERIC, 12);
        assertEquals(new BigDecimal("42"), isoService.getAmountValue(msg));
        assertEquals("320", isoService.getMonedaPorDefecto());
        assertEquals("840", new IsoMessageService("840").getMonedaPorDefecto());
    }
}
