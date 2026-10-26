package com.banco.recolector.corebancario;

import com.banco.recolector.iso.IsoResponseCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Cliente HTTP resiliente y no bloqueante basado en Spring RestClient (Spring 6 / Boot 3/4).
 * Utiliza anotaciones del estándar Jakarta (jakarta.annotation.* y jakarta.inject.*)
 * e implementa traducción robusta de errores HTTP hacia códigos de acción ISO 8583 (DE 39).
 */
@Component
@Named("coreBancarioClient")
public class CoreBancarioClient {

    private static final Logger log = LoggerFactory.getLogger(CoreBancarioClient.class);

    @Value("${recolector.corebancario.url:http://localhost:8080/api/core}")
    private String coreUrl;

    @Value("${recolector.corebancario.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${recolector.corebancario.read-timeout-ms:5000}")
    private int readTimeoutMs;

    private RestClient restClient;
    private final ObjectMapper objectMapper;

    @Inject
    public CoreBancarioClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Constructor para pruebas unitarias o de integración sin contexto Spring completo.
     */
    public CoreBancarioClient(String coreUrl, int timeoutMs) {
        this.objectMapper = new ObjectMapper();
        this.coreUrl = coreUrl;
        this.connectTimeoutMs = timeoutMs;
        this.readTimeoutMs = timeoutMs;
        init();
    }

    @PostConstruct
    public void init() {
        log.info("Inicializando RestClient resiliente contra Core Bancario en URL: {}", coreUrl);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs > 0 ? connectTimeoutMs : 3000));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs > 0 ? readTimeoutMs : 5000));

        this.restClient = RestClient.builder()
                .baseUrl(coreUrl != null ? coreUrl : "http://localhost:8080/api/core")
                .requestFactory(requestFactory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Permite reconfigurar la URL base dinámicamente (esencial para pruebas con WireMock).
     */
    public void setBaseUrl(String newUrl) {
        this.coreUrl = newUrl;
        init();
    }

    /**
     * Despacha una transacción financiera recibida desde un IsoTransactionDto.
     */
    public TransaccionCoreResponse procesarTransaccion(com.banco.recolector.iso.IsoTransactionDto isoDto) {
        TransaccionCoreRequest req = TransaccionCoreRequest.fromIso(
                isoDto.rrn(),
                isoDto.stan(),
                isoDto.pan(),
                isoDto.cuenta(),
                isoDto.amount(),
                isoDto.processingCode(),
                isoDto.terminalId()
        );
        return procesarTransaccion(req);
    }

    /**
     * Consulta información y saldo de una cuenta bancaria.
     */
    public CuentaDto consultarCuenta(String numeroCuenta) {
        log.info("Consultando saldo de cuenta en Core Bancario: {}", numeroCuenta);
        return restClient.get()
                .uri("/cuentas/{numeroCuenta}", numeroCuenta)
                .retrieve()
                .body(CuentaDto.class);
    }

    /**
     * Despacha una transacción financiera hacia el Core Bancario con traducción de errores HTTP a DE 39.
     */
    public TransaccionCoreResponse procesarTransaccion(TransaccionCoreRequest request) {
        log.info("Enviando transacción a Core Bancario via RestClient: RRN={} STAN={} Monto={}",
                request.rrn(), request.stan(), request.monto());

        try {
            TransaccionCoreResponse response = restClient.post()
                    .uri("/transacciones")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(TransaccionCoreResponse.class);

            if (response != null) {
                log.info("Respuesta exitosa recibida del Core Bancario: RC={} Auth={}",
                        response.codigoRespuesta(), response.codigoAutorizacion());
                return response;
            }

            return TransaccionCoreResponse.rechazada(
                    request.rrn(), IsoResponseCode.ERROR_SISTEMA, "Respuesta nula del Host Bancario",
                    BigDecimal.ZERO, BigDecimal.ZERO
            );

        } catch (RestClientResponseException ex) {
            // Manejo de respuestas HTTP 4xx y 5xx con cuerpo JSON de error
            log.warn("Error HTTP devuelto por Core Bancario [{}]: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            return traducirErrorHttp(ex, request.rrn());

        } catch (ResourceAccessException ex) {
            // Manejo de timeout de socket o caída de conexión TCP/HTTP
            log.error("Fallo de conectividad o Timeout con Core Bancario: {}", ex.getMessage());
            return TransaccionCoreResponse.rechazada(
                    request.rrn(),
                    IsoResponseCode.HOST_NO_DISPONIBLE, // DE 39 = 91 (Issuer Inoperative)
                    "Tiempo de Espera Agotado o Enlace con Core Caído: " + ex.getMessage(),
                    BigDecimal.ZERO, BigDecimal.ZERO
            );

        } catch (Exception ex) {
            log.error("Excepción inesperada al invocar Core Bancario: {}", ex.getMessage(), ex);
            return TransaccionCoreResponse.rechazada(
                    request.rrn(),
                    IsoResponseCode.ERROR_SISTEMA, // DE 39 = 96 (System Error)
                    "Error Interno de Comunicación: " + ex.getMessage(),
                    BigDecimal.ZERO, BigDecimal.ZERO
            );
        }
    }

    /**
     * Traduce códigos de error HTTP a los códigos equivalentes del estándar ISO 8583 (DE 39).
     */
    private TransaccionCoreResponse traducirErrorHttp(RestClientResponseException ex, String rrn) {
        HttpStatusCode status = ex.getStatusCode();
        String responseBody = ex.getResponseBodyAsString();

        // Si el body contiene un TransaccionCoreResponse estructurado, intentar deserializarlo
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                TransaccionCoreResponse parsed = objectMapper.readValue(responseBody, TransaccionCoreResponse.class);
                if (parsed != null && parsed.codigoRespuesta() != null) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // Fallback a mapeo por código HTTP
            }
        }

        String codigoIso = switch (status.value()) {
            case 404 -> IsoResponseCode.CUENTA_INVALIDA;       // 14: Cuenta no encontrada
            case 403, 423 -> IsoResponseCode.TARJETA_BLOQUEADA; // 05: Bloqueada / Do Not Honor
            case 422 -> IsoResponseCode.FONDOS_INSUFICIENTES;   // 51: Fondos insuficientes
            case 410 -> IsoResponseCode.TARJETA_EXPIRADA;       // 54: Vencida
            case 429 -> IsoResponseCode.LIMITE_EXCEDIDO;        // 61: Excede límite diario
            case 408, 504 -> IsoResponseCode.TIMEOUT;           // 68: Timeout
            case 500, 502, 503 -> IsoResponseCode.HOST_NO_DISPONIBLE; // 91: Host no disponible
            default -> IsoResponseCode.ERROR_SISTEMA;           // 96: Error general
        };

        return TransaccionCoreResponse.rechazada(
                rrn,
                codigoIso,
                "Error HTTP " + status.value() + ": " + ex.getStatusText(),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }
}
