package com.cremerias.puntoventa.ui.admin;

import atlantafx.base.controls.ToggleSwitch;
import com.cremerias.puntoventa.model.AvisoSinVenta;
import com.cremerias.puntoventa.model.Disponibilidad;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Temporada;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.repository.ProductoRepository;
import com.cremerias.puntoventa.service.admin.ProductoAdminService;
import com.cremerias.puntoventa.ui.componentes.CampoMasa;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Masa;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Alta y edición de un producto con sus presentaciones. */
final class DialogoProducto {

    /** Renglón editable de una presentación. */
    private static final class Fila {
        String id;
        final TextField nombre = new TextField();
        final ComboBox<String> tipo = new ComboBox<>();
        final TextField contenido = new TextField();
        final Label unidadContenido = new Label();
        final TextField codigo = new TextField();
        final RadioButton principal = new RadioButton();
        final CheckBox activa = new CheckBox();
        final Button quitar = new Button(null, new FontIcon("mdi2t-trash-can-outline"));
    }

    private DialogoProducto() {
    }

    static void mostrar(AdminContexto a, ProductoCatalogo p, Runnable alGuardar) {
        boolean nuevo = p == null;
        Dialogo d = new Dialogo(nuevo ? "Nuevo producto" : "Editar producto",
                nuevo ? "Captura sus datos y las presentaciones en que se vende." : p.nombre(),
                "mdi2p-package-variant-closed", Dialogo.Tono.ACENTO);
        d.setPrefWidth(900);

        TextField nombre = new TextField(nuevo ? "" : p.nombre());
        nombre.setPromptText("Ej. Queso Oaxaca");
        ComboBox<String> categoria = new ComboBox<>(FXCollections.observableArrayList(
                a.admin().productos().categorias().stream().map(ProductoRepository.Categoria::nombre).toList()));
        categoria.setEditable(true);
        categoria.setPromptText("Elige o escribe una nueva");
        if (!nuevo && p.categoria() != null) {
            categoria.setValue(p.categoria());
        }
        TextField clave = new TextField(nuevo || p.clave() == null ? "" : p.clave());
        clave.setPromptText("PLU de báscula (opcional)");
        TextField codigoInventario = new TextField(nuevo || p.codigoInventario() == null ? "" : p.codigoInventario());
        codigoInventario.setPromptText("Control interno del almacén (opcional)");
        ComboBox<Unidad> unidad = new ComboBox<>(FXCollections.observableArrayList(Unidad.PZA, Unidad.KG));
        unidad.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Unidad u) {
                return u == null ? "" : u == Unidad.KG ? "Por kilo (se maneja en gramos)" : "Por pieza";
            }

