package com.cremerias.puntoventa.ui.componentes;

import com.cremerias.puntoventa.model.ResumenTurno;
import com.cremerias.puntoventa.model.Ticket;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.util.Dinero;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Genera el texto de los tickets (40 columnas, apto para impresora térmica de 80 mm). */
public final class TicketTexto {

    public static final int ANCHO = 40;
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String NEGOCIO = "CREMERÍA";

    /** Qué tanto debe resaltar una línea al imprimirse (en pantalla todas se ven igual). */
    public enum Enfasis { NORMAL, DESTACADO }

    /** Una línea del ticket ya formateada, con su énfasis para la impresión. */
    public record Linea(String texto, Enfasis enfasis) {
    }

    private TicketTexto() {
    }

    public static String venta(Ticket t) {
        return construirVenta(t).texto();
    }

    public static List<Linea> lineasVenta(Ticket t) {
        return construirVenta(t).lineas();
    }

    private static Renglones construirVenta(Ticket t) {
        Renglones out = new Renglones();
        out.centrar(NEGOCIO, Enfasis.DESTACADO);
        out.centrar(t.sucursal(), Enfasis.DESTACADO);
        out.separador();
        out.agregar("Folio:  " + t.folio());
        out.agregar("Fecha:  " + fecha(t.fecha()));
        out.agregar("Cajero: " + t.cajero());
        if (t.cancelada()) {
            out.separador();
            out.centrar("*** VENTA CANCELADA ***", Enfasis.DESTACADO);
        }
        out.separador();
        for (Ticket.Renglon r : t.renglones()) {
            for (String linea : ajustar(r.descripcion(), ANCHO)) {
                out.agregar(linea);
            }
            String detalle = "  " + r.unidad().formatear(r.cantidad()) + " x "
                    + Dinero.formatear(r.precioCentavos()) + (r.unidad() == Unidad.KG ? "/kg" : "");
            out.ambos(detalle, Dinero.formatear(r.importeCentavos()));
        }
        out.separador();
        out.ambos("TOTAL", Dinero.formatear(t.totalCentavos()), Enfasis.DESTACADO);
        for (Ticket.PagoTicket p : t.pagos()) {
            out.ambos(p.metodo().nombre(), Dinero.formatear(p.recibidoCentavos()));
            if (p.referencia() != null && !p.referencia().isBlank()) {
                out.agregar("  Ref: " + p.referencia());
            }
        }
        if (t.cambioCentavos() > 0) {
            out.ambos("Cambio", Dinero.formatear(t.cambioCentavos()));
        }
        out.separador();
        out.agregar("Artículos: " + t.articulos().stripTrailingZeros().toPlainString());
        out.agregar("");
        out.centrar("¡Gracias por su compra!", Enfasis.DESTACADO);
        return out;
    }

    public static String corte(ResumenTurno r, long contado, String sucursal, Instant cierre, String notas,
                               String realizadoPor) {
        return construirCorte(r, contado, sucursal, cierre, notas, realizadoPor).texto();
    }

    public static List<Linea> lineasCorte(ResumenTurno r, long contado, String sucursal, Instant cierre, String notas,
                                          String realizadoPor) {
        return construirCorte(r, contado, sucursal, cierre, notas, realizadoPor).lineas();
    }

