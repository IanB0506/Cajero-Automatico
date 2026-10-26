package com.banco.recolector.config;

import com.banco.recolector.corebancario.CuentaDto;
import com.banco.recolector.corebancario.TransaccionCoreRequest;
import com.banco.recolector.corebancario.TransaccionCoreResponse;
import com.banco.recolector.iso.IsoTransactionDto;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.IsoValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registrador de Runtime Hints para compilación AOT con GraalVM Native Image.
 * Registra explícitamente clases de reflexión, serialización y recursos XML para
 * garantizar arranque instantáneo (<100 ms) y empaquetado Distroless sin fallos.
 */
public class IsoAotRuntimeHints implements RuntimeHintsRegistrar {

    private static final Logger log = LoggerFactory.getLogger(IsoAotRuntimeHints.class);

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        log.info("Registrando GraalVM AOT Runtime Hints para Recolector ISO 8583...");

        // 1. Reflexión para el Motor j8583
        hints.reflection().registerType(IsoMessage.class, MemberCategory.values());
        hints.reflection().registerType(IsoValue.class, MemberCategory.values());
        hints.reflection().registerType(IsoType.class, MemberCategory.values());

        // 2. Reflexión para DTOs y Records Inmutables
        hints.reflection().registerType(IsoTransactionDto.class, MemberCategory.values());
        hints.reflection().registerType(TransaccionCoreRequest.class, MemberCategory.values());
        hints.reflection().registerType(TransaccionCoreResponse.class, MemberCategory.values());
        hints.reflection().registerType(CuentaDto.class, MemberCategory.values());
        hints.reflection().registerType(com.banco.recolector.corebancario.CrearCuentaRequest.class, MemberCategory.values());
        hints.reflection().registerType(com.banco.recolector.corebancario.EditarCuentaRequest.class, MemberCategory.values());

        // 3. Recursos en Classpath requeridos en tiempo de ejecución nativo
        hints.resources().registerPattern("j8583.xml");
        hints.resources().registerPattern("application.yaml");
        hints.resources().registerPattern("META-INF/spring/aot.factories");

        log.info("Runtime Hints para GraalVM Native Image registrados satisfactoriamente.");
    }
}
