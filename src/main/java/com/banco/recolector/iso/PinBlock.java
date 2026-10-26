package com.banco.recolector.iso;

import java.util.Optional;

/**
 * PIN Block ISO 9564 Formato 0 (ISO-0) para el Data Element 52.
 *
 * <p>Bloque = (0 | longitud | PIN | relleno F) XOR (0000 | 12 dígitos del PAN sin dígito verificador).
 * En producción el bloque viaja cifrado bajo una ZPK dentro de un HSM; en este simulador viaja en claro
 * para poder demostrar la validación del PIN extremo a extremo.
 */
public final class PinBlock {

    private PinBlock() {}

    /** Construye el PIN block ISO-0 (16 dígitos hexadecimales) a partir del PIN y el PAN. */
    public static String formato0(String pin, String pan) {
        if (pin == null || !pin.matches("\\d{4,12}")) {
            throw new IllegalArgumentException("El PIN debe tener entre 4 y 12 dígitos");
        }
        String campoPin = ("0" + Integer.toHexString(pin.length()).toUpperCase() + pin + "F".repeat(14)).substring(0, 16);
        return xor(campoPin, campoPan(pan));
    }

    /** Recupera el PIN de un PIN block ISO-0; vacío si el bloque o el PAN no son válidos. */
    public static Optional<String> extraerPin(String pinBlock, String pan) {
        if (pinBlock == null || !pinBlock.matches("[0-9A-Fa-f]{16}")) {
            return Optional.empty();
        }
        String campoPin;
        try {
            campoPin = xor(pinBlock.toUpperCase(), campoPan(pan));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        int longitud = Character.digit(campoPin.charAt(1), 16);
        if (campoPin.charAt(0) != '0' || longitud < 4 || longitud > 12) {
            return Optional.empty();
        }
        String pin = campoPin.substring(2, 2 + longitud);
        String relleno = campoPin.substring(2 + longitud);
        if (!pin.matches("\\d+") || !relleno.matches("F*")) {
            return Optional.empty();
        }
        return Optional.of(pin);
    }

    private static String campoPan(String pan) {
        if (pan == null || !pan.matches("\\d{13,19}")) {
            throw new IllegalArgumentException("PAN inválido para el PIN block");
        }
        // 12 dígitos más a la derecha excluyendo el dígito verificador
        return "0000" + pan.substring(pan.length() - 13, pan.length() - 1);
    }

    private static String xor(String a, String b) {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            int v = Character.digit(a.charAt(i), 16) ^ Character.digit(b.charAt(i), 16);
            sb.append(Character.toUpperCase(Character.forDigit(v, 16)));
        }
        return sb.toString();
    }
}
