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

/** Genera el texto de los tickets (40 columnas, apto para impresora térmica de 80 mm). */
public final class TicketTexto {

    public static final int ANCHO = 40;
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String NEGOCIO = "CREMERÍA";

    private TicketTexto() {
    }

    public static String venta(Ticket t) {
        StringBuilder sb = new StringBuilder();
        centrar(sb, NEGOCIO);
        centrar(sb, t.sucursal());
        separador(sb);
        sb.append("Folio:  ").append(t.folio()).append('\n');
        sb.append("Fecha:  ").append(fecha(t.fecha())).append('\n');
        sb.append("Cajero: ").append(t.cajero()).append('\n');
        if (t.cancelada()) {
            separador(sb);
            centrar(sb, "*** VENTA CANCELADA ***");
        }
        separador(sb);
        for (Ticket.Renglon r : t.renglones()) {
            for (String linea : ajustar(r.descripcion(), ANCHO)) {
                sb.append(linea).append('\n');
            }
            String detalle = "  " + r.unidad().formatear(r.cantidad()) + " x "
                    + Dinero.formatear(r.precioCentavos()) + (r.unidad() == Unidad.KG ? "/kg" : "");
            ambos(sb, detalle, Dinero.formatear(r.importeCentavos()));
        }
        separador(sb);
        ambos(sb, "TOTAL", Dinero.formatear(t.totalCentavos()));
        for (Ticket.PagoTicket p : t.pagos()) {
            ambos(sb, p.metodo().nombre(), Dinero.formatear(p.recibidoCentavos()));
            if (p.referencia() != null && !p.referencia().isBlank()) {
                sb.append("  Ref: ").append(p.referencia()).append('\n');
            }
        }
        if (t.cambioCentavos() > 0) {
            ambos(sb, "Cambio", Dinero.formatear(t.cambioCentavos()));
        }
        separador(sb);
        sb.append("Artículos: ").append(t.articulos().stripTrailingZeros().toPlainString()).append('\n');
        sb.append('\n');
        centrar(sb, "¡Gracias por su compra!");
        return sb.toString();
    }

    public static String corte(ResumenTurno r, long contado, String sucursal, Instant cierre, String notas,
                               String realizadoPor) {
        StringBuilder sb = new StringBuilder();
        centrar(sb, NEGOCIO);
        centrar(sb, sucursal);
        centrar(sb, "CORTE DE CAJA");
        separador(sb);
        sb.append("Caja:     ").append(r.turno().dispositivoNombre()).append('\n');
        sb.append("Cajero:   ").append(r.turno().usuarioNombre()).append('\n');
        if (realizadoPor != null && !realizadoPor.isBlank() && !realizadoPor.equals(r.turno().usuarioNombre())) {
            sb.append("Corte:    ").append(realizadoPor).append('\n');
        }
        sb.append("Apertura: ").append(fecha(r.turno().abiertoEn())).append('\n');
        sb.append("Cierre:   ").append(fecha(cierre)).append('\n');
        separador(sb);
        ambos(sb, "Ventas en efectivo", Dinero.formatear(r.ventasEfectivo()));
        ambos(sb, "+ Entradas", Dinero.formatear(r.entradas()));
        ambos(sb, "- Retiros", Dinero.formatear(r.retiros()));
        ambos(sb, "= Efectivo esperado", Dinero.formatear(r.efectivoEsperado()));
        ambos(sb, "Efectivo contado", Dinero.formatear(contado));
        long diferencia = contado - r.efectivoEsperado();
        ambos(sb, diferencia < 0 ? "FALTANTE" : diferencia > 0 ? "SOBRANTE" : "Diferencia",
                Dinero.formatear(diferencia));
        separador(sb);
        ambos(sb, "Ventas con tarjeta", Dinero.formatear(r.ventasTarjeta()));
        ambos(sb, "Ventas por transferencia", Dinero.formatear(r.ventasTransferencia()));
        ambos(sb, "TOTAL VENDIDO", Dinero.formatear(r.totalVentas()));
        ambos(sb, "Número de ventas", String.valueOf(r.numeroVentas()));
        ambos(sb, "Canceladas (" + r.numeroCanceladas() + ")", Dinero.formatear(r.totalCancelado()));
        if (notas != null && !notas.isBlank()) {
            separador(sb);
            sb.append("Notas:\n");
            for (String linea : ajustar(notas, ANCHO)) {
                sb.append(linea).append('\n');
            }
        }
        sb.append("\n\n");
        centrar(sb, "______________________");
        centrar(sb, "Firma del cajero");
        return sb.toString();
    }

