package com.banco.recolector.corebancario;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * DTO Inmutable (Java Record) que representa la respuesta emitida por el Core Bancario.
 * Incluye saldos, código de autorización y código de respuesta mapped a DE 39.
 */
public record TransaccionCoreResponse(
    boolean exitosa,
    String codigoRespuesta,     // Código ISO DE 39 ("00", "51", "14", "05", etc.)
    String codigoAutorizacion,  // Código de aprobación (DE 38, ej. "100001")
    BigDecimal saldoDisponible,
    BigDecimal saldoContable,
    String mensaje,
    String rrn,
    String timestamp
) {
    public static TransaccionCoreResponse aprobada(
            String rrn,
            String authCode,
            BigDecimal saldoDisp,
            BigDecimal saldoCont
    ) {
        return new TransaccionCoreResponse(
                true,
                "00",
                authCode,
                saldoDisp,
                saldoCont,
                "Transacción Aprobada Exitosamente",
                rrn,
                LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)
        );
    }

    public static TransaccionCoreResponse rechazada(
            String rrn,
            String codigoIso,
            String motivo,
            BigDecimal saldoDisp,
            BigDecimal saldoCont
    ) {
        return new TransaccionCoreResponse(
                false,
                codigoIso,
                null,
                saldoDisp != null ? saldoDisp : BigDecimal.ZERO,
                saldoCont != null ? saldoCont : BigDecimal.ZERO,
                motivo,
                rrn,
                LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)
        );
    }
}
