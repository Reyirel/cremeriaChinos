package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.security.PasswordHasher;

import java.time.Clock;

/** Servicios del panel del administrador (almacén central). */
public class Administracion {

    private final SucursalService sucursales;
    private final UsuarioService usuarios;
    private final ProductoAdminService productos;
    private final AlmacenService almacen;
    private final SurtidoService surtidos;
    private final OrdenService ordenes;
    private final LimitesService limites;
    private final CreditoService credito;
    private final ComisionService comisiones;
    private final AlertaService alertas;
    private final BitacoraService bitacora;

    public Administracion(Database database, PasswordHasher hasher, Clock reloj, String dispositivoId) {
        this.sucursales = new SucursalService(database, dispositivoId);
        String almacenId = sucursales.almacen().map(s -> s.id()).orElse(null);
        this.usuarios = new UsuarioService(database, hasher, dispositivoId);
        this.productos = new ProductoAdminService(database, reloj, dispositivoId);
        this.almacen = new AlmacenService(database, dispositivoId, almacenId);
        this.surtidos = new SurtidoService(database, dispositivoId, almacen);
        this.ordenes = new OrdenService(database, dispositivoId);
        this.limites = new LimitesService(database, dispositivoId);
        this.credito = new CreditoService(database, dispositivoId);
        this.comisiones = new ComisionService(database, dispositivoId);
        this.alertas = new AlertaService(database, reloj, dispositivoId);
        this.bitacora = new BitacoraService(database);
    }

    public SucursalService sucursales() {
        return sucursales;
    }

    public UsuarioService usuarios() {
        return usuarios;
    }

    public ProductoAdminService productos() {
        return productos;
    }

    public AlmacenService almacen() {
        return almacen;
    }

    public SurtidoService surtidos() {
        return surtidos;
    }

    public OrdenService ordenes() {
        return ordenes;
    }

    public LimitesService limites() {
        return limites;
    }

    public CreditoService credito() {
        return credito;
    }

    public ComisionService comisiones() {
        return comisiones;
    }

    public AlertaService alertas() {
        return alertas;
    }

    public BitacoraService bitacora() {
        return bitacora;
    }
}
