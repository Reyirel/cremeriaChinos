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
import com.cremerias.puntoventa.ui.componentes.Formulario;
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
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
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
        /** Merma al preparar cada venta (derivados como queso rallado); vacío = sin merma. */
        final TextField merma = new TextField();
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
        d.setPrefWidth(960);

        TextField nombre = new TextField(nuevo ? "" : p.nombre());
        nombre.setPromptText("Ej. Queso Oaxaca");
        Formulario.Campo campoNombre = Formulario.campo("Nombre", nombre);
        ComboBox<String> categoria = new ComboBox<>(FXCollections.observableArrayList(
                a.admin().productos().categorias().stream().map(ProductoRepository.Categoria::nombre).toList()));
        categoria.setEditable(true);
        categoria.setPromptText("Elige o escribe una nueva");
        if (!nuevo && p.categoria() != null) {
            categoria.setValue(p.categoria());
        }
        TextField marca = new TextField(nuevo || p.marca() == null ? "" : p.marca());
        marca.setPromptText("Ej. Lala");
        TextField clave = new TextField(nuevo || p.clave() == null ? "" : p.clave());
        clave.setPromptText("Opcional · PLU de báscula");
        TextField codigoInventario = new TextField(nuevo || p.codigoInventario() == null ? "" : p.codigoInventario());
        codigoInventario.setPromptText("Opcional · control interno");
        ComboBox<Unidad> unidad = new ComboBox<>(FXCollections.observableArrayList(Unidad.PZA, Unidad.KG));
        unidad.setConverter(Combos.de(u -> u == Unidad.KG ? "Por kilo (en gramos)" : "Por pieza"));
        unidad.setValue(nuevo ? Unidad.PZA : p.unidad());
        unidad.setDisable(!nuevo); // cambiarla alteraría las existencias ya registradas
        Formulario.Campo campoUnidad = Formulario.campo("Se vende", unidad);
        if (!nuevo) {
            campoUnidad.ayuda("No se puede cambiar: alteraría las existencias registradas.");
        }
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
        Formulario.Campo campoGramaje = Formulario.campo("Gramaje", gramaje);
        Formulario.Campo campoMerma = Formulario.campo("Merma (se resta del gramaje)", cantidadMerma);
        campoMerma.visibleProperty().bind(merma.selectedProperty());
        campoMerma.managedProperty().bind(campoMerma.visibleProperty());
        Label neto = Ui.texto("", "gramaje-neto");
        neto.managedProperty().bind(neto.visibleProperty());
        Runnable actualizarGramaje = () -> {
            boolean porKilo = unidad.getValue() == Unidad.KG;
            campoGramaje.ayuda(porKilo
                    ? "Peso de referencia por kilo vendido (normalmente 1000 g = 1 kg)."
                    : "Lo que pesa una pieza. Sirve para las comisiones por gramaje.");
            BigDecimal g = gramaje.gramos().orElse(null);
            BigDecimal m = merma.isSelected() ? cantidadMerma.gramos().orElse(BigDecimal.ZERO) : BigDecimal.ZERO;
            neto.setText(g == null ? "" : "Gramaje neto: " + Masa.formatear(g.subtract(m).max(BigDecimal.ZERO))
                    + (porKilo ? " por kilo" : " por pieza")
                    + (m.signum() > 0 ? "  (" + Masa.formatear(g) + " − " + Masa.formatear(m) + " de merma)" : ""));
            neto.setVisible(g != null);
        };
        for (var control : List.of(gramaje.campo().textProperty(), cantidadMerma.campo().textProperty())) {
            control.addListener((o, x, y) -> actualizarGramaje.run());
        }
        gramaje.selectorUnidad().valueProperty().addListener((o, x, y) -> actualizarGramaje.run());
        cantidadMerma.selectorUnidad().valueProperty().addListener((o, x, y) -> actualizarGramaje.run());
        merma.selectedProperty().addListener((o, x, y) -> actualizarGramaje.run());
        VBox seccionGramaje = Formulario.seccionSeparada("Gramaje", null, merma,
                Formulario.fila(campoGramaje, campoMerma), neto);

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
        VBox bloquePlazo = new VBox(10, Formulario.fila(Formulario.campo("Sin venderse en", campoPlazo),
                Formulario.campo("Avisar a", destinatarios)),
                Ui.texto("Cada sucursal lo mide con sus propias ventas, desde la última vez que se vendió ahí. "
                        + "El aviso se quita solo en cuanto se vende.", "campo-ayuda"));
        bloquePlazo.visibleProperty().bind(avisar.selectedProperty());
        bloquePlazo.managedProperty().bind(bloquePlazo.visibleProperty());
        Label sinAvisoPropio = Ui.texto("Sin aviso propio: se usa el plazo en días de la sucursal y solo lo ve el "
                + "administrador (en Avisos).", "campo-ayuda");
        sinAvisoPropio.visibleProperty().bind(avisar.selectedProperty().not());
        sinAvisoPropio.managedProperty().bind(sinAvisoPropio.visibleProperty());
        VBox seccionAviso = Formulario.seccionSeparada("Aviso si no se vende", null, avisar, bloquePlazo, sinAvisoPropio);

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
        Formulario.Campo campoRepetir = Formulario.campo("Cada", campoRepetirTemporada);
        campoRepetir.visibleProperty().bind(repetirTemporada.selectedProperty());
        campoRepetir.managedProperty().bind(campoRepetir.visibleProperty());
        VBox seccionTemporada = Formulario.seccionSeparada("Temporada",
                "Al terminar la temporada el producto se deshabilita solo (sin borrarse). Si se repite, el sistema "
                        + "lo vuelve a habilitar en el siguiente ciclo; si no, tú eliges cuándo y con qué fechas.",
                Formulario.fila(Formulario.campo("Desde", desdeTemporada), Formulario.campo("Hasta", hastaTemporada)),
                repetirTemporada, campoRepetir);
        seccionTemporada.visibleProperty().bind(Bindings.equal(disponibilidad.valueProperty(), Disponibilidad.TEMPORADA));
        seccionTemporada.managedProperty().bind(seccionTemporada.visibleProperty());

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
                d.marcarCambios();
                redibujar[0].run();
            });
            filas.add(f);
            redibujar[0].run();
        };
        redibujar[0] = () -> {
            grid.getChildren().clear();
            String[] titulos = {"Principal", "Nombre", "Se vende", "Contenido", "", "Merma %", "Código de barras", "Activa",
                    ""};
            for (int i = 0; i < titulos.length; i++) {
                Label l = new Label(titulos[i]);
                l.getStyleClass().add("grid-surtido-encabezado");
                l.setMinWidth(Region.USE_PREF_SIZE);
                grid.add(l, i, 0);
            }
            int r = 1;
            for (Fila f : filas) {
                grid.addRow(r++, f.principal, f.nombre, f.tipo, f.contenido, f.unidadContenido, f.merma, f.codigo, f.activa,
                        f.quitar);
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
                f.merma.setText(pr.mermaPct().signum() == 0 ? "" : pr.mermaPct().stripTrailingZeros().toPlainString());
                f.principal.setSelected(pr.principal());
                f.activa.setSelected(pr.activo());
                f.quitar.setOnAction(e -> {
                    filas.remove(f);
                    eliminadas.add(f.id);
                    if (f.principal.isSelected() && !filas.isEmpty()) {
                        filas.getFirst().principal.setSelected(true);
                    }
                    d.marcarCambios();
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
        Button masPresentacion = Ui.boton("Agregar presentación", "mdi2p-plus", "small");
        masPresentacion.setOnAction(e -> {
            agregar.run();
            d.marcarCambios();
        });
        VBox seccionPresentaciones = Formulario.seccionSeparada("Presentaciones",
                "«Contenido» es cuánto trae cada presentación en la unidad del producto: piezas (ej. Caja = 12) o "
                        + "gramos (ej. Pieza de queso = 400). A granel siempre es 1000 g = 1 kg. «Merma %» es solo para "
                        + "lo que se prepara al venderse (ej. queso rallado): al vender se descuenta además ese porcentaje "
                        + "del inventario y queda registrado como merma.",
                grid, masPresentacion);

        VBox generales = Formulario.seccion(null, null,
                Formulario.fila(campoNombre, Formulario.campo("Categoría", categoria), Formulario.opcional("Marca", marca)));
        VBox venta = Formulario.seccionSeparada("Venta y control", null,
                Formulario.fila(campoUnidad, Formulario.campo("Clave / PLU", clave),
                        Formulario.campo("Código de inventario", codigoInventario),
                        Formulario.campo("Disponibilidad", disponibilidad)));
        d.setContenido(generales, venta, seccionPresentaciones, seccionGramaje, seccionTemporada, seccionAviso);
        nombre.textProperty().addListener((o, x, y) -> campoNombre.setError(null));

        Runnable guardar = () -> {
            if (nombre.getText() == null || nombre.getText().isBlank()) {
                campoNombre.setError("Escribe el nombre del producto.");
                nombre.requestFocus();
                return;
            }
            List<Presentacion> lista = new ArrayList<>();
            for (Fila f : filas) {
                boolean granel = "A granel".equals(f.tipo.getValue());
                BigDecimal factor = granel ? BigDecimal.ONE
                        : Cantidades.desdeCaptura(unidad.getValue(), f.contenido.getText()).orElse(null);
                lista.add(new Presentacion(f.id, nuevo ? null : p.id(), f.nombre.getText(), granel, factor,
                        f.codigo.getText(), f.principal.isSelected(), f.activa.isSelected(), porcentaje(f.merma.getText())));
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
                    categoria.getEditor().getText(), marca.getText(), clave.getText(), codigoInventario.getText(),
                    unidad.getValue(), merma.isSelected(), disponibilidad.getValue(), temporada, lista, List.copyOf(eliminadas),
                    gramaje.gramos().orElse(null), merma.isSelected() ? cantidadMerma.gramos().orElse(null) : null, aviso);
            a.guardar(d, (nuevo ? "Producto creado: " : "Producto actualizado: ") + nombre.getText().strip(),
                    () -> a.admin().productos().guardar(datos, a.usuario()), alGuardar);
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton(nuevo ? "Crear producto" : "Guardar cambios", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setFocoInicial(nombre);
        d.vigilarCambios(nombre, categoria, marca, clave, codigoInventario, unidad, disponibilidad, merma, gramaje,
                cantidadMerma, avisar, plazo, unidadPlazo, avisarAdmin, avisarSupervisor, avisarCaja, desdeTemporada,
                hastaTemporada, repetirTemporada, plazoTemporada, unidadTemporada, grid);
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

    /** Porcentaje capturado; vacío o inválido = 0 (el servicio valida el rango). */
    private static BigDecimal porcentaje(String texto) {
        if (texto == null || texto.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(texto.strip());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static Fila nuevaFila(ToggleGroup principales, Unidad unidad) {
        Fila f = new Fila();
        f.principal.setToggleGroup(principales);
        f.principal.setAccessibleText("Presentación principal");
        f.nombre.setPromptText("Ej. Caja 12 pzas");
        f.nombre.setPrefColumnCount(12);
        f.contenido.setPrefColumnCount(6);
        f.contenido.setAlignment(Pos.CENTER_RIGHT);
        Campos.soloNumeros(f.contenido, 0);
        f.codigo.setPromptText("Opcional");
        f.merma.setPromptText("0");
        f.merma.setPrefColumnCount(4);
        f.merma.setAlignment(Pos.CENTER_RIGHT);
        f.merma.setAccessibleText("Merma al preparar, en porcentaje");
        f.merma.setTooltip(new Tooltip("Solo para lo que se prepara al venderse (ej. rallado). Déjalo vacío si no aplica."));
        Campos.soloNumeros(f.merma, 2);
        f.tipo.setPrefWidth(140);
        f.tipo.setMinWidth(140);
        f.unidadContenido.setMinWidth(Region.USE_PREF_SIZE);
        f.unidadContenido.getStyleClass().add("unidad-campo");
        f.codigo.setPrefColumnCount(13);
        f.activa.setSelected(true);
        f.activa.setAccessibleText("Presentación activa");
        f.quitar.getStyleClass().addAll("flat", "boton-icono", "danger");
        f.quitar.setAccessibleText("Quitar presentación");
        f.quitar.setTooltip(new Tooltip("Quitar presentación"));
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
