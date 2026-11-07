# VideoNest 数据库查询与并发优化评审

日期：2026-11-06  
范围：`backend`（Spring Boot 4 + MyBatis-Plus + MySQL 8 + Redis + RabbitMQ）  
依据：代码静态审查 + `docs/performance-test-2026-09-04.md` 等既有压测数据 + 表结构与索引迁移

---

## 0. 结论摘要

先给判断：**当前系统的瓶颈不在“SQL 写得慢”，而在“缓存失效方式把并发读退化成串行回源”，
以及“小规格容器里线程数/连接数与后台任务互相争抢”。** 查询层的问题是第二位的、局部的（缺索引、
重复计数、写路径多做一次查询）。

| # | 问题 | 影响 | 状态 |
|---|---|---|---|
| 1 | 列表缓存失效 = `SMEMBERS` + `DEL` 全量删除，且未命中时用**全局单锁**回源 | 任一次投稿/审核/编辑都会清空全部列表缓存；并发请求排成一列，吞吐上限 ≈ 1 / 回源耗时 | **已改**：版本号失效 + 按 key 分片锁 |
| 2 | 创作者主页 4 条独立 `COUNT` | 每次进创作中心扫 4 遍同一批行 | **已改**：1 条条件聚合 |
| 3 | 点赞/收藏“先 count 再 insert”，事务内读整行 + 写 Redis + 推热度 | 每次 5 次 DB 往返；行锁与 Redis 往返绑在一起；回滚后缓存可能已写脏 | **已改**：唯一键幂等 + 提交后置 + 单列计数 |
| 4 | 通知消费“先 selectCount 再 insert” | 每条通知 2 次 DB 往返，且两步之间仍有竞态 | **已改**：只靠唯一键 |
| 5 | 热度 `ZINCRBY` + `EXPIRE` 两次 Redis 往返，且在业务事务内 | 写路径事务时长 +1 个 RTT | **已改**：单条 Lua |
| 6 | `video`/`video_comment` 缺 4 条组合索引 | 兜底扫描、回收站分页、级联删评论走全表/文件排序 | **已改**：`V11__query_path_indexes.sql` |
| 7 | `SCHEDULING_POOL_SIZE=1`，Outbox 逐条阻塞等 confirm（≤5s/条） | 热榜刷新/播放量刷库被排队，卡片 key 先过期 → 请求回源 | **已改**：池调到 4 |
| 8 | 关键词搜索 `LIKE '%kw%'`、深分页 `LIMIT offset`、列表每次 `COUNT(*)` | 搜索必然全表扫 + filesort；页数越深越慢 | 建议（见 §6） |
| 9 | `video.like_count` 单行热点 + 同步更新 | 爆款视频的点赞会串行争用同一行锁 | 建议（见 §6） |
| 10 | Tomcat 100 线程 / Hikari 16 / Redis 16 的配比 | 超配线程只是在连接池前排队，尾延迟被放大 | 建议（见 §6.7） |

本次**已落地的代码改动**见 §4，全部可回滚；未落地的部分给了明确的收益、成本与验证方法。

---

## 1. 现状基线

### 1.1 运行规格（来自 `docker-compose.server.yml`）

| 项 | 值 | 备注 |
|---|---|---|
| 容器 | `mem_limit 640m`，2 核共享宿主机 | 与 MySQL / Redis / MinIO 同机（`lp-shared` 网络） |
| JVM | `-Xms192m -Xmx256m`，Metaspace 128m，CodeCache 64m，Xss 512k | 堆只占容器 40% |
| Tomcat | `max-threads 100`，`accept-count 150`，`max-connections 800` | |
| Hikari | `maximum-pool-size 16`，`connection-timeout 2000ms` | |
| Redis (Lettuce) | `max-active 16`，`max-wait 500ms` | |
| 调度池 | `SCHEDULING_POOL_SIZE`（原 1，本次改 4） | 所有 `@Scheduled` 共用 |
| 列表缓存 | Redis TTL 45s（演示档），本地软过期 15s | |
| 热榜 | 刷新 45s，卡片 TTL 60s，本地 2s | 三个值互相耦合 |

### 1.2 实测表现（`docs/performance-test-2026-09-04.md`）

