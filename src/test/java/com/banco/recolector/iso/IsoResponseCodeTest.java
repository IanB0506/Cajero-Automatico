package com.banco.recolector.iso;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Catálogo de códigos de respuesta DE 39")
class IsoResponseCodeTest {

    @ParameterizedTest(name = "DE 39 = {0}")
    @ValueSource(strings = {"00", "05", "12", "13", "14", "25", "30", "51", "54", "55", "61", "68", "75", "91", "96"})
    void testDescripcionCatalogada(String codigo) {
        String descripcion = IsoResponseCode.getDescripcion(codigo);
        assertTrue(descripcion.startsWith(codigo + " - "));
        assertFalse(descripcion.contains("no catalogada"));
    }

    @Test
    void testCodigoDesconocidoYNulo() {
        assertEquals("99 - Respuesta no catalogada", IsoResponseCode.getDescripcion("99"));
        assertEquals("Código Desconocido", IsoResponseCode.getDescripcion(null));
    }
}
