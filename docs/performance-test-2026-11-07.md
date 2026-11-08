# VideoNest 重要接口完整压测与安全测试报告

测试窗口：2026-11-07 22:36 → 2026-11-08 00:13（服务器本地时间 UTC+8）

目标：`http://101.201.208.223:8082/`（vn-frontend nginx → vn-backend:8080）

发压位置：**服务器本机 loopback**（`127.0.0.1:8082`），非公网

资源档：**当前 idle 生产档，未做任何 bench 覆盖、未重启容器、未改 compose/env**

指标口径：**全文 QPS = 入口每秒完成的 HTTP 请求数**（`completed / elapsedSeconds`）。与历史报告 [performance-test-2026-09-04.md](performance-test-2026-09-04.md) 的 QPS 列**同公式、同计数单位**（旧：[benchmark-read.js:68](../scripts/benchmark-read.js#L68)；本次：[harness/perfload.js:111](performance-results/2026-11-07-load-test/harness/perfload.js#L111)），因此可 1:1 对比，数值未做任何换算。**它不是数据库 SQL 条数/秒**——缓存命中时单请求 SQL 数接近 0，详见 3.4。原始 JSON 字段名仍为 `requestsPerSecond`、`summary.txt` 表头仍为 `rps`，与本报告 QPS 是同一个数，原始件保持原样未改写。

测试状态：**已完成；数据已回滚并逐项核对回基线**

原始数据：[performance-results/2026-11-07-load-test/](performance-results/2026-11-07-load-test/)

---

## 1. 执行摘要

| 维度 | 结论 |
| --- | --- |
| 只读并发极限（视频列表） | **拐点 128 并发**（1542 QPS，100% 成功）；192 并发开始崩塌（309 QPS，p99 21.9 s）；320 并发只剩 21 QPS、成功率 46.5% |
| 只读峰值吞吐 | 列表 **2025 QPS @64 并发**、热榜 **2077 QPS @128 并发**，均 100% 成功 |
| 失效模式 | 列表路径**全程零 5xx**，失败全是客户端 20 s 超时（`status=0`）→ Tomcat 排队而不是快速失败 |
| 真正的瓶颈 | **主机内存（1.87 GB，可用常年 95–215 MB）+ 共享 infra-mysql 的 288 MiB cgroup**，不是 SQL、不是索引、不是 Tomcat 线程数 |
| 写入并发极限 | 点赞 **4 并发稳定 42.8 QPS / p50 49 ms / 100% 成功**；**8 并发即出现 MySQL 连接失败（33×500）** |
| 业务限流 | **精确生效**：匿名播放恰好放行 30 次后全部 429；评论恰好放行 20 次（4 用户 × 5 条）后全部 429 |
| 安全高危 | **认证限流可被 `X-Forwarded-For` 伪造完全绕过**（26 次伪造 IP 的错误密码登录，0 次 429） |
| 安全事故 | 压测期间触发 2 次主机全局 OOM，其中 **vn-backend 被杀并重启 1 次**；另有 **infra-mysql 在空载时自身 cgroup OOM 重启 1 次** |
| 数据回滚 | 12 张表行数、水位线、视频计数、Redis 探针键**全部核对回基线**，0 残留 |

**一句话结论**：这台 2 核 1.87 GB、三套 demo 共用一套 infra 的机器，只读缓存命中路径能扛到 ~2000 QPS，但**任何写入压力在 8 并发左右就会把共享 MySQL 推到内存悬崖**；上线前必须先解决内存配额与快速失败，而不是继续优化 SQL。

---

## 2. 重要更正：撤回"视频列表 ~127 QPS 是查询路径天花板"

历史报告 [performance-test-2026-09-04.md:64](performance-test-2026-09-04.md#L64) 把列表 50 并发的 **126.76 QPS** 归因为"MySQL 查询路径"瓶颈。**这个归因是错的，本次予以撤回。**

证据一 —— 仓库自带的 JMeter 结果 [benchmark/results/hot_rank_20260908.csv](../benchmark/results/hot_rank_20260908.csv)：

| 并发 | 吞吐量 (QPS) | 接收 KB/sec |
| --- | --- | --- |
| 50 | 181.58 | 301.62 |
| 100 | 158.91 | 263.97 |
| 200 | 154.89 | 257.29 |

**并发翻倍、吞吐反而下降，而接收带宽被钉在 257–302 KB/s 不动**——这是链路带宽饱和的特征，不是数据库饱和的特征。数据库饱和时带宽会随吞吐一起掉，不会保持水平。

证据二 —— 历史报告的 baseUrl 是公网入口 `http://82.157.205.6`（**旧服务器，不是本次的 101.201.208.223**），流量走公网；本次实测当前服务器公网上行约 **417 KB/s**。注意历史报告开头那句"流量从同一台主机发起"是 [run-performance-suite.js:139](../scripts/run-performance-suite.js#L139) 里写死的模板文案，不随 `--base-url` 变化，**不能当作发压位置的证据**。

证据三 —— 同一个接口在**本次服务器 + 本次代码 + loopback** 下是 1740–2025 QPS（8–64 并发，100% 成功），比历史值高一个数量级。

**但这不是受控对比，13 倍里至少有三个变量同时在动**：

| 变量 | 2026-09-04 那次 | 本次 |
| --- | --- | --- |
| 发压位置 | 公网入口 `82.157.205.6` | 服务器本机 loopback |
| 服务器 | 旧机 `82.157.205.6` | 当前 `101.201.208.223`（2 核 1.87 GB，三 demo 共用 infra） |
| 代码版本 | 测试开始 **10:59:21**；Caffeine 本地缓存 + SWR 的提交 `b50b94e` 是 **11:34:39**，晚 35 分钟 → **那次没有 L1 SWR** | 含 `b50b94e`，且含测试前 11 小时的 `21674f8`（列表/热榜查询优化，`VideoListCacheServiceImpl` +111 行） |

所以准确的结论是：**"126.76 QPS = MySQL 查询路径天花板"这个归因不成立**，因为该数字同时被公网带宽、旧服务器、以及当时尚未上线的两轮缓存优化污染，无法单独归给 SQL。**反过来也不能说"13 倍全是带宽假象"**——其中有多少来自真实代码优化，本次没有做同机同代码的 A/B，无法拆分。

参考载荷尺寸（本次实测）：列表 raw **3784 B** / gzip **711 B**，热榜 raw **3242 B** / gzip **715 B**。历史运行是否启用 gzip 未记录，因此不做精确带宽反推。

**今后所有容量数字必须注明发压位置、服务器与代码版本。公网入口测出来的 QPS 是带宽上限，不是服务能力上限；跨版本对比不能当作优化效果的度量。**

---

## 3. 测试方法

### 3.1 发压器

无第三方依赖的 Node 脚本 [harness/perfload.js](performance-results/2026-11-07-load-test/harness/perfload.js)，跑在 `node:24-alpine` 容器里（`--network host --memory 128m --memory-swap 192m`），闭环模型：每个并发 worker 串行发请求、收到响应立刻发下一个，`http.Agent` keep-alive，每档重建 agent。

- 模式：`simple`（单接口阶梯）、`views`（播放上报）、`comment`（评论写入）、`like-toggle`（点赞净零写入）、`mixed`（读写混流）
- 成功判定**双口径**：HTTP 2xx **且** 业务 `code` 为 200/缺省，两者都记进 `statusCodes` 与 `appCodes`
- 单请求超时 20 s；百分位取 p50/p95/p99/max
- 自动停档：失败率 > 5% **或**出现任何 5xx → 不再往上加并发

### 3.2 护栏（本次新增宿主侧守卫）

发压器内部每 5 s 采样 `/proc/meminfo` 的 MemAvailable，并探测邻居金丝雀（lp `127.0.0.1:80`、hp `127.0.0.1:8081`），连续 2 次非 200 立即停发。

第二阶段额外加了宿主侧 [harness/guard.sh](performance-results/2026-11-07-load-test/harness/guard.sh)，每 3 s 采样并写 `STOP` 标志文件，发压器见到即停：

- 主机 MemAvailable < **140 MB**
- infra-mysql cgroup 内存 ≥ **92%**（288 MiB × 92% ≈ 265 MiB）
- infra-mysql `memory.oom_control` 的 `under_oom == 1`

守卫采样原始数据：[raw/guard.csv](performance-results/2026-11-07-load-test/raw/guard.csv)（240 个样本，00:00:31–00:13:20）。

### 3.3 写入数据打标与净零设计

- 探针账号：`pp20261107_1..4`，昵称"压测探针N"，密码统一，userId 7–10
- 评论内容全部带 ASCII 标记 `PP20261107`，便于精确删除
- **点赞用净零引擎**：(user, video) 组合按 worker 独占分配，POST/DELETE 严格交替 → 真实产生 insert + delete，但行数净增为零
- 回滚用**基线行数 + max(id) 水位线 + 逐视频计数快照**三重界定，见第 8 节

### 3.4 本次测试的局限（必须一起读）

1. **测的是缓存命中路径**。列表 L1 Caffeine 软过期 15 s（SWR 后台刷新）、硬过期 =  live `VIDEO_LIST_CACHE_TTL_SECONDS=45`，L2 Redis 同 TTL；热榜 L1 live `HOT_RANK_LOCAL_CACHE_MILLISECONDS=2000`。所以入口 ~2000 QPS **不代表 MySQL 侧能扛 2000 条 SQL/秒**——缓存命中时单请求 SQL 数接近 0，它代表的是"缓存命中时的 HTTP 服务上限"。冷缓存/缓存击穿场景本次未单独测。
2. **数据量极小**：19 个视频、6 条评论、2 个真实用户。索引与查询 plans 在这个量级下不构成压力，结果不能外推到万级数据。
3. **写入阶梯被护栏截断**：第二阶段每个写入档只跑了 **7–12 秒**就被守卫停发（原因见 6.2），因此写入侧只有"低并发可用 / 8 并发失败"两个点，**没有完整的写入崩塌曲线**。
4. **keyword 搜索分支未压测**：该分支是前置通配 LIKE，无法走索引，是已知的慢路径，本次只做了注入探测，没做吞吐阶梯。
5. 主机可用内存本身在 95–215 MB 之间自然震荡，140 MB 地板落在这个震荡带内，导致部分档位是"护栏中止"而非"打到错误"。这一点在解读写入结果时必须扣掉。

---

## 4. 只读接口阶梯结果

### 4.1 视频列表 `GET /api/videos?page=1&size=12`

| 并发 | QPS | p50 | p95 | p99 | max | 成功率 | 状态码 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8 | 1740.55 | 3.38 ms | 7.03 ms | 18.92 ms | 897 ms | 100% | 200 | |
| 16 | 1945.89 | 5.89 ms | 12.93 ms | 34.33 ms | 1498 ms | 100% | 200 | |
| 32 | 1713.98 | 11.28 ms | 26.24 ms | 106.07 ms | 2917 ms | 100% | 200 | |
| **64** | **2025.19** | 22.23 ms | 49.31 ms | 199.91 ms | 3762 ms | **100%** | 200 | **本次峰值** |
| 96 | 1619.07 | 32.04 ms | 112.32 ms | 573.63 ms | 4648 ms | 100% | 200 | 护栏中止：MemAvailable 89M < 90M |
| **128** | **1542.10** | 46.60 ms | 141.92 ms | 842.35 ms | 5369 ms | **100%** | 200 | **拐点：吞吐开始掉、p99 破 800 ms** |
| 192 | 309.31 | 79.18 ms | 897.34 ms | **21883.97 ms** | 22341 ms | 98.148% | 0:384 / 200:20355 | 崩塌 |
| 256 | 284.16 | 171.24 ms | 3262.21 ms | 19004.43 ms | 20882 ms | 99.988% | 0:2 / 200:16759 | |
| 320 | **21.32** | 20000.28 ms | 20871.82 ms | 20874.09 ms | 21062 ms | **46.465%** | 0:742 / 200:644 | 接近完全不可用 |

原始数据：[raw/list_a.json](performance-results/2026-11-07-load-test/raw/list_a.json)、[raw/list_b.json](performance-results/2026-11-07-load-test/raw/list_b.json)

**关键观察：从 8 到 320 并发，一个 5xx 都没有。** 742 个失败全是 `status=0`（客户端 20 s 超时）。live 配置 `TOMCAT_ACCEPT_COUNT=150`、`TOMCAT_MAX_CONNECTIONS=800`、`TOMCAT_MAX_THREADS=100`，意味着过载时请求先在 accept 队列里排到超时，而不是被立刻拒绝。对用户表现为"转圈 20 秒然后失败"，对运维表现为"没有错误日志可告警"。

### 4.2 热榜 `GET /api/videos/hot?limit=10`

| 并发 | QPS | p50 | p95 | p99 | 成功率 | 状态码 |
| --- | --- | --- | --- | --- | --- | --- |
| 16 | 906.56 | 5.29 ms | 17.84 ms | 157.17 ms | 100% | 200 |
| 64 | 1435.93 | 19.05 ms | 68.12 ms | 386.53 ms | 100% | 200 |
| **128** | **2076.80** | 49.58 ms | 99.33 ms | 160.02 ms | **100%** | 200 |
| 256 | 492.31 | 137.80 ms | 1247.31 ms | 10948.16 ms | 99.311% | 200:26515 / **502:184** |

原始数据：[raw/hot_a.json](performance-results/2026-11-07-load-test/raw/hot_a.json)

256 并发那 184 个 **502 来自 nginx**，时间点与 22:58:05 的主机全局 OOM 杀掉 vn-backend 重合（见第 7 节）——即"上游进程被内核杀掉"，不是应用主动返回的错误。

### 4.3 其他只读接口

| 接口 | 并发 | QPS | p50 | p95 | p99 | 成功率 | 状态码 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `GET /api/videos/4`（详情） | 64 | 454.64 | 83.75 ms | 261.81 ms | 901.13 ms | 100% | 200 |
| `GET /api/videos/4`（详情） | 256 | **54.00** | 398.29 ms | 20411.37 ms | 22225.34 ms | 95.946% | 0:69 / 200:1633 |
| `GET /api/categories` | 64 | 236.92 | 82.96 ms | 316.99 ms | 1660.24 ms | 100% | 200 |
| `GET /api/videos/4/comments?page=1&size=10` | 64 | 217.23 | 68.33 ms | 279.60 ms | 6512.90 ms | 98.93% | 0:47 / 200:6564 / **503:24** |
| `GET /api/videos/4/interaction`（带 token） | 16 | 605.05 | 15.12 ms | 32.30 ms | 59.27 ms | 100% | 200（7.3 s 后护栏中止） |

原始数据：[raw/detail.json](performance-results/2026-11-07-load-test/raw/detail.json)、[raw/detail_256.json](performance-results/2026-11-07-load-test/raw/detail_256.json)、[raw/categories.json](performance-results/2026-11-07-load-test/raw/categories.json)、[raw/comments_ro.json](performance-results/2026-11-07-load-test/raw/comments_ro.json)、[raw/interaction_auth.json](performance-results/2026-11-07-load-test/raw/interaction_auth.json)

两点值得单独拎出来：

- **详情接口在 256 并发下从 454 QPS 掉到 54 QPS（-88%）**，比列表路径脆弱得多。详情走的是 `videonest:video:detail:v2:*` 单视频缓存 + 作者/分类拼装，缓存粒度细、 miss 代价高。
- **评论列表那 24 个 503 是数据库访问失败**，不是缓存失败。日志证据（`/app/logs/demo.2026-11-07.0.log.gz`）：

  ```
  2026-11-07 23:02:11.020 ERROR [http-nio-8080-exec-85] c.v.c.exception.GlobalExceptionHandler - 数据库访问失败
  ```

  映射来自 [GlobalExceptionHandler.java:107](../backend/src/main/java/com/videonest/common/exception/GlobalExceptionHandler.java#L107) 的 `DataAccessException → 503`。**一个只读接口在 64 并发就把共享 MySQL 打到取不到连接**，这是本次最有价值的容量信号之一。

### 4.4 作废的测量

`interaction_ro`（[raw/interaction_ro.json](performance-results/2026-11-07-load-test/raw/interaction_ro.json)）21655 个请求全部 401 —— 发压器 `simple` 模式当时没带 Authorization 头，**该文件无效，不要引用**，已用 `interaction_auth` 重测。

`comment`（[raw/comment.json](performance-results/2026-11-07-load-test/raw/comment.json)）1390 个请求全部 500 —— **发压器自身的 bug**：`http.request` 的参数对象里 `...opts` 展开在 `headers` 之后，把合并好的头整个覆盖掉，导致评论 POST 只剩 `Authorization`、丢了 `Content-Type`，Spring 按 `application/octet-stream` 拒绝：

```
org.springframework.web.HttpMediaTypeNotSupportedException: Content-Type 'application/octet-stream' is not supported
```

这也解释了当时"评论阶梯跑完一条评论都没插进去"的异常。已修复（合并头放在展开之后 + 显式 `Content-Length`），修复后冒烟验证 5 条真实落库，见 5.2。**这不是后端缺陷。**

`smoke`（[raw/smoke.json](performance-results/2026-11-07-load-test/raw/smoke.json)）`requests=0` —— 发压器把 worker 函数本身而不是调用结果放进了 `Promise.all`（`mkWorker(w)` 而非 `mkWorker(w)()`），于是只有采样器在跑、没有任何请求发出。已修复为 `...Array.from({length: conc}, (_, w) => mkWorker(w)())`，重跑得到 `smoke2`（2982 请求 / 288 QPS / 100% 成功）。**smoke.json 无效，不要引用。**

---

## 5. 写入与读写混流结果

### 5.1 点赞（净零写入引擎）

| 轮次 | 并发 | 时长 | 请求 | QPS | p50 | p95 | p99 | 成功率 | 状态码 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 23:08（主机内存吃紧期） | 8 | 61.6 s | 178 | **2.89** | 418.82 ms | 14402 ms | 24662 ms | **79.213%** | 200:141 / **500:33** / 0:4 |
| 00:01（MySQL 重启后） | 4 | 12.1 s | 517 | **42.77** | 49.20 ms | 329.00 ms | 746.37 ms | **100%** | 200:517（护栏中止） |

原始数据：[raw/like_toggle.json](performance-results/2026-11-07-load-test/raw/like_toggle.json)、[raw/like2.json](performance-results/2026-11-07-load-test/raw/like2.json)

**8 并发那 33 个 500 的根因链**（取自持久日志 `/app/logs/demo.log`，docker json-file 日志因 10m×2 轮转已丢失该窗口）：

```
CannotCreateTransactionException: Could not open JDBC Connection for transaction
  ← CJCommunicationsException: Communications link failure        ×33
  ← java.net.SocketException: Connection attempt exceeded defined timeout.  ×20
  ← java.net.UnknownHostException: mysql                          ×9
  ← SQLTransientConnectionException: HikariPool-1 - Connection is not available,
     request timed out after 2000ms (total=5, active=5, idle=0, waiting=3)
```

同时 `videonest-redis-lock-watchdog-1` 定时任务也在报 `OutboxEventMapper.recoverStaleProcessing` 取不到连接。

解读：live Hikari 是 `DB_POOL_MAX_SIZE=16 / MIN_IDLE=4 / connection-timeout=2000ms`，但报错时池里 **total=5**——说明不是"16 个连接都被占满"，而是**连物理连接都建不起来**，连 DNS 解析 `mysql` 都失败了。这是主机内存耗尽 + swap 抖动时 docker 网络/解析栈一起劣化的表现，与第 7 节的 OOM 时间线吻合。

**结论：写入路径的实际可用并发在这台机器上是 4，8 就会失败。** 而且失败方式是 500（事务开不起来），不是优雅降级。

### 5.2 评论写入 `POST /api/videos/{id}/comments`

| 轮次 | 并发 | 时长 | 请求 | 真实落库 (200) | 限流拒绝 (429) | QPS | p50 | p95 | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 冒烟（修复验证） | 1 | 10.2 s | 911 | **5** | 906 | 89.75 | 3.64 ms | 16.66 ms | 32.82 ms |
| 正式 | 4 | 12.5 s | 3769 | **20** | 3749 | 301.18 | 6.64 ms | 32.88 ms | 67.22 ms |

原始数据：[raw/comment_smoke.json](performance-results/2026-11-07-load-test/raw/comment_smoke.json)、[raw/comment2.json](performance-results/2026-11-07-load-test/raw/comment2.json)

**20 = 4 个探针用户 × 5 条/分钟，一条不多一条不少。** 限流常量在 [CommentServiceImpl.java:55](../backend/src/main/java/com/videonest/module/interaction/service/impl/CommentServiceImpl.java#L55)（`COMMENT_LIMIT = 5`、`COMMENT_LIMIT_SECONDS = 60`），实现是 Redis `INCR` + 首次 `EXPIRE`。

因此：**评论接口的"并发极限"由业务限流决定，压测测不到它的存储写入上限**。能测到的是限流拒绝路径的吞吐——4 并发下 301 QPS、p50 6.6 ms，说明拒绝路径很便宜。要测真实评论写入容量，必须先临时调高限流常量或用更多真实账号，本次未做（不改生产配置是既定约束）。

### 5.3 播放上报 `POST /api/videos/{id}/views`

| 并发 | 时长 | 请求 | 计入播放 (200) | 限流拒绝 (429) | QPS | p50 | p95 | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8 | 11.7 s | 9474 | **30** | 9444 | 812.36 | 4.96 ms | 21.82 ms | 108.17 ms |

原始数据：[raw/views2.json](performance-results/2026-11-07-load-test/raw/views2.json)

**30 恰好等于匿名播放限流 `anonymous-limit-per-minute=30`**（[VideoViewCountServiceImpl.java:153](../backend/src/main/java/com/videonest/module/video/service/impl/VideoViewCountServiceImpl.java#L153)）。loopback 发压在应用看来是同一个 IP（nginx 容器网关 `172.20.0.1`），所以 30 次之后全部 429。去重窗口 `dedup-window-seconds=1800` 也在生效。

拒绝路径 812 QPS / p50 4.96 ms —— Lua 脚本 + Redis 计数很轻。

### 5.4 读写混流

权重：热榜 25% / 列表 25% / 分类 10% / 评论列表 10% / 互动状态(带 token) 10% / 播放上报 20%。

| 并发 | 时长 | 请求 | 200 | 429 | QPS | p50 | p95 | p99 | 成功率 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 16 | 7.2 s | 4639 | 3761 | 878 | 647.01 | 11.99 ms | 41.58 ms | 277.09 ms | 81.074% |

原始数据：[raw/mixed2.json](performance-results/2026-11-07-load-test/raw/mixed2.json)

878 个 429 全部来自播放上报分支（占 20% 权重，符合 30/min 的硬帽）。**扣掉限流拒绝后，混流里没有一个是服务端错误**——16 并发下读路径完全健康，是宿主守卫在 7.2 s 时因为主机可用内存跌到 106 MB 主动停的发压。

### 5.5 业务限流汇总（全部实测确认生效）

| 接口 | 限流 | 配置位置 | 实测 |
| --- | --- | --- | --- |
| 登录 | 20 次 / 60 s / IP | [application.yml:106](../backend/src/main/resources/application.yml#L106) | 26 次错误密码 → 20×401 + 6×429 ✅ |
| 注册 | 5 次 / 3600 s / IP | [application.yml:108](../backend/src/main/resources/application.yml#L108) | 建号阶段确认，重复运行需先清残留计数 |
| 评论 | 5 条 / 60 s / 用户 | CommentServiceImpl.java:55 | 4 用户 → 恰好 20×200，其余 3749×429 ✅ |
| 匿名播放 | 30 次 / 60 s / IP | VideoViewCountServiceImpl.java:153 | 恰好 30×200，其余 9444×429 ✅ |
| 播放去重 | 1800 s / (用户或IP哈希, 视频) | VideoViewCountServiceImpl.java:150 | 生效 |

**但这些限流全部可以被 XFF 伪造绕过，见 6.1。**

---

## 6. 安全测试结果

完整逐条结果：[raw/security.jsonl](performance-results/2026-11-07-load-test/raw/security.jsonl)（47 条），探针脚本 [harness/secprobe.js](performance-results/2026-11-07-load-test/harness/secprobe.js)。

### 6.1 高危：认证限流可被 `X-Forwarded-For` 伪造完全绕过

| 用例 | 结果 |
| --- | --- |
| 26 次错误密码登录，**同一 IP** | `401 × 20`，`429 × 6` → 限流生效 |
| 26 次错误密码登录，**每次伪造不同 `X-Forwarded-For: 203.0.113.N`** | **`401 × 26`，`429 × 0`** → **限流完全失效** |

机制（三个环节共同造成）：

1. nginx 用的是 **追加**而不是覆盖：[deploy/server/nginx.conf:71](../deploy/server/nginx.conf#L71) `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` → 客户端自带的 XFF 被保留在最左边，最终头部形如 `203.0.113.7, 172.20.0.1`
2. 后端 [application.yml:4](../backend/src/main/resources/application.yml#L4) `server.forward-headers-strategy: native` → 启用 Tomcat `RemoteIpValve`
3. `RemoteIpValve` 从右往左剥掉落在 `internalProxies` 默认网段里的地址（`172.20.0.1` 属于 172.16/12，被信任），然后**把下一个地址当作真实客户端 IP**——那正是攻击者填的 `203.0.113.7`

限流键是 `videonest:rate-limit:auth:<action>:<sha256(remoteAddr)>`（[AuthEndpointRateLimitFilter.java](../backend/src/main/java/com/videonest/security/AuthEndpointRateLimitFilter.java)），IP 一换键就换，计数永远回到 1。

**影响**：无限次密码暴力破解、无限批量注册；反过来也可以伪造受害者 IP 把对方的登录/注册限流打满（拒绝服务）。

**最小修复**（公网入口只有 nginx 一跳，没有可信上游代理链）：把 nginx 那行改成**覆盖**

```nginx
proxy_set_header X-Forwarded-For $remote_addr;
```

这样客户端自带的 XFF 直接被丢弃。若将来前面还要加 CDN，则改为在 backend 显式配置 `server.tomcat.remoteip.internal-proxies` 只信任真实 nginx 地址，并由 nginx 统一重写 XFF。**本次未改任何配置**（约束：不动生产配置），仅报告。

### 6.2 假阳性澄清：actuator 与 /minio/ 并没有暴露

探针里 `/actuator`、`/actuator/env`、`/actuator/mappings`、`/actuator/beans`、`/actuator/configprops`、`/actuator/prometheus`、`/minio/` 全部返回 200，**乍看像重大暴露，实际是 SPA 兜底**。逐个复核内容类型与字节数：

| 路径（经 8082 公网入口） | HTTP | Content-Type | 字节 | 实际内容 |
| --- | --- | --- | --- | --- |
| `/actuator/env` 等 6 个 | 200 | `text/html` | 788 | 前端 `index.html` |
| `/minio/` | 200 | `text/html` | 788 | 前端 `index.html` |

nginx 只把 `^~ /api/` 转发给后端，其余路径落到前端静态兜底，所以任何不存在的路径都是 200 + 788 B 的 HTML。**判定暴露必须看 Content-Type 和响应体，不能只看状态码。**

直连后端 `127.0.0.1:8080`（该端口只绑定 loopback，公网不可达）复核：

| 路径 | HTTP | 说明 |
| --- | --- | --- |
| `/actuator/env` | **401** | 已鉴权，未暴露 ✅ |
| `/actuator/health` | 200 | 设计如此，只返回 `{"status":"UP"}`，`show-details: when-authorized` ✅ |
| `/actuator/prometheus` | **200，554 KB 指标，无鉴权** | `SecurityConfig` 里 permitAll；当前仅 loopback 可达，**一旦有人把它代理到公网就是完整指标泄露** |

live 暴露白名单是 `health,info,prometheus,metrics`（[application.yml:202](../backend/src/main/resources/application.yml#L202)），`env/mappings/beans/configprops` 根本没开。

**建议**：把 `/actuator/prometheus` 从 permitAll 收紧为内网/鉴权访问，成本几乎为零；现在它没暴露只是因为端口绑定运气好。

### 6.3 通过的项

| 类别 | 用例 | 结果 |
| --- | --- | --- |
|  admin 越权 | `/api/admin/videos/pending`、`/api/admin/users`、`/api/admin/comments`、`/api/admin/videos/4/review` 无 token | 全部 **401** ✅ |
| 角色隔离 | 普通用户 token 访问 `/api/admin/users` | **403** ✅ |
| 创作者/文件 | `/api/creator/videos`、`/api/files/presign` 无 token | **401** ✅ |
| JWT 篡改 | 改 `role=ADMIN` 保留签名 / 换 `userId` 保留签名 / 截断 / `alg=none` | 全部 **401** ✅ |
| SQL 注入 | keyword 单引号、恒真注释、分号堆叠、UNION SELECT username,password | 全部 **200 正常空结果**，注入后 `video` 表存活检查 200 ✅ |
| 注入（类型校验） | `categoryId` 注入 | **400** ✅ |
| 参数校验 | 视频 id=0 / -1 / 超大整数 / 非数字；列表 page=0 / size=100000 / 负 size；热榜 limit=500；评论 page 非数字 | 全部 **400** ✅ |
| CORS | 陌生 Origin / 恶意预检 | **403** ✅ |
| CORS | 合法 Origin | **200** ✅ |
| 方法越权 | 未授权 PUT 改视频 / DELETE 删视频 | **401** ✅ |
| 错误响应 | 登录失败响应体 | 不回显密码、不回显堆栈 ✅ |

CORS live 白名单已包含公网入口与域名，**之前怀疑的 CORS 配置缺口不存在**：

```
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://127.0.0.1:5173,
                     http://101.201.208.223:8082,http://vnest.xyz,http://www.vnest.xyz
```

实现用 `setAllowedOrigins`（显式列表，非通配）+ `setAllowCredentials(true)`，组合是安全的（[SecurityConfig.java:177](../backend/src/main/java/com/videonest/config/SecurityConfig.java#L177)）。

### 6.4 未能运行时验证 / 低危项

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 越权删评论（IDOR） | **运行时跳过，代码核验通过** | 探针在评论列表里没找到属于第二个探针账号的评论（评论写入按视频轮转，分布不重合），用例自动跳过。改为代码核验：[CommentServiceImpl.java:292](../backend/src/main/java/com/videonest/module/interaction/service/impl/CommentServiceImpl.java#L292) 用 `softDeleteByIdAndUserId(commentId, currentUser.userId())` 把归属条件写进 SQL，`rows == 0` 时抛 404「评论不存在，或你无权删除该评论」→ **归属校验在 SQL 层，不存在先查后删的竞态窗口** ✅ |
| 账号锁定 | **不存在该机制** | auth 模块内没有任何失败计数/锁定逻辑。探针末次"正确密码登录"返回 429，是 IP 限流窗口未过（探针没等满 60 s），**不是账号被锁**。结合 6.1，攻击者可以无限试密码而永远不会触发锁定 |
| 用户名泄露 | **低危** | `GET /api/videos/4` 响应里带 `authorUsername":"admin"`，可用于用户名枚举。媒体地址本身是公网 `http://101.201.208.223:9010/...`，**未泄露内网 host**（内部 `MINIO_ENDPOINT=http://minio:9000` 只用于服务端） |
| TRACE 方法 | 未判定 | 探针 fetch 报错（`FETCH_ERROR`），Node fetch 不支持 TRACE，非后端问题 |
| 端口暴露 | 通过 | 3306/5432/6379/9011/8080/5672/15672/443 公网均不可达；80/8081/8082/9010 按设计开放 |

---

## 7. 事故与资源时间线（如实记录）

主机：Alibaba Cloud ECS，2 核，**MemTotal 1870 MB**，swap 约 2 GB。三套 demo（videonest / learningplatform / heart-pilot）共用一套 infra。

### 7.1 内核 OOM 记录（`dmesg -T`）

| 时间 | 约束类型 | 被杀进程 | 归属容器 | 归因 |
| --- | --- | --- | --- | --- |
| 22:12:54 | `CONSTRAINT_NONE` / global_oom | java，anon-rss 390 MB，uid 100 | hp-backend | **早于本次首次发压（22:36），与压测无关** |
| 22:55:41 | `CONSTRAINT_NONE` / global_oom | java，anon-rss 397 MB，uid 100 | **hp-backend**（`0054aa5162b8`） | 落在压测窗口内，但该容器 **RestartCount=157**，本来就是崩溃循环，无法把这一次单独归因于压测 |
| 22:58:05 | `CONSTRAINT_NONE` / global_oom | java，total-vm 876 MB，anon-rss 395 MB，uid 0 | **vn-backend**（`2fa9abf6319f`） | **本次压测直接导致**；容器 22:58:26 重启，RestartCount=1 |
| 23:53:38 | **`CONSTRAINT_MEMCG`** | **mysqld**，uid 999 | **infra-mysql**（`5307f193e892`） | 触发时**已无压测流量**（最后一次发压 23:12，回滚 23:43 结束）；MySQL 23:53:46 重启并执行 `XA crash recovery`，RestartCount=1 |

容器 ID 归属已逐个用 `docker inspect --format '{{.Id}}'` 核对，不是推测。

### 7.2 infra-mysql 的内存配额本身就偏紧

live 配置与实测：

| 项 | 值 |
| --- | --- |
| cgroup 上限 | **288 MiB**（`mem_limit=301989888`） |
| `innodb_buffer_pool_size` | 96 MiB |
| **`innodb_log_buffer_size`** | **64 MiB** |
| `performance_schema` | OFF |
| `max_connections` | 120 |
| 空载实测占用（23:54，重启后 1 分钟） | **228.9 MiB = 79%** |
| 空载实测占用（00:15，回滚后） | **257 MiB = 89%** |
| 第二阶段守卫采样 p50 / p90 | **89% / 93%** |
| 采样中 ≥92%（守卫阈值）的次数 | **53 / 240** |

96 + 64 = 160 MiB 是两块固定缓冲，加 mysqld 自身开销约 70 MiB，**空载就吃掉 79–89%**，留给连接缓冲/临时表的余量只有 20–30 MiB。这就是为什么 23:53 空载也会 OOM，也是为什么写入阶梯在 4–16 并发就被守卫拦下。

**`innodb_log_buffer_size=64MiB` 对一个 288 MiB 的容器明显过大**，降到 16 MiB 可直接腾出 48 MiB（约 17% 的配额），是成本最低的一处修改。本次未改（约束：不动 infra 配置）。

### 7.3 守卫触发统计

第二阶段 240 个样本里 **84 个处于 STOP 状态**：主机内存低于 140 MB 共 36 次，MySQL cgroup ≥92% 共 48 次。主机 MemAvailable 分布：min 95 / p10 130 / p50 167 / p90 193 MB。

**140 MB 地板落在主机自然震荡带（95–215 MB）之内**，所以部分停发是"地板偏保守"而不是"服务真的到了极限"。但 MySQL cgroup 那 48 次是独立的硬信号——**数据库侧确实贴着悬崖**，两条护栏一起说明这台机器没有写入余量。

### 7.4 最终状态（00:15 复核）

```
lp-frontend    Up 2 hours          lp-backend     Up 2 hours (healthy)   RestartCount=0
vn-frontend    Up 2 hours          vn-backend     Up 1 hour  (healthy)   RestartCount=1
hp-frontend    Up 2 days           hp-backend     Up 1 hour  (healthy)   RestartCount=157
infra-mysql    Up 25 min (healthy) RestartCount=1     infra-redis  Up 3 days (healthy) RestartCount=0
infra-postgres Up 3 days (healthy)   infra-minio  Up 3 days (healthy)   vn-rabbitmq Up 3 days (healthy)
MemAvailable: 166 MB
```

**lp-backend 与 infra-redis 全程零重启**，邻居金丝雀在全部 read 档位里保持 200（唯一一次非 200 出现在 hot_a 256 并发末尾，与 vn-backend 被 OOM 杀掉同时）。未停止任何其他进程。

### 7.5 另一处仍未部署的既有风险

infra-redis 的 live 启动参数仍是 `--maxmemory 48mb --maxmemory-policy allkeys-lru`（用 `docker inspect --format '{{join .Config.Cmd " "}}'` 核对）。**之前讨论过的 `noeviction` 修复没有上线**。在 allkeys-lru 下，转码分布式锁键可能被当作普通缓存淘汰，导致并发转码互斥失效。本次压测未覆盖转码路径，但该风险仍然活着。

---

## 8. 数据回滚与基线核对

回滚脚本 [harness/cleanup.sh](performance-results/2026-11-07-load-test/harness/cleanup.sh)，计数还原 [harness/restore-counters.sql](performance-results/2026-11-07-load-test/harness/restore-counters.sql)，完整执行日志 [raw/cleanup.log](performance-results/2026-11-07-load-test/raw/cleanup.log)。共执行两次（23:43 一次、00:05–00:13 一次），两次均核对通过。

### 8.1 删除前清点（00:05）

```
probe user ids: 7,8,9,10
sys_user=6  video_like=8  video_favorite=3  video_comment=31  user_follow=1
notification=291  outbox_event=418
探针拥有: like=1 favorite=0 comment=25 follow=0
探针为发起者的通知=282   基线之后新增的通知=282   基线之后新增的 outbox=282
```

通知与 outbox 的新增量完全相等（282 = 282），说明**每一条探针通知都恰好对应一条 outbox 事件，没有漏派也没有重复**。

### 8.2 回滚后逐表核对（00:13）

| 表 | 基线 | 回滚后 | 结论 |
| --- | --- | --- | --- |
| `sys_user` | 2 | **2** | ✅ |
| `video` | 19 | **19** | ✅ |
| `video_comment` | 6 | **6** | ✅ |
| `video_like` | 7 | **7** | ✅ |
| `video_favorite` | 3 | **3** | ✅ |
| `user_follow` | 1 | **1** | ✅ |
| `notification` | 9 | **9** | ✅ |
| `outbox_event` | 136 | **136** | ✅ |
| `upload_multipart_session` | 0 | **0** | ✅ |
| `password_reset_request` | 0 | **0** | ✅ |
| `dead_letter_record` | 0 | **0** | ✅ |
| `video_category` | 6 | **6** | ✅ |

水位线与残留：

```
残留探针检查: 0 个探针账号 / 0 条探针评论
notification max(id)=9   (基线 9)   ✅
outbox_event max(id)=136 (基线 136) ✅
```

评论用的是雪花 ID（如 `2119097398800224257`）而非自增，所以水位线法对评论不适用，改用 `user_id IN (探针)` + `content LIKE '%PP20261107%'` 双条件精确删除，残留检查为 0。

### 8.3 视频计数还原（19 行逐个 UPDATE，非重算）

```
3 view=75 like=9 fav=2      12 view=0 like=0 fav=0     18 view=1 like=0 fav=0
4 view=1  like=0 fav=0      13 view=2 like=0 fav=0     19 view=2 like=1 fav=0
5 view=6  like=1 fav=0      14 view=4 like=1 fav=0     20 view=3 like=2 fav=2
6 view=3  like=0 fav=0      15 view=2 like=0 fav=0     21 view=3 like=1 fav=0
7 view=4  like=0 fav=0      16 view=1 like=1 fav=1
8 view=0  like=0 fav=0      17 view=1 like=0 fav=0
9 view=0  like=0 fav=0      10 view=2 like=0 fav=0     11 view=0 like=0 fav=0
```

全部等于压测前快照。

### 8.4 Redis 清理

| 键前缀 | 命中并删除 |
| --- | --- |
| `videonest:video:view:dedup:*` | 4 |
| `videonest:video:view:total:*` | 18 |
| `videonest:video:view:anonymous-rate:*` | 0（窗口已自然过期） |
| `videonest:video:view:delta:*` | 0 |
| `videonest:comment:limit:*` | 0（窗口已自然过期） |
| `videonest:rate-limit:auth:*` | 1（安全探针的失败登录计数） |
| `videonest:video:view:dirty` | 删除 |

随后主动失效读缓存（详情 / 评论首屏 / 热榜）并 `INCR videonest:video:list:ver:v3`（1 → 2），等 60 s 让 JVM 本地缓存过期。最终 `videonest:*` 键总数 **53**（基线 48，差额是后端自行重建的读缓存，属正常）。

**说明**：第二阶段建号前额外删过一次 `videonest:rate-limit:auth:*`，那是第一轮探针注册留下的计数残留（否则 5 次/小时会挡住重新建号）。删的是本次压测自己产生的键，不涉及真实用户数据；该操作已在此披露。

### 8.5 回滚后对外接口抽样

```
GET /api/videos?page=1&size=12          -> 200  0.640 s（冷缓存重建）
GET /api/videos/hot?limit=10            -> 200  0.018 s
GET /api/videos/4                       -> 200  0.035 s
GET /api/videos/4/comments?page=1&size=10 -> 200  0.012 s
```

---

## 9. 建议（按优先级，含代价）

| 优先级 | 问题 | 建议 | 代价 / 风险 |
| --- | --- | --- | --- |
| **P0** | XFF 伪造绕过认证限流（6.1） | nginx 改 `proxy_set_header X-Forwarded-For $remote_addr;`（覆盖而非追加） | 一行配置 + `nginx -s reload`，无重启；若将来加 CDN 需同步调整 `remoteip.internal-proxies` |
| **P0** | infra-mysql 288 MiB 配额空载即 79–89%（7.2） | 先把 `innodb_log_buffer_size` 从 64 MiB 降到 16 MiB，腾出 48 MiB；再评估是否把 cgroup 上限提到 384 MiB | 前者需重启 MySQL（共享 infra，影响三套 demo，须单独授权）；后者受主机 1.87 GB 总内存制约，不能无限加 |
| **P1** | 过载零 5xx、全部排队到 20 s 超时（4.1） | 降低 `TOMCAT_ACCEPT_COUNT`（live 150）到接近 max-threads 的量级，让过载**快速失败**而不是排长队；配合前端重试退避 | 改 env + 重启 vn-backend；副作用是过载时错误率上升（但这是正确的可观测信号） |
| **P1** | 写入 8 并发即 MySQL 连接失败（5.1） | Hikari `connection-timeout=2000ms` 偏短，建连失败直接 500。考虑对 `CannotCreateTransactionException` 做一次短重试，或返回 503 + Retry-After 而不是 500 | 代码改动；重试会放大压力，须与 P0 内存修复一起做，单独做重试可能更糟 |
| **P1** | infra-redis 仍是 `allkeys-lru`（7.5） | 部署既有的 `noeviction` 修复，保护转码锁键 | 需重启共享 Redis；`noeviction` 下内存满会写入报错，须同时确认 48 MB 够用 |
| **P2** | `/actuator/prometheus` 无鉴权（6.2） | 从 `permitAll` 收紧为内网或鉴权访问 | 一处 SecurityConfig 改动；当前仅 loopback 可达，不紧急 |
| **P2** | 详情接口 256 并发掉 88%（4.3） | 详情缓存粒度细、miss 代价高，考虑批量预热或合并作者/分类查询 | 需要先做一次冷缓存专项压测定位，本次数据不足以直接下结论 |
| **P2** | 无账号锁定机制（6.4） | 在 IP 限流之外增加按用户名的失败计数 + 递增延迟 | 修完 P0 之后再做才有意义，否则锁定同样能被 XFF 绕过的 IP 维度规避 |
| **P3** | 详情响应泄露 `authorUsername`（6.4） | 对外 VO 去掉 username，只留 nickname | 前端若用到该字段需同步改 |

**不建议现在做的事**：继续优化列表 SQL / 加索引。本次数据表明列表路径在缓存命中时能到 2025 QPS、128 并发仍 100% 成功，瓶颈明确在主机内存与共享 MySQL 配额，不在查询。历史"~127 QPS 查询路径天花板"的归因已在第 2 节撤回。

---

## 10. 复现方式与原始数据索引

### 10.1 目录

```
docs/performance-results/2026-11-07-load-test/
├── summary.txt          一屏表格（28 个档位）
├── summary.csv          同上，机器可读
├── raw/                 全部原始 JSON / JSONL / CSV / 日志
│   ├── list_a.json list_b.json hot_a.json detail.json detail_256.json
│   ├── categories.json comments_ro.json interaction_auth.json
│   ├── interaction_ro.json  ← 作废（未带 token，全 401）
│   ├── comment.json         ← 作废（发压器 Content-Type bug，全 500）
│   ├── like_toggle.json like2.json comment_smoke.json comment2.json
│   ├── views2.json mixed2.json smoke2.json
│   ├── smoke.json           ← 作废（worker 未调用，0 请求）
│   ├── security.jsonl       47 条安全用例
│   ├── guard.csv            240 个宿主守卫样本
│   ├── server-samples.csv server-samples2.csv   主机/容器采样
│   ├── cleanup.log          最终回滚全过程
│   └── orch.log seq_e.log guard.log             编排日志
└── harness/             14 个脚本，可完整复现
    ├── perfload.js      发压器（5 种模式 + 内置护栏）
    ├── guard.sh         宿主侧守卫（主机内存 + MySQL cgroup）
    ├── bootstrap.js     探针建号 + 登录取 JWT
    ├── secprobe.js      安全探针
    ├── cleanup.sh       回滚 + 基线核对
    ├── restore-counters.sql  19 行视频计数还原
    ├── run_all.sh run_e1.sh run_e2.sh sequence_e.sh pre_smoke.sh
    ├── monitor.sh monitor2.sh   主机/容器采样器（分别产出两份 server-samples）
    └── summarize.js     结果汇总
```

`raw/` 全部 26 个文件已逐个 md5 核对，与服务器 `/tmp/vnperf-20261107/` 上的原件**字节一致**后才删除原件。

### 10.2 复现要点

```bash
# 全部在服务器本机跑，不要从公网发压（否则测到的是带宽）
D=/tmp/vnperf-20261107
docker run --rm --memory 128m --memory-swap 192m --network host -v $D:/w \
  node:24-alpine node /w/perfload.js \
  --mode=simple --label=list --path='/api/videos?page=1&size=12' \
  --ladder=8,16,32,64 --secs=30 --min-free-mb=140 --stop-file=/w/STOP --out=/w/results
```

- 写入类必须先 `bootstrap.js` 建探针账号，结束后**必须**跑 `cleanup.sh`
- `guard.sh` 要在宿主上跑（容器内看不到兄弟 cgroup），它写的 `STOP` 文件通过 `-v $D:/w` 传给发压器
- 汇总：`node harness/summarize.js raw/`

### 10.3 测试侧产物清理（00:29 执行并核对）

- 服务器 `/tmp/vnperf-20261107/` 已删除（含探针 JWT 的 `tokens.json`），**JWT 不进仓库**；`find /tmp -name tokens.json` 返回空
- 本地 `.perf-scratch/` 已删除，14 个脚本副本保留在 `harness/`（删除前逐个 md5 核对一致）
- 宿主守卫进程 pid 2931871 已确认不存在，`docker ps -a --filter ancestor=node:24-alpine` 为空，无遗留发压容器
- 交付物已扫描：无 JWT、无 bcrypt 哈希、无邮箱/手机号；`MYSQL_ROOT_PASSWORD` 全部运行时从容器 env 读取，脚本里没有硬编码凭据。唯一的字面口令是探针账号口令 `PerfProbe!20261107`，这些账号已在回滚中删除
- 11 个业务/infra 容器全部 Up，MemAvailable 160 MB，未停止任何其他进程

---

## 11. 未覆盖项

| 项 | 原因 |
| --- | --- |
| 上传 / 转码链路 | 写入强度受 MySQL 内存配额限制，转码会长时间占用 ffmpeg 与磁盘，风险超出本次授权范围 |
| keyword 搜索吞吐 | 前置通配 LIKE 无法走索引，是已知慢路径；本次只做注入探测 |
| 冷缓存 / 缓存击穿 | 需要主动清空缓存后瞬时打满，会直接冲击共享 MySQL，本次未做 |
| 关注 / 收藏写入 | 与点赞同属 InteractionService，点赞已暴露连接层瓶颈；未单独跑阶梯 |
| 完整写入崩塌曲线 | 每档 7–12 s 即被守卫停发，只有 4 并发（可用）与 8 并发（失败）两个点 |
| IDOR 运行时验证 | 探针评论分布不重合导致用例跳过，已改代码核验（6.4） |
| 公网入口容量 | 本次刻意只测 loopback；公网入口受 ~417 KB/s 上行限制，播放并发约 3 人即满，属带宽问题而非服务问题 |