            @Override
            public Unidad fromString(String s) {
                return null;
            }
        });
        unidad.setValue(nuevo ? Unidad.PZA : p.unidad());
        unidad.setDisable(!nuevo); // cambiarla alteraría las existencias ya registradas
        ComboBox<Disponibilidad> disponibilidad = new ComboBox<>(FXCollections.observableArrayList(Disponibilidad.values()));
        disponibilidad.setValue(nuevo ? Disponibilidad.REGULAR : p.disponibilidad());
        ToggleSwitch merma = new ToggleSwitch("Sujeto a merma (ej. jamón, queso)");
        merma.setSelected(!nuevo && p.sujetoMerma());

        // Gramaje (todos los productos) y merma (se resta del gramaje)
        CampoMasa gramaje = new CampoMasa();
        CampoMasa cantidadMerma = new CampoMasa();
        if (!nuevo) {
            gramaje.setGramos(p.gramajeGramos());
            if (p.sujetoMerma() && p.mermaGramos().signum() > 0) {
                cantidadMerma.setGramos(p.mermaGramos());
            }
        }
        Label ayudaGramaje = Ui.texto("", "texto-ayuda");
        Label neto = Ui.texto("", "gramaje-neto");
        VBox bloqueMerma = new VBox(6, Ui.campo("Merma (se resta del gramaje)", cantidadMerma));
        bloqueMerma.visibleProperty().bind(merma.selectedProperty());
        bloqueMerma.managedProperty().bind(bloqueMerma.visibleProperty());
        Runnable actualizarGramaje = () -> {
            boolean porKilo = unidad.getValue() == Unidad.KG;
            ayudaGramaje.setText(porKilo
                    ? "Peso de referencia por kilo vendido (normalmente 1000 g = 1 kg); ajústalo si lo necesitas. "
                    + "La merma se resta de este valor."
                    : "Lo que pesa una pieza. Todos los productos lo llevan (sirve para comisiones por gramaje).");
            BigDecimal g = gramaje.gramos().orElse(null);
            BigDecimal m = merma.isSelected() ? cantidadMerma.gramos().orElse(BigDecimal.ZERO) : BigDecimal.ZERO;
            neto.setText(g == null ? "" : "Gramaje neto: " + Masa.formatear(g.subtract(m).max(BigDecimal.ZERO))
                    + (porKilo ? " por kilo" : " por pieza")
                    + (m.signum() > 0 ? "  (" + Masa.formatear(g) + " − " + Masa.formatear(m) + " de merma)" : ""));
        };
        for (var control : List.of(gramaje.campo().textProperty(), cantidadMerma.campo().textProperty())) {
            control.addListener((o, x, y) -> actualizarGramaje.run());
        }
        gramaje.selectorUnidad().valueProperty().addListener((o, x, y) -> actualizarGramaje.run());
        cantidadMerma.selectorUnidad().valueProperty().addListener((o, x, y) -> actualizarGramaje.run());
        merma.selectedProperty().addListener((o, x, y) -> actualizarGramaje.run());
        VBox bloqueGramaje = Ui.tarjeta("Gramaje", merma, Ui.fila(Ui.campo("Gramaje", gramaje), bloqueMerma),
                ayudaGramaje, neto);

        // Aviso opcional si el producto no se vende en cierto tiempo (para ofertarlo)
        AvisoSinVenta avisoActual = nuevo ? null : p.avisoSinVenta();
        ToggleSwitch avisar = new ToggleSwitch("Avisar si no se vende en cierto tiempo (para ofertarlo)");
        avisar.setSelected(avisoActual != null);
        TextField plazo = new TextField(avisoActual == null ? "" : String.valueOf(avisoActual.plazo()));
        Campos.soloNumeros(plazo, 0);
        plazo.setAlignment(Pos.CENTER_RIGHT);
        plazo.setPromptText("Ej. 30");
        plazo.setPrefColumnCount(5);
        ComboBox<AvisoSinVenta.Unidad> unidadPlazo = new ComboBox<>(
                FXCollections.observableArrayList(AvisoSinVenta.Unidad.values()));
        unidadPlazo.setValue(avisoActual == null ? AvisoSinVenta.Unidad.MINUTOS : avisoActual.unidad());
        unidadPlazo.setPrefWidth(130);
        CheckBox avisarAdmin = new CheckBox("Administrador");
        CheckBox avisarSupervisor = new CheckBox("Supervisor");
        CheckBox avisarCaja = new CheckBox("Caja");
        avisarAdmin.setSelected(avisoActual != null && avisoActual.administrador());
        avisarSupervisor.setSelected(avisoActual == null || avisoActual.supervisor());
        avisarCaja.setSelected(avisoActual == null || avisoActual.caja());
        HBox campoPlazo = new HBox(8, plazo, unidadPlazo);
        campoPlazo.setAlignment(Pos.CENTER_LEFT);
        HBox destinatarios = new HBox(18, avisarAdmin, avisarSupervisor, avisarCaja);
        destinatarios.setAlignment(Pos.CENTER_LEFT);
        destinatarios.setMinHeight(36);
        VBox bloquePlazo = new VBox(10, Ui.fila(Ui.campo("Sin venderse en", campoPlazo), Ui.campo("Avisar a", destinatarios)),
                Ui.texto("Cada sucursal lo mide con sus propias ventas, desde la última vez que se vendió ahí. "
                        + "El aviso se quita solo en cuanto se vende.", "texto-ayuda"));
        bloquePlazo.visibleProperty().bind(avisar.selectedProperty());
        bloquePlazo.managedProperty().bind(bloquePlazo.visibleProperty());
        Label sinAvisoPropio = Ui.texto("Sin aviso propio: se usa el plazo en días de la sucursal y solo lo ve el "
                + "administrador (Avisos).", "texto-ayuda");
        sinAvisoPropio.visibleProperty().bind(avisar.selectedProperty().not());
        sinAvisoPropio.managedProperty().bind(sinAvisoPropio.visibleProperty());
        VBox bloqueAviso = Ui.tarjeta("Aviso si no se vende", avisar, bloquePlazo, sinAvisoPropio);

        // Temporada: ventana de fechas en que se vende y, opcional, cada cuánto se repite
        Temporada temporadaActual = nuevo ? null : p.temporada();
        DatePicker desdeTemporada = new DatePicker();
        DatePicker hastaTemporada = new DatePicker();
        desdeTemporada.setPromptText("Empieza");
        hastaTemporada.setPromptText("Termina");
        ToggleSwitch repetirTemporada = new ToggleSwitch("Se repite automáticamente");
        TextField plazoTemporada = new TextField();
        Campos.soloNumeros(plazoTemporada, 0);
        plazoTemporada.setAlignment(Pos.CENTER_RIGHT);
        plazoTemporada.setPromptText("Ej. 1");
        plazoTemporada.setPrefColumnCount(5);
        ComboBox<Temporada.Unidad> unidadTemporada = new ComboBox<>(
                FXCollections.observableArrayList(Temporada.Unidad.values()));
        unidadTemporada.setValue(Temporada.Unidad.ANIOS);
        unidadTemporada.setPrefWidth(130);
        if (temporadaActual != null) {
            desdeTemporada.setValue(temporadaActual.desde());
            hastaTemporada.setValue(temporadaActual.fin());
            if (temporadaActual.repite()) {
                repetirTemporada.setSelected(true);
                plazoTemporada.setText(String.valueOf(temporadaActual.repetirCada()));
                unidadTemporada.setValue(temporadaActual.repetirUnidad());
            }
        }
        HBox campoRepetirTemporada = new HBox(8, plazoTemporada, unidadTemporada);
        campoRepetirTemporada.setAlignment(Pos.CENTER_LEFT);
        campoRepetirTemporada.visibleProperty().bind(repetirTemporada.selectedProperty());
        campoRepetirTemporada.managedProperty().bind(campoRepetirTemporada.visibleProperty());
        VBox bloqueTemporada = Ui.tarjeta("Temporada",
                Ui.fila(Ui.campo("Desde", desdeTemporada), Ui.campo("Hasta", hastaTemporada)),
                repetirTemporada, Ui.campo("Se repite", campoRepetirTemporada),
                Ui.texto("Al terminar la temporada el producto se deshabilita solo (sin borrarse). Si se repite, "
                        + "el sistema lo vuelve a habilitar solo en el siguiente ciclo; si no, tú eliges cuándo y "
                        + "con qué fechas.", "texto-ayuda"));
        bloqueTemporada.visibleProperty().bind(Bindings.equal(disponibilidad.valueProperty(), Disponibilidad.TEMPORADA));
        bloqueTemporada.managedProperty().bind(bloqueTemporada.visibleProperty());

        // Presentaciones
        ToggleGroup principales = new ToggleGroup();
        List<Fila> filas = new ArrayList<>();
        List<String> eliminadas = new ArrayList<>();
        GridPane grid = new GridPane(10, 8);
        grid.getStyleClass().add("grid-presentaciones");
        Runnable[] redibujar = new Runnable[1];
        Runnable agregar = () -> {
            Fila f = nuevaFila(principales, unidad.getValue());
            if (filas.isEmpty()) {
                f.principal.setSelected(true);
                f.nombre.setText(unidad.getValue() == Unidad.KG ? "Kilo" : "Pieza");
                f.tipo.setValue(unidad.getValue() == Unidad.KG ? "A granel" : "Por unidad");
                f.contenido.setText(unidad.getValue() == Unidad.KG ? "1000" : "1");
            }
            f.quitar.setOnAction(e -> {
                filas.remove(f);
                if (f.id != null) {
                    eliminadas.add(f.id);
                }
                if (f.principal.isSelected() && !filas.isEmpty()) {
                    filas.getFirst().principal.setSelected(true);
                }
                redibujar[0].run();
            });
            filas.add(f);
            redibujar[0].run();
        };
        redibujar[0] = () -> {
            grid.getChildren().clear();
            String[] titulos = {"Principal", "Nombre", "Se vende", "Contenido", "", "Código de barras", "Activa", ""};
            for (int i = 0; i < titulos.length; i++) {
                Label l = new Label(titulos[i]);
                l.getStyleClass().add("campo-etiqueta");
                l.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                grid.add(l, i, 0);
            }
            int r = 1;
            for (Fila f : filas) {
                grid.addRow(r++, f.principal, f.nombre, f.tipo, f.contenido, f.unidadContenido, f.codigo, f.activa, f.quitar);
            }
        };
        if (nuevo) {
            agregar.run();
        } else {
            for (Presentacion pr : p.presentaciones()) {
                Fila f = nuevaFila(principales, p.unidad());
                f.id = pr.id();
                f.nombre.setText(pr.nombre());
                f.tipo.setValue(pr.granel() ? "A granel" : "Por unidad");
                f.contenido.setText(Cantidades.aCaptura(p.unidad(), pr.factor()));
                f.codigo.setText(pr.codigoBarras() == null ? "" : pr.codigoBarras());
                f.principal.setSelected(pr.principal());
                f.activa.setSelected(pr.activo());
                f.quitar.setOnAction(e -> {
                    filas.remove(f);
                    eliminadas.add(f.id);
                    if (f.principal.isSelected() && !filas.isEmpty()) {
                        filas.getFirst().principal.setSelected(true);
                    }
                    redibujar[0].run();
                });
                filas.add(f);
            }
            redibujar[0].run();
        }
        unidad.valueProperty().addListener((o, x, u) -> {
            for (Fila f : filas) {
                configurarTipo(f, u);
                // La presentación que se puso sola cambia con la unidad; si no, quedaría una
                // "Pieza" de 1 g (al pasar a kilo) o un "Kilo" de 1000 piezas (al pasar a pieza).
                if ((x == Unidad.KG ? "Kilo" : "Pieza").equals(f.nombre.getText().strip())) {
                    f.nombre.setText(u == Unidad.KG ? "Kilo" : "Pieza");
                    f.tipo.setValue(u == Unidad.KG ? "A granel" : "Por unidad");
                    f.contenido.setText(u == Unidad.KG ? "1000" : "1");
                }
            }
            gramaje.setGramos(u == Unidad.KG ? new BigDecimal("1000") : null);
            actualizarGramaje.run();
        });
        actualizarGramaje.run();
        Button masPresentacion = Ui.secundario("Agregar presentación", "mdi2p-plus");
        masPresentacion.setOnAction(e -> agregar.run());

        Label ayuda = Ui.texto("«Contenido» es cuánto trae cada presentación en la unidad del producto: piezas "
                + "(ej. Caja = 12) o gramos (ej. Pieza de queso = 400). A granel siempre es 1000 g = 1 kg.", "texto-ayuda");
        VBox presentaciones = Ui.tarjeta("Presentaciones", grid, masPresentacion, ayuda);

        VBox formulario = new VBox(16,
                Ui.fila(Ui.campo("Nombre", nombre), Ui.campo("Categoría", categoria)),
                Ui.fila(Ui.campo("Se maneja", unidad), Ui.campo("Clave / PLU", clave),
                        Ui.campo("Código de inventario", codigoInventario), Ui.campo("Disponibilidad", disponibilidad)),
                bloqueGramaje, bloqueTemporada, bloqueAviso, presentaciones);
        // El formulario es alto: en pantallas chicas se desplaza en lugar de salirse de la ventana.
        ScrollPane desplazable = new ScrollPane(formulario);
        desplazable.setFitToWidth(true);
        desplazable.getStyleClass().addAll("edge-to-edge", "scroll-surtido");
        desplazable.sceneProperty().addListener((o, x, escena) -> {
            if (escena != null) {
                desplazable.maxHeightProperty().bind(escena.heightProperty().subtract(230));
            }
        });
        d.setContenido(desplazable);

        Runnable guardar = () -> {
            List<Presentacion> lista = new ArrayList<>();
            for (Fila f : filas) {
                boolean granel = "A granel".equals(f.tipo.getValue());
                BigDecimal factor = granel ? BigDecimal.ONE
                        : Cantidades.desdeCaptura(unidad.getValue(), f.contenido.getText()).orElse(null);
                lista.add(new Presentacion(f.id, nuevo ? null : p.id(), f.nombre.getText(), granel, factor,
                        f.codigo.getText(), f.principal.isSelected(), f.activa.isSelected()));
            }
            AvisoSinVenta aviso = avisar.isSelected() ? new AvisoSinVenta(entero(plazo.getText()),
                    unidadPlazo.getValue(), avisarAdmin.isSelected(), avisarSupervisor.isSelected(),
                    avisarCaja.isSelected()) : null;
            Temporada temporada = disponibilidad.getValue() == Disponibilidad.TEMPORADA
                    ? new Temporada(desdeTemporada.getValue(), hastaTemporada.getValue(),
                            repetirTemporada.isSelected() ? entero(plazoTemporada.getText()) : null,
                            repetirTemporada.isSelected() ? unidadTemporada.getValue() : null)
                    : null;
            var datos = new ProductoAdminService.Datos(nuevo ? null : p.id(), nombre.getText(),
                    categoria.getEditor().getText(), clave.getText(), codigoInventario.getText(), unidad.getValue(),
                    merma.isSelected(), disponibilidad.getValue(), temporada, lista, eliminadas,
                    gramaje.gramos().orElse(null), merma.isSelected() ? cantidadMerma.gramos().orElse(null) : null, aviso);
            if (a.ejecutar(nuevo ? "Producto creado" : "Producto actualizado",
                    () -> a.admin().productos().guardar(datos, a.usuario()))) {
                d.cerrar();
                alGuardar.run();
            }
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton("Guardar producto", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setFocoInicial(nombre);
        a.dialogos().mostrar(d);
    }

    /** Número entero capturado; 0 si está vacío y el máximo si es demasiado grande. */
    private static int entero(String texto) {
        if (texto == null || texto.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(texto.strip());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static Fila nuevaFila(ToggleGroup principales, Unidad unidad) {
        Fila f = new Fila();
        f.principal.setToggleGroup(principales);
        f.nombre.setPromptText("Ej. Caja 12 pzas");
        f.nombre.setPrefColumnCount(12);
        f.contenido.setPrefColumnCount(6);
        f.contenido.setAlignment(Pos.CENTER_RIGHT);
        Campos.soloNumeros(f.contenido, 0);
        f.codigo.setPromptText("Opcional");
        f.tipo.setPrefWidth(150);
        f.tipo.setMinWidth(150);
        f.unidadContenido.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        f.codigo.setPrefColumnCount(13);
        f.activa.setSelected(true);
        f.quitar.getStyleClass().addAll("button-icon", "flat", "danger");
        f.tipo.valueProperty().addListener((o, x, t) -> {
            boolean granel = "A granel".equals(t);
            f.contenido.setDisable(granel);
            if (granel) {
                f.contenido.setText("1000");
            }
        });
        configurarTipo(f, unidad);
        return f;
    }

    private static void configurarTipo(Fila f, Unidad unidad) {
        if (unidad == Unidad.KG) {
            f.tipo.getItems().setAll("A granel", "Por unidad");
            f.unidadContenido.setText("g");
        } else {
            f.tipo.getItems().setAll("Por unidad");
            f.tipo.setValue("Por unidad");
            f.unidadContenido.setText("pzas");
        }
        if (f.tipo.getValue() == null) {
            f.tipo.setValue(unidad == Unidad.KG ? "A granel" : "Por unidad");
        }
    }
}