| 场景 | 并发 | QPS | P50 | P95 | P99 | 成功率 |
|---|---:|---:|---:|---:|---:|---:|
| 热门视频 | 100 | 319 | 296 ms | 652 ms | 777 ms | 100% |
| 热门视频 | 400 | 385 | 366 ms | 2304 ms | 9011 ms | 99.25% |
| 热门视频 | 800 | 373 | 576 ms | 10000 ms | 10133 ms | 94.73% |
| 视频列表 | 50 | 127 | 301 ms | 890 ms | 1559 ms | 100% |
| 视频列表 | 100 | 107 | 524 ms | 2120 ms | 6041 ms | 99.58% |
| 视频列表 | 200 | 98 | 832 ms | 6070 ms | 11261 ms | 98.23% |
| 视频列表 | 400 | 96 | 1182 ms | 10700 ms | 16263 ms | 88.20% |

两个特征非常典型：

1. **QPS 在 ~100（列表）/ ~400（热榜）就封顶，之后只涨延迟不涨吞吐** —— 说明请求在某个串行点上排队，
   而不是 CPU 还能继续干活。
2. **P50 随并发线性上涨**（列表 301 → 524 → 832 → 1182ms）—— 典型的“全队列等待”，不是个别慢查询。

列表页在压测里是固定 `page=1&size=12`，本应 100% 命中本地 Caffeine。它却呈现“每请求 ~300ms”的形态，
只可能是两种情况之一：**缓存没命中（回源 DB）**，或者**命中但被锁/线程池挡住**。本次改动同时消除了
“全局锁”和“全量失效”这两个会把命中率打回 0 的结构性原因；剩下的部分需要按 §5 的指标确认。

---

## 2. 查询层审查

### 2.1 视频列表（最热的读路径）

`VideoMapper.xml` → `selectPublishedPage` / `countPublishedVideos`：

```sql
FROM video v
INNER JOIN sys_user u ON v.author_id = u.id
INNER JOIN video_category c ON v.category_id = c.id
WHERE v.status = 'PUBLISHED' AND v.is_deleted = 0 [AND v.category_id = ?]
ORDER BY v.publish_time DESC, v.id DESC
LIMIT ?, ?
```

**做对的地方**：V4 已经建了 `(status, is_deleted, publish_time, id)` 与
`(category_id, status, is_deleted, publish_time, id)`，`ORDER BY` 的列序与索引列序一致，
MySQL 8 可以走索引顺序扫描、避免 filesort；两个 JOIN 都是主键等值，代价可控。

**仍然存在的问题**：

1. **每次回源都是 2 条 SQL（count + page）**。`COUNT(*)` 带两个 JOIN，无法只扫 `video` 索引，
   在数据量上来后是列表回源里最贵的一步。列表分页 UI 确实需要 total，但 total 的变化频率远低于列表内容，
   完全可以单独缓存（更长 TTL）或用 Redis 计数器维护，而不是跟着每一页一起重算。
2. **搜索走 `LIKE CONCAT('%', ?, '%')`**：前置通配符无法用 B-Tree，必然全表扫 + filesort，
   且 `count` 版本还要再扫一遍。这是“查询优化”里唯一必须换数据结构的点（见 §6.1）。
3. **深分页没有上限**：`VideoController.list` 只限制 `size ≤ 50`，`page` 没有 `@Max`；
   配合 `page > 10` 不进缓存的策略，`page=10000` 会实打实扫 12 万行。成熟做法是游标分页或封顶页码（见 §6.2）。
4. **`selectPublishedListByIds` / `selectRecentPublished` 查了 `description`（VARCHAR(2000)）**，
   而列表卡片并不展示简介。热门卡片一次取 50 条时这是纯 IO 浪费。属于低风险清理项，本次未动，
   因为要确认前端是否在卡片上用了该字段。

### 2.2 评论

V6 已经补了 `(video_id, root_id, status, created_at, id)`，所以：

- 一级评论分页 `(video_id, parent_id=0, status=1) ORDER BY created_at DESC` → 命中
  `idx_comment_video_parent_status_time`；
- 回复分页 `(video_id, root_id, status) ORDER BY created_at` → 命中 V6 的新索引；
- 当前页回复数聚合 `root_id IN (...) GROUP BY root_id` → 也能被 V6 索引覆盖。

**唯一的缺口**：`softDeleteRepliesByRootId` 的条件是 `WHERE root_id = ? AND status = 1`。
所有现存索引都以 `video_id` 或 `parent_id` 开头，`root_id` 没有可用前缀 —— 删一条热门视频的根评论
会扫全表。本次补 `(root_id, status)`。

