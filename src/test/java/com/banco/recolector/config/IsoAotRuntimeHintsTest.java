package com.banco.recolector.config;

import com.banco.recolector.corebancario.CuentaDto;
import com.banco.recolector.corebancario.TransaccionCoreRequest;
import com.banco.recolector.corebancario.TransaccionCoreResponse;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.support.SpringFactoriesLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuntimeHints de GraalVM Native Image para el motor ISO 8583")
class IsoAotRuntimeHintsTest {

    @Test
    @DisplayName("Registra recursos de j8583 (XML y DTD) y la reflexión de j8583 y los DTOs del RestClient")
    void testHintsRegistrados() {
        RuntimeHints hints = new RuntimeHints();
        new IsoAotRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertTrue(RuntimeHintsPredicates.resource().forResource("j8583.xml").test(hints));
        assertTrue(RuntimeHintsPredicates.resource().forResource("com/solab/iso8583/parse/j8583.dtd").test(hints));
        assertTrue(RuntimeHintsPredicates.reflection().onType(IsoMessage.class).test(hints));
        assertTrue(RuntimeHintsPredicates.reflection().onType(IsoType.class).test(hints));
        assertTrue(RuntimeHintsPredicates.reflection().onType(TransaccionCoreRequest.class).test(hints));
        assertTrue(RuntimeHintsPredicates.reflection().onType(TransaccionCoreResponse.class).test(hints));
        assertTrue(RuntimeHintsPredicates.reflection().onType(CuentaDto.class).test(hints));
    }

    @Test
    @DisplayName("El registrador está declarado en META-INF/spring/aot.factories")
    void testRegistradoEnAotFactories() {
        assertTrue(SpringFactoriesLoader.forResourceLocation("META-INF/spring/aot.factories")
                .load(org.springframework.aot.hint.RuntimeHintsRegistrar.class).stream()
                .anyMatch(IsoAotRuntimeHints.class::isInstance));
    }
}
