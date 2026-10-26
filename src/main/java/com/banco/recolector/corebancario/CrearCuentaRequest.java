package com.banco.recolector.corebancario;

import java.math.BigDecimal;

/**
 * Petición para crear una nueva cuenta bancaria y tarjeta de débito en el Core Bancario.
 */
public record CrearCuentaRequest(
    String titular,
    String numeroCuenta,
    String pan,
    BigDecimal saldoInicial,
    BigDecimal limiteDiario,
    String estado,
    String pin,
    String banco
) {
    public CrearCuentaRequest(String titular, String numeroCuenta, String pan, BigDecimal saldoInicial,
                              BigDecimal limiteDiario, String estado, String pin) {
        this(titular, numeroCuenta, pan, saldoInicial, limiteDiario, estado, pin, "BANCO INDUSTRIAL");
    }
}
