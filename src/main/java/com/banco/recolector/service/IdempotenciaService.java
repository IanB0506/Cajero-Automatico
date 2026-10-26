package com.banco.recolector.service;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caché de idempotencia del Recolector (primera línea de defensa ante retransmisiones RRN/STAN).
 * Estructura lock-free (ConcurrentHashMap) para no fijar Carrier Threads bajo Virtual Threads.
 * Está acotada por TTL y por número máximo de entradas; la garantía definitiva de "una sola aplicación"
 * la da el Core Bancario con su restricción única (RRN, STAN).
 */
@Service
public class IdempotenciaService {

    private static final Logger log = LoggerFactory.getLogger(IdempotenciaService.class);

    public record RegistroIdempotente(byte[] isoResponseBytes, Instant timestamp) {}

    private final ConcurrentHashMap<String, RegistroIdempotente> cache = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxEntradas;
    private final Clock clock;

    @Inject
    public IdempotenciaService(@Value("${recolector.idempotencia.ttl-minutes:60}") long ttlMinutos,
                               @Value("${recolector.idempotencia.max-entries:50000}") int maxEntradas) {
        this(Duration.ofMinutes(ttlMinutos), maxEntradas, Clock.systemUTC());
    }

    IdempotenciaService(Duration ttl, int maxEntradas, Clock clock) {
        this.ttl = ttl;
        this.maxEntradas = maxEntradas;
        this.clock = clock;
    }

    /**
     * Genera la clave compuesta de idempotencia a partir de RRN y STAN.
     */
    public String generarClave(String rrn, String stan) {
        return (rrn != null ? rrn.trim() : "") + ":" + (stan != null ? stan.trim() : "");
    }

    /**
     * Devuelve la respuesta previa si la transacción ya fue procesada y no expiró; null en otro caso.
     */
    public RegistroIdempotente obtener(String rrn, String stan) {
        if (rrn == null || stan == null) {
            return null;
        }
        String clave = generarClave(rrn, stan);
        RegistroIdempotente reg = cache.get(clave);
        if (reg == null) {
            return null;
        }
        if (expirado(reg, clock.instant())) {
            cache.remove(clave, reg);
            return null;
        }
        log.info("Transacción duplicada detectada (Idempotencia Activada): RRN={} STAN={}", rrn, stan);
        return reg;
    }

    /**
     * Registra la respuesta ISO de una transacción. Si la caché está llena se purgan las entradas expiradas;
     * si sigue llena, la respuesta no se guarda (el Core sigue garantizando la idempotencia).
     */
    public void registrar(String rrn, String stan, byte[] isoResponseBytes) {
        if (rrn == null || stan == null) {
            return;
        }
        if (cache.size() >= maxEntradas) {
            purgarExpirados();
            if (cache.size() >= maxEntradas) {
                log.debug("Caché de idempotencia llena ({} entradas): se delega en el Core", cache.size());
                return;
            }
        }
        cache.put(generarClave(rrn, stan), new RegistroIdempotente(isoResponseBytes, clock.instant()));
    }

    /** Limpieza periódica de entradas expiradas. */
    @Scheduled(fixedDelayString = "${recolector.idempotencia.purga-ms:60000}")
    public void purgarExpirados() {
        Instant ahora = clock.instant();
        cache.entrySet().removeIf(e -> expirado(e.getValue(), ahora));
    }

    private boolean expirado(RegistroIdempotente reg, Instant ahora) {
        return ahora.isAfter(reg.timestamp().plus(ttl));
    }

    /**
     * Limpia la caché (útil para tests).
     */
    public void limpiar() {
        cache.clear();
    }

    public int totalRegistros() {
        return cache.size();
    }
}
