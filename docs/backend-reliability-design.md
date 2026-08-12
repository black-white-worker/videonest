# VideoNest 后端可靠性边界

## HTTP 与认证

- 业务错误同时使用真实 HTTP 状态码和统一的 `ApiResponse` 响应体。
- Spring Security 的未登录与无权限响应分别为 JSON 格式的 HTTP 401 和 403。
- 登录、注册按客户端 IP 使用 Redis Lua 原子计数限流。Redis 故障时限流降级为放行并记录错误日志，避免认证入口整体不可用。
- JWT 包含唯一 `jti`。退出登录后，`jti` 会按令牌剩余有效期写入 Redis 黑名单。黑名单检查故障时认证失败关闭，避免已撤销令牌被接受。
- JWT 仍是无状态访问令牌：修改密码、封禁用户后的全部历史令牌不会自动批量失效。如需该能力，可在用户表增加 `token_version` 并在认证时校验。

## 数据库迁移

- Flyway 是数据库结构版本的唯一执行入口，迁移文件位于 `backend/src/main/resources/db/migration`。
- 全新空数据库依次执行 V1 到最新版本。
- 已有且没有 Flyway 历史表的旧数据库以 V6 建立基线，再执行 V7 及之后的迁移。部署前必须确认旧数据库已经执行原有的 V1 到 V6 SQL。
- Docker Compose 不再把 SQL 文件挂载到 MySQL 初始化目录，避免“仅首次初始化执行”和重复加列造成的环境差异。
- `sql` 目录保留为历史学习与人工部署参考，后续结构变更必须新增 Flyway 版本，不能修改已经部署过的迁移文件。

## RabbitMQ 一致性与 Outbox

核心的视频转码事件和站内通知事件采用 Transactional Outbox：

1. 业务数据和 `outbox_event` 在同一个 MySQL 事务中提交。
2. 后台调度器竞争领取待发送事件并投递 RabbitMQ。
3. 收到 Broker Confirm 后标记 `SENT`；失败按指数退避重试。
4. 发送进程中断超过五分钟的 `PROCESSING` 记录会被自动恢复。
5. MQ 可能在“Broker 已收到、数据库尚未标记 SENT”时重复投递，因此消费者仍必须依靠业务唯一键保持幂等。

这解决了原先“数据库已提交、进程却在发送 MQ 前崩溃”的丢消息窗口，但不承诺严格 exactly-once。

审核超时和资源延迟清理仍依赖 RabbitMQ 延迟交换机，并保留死信记录和人工重投机制。它们当前没有纳入通用 Outbox 的延迟投递模型；这是已知边界，不应宣称所有消息都具备完全相同的一致性保证。

## 测试边界

- 普通单元测试不依赖本机 MySQL、Redis 或 RabbitMQ。
- Testcontainers 测试会在 Docker 可用时启动 MySQL 8.4，验证 Flyway 能从空库迁移到当前版本。
- Docker 不可用时容器测试会明确标记为 skipped，不能把 skipped 当作迁移已经通过。
