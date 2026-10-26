package com.banco.recolector.corebancario;

import com.banco.recolector.iso.IsoTransactionDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * DTO Inmutable (Java Record) para solicitar una transacción en el Core Bancario.
 * Utiliza validaciones del namespace jakarta.validation.
 */
public record TransaccionCoreRequest(
    @NotBlank(message = "El RRN es obligatorio para trazabilidad e idempotencia")
    @Size(max = 12, message = "El RRN admite máximo 12 caracteres")
    String rrn,

    @NotBlank(message = "El STAN es obligatorio")
    @Size(max = 6, message = "El STAN admite máximo 6 dígitos")
    String stan,

    String pan,

    @NotBlank(message = "La cuenta origen (DE 102 o PAN) es obligatoria")
    String cuentaOrigen,

    String cuentaDestino,

    @NotNull(message = "El monto es obligatorio")
    @PositiveOrZero(message = "El monto debe ser mayor o igual a cero")
    BigDecimal monto,

    @NotBlank(message = "El tipo de operación es obligatorio")
    String tipoOperacion,

    @Size(max = 30, message = "El terminal admite máximo 30 caracteres")
    String terminalId,

    String codigoProcesamiento,

    /** PIN Block ISO-0 (DE 52) en hexadecimal; nulo si la terminal no capturó PIN. */
    String pinBlock
) {
    public static final String COMPRA = "COMPRA";
    public static final String RETIRO = "RETIRO";
    public static final String DEVOLUCION = "DEVOLUCION";
    public static final String CONSULTA = "CONSULTA";
    public static final String TRANSFERENCIA = "TRANSFERENCIA";
    public static final String REVERSA = "REVERSA";
    public static final String NO_SOPORTADA = "NO_SOPORTADA";

    /**
     * Traduce los dos primeros dígitos del Processing Code (DE 3) al tipo de operación del Core.
     */
    public static String tipoPorCodigoProcesamiento(String codigoProcesamiento) {
        if (codigoProcesamiento == null || codigoProcesamiento.length() < 2) {
            return NO_SOPORTADA;
        }
        return switch (codigoProcesamiento.substring(0, 2)) {
            case "00" -> COMPRA;
            case "01" -> RETIRO;
            case "20" -> DEVOLUCION;
            case "30" -> CONSULTA;
            case "40" -> TRANSFERENCIA;
            default -> NO_SOPORTADA;
        };
    }

    /**
     * Construye la solicitud al Core a partir de los Data Elements extraídos de la trama ISO.
     */
    public static TransaccionCoreRequest fromIso(IsoTransactionDto iso, String tipoOperacion) {
        return new TransaccionCoreRequest(
                iso.rrn(),
                iso.stan(),
                iso.pan(),
                iso.cuenta(),
                iso.cuentaDestino(),
                iso.amount(),
                tipoOperacion,
                iso.terminalId(),
                iso.processingCode(),
                iso.pinBlock()
        );
    }

    /** Nunca expone el PIN block en logs. */
    @Override
    public String toString() {
        return "TransaccionCoreRequest[tipo=%s, rrn=%s, stan=%s, cuenta=%s, destino=%s, monto=%s, pinBlock=%s]"
                .formatted(tipoOperacion, rrn, stan, cuentaOrigen, cuentaDestino, monto, pinBlock != null ? "****" : null);
    }
}
