package com.banco.recolector.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Caché de idempotencia RRN/STAN (TTL y capacidad acotada)")
class IdempotenciaServiceTest {

    /** Reloj manipulable para simular el paso del tiempo. */
    static final class RelojMovible extends Clock {
        private Instant ahora = Instant.parse("2026-10-26T12:00:00Z");

        void avanzar(Duration d) { ahora = ahora.plus(d); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return ahora; }
    }

    @Test
    @DisplayName("Registra y recupera la respuesta de una retransmisión")
    void testRegistrarYObtener() {
        IdempotenciaService servicio = new IdempotenciaService(60, 100);
        assertNull(servicio.obtener("262722000001", "000001"));

        servicio.registrar("262722000001", "000001", new byte[]{1, 2, 3});
        IdempotenciaService.RegistroIdempotente reg = servicio.obtener("262722000001", "000001");
        assertNotNull(reg);
        assertArrayEquals(new byte[]{1, 2, 3}, reg.isoResponseBytes());
        assertNull(servicio.obtener("262722000001", "000002"), "Otro STAN es otra transacción");
        assertEquals("262722000001:000001", servicio.generarClave(" 262722000001 ", "000001"));
        assertEquals(1, servicio.totalRegistros());
    }

    @Test
    @DisplayName("Las entradas expiran al vencer el TTL")
    void testExpiracionPorTtl() {
        RelojMovible reloj = new RelojMovible();
        IdempotenciaService servicio = new IdempotenciaService(Duration.ofMinutes(60), 100, reloj);
        servicio.registrar("RRN1", "1", new byte[]{1});

        reloj.avanzar(Duration.ofMinutes(59));
        assertNotNull(servicio.obtener("RRN1", "1"));

        reloj.avanzar(Duration.ofMinutes(2));
        assertNull(servicio.obtener("RRN1", "1"));
        assertEquals(0, servicio.totalRegistros(), "La entrada expirada se elimina al consultarla");
    }

    @Test
    @DisplayName("Con la caché llena se purgan expiradas; si sigue llena no se agregan más")
    void testCapacidadMaxima() {
        RelojMovible reloj = new RelojMovible();
        IdempotenciaService servicio = new IdempotenciaService(Duration.ofMinutes(10), 2, reloj);
        servicio.registrar("A", "1", new byte[]{1});
        servicio.registrar("B", "1", new byte[]{1});
        servicio.registrar("C", "1", new byte[]{1});
        assertEquals(2, servicio.totalRegistros(), "No supera el máximo");
        assertNull(servicio.obtener("C", "1"));

        reloj.avanzar(Duration.ofMinutes(11));
        servicio.registrar("D", "1", new byte[]{1});
        assertEquals(1, servicio.totalRegistros(), "Se purgaron las expiradas y entró la nueva");
        assertNotNull(servicio.obtener("D", "1"));
    }

    @Test
    @DisplayName("Purga periódica, limpieza y claves nulas")
    void testPurgaYNulos() {
        RelojMovible reloj = new RelojMovible();
        IdempotenciaService servicio = new IdempotenciaService(Duration.ofMinutes(1), 10, reloj);
        servicio.registrar(null, "1", new byte[]{1});
        servicio.registrar("A", null, new byte[]{1});
        assertEquals(0, servicio.totalRegistros());
        assertNull(servicio.obtener(null, "1"));
        assertEquals(":", servicio.generarClave(null, null));

        servicio.registrar("A", "1", new byte[]{1});
        reloj.avanzar(Duration.ofMinutes(2));
        servicio.purgarExpirados();
        assertEquals(0, servicio.totalRegistros());

        servicio.registrar("B", "1", new byte[]{1});
        servicio.limpiar();
        assertEquals(0, servicio.totalRegistros());
    }
}
