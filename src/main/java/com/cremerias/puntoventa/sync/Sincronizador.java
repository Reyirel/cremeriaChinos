package com.cremerias.puntoventa.sync;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.repository.SyncOutboxRepository;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Vigila la conexión a internet y los cambios locales pendientes de subir.
 *
 * <p>El sistema siempre escribe primero en la base local; cuando se conecte Supabase,
 * aquí se enviarán los registros de {@code sync_outbox} en cuanto haya conexión.
 */
public class Sincronizador implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Sincronizador.class);
    private static final int INTERVALO_SEGUNDOS = 10;
    private static final int TIMEOUT_MS = 2_500;

    private final Database database;
    private final SyncOutboxRepository outbox = new SyncOutboxRepository();
    // Por ahora se verifica contra un DNS público; con Supabase será el host del proyecto.
    private final InetSocketAddress destino = new InetSocketAddress("1.1.1.1", 443);

    private final ReadOnlyBooleanWrapper enLinea = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyIntegerWrapper pendientes = new ReadOnlyIntegerWrapper(0);
    private final ScheduledExecutorService ejecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread hilo = new Thread(r, "sincronizador");
        hilo.setDaemon(true);
        return hilo;
    });

    public Sincronizador(Database database) {
        this.database = database;
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

    public void iniciar() {
        ejecutor.scheduleWithFixedDelay(this::revisar, 0, INTERVALO_SEGUNDOS, TimeUnit.SECONDS);
    }

    /** Fuerza una revisión inmediata (ej. después de guardar algo). */
    public void revisarAhora() {
        ejecutor.execute(this::revisar);
    }

    private void revisar() {
        boolean conectado = hayConexion();
        int porSubir;
        try {
            porSubir = database.con(outbox::contarPendientes);
        } catch (RuntimeException e) {
            log.warn("No se pudo leer la cola de sincronización", e);
            porSubir = pendientes.get();
        }
        if (conectado != enLinea.get()) {
            log.info(conectado ? "Conexión a internet restablecida" : "Sin conexión a internet: modo local");
        }
        int total = porSubir;
        Platform.runLater(() -> {
            enLinea.set(conectado);
            pendientes.set(total);
        });
        // TODO(Supabase): si conectado, enviar los pendientes de sync_outbox y marcar enviado_en.
    }

    private boolean hayConexion() {
        try (Socket socket = new Socket()) {
            socket.connect(destino, TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void close() {
        ejecutor.shutdownNow();
    }
}
