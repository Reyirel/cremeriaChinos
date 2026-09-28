package com.cremerias.puntoventa.db;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface SqlFunction<T> {

    T aplicar(Connection conexion) throws SQLException;
}
