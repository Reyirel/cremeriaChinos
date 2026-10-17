package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.model.Disponibilidad;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.service.admin.CompraService;
import com.cremerias.puntoventa.service.admin.ProductoAdminService;
import com.cremerias.puntoventa.util.Ids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ajustes de inventario (motivos, signos, permisos, documentos) y transformaciones con trazabilidad. */
class AjustesYTransformacionesTest extends EscenarioBase {

    private AjusteService ajustes;
    private TransformacionService transformaciones;

    @BeforeEach
    void servicios() {
        ajustes = new AjusteService(db, dispositivo);
        transformaciones = new TransformacionService(db, dispositivo);
    }

    private String ajustar(Sucursal donde, String producto, AjusteService.Motivo motivo, String cantidad, String documento,
                           String nota) {
        return ajustes.registrar(Ids.nuevo(), donde, catalogoAdmin(producto), motivo, new BigDecimal(cantidad), null,
                documento, nota, administrador);
    }

    // ------------------------------------------------------------------ ajustes

    @Test
    void unFaltanteSaleDelLoteMasAntiguoConSuCosto() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        LoteRepository.Lote masViejo = admin.almacen().lotes(queso.id(), almacen.id()).getFirst();
        String folio = ajustar(almacen, "Queso Oaxaca", AjusteService.Motivo.FALTANTE, "-2", null, "Conteo físico");

