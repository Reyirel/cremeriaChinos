-- =====================================================================
-- V11: Ventana de fechas de un producto "de temporada" y su repetición
--
--   * temporada_desde / temporada_fin: ventana en la que el producto se
--     vende (ej. del 1 dic al 6 ene). Nulo si el producto no es de
--     temporada.
--   * temporada_repetir_cada + temporada_repetir_unidad: si la temporada
--     se repite sola (ej. cada 1 AÑOS). Nulo = no se repite; hay que
--     volver a capturar la ventana a mano cuando se vuelva a habilitar.
--   * El sistema revisa las temporadas (al iniciar y cada 30 s en el
--     panel de administración) y deshabilita/habilita el producto solo,
--     sin borrarlo.
-- =====================================================================

ALTER TABLE productos ADD COLUMN temporada_desde TEXT;
ALTER TABLE productos ADD COLUMN temporada_fin TEXT;
ALTER TABLE productos ADD COLUMN temporada_repetir_cada INTEGER
    CHECK (temporada_repetir_cada IS NULL OR temporada_repetir_cada > 0);
ALTER TABLE productos ADD COLUMN temporada_repetir_unidad TEXT
    CHECK (temporada_repetir_unidad IS NULL OR temporada_repetir_unidad IN ('DIAS', 'SEMANAS', 'MESES', 'ANIOS'));