    private static Renglones construirCorte(ResumenTurno r, long contado, String sucursal, Instant cierre,
                                            String notas, String realizadoPor) {
        Renglones out = new Renglones();
        out.centrar(NEGOCIO, Enfasis.DESTACADO);
        out.centrar(sucursal, Enfasis.DESTACADO);
        out.centrar("CORTE DE CAJA", Enfasis.DESTACADO);
        out.separador();
        out.agregar("Caja:     " + r.turno().dispositivoNombre());
        out.agregar("Cajero:   " + r.turno().usuarioNombre());
        if (realizadoPor != null && !realizadoPor.isBlank() && !realizadoPor.equals(r.turno().usuarioNombre())) {
            out.agregar("Corte:    " + realizadoPor);
        }
        out.agregar("Apertura: " + fecha(r.turno().abiertoEn()));
        out.agregar("Cierre:   " + fecha(cierre));
        out.separador();
        out.ambos("Ventas en efectivo", Dinero.formatear(r.ventasEfectivo()));
        out.ambos("+ Entradas", Dinero.formatear(r.entradas()));
        out.ambos("- Retiros", Dinero.formatear(r.retiros()));
        out.ambos("= Efectivo esperado", Dinero.formatear(r.efectivoEsperado()), Enfasis.DESTACADO);
        out.ambos("Efectivo contado", Dinero.formatear(contado));
        long diferencia = contado - r.efectivoEsperado();
        out.ambos(diferencia < 0 ? "FALTANTE" : diferencia > 0 ? "SOBRANTE" : "Diferencia",
                Dinero.formatear(diferencia), Enfasis.DESTACADO);
        out.separador();
        out.ambos("Ventas con tarjeta", Dinero.formatear(r.ventasTarjeta()));
        out.ambos("Ventas por transferencia", Dinero.formatear(r.ventasTransferencia()));
        out.ambos("TOTAL VENDIDO", Dinero.formatear(r.totalVentas()), Enfasis.DESTACADO);
        out.ambos("Número de ventas", String.valueOf(r.numeroVentas()));
        out.ambos("Canceladas (" + r.numeroCanceladas() + ")", Dinero.formatear(r.totalCancelado()));
        if (notas != null && !notas.isBlank()) {
            out.separador();
            out.agregar("Notas:");
            for (String linea : ajustar(notas, ANCHO)) {
                out.agregar(linea);
            }
        }
        out.agregar("");
        out.agregar("");
        out.centrar("______________________");
        out.centrar("Firma del cajero");
        return out;
    }

    public static String surtido(com.cremerias.puntoventa.service.admin.SurtidoService.Detalle d) {
        return construirSurtido(d).texto();
    }

    public static List<Linea> lineasSurtido(com.cremerias.puntoventa.service.admin.SurtidoService.Detalle d) {
        return construirSurtido(d).lineas();
    }

    private static Renglones construirSurtido(com.cremerias.puntoventa.service.admin.SurtidoService.Detalle d) {
        var r = d.resumen();
        Renglones out = new Renglones();
        out.centrar(NEGOCIO, Enfasis.DESTACADO);
        out.centrar("ALMACÉN CENTRAL", Enfasis.DESTACADO);
        out.centrar("SURTIDO A SUCURSAL", Enfasis.DESTACADO);
        out.separador();
        out.agregar("Folio:    " + r.folio());
        out.agregar("Fecha:    " + fecha(r.fecha()));
        out.agregar("Destino:  " + r.sucursal());
        out.agregar("Pago:     " + r.formaPago().nombre());
        out.agregar("Surtió:   " + r.usuario());
        if (r.ordenFolio() != null) {
            out.agregar("Orden:    " + r.ordenFolio());
        }
        out.separador();
        for (var l : d.lineas()) {
            for (String linea : ajustar(l.producto(), ANCHO)) {
                out.agregar(linea);
            }
            out.ambos("  " + com.cremerias.puntoventa.util.Cantidades.formatear(l.unidad(), l.cantidad()),
                    Dinero.formatear(l.costoCentavos()));
            for (var precio : l.precios().entrySet()) {
                out.agregar("    Precio " + precio.getKey() + ": " + Dinero.formatear(precio.getValue()));
            }
        }
        out.separador();
        out.ambos("TOTAL ENVIADO (COSTO)", Dinero.formatear(r.costoCentavos()), Enfasis.DESTACADO);
        out.ambos("Valor a precio de venta", Dinero.formatear(r.ventaCentavos()));
        if (r.formaPago() == com.cremerias.puntoventa.service.admin.SurtidoService.FormaPago.CREDITO) {
            out.ambos("Saldo de crédito", Dinero.formatear(d.saldoCredito()));
        }
        if (d.notas() != null) {
            out.separador();
            for (String linea : ajustar("Notas: " + d.notas(), ANCHO)) {
                out.agregar(linea);
            }
        }
        out.agregar("");
        out.agregar("");
        out.centrar("____________      ____________");
        out.centrar("  Entregó            Recibió  ");
        return out;
    }

