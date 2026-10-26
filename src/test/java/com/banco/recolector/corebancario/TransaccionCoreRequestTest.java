package com.banco.recolector.corebancario;

import com.banco.recolector.iso.IsoTransactionDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Mapeo ISO -> DTO del Core Bancario")
class TransaccionCoreRequestTest {

    @ParameterizedTest(name = "DE 3 = {0} -> {1}")
    @CsvSource({
            "000000, COMPRA", "010000, RETIRO", "200000, DEVOLUCION", "300000, CONSULTA",
            "400000, TRANSFERENCIA", "990000, NO_SOPORTADA", "5, NO_SOPORTADA"
    })
    void testTipoPorCodigoProcesamiento(String codigo, String tipo) {
        assertEquals(tipo, TransaccionCoreRequest.tipoPorCodigoProcesamiento(codigo));
    }

    @Test
    void testCodigoNulo() {
        assertEquals(TransaccionCoreRequest.NO_SOPORTADA, TransaccionCoreRequest.tipoPorCodigoProcesamiento(null));
    }

    @Test
    @DisplayName("fromIso copia los Data Elements y toString oculta el PIN block")
    void testFromIso() {
        IsoTransactionDto iso = new IsoTransactionDto("0200", "4532000000000001", "400000", new BigDecimal("12.34"),
                "000001", "262722000001", "ATM00001", null, "1001-2002-3001", "1001-2002-3002", "320",
                "041225EEEEEEEEEE", null, null);
        TransaccionCoreRequest req = TransaccionCoreRequest.fromIso(iso, TransaccionCoreRequest.TRANSFERENCIA);

        assertEquals("262722000001", req.rrn());
        assertEquals("000001", req.stan());
        assertEquals("1001-2002-3001", req.cuentaOrigen());
        assertEquals("1001-2002-3002", req.cuentaDestino());
        assertEquals(new BigDecimal("12.34"), req.monto());
        assertEquals("041225EEEEEEEEEE", req.pinBlock());
        assertFalse(req.toString().contains("041225EEEEEEEEEE"));
        assertTrue(req.toString().contains("****"));
    }
}
