package com.banco.recolector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propiedades del Servidor TCP de Ingesta ISO 8583.
 *
 * @param port          puerto de escucha de las terminales POS/ATM
 * @param timeoutMs     tiempo máximo de inactividad de una conexión antes de cerrarla
 * @param maxFrameBytes tamaño máximo de una trama (protege contra prefijos de longitud maliciosos)
 * @param backlog       cola de conexiones pendientes del ServerSocket
 */
@ConfigurationProperties(prefix = "recolector.tcp")
public record TcpServerProperties(
        @DefaultValue("15000") int port,
        @DefaultValue("30000") int timeoutMs,
        @DefaultValue("8192") int maxFrameBytes,
        @DefaultValue("2048") int backlog
) {}
