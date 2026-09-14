# AI 图片生成任务网关

把"耗时数十秒、结果异步返回、协议各不相同、还会偶发失败"的外部 AI 生成服务，
封装成一个**状态可追踪、结果可复用、异常可恢复**的内部服务。

一条命令起全套（MySQL + Redis + 应用），内置模拟供应商，**不需要任何 API Key 就能完整跑通**。

---

## 快速开始

```bash
docker compose up -d --build
# 等健康检查通过（约 40s），然后提交一个 3 图任务
curl -s -X POST http://localhost:8080/api/generation/submit \
  -H 'Content-Type: application/json' \
  -d '{"userId":1001,"taskType":"TEXT_TO_IMAGE","prompt":"a cat sitting on a keyboard","count":3}'
# => {"success":true,"data":{"taskNo":"T3f9a..."}}

# 轮询结果（可以并发疯狂调用，幂等安全）
curl -s http://localhost:8080/api/generation/T3f9a...
```

轮询返回里每个槽位都带独立进度，出图后 `url` 可直接在浏览器打开：

```json
{
  "success": true,
  "data": {
    "taskNo": "T3f9a...",
    "status": "RUNNING",
    "totalCount": 3,
    "successCount": 1,
    "images": [
      { "slotIndex": 0, "url": "/images/T3f9a.../mock-8c1e....png", "progress": 100 },
      { "slotIndex": 1, "url": null, "progress": 65 },
      { "slotIndex": 2, "url": null, "progress": 65 }
    ]
  }
}
```

本地开发（只用容器起中间件）：

```bash
docker compose up -d mysql redis
mvn spring-boot:run
```

---

## 架构

```
客户端
  │  ① POST /submit  —— 立即返回内部任务号，不等出图
  ▼
GenerationController
  │
  ├─► GenerationService ──► 落任务记录(PENDING) ──► 返回 taskNo
  │                              │
  │                              └─► 异步线程池：拆成 N 个子请求提交给供应商
  │                                        └─► 回填 providerRequestId，状态 → RUNNING
  │
  │  ② GET /{taskNo}  —— 高频轮询，幂等安全
  ▼
TaskPollingService ◄─────────────────────── TaskRecoveryService（定时兜底）
  │                                            扫 RUNNING 且超时未轮询的任务
  ├─ ① Redis 任务锁：拿不到锁 → 直接返回数据库快照
  ├─ ② Redis 已消费集合：跳过已处理的 providerRequestId
  ├─ ③ 数据库 CAS：带 version 更新待查列表
  ├─ ④ 唯一索引：(task_id, provider_request_id) 撞键即视为已入库
  └─ 状态收敛：以数据库真实成功数量重算终态
        │
        ├──► ProviderFactory ──► ImageProvider（Strategy）
        │                          ├─ MockImageProvider（内置，可离线跑）
        │                          └─ 新增供应商只需实现接口
        │
        └──► ObjectStorage ──► LocalFileStorage（换 S3/OBS 不动上层）
```

---

## 核心问题：并发轮询导致重复入库

**场景**：AI 出图要几十秒，前端每 1-2 秒轮询一次。于是同一个任务在同一时刻会有
多个请求并发进入处理逻辑 —— 一起去问供应商、一起下载同一张图、一起往数据库插同一条记录。

用一层防护不够，因为每一层都有失效的时候。这里用四层，从概率上逐层收窄，最后一层保证绝对正确：

| 层 | 手段 | 作用 | 什么时候会失效 |
|---|---|---|---|
| ① | Redis `SETNX` 任务锁 | 把并发收敛成单线程处理 | 锁 TTL 到期而业务还没跑完 |
| ② | Redis 已消费集合 | 跳过已处理子请求，省掉重复下载转存 | Redis 重启、key 被驱逐 |
| ③ | 数据库 CAS（`WHERE version = ?`） | 防止并发更新互相覆盖待查列表 | 只能保护这一个字段 |
| ④ | 唯一索引 `(task_id, provider_request_id)` | 撞键 = 已有线程写入，属正常竞争 | **不会失效** |

关键在于**第四层不依赖任何外部组件的可用性**。前三层都是性能优化——
让绝大多数重复请求在打到数据库之前就被拦掉；第四层才是正确性保证。

