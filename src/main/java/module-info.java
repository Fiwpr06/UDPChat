module com.udpchat {
    requires javafx.controls;
    requires javafx.fxml;

    opens com.udpchat.client.controller to javafx.fxml;
    opens com.udpchat.server.controller to javafx.fxml;

    exports com.udpchat.client;
    exports com.udpchat.server;
}