另外 `CommentServiceImpl.createComment` 在事务里做了：`selectPublishedViewCountById`（校验）→
`insert` → `hotRank.addScore`（Redis）→ `selectById(videoId)`（拿作者）→ 发事件。
其中“拿作者”可以并进第一次校验查询（`SELECT author_id ... WHERE id=? AND status='PUBLISHED'`），
省一次往返。本次未改，避免动评论主流程；列在这里作为下一步的小优化。

### 2.3 创作者中心

`CreatorVideoQueryServiceImpl.getCreatorProfile` 原来是 4 条 `selectCount`：

```java
selectCount(author_id = ?)
selectCount(author_id = ? AND status = 'PENDING')
selectCount(author_id = ? AND status = 'PUBLISHED')
selectCount(author_id = ? AND status = 'REJECTED')
```

单列索引 `idx_video_author(author_id)` 只能定位，每一条都要回表判断 `status`，同一批行扫 4 遍。
已改为一条：

```sql
SELECT COUNT(*) AS total,
       COALESCE(SUM(status='PENDING'),0)   AS pending,
       COALESCE(SUM(status='PUBLISHED'),0) AS published,
       COALESCE(SUM(status='REJECTED'),0)  AS rejected
FROM video WHERE author_id = ? AND is_deleted = 0
```

配合新增的 `(author_id, status)`，4 次扫描变 1 次、且可走覆盖索引。**行为变化**：原实现没有过滤
`is_deleted`，回收站里的稿件也被算进“全部作品”，现在不计入 —— 这是修正，但会改变页面上显示的数字，
上线前需要跟产品确认。

### 2.4 后台扫描类查询

| 查询 | 现状 | 问题 |
|---|---|---|
| `selectStuckProcessingVideoIds` | `status='PROCESSING' AND is_deleted=0 AND update_time <= ? ORDER BY update_time` | 无索引覆盖 `update_time`，每 5 分钟扫全表 + filesort |
| `selectDeletedVideoPage` | `is_deleted=1 ORDER BY deleted_at DESC` | 只有 `(is_deleted, purge_after)`，排序列不同 → filesort |
| `selectCoverThumbnailBackfillBatch` | `cover_list_url IS NULL OR ... LIKE 'cover/%'` + `NOT LIKE` | 无法走索引，每 5 分钟全表扫；`SELECT *` 还带出大字段 |
| `selectDueReviewTimeoutVideoIds` | `(status, review_deadline, review_timeout_notified)` | 已有索引，OK |
| `selectDuePurgeVideoIds` | `(is_deleted, purge_after)` | 已有索引，OK |
| `selectReady`（Outbox） | `(status, next_retry_at, id)` | 已有索引，OK |

前三条已在 V11 修了两条（stuck / deleted）。封面补全那条建议改成“按 id 游标分批 + 只查需要的列”，
而不是每次 `SELECT * ... LIKE`（见 §6.4）。

### 2.5 索引冗余与写放大

`video` 表当前有 11 条索引（含 1 个唯一索引），其中 4 条是本次新增：

```
idx_video_category_publish (category_id, publish_time)          -- 被 V4 的 category_feed 覆盖
idx_video_author (author_id)                                    -- 被 V4 的 author_feed / V11 的 author_status 覆盖
idx_video_status_publish (status, publish_time)                 -- 被 V4 的 publish_feed 覆盖
idx_video_publish_feed (status, is_deleted, publish_time, id)   -- 保留
idx_video_category_feed (category_id, status, is_deleted, publish_time, id) -- 保留
idx_video_author_feed (author_id, is_deleted, create_time, id)  -- 保留
idx_video_deleted_purge (is_deleted, purge_after)               -- 物理清理任务，保留
idx_video_review_timeout (status, review_deadline, review_timeout_notified) -- 审核超时，保留
idx_video_status_deleted_update (status, is_deleted, update_time) -- V11
idx_video_deleted_at (is_deleted, deleted_at, id)               -- V11
idx_video_author_status (author_id, status)                     -- V11
uk_video_original_object (original_video_url)                   -- 唯一约束，保留
```