对应代码：[`TaskPollingService`](src/main/java/com/yzc/painting/service/TaskPollingService.java)

```java
try {
    imageMapper.insert(image);
} catch (DuplicateKeyException e) {
    // 不是错误，是并发竞争的正常结果
    log.info("命中唯一索引，结果已由其他线程写入 taskNo={}", task.getTaskNo());
}
```

### Redis 挂了会怎样

**不会算错，只会多做重复工作。** 因为任务终态一律以数据库
`COUNT(*) FROM t_generation_image` 重算，而不信任 Redis 里的任何中间计数：

```java
int successCount = imageMapper.countByTaskId(fresh.getId());   // 真相只在数据库
```

Redis 丢状态后，第②层失效，同一个子请求会被重新查询一次、重新下载一次，
但第④层保证它不会重复入库，最终状态依然正确。

---

## 其他几个设计决策

**内部任务号与供应商任务 ID 解耦**
前端只拿 `taskNo`，供应商的 `providerRequestId` 只存在数据库里。
这样换供应商、供应商 ID 回填延迟、对方改协议，都不会影响到已经发出去的接口。

**多图任务拆成独立子请求 + 固定槽位**
一次请求 3 张图 = 3 个互不影响的子请求，单张失败只影响那一张（走部分成功）。
每张图绑定一个 `slotIndex`，展示时按槽位排序 ——
供应商的完成顺序是乱的，如果按完成顺序展示，前端页面会来回跳动。

**进度只增不减，未完成时封顶 99**
多数供应商不给真实进度，只好按「已耗时 / 预估耗时」推算。两条铁律：
推算值只增不减（倒退的进度条比不动的更像是坏了）；
99 是展示层的上限，**100% 只能由数据库里的真实结果宣布**。

**线程池不用 `Executors` 工厂方法**
`newFixedThreadPool` 用的是无界队列，堆积起来会一直涨到 OOM 且毫无征兆。
这里用有界队列 + `CallerRunsPolicy`：队列满时让调用线程自己跑，
相当于对上游施加背压 —— 比直接丢任务更符合"用户已经付费提交"的业务语义。
线程名也自定义了（`painting-submit-N`），线上 jstack 时能一眼看出是哪个池子满了。

**超时回收依赖联合索引**
任务推进靠前端轮询触发，用户关掉页面任务就永久停在 RUNNING。
定时任务扫 `WHERE status = 'RUNNING' AND last_polled_at < ?`，
必须有 `idx_status_polled(status, last_polled_at)`：
没索引时 MySQL 要全表扫描找符合条件的行，**扫过的行都会加锁**，
锁范围远大于实际要更新的几行，线上会直接表现为其他写请求大面积锁等待。

回收复用正常的轮询逻辑，四层幂等同样生效，所以不会因为"回收"这个动作本身产生重复数据。

---

## 可以这样验证

**验证幂等**：提交任务后，对同一个 taskNo 并发轮询 50 次，
然后查 `SELECT COUNT(*) FROM t_generation_image WHERE task_id = ?`，
结果恒等于成功图片数，不会多。

```bash
TASK_NO=T3f9a...
for i in $(seq 1 50); do curl -s "http://localhost:8080/api/generation/$TASK_NO" > /dev/null & done; wait
```

**验证部分成功与重试**：把 `app.provider.mock.fail-rate` 调成 `0.3`，
提交若干 4 图任务，会看到 `PARTIAL_SUCCESS` 状态和 `failReason` 里的实际成功数。

**验证超时回收**：提交任务后立刻停止轮询，等 `stale-seconds`（默认 120s）过去，
定时任务会自己把任务推到终态 —— 日志里能看到"发现 N 个疑似僵死任务"。

---

## 技术栈

Java 21 · Spring Boot 3.3 · MyBatis-Plus · MySQL 8 · Redis 7 · Docker Compose

## 刻意没做的事

- **没做鉴权**：本项目想讲清的是异步任务与幂等，加 JWT 只会稀释重点。
- **没接真实模型 API**：内置 mock 供应商反而更好——离线可跑，
  而且能按需模拟"延迟随机、结果乱序、偶发失败"这三种真实故障，
  接一个真的反而不好造这些场景。要接真实供应商，实现 `ImageProvider` 注册进去即可。
- **没做前端**：接口返回的进度/槽位结构已经够前端直接渲染。

