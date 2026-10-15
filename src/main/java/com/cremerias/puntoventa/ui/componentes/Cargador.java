package com.cremerias.puntoventa.ui.componentes;

import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Ejecuta consultas y guardados fuera del hilo de la interfaz y entrega el resultado en él.
 *
 * <p>Cada pantalla usa su propio {@code Cargador}: si se pide una carga nueva antes de que
 * termine la anterior (por ejemplo, al cambiar un filtro), el resultado viejo se descarta y nunca
 * pisa al nuevo. Cada consulta abre su propia conexión a SQLite (modo WAL), así que no estorba a la
 * caja ni a la sincronización.
 */
public final class Cargador {

    private static final Logger log = LoggerFactory.getLogger(Cargador.class);
    private static final ExecutorService HILOS = Executors.newFixedThreadPool(2, r -> {
        Thread hilo = new Thread(r, "carga-ui");
        hilo.setDaemon(true);
        return hilo;
    });

    private int generacion;

    /** Carga en segundo plano; solo entrega el resultado de la petición más reciente. */
    public <T> void cargar(Callable<T> trabajo, Consumer<T> alTerminar, Consumer<Throwable> alFallar) {
        int esta = ++generacion;
        ejecutar(trabajo, r -> {
            if (esta == generacion) {
                alTerminar.accept(r);
            }
        }, e -> {
            if (esta == generacion) {
                alFallar.accept(e);
            }
        });
    }

    /** Olvida las cargas en curso (la pantalla se cerró o cambió de contexto). */
    public void cancelar() {
        generacion++;
    }

    /** Trabajo suelto en segundo plano (guardados): siempre entrega su resultado. */
    public static <T> void ejecutar(Callable<T> trabajo, Consumer<T> alTerminar, Consumer<Throwable> alFallar) {
        Task<T> tarea = new Task<>() {
            @Override
            protected T call() throws Exception {
                return trabajo.call();
            }
        };
        tarea.setOnSucceeded(e -> alTerminar.accept(tarea.getValue()));
        tarea.setOnFailed(e -> {
            Throwable error = tarea.getException();
            if (!(error instanceof IllegalArgumentException || error instanceof IllegalStateException)) {
                log.warn("Falló una operación en segundo plano", error);
            }
            alFallar.accept(error);
        });
        HILOS.submit(tarea);
    }
}