每条二级索引都会让 INSERT/UPDATE 多维护一棵 B+ 树。在 2 核机器上，这是实打实的写放大。
`idx_video_category_publish`、`idx_video_status_publish`、`idx_video_author` 理论上都可以删，
但**本次没有删**：删除索引属于不可逆操作，且需要先确认没有历史查询依赖。建议用
`sys.schema_unused_indexes` 观察一个周期后再手工 `DROP`（校验脚本 §8 已给出查询）。

---

## 3. 并发与一致性审查

### 3.1 列表缓存的失效模型（本次最大的改动）

**原实现**（`VideoListCacheServiceImpl.invalidateAll`）：

```java
localCache.invalidateAll();
Set<String> keys = redis.opsForSet().members(VIDEO_LIST_CACHE_KEYS_KEY);
redis.delete(keys);
redis.delete(VIDEO_LIST_CACHE_KEYS_KEY);
```

三个问题：

1. **O(N) 且非原子**：先 `SMEMBERS` 再 `DEL`，两步之间新写入的 key 不会被删掉，只能等 TTL。
2. **全量失效**：任何一条视频的审核/编辑/删除都会清空**所有分类、所有页码**的缓存。
   频繁投稿时，列表缓存永远处于“刚被清空”的状态 —— 压测是纯读所以不明显，真实流量（读多写少但持续写）
   下会把回源压力放大一个数量级。
3. **配合全局锁 → 吞吐被 1 条 SQL 定死**：见 3.2。

**新实现**：版本号。

```
cacheKey = videonest:video:list:first:v3:v{version}:{category}:{page}:{size}
写操作   = INCR videonest:video:list:ver:v3
```

- 失效是**单条命令、原子、O(1)**，不存在漏删；
- 旧版本 key 不再被访问，交给 TTL 自然淘汰，不需要遍历；
- 本地 Caffeine 的 key 也带版本，版本一变自然穿透到 Redis，不存在“本地缓存删不干净”；
- 版本号在本地缓存 1 秒：列表命中路径**零 Redis 往返**，跨实例失效最迟 1 秒生效；
- **回源期间失效的防抖**：回源前取一次版本快照，写缓存时再比对；不一致说明回源这 200~300ms 内有写操作提交，
  直接丢弃这批结果，让下一个请求重新回源。否则旧数据会被写进新版本，一直存活到 TTL 到期。

对“上传后马上能看到”的诉求没有影响：写操作触发 `INCR` 后，下一次读就是新版本、直接回源。

> 参考做法：Rails 的 `cache_key_with_version`（key 里带 `updated_at` 版本）就是同一思路；
> 大规模缓存系统普遍用“版本/代际”代替“枚举删除”，因为后者在分布式下既慢又不可能严格正确。

### 3.2 击穿锁的粒度

`VideoDiscoveryServiceImpl.readCachedList` 原来：

```java
synchronized (firstPageRebuildMonitor) { ... 查库 ... }
```

这是一个 **JVM 级全局监视器**：所有分类、所有页码的未命中请求在这里排成一队。
缓存被清空后 N 个并发请求 → 1 个查库、N-1 个阻塞；假设回源 250ms，这条链路的吞吐上限就是 **4 QPS**。
即使命中率 99%，只要每秒有几次 miss，尾延迟也会被拉爆 —— 这与压测里“P50 线性上涨”完全吻合。

改为**按 `category:page:size` 分片锁**（Caffeine 保存锁对象，上限 512 个）：

- 同一个 key 仍然只有一个线程回源（防击穿目标不变）；
- 不同页/不同分类互不阻塞；
- 锁对象有界，不会随请求参数无限增长。

> 参考做法：go-zero 的 cache 组件用 `singleflight` 把同一 key 的并发回源合并成一次；
> 这是“防击穿”的标准形态 —— 关键是**按 key 合并**，不是**全局互斥**。

### 3.3 SWR 刷新只有 1 个线程

`refreshExecutor = Executors.newSingleThreadExecutor(...)`。10 个缓存页同时软过期时，重建是串行的；
单次重建 = count + page ≈ 300ms，队列会越积越长，软过期数据被反复返回。
已改为 2 线程（2 核机器上再多只会和 Web 线程抢 CPU）。

### 3.4 事务边界：Redis / 热度 / 通知不该在事务里

`InteractionServiceImpl.like()` 原来是：

