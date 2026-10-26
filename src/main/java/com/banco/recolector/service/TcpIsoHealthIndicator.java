package com.banco.recolector.service;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.integration.ip.tcp.connection.TcpNetServerConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Estado del socket TCP ISO 8583 en /actuator/health (componente "tcpIso"): si el puerto no está
 * escuchando, la aplicación se reporta DOWN y el healthcheck del contenedor falla.
 */
@Component("tcpIso")
public class TcpIsoHealthIndicator implements HealthIndicator {

    private final TcpNetServerConnectionFactory servidor;

    public TcpIsoHealthIndicator(TcpNetServerConnectionFactory servidor) {
        this.servidor = servidor;
    }

    @Override
    public Health health() {
        Health.Builder estado = servidor.isListening() ? Health.up() : Health.down();
        return estado
                .withDetail("puerto", servidor.getPort())
                .withDetail("conexionesAbiertas", servidor.getOpenConnectionIds().size())
                .build();
    }

    public boolean escuchando() {
        return servidor.isListening();
    }

    public int conexionesAbiertas() {
        return servidor.getOpenConnectionIds().size();
    }
}
