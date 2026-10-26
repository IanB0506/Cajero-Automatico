package com.banco.recolector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credenciales del Core Bancario.
 *
 * @param apiKey       API Key que el Recolector envía en {@code X-API-Key} para registrar transacciones
 * @param adminUsuario usuario administrador (HTTP Basic) para la gestión de cuentas
 * @param adminClave   contraseña del administrador
 */
@ConfigurationProperties(prefix = "recolector.seguridad")
public record SeguridadProperties(String apiKey, String adminUsuario, String adminClave) {}
