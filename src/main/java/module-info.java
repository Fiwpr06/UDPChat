module com.udpchat {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires com.google.gson;
    requires jakarta.mail;
    requires java.sql;
    requires java.net.http;
    requires jdk.httpserver;
    requires com.zaxxer.hikari;
    requires org.mariadb.jdbc;

    opens com.udpchat.client.controller to javafx.fxml;
    opens com.udpchat.server.controller to javafx.fxml;
    opens com.udpchat.common.model to com.google.gson, javafx.base;

    exports com.udpchat.client;
    exports com.udpchat.server;
    exports com.udpchat.common.model;
    exports com.udpchat.common.util;
    exports com.udpchat.database;
    exports com.udpchat.server.smtp;
    exports com.udpchat.server.pop3;
    exports com.udpchat.server.service;
    exports com.udpchat.client.smtp;
    exports com.udpchat.client.pop3;
    exports com.udpchat.client.service;
}
