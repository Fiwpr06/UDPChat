module com.udpchat {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires com.google.gson;

    opens com.udpchat.client.controller to javafx.fxml;
    opens com.udpchat.server.controller to javafx.fxml;
    opens com.udpchat.shared.model to com.google.gson;

    exports com.udpchat.client;
    exports com.udpchat.server;
    exports com.udpchat.shared.model;
    exports com.udpchat.shared.protocol;
    exports com.udpchat.shared.util;
}