```
@Transactional
  selectById (校验 + 拿整行)
  count (判重)
  insert
  update video set like_count = like_count + 1   ← 行锁
  selectById (再读整行拿新计数)
  redis set ×2 + redis get/set 详情缓存            ← 网络往返
  redis zincrby + expire                          ← 2 次往返
  publishEvent → outbox insert                    ← 1 次写
```

数据库事务的持有时间里混进了 5+ 次 Redis 往返和一次 outbox 写入。后果：

- `video` 行的排他锁要等所有 Redis 操作做完才释放 → 爆款视频的点赞请求互相排队；
- 事务若在 Redis 写入之后回滚，缓存里已经写着“已点赞”，DB 却是未点赞 —— 脏缓存要等 12 小时 TTL 才自愈；
- 连接池（16 条）被这类“长事务”占用更久。

已改为：**事务内只保留 `insert` + 计数自增**，其余全部注册到 `afterCommit`。
这不会降低单请求延迟，但会显著缩短行锁持有时间与连接占用时间，并消除“回滚脏缓存”。

> 参考规范：Spring 官方对 `@Transactional` 的建议就是“事务尽量短、不要在事务里做远程调用”；
> 事件用 `@TransactionalEventListener(AFTER_COMMIT)`，缓存用 afterCommit 回调。

### 3.5 幂等实现：先查后插 → 唯一键

`like` / `favorite` / `consume` 三处都是“先 count/selectCount 再 insert”。问题：

- 多一次往返；
- **两步之间仍有竞态**：两个并发请求都查到 0，然后都插入，一个成功一个抛唯一键异常（500）；
- 真正保证幂等的本来就是唯一键，前置查询只是“让异常少发生”，并没有让逻辑更正确。

已改为直接 insert、捕获 `DuplicateKeyException` 当作“已处理”。MySQL 的唯一键冲突不会中止事务，
捕获后正常返回是安全的。

### 3.6 调度线程池与阻塞任务

所有 `@Scheduled` 默认共用一个 `taskScheduler`。生产配置把它设成 **1**，而任务里有：

- `OutboxEventScheduler`（每 5s）：逐条 `convertAndSend` + **阻塞等 publisher confirm，单条最长 5s**；
- `HotVideoCacheServiceImpl.refreshRankAndCards`（每 45s）：24 个 ZSet 读 + 一次批量查库 + 50 次签名；
- `VideoViewCountServiceImpl.flushPendingViews`（每 60s）：一次 200 行的批量 UPDATE；
- 审核超时兜底、封面补全、资源清理、上传清理、指标采集。

只要 Outbox 这一轮有 3 条消息各等 5s，热榜刷新就会被推迟 15s；推迟超过卡片 TTL（60s）后，
热榜 key 先过期，所有请求回源重建 —— 这正好解释了“热门接口偶发 P99 冲顶”。
本次把池调到 4（4 × 512k 栈 ≈ 2MB），让阻塞任务之间不再互相拖累。

> 注意：`RenewableRedisLock` 的看门狗已经用了独立 scheduler（`redisLockWatchdogScheduler`），
> 这一点原本就做对了，不在本次修改范围内。

### 3.7 线程数与连接数的配比

HikariCP 官方 wiki 给的池大小估算：`connections = (core_count * 2) + effective_spindle_count`。
2 核 + SSD ≈ **5~6 条**就够跑满 CPU；现在的 16 条已经偏宽松，而 Tomcat 却开了 100 个线程。

- 100 个 Web 线程里，最多 16 个能拿到连接，其余 84 个在 Hikari 的 `connectionTimeout=2000ms` 上排队；
- 排队失败就是 500，表现为“QPS 不涨、失败率涨”；
- 线程越多，上下文切换和 256MB 堆里的对象存活时间越长，GC 压力越大。

**建议**（未直接改，需按压测验证）：`TOMCAT_MAX_THREADS` 降到 48~64，`DB_POOL_MAX_SIZE` 保持 16
或降到 12。让请求在 Tomcat 的 accept 队列里等，比在连接池上调 2 秒超时要健康得多（前者可观测、后者直接 500）。

### 3.8 热点行：`video.like_count`

点赞成功要 `UPDATE video SET like_count = like_count + 1`。同一行的更新在 InnoDB 里是排他的，
爆款视频的点赞会被串行化；同时这条 UPDATE 还会让列表/详情缓存失效。

