package com.cremerias.puntoventa.ui.componentes;

import atlantafx.base.controls.ModalPane;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Muestra diálogos sobre la ventana principal; se pueden apilar (ej. autorización sobre un listado).
 * Mientras haya uno abierto, el teclado solo llega al diálogo de hasta arriba.
 */
public class Dialogos {

    private record Abierto(ModalPane modal, Dialogo dialogo) {
    }

    private final StackPane raiz;
    private final List<Abierto> abiertos = new ArrayList<>();

    public Dialogos(StackPane raiz) {
        this.raiz = raiz;
        raiz.addEventFilter(KeyEvent.ANY, this::redirigirTeclado);
    }

    public void mostrar(Dialogo dialogo) {
        ModalPane modal = new ModalPane();
        modal.setPersistent(true);
        modal.getStyleClass().add("modal-app");
        raiz.getChildren().add(modal);
        // El skin del ModalPane debe existir antes de show(); si no, el diálogo no se muestra.
        modal.applyCss();
        Abierto abierto = new Abierto(modal, dialogo);
        abiertos.add(abierto);
        dialogo.setCerrador(() -> cerrar(abierto));
        modal.show(dialogo);
        Platform.runLater(dialogo::enfocar);
    }

    private void cerrar(Abierto abierto) {
        if (!abiertos.remove(abierto)) {
            return;
        }
        abierto.modal().hide(true);
        PauseTransition espera = new PauseTransition(Duration.millis(350));
        espera.setOnFinished(e -> raiz.getChildren().remove(abierto.modal()));
        espera.play();
        if (!abiertos.isEmpty()) {
            Dialogo anterior = abiertos.getLast().dialogo();
            Platform.runLater(anterior::enfocar);
        }
    }

    /** Si el foco quedó fuera del diálogo activo, las teclas se mandan a él y no a la pantalla de atrás. */
    private void redirigirTeclado(KeyEvent e) {
        if (abiertos.isEmpty() || raiz.getScene() == null) {
            return;
        }
        Dialogo activo = abiertos.getLast().dialogo();
        Node foco = raiz.getScene().getFocusOwner();
        if (foco != null && activo.isAncestor(foco)) {
            return;
        }
        e.consume();
        if (e.getEventType() == KeyEvent.KEY_PRESSED) {
            activo.teclas(e);
        }
        activo.enfocar();
    }

    public boolean hayAbierto() {
        return !abiertos.isEmpty();
    }

    /** Olvida los diálogos abiertos (al cambiar de pantalla se retiran de la vista). */
    public void limpiar() {
        abiertos.clear();
    }
}
