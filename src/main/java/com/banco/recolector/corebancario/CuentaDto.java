package com.banco.recolector.corebancario;

import java.math.BigDecimal;

/**
 * DTO Inmutable (Java Record) con la información de una cuenta bancaria y su tarjeta asociada.
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
    String pin,
    String banco
) {
    public CuentaDto(String numeroCuenta, String titular, String pan, BigDecimal saldoDisponible,
                     BigDecimal saldoContable, BigDecimal limiteDiario, BigDecimal retiradoHoy, String estado, String pin) {
        this(numeroCuenta, titular, pan, saldoDisponible, saldoContable, limiteDiario, retiradoHoy, estado, pin, "BANCO INDUSTRIAL");
    }

    public CuentaDto(String numeroCuenta, String titular, String pan, BigDecimal saldoDisponible,
                     BigDecimal saldoContable, BigDecimal limiteDiario, BigDecimal retiradoHoy, String estado) {
        this(numeroCuenta, titular, pan, saldoDisponible, saldoContable, limiteDiario, retiradoHoy, estado, "1234", "BANCO INDUSTRIAL");
    }
}