        assertTrue(folio.startsWith("AJU-"));
        mismo("48", existencia(queso.id(), almacen.id()));
        assertEquals(masViejo.id(), consultar("SELECT lote_id FROM movimientos_inventario WHERE tipo = 'AJUSTE'"));
        assertEquals(-2 * 13300, numero("SELECT costo_centavos FROM ajustes_inventario"));
    }

    @Test
    void unSobranteEntraAlLoteMasRecienteYEnSucursalAUnoConPrecio() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        ajustar(almacen, "Queso Oaxaca", AjusteService.Motivo.SOBRANTE, "1.5", null, null);
        mismo("51.5", existencia(queso.id(), almacen.id()));

        BigDecimal antes = existencia(queso.id(), matriz.id());
        ajustes.registrar(Ids.nuevo(), matriz, queso, AjusteService.Motivo.SOBRANTE, new BigDecimal("0.250"), null, null,
                null, supervisor);
        mismo(antes.add(new BigDecimal("0.250")).toPlainString(), existencia(queso.id(), matriz.id()));
        String lote = consultar("SELECT lote_id FROM movimientos_inventario WHERE tipo = 'AJUSTE' AND sucursal_id = '"
                + matriz.id() + "'");
        assertTrue(numero("SELECT COUNT(*) FROM lote_precios WHERE lote_id = '" + lote + "'") > 0);
    }

    @Test
    void cadaMotivoRespetaSuSignoYSusDatosObligatorios() {
        assertThrows(IllegalArgumentException.class,
                () -> ajustar(almacen, "Queso Oaxaca", AjusteService.Motivo.FALTANTE, "1", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> ajustar(almacen, "Queso Oaxaca", AjusteService.Motivo.SOBRANTE, "-1", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> ajustar(almacen, "Queso Oaxaca", AjusteService.Motivo.CORRECCION, "-1", null, " "));
        assertThrows(IllegalArgumentException.class,
                () -> ajustar(almacen, "Queso Oaxaca", AjusteService.Motivo.DEVOLUCION, "1", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> ajustar(almacen, "Refresco de cola 600 ml", AjusteService.Motivo.CADUCIDAD, "-1.5", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> ajustar(almacen, "Queso Oaxaca", AjusteService.Motivo.FALTANTE, "0", null, null));
        // No se saca más de lo que hay.
        assertThrows(IllegalStateException.class,
                () -> ajustar(almacen, "Queso Oaxaca", AjusteService.Motivo.DANO, "-51", null, null));
        assertEquals(0, numero("SELECT COUNT(*) FROM ajustes_inventario"));
    }

    @Test
    void elSupervisorSoloAjustaSuSucursalYElCajeroNoAjusta() {
        ProductoCatalogo leche = catalogoAdmin("Leche entera 1 L");
        ajustes.registrar(Ids.nuevo(), matriz, leche, AjusteService.Motivo.DANO, new BigDecimal("-1"), null, null, null,
                supervisor);
        assertThrows(IllegalStateException.class, () -> ajustes.registrar(Ids.nuevo(), almacen, leche,
                AjusteService.Motivo.DANO, new BigDecimal("-1"), null, null, null, supervisor));
        admin.sucursales().guardar(null, "SUR", "Cremería Sur", null, null, administrador);
        Sucursal sur = admin.sucursales().listar().stream().filter(s -> s.codigo().equals("SUR")).findFirst().orElseThrow();
        assertThrows(IllegalStateException.class, () -> ajustes.registrar(Ids.nuevo(), sur, leche,
                AjusteService.Motivo.DANO, new BigDecimal("-1"), null, null, null, supervisor));
        assertThrows(IllegalStateException.class, () -> ajustes.registrar(Ids.nuevo(), matriz, leche,
                AjusteService.Motivo.DANO, new BigDecimal("-1"), null, null, null, cajero.usuario()));
        assertEquals(1, numero("SELECT COUNT(*) FROM ajustes_inventario"));
    }

    @Test
    void unAjusteNoDuplicaUnaCompraNiSeAplicaDosVecesElMismoDocumento() {
        admin.compras().guardarProveedor(null, "Proveedor", null, null, null, null, administrador);
        CompraService.Proveedor proveedor = admin.compras().proveedores().getFirst();
        CompraService.Detalle compra = admin.compras().registrar(Ids.nuevo(), proveedor, null, LocalDate.now(),
                List.of(new CompraService.Linea(catalogoAdmin("Queso Oaxaca"), null, BigDecimal.TEN, BigDecimal.ZERO,
                        13500)), null, administrador);
        assertThrows(IllegalArgumentException.class, () -> ajustar(almacen, "Queso Oaxaca",
                AjusteService.Motivo.DEVOLUCION, "-1", compra.resumen().folio(), null));

        ajustar(almacen, "Queso Oaxaca", AjusteService.Motivo.CORTESIA, "0.5", "REM-77", "Regalo del proveedor");
        assertThrows(IllegalArgumentException.class, () -> ajustar(almacen, "Queso Oaxaca",
                AjusteService.Motivo.CORTESIA, "0.5", "rem-77", "Otra vez"));
    }

    @Test
    void repetirElMismoAjusteNoLoAplicaDosVeces() {
        String id = Ids.nuevo();
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        String folio = ajustes.registrar(id, almacen, queso, AjusteService.Motivo.FALTANTE, new BigDecimal("-1"), null,
                null, null, administrador);
        assertEquals(folio, ajustes.registrar(id, almacen, queso, AjusteService.Motivo.FALTANTE, new BigDecimal("-1"),
                null, null, null, administrador));
        mismo("49", existencia(queso.id(), almacen.id()));
        assertEquals(1, ajustes.listar(almacen.id(), LocalDate.now(), LocalDate.now(), administrador).size());
    }

    // ------------------------------------------------------------------ transformaciones

    private ProductoCatalogo paquete() {
        admin.productos().guardar(new ProductoAdminService.Datos(null, "Queso Oaxaca paquete 500 g", "Quesos", null, null,
                null, Unidad.PZA, false, Disponibilidad.REGULAR, null,
                List.of(new Presentacion(null, null, "Paquete", false, BigDecimal.ONE, "7509990000500", true, true)),
                List.of(), new BigDecimal("500"), BigDecimal.ZERO, null), administrador);
        return catalogoAdmin("Queso Oaxaca paquete 500 g");
    }

    @Test
    void cincoKilosDeQuesoSeVuelvenDiezPaquetesConElCostoDelOrigen() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        ProductoCatalogo paquete = paquete();
        String id = Ids.nuevo();
        String folio = transformaciones.registrar(id, almacen, queso, new BigDecimal("5"), paquete, BigDecimal.TEN,
                new BigDecimal("0.5"), null, null, administrador);

        assertTrue(folio.startsWith("TRA-"));
        mismo("45", existencia(queso.id(), almacen.id()));
        mismo("10", existencia(paquete.id(), almacen.id()));
        LoteRepository.Lote nuevo = admin.almacen().lotes(paquete.id(), almacen.id()).getFirst();
        assertEquals(6650, nuevo.costoUnitarioCentavos()); // 5 kg × $133 ÷ 10
        var t = transformaciones.listar(almacen.id(), LocalDate.now(), LocalDate.now(), administrador).getFirst();
        mismo("0", t.merma());
        assertEquals(66500, t.costoCentavos());
        // Trazabilidad: el lote nuevo sabe de qué lote de queso salió.
        assertEquals(List.of(admin.almacen().lotes(queso.id(), almacen.id()).getFirst().id()),
                transformaciones.lotesDeOrigen(id));
        // Repetir la misma operación no consume otra vez el queso.
        assertEquals(folio, transformaciones.registrar(id, almacen, queso, new BigDecimal("5"), paquete, BigDecimal.TEN,
                new BigDecimal("0.5"), null, null, administrador));
        mismo("45", existencia(queso.id(), almacen.id()));
    }

    @Test
    void laMermaRealQuedaRegistradaYNoSeProduceMasDeLoQueAlcanza() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        ProductoCatalogo paquete = paquete();
        transformaciones.registrar(Ids.nuevo(), almacen, queso, new BigDecimal("5.2"), paquete, BigDecimal.TEN,
                new BigDecimal("0.5"), null, null, administrador);
        var t = transformaciones.listar(almacen.id(), LocalDate.now(), LocalDate.now(), administrador).getFirst();
        mismo("0.2", t.merma());
        assertEquals(6916, t.costoUnitarioDestino()); // 5.2 × $133 ÷ 10 = $69.16: la merma va en el costo

        assertThrows(IllegalArgumentException.class, () -> transformaciones.registrar(Ids.nuevo(), almacen, queso,
                new BigDecimal("4"), paquete, BigDecimal.TEN, new BigDecimal("0.5"), null, null, administrador));
        assertThrows(IllegalArgumentException.class, () -> transformaciones.registrar(Ids.nuevo(), almacen, queso,
                new BigDecimal("4"), queso, BigDecimal.TEN, new BigDecimal("0.1"), null, null, administrador));
    }

    @Test
    void enSucursalElLoteNuevoLlevaPrecioYSeVendeEnLaCaja() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        ProductoCatalogo paquete = paquete();
        String presentacion = paquete.principal().id();
        assertThrows(IllegalArgumentException.class, () -> transformaciones.registrar(Ids.nuevo(), matriz, queso,
                new BigDecimal("2"), paquete, new BigDecimal("4"), new BigDecimal("0.5"), Map.of(), null, supervisor));

        transformaciones.registrar(Ids.nuevo(), matriz, queso, new BigDecimal("2"), paquete, new BigDecimal("4"),
                new BigDecimal("0.5"), Map.of(presentacion, 11000L), null, supervisor);
        mismo("23", existencia(queso.id(), matriz.id()));

        Turno turno = caja.abrir(cajero, 0);
        var ticket = vender(turno, articulo("Queso Oaxaca paquete 500 g"), "2");
        assertEquals(22000, ticket.totalCentavos());
        mismo("2", existencia(paquete.id(), matriz.id()));
    }
}
