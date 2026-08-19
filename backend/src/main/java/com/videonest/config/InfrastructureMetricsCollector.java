package com.videonest.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 低频采集 MySQL / Redis 服务端状态并暴露为 Micrometer Gauge。
 * JVM、Tomcat、Hikari 与 HTTP 指标由 Actuator 自动提供；这里补齐基础设施侧指标。
 */
@Component
@Slf4j
public class InfrastructureMetricsCollector {

    private static final List<String> MYSQL_METRICS = List.of(
            "threads_connected", "threads_running", "slow_queries", "queries"
    );
    private static final List<String> REDIS_METRICS = List.of(
            "connected_clients", "blocked_clients", "used_memory",
            "keyspace_hits", "keyspace_misses", "instantaneous_ops_per_sec"
    );

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicLong> mysqlValues = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> redisValues = new ConcurrentHashMap<>();

    public InfrastructureMetricsCollector(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void registerMetrics() {
        MYSQL_METRICS.forEach(name -> Gauge.builder(
                        "videonest.mysql." + name,
                        mysqlValues.computeIfAbsent(name, ignored -> new AtomicLong()),
                        AtomicLong::get
                ).description("MySQL SHOW GLOBAL STATUS: " + name).register(meterRegistry));
        REDIS_METRICS.forEach(name -> Gauge.builder(
                        "videonest.redis." + name,
                        redisValues.computeIfAbsent(name, ignored -> new AtomicLong()),
                        AtomicLong::get
                ).description("Redis INFO: " + name).register(meterRegistry));
        Gauge.builder("videonest.redis.cache.hit.ratio", this, collector -> collector.redisHitRatio())
                .description("Redis keyspace hit ratio since server start")
                .register(meterRegistry);
        refresh();
    }

    @Scheduled(fixedDelayString = "${infrastructure-metrics.refresh-milliseconds:15000}")
    public void refresh() {
        refreshMySql();
        refreshRedis();
    }

    private void refreshMySql() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SHOW GLOBAL STATUS
                    WHERE Variable_name IN (
                      'Threads_connected', 'Threads_running', 'Slow_queries', 'Queries'
                    )
                    """);
            for (Map<String, Object> row : rows) {
                String name = String.valueOf(row.get("Variable_name")).toLowerCase(Locale.ROOT);
                set(mysqlValues, name, row.get("Value"));
            }
        } catch (RuntimeException e) {
            log.debug("采集 MySQL 性能指标失败", e);
        }
    }

    private void refreshRedis() {
        try {
            Properties info = redisTemplate.execute((RedisCallback<Properties>) connection ->
                    connection.serverCommands().info()
            );
            if (info == null) {
                return;
            }
            REDIS_METRICS.forEach(name -> set(redisValues, name, info.getProperty(name)));
        } catch (RuntimeException e) {
            log.debug("采集 Redis 性能指标失败", e);
        }
    }

    private void set(Map<String, AtomicLong> target, String name, Object rawValue) {
        if (rawValue == null || !target.containsKey(name)) {
            return;
        }
        try {
            target.get(name).set(Long.parseLong(String.valueOf(rawValue)));
        } catch (NumberFormatException ignored) {
            // 指标格式异常时保留上一次有效值。
        }
    }

    private double redisHitRatio() {
        long hits = redisValues.get("keyspace_hits").get();
        long misses = redisValues.get("keyspace_misses").get();
        long total = hits + misses;
        return total == 0 ? 1D : (double) hits / total;
    }
}
