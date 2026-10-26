package com.banco.recolector.corebancario;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Petición para crear una nueva cuenta bancaria y tarjeta de débito en el Core Bancario.
 * Los campos omitidos se completan con valores por defecto (número, PAN, saldo, límite, PIN 1234).
 */
public record CrearCuentaRequest(
    @Size(max = 100, message = "El titular admite máximo 100 caracteres")
    String titular,

    @Pattern(regexp = "[0-9A-Za-z-]{1,30}", message = "El número de cuenta admite hasta 30 caracteres alfanuméricos o guiones")
    String numeroCuenta,

    @Pattern(regexp = "\\d{13,19}", message = "El PAN debe tener entre 13 y 19 dígitos")
    String pan,

    @PositiveOrZero(message = "El saldo inicial no puede ser negativo")
    @Digits(integer = 13, fraction = 2, message = "El saldo inicial admite 2 decimales")
    BigDecimal saldoInicial,

    @PositiveOrZero(message = "El límite diario no puede ser negativo")
    @Digits(integer = 13, fraction = 2, message = "El límite diario admite 2 decimales")
    BigDecimal limiteDiario,

    @Pattern(regexp = "(?i)ACTIVE|BLOCKED|EXPIRED", message = "El estado debe ser ACTIVE, BLOCKED o EXPIRED")
    String estado,

    @Pattern(regexp = "\\d{4}", message = "El PIN debe tener exactamente 4 dígitos")
    String pin,

    @Size(max = 60, message = "El banco admite máximo 60 caracteres")
    String banco
) {}
