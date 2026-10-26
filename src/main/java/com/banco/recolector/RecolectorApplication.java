package com.banco.recolector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Clase Principal del Recolector Transaccional ISO 8583.
 * Arquitectura de Alto Rendimiento basada en Spring Boot 4.x / 3.4+, Java 21+,
 * Virtual Threads (Project Loom) y GraalVM Native Image.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RecolectorApplication {

    private static final Logger log = LoggerFactory.getLogger(RecolectorApplication.class);

    public static void main(String[] args) {
        log.info("Iniciando Recolector Transaccional de Alto Rendimiento...");
        ConfigurableApplicationContext ctx = SpringApplication.run(RecolectorApplication.class, args);
        String httpPort = ctx.getEnvironment().getProperty("local.server.port", "8080");
        String tcpPort = ctx.getEnvironment().getProperty("recolector.tcp.port", "15000");
        log.info("========================================================================");
        log.info("* RECOLECTOR TRANSACCIONAL ISO 8583 OPERATIVO                         *");
        log.info("* Sockets TCP ISO 8583 : Puerto {} (Virtual Threads Loom)           *", String.format("%-5s", tcpPort));
        log.info("* API REST Core Bancario: {}*", String.format("%-45s", "http://localhost:" + httpPort + "/api/core"));
        log.info("* Mapeos Transaccionales: MTI 0200/0210, 0100/0110, 0420/0430, 0800    *");
        log.info("* Métricas y Salud      : {}*", String.format("%-45s", "http://localhost:" + httpPort + "/actuator/health"));
        log.info("========================================================================");
    }
}
