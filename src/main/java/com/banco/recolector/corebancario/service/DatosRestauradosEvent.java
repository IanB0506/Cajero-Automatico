package com.banco.recolector.corebancario.service;

/**
 * Evento que publica el Core Bancario al restaurar los datos de demostración (cuentas semilla e historial
 * de transacciones vacío). El Recolector lo escucha para vaciar su caché de idempotencia.
 */
public record DatosRestauradosEvent() {}
