package com.banco.recolector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

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
        SpringApplication.run(RecolectorApplication.class, args);
        log.info("========================================================================");
        log.info("* RECOLECTOR TRANSACCIONAL ISO 8583 OPERATIVO                         *");
        log.info("* Sockets TCP ISO 8583 : Puerto 15000 (Virtual Threads Loom)           *");
        log.info("* API REST Core Bancario: http://localhost:8080/api/core               *");
        log.info("* Mapeos Transaccionales: MTI 0200/0210, 0100/0110, 0420/0430, 0800    *");
        log.info("* Métricas y Salud      : http://localhost:8080/actuator/health        *");
        log.info("========================================================================");
    }
}