成熟做法是把**计数从内容行里拆出去**：Redis `HINCRBY` 计数 + 定时批量刷库（本项目播放量已经这么做了，
见 `VideoViewCountServiceImpl`，点赞/收藏却还是同步写主表）。建议下一步把 `like_count` / `favorite_count`
也迁到同一套“Redis 计数 + 定时合并”模型，`video` 行只作为最终一致的兜底。

### 3.9 多实例下的本地缓存一致性

版本号方案把跨实例失效延迟压到 1 秒，已经够用。如果以后对“1 秒”也不满意，再加 Redis Pub/Sub
广播版本变更即可（收到消息就把本地版本戳置空），不需要动缓存结构。

---

## 4. 已落地改动

### 4.1 数据库

| 文件 | 内容 |
|---|---|
| `backend/src/main/resources/db/migration/V11__query_path_indexes.sql` | 4 条索引：`video(status,is_deleted,update_time)`、`video(is_deleted,deleted_at,id)`、`video(author_id,status)`、`video_comment(root_id,status)` |
| `sql/2026-11-06-explain-checks.sql` | 新增：14 条 `EXPLAIN` 校验 + 索引清单 + 未使用索引查询 |

Flyway 会在启动时自动执行 V11；只加索引、不改数据，回滚方式是手工 `DROP INDEX`。

### 4.2 代码

| 文件 | 改动 | 行为变化 |
|---|---|---|
| `infrastructure/redis/RedisKeys.java` | 新增 `VIDEO_LIST_VERSION_KEY`；`videoListPage(...)` 增加 `version` 参数，前缀升到 `v3` | 旧 key 由 TTL 淘汰，无需清理 |
| `module/video/service/impl/VideoListCacheServiceImpl.java` | 版本号失效；回源前后做版本快照（CAS 写入，失效中途的旧结果直接丢弃）；去掉 key 集合的 `SADD`/`EXPIRE` 记账；刷新线程 1→2 | 失效变 O(1)；跨实例最迟 1s 生效；不会把回源期间的旧数据写进新版本 |
| `module/video/service/VideoListCacheService.java` | 新增 `currentVersion()` 与带版本的 `putPage(..., version)`，保留 4 参重载 | 调用方按需选择是否做版本校验 |
| `module/video/service/impl/VideoDiscoveryServiceImpl.java` | 全局 `synchronized` → 按 key 分片锁（Caffeine，上限 512） | 不同页不再互相阻塞 |
| `module/video/service/impl/CreatorVideoQueryServiceImpl.java` | 4 条 COUNT → 1 条聚合 | “全部作品”不再计入已删除稿件 |
| `module/video/vo/CreatorVideoStatusCountsVO.java` | 新增承载对象 | — |
| `module/video/mapper/VideoMapper.java` + `mapper/video/VideoMapper.xml` | 新增 `selectCreatorVideoStatusCounts`、`selectLikeCountById`、`selectFavoriteCountById` | — |
| `module/interaction/service/impl/InteractionServiceImpl.java` | 唯一键幂等；事务后置副作用；单列计数；详情缓存保留剩余 TTL；计数 TTL 加抖动 | 重复点赞/收藏不再 500；不再把详情缓存 TTL 重置成 30 分钟 |
| `module/notification/service/impl/NotificationMessageConsumerServiceImpl.java` | 去掉前置 `selectCount` | 每条通知少 1 次查询 |
| `module/video/service/impl/HotRankServiceImpl.java` | `ZINCRBY`+`EXPIRE` 合成 1 条 Lua | 写路径少 1 个 RTT |
| `docker-compose.server.yml` | `SCHEDULING_POOL_SIZE: 1 → 4` | 后台任务不再互相阻塞 |

### 4.3 明确没有改的

- 没有删任何索引（只加）；
- 没有改 `LIKE` 搜索、没有改分页参数（涉及前端契约）；
- 没有改 Tomcat / Hikari / Redis 池大小（需要压测数据支撑，见 §6.7）；
- 没有动评论、发布、转码主流程。

---

## 5. 验证方法

### 5.1 编译与启动

```powershell
cd S:\videonest\backend
mvn -DskipTests package          # 或 .\mvnw.cmd -DskipTests package
```

Flyway 会在启动日志里打印 `Migrating schema ... to version 11`。

### 5.2 SQL 层

用 `sql/2026-11-06-explain-checks.sql` 逐条跑 `EXPLAIN`，重点看三列：

