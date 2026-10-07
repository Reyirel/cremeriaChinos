module com.cremerias.puntoventa {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.sql;
    requires java.net.http;

    requires atlantafx.base;
    requires org.kordamp.ikonli.core;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.materialdesign2;
    requires org.kordamp.ikonli.feather;

    requires org.xerial.sqlitejdbc;
    requires bcrypt;
    requires org.slf4j;
    requires com.fasterxml.jackson.databind;

    opens com.cremerias.puntoventa.ui to javafx.fxml;
    opens com.cremerias.puntoventa.ui.roles to javafx.fxml;
    opens com.cremerias.puntoventa.ui.caja to javafx.fxml;
    opens com.cremerias.puntoventa.ui.supervisor to javafx.fxml;
    opens com.cremerias.puntoventa.ui.admin to javafx.fxml;

    exports com.cremerias.puntoventa;
}
