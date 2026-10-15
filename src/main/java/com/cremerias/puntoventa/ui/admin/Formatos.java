package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.util.Cantidades;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Textos y formatos que comparten las páginas del panel. */
final class Formatos {

    static final Locale ES = Locale.forLanguageTag("es-MX");
    static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    static final DateTimeFormatter FECHA_CORTA = DateTimeFormatter.ofPattern("d MMM yyyy", ES);
    static final DateTimeFormatter FECHA_HORA_CORTA = DateTimeFormatter.ofPattern("d MMM, HH:mm", ES);

    private Formatos() {
    }

    static String fechaHora(Instant instante) {
        return instante == null ? "—" : FECHA_HORA.format(instante.atZone(ZoneId.systemDefault()));
    }

    static String fechaCorta(Instant instante) {
        return instante == null ? "—" : FECHA_CORTA.format(instante.atZone(ZoneId.systemDefault()));
    }

    /** "Hoy, 14:05" / "12 oct, 09:30". */
    static String cuando(Instant instante) {
        if (instante == null) {
            return "—";
        }
        var local = instante.atZone(ZoneId.systemDefault());
        if (local.toLocalDate().equals(LocalDate.now())) {
            return "Hoy, " + DateTimeFormatter.ofPattern("HH:mm").format(local);
        }
        return FECHA_HORA_CORTA.format(local);
    }

    static String seVende(Unidad unidad) {
        return unidad == Unidad.KG ? "Por kilo" : "Por pieza";
    }

    static String porUnidad(Unidad unidad) {
        return unidad == Unidad.KG ? " / kg" : " / pza";
    }

    /** "Clave 401 · Inv. INV-Q01 · Quesos". */
    static String codigos(ProductoCatalogo p, boolean conCategoria) {
        return Stream.of(p.clave() == null || p.clave().isBlank() ? null : "Clave " + p.clave(),
                        p.codigoInventario() == null || p.codigoInventario().isBlank() ? null : "Inv. " + p.codigoInventario(),
                        conCategoria ? p.categoria() : null)
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.joining(" · "));
    }

    /** Sucursal con su código para distinguir nombres repetidos: "Cremería Centro (CENTRO)". */
    static String sucursal(Sucursal s) {
        if (s == null) {
            return "—";
        }
        return s.codigo() == null || s.codigo().isBlank() ? s.nombre() : s.nombre() + " (" + s.codigo() + ")";
    }

    /** Movimiento de inventario en palabras. */
    static String movimiento(String tipo) {
        return switch (tipo) {
            case "ENTRADA" -> "Entrada";
            case "VENTA" -> "Venta";
            case "CANCELACION_VENTA" -> "Venta cancelada";
            case "AJUSTE" -> "Ajuste";
            case "MERMA" -> "Merma";
            case "SURTIDO_SALIDA" -> "Surtido a sucursal";
            case "SURTIDO_ENTRADA" -> "Llegó por surtido";
            default -> tipo;
        };
    }

    /** Origen de un lote en palabras. */
    static String origenLote(String origen) {
        return switch (origen) {
            case "ENTRADA" -> "Reabastecimiento";
            case "SURTIDO" -> "Surtido";
            case "INICIAL" -> "Inventario inicial";
            default -> "Migración";
        };
    }

    /** Separa "1,250 g" en número y unidad para mostrarlos con distinto peso. */
    static String[] cantidadPartida(Unidad unidad, java.math.BigDecimal cantidad) {
        String texto = Cantidades.formatear(unidad, cantidad);
        int espacio = texto.lastIndexOf(' ');
        return espacio < 0 ? new String[]{texto, ""} : new String[]{texto.substring(0, espacio), texto.substring(espacio + 1)};
    }
}
