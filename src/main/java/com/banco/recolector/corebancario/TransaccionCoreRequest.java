package com.banco.recolector.corebancario;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * DTO Inmutable (Java Record) para solicitar una transacción en el Core Bancario.
 * Utiliza validaciones del namespace jakarta.validation.
 */
public record TransaccionCoreRequest(
    @NotBlank(message = "El RRN es obligatorio para trazabilidad e idempotencia")
    String rrn,

    @NotBlank(message = "El STAN es obligatorio")
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

    String terminalId,

    String codigoProcesamiento
) {
    public static TransaccionCoreRequest fromIso(
            String rrn,
            String stan,
            String pan,
            String cuentaOrigen,
            BigDecimal monto,
            String codigoProcesamiento,
            String terminalId
    ) {
        String tipoOp = switch (codigoProcesamiento != null ? codigoProcesamiento.substring(0, 2) : "00") {
            case "01" -> "RETIRO";
            case "30" -> "CONSULTA";
            case "20" -> "DEVOLUCION";
            case "40" -> "TRANSFERENCIA";
            default -> "COMPRA";
        };

        return new TransaccionCoreRequest(
                rrn,
                stan,
                pan,
                cuentaOrigen,
                null,
                monto,
                tipoOp,
                terminalId,
                codigoProcesamiento
        );
    }
}
