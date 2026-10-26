package com.banco.recolector.corebancario;

import java.math.BigDecimal;

/**
 * Petición para actualizar datos o estado de una cuenta bancaria en el Core Bancario.
 */
public record EditarCuentaRequest(
    String titular,
    BigDecimal limiteDiario,
    BigDecimal saldoDisponible,
    String estado,
    String pin,
    String banco
) {
    public EditarCuentaRequest(String titular, BigDecimal limiteDiario, BigDecimal saldoDisponible, String estado, String pin) {
        this(titular, limiteDiario, saldoDisponible, estado, pin, null);
    }
}
