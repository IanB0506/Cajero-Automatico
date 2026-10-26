package com.banco.recolector.service;

import com.banco.recolector.corebancario.CoreBancarioClient;
import com.banco.recolector.corebancario.TransaccionCoreRequest;
import com.banco.recolector.corebancario.TransaccionCoreResponse;
import com.banco.recolector.iso.IsoMessageService;
import com.banco.recolector.iso.IsoResponseCode;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Orquestador del Recolector (Core Bancario simulado con Mockito)")
class RecolectorTransaccionalServiceTest {

    private IsoMessageService iso;
    private CoreBancarioClient core;
    private RecolectorTransaccionalService recolector;

    @BeforeEach
    void setUp() {
        iso = new IsoMessageService();
        iso.init();
        core = mock(CoreBancarioClient.class);
        recolector = new RecolectorTransaccionalService(iso, core, new IdempotenciaService(60, 1000));
    }

    private IsoMessage compra(String stan, String rrn) {
        return iso.createFinancialRequest("4532000000000001", "000000", new BigDecimal("10.00"),
                stan, rrn, "POS00001", "1001-2002-3001");
    }

    private static TransaccionCoreResponse aprobada(String saldo) {
        return TransaccionCoreResponse.aprobada("r", "100001", new BigDecimal(saldo), new BigDecimal(saldo));
    }

    @Test
    @DisplayName("0200 aprobada: DE 38, DE 39 y DE 54 con moneda 320 y signo C")
    void testCompraAprobada() throws Exception {
        when(core.procesarTransaccion(any())).thenReturn(aprobada("90.00"));
        IsoMessage resp = iso.parse(recolector.procesarTrama(iso.encode(compra("000001", "262722000001"))));

        assertEquals(0x0210, resp.getType());
        assertEquals("00", iso.getFieldValue(resp, 39));
        assertEquals("100001", iso.getFieldValue(resp, 38));
        assertEquals("1001320C0000000090001002320C000000009000", iso.getFieldValue(resp, 54));

        ArgumentCaptor<TransaccionCoreRequest> captor = ArgumentCaptor.forClass(TransaccionCoreRequest.class);
        verify(core).procesarTransaccion(captor.capture());
        assertEquals("COMPRA", captor.getValue().tipoOperacion());
        assertEquals("262722000001", captor.getValue().rrn());
        assertEquals(1, recolector.getContadorAprobadas());
    }

    @Test
    @DisplayName("Retransmisión con el mismo RRN/STAN se responde desde la caché sin volver al Core")
    void testIdempotenciaEnMemoria() {
        when(core.procesarTransaccion(any())).thenReturn(aprobada("90.00"));
        recolector.procesarMensaje(compra("000002", "262722000002"));
        IsoMessage segunda = recolector.procesarMensaje(compra("000002", "262722000002"));

        assertEquals("00", iso.getFieldValue(segunda, 39));
        verify(core, times(1)).procesarTransaccion(any());
    }

    @Test
    @DisplayName("Un error transitorio (91) no se guarda en caché: la retransmisión vuelve a intentar")
    void testTransitorioNoSeCachea() {
        when(core.procesarTransaccion(any()))
                .thenReturn(TransaccionCoreResponse.rechazada("r", "91", "caído", BigDecimal.ZERO, BigDecimal.ZERO))
                .thenReturn(aprobada("80.00"));
        assertEquals("91", iso.getFieldValue(recolector.procesarMensaje(compra("000003", "262722000003")), 39));
        assertEquals("00", iso.getFieldValue(recolector.procesarMensaje(compra("000003", "262722000003")), 39));
        verify(core, times(2)).procesarTransaccion(any());
    }

    @Test
    @DisplayName("Sin RRN (DE 37) la trama tiene error de formato: DE 39 = 30 sin llamar al Core")
    void testSinRrnErrorFormato() {
        IsoMessage sinRrn = iso.createFinancialRequest("4532000000000001", "000000", BigDecimal.TEN,
                "000004", null, "POS00001", "1001-2002-3001");
        assertEquals(IsoResponseCode.ERROR_FORMATO, iso.getFieldValue(recolector.procesarMensaje(sinRrn), 39));
        verifyNoInteractions(core);
    }

