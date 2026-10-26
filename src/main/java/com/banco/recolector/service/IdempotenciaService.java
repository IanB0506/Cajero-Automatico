package com.banco.recolector.service;

import com.banco.recolector.corebancario.TransaccionCoreResponse;
import com.solab.iso8583.IsoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servicio de Idempotencia de Alto Rendimiento para el Recolector Transaccional.
 * Emplea estructuras concurrentes libres de bloqueos (lock-free) para garantizar
 * CERO Carrier Thread Pinning bajo Virtual Threads (Project Loom).
 */
@Service
public class IdempotenciaService {

    private static final Logger log = LoggerFactory.getLogger(IdempotenciaService.class);

    @Value("${recolector.idempotencia.ttl-minutes:60}")
    private long ttlMinutes;

    public record RegistroIdempotente(
            TransaccionCoreResponse coreResponse,
            byte[] isoResponseBytes,
            Instant timestamp
    ) {}

    private final ConcurrentHashMap<String, RegistroIdempotente> cacheIdempotencia = new ConcurrentHashMap<>();

    /**
     * Genera la clave compuesta de idempotencia a partir de RRN y STAN.
     */
    public String generarClave(String rrn, String stan) {
        return (rrn != null ? rrn.trim() : "") + ":" + (stan != null ? stan.trim() : "");
    }

    /**
     * Consulta si la transacción ya fue procesada previamente.
     */
    public RegistroIdempotente obtener(String rrn, String stan) {
        String clave = generarClave(rrn, stan);
        RegistroIdempotente reg = cacheIdempotencia.get(clave);

        if (reg != null) {
            // Verificar expiración por TTL
            if (Instant.now().isAfter(reg.timestamp().plusSeconds(ttlMinutes * 60))) {
                cacheIdempotencia.remove(clave);
                return null;
            }
            log.warn("Transacción duplicada detectada (Idempotencia Activada): RRN={} STAN={}", rrn, stan);
            return reg;
        }

        return null;
    }

    /**
     * Registra el resultado de una transacción exitosa o rechazada.
     */
    public void registrar(String rrn, String stan, TransaccionCoreResponse coreResponse, byte[] isoResponseBytes) {
        if (rrn == null || stan == null) return;
        String clave = generarClave(rrn, stan);
        cacheIdempotencia.put(clave, new RegistroIdempotente(coreResponse, isoResponseBytes, Instant.now()));
    }

    /**
     * Limpia la caché (útil para tests).
     */
    public void limpiar() {
        cacheIdempotencia.clear();
    }

    public int totalRegistros() {
        return cacheIdempotencia.size();
    }
}
