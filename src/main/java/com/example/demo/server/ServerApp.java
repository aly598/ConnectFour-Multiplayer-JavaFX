package com.example.demo.server;

import com.example.demo.net.Protocol;

import java.nio.file.Path;
import com.example.demo.db.Database;
import java.sql.SQLException;

/**
 * CLI entry point: starts the Connect Four server.
 * Args: [--port N] [--variant 4|5] [--turn-seconds N]
 * [--db-host H] [--db-port P] [--db-name N] [--db-user U] [--db-pass P]
 *
 * Environment fallbacks:
 * C4_DB_HOST, C4_DB_PORT, C4_DB_NAME, C4_DB_USER, C4_DB_PASS
 */
public final class ServerApp {
    public static void main(String[] args) throws Exception {
        int port = intFromEnv("C4_PORT", Protocol.DEFAULT_PORT);
        int variant = intFromEnv("C4_VARIANT", 4);
        int turnSeconds = intFromEnv("C4_TURN_SECONDS", 20);

        String dbHost = strFromEnv("C4_DB_HOST", "localhost");
        int dbPort = intFromEnv("C4_DB_PORT", 3306);
        String dbName = strFromEnv("C4_DB_NAME", "connect4");
        String dbUser = strFromEnv("C4_DB_USER", "root");
        String dbPass = strFromEnv("C4_DB_PASS", "12345678A@");

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--"))
                continue;
            if (i + 1 >= args.length)
                throw new IllegalArgumentException("Missing value for " + arg);
            String value = args[++i];
            switch (arg) {
                case "--port" -> port = Integer.parseInt(value);
                case "--variant" -> variant = Integer.parseInt(value);
                case "--turn-seconds" -> turnSeconds = Integer.parseInt(value);
                case "--db-host" -> dbHost = value;
                case "--db-port" -> dbPort = Integer.parseInt(value);
                case "--db-name" -> dbName = value;
                case "--db-user" -> dbUser = value;
                case "--db-pass" -> dbPass = value;
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }

        if (variant != 4 && variant != 5)
            throw new IllegalArgumentException("variant must be 4 or 5");
        if (turnSeconds < 5)
            throw new IllegalArgumentException("turn-seconds must be at least 5");

        new Server(port, variant, turnSeconds,
                dbHost, dbPort, dbName, dbUser, dbPass,
                Path.of("clips")).run();
    }

    private static String strFromEnv(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static int intFromEnv(String name, int fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank())
            return fallback;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
