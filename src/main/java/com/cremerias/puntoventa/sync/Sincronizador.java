package com.cremerias.puntoventa.sync;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.repository.SyncOutboxRepository;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mantiene la caja sincronizada con Supabase.
 *
 * <p>El sistema siempre escribe primero en la base local. Cada pocos segundos, si hay internet,
 * sube lo encolado en {@code sync_outbox} y baja lo que cambió en la nube (otras cajas, la web o
 * el móvil). Sin internet la caja sigue trabajando igual y se pone al día al volver la conexión.
 */
public class Sincronizador implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Sincronizador.class);
    private static final int INTERVALO_SEGUNDOS = 10;
    private static final int TIMEOUT_MS = 2_500;
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Database database;
    /** Nulo si la caja no tiene conexión con la nube configurada. */
    private final ClienteSupabase nube;
    private final SyncOutboxRepository outbox = new SyncOutboxRepository();
    private final RegistroCaja registro;
    private final Subida subida;
    private final Bajada bajada;
    private final InetSocketAddress destino;

    private final ReadOnlyBooleanWrapper enLinea = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyIntegerWrapper pendientes = new ReadOnlyIntegerWrapper(0);
    private final ReadOnlyObjectWrapper<EstadoNube> estado;
    private final ReadOnlyStringWrapper detalle = new ReadOnlyStringWrapper("");
    private final ScheduledExecutorService ejecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread hilo = new Thread(r, "sincronizador");
        hilo.setDaemon(true);
        return hilo;
    });

    private String ultimoProblema;

    /** @param porConectar sin nube todavía, pero se conecta sola al entrar un administrador */
    public Sincronizador(Database database, String dispositivoId, ClienteSupabase nube, boolean porConectar) {
        this.database = database;
        this.nube = nube;
        this.registro = new RegistroCaja(database, dispositivoId);
        this.subida = new Subida(database);
        this.bajada = new Bajada(database);
        this.estado = new ReadOnlyObjectWrapper<>(nube == null ? EstadoNube.SOLO_LOCAL : EstadoNube.SIN_CONEXION);
        // Se revisa que se alcance Supabase; sin nube configurada, solo si hay internet.
        this.destino = nube == null
                ? new InetSocketAddress("1.1.1.1", 443)
                : InetSocketAddress.createUnresolved(nube.config().url().getHost(), 443);
        if (nube == null) {
            detalle.set(porConectar
                    ? "Esta caja todavía no está conectada a la nube: se conecta cuando entra un administrador"
                            + " de la nube, con internet. Sus datos se cambian por los de la nube."
                    : "Esta caja no tiene configurada la conexión con la nube: todo se guarda solo aquí.");
        }
    }

    public ReadOnlyBooleanProperty enLineaProperty() {
        return enLinea.getReadOnlyProperty();
    }

    public boolean enLinea() {
        return enLinea.get();
    }

    public ReadOnlyIntegerProperty pendientesProperty() {
        return pendientes.getReadOnlyProperty();
    }

    public ReadOnlyObjectProperty<EstadoNube> estadoProperty() {
        return estado.getReadOnlyProperty();
    }

    /** Explicación del estado (última sincronización o el error), para mostrar al pasar el mouse. */
    public ReadOnlyStringProperty detalleProperty() {
        return detalle.getReadOnlyProperty();
    }

    public void iniciar() {
        if (nube != null) {
            int interrumpidos = database.con(outbox::reiniciarEnvios);
            if (interrumpidos > 0) {
                log.info("{} cambios se estaban subiendo cuando se cerró la app: vuelven a la cola", interrumpidos);
            }
        }
        ejecutor.scheduleWithFixedDelay(this::revisar, 0, INTERVALO_SEGUNDOS, TimeUnit.SECONDS);
    }

    /** Fuerza una revisión inmediata (ej. después de guardar algo). */
    public void revisarAhora() {
        ejecutor.execute(this::revisar);
    }

    private void revisar() {
        boolean conectado = hayConexion();
        int porSubir = contarPendientes();
        if (conectado != enLinea.get()) {
            log.info(conectado ? "Conexión a internet restablecida" : "Sin conexión a internet: modo local");
        }
        publicar(conectado, porSubir, null, null);
        if (nube == null) {
            return;
        }
        if (!conectado) {
            publicar(false, porSubir, EstadoNube.SIN_CONEXION,
                    "Sin internet: todo se guarda en la caja y se sube al volver la conexión.");
            return;
        }
        if (porSubir > 0 || estado.get() != EstadoNube.AL_DIA) {
            publicar(true, porSubir, EstadoNube.SINCRONIZANDO, "Subiendo y bajando cambios…");
        }
        try {
            registro.asegurar(nube, nube.config().correo());
            int subidos = subida.subir(nube);
            int bajados = bajada.bajar(nube);
            if (subidos > 0 || bajados > 0) {
                log.info("Sincronizado con la nube: {} cambios subidos, {} registros bajados", subidos, bajados);
            }
            int rechazados = database.con(outbox::contarRechazados);
            String hecho = "Última sincronización: " + LocalTime.now().format(HORA)
                    + (rechazados > 0 ? "\n" + rechazados + " cambios rechazados por la nube (ver el log)" : "");
            ultimoProblema = null;
            publicar(true, contarPendientes(), EstadoNube.AL_DIA, hecho);
        } catch (IOException e) {
            avisar("Se perdió la conexión con la nube: " + e.getMessage(), null);
            publicar(false, contarPendientes(), EstadoNube.SIN_CONEXION,
                    "Se perdió la conexión con la nube; se reintenta en unos segundos.");
        } catch (ErrorNube e) {
            avisar("No se pudo sincronizar con la nube: " + e.getMessage(), null);
            publicar(true, contarPendientes(), EstadoNube.ERROR, e.getMessage());
        } catch (RuntimeException e) {
            avisar("Error al sincronizar con la nube", e);
            publicar(true, contarPendientes(), EstadoNube.ERROR, String.valueOf(e.getMessage()));
        }
    }

    private int contarPendientes() {
        try {
            return database.con(outbox::contarPendientes);
        } catch (RuntimeException e) {
            log.warn("No se pudo leer la cola de sincronización", e);
            return pendientes.get();
        }
    }

    /** Escribe en el log solo cuando el problema cambia (no cada 10 segundos). */
    private void avisar(String problema, RuntimeException causa) {
        if (Objects.equals(problema, ultimoProblema)) {
            return;
        }
        ultimoProblema = problema;
        if (causa != null) {
            log.error(problema, causa);
        } else {
            log.warn(problema);
        }
    }

    private void publicar(boolean conectado, int porSubir, EstadoNube nuevoEstado, String nuevoDetalle) {
        Platform.runLater(() -> {
            enLinea.set(conectado);
            pendientes.set(porSubir);
            if (nuevoEstado != null) {
                estado.set(nuevoEstado);
                detalle.set(nuevoDetalle);
            }
        });
    }

    private boolean hayConexion() {
        try (Socket socket = new Socket()) {
            InetSocketAddress direccion = destino.isUnresolved()
                    ? new InetSocketAddress(destino.getHostString(), destino.getPort())
                    : destino;
            socket.connect(direccion, TIMEOUT_MS);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void close() {
        ejecutor.shutdownNow();
    }
}
