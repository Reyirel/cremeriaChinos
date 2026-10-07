package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.MetodoPago;
import com.cremerias.puntoventa.model.Pago;
import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Tarifa;
import com.cremerias.puntoventa.model.Ticket;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.model.VentaResumen;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.ContadorRepository;
import com.cremerias.puntoventa.repository.InventarioRepository;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.repository.VentaRepository;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Registra y cancela ventas. Todo se guarda de forma atómica en la base local.
 * Cada renglón se surte de los lotes más antiguos primero y se cobra al precio de cada lote.
 * No se vende más de lo que hay en la sucursal.
 */
public class VentaService {

    private static final Logger log = LoggerFactory.getLogger(VentaService.class);

    /** Lo que se va a cobrar: presentación y cantidad de cada renglón. */
    public record Renglon(Producto producto, BigDecimal cantidad) {
    }

    /** Producto del que se pide más de lo que hay en la sucursal (cantidades en unidad base). */
    public record Faltante(String productoId, String nombre, Unidad unidadBase, BigDecimal existencia,
                           BigDecimal pedida) {

        public String mensaje() {
            return existencia.signum() <= 0
                    ? nombre + " ya no tiene existencia"
                    : "De " + nombre + " solo hay " + cantidad(existencia) + " y se piden " + cantidad(pedida);
        }

        private String cantidad(BigDecimal base) {
            return unidadBase.formatear(base) + (unidadBase == Unidad.PZA ? " pzas" : "");
        }
    }

    /** La venta pide más de lo que hay de uno o más productos. */
    public static class ExistenciaInsuficienteException extends IllegalStateException {

        private final List<Faltante> faltantes;

        public ExistenciaInsuficienteException(List<Faltante> faltantes) {
            super("No hay existencia suficiente:\n" + String.join("\n",
                    faltantes.stream().map(f -> "• " + f.mensaje() + ".").toList()));
            this.faltantes = List.copyOf(faltantes);
        }

        public List<Faltante> faltantes() {
            return faltantes;
        }
    }

    private final Database database;
    private final String dispositivoId;
    private final VentaRepository ventas = new VentaRepository();
    private final InventarioRepository inventario = new InventarioRepository();
    private final LoteRepository lotes = new LoteRepository();
    private final ContadorRepository contadores = new ContadorRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();
    private final ReabastecimientoAutomatico reabastecimiento = new ReabastecimientoAutomatico();

