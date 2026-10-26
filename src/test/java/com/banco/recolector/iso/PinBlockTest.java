package com.banco.recolector.iso;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PIN Block ISO 9564 Formato 0 (DE 52)")
class PinBlockTest {

    @Test
    @DisplayName("Vector de referencia ISO-0: PIN 1234 con PAN 4111111111111111")
    void testVectorConocido() {
        // Campo PIN 041234FFFFFFFFFF XOR campo PAN 0000111111111111 = 041225EEEEEEEEEE
        assertEquals("041225EEEEEEEEEE", PinBlock.formato0("1234", "4111111111111111"));
    }

    @Test
    @DisplayName("Ida y vuelta: el PIN se recupera con el mismo PAN")
    void testIdaYVuelta() {
        String bloque = PinBlock.formato0("987654", "4532000000000001");
        assertEquals(16, bloque.length());
        assertEquals(Optional.of("987654"), PinBlock.extraerPin(bloque, "4532000000000001"));
        assertEquals(Optional.of("987654"), PinBlock.extraerPin(bloque.toLowerCase(), "4532000000000001"));
    }

    @Test
    @DisplayName("Con otro PAN el bloque no revela el PIN correcto")
    void testOtroPan() {
        // ISO-0 usa los 12 dígitos del PAN previos al verificador: otra tarjeta produce otro bloque
        String bloque = PinBlock.formato0("1234", "4532000000000001");
        assertNotEquals(bloque, PinBlock.formato0("1234", "4111111111111111"));
        assertNotEquals(Optional.of("1234"), PinBlock.extraerPin(bloque, "4111111111111111"));
    }

    @Test
    @DisplayName("Bloques o PAN inválidos devuelven vacío")
    void testEntradasInvalidas() {
        assertTrue(PinBlock.extraerPin(null, "4532000000000001").isEmpty());
        assertTrue(PinBlock.extraerPin("XYZ", "4532000000000001").isEmpty());
        assertTrue(PinBlock.extraerPin("041225EEEEEEEEEE", "123").isEmpty(), "PAN demasiado corto");
        assertTrue(PinBlock.extraerPin("FFFFFFFFFFFFFFFF", "4111111111111111").isEmpty(), "Formato distinto de 0");
        assertTrue(PinBlock.extraerPin("0F1225EEEEEEEEEE", "4111111111111111").isEmpty(), "Longitud de PIN fuera de rango");
        assertTrue(PinBlock.extraerPin("0412A5EEEEEEEEEE", "4111111111111111").isEmpty(), "PIN no numérico");
        assertTrue(PinBlock.extraerPin("041225EEEEEEEEE0", "4111111111111111").isEmpty(), "Relleno distinto de F");
    }

    @Test
    @DisplayName("No se puede construir un bloque con PIN o PAN inválidos")
    void testConstruccionInvalida() {
        assertThrows(IllegalArgumentException.class, () -> PinBlock.formato0("12", "4532000000000001"));
        assertThrows(IllegalArgumentException.class, () -> PinBlock.formato0(null, "4532000000000001"));
        assertThrows(IllegalArgumentException.class, () -> PinBlock.formato0("12a4", "4532000000000001"));
        assertThrows(IllegalArgumentException.class, () -> PinBlock.formato0("1234", "45320"));
    }
}
