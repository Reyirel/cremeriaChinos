package com.cremerias.puntoventa.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/** Lo que la sincronización necesita de Supabase (se reemplaza por uno falso en las pruebas). */
public interface Nube {

    /** Llama una función de la base ({@code /rest/v1/rpc/...}). */
    JsonNode rpc(String funcion, ObjectNode parametros) throws IOException, ErrorNube;

    /** Consulta una tabla; {@code consulta} es lo que va después de {@code /rest/v1/} (tabla y filtros). */
    ArrayNode consultar(String consulta) throws IOException, ErrorNube;
}
