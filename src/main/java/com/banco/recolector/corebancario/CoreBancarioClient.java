package com.banco.recolector.corebancario;

import com.banco.recolector.config.ApiKeyAuthenticationFilter;
import com.banco.recolector.config.CoreBancarioProperties;
import com.banco.recolector.iso.IsoResponseCode;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;

/**
 * Adaptador HTTP resiliente hacia el Core Bancario basado en RestClient (Spring Framework 7).
 * <ul>
 *   <li>DTOs inmutables (Java Records) y anotaciones Jakarta ({@code @Named}, {@code @Inject}).</li>
 *   <li>Reintentos con {@link RetryTemplate} (Framework 7) ante caída de conexión o HTTP 5xx; es seguro
 *       reintentar porque el Core es idempotente por RRN/STAN.</li>
 *   <li>Traducción robusta de estados HTTP/JSON a códigos de acción ISO 8583 (DE 39).</li>
 * </ul>
 */
@Named("coreBancarioClient")
public class CoreBancarioClient {

    private static final Logger log = LoggerFactory.getLogger(CoreBancarioClient.class);
    private static final int MAX_CONEXIONES = 500;

    private final RestClient restClient;
    private final RetryTemplate retryTemplate;

    @Inject
    public CoreBancarioClient(RestClient.Builder builder, CoreBancarioProperties props) {
        // E/S bloqueante en el propio Virtual Thread con conexiones keep-alive reutilizadas
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(MAX_CONEXIONES)
                        .setMaxConnPerRoute(MAX_CONEXIONES)
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofMilliseconds(props.connectTimeoutMs()))
                                .setSocketTimeout(Timeout.ofMilliseconds(props.readTimeoutMs()))
                                .build())
                        .build())
                .disableAutomaticRetries() // los reintentos los decide el RetryTemplate
                .build();
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(props.readTimeoutMs()));

        RestClient.Builder configurado = builder.clone()
                .baseUrl(props.url())
                .requestFactory(requestFactory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            configurado.defaultHeader(ApiKeyAuthenticationFilter.CABECERA, props.apiKey());
        }
        this.restClient = configurado.build();
        this.retryTemplate = new RetryTemplate(RetryPolicy.builder()
                .includes(ResourceAccessException.class, CoreNoDisponibleException.class)
                .maxRetries(props.reintentos())
                .delay(Duration.ofMillis(props.esperaReintentoMs()))
                .multiplier(2)
                .build());
        log.info("RestClient del Core Bancario configurado: {} (timeout {} ms, {} reintentos)",
                props.url(), props.readTimeoutMs(), props.reintentos());
    }

    /**
     * Constructor para pruebas de integración sin contexto Spring (WireMock).
     */
    public CoreBancarioClient(String coreUrl, int timeoutMs) {
        this(RestClient.builder(), new CoreBancarioProperties(coreUrl, "clave-de-prueba", timeoutMs, timeoutMs, 2, 10));
    }

    /**
     * Despacha una transacción financiera hacia el Core Bancario y devuelve siempre una respuesta con DE 39.
     */
    public TransaccionCoreResponse procesarTransaccion(TransaccionCoreRequest request) {
        log.debug("Enviando al Core Bancario: {}", request);
        try {
            return retryTemplate.execute(() -> enviar(request));
        } catch (RetryException e) {
            return traducirFallo(request.rrn(), e.getLastException());
        } catch (RuntimeException e) {
            log.error("Excepción inesperada al invocar el Core Bancario: {}", e.getMessage(), e);
            return rechazo(request.rrn(), IsoResponseCode.ERROR_SISTEMA, "Error interno de comunicación: " + e.getMessage());
        }
    }

    private TransaccionCoreResponse enviar(TransaccionCoreRequest request) {
        return restClient.post()
                .uri("/transacciones")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange((req, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.is5xxServerError()) {
                        throw new CoreNoDisponibleException(status.value());
                    }
                    TransaccionCoreResponse cuerpo = leerCuerpo(response);
                    if (cuerpo != null && cuerpo.codigoRespuesta() != null) {
                        // El Core responde con DE 39 exacto (00, 51, 14, 05, 55, ...) también en 4xx
                        return cuerpo;
                    }
                    return rechazo(request.rrn(), codigoPorEstadoHttp(status.value()), "HTTP " + status.value() + " sin cuerpo ISO");
                });
    }

    private static TransaccionCoreResponse leerCuerpo(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(TransaccionCoreResponse.class);
        } catch (RestClientException | IllegalArgumentException e) {
            return null; // cuerpo vacío o no JSON: se traduce por código HTTP
        }
    }

    /**
     * Traducción de estados HTTP del Core sin cuerpo ISO a códigos de acción ISO 8583 (DE 39).
     */
    static String codigoPorEstadoHttp(int status) {
        return switch (status) {
            case 400 -> IsoResponseCode.ERROR_FORMATO;               // 30: solicitud malformada
            case 403, 423 -> IsoResponseCode.TARJETA_BLOQUEADA;      // 05
            case 404 -> IsoResponseCode.CUENTA_INVALIDA;             // 14
            case 410 -> IsoResponseCode.TARJETA_EXPIRADA;            // 54
            case 422 -> IsoResponseCode.FONDOS_INSUFICIENTES;        // 51
            case 429 -> IsoResponseCode.LIMITE_EXCEDIDO;             // 61
            case 408, 504 -> IsoResponseCode.TIMEOUT;                // 68
            case 500, 502, 503 -> IsoResponseCode.HOST_NO_DISPONIBLE; // 91
            default -> IsoResponseCode.ERROR_SISTEMA;                // 96 (incluye 401: API Key inválida)
        };
    }

    private TransaccionCoreResponse traducirFallo(String rrn, Throwable fallo) {
        if (fallo instanceof CoreNoDisponibleException http) {
            log.error("Core Bancario respondió HTTP {} tras agotar los reintentos", http.status());
            return rechazo(rrn, codigoPorEstadoHttp(http.status()), "Core Bancario no disponible (HTTP " + http.status() + ")");
        }
        if (esTimeout(fallo)) {
            log.error("Tiempo de espera agotado con el Core Bancario: {}", fallo.getMessage());
            return rechazo(rrn, IsoResponseCode.TIMEOUT, "Tiempo de espera agotado con el Core Bancario");
        }
        log.error("Sin conexión con el Core Bancario: {}", fallo != null ? fallo.getMessage() : "desconocido");
        return rechazo(rrn, IsoResponseCode.HOST_NO_DISPONIBLE, "Enlace con el Core Bancario caído");
    }

    private static boolean esTimeout(Throwable fallo) {
        for (Throwable t = fallo; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static TransaccionCoreResponse rechazo(String rrn, String codigo, String motivo) {
        return TransaccionCoreResponse.rechazada(rrn, codigo, motivo, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Consulta la información pública de una cuenta (número de cuenta o PAN); vacío si no existe o el Core no responde.
     */
    public Optional<CuentaDto> consultarCuenta(String numeroCuenta) {
        try {
            return restClient.get()
                    .uri("/cuentas/{numeroCuenta}", numeroCuenta)
                    .exchange((req, response) -> response.getStatusCode().is2xxSuccessful()
                            ? Optional.ofNullable(response.bodyTo(CuentaDto.class))
                            : Optional.<CuentaDto>empty());
        } catch (RestClientException e) {
            log.warn("No se pudo consultar la cuenta {} en el Core: {}", numeroCuenta, e.getMessage());
            return Optional.empty();
        }
    }

    /** HTTP 5xx del Core: error transitorio que se reintenta. */
    static final class CoreNoDisponibleException extends RuntimeException {
        private final int status;

        CoreNoDisponibleException(int status) {
            super("Core Bancario respondió HTTP " + status);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