    public VentaService(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    /** Cambio a entregar al cliente, o lanza excepción si los pagos no alcanzan. */
    public static long calcularCambio(long total, List<Pago> pagos) {
        long recibido = 0;
        long efectivo = 0;
        for (Pago pago : pagos) {
            if (pago.recibidoCentavos() < 0) {
                throw new IllegalArgumentException("Los montos no pueden ser negativos.");
            }
            recibido += pago.recibidoCentavos();
            if (pago.metodo() == MetodoPago.EFECTIVO) {
                efectivo += pago.recibidoCentavos();
            }
        }
        if (recibido < total) {
            throw new IllegalArgumentException("Faltan " + Dinero.formatear(total - recibido) + " por cubrir.");
        }
        long cambio = recibido - total;
        if (cambio > efectivo) {
            throw new IllegalArgumentException("Con tarjeta o transferencia no se puede cobrar más del total.");
        }
        return cambio;
    }

    /**
     * Productos de los que la venta pide más de lo que hay; vacío si alcanza todo. Suma las
     * presentaciones de un mismo producto (una caja de 12 y 3 piezas piden 15 piezas).
     *
     * @param existenciaDe existencia en la sucursal, en unidad base, del producto de una presentación
     */
    public static List<Faltante> faltantes(List<Renglon> renglones, Function<Producto, BigDecimal> existenciaDe) {
        Map<String, BigDecimal> pedida = new LinkedHashMap<>();
        Map<String, Producto> presentacion = new HashMap<>();
        for (Renglon r : renglones) {
            Producto p = r.producto();
            pedida.merge(p.productoId(), p.unidad().normalizar(r.cantidad()).multiply(p.factor()), BigDecimal::add);
            presentacion.putIfAbsent(p.productoId(), p);
        }
        List<Faltante> faltan = new ArrayList<>();
        pedida.forEach((productoId, base) -> {
            Producto p = presentacion.get(productoId);
            BigDecimal hay = existenciaDe.apply(p).max(BigDecimal.ZERO);
            if (base.compareTo(hay) > 0) {
                faltan.add(new Faltante(productoId, p.nombre(), p.unidadBase(), hay, base));
            }
        });
        return faltan;
    }

    public Ticket registrar(List<Renglon> renglones, List<Pago> pagos, Sesion sesion, Turno turno) {
        if (renglones.isEmpty()) {
            throw new IllegalArgumentException("La venta no tiene productos.");
        }
        for (Renglon r : renglones) {
            if (r.cantidad().signum() <= 0) {
                throw new IllegalArgumentException("Cantidad inválida para " + r.producto().nombre());
            }
        }
        Usuario usuario = sesion.usuario();
        String sucursalId = turno.sucursalId();
        String ventaId = Ids.nuevo();

        long total = database.enTransaccion(c -> {
            // Se vuelven a leer los lotes: el precio y la existencia salen de la base, no de la pantalla.
            Map<String, Map<String, Long>> precios = lotes.preciosDeSucursal(c, sucursalId);
            Map<String, List<LoteRepository.Lote>> lotesPorProducto = new HashMap<>();
            List<Faltante> faltan = faltantes(renglones, p -> lotesPorProducto
                    .computeIfAbsent(p.productoId(), id -> consultarLotes(c, id, sucursalId)).stream()
                    .map(LoteRepository.Lote::existencia)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            if (!faltan.isEmpty()) {
                throw new ExistenciaInsuficienteException(faltan);
            }
            Map<String, BigDecimal> consumido = new HashMap<>();
            List<Tarifa.Cotizacion> cotizaciones = new ArrayList<>();
            long suma = 0;
            BigDecimal articulos = BigDecimal.ZERO;
            for (Renglon r : renglones) {
                Producto p = r.producto();
                List<LoteRepository.Lote> lotesProducto = lotesPorProducto.computeIfAbsent(p.productoId(),
                        id -> consultarLotes(c, id, sucursalId));
                PreciosLote.Tramos tramos = PreciosLote.de(lotesProducto, precios, p.id());
                if (tramos == null) {
                    throw new IllegalStateException(p.nombre() + " no tiene precio en esta sucursal.");
                }
                Tarifa.Cotizacion cot = Tarifa.cotizar(tramos.tramos(), tramos.extra(), p.factor(), consumido,
                        p.unidad().normalizar(r.cantidad()));
                cotizaciones.add(cot);
                suma += cot.totalCentavos();
                articulos = articulos.add(p.unidad().esGranel() ? BigDecimal.ONE : r.cantidad());
            }

            long cambio = calcularCambio(suma, pagos);
            long pagado = pagos.stream().mapToLong(Pago::recibidoCentavos).sum();
            String folio = siguienteFolio(c, sucursalId, sesion.dispositivoId());
            ventas.insertar(c, new VentaRepository.NuevaVenta(ventaId, folio, turno.id(), sucursalId,
                    sesion.dispositivoId(), usuario.id(), articulos, suma, pagado, cambio));

            Set<String> productos = new LinkedHashSet<>();
            for (int i = 0; i < renglones.size(); i++) {
                Producto p = renglones.get(i).producto();
                BigDecimal cantidad = p.unidad().normalizar(renglones.get(i).cantidad());
                Tarifa.Cotizacion cot = cotizaciones.get(i);
                long precioUnitario = BigDecimal.valueOf(cot.totalCentavos())
                        .divide(cantidad, 0, RoundingMode.HALF_UP).longValue();
                ventas.insertarDetalle(c, ventaId, i + 1, p.productoId(), p.id(), p.nombre(), p.unidad(), cantidad,
                        cantidad.multiply(p.factor()).setScale(3, RoundingMode.HALF_UP), precioUnitario,
                        cot.totalCentavos());
                for (Tarifa.Parte parte : cot.partes()) {
                    inventario.registrar(c, p.productoId(), sucursalId, parte.loteId(), "VENTA",
                            parte.cantidadBase().negate(), ventaId, usuario.id(), null);
                }
                productos.add(p.productoId());
            }

            long cambioPendiente = cambio;
            for (Pago pago : pagos) {
                if (pago.recibidoCentavos() == 0) {
                    continue;
                }
                long monto = pago.recibidoCentavos();
                if (pago.metodo() == MetodoPago.EFECTIVO && cambioPendiente > 0) {
                    long descuento = Math.min(cambioPendiente, monto);
                    monto -= descuento;
                    cambioPendiente -= descuento;
                }
                ventas.insertarPago(c, ventaId, pago.metodo(), monto, pago.recibidoCentavos(), pago.referencia());
            }

            for (String productoId : productos) {
                reabastecimiento.revisar(c, sucursalId, productoId, usuario, sesion.dispositivoId());
            }
            return suma;
        });
        log.info("Venta registrada {} por {}", ventaId, Dinero.formatear(total));
        return ticket(ventaId);
    }

    private List<LoteRepository.Lote> consultarLotes(Connection c, String productoId, String sucursalId) {
        try {
            return lotes.deProducto(c, productoId, sucursalId);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Ticket ticket(String ventaId) {
        return database.con(c -> ventas.ticket(c, ventaId))
                .orElseThrow(() -> new IllegalArgumentException("No existe la venta " + ventaId));
    }

    public List<VentaResumen> delTurno(String turnoId) {
        return database.con(c -> ventas.delTurno(c, turnoId));
    }

    /** Cancela una venta del turno abierto y regresa los productos a los mismos lotes. */
    public void cancelar(String ventaId, Turno turno, Usuario usuario, Usuario autorizador, String motivo) {
        database.enTransaccion(c -> {
            VentaRepository.DatosCancelacion datos = ventas.datosCancelacion(c, ventaId)
                    .orElseThrow(() -> new IllegalArgumentException("No existe la venta."));
            if (datos.cancelada()) {
                throw new IllegalStateException("La venta ya estaba cancelada.");
            }
            if (!datos.turnoId().equals(turno.id())) {
                throw new IllegalStateException("Solo se pueden cancelar ventas del turno actual.");
            }
            ventas.marcarCancelada(c, ventaId, usuario.id(), autorizador.id(), motivo);
            for (VentaRepository.SalidaLote s : ventas.salidasDeVenta(c, ventaId)) {
                inventario.registrar(c, s.productoId(), datos.sucursalId(), s.loteId(), "CANCELACION_VENTA",
                        s.cantidad(), ventaId, usuario.id(), motivo);
            }
            String folioVenta = ventas.ticket(c, ventaId).map(Ticket::folio).orElse(ventaId);
            bitacora.registrar(c, AccionBitacora.VENTA_CANCELADA, usuario, dispositivoId, "ventas", ventaId,
                    "Venta " + folioVenta + " cancelada (autorizó " + autorizador.nombreCompleto() + "): " + motivo);
            return null;
        });
        log.info("Venta {} cancelada por {} (autorizó {})", ventaId, usuario.usuario(), autorizador.usuario());
    }

    /** Folio único aunque haya varias terminales sin conexión: SUCURSAL-TERMINAL-consecutivo. */
    private String siguienteFolio(Connection c, String sucursalId, String dispositivo) throws SQLException {
        long consecutivo = contadores.siguiente(c, "venta:" + dispositivo);
        String sucursal = "CAJA";
        try (PreparedStatement ps = c.prepareStatement("SELECT codigo FROM sucursales WHERE id = ?")) {
            ps.setString(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sucursal = rs.getString(1);
                }
            }
        }
        String terminal = dispositivo.replace("-", "").substring(0, 4).toUpperCase(Locale.ROOT);
        return "%s-%s-%06d".formatted(sucursal, terminal, consecutivo);
    }
}
