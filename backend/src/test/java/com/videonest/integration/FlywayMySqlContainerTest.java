package com.videonest.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMySqlContainerTest {

    /** 老库升级基准：迁移到 V3 后删除历史表，再按 V3 基线继续升级 */
    private static final String LEGACY_BASELINE_VERSION = "3";

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

        // 空库全量迁移：执行全部 V*.sql，数量由 countMigrationScripts() 自动统计，新增迁移无需改测试
        int totalMigrations = countMigrationScripts();
        assertEquals(totalMigrations, flyway.migrate().migrationsExecuted);

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
                .baselineVersion(LEGACY_BASELINE_VERSION)
                .load();

        // 从 V3 baseline 起仅执行 V4 及以后（总迁移数 - 前 3 个），新增迁移自动适配
        int expectedAfterBaseline =
                countMigrationScripts() - Integer.parseInt(LEGACY_BASELINE_VERSION);
        assertEquals(expectedAfterBaseline, legacyUpgradeFlyway.migrate().migrationsExecuted);

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

    /**
     * 统计 db/migration 下所有 V*.sql 迁移脚本的数量。
     * 新增迁移脚本后无需修改测试断言，此处自动适配最新迁移数。
     */
    private static int countMigrationScripts() throws IOException {
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(FlywayMySqlContainerTest.class.getClassLoader());
        Resource[] migrations =
                resolver.getResources("classpath:db/migration/V*.sql");
        return migrations.length;
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
