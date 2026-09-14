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

---

## 面试怎么讲这个项目

### 1 分钟版（电梯演讲）

这是我针对 **AI 视觉生成服务的工程化封装** 做的一个完整实现。

**背景**：外部 AI 服务耗时几十秒、结果异步乱序返回、协议各不相同，如果每次都在接口里等它完成再返回，要么超时、要么把调用方锁死。

**做法**：提交时立即返回内部任务号，后台异步拆分成多个子请求提交给供应商，前端轮询拿结果。核心难点是 **高频并发轮询导致重复入库** —— 同一个任务会有几十个请求同时进来处理，用四层防护逐层收窄：Redis 锁、已消费集合、数据库 CAS、唯一索引兜底。

**结果**：状态可追踪（每张图独立进度 + 槽位）、结果可复用（轮询幂等）、异常可恢复（定时回收超时任务），并且内置模拟供应商，`docker compose up` 就能完整跑通，不需要任何外部 API Key。

---

### 5 分钟版（技术面一轮）

#### 为什么做这个项目

之前在公司做 AI 视觉内容生产平台时，核心痛点是 **外部模型服务的协议不统一、耗时长、结果异步**。比如文生图要 20-30 秒，图生视频要 40-60 秒，有的同步返回、有的要轮询第三方、有的通过回调 —— 如果每次调用都在接口里同步等，要么触发 60s 超时、要么把上游锁死。

这个项目就是把这类"长耗时异步外部服务"封装成 **状态可追踪、结果可复用、异常可恢复** 的内部服务的完整实现。

#### 核心设计

**① 提交与轮询分离**
`POST /submit` 立即返回内部任务号（`taskNo`），不等出图；后台线程池异步拆成 N 个子请求提交给供应商。前端拿着 `taskNo` 高频轮询 `GET /{taskNo}`，每 1-2 秒一次。

**② 并发轮询导致重复入库 —— 四层防护**
这是最难的地方。AI 生成要几十秒，前端每秒轮询，同一个任务在同一时刻会有多个请求并发进入处理逻辑 —— 一起去查供应商、一起下载同一张图、一起往数据库插同一条记录。

用四层防护逐层收窄：
- **Redis SETNX 任务锁**：把并发收敛成单线程处理（失效场景：锁 TTL 到期而业务还没跑完）
- **Redis 已消费集合**：跳过已处理的 `providerRequestId`，省掉重复下载转存（失效场景：Redis 重启、key 被驱逐）
- **数据库 CAS**：`UPDATE ... WHERE version = ?` 防止并发更新互相覆盖待查列表（只能保护这一个字段）
- **唯一索引 `(task_id, provider_request_id)`**：撞键 = 已有线程写入，属正常竞争，**这层不会失效**

关键在于第四层不依赖任何外部组件的可用性。前三层是性能优化，让绝大多数重复请求在打到数据库之前就被拦掉；第四层才是正确性保证。

**③ Redis 挂了会怎样**
不会算错，只会多做重复工作。因为任务终态一律以数据库 `COUNT(*) FROM t_generation_image WHERE task_id = ?` 重算，而不信任 Redis 里的任何中间计数。Redis 丢状态后，同一个子请求会被重新查询、重新下载，但唯一索引保证它不会重复入库，最终状态依然正确。

**④ 多图任务拆成独立子请求 + 固定槽位**
一次请求 3 张图 = 3 个互不影响的子请求，单张失败只影响那一张（部分成功）。每张图绑定一个 `slotIndex`，展示时按槽位排序 —— 供应商的完成顺序是乱的，如果按完成顺序展示，前端页面会来回跳动。

**⑤ 超时回收**
任务推进靠前端轮询触发，用户关掉页面任务就永久停在 `RUNNING`。定时任务扫 `WHERE status = 'RUNNING' AND last_polled_at < NOW() - INTERVAL 120 SECOND`，必须有联合索引 `idx_status_polled(status, last_polled_at)` —— 没索引时 MySQL 要全表扫描，扫过的行都会加锁，锁范围远大于实际要更新的几行，线上会表现为其他写请求大面积锁等待。

回收复用正常的轮询逻辑，四层幂等同样生效，不会因为"回收"这个动作本身产生重复数据。

#### 技术栈与可跑性

Java 21、Spring Boot 3.3、MyBatis-Plus、MySQL 8、Redis 7。

内置模拟供应商（`MockImageProvider`），可以离线跑通全流程，并且能按需模拟"延迟随机、结果乱序、偶发失败"这三种真实故障 —— 接一个真实 API 反而不好造这些场景。要接真实供应商，实现 `ImageProvider` 接口注册进去即可。

`docker compose up -d --build` 起全套（MySQL + Redis + 应用），健康检查通过后提交任务，立刻能轮询到结果，出图后的 URL 可以直接在浏览器打开。

---

### 15 分钟版（技术面二轮 / 现场手撕）

在 5 分钟版的基础上，深入展开以下几个点：

#### 1. 幂等设计的完整推演

**为什么需要四层，一层不够吗？**

最开始只用了 Redis 锁。但线上出现过这样的情况：某个任务的外部 API 响应特别慢（40s+），Redis 锁 30s TTL 到期释放了，后续轮询拿到锁又进来一遍，结果同一张图下载了两次、入库了两次。

加第二层 Redis 已消费集合后，解决了"同一个 `providerRequestId` 被处理多次"的问题。但又遇到 Redis 重启（运维升级），集合丢了，任务又从头跑了一遍。

这时候意识到 **Redis 的任何状态都不是可靠的**，必须有一层不依赖 Redis 可用性的兜底。数据库唯一索引是最后一道门：无论前面发生什么，撞键 = 已入库，代码里 `catch (DuplicateKeyException e)` 当成正常情况处理，不往外抛。

