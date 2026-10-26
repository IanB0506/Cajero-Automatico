package com.banco.recolector.corebancario.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hash del PIN: SHA-256 en hexadecimal (mayúsculas) de "numeroCuenta:PIN".
 * Coincide con {@code CONVERT(VARCHAR(64), HASHBYTES('SHA2_256', numero_cuenta + ':' + pin), 2)}
 * usado en init_sqlserver.sql, de modo que el PIN nunca se guarda en claro.
 */
public final class PinHasher {

    private PinHasher() {}

    public static String hash(String numeroCuenta, String pin) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest((numeroCuenta + ":" + pin).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /** Comparación en tiempo constante del PIN contra el hash almacenado. */
    public static boolean coincide(String numeroCuenta, String pin, String hashGuardado) {
        if (hashGuardado == null || pin == null) {
            return false;
        }
        byte[] esperado = hashGuardado.toUpperCase().getBytes(StandardCharsets.US_ASCII);
        byte[] calculado = hash(numeroCuenta, pin).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(esperado, calculado);
    }
}
