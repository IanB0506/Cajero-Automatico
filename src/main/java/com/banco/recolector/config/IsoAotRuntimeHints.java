package com.banco.recolector.config;

import com.banco.recolector.corebancario.CrearCuentaRequest;
import com.banco.recolector.corebancario.CuentaDto;
import com.banco.recolector.corebancario.EditarCuentaRequest;
import com.banco.recolector.corebancario.TransaccionCoreRequest;
import com.banco.recolector.corebancario.TransaccionCoreResponse;
import com.banco.recolector.iso.IsoTransactionDto;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.IsoValue;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Runtime Hints para GraalVM Native Image (registrado en META-INF/spring/aot.factories).
 * Declara explícitamente la reflexión y los recursos que el motor j8583 y el RestClient usan en tiempo de
 * ejecución y que el análisis estático de AOT no puede deducir.
 */
public class IsoAotRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // 1. Motor j8583: tipos creados y consultados por nombre al leer j8583.xml
        for (Class<?> tipo : new Class<?>[]{IsoMessage.class, IsoValue.class, IsoType.class}) {
            hints.reflection().registerType(tipo,
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS,
                    MemberCategory.ACCESS_PUBLIC_FIELDS);
        }

        // 2. Records serializados con Jackson por el RestClient (fuera de los controladores MVC)
        new BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(),
                TransaccionCoreRequest.class, TransaccionCoreResponse.class, CuentaDto.class,
                CrearCuentaRequest.class, EditarCuentaRequest.class, IsoTransactionDto.class);

        // 3. Recursos del classpath: configuración ISO y el DTD que j8583 resuelve localmente al parsearla
        hints.resources().registerPattern("j8583.xml");
        hints.resources().registerPattern("com/solab/iso8583/parse/j8583.dtd");
    }
}
