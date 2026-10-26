package com.banco.recolector.corebancario;

import java.math.BigDecimal;

/**
 * DTO Inmutable (Java Record) con la información pública de una cuenta y su tarjeta asociada.
 * Por seguridad nunca incluye el PIN ni su hash.
 */
public record CuentaDto(
    String numeroCuenta,
    String titular,
    String pan,
    BigDecimal saldoDisponible,
    BigDecimal saldoContable,
    BigDecimal limiteDiario,
    BigDecimal retiradoHoy,
    String estado,
    String banco
) {}
