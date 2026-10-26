package com.banco.recolector.corebancario;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Petición para actualizar datos o estado de una cuenta bancaria; los campos nulos no se modifican.
 */
public record EditarCuentaRequest(
    @Size(max = 100, message = "El titular admite máximo 100 caracteres")
    String titular,

    @PositiveOrZero(message = "El límite diario no puede ser negativo")
    @Digits(integer = 13, fraction = 2, message = "El límite diario admite 2 decimales")
    BigDecimal limiteDiario,

    @PositiveOrZero(message = "El saldo no puede ser negativo")
    @Digits(integer = 13, fraction = 2, message = "El saldo admite 2 decimales")
    BigDecimal saldoDisponible,

    @Pattern(regexp = "(?i)ACTIVE|BLOCKED|EXPIRED", message = "El estado debe ser ACTIVE, BLOCKED o EXPIRED")
    String estado,

    @Pattern(regexp = "\\d{4}", message = "El PIN debe tener exactamente 4 dígitos")
    String pin,

    @Size(max = 60, message = "El banco admite máximo 60 caracteres")
    String banco
) {}
