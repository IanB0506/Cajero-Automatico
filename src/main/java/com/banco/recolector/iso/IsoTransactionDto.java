package com.banco.recolector.iso;

import java.math.BigDecimal;

/**
 * DTO Inmutable (Java Record) que representa una transacción ISO 8583 normalizada.
 * Contiene los Data Elements críticos extraídos de forma segura:
 * DE 2 (PAN), DE 3 (ProcCode), DE 4 (Monto), DE 11 (STAN), DE 37 (RRN), DE 102 (Cuenta).
 */
public record IsoTransactionDto(
    String mti,
    String pan,
    String processingCode,
    BigDecimal amount,
    String stan,
    String rrn,
    String terminalId,
    String merchantId,
    String cuenta,
    String responseCode,
    String authCode
) {
    /**
     * Retorna el PAN enmascarado conforme a PCI-DSS (primeros 6 y últimos 4 visibles).
     */
    public String panEnmascarado() {
        if (pan == null || pan.length() < 10) return pan;
        int len = pan.length();
        return pan.substring(0, 6) + "*".repeat(len - 10) + pan.substring(len - 4);
    }

    /**
     * Clave única de idempotencia compuesta por RRN y STAN.
     */
    public String claveIdempotencia() {
        return (rrn != null ? rrn : "") + ":" + (stan != null ? stan : "");
    }
}
