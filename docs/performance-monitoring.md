# VideoNest 性能监控与复测说明

## 入口

后端 Actuator 监听应用端口，Docker Compose 默认只把 `8080` 绑定到服务器本机：

- 健康检查：`http://127.0.0.1:8080/actuator/health`
- Prometheus：`http://127.0.0.1:8080/actuator/prometheus`
- 指标目录：`http://127.0.0.1:8080/actuator/metrics`

Nginx 不代理 `/actuator`，因此监控入口不会通过网站 80 端口公开。

## 关键指标

| 范围 | 指标 |
|---|---|
| HTTP | `http_server_requests_seconds_*`，按 URI、method、status 观察吞吐、错误和分位桶 |
| JVM | `jvm_memory_used_bytes`、`jvm_gc_pause_seconds_*`、`jvm_threads_live_threads` |
| Tomcat | `tomcat_threads_busy_threads`、`tomcat_threads_current_threads`、`tomcat_connections_current_connections` |
| Hikari | `hikaricp_connections_active`、`hikaricp_connections_pending`、`hikaricp_connections_timeout_total` |
| MySQL | `videonest_mysql_threads_connected`、`videonest_mysql_threads_running`、`videonest_mysql_slow_queries`、`videonest_mysql_queries` |
| Redis | `videonest_redis_connected_clients`、`videonest_redis_blocked_clients`、`videonest_redis_used_memory`、`videonest_redis_instantaneous_ops_per_sec` |
| Redis命中 | `videonest_redis_cache_hit_ratio` |
| 列表缓存 | `videonest_video_list_cache_total{result="hit|miss"}` |

HTTP 直方图已配置 100ms、300ms、500ms、1s、3s、10s 的 SLO 桶，便于直接计算达标率和 P95/P99。

## SQL 验证

本机现有测试库只有 10 条视频（5条已发布）。`COUNT` 已确认使用覆盖索引 `idx_video_publish_feed`；数据查询由于样本极小，MySQL 选择全表扫描加排序，其成本低于走索引，不能代表生产数据规模。

生产复测前应在接近生产的数据量下执行：

```sql
EXPLAIN ANALYZE
SELECT v.id, v.title, v.cover_list_url, v.duration,
       v.view_count, v.like_count, v.favorite_count, v.publish_time,
       v.author_id, u.nickname, v.category_id, c.name
FROM video v
JOIN sys_user u ON u.id = v.author_id
JOIN video_category c ON c.id = v.category_id
WHERE v.status = 'PUBLISHED' AND v.is_deleted = 0
ORDER BY v.publish_time DESC, v.id DESC
LIMIT 12;

EXPLAIN ANALYZE
SELECT COUNT(*)
FROM video
WHERE status = 'PUBLISHED' AND is_deleted = 0;
```

普通首页预期优先使用 `idx_video_publish_feed`，分类列表预期使用 `idx_video_category_feed`。关键词包含搜索仍使用 `%keyword%`，数据规模扩大后应单独评估 FULLTEXT/ngram。

## 公平复测

1. 使用与优化前相同的发压机、网络、并发和持续时间。
2. 分别记录冷缓存首请求与缓存预热后的稳态结果。
3. 压测期间同步保存 Prometheus 指标，重点对齐超时发生时的 Tomcat busy、Hikari pending、MySQL running 和 Redis blocked。
4. 使用 `node scripts/run-performance-suite.js`；脚本会从热门接口动态取得真实视频 ID，不再固定访问视频 1。
5. 比较 QPS、成功率、P50/P95/P99、连接重置、服务器 CPU/内存及缓存命中率。
