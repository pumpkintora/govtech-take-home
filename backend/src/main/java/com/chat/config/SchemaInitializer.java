package com.chat.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * Initializes the PostgreSQL schema at startup under a transaction-independent PostgreSQL
 * advisory lock.
 *
 * WHY: When multiple backend replicas (backend-1, backend-2) boot simultaneously via Docker
 * Compose, concurrent `CREATE TABLE IF NOT EXISTS` statements in PostgreSQL can still collide
 * on the internal `pg_type_typname_nsp_index` catalog constraint and crash one replica. Acquiring
 * a shared advisory lock serializes DDL initialization cleanly across all instances without
 * requiring an external migration job for the MVP. In production, this would be replaced by
 * versioned Flyway/Liquibase migrations executed as a pre-deploy step.
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);
    private static final long SCHEMA_LOCK_KEY = 7382910421L;

    private final DataSource dataSource;
    private final AppProperties properties;

    public SchemaInitializer(DataSource dataSource, AppProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement lock = conn.prepareStatement("SELECT pg_advisory_lock(?)")) {
                lock.setLong(1, SCHEMA_LOCK_KEY);
                lock.execute();
            }
            try {
                ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
                populator.populate(conn);

                // Clean up any stale connection rows left behind by a previous hard crash (SIGKILL/OOM)
                // of this exact instance ID so online presence doesn't show ghost connections.
                try (PreparedStatement cleanup = conn.prepareStatement(
                        "DELETE FROM active_connections WHERE node_id = ?")) {
                    cleanup.setString(1, properties.getInstanceId());
                    cleanup.executeUpdate();
                }
                log.info("Database schema verified on node={}", properties.getInstanceId());
            } finally {
                try (PreparedStatement unlock = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    unlock.setLong(1, SCHEMA_LOCK_KEY);
                    unlock.execute();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize database schema", e);
        }
    }
}
