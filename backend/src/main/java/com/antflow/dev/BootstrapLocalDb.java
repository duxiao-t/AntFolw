package com.antflow.dev;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * One-shot bootstrap for local (non-docker) PostgreSQL deployments.
 *
 * Connects to the `postgres` system DB as the configured user and ensures
 * the target database (`antflow` by default) exists. Idempotent.
 *
 * Usage:
 *   mvn -q exec:java -Dexec.mainClass=com.antflow.dev.BootstrapLocalDb \
 *       -Dexec.classpathScope=runtime \
 *       -Dexec.args="jdbc:postgresql://localhost:5432/postgres postgres Tao@1234 antflow"
 *
 * After this prints "OK", Flyway takes over via `mvn spring-boot:run`.
 */
public class BootstrapLocalDb {

    public static void main(String[] args) throws Exception {
        String adminUrl = args.length > 0
            ? args[0] : "jdbc:postgresql://localhost:5432/postgres";
        String user     = args.length > 1 ? args[1] : "postgres";
        String password = args.length > 2 ? args[2] : "Tao@1234";
        String targetDb = args.length > 3 ? args[3] : "antflow";

        Class.forName("org.postgresql.Driver");

        try (Connection c = DriverManager.getConnection(adminUrl, user, password)) {
            boolean exists;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM pg_database WHERE datname = ?")) {
                ps.setString(1, targetDb);
                try (ResultSet rs = ps.executeQuery()) {
                    exists = rs.next();
                }
            }
            if (exists) {
                System.out.println("OK — database `" + targetDb + "` already exists.");
            } else {
                // CREATE DATABASE cannot run inside a transaction block.
                c.setAutoCommit(true);
                try (PreparedStatement ps = c.prepareStatement(
                        "CREATE DATABASE " + targetDb)) {
                    ps.executeUpdate();
                }
                System.out.println("OK — created database `" + targetDb + "`.");
            }
        }

        // Install extensions in the target database. The connecting user
        // must have superuser-level grants (the default `postgres` does).
        String targetUrl = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + targetDb;
        try (Connection c = DriverManager.getConnection(targetUrl, user, password)) {
            for (String ext : new String[]{"ltree", "pgcrypto"}) {
                try (PreparedStatement ps = c.prepareStatement(
                        "CREATE EXTENSION IF NOT EXISTS \"" + ext + "\"")) {
                    ps.executeUpdate();
                    System.out.println("OK — extension `" + ext + "` ensured.");
                }
            }
        }

        System.out.println("Bootstrap complete. Next: mvn spring-boot:run");
    }
}