    public static String abono(com.cremerias.puntoventa.service.admin.CreditoService.Abono a) {
        return construirAbono(a).texto();
    }

    public static List<Linea> lineasAbono(com.cremerias.puntoventa.service.admin.CreditoService.Abono a) {
        return construirAbono(a).lineas();
    }

    private static Renglones construirAbono(com.cremerias.puntoventa.service.admin.CreditoService.Abono a) {
        Renglones out = new Renglones();
        out.centrar(NEGOCIO, Enfasis.DESTACADO);
        out.centrar("ALMACÉN CENTRAL", Enfasis.DESTACADO);
        out.centrar("ABONO A CRÉDITO", Enfasis.DESTACADO);
        out.separador();
        out.agregar("Folio:    " + a.folio());
        out.agregar("Fecha:    " + fecha(a.fecha()));
        out.agregar("Sucursal: " + a.sucursal().nombre());
        out.agregar("Recibió:  " + a.usuario());
        out.separador();
        out.ambos("Saldo anterior", Dinero.formatear(a.saldoAnterior()));
        out.ambos("ABONO", Dinero.formatear(a.montoCentavos()), Enfasis.DESTACADO);
        out.ambos("Saldo nuevo", Dinero.formatear(a.saldoNuevo()), Enfasis.DESTACADO);
        if (a.nota() != null && !a.nota().isBlank()) {
            out.separador();
            for (String linea : ajustar("Nota: " + a.nota(), ANCHO)) {
                out.agregar(linea);
            }
        }
        out.agregar("");
        out.agregar("");
        out.centrar("______________________");
        out.centrar("Firma");
        return out;
    }

    private static String fecha(Instant instante) {
        return FECHA.format(instante.atZone(ZoneId.systemDefault()));
    }

    static List<String> ajustar(String texto, int ancho) {
        List<String> lineas = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        for (String palabra : texto.split("\\s+")) {
            if (!actual.isEmpty() && actual.length() + 1 + palabra.length() > ancho) {
                lineas.add(actual.toString());
                actual.setLength(0);
            }
            if (!actual.isEmpty()) {
                actual.append(' ');
            }
            actual.append(palabra);
        }
        if (!actual.isEmpty()) {
            lineas.add(actual.toString());
        }
        return lineas;
    }

    /** Acumula las líneas de un ticket: sirve tanto para mostrarlo en pantalla (texto plano,
     *  uniendo las líneas) como para imprimirlo resaltando lo importante en vez de parejo. */
    private static final class Renglones {
        private final List<Linea> lineas = new ArrayList<>();

        void agregar(String texto) {
            agregar(texto, Enfasis.NORMAL);
        }

        void agregar(String texto, Enfasis enfasis) {
            lineas.add(new Linea(texto, enfasis));
        }

        void separador() {
            agregar("-".repeat(ANCHO));
        }

        void centrar(String texto) {
            centrar(texto, Enfasis.NORMAL);
        }

        void centrar(String texto, Enfasis enfasis) {
            int espacios = Math.max(0, (ANCHO - texto.length()) / 2);
            agregar(" ".repeat(espacios) + texto, enfasis);
        }

        void ambos(String izquierda, String derecha) {
            ambos(izquierda, derecha, Enfasis.NORMAL);
        }

        void ambos(String izquierda, String derecha, Enfasis enfasis) {
            int espacios = ANCHO - izquierda.length() - derecha.length();
            if (espacios < 1) {
                agregar(izquierda, enfasis);
                agregar(" ".repeat(Math.max(0, ANCHO - derecha.length())) + derecha, enfasis);
            } else {
                agregar(izquierda + " ".repeat(espacios) + derecha, enfasis);
            }
        }

        List<Linea> lineas() {
            return lineas;
        }

        String texto() {
            return lineas.stream().map(Linea::texto).collect(Collectors.joining("\n")) + "\n";
        }
    }
}