- `key` 是否为期望索引（不是 `NULL`）；
- `rows` 是否接近实际返回行数，而不是整表；
- `Extra` 是否还有 `Using filesort` / `Using temporary`。

### 5.3 指标层（已有，不用新加埋点）

| 指标 | 位置 | 期望 |
|---|---|---|
| `videonest.video.list.cache{result=hit/miss}` | `/actuator/prometheus` | 稳态 hit 占比 > 95% |
| `hikaricp_connections_pending` | 同上 | 稳态 ≈ 0，峰值不应持续 > 0 |
| `tomcat_threads_busy_threads` | 同上 | 不应长期贴住 `max-threads` |
| `videonest.mysql.slow_queries` | 同上 | 压测期间增量应接近 0 |
| `videonest.redis.cache.hit.ratio` | 同上 | > 0.9 |
| JVM GC pause | `jvm_gc_pause_seconds` | P99 < 50ms（256MB 堆要重点看） |

如果压测时 `list.cache` 的 miss 仍然很高，说明是**写操作在持续 bump 版本**，而不是读路径有问题 ——
那时应该把“投稿/审核”与“普通编辑”区分开：只有影响列表排序/可见性的写操作才 bump 版本，
改标题、改简介这类不影响列表的写操作不应该清空列表缓存（当前是统一 `invalidateAll`）。

### 5.4 压测

```powershell
node scripts/run-performance-suite.js --quick        # 约 100 秒
node scripts/run-performance-suite.js                # 完整 16 场景
```

建议连续 3 轮、每轮间隔 5 分钟冷却，取中位数；单轮数据受机器瞬时负载影响很大（既有报告自己也提到了这点）。

### 5.5 功能回归清单

- 重复点赞 / 重复收藏：第二次应静默成功（幂等），不报 500；
- 点赞后立刻刷新详情页：计数应更新（afterCommit 后置）；
- 投稿 → 审核通过 → 首页列表：应立即可见（版本号 bump）；
- 删除视频 → 列表/热榜：应消失；
- 评论列表、回复展开、删除根评论：条数与级联删除正确。

---

## 6. 下一步（按收益/成本排序，尚未实施）

### 6.1 搜索：换掉 `LIKE '%kw%'`（P0）

- **低成本方案**：给 `video.title` 建 `FULLTEXT ... WITH PARSER ngram`（MySQL 官方支持中文二元分词），
  查询改 `MATCH(title) AGAINST (? IN BOOLEAN MODE)`。代价：多一个全文索引的写放大，收益：搜索从全表扫变索引检索。
- **高成本方案**：接 Elasticsearch / Meilisearch。对当前数据量没必要，但若要做「标题 + 简介 + 作者」联合搜索，
  这是唯一正确的方向。

### 6.2 深分页：游标化（P0）

`WHERE (publish_time, id) < (?, ?) ORDER BY publish_time DESC, id DESC LIMIT 12`。
前端首页/分类页如果只做“下一页”，完全不需要 offset；分页器场景可以保留 offset，但给 `page` 加 `@Max(100)`。

> 参考：Mastodon / Twitter 的 API 都是 `max_id` / `since_id` 游标，而不是 page/offset。

### 6.3 列表 total：计数器化（P1）

发布/下架时 `INCR/DECR` 一个 `list:count:{category}`，列表接口读计数器；DB 的 `COUNT(*)` 只作为
定时校准的兜底。或者干脆去掉 total（无限滚动不需要精确总数）。

### 6.4 后台扫描：游标分批（P1）

`selectCoverThumbnailBackfillBatch` 目前每轮 `SELECT * ... WHERE cover_list_url IS NULL OR ... LIKE`，
全表扫。改成 `WHERE id > #{lastId} AND ... ORDER BY id LIMIT 200`，每轮只推进一段，避免反复扫头部数据。

### 6.5 播放量 dirty 集合：`SET` → `ZSET`（P1）

`distinctRandomMembers(dirty, 200)` 在集合很大时是 O(N) 的随机取样。改成 `ZADD dirty {now} videoId`，
刷库时 `ZRANGEBYSCORE 0 now LIMIT 0 200`，天然按时间有序、可观测积压量。

### 6.6 Outbox 派发：批量化（P1）

当前是 `selectReady(50)` + 逐条 `claim` + 逐条阻塞等 confirm。两条改进：