    @Test
    @DisplayName("Trama ilegible por TCP -> respuesta ISO con DE 39 = 30")
    void testTramaIlegible() throws Exception {
        IsoMessage resp = iso.parse(recolector.procesarTrama("ISO0150000500200XXXX".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0x0210, resp.getType());
        assertEquals(IsoResponseCode.ERROR_FORMATO, iso.getFieldValue(resp, 39));
        assertEquals(1, recolector.getContadorErroresFormato());
        assertEquals(1, recolector.getContadorRechazadas());
    }

    @Test
    @DisplayName("Una excepción inesperada se responde con DE 39 = 96 en lugar de cortar la conexión")
    void testExcepcionInesperada() throws Exception {
        when(core.procesarTransaccion(any())).thenThrow(new IllegalStateException("bug"));
        IsoMessage resp = iso.parse(recolector.procesarTrama(iso.encode(compra("000005", "262722000005"))));
        assertEquals(IsoResponseCode.ERROR_SISTEMA, iso.getFieldValue(resp, 39));
    }

    @Test
    @DisplayName("0100 se envía como CONSULTA y 0420 como REVERSA")
    void testTiposForzadosPorMti() {
        when(core.procesarTransaccion(any())).thenReturn(aprobada("100.00"));
        IsoMessage consulta = iso.crearSolicitud(0x0100, "4532000000000001", "300000", null,
                "000006", "262722000006", "ATM00001", "1001-2002-3001");
        IsoMessage reversa = iso.crearSolicitud(0x0420, "4532000000000001", "010000", BigDecimal.TEN,
                "000007", "262722000006", "ATM00001", "1001-2002-3001");

        assertEquals(0x0110, recolector.procesarMensaje(consulta).getType());
        assertEquals(0x0430, recolector.procesarMensaje(reversa).getType());

        ArgumentCaptor<TransaccionCoreRequest> captor = ArgumentCaptor.forClass(TransaccionCoreRequest.class);
        verify(core, times(2)).procesarTransaccion(captor.capture());
        assertEquals("CONSULTA", captor.getAllValues().get(0).tipoOperacion());
        assertEquals("REVERSA", captor.getAllValues().get(1).tipoOperacion());
    }

    @Test
    @DisplayName("MTI no soportado (0210 recibido como solicitud) -> DE 39 = 12")
    void testMtiNoSoportado() {
        IsoMessage respuestaComoSolicitud = iso.getMessageFactory().newMessage(0x0210);
        respuestaComoSolicitud.setValue(11, "000008", IsoType.NUMERIC, 6);
        assertEquals(IsoResponseCode.TRANSACCION_INVALIDA, iso.getFieldValue(recolector.procesarMensaje(respuestaComoSolicitud), 39));
    }

    @Test
    @DisplayName("Echo test 0800 -> 0810 sin pasar por el Core")
    void testEcho() {
        IsoMessage echo = iso.getMessageFactory().newMessage(0x0800);
        echo.setValue(11, "000009", IsoType.NUMERIC, 6);
        echo.setValue(70, "301", IsoType.NUMERIC, 3);
        IsoMessage resp = recolector.procesarMensaje(echo);
        assertEquals(0x0810, resp.getType());
        assertEquals("00", iso.getFieldValue(resp, 39));
        verifyNoInteractions(core);
        assertEquals(1, recolector.getContadorProcesadas());
    }

    @Test
    @DisplayName("DE 54: saldo negativo con signo D y moneda de la transacción")
    void testCampo54() {
        assertEquals("1001840D0000000012341002840C000000000500",
                RecolectorTransaccionalService.formatearCampo54(new BigDecimal("5.00"), new BigDecimal("-12.34"), "840"));
        assertEquals("1001320C0000000005001002320C000000000500",
                RecolectorTransaccionalService.formatearCampo54(new BigDecimal("5.00"), null, "320"));
    }
}