    public static String surtido(com.cremerias.puntoventa.service.admin.SurtidoService.Detalle d) {
        var r = d.resumen();
        StringBuilder sb = new StringBuilder();
        centrar(sb, NEGOCIO);
        centrar(sb, "ALMACÉN CENTRAL");
        centrar(sb, "SURTIDO A SUCURSAL");
        separador(sb);
        sb.append("Folio:    ").append(r.folio()).append('\n');
        sb.append("Fecha:    ").append(fecha(r.fecha())).append('\n');
        sb.append("Destino:  ").append(r.sucursal()).append('\n');
        sb.append("Pago:     ").append(r.formaPago().nombre()).append('\n');
        sb.append("Surtió:   ").append(r.usuario()).append('\n');
        if (r.ordenFolio() != null) {
            sb.append("Orden:    ").append(r.ordenFolio()).append('\n');
        }
        separador(sb);
        for (var l : d.lineas()) {
            for (String linea : ajustar(l.producto(), ANCHO)) {
                sb.append(linea).append('\n');
            }
            ambos(sb, "  " + com.cremerias.puntoventa.util.Cantidades.formatear(l.unidad(), l.cantidad()),
                    Dinero.formatear(l.costoCentavos()));
            for (var precio : l.precios().entrySet()) {
                sb.append("    Precio ").append(precio.getKey()).append(": ").append(Dinero.formatear(precio.getValue()))
                        .append('\n');
            }
        }
        separador(sb);
        ambos(sb, "TOTAL ENVIADO (COSTO)", Dinero.formatear(r.costoCentavos()));
        ambos(sb, "Valor a precio de venta", Dinero.formatear(r.ventaCentavos()));
        if (r.formaPago() == com.cremerias.puntoventa.service.admin.SurtidoService.FormaPago.CREDITO) {
            ambos(sb, "Saldo de crédito", Dinero.formatear(d.saldoCredito()));
        }
        if (d.notas() != null) {
            separador(sb);
            for (String linea : ajustar("Notas: " + d.notas(), ANCHO)) {
                sb.append(linea).append('\n');
            }
        }
        sb.append("\n\n");
        centrar(sb, "____________      ____________");
        centrar(sb, "  Entregó            Recibió  ");
        return sb.toString();
    }

    public static String abono(com.cremerias.puntoventa.service.admin.CreditoService.Abono a) {
        StringBuilder sb = new StringBuilder();
        centrar(sb, NEGOCIO);
        centrar(sb, "ALMACÉN CENTRAL");
        centrar(sb, "ABONO A CRÉDITO");
        separador(sb);
        sb.append("Folio:    ").append(a.folio()).append('\n');
        sb.append("Fecha:    ").append(fecha(a.fecha())).append('\n');
        sb.append("Sucursal: ").append(a.sucursal().nombre()).append('\n');
        sb.append("Recibió:  ").append(a.usuario()).append('\n');
        separador(sb);
        ambos(sb, "Saldo anterior", Dinero.formatear(a.saldoAnterior()));
        ambos(sb, "ABONO", Dinero.formatear(a.montoCentavos()));
        ambos(sb, "Saldo nuevo", Dinero.formatear(a.saldoNuevo()));
        if (a.nota() != null && !a.nota().isBlank()) {
            separador(sb);
            for (String linea : ajustar("Nota: " + a.nota(), ANCHO)) {
                sb.append(linea).append('\n');
            }
        }
        sb.append("\n\n");
        centrar(sb, "______________________");
        centrar(sb, "Firma");
        return sb.toString();
    }

    private static String fecha(Instant instante) {
        return FECHA.format(instante.atZone(ZoneId.systemDefault()));
    }

    private static void separador(StringBuilder sb) {
        sb.append("-".repeat(ANCHO)).append('\n');
    }

    private static void centrar(StringBuilder sb, String texto) {
        int espacios = Math.max(0, (ANCHO - texto.length()) / 2);
        sb.append(" ".repeat(espacios)).append(texto).append('\n');
    }

    private static void ambos(StringBuilder sb, String izquierda, String derecha) {
        int espacios = ANCHO - izquierda.length() - derecha.length();
        if (espacios < 1) {
            sb.append(izquierda).append('\n').append(" ".repeat(Math.max(0, ANCHO - derecha.length())))
                    .append(derecha).append('\n');
        } else {
            sb.append(izquierda).append(" ".repeat(espacios)).append(derecha).append('\n');
        }
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
}
