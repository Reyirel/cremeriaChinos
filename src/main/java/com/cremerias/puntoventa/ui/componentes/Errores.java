package com.cremerias.puntoventa.ui.componentes;

/** Convierte una excepción en un mensaje que se puede mostrar a quien usa la caja. */
public final class Errores {

    private Errores() {
    }

    public static String mensaje(Throwable e) {
        Throwable causa = e;
        while (causa.getCause() != null && (causa.getMessage() == null || causa.getMessage().startsWith("Error en la base"))) {
            causa = causa.getCause();
        }
        String m = causa.getMessage();
        if (m == null) {
            return "Ocurrió un error inesperado.";
        }
        if (m.contains("UNIQUE constraint failed")) {
            return "Ese dato ya existe (debe ser único).";
        }
        if (m.contains("SQLITE_BUSY") || m.contains("database is locked")) {
            return "La base de datos está ocupada en este momento. Intenta de nuevo en unos segundos.";
        }
        return m;
    }
}