1. `claim` 改成一条 `UPDATE ... WHERE id IN (...)`（配合 `instance_token` 或 `SKIP LOCKED`）；
2. confirm 改成异步回调（`CorrelationData.getFuture().whenComplete`），派发线程不再被单条消息卡 5 秒。

### 6.7 池与线程（P2，必须压测验证）

| 参数 | 现状 | 建议 | 理由 |
|---|---:|---:|---|
| `TOMCAT_MAX_THREADS` | 100 | 48~64 | 2 核；超出 DB 池的部分只是在连接池前排队 |
| `DB_POOL_MAX_SIZE` | 16 | 12~16 | Hikari 公式 2 核约 5~6；16 已宽松，不必再加 |
| `REDIS_POOL_MAX_ACTIVE` | 16 | 16~24 | 列表命中不碰 Redis 后压力已下降 |
| `SCHEDULING_POOL_SIZE` | 1 → 4 | 4 | 已改 |
| `JAVA_TOOL_OPTIONS` | `-Xmx256m` | 观察 GC 后再定 | 容器 640m，256m 堆 + 128m metaspace + 64m codecache 已占 460m |

### 6.8 Nginx 微缓存（P2，成本最低的容量提升）

`/api/videos/hot` 与 `/api/videos?page=1&size=12` 是典型的“内容粒度粗、允许秒级延迟”的接口。
在 Nginx 上做 `proxy_cache` 1~2 秒，可以把大部分突发流量挡在后端之外，且不影响一致性语义（本来就有 45s TTL）。
这是 YouTube/B 站这类站点在源站前做“边缘缓存”的基本盘。

### 6.9 点赞计数异步化（P2）

把 `like_count` 从 `video` 行迁到 Redis 计数 + 定时合并（复用 `VideoViewCountServiceImpl` 的
`claim/restore` 模式），彻底消除热点行争用。需要改 `getStatus` 与列表 VO 的取值来源。

---

## 7. 这次改动的取舍说明

1. **为什么用版本号而不是“按分类精确失效”？**
   精确失效需要知道某条视频属于哪些分类、影响前 10 页中的哪些页，逻辑复杂度高且容易漏。
   版本号是“全局正确、成本最低”的解；如果以后写操作非常频繁（比如每秒几十次投稿），
   再按分类拆版本号（`list:ver:cat:{id}`）即可，结构不变。

2. **为什么保留本地 Caffeine 而不是只用 Redis？**
   列表是最高频读，本地命中的成本是纳秒级；Redis 在 100 并发下即使只有 0.5ms，也会占满 Lettuce 连接池。
   本地 + Redis + SWR 是这类 feed 的标准三级结构。

3. **为什么没有直接把 `TOMCAT_MAX_THREADS` 调小？**
   线程数直接决定“能同时挂起多少请求”。调小会把排队从连接池挪到 accept 队列，**吞吐不变、尾延迟更可控**，
   但需要压测确认不会把 200 并发的成功率打下去。这类改动应该由数据决定，而不是拍脑袋。

4. **为什么没有引入 Elasticsearch / 分库分表？**
   当前数据量（单机 MySQL、几千条视频）远没到需要它们的程度。过早引入会让部署复杂度和故障面翻倍，
   收益却接近于零。

---

## 8. 附：参考的成熟实践

| 主题 | 参考 | 与本项目的对应 |
|---|---|---|
| 缓存击穿 | go-zero `stores/cache` 用 `singleflight` 合并同 key 回源 | 按 key 分片锁 |
| 缓存失效 | Rails `cache_key_with_version`；HTTP `ETag` 的版本思路 | 列表版本号 |
| 连接池大小 | HikariCP wiki：`(cores * 2) + spindles`；PostgreSQL wiki "About pooling" | §6.7 |
| 深分页 | Mastodon / Twitter API 的 `max_id` 游标 | §6.2 |
| 中文全文检索 | MySQL 官方 `FULLTEXT ... WITH PARSER ngram` | §6.1 |
| 事务性发件箱 | Transactional Outbox + `SELECT ... FOR UPDATE SKIP LOCKED` 批量抢占 | §6.6 |
| 播放/点赞计数 | 计数与内容分离、异步合并刷库（YouTube 官方说明播放数为批量统计、非实时） | §6.9 |
| 短事务 | Spring 官方：事务内不做远程调用；`@TransactionalEventListener(AFTER_COMMIT)` | §3.4 |
