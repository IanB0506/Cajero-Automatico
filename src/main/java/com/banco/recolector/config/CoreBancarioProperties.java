package com.banco.recolector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración del adaptador RestClient hacia el Core Bancario.
 *
 * @param url               URL base de la API del Core (ej. http://localhost:8087/api/core)
 * @param apiKey            API Key enviada en la cabecera X-API-Key
 * @param connectTimeoutMs  tiempo máximo para establecer la conexión
 * @param readTimeoutMs     tiempo máximo de espera de la respuesta (excederlo se traduce a DE 39 = 68)
 * @param reintentos        reintentos ante caída de conexión o HTTP 5xx (seguros: el Core es idempotente por RRN/STAN)
 * @param esperaReintentoMs espera inicial entre reintentos (se duplica en cada intento)
 */
@ConfigurationProperties(prefix = "recolector.corebancario")
public record CoreBancarioProperties(
        @DefaultValue("http://localhost:8087/api/core") String url,
        String apiKey,
        @DefaultValue("2000") int connectTimeoutMs,
        @DefaultValue("5000") int readTimeoutMs,
        @DefaultValue("2") int reintentos,
        @DefaultValue("100") long esperaReintentoMs
) {}