数据库 CAS（`WHERE version = ?`）是为了保护"待查列表"这个字段不被并发更新覆盖 —— 但它只能保护一个字段，保护不了整条记录的插入，所以它是第三层，不是最后一层。

**怎么验证幂等真的生效了？**

提交任务后，对同一个 `taskNo` 并发轮询 50 次：

```bash
for i in $(seq 1 50); do curl -s "http://localhost:8080/api/generation/$TASK_NO" > /dev/null & done; wait
```

然后查 `SELECT COUNT(*) FROM t_generation_image WHERE task_id = ?`，结果恒等于成功图片数，不会多。

项目里我把 mock 供应商的延迟调到 3-5 秒（`app.provider.mock.min-delay-ms`），并发打 50 个请求进去，能明显看到日志里前三层的拦截效果：大部分请求在 Redis 锁那层就返回了，少数进入处理逻辑的会命中已消费集合，极少数走到数据库撞唯一索引。

#### 2. 线程池为什么不用 `Executors` 工厂方法

`Executors.newFixedThreadPool` 内部用的是 `LinkedBlockingQueue` —— 无界队列。任务堆积起来会一直涨到 OOM，而且毫无征兆，因为队列本身不会拒绝。

这里用 `ThreadPoolExecutor` 显式构造，指定有界队列（`ArrayBlockingQueue`，容量 200）+ `CallerRunsPolicy` 拒绝策略：队列满时让调用线程自己跑任务，相当于对上游施加背压 —— 比直接丢任务（`AbortPolicy`）或者默默丢弃（`DiscardPolicy`）更符合"用户已经付费提交"的业务语义。

线程名也自定义了（`painting-submit-%d`），线上 jstack 时能一眼看出是哪个池子在干活、哪个池子满了。

#### 3. 索引设计与锁范围控制

**超时回收的索引**
定时任务扫描的条件是 `WHERE status = 'RUNNING' AND last_polled_at < NOW() - INTERVAL 120 SECOND`。必须有联合索引 `idx_status_polled(status, last_polled_at)`。

如果没有这个索引，MySQL 会全表扫描 `t_generation_task`，扫描过程中对每一行加锁（InnoDB 的 next-key lock），即使最终只更新 3 条记录，但扫描了 10 万行，这 10 万行在扫描期间都不能被其他事务修改 —— 线上会表现为其他写请求（提交新任务、更新任务状态）大面积锁等待，`SHOW ENGINE INNODB STATUS` 里能看到一堆 `lock wait`。

有了联合索引后，MySQL 直接走索引定位到符合条件的行，只锁这几行，锁范围收窄到实际要更新的数据。

**唯一索引做幂等兜底**
`uk_task_provider_request(task_id, provider_request_id)` 这个唯一索引既是业务约束（一个任务的同一个子请求只能有一条记录），也是并发控制 —— 第一个写入的线程成功，后续撞键的都会抛 `DuplicateKeyException`，代码里当成正常竞争处理：

```java
try {
    imageMapper.insert(image);
} catch (DuplicateKeyException e) {
    log.info("命中唯一索引，结果已由其他线程写入 taskNo={} providerRequestId={}",
        task.getTaskNo(), providerRequestId);
}
```

这个异常不往上抛，外层感知不到，事务正常提交。

#### 4. 金额精度与整数化存储（如果问到计费相关）

虽然这个项目没做计费，但如果要加，肯定涉及"每张图扣多少钱"。

**浮点数不能用于金额计算**，经典例子：

```java
System.out.println(0.1 + 0.2);  // 0.30000000000000004
```

两种方案：
- **`BigDecimal`**：精度可控，但每次运算都要 `new` 对象，高频场景有 GC 压力
- **整数化存储**（推荐）：数据库存"分"或"厘"（1 元 = 1000 厘），Java 里用 `long` 计算，展示时再除以 1000

我在之前项目里用的是整数化 + `decimal` 类型：数据库字段 `DECIMAL(20, 0)` 存厘，Java 映射成 `Long`，所有加减乘除都在整数域完成，最后展示时 `amount / 1000.0` 转回元。这样既避免浮点误差，也避免 `BigDecimal` 的对象开销。

#### 5. 如果让你加功能：批量任务、优先级队列、成本优化

**批量任务**
一个用户一次提交 50 个任务（电商批量生产商品图）。现在的实现是每个任务独立，50 个任务 = 50 次数据库写入。可以改成：

- 提交时一次 `batchInsert` 写入 50 条 `t_generation_task`
- 返回一个批次号 `batchNo`，前端轮询 `/api/generation/batch/{batchNo}`，返回整个批次的聚合状态
- 数据库加 `idx_batch_no(batch_no)` 索引，`SELECT ... WHERE batch_no = ?` 一次拿出 50 个任务的状态

**优先级队列**
付费用户的任务优先处理。可以在 `t_generation_task` 加 `priority` 字段（1 = 高优先级，0 = 普通），线程池改成 `PriorityBlockingQueue`，任务实现 `Comparable` 按 `priority` 排序。

但这样有个问题：高优先级任务太多时，低优先级任务会被饿死。更好的方案是用两个线程池：高优先级池 core=5、低优先级池 core=3，保证低优先级至少有 3 个线程在处理。

**成本优化**
多个供应商的同一个模型，价格不一样。可以在 `ProviderConfig` 里加 `cost_per_image` 字段，提交时按成本排序选最便宜的；如果最便宜的挂了（连续失败 N 次），降级到次便宜的。这个逻辑放在 `ProviderFactory.selectProvider()` 里，业务层无感知。

---

**以上就是我对这个项目的完整讲解。代码在 GitHub 可以直接跑，README 里也有验证幂等、验证部分成功、验证超时回收的具体命令。**
