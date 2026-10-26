package com.banco.recolector.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.util.function.SingletonSupplier;

import java.util.function.Supplier;

/**
 * Seguridad del Core Bancario y del Recolector.
 * <ul>
 *   <li>{@code POST /api/core/transacciones}: solo el Recolector (API Key) o un administrador.</li>
 *   <li>Altas, ediciones, recargas, bloqueos, borrado y reset de cuentas: rol ADMIN (HTTP Basic).</li>
 *   <li>Consultas GET del Core, simulador ISO, interfaz web y health: públicos.</li>
 * </ul>
 * Sin sesiones ni cookies (API stateless), por lo que CSRF no aplica.
 */
@Configuration
public class SeguridadConfig {

    @Bean
    public SecurityFilterChain filtroSeguridad(HttpSecurity http, SeguridadProperties seguridad) throws Exception {
        HttpStatusEntryPoint noAutorizado = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/core/transacciones").hasAnyRole("SERVICIO", "ADMIN")
                .requestMatchers("/api/core/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/core/**").permitAll()
                .requestMatchers("/api/core/**").hasRole("ADMIN")
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .anyRequest().permitAll())
            // Sin cabecera WWW-Authenticate: el navegador no muestra su diálogo nativo de login
            .httpBasic(basic -> basic.authenticationEntryPoint(noAutorizado))
            .exceptionHandling(e -> e.authenticationEntryPoint(noAutorizado))
            .addFilterBefore(new ApiKeyAuthenticationFilter(seguridad.apiKey()), BasicAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Usuario administrador definido por configuración. El hash BCrypt se calcula en el primer login
     * (no al arrancar), para no retrasar el arranque del binario nativo.
     */
    @Bean
    public UserDetailsService usuarios(SeguridadProperties seguridad, PasswordEncoder encoder) {
        Supplier<UserDetails> admin = SingletonSupplier.of(() -> User.withUsername(seguridad.adminUsuario())
                .password(encoder.encode(seguridad.adminClave()))
                .roles("ADMIN")
                .build());
        return username -> {
            if (!seguridad.adminUsuario().equals(username)) {
                throw new UsernameNotFoundException("Usuario no encontrado");
            }
            return User.withUserDetails(admin.get()).build();
        };
    }
}
