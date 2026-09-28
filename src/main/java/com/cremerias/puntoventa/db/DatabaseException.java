package com.cremerias.puntoventa.db;

public class DatabaseException extends RuntimeException {

    public DatabaseException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
