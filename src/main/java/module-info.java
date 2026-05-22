module com.example.demo {
    requires javafx.controls;
    requires javafx.fxml;
    requires transitive java.sql;

    // ── Model (shared between client & server) ───────────────────────────
    exports com.example.demo.model;

    // ── Network protocol (shared between client & server) ────────────────
    exports com.example.demo.net;

    // ── Database layer ───────────────────────────────────────────────────
    exports com.example.demo.db;

    // ── Server ───────────────────────────────────────────────────────────
    exports com.example.demo.server;

    // ── Client (JavaFX UI) ───────────────────────────────────────────────
    opens com.example.demo.client to javafx.fxml;
    exports com.example.demo.client;
}