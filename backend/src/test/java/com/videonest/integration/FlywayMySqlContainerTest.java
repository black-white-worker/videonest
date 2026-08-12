package com.videonest.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMySqlContainerTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("videonest_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void migrationsCreateCurrentSchemaFromEmptyDatabase() throws Exception {
        cleanDatabase();

        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();

        assertEquals(7, flyway.migrate().migrationsExecuted);

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        )) {
            try (ResultSet tables = connection.getMetaData().getTables(
                    MYSQL.getDatabaseName(), null, "outbox_event", new String[]{"TABLE"}
            )) {
                assertTrue(tables.next());
            }
            try (ResultSet columns = connection.getMetaData().getColumns(
                    MYSQL.getDatabaseName(), null, "video_comment", "root_id"
            )) {
                assertTrue(columns.next());
            }
        }
    }

    @Test
    void migrationsUpgradeExistingVersionThreeSchemaWithoutHistoryTable() throws Exception {
        cleanDatabase();

        Flyway versionThreeFlyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("3")
                .load();
        assertEquals(3, versionThreeFlyway.migrate().migrationsExecuted);

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE flyway_schema_history");
        }

        Flyway legacyUpgradeFlyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("3")
                .load();

        assertEquals(4, legacyUpgradeFlyway.migrate().migrationsExecuted);

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        )) {
            try (ResultSet columns = connection.getMetaData().getColumns(
                    MYSQL.getDatabaseName(), null, "video", "cover_list_url"
            )) {
                assertTrue(columns.next());
            }
            try (ResultSet tables = connection.getMetaData().getTables(
                    MYSQL.getDatabaseName(), null, "outbox_event", new String[]{"TABLE"}
            )) {
                assertTrue(tables.next());
            }
        }
    }

    private void cleanDatabase() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
    }
}
