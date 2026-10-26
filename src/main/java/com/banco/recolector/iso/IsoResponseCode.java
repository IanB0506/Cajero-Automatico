package com.banco.recolector.iso;

/**
 * Catálogo de códigos de respuesta ISO 8583 (Data Element 39).
 * Define los códigos estándar de acción bancaria para MTI 0110, 0210, 0430 y 0810.
 */
public final class IsoResponseCode {

    private IsoResponseCode() {}

    /** 00 - Transacción Aprobada Exitosamente (Approved) */
    public static final String APROBADA = "00";

    /** 05 - Denegada / Tarjeta Bloqueada / No Honrar (Do Not Honor) */
    public static final String TARJETA_BLOQUEADA = "05";

    /** 12 - Transacción Inválida o no soportada (Invalid Transaction) */
    public static final String TRANSACCION_INVALIDA = "12";

    /** 13 - Monto Inválido (Invalid Amount) */
    public static final String MONTO_INVALIDO = "13";

    /** 14 - Número de Tarjeta o Cuenta Inválido / No Existe (Invalid Account Number) */
    public static final String CUENTA_INVALIDA = "14";

    /** 25 - No se encuentra la transacción original (Unable to Locate Record) */
    public static final String ORIGINAL_NO_ENCONTRADA = "25";

    /** 30 - Error de Formato en la Trama ISO 8583 (Format Error) */
    public static final String ERROR_FORMATO = "30";

    /** 51 - Fondos Insuficientes en la Cuenta (Insufficient Funds) */
    public static final String FONDOS_INSUFICIENTES = "51";

    /** 54 - Tarjeta Vencida / Expirada (Expired Card) */
    public static final String TARJETA_EXPIRADA = "54";

    /** 55 - Clave PIN Incorrecta o ausente (Incorrect PIN) */
    public static final String PIN_INCORRECTO = "55";

    /** 61 - Supera Límite de Retiro Diario (Exceeds Withdrawal Amount Limit) */
    public static final String LIMITE_EXCEDIDO = "61";

    /** 68 - Respuesta Recibida Demasiado Tarde / Tiempo de Espera Agotado (Timeout) */
    public static final String TIMEOUT = "68";

    /** 75 - Excede intentos de PIN permitidos: tarjeta bloqueada (PIN Tries Exceeded) */
    public static final String INTENTOS_PIN_EXCEDIDOS = "75";

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
            case TRANSACCION_INVALIDA -> "12 - Transacción Inválida o No Soportada";
            case MONTO_INVALIDO -> "13 - Monto Inválido";
            case CUENTA_INVALIDA -> "14 - Cuenta o Tarjeta No Existe";
            case ORIGINAL_NO_ENCONTRADA -> "25 - Transacción Original No Encontrada";
            case ERROR_FORMATO -> "30 - Error de Formato en Trama ISO";
            case FONDOS_INSUFICIENTES -> "51 - Fondos Insuficientes";
            case TARJETA_EXPIRADA -> "54 - Tarjeta Vencida o Expirada";
            case PIN_INCORRECTO -> "55 - Clave PIN Incorrecta";
            case LIMITE_EXCEDIDO -> "61 - Excede Límite Diario de Retiro";
            case TIMEOUT -> "68 - Tiempo de Espera Agotado";
            case INTENTOS_PIN_EXCEDIDOS -> "75 - Intentos de PIN Excedidos: Tarjeta Bloqueada";
            case HOST_NO_DISPONIBLE -> "91 - Emisor Fuera de Línea / Host No Disponible";
            case ERROR_SISTEMA -> "96 - Error Interno del Sistema Bancario";
            default -> codigo + " - Respuesta no catalogada";
        };
    }
}
