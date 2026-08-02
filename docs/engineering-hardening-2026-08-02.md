# VideoNest 缓存、热榜、播放与上传加固

## 关键链路

### 视频详情缓存

- 正常数据缓存 25–35 分钟随机 TTL，分散集中失效时间。
- 不存在的视频写入 2 分钟空值，阻断重复穿透。
- 热点未命中使用 5 秒带所有者令牌的 Redis 互斥锁；等待者短暂重读，Redis 故障时降级查库。
- 解锁由 Lua 比较令牌后删除，避免误删已续任的新锁。

### 播放和热榜

- 获取详情不再增加播放量。
- 前端累计实际播放 5 秒后调用 `POST /api/videos/{id}/views`。
- 登录用户按 `用户 + 视频 + 30 分钟窗口` 去重；匿名用户按 `IP + 视频 + 30 分钟窗口` 去重，并限制每个 IP 每分钟 30 次上报。
- 去重、匿名限频、总量与待刷盘增量在一段 Redis Lua 中原子完成。
- 热度写入小时 ZSet 桶，读取最近 24 桶，并按 6 小时半衰期做指数衰减：`weight = exp(-ln(2) * ageHours / 6)`。
- Redis 热榜不可用或尚无数据时，回退为最新发布视频。

### MinIO 直传和上传安全

1. 前端向 `POST /api/files/presign` 提交文件名、声明 MIME 和大小。
2. 后端签发 15 分钟 MinIO PUT URL，并在 Redis 保存绑定用户、对象名、类型和声明大小的票据。
3. 浏览器直接 PUT 到 MinIO 的 `staging/` 隔离前缀，不再经过 Spring Boot 转发文件内容。
4. 前端调用 `POST /api/files/uploads/{uploadId}/complete`。
5. 后端读取 MinIO 的真实对象大小，下载到临时隔离文件，执行魔数识别、图片像素限制或 ffprobe 媒体探测，并按配置调用 ClamAV。
6. 校验通过后由 MinIO 服务端复制到正式 `video/` 或 `cover/` 前缀并删除隔离对象；未确认对象由 1 天生命周期规则兜底清理。
7. 只有确认完成且属于当前用户的正式对象才能用于创建稿件；稿件继续经过转码和人工审核。

生产环境建议设置：

```dotenv
ANTIVIRUS_COMMAND=clamscan
ANTIVIRUS_REQUIRED=true
```

`ANTIVIRUS_REQUIRED=true` 时，扫描器缺失或故障会失败关闭，不会让未扫描对象进入稿件链路。MinIO CORS 规则位于 `deploy/minio/cors.xml`，部署到非本地域名时需补充实际前端 Origin。

### 查询和索引

- 一级评论分页改用一次 SQL 联查用户并聚合回复数，移除逐条查询用户和回复数的 N+1。
- 新迁移 `sql/2026-08-02-hot-upload-query-hardening.sql` 覆盖发布流、分区流、作者页、评论/回复和我的点赞/收藏分页。
- `original_video_url` 增加唯一索引，阻止同一个已确认上传对象被并发创建成多份稿件。

## 上线顺序

1. 对现有数据库执行新增 SQL 迁移。
2. 配置 MinIO 公网地址和 CORS Origin；确认浏览器能访问预签名 URL。
3. 安装并更新 ClamAV 病毒库，先设置扫描命令验证，再开启 `ANTIVIRUS_REQUIRED=true`。
4. 发布后端，再发布前端。旧的后端 multipart 上传接口已移除，必须成对上线。
