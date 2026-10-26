package com.banco.recolector.iso;

/**
 * Catálogo de códigos de respuesta ISO 8583 (Data Element 39).
 * Define los códigos estándar de acción bancaria para MTI 0210, 0110, 0430, 0810.
 */
public final class IsoResponseCode {

    private IsoResponseCode() {}

    /** 00 - Transacción Aprobada Exitosamente (Approved) */
    public static final String APROBADA = "00";

    /** 05 - Denegada / Tarjeta Bloqueada / No Honrar (Do Not Honor) */
    public static final String TARJETA_BLOQUEADA = "05";

    /** 14 - Número de Tarjeta o Cuenta Inválido / No Existe (Invalid Account Number) */
    public static final String CUENTA_INVALIDA = "14";

    /** 30 - Error de Formato en la Trama ISO 8583 (Format Error) */
    public static final String ERROR_FORMATO = "30";

    /** 51 - Fondos Insuficientes en la Cuenta (Insufficient Funds) */
    public static final String FONDOS_INSUFICIENTES = "51";

    /** 54 - Tarjeta Vencida / Expirada (Expired Card) */
    public static final String TARJETA_EXPIRADA = "54";

    /** 55 - Clave PIN Incorrecta (Incorrect PIN) */
    public static final String PIN_INCORRECTO = "55";

    /** 61 - Supera Límite de Retiro Diario o por Operación (Exceeds Withdrawal Amount Limit) */
    public static final String LIMITE_EXCEDIDO = "61";

    /** 68 - Respuesta Recibida Demasiado Tarde / Tiempo de Espera Agotado (Timeout) */
    public static final String TIMEOUT = "68";

    /** 91 - Emisor Fuera de Línea / Host No Disponible (Card Issuer Inoperative) */
    public static final String HOST_NO_DISPONIBLE = "91";

    /** 96 - Error Interno del Sistema Bancario (System Malfunction) */
    public static final String ERROR_SISTEMA = "96";

    /**
     * Retorna una descripción legible para el código de respuesta.
     */
    public static String getDescripcion(String codigo) {
        if (codigo == null) return "Código Desconocido";
        return switch (codigo) {
            case APROBADA -> "00 - Aprobado Exitosamente";
            case TARJETA_BLOQUEADA -> "05 - Denegada / Tarjeta o Cuenta Bloqueada";
            case CUENTA_INVALIDA -> "14 - Cuenta o Tarjeta No Existe";
            case ERROR_FORMATO -> "30 - Error de Formato en Trama ISO";
            case FONDOS_INSUFICIENTES -> "51 - Fondos Insuficientes";
            case TARJETA_EXPIRADA -> "54 - Tarjeta Vencida o Expirada";
            case PIN_INCORRECTO -> "55 - Clave PIN Incorrecta";
            case LIMITE_EXCEDIDO -> "61 - Excede Límite Diario de Retiro";
            case TIMEOUT -> "68 - Tiempo de Espera Agotado";
            case HOST_NO_DISPONIBLE -> "91 - Emisor Fuera de Línea / Host No Disponible";
            case ERROR_SISTEMA -> "96 - Error Interno del Sistema Bancario";
            default -> codigo + " - Respuesta no catalogada";
        };
    }
}
