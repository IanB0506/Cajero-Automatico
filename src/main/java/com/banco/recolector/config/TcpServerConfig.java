package com.banco.recolector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Propiedades y configuración del Servidor TCP de Ingesta ISO 8583.
 */
@Configuration
@ConfigurationProperties(prefix = "recolector.tcp")
public class TcpServerConfig {

    private int port = 15000;
    private int timeoutMs = 10000;
    private int workerThreads = 200;

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
}
