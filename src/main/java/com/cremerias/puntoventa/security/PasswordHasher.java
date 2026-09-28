package com.cremerias.puntoventa.security;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;

/**
 * Hash de contraseñas con BCrypt (formato $2a$, compatible con Supabase/pgcrypto),
 * de modo que el inicio de sesión funciona sin internet con las credenciales guardadas.
 */
public class PasswordHasher {

    public static final int COSTO_POR_DEFECTO = 12;

    private final int costo;

    public PasswordHasher() {
        this(COSTO_POR_DEFECTO);
    }

    public PasswordHasher(int costo) {
        this.costo = costo;
    }

    public String hash(char[] password) {
        return BCrypt.withDefaults().hashToString(costo, password);
    }

    public boolean verificar(char[] password, String hash) {
        if (hash == null) {
            return false;
        }
        return BCrypt.verifyer().verify(password, hash.getBytes(StandardCharsets.UTF_8)).verified;
    }
}
