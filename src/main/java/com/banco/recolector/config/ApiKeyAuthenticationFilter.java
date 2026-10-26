package com.banco.recolector.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Autenticación servicio-a-servicio: el Recolector envía la cabecera {@code X-API-Key} al Core.
 * La comparación es en tiempo constante y no usa BCrypt, para no penalizar el camino transaccional.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String CABECERA = "X-API-Key";

    private final byte[] apiKeyEsperada;

    public ApiKeyAuthenticationFilter(String apiKey) {
        this.apiKeyEsperada = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String recibida = request.getHeader(CABECERA);
        if (recibida != null) {
            if (!MessageDigest.isEqual(apiKeyEsperada, recibida.getBytes(StandardCharsets.UTF_8))) {
                response.sendError(HttpStatus.UNAUTHORIZED.value(), "API Key inválida");
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "recolector-iso8583", null, AuthorityUtils.createAuthorityList("ROLE_SERVICIO")));
        }
        chain.doFilter(request, response);
    }
}
