package com.banco.recolector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;

/**
 * Recolector Transaccional ISO 8583: Spring Boot 4.1, Java 25, Virtual Threads, Spring Integration IP
 * y GraalVM Native Image.
 *
 * <p>Con el argumento {@code --healthcheck} el mismo ejecutable consulta /actuator/health y termina con
 * código 0 (UP) o 1: así la imagen Distroless, que no tiene shell ni curl, puede declarar un HEALTHCHECK.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class RecolectorApplication {

    private static final Logger log = LoggerFactory.getLogger(RecolectorApplication.class);

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--healthcheck")) {
            System.exit(verificarSalud(System.getenv().getOrDefault("SERVER_PORT", "8087")));
        }
        ConfigurableApplicationContext ctx = SpringApplication.run(RecolectorApplication.class, args);
        String httpPort = ctx.getEnvironment().getProperty("local.server.port", "8087");
        String tcpPort = ctx.getEnvironment().getProperty("recolector.tcp.port", "15000");
        log.info("========================================================================");
        log.info("* RECOLECTOR TRANSACCIONAL ISO 8583 OPERATIVO                         *");
        log.info("* Sockets TCP ISO 8583 : Puerto {} (Virtual Threads Loom)           *", String.format("%-5s", tcpPort));
        log.info("* API REST Core Bancario: {}*", String.format("%-45s", "http://localhost:" + httpPort + "/api/core"));
        log.info("* Mapeos Transaccionales: MTI 0200/0210, 0100/0110, 0420/0430, 0800    *");
        log.info("* Métricas y Salud      : {}*", String.format("%-45s", "http://localhost:" + httpPort + "/actuator/health"));
        log.info("========================================================================");
    }

    /** @return 0 si /actuator/health responde UP, 1 en cualquier otro caso. */
    static int verificarSalud(String puerto) {
        try (HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpResponse<String> respuesta = cliente.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + "/actuator/health"))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return respuesta.statusCode() == 200 && respuesta.body().contains("\"UP\"") ? 0 : 1;
        } catch (Exception e) {
            return 1;
        }
    }
}
