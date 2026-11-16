# M1 · 架构设计（Deep Research Agent）

> 输入基线：`M0-scope.md`（已冻结）。
> 本阶段回答"怎么搭"，不写业务代码；产出是 M2（工程基建）到 M8（引用溯源）的施工图。
> 本阶段冻结：**模块划分、Temporal 拓扑与 activity 契约、数据模型、API 契约、关键 ADR**。

---

## 1. 设计约束与新增顶层原则

从 M0 继承的硬约束：

- Java 21 + Spring Boot 3.x，单仓多模块。
- **Temporal 是唯一编排引擎**：拓扑写在 workflow Java 代码里，不叠加 Alibaba Graph。
- **所有外部副作用都在 activity**；workflow 保持确定性。
- **所有 LLM 调用经自研 AI 网关（项目 A）**，不直连模型 provider。
- REST + SSE；单租户 API Key；仅文本；全自动出报告；Web UI 后置。
- 核心卖点：引用可验证、冲突显式标注、长任务可恢复。

M1 新增的顶层原则（后续所有设计都服从）：

| # | 原则 | 原因 |
|---|---|---|
| P1 | **workflow 历史里不放大对象**：正文/HTML/embedding 一律落库，workflow 只流转 ID 与小结构体 | Temporal payload 默认 2MB 告警、历史有硬上限，这是最容易踩的坑 |
| P2 | **失败与降级可见**：provider 切换、抓取失败、预算耗尽都进 `warnings` + SSE `warning` 事件 + 报告附录 | 不静默降级；线上排障和面试叙事都靠它 |
| P3 | **引用 fail-closed**：报告只接受"证据存在且校验通过"的 claim，校验不过宁可不渲染 | M0 的核心卖点不能靠概率 |
| P4 | **进程无状态、可水平扩**：API 不存进度状态，进度走 Redis Stream；worker 按队列扩容 | 恢复与扩展都依赖这一点 |
| P5 | **模型输出只作数据，永不作控制流**：预算、循环、停止条件全部由 Java 代码判定 | 防提示注入改变任务行为，也是"Agent 工程化"的体现 |

---

## 2. 系统上下文与部署视图

```
                    ┌──────────────────────────────┐
                    │ Client / Eval CLI / Web UI   │
                    └───────────────┬──────────────┘
                                    │ REST + SSE
┌───────────────────────────────────▼───────────────────────────────────────┐
│ dra-api（Spring MVC + Java 21 虚拟线程）                                  │
│  · API Key 鉴权  · POST /tasks  · 状态/报告查询  · SSE ← Redis Stream     │
└───────┬─────────────────────────────────────────────┬─────────────────────┘
        │ Temporal client（gRPC）                     │ XREAD BLOCK（Lettuce）
        │ start / signal / query                     │
┌───────▼──────────────┐                     ┌───────▼────────────────────┐
│ Temporal Server      │                     │ Redis                      │
│ 持久化：MySQL（独立库）│                     │ Stream / 缓存 / 限流 / 幂等 │
└───────▲──────────────┘                     └────────────▲───────────────┘
        │ task queues（poll）                              │ XADD 进度事件
┌───────┴──────────────────────────────────────────────────┴───────────────┐
│ dra-worker                                                               │
│  [drs-orchestration] ResearchWorkflow（确定性，无 IO）                    │
│  [drs-io]  SearchActivity · FetchActivity · EventActivity                │
│  [drs-llm] Plan · Extract · Coverage · Verify · Report                   │
└───────┬──────────────────────────────┬───────────────────────────────────┘
        │ HTTPS                        │ HTTPS（OpenAI 兼容）
┌───────▼──────────┐          ┌────────▼──────────────────────┐
│ Tavily（主）      │          │ AI Gateway（项目 A）           │
│ Serper（备）      │          │ 路由 / 限流 / 缓存 / trace / 计费│
└──────────────────┘          └────────┬──────────────────────┘
                                       │
                              ┌────────▼─────────┐
                              │ LLM Providers    │
                              └──────────────────┘

（worker 通过 FetchActivity 抓取 Web 页面、写 MySQL 业务库；为图面简洁省略连线）
```

> worker 同时写 MySQL（infra 层），图中省略连线。

**进程职责与扩展方式：**

| 进程 | 职责 | 扩展方式 | 有状态吗 |
|---|---|---|---|
| `dra-api` | REST、SSE、鉴权、Temporal client（start/signal/query）、报告读取、幂等受理 | 无状态，按 SSE 连接数水平扩 | 否 |
| `dra-worker` | Temporal worker：workflow + activity | 按 task queue 分开扩（io / llm / orchestration） | 否（状态在 Temporal） |
| Temporal Server | 持久化执行、调度、重试、signal/query | 官方集群/托管 | 是 |
| MySQL | 业务数据（`dra`）；Temporal 持久化（`temporal` 独立库/用户） | 主从 | 是 |
| Redis | 进度流、搜索缓存、分布式限流、幂等快速判重 | 哨兵/集群 | 可丢（见 §11 降级） |
| AI 网关（项目 A） | 模型路由、限流、语义缓存、trace、成本归属 | 独立项目 | — |

**MVP 部署形态（M2 落地）**：`docker compose` 启动 `api + worker-io + worker-llm + temporal(auto-setup, MySQL 持久化) + temporal-ui + mysql + redis`。worker-io 与 worker-llm 是同镜像不同启动参数（注册不同 activity 集、不同队列）。

---

## 3. 代码模块划分

**Maven 多模块 + 依赖方向单一**：

```
dra-parent
├─ dra-common       日志/MDC、错误码、OTel 配置、通用工具
├─ dra-contracts    @WorkflowInterface / @ActivityInterface、DTO、枚举（无 Spring、无 IO）
├─ dra-domain       领域模型 + 端口接口（SearchPort / LlmPort / TaskRepository ...），框架无关
├─ dra-infra        Tavily/Serper、HTTP 抓取器、网关 LLM 客户端、MySQL/Redis 适配
├─ dra-worker       workflow 实现 + activity 实现 + worker 启动
├─ dra-api          REST/SSE/鉴权 + Temporal client
└─ dra-eval         M9：评估 CLI（独立入口，复用 infra）
```

| 模块 | 允许依赖 | 不允许依赖 | 说明 |
|---|---|---|---|
| `dra-common` | 第三方基础库 | 所有业务模块 | 会被所有模块依赖，保持极薄 |
| `dra-contracts` | `dra-common` | Spring、infra、domain | 序列化契约；M2 用 ArchUnit 禁止引入 IO/Spring |
| `dra-domain` | `dra-common` | Spring、Temporal、infra | 纯模型 + 端口；可单测 |
| `dra-infra` | `dra-domain`、`dra-contracts`、`dra-common` | `dra-api`、`dra-worker` | 实现端口；provider 适配器都在这 |
| `dra-worker` | `dra-contracts`、`dra-domain`、`dra-infra` | `dra-api` | **workflow 实现只允许依赖 contracts**（ArchUnit 守） |
| `dra-api` | `dra-contracts`、`dra-domain`、`dra-infra` | `dra-worker` | 只通过 Temporal client 触发执行 |

**关键工程规则：**

1. **workflow 实现所在包**（`com.dra.worker.workflow`）禁止 import `dra-infra` 和任何 IO 库；activity 桩只来自 `dra-contracts`。
2. activity 实现（`com.dra.worker.activity`）是"用例编排层"：加载数据 → 调端口 → 落库 → 发事件。
3. 所有 activity 名字带版本：`search.query.v1`；DTO 只增字段、不改语义；序列化固定 Jackson JSON（禁止 Java 原生序列化）。
4. 包结构按"能力"切，不按"技术层"切：
   - `worker/activity/plan`、`search`、`fetch`、`extract`、`coverage`、`verify`、`report`、`event`
   - `infra/search/tavily`、`infra/search/serper`、`infra/llm/gateway`、`infra/fetch`、`infra/persistence/mysql`、`infra/progress/redis`

---

## 4. Temporal 设计

### 4.1 Workflow 拓扑

- Workflow：`ResearchWorkflow`，`workflowId = research/{taskId}`，队列 `drs-orchestration`，`WorkflowRunTimeout = 45m`。
- **单一 workflow，不用 child workflow**（ADR-11）。子问题并行度靠 activity 扇出，不靠子流程；MVP 拓扑复杂度不需要第二层。
- 阶段：`INIT → PLAN → [SEARCH → FETCH → EXTRACT → COVERAGE → REFINE]* → SYNTHESIZE → VERIFY → [REMEDIATE]? → REPORT → FINALIZE`。

```java
@WorkflowInterface
public interface ResearchWorkflow {
    @WorkflowMethod String run(ResearchRequest request);
    @SignalMethod void pause();
    @SignalMethod void resume();
    @QueryMethod ResearchProgress progress();
}
```

```java
// 实现骨架（控制流全在 Java 代码里，模型输出只作为数据）
@Override
public String run(ResearchRequest req) {
    TaskContext ctx = init(req);                            // activity：建任务、发 phase 事件
    PlanResult plan = llm.plan(ctx);                        // LLM：大纲 + 子问题 + 检索词
    budget.charge(plan.usage());
    int round = 0;

    while (shouldContinue(ctx, plan, round)) {              // 纯 Java 判定，见 §4.4
        if (paused) awaitResume();
        List<SearchHit> hits = fanOut(SEARCH_FANOUT, ctx.openSubQuestions(),
                q -> io.search(new SearchInput(ctx.taskId(), q, round)));   // 批量 ≤ 6
        List<SourceRef> picked = Ranker.pick(hits, ctx);    // 纯函数：canonical 去重 + 域名配额
        List<SourceRef> fetched = fanOut(FETCH_FANOUT, picked,
                s -> io.fetch(ctx.taskId(), s.url()));      // 正文落库；workflow 只拿 SourceRef
        fanOut(EXTRACT_FANOUT, fetched,
                s -> llm.extract(ctx.taskId(), s.sourceId(), ctx.openSubQuestions()));
        CoverageReport cov = llm.assessCoverage(ctx, plan); // 覆盖率不达标 → refine 出新检索词
        plan = llm.refine(ctx, plan, cov);                  // 预算/轮次不足时跳过
        round++;
    }

    List<ClaimId> claims = llm.synthesize(ctx);             // 每条 claim 必须绑定 evidence id
    List<Verification> verified = fanOut(VERIFY_FANOUT, claims,
            c -> llm.verify(ctx.taskId(), c));              // 规则校验 + 跨模型 judge
    if (needsRemediation(verified) && budget.allowsTargetedRound()) {
        verified = remediate(ctx, verified);                // 最多 1 轮定向补证
    }
    ReportRef report = report.generate(ctx, verified);      // 服务端 fail-closed 渲染
    return finalize(ctx, report);                           // 落库 + 终态事件（detached scope）
}
```

**失败收尾**：整个 `run` 外层包 `try/catch`，异常时在 `Workflow.newDetachedCancellationScope(...)` 中执行 `task.markFailed.v1`，保证"取消/失败也有终态事件与 DB 状态"，不会留下永远 RUNNING 的任务。

### 4.2 Task Queue 划分（按资源特征隔离）

| 队列 | 内容 | Worker 默认并发 | 理由 |
|---|---|---|---|
| `drs-orchestration` | 只跑 workflow | 轻量 | 与 activity 隔离，避免 activity 积压拖慢 workflow task（会表现为"任务卡住"） |
| `drs-io` | 搜索、抓取、事件、DB 写 | 50 | IO 密集、短耗时；限流主要针对外部 provider |
| `drs-llm` | plan / extract / coverage / verify / report | 20 | 长耗时、走网关；并发受网关配额约束 |

三个队列对应同一镜像的不同启动 profile：`--worker.roles=orchestration|io|llm`（M2 实现）。

### 4.3 Activity 清单（稳定名 + 幂等键）

| Activity | 队列 | 输入 → 输出 | 超时 / 重试 | 幂等键 |
|---|---|---|---|---|
| `task.init.v1` | io | `ResearchRequest` → `TaskContext` | 10s / 3 | `taskId` |
| `plan.create.v1` | llm | 问题+深度+限额 → 大纲/子问题/usage | 120s（hb 30s）/ 3 | `taskId + planVersion` |
| `plan.refine.v1` | llm | plan + coverage → 新检索词/子问题 | 60s / 2 | `taskId + round` |
| `search.query.v1` | io | query + topK → `SearchHit[]` + usage | 30s / 4 | `taskId + round + queryHash` |
| `source.fetch.v1` | io | url → `SourceRef(sourceId, status, hash)` | 25s（hb 10s）/ 3 | `taskId + urlHash` |
| `evidence.extract.v1` | llm | `sourceId + subQuestionIds + round` → evidenceIds + usage | 90s（hb 30s）/ 2 | `sourceId + subQ + extractVersion` |
| `coverage.assess.v1` | llm | 子问题 + 证据摘要 → `CoverageReport` | 90s / 2 | `taskId + round` |
| `claims.synthesize.v1` | llm | 大纲 + 证据 → `ClaimDraft[]` | 120s / 2 | `taskId + synthesisVersion` |
| `claim.verify.v1` | llm | `claimId` → `Verification` | 60s / 3 | `claimId + round + verifierProfile` |
| `conflict.detect.v1` | llm | 分组 claims → `ConflictReport` | 60s / 2 | `taskId + round` |
| `report.generate.v1` | llm | 已验证 claims + 冲突 + 统计 → `ReportRef` | 240s（hb 60s）/ 2 | `taskId + reportVersion` |
| `task.finalize.v1` | io | 终态 + 报告引用 → void | 30s / 3 | `taskId` |
| `task.markFailed.v1` | io | 错误码/信息 → void | 30s / 3 | `taskId + errorCode` |
| `event.emit.v1` | io | `TaskEvent` → void | 5s / 1（失败仅记日志） | 事件自带 `eventId` |

**重试策略统一基线**：初始 1s、指数退避、最大间隔 20s；`400/401/403/422` 非重试；`408/429/5xx` 可重试（429 加抖动）。LLM activity 额外设置 heartbeat，长调用定期 `Activity.getExecutionContext().heartbeat(...)`，用于取消响应与超时判定。

**代价**：activity 至少执行一次，所以每个副作用都必须幂等。写库用 upsert + 唯一键；事件消费端按 stream id 去重；报告用 `(taskId, version)` 唯一。

### 4.4 预算、降级与停止条件

`Budget` 在 workflow 内存中累计（加法确定性），每次 activity 返回 `Usage{promptTokens, completionTokens, costMicros}` 后 `charge()`。

**深度档位（可配置，M9 校准）：**

| depth | 子问题 | 最大轮次 | 每查询 topK | 最多来源 | 抽取并发 | token 预算 | 目标时长 |
|---|---|---|---|---|---|---|---|
| QUICK | 3 | 1 | 5 | 15 | 4 | 150k | ≤ 2 min |
| STANDARD | 5 | 2 | 8 | 40 | 6 | 500k | ≤ 5 min |
| DEEP | 8 | 3 | 10 | 80 | 6 | 1.5M | ≤ 12 min |

**预算保护：**

- 预留 15% 给最后的 `SYNTHESIZE/VERIFY/REPORT`；剩余低于预留时不再开新一轮检索。
- 剩余 < 30%：抽取/覆盖率改用 `fast` 模型档、减少 topK；剩余 < 15%：跳过定向补证。
- 归零：立即停止研究，进入报告阶段，`stopReason = BUDGET_EXHAUSTED`，输出"基于已有证据的降级报告"。
- **双重兜底**：workflow 预判 + 网关硬上限（activity 透传 `X-Budget-Remaining-Tokens`，网关超限返回 `BUDGET_EXCEEDED`，activity 映射为非重试异常，workflow 当作停止信号而非失败）。

**停止条件（任一满足即停）：**

| 条件 | 判定 |
|---|---|
| `COVERAGE_REACHED` | 覆盖率 ≥ 80% 且已完成至少 1 轮验证 |
| `MAX_ROUNDS` | `round ≥ depth.maxRounds` |
| `BUDGET_EXHAUSTED` | 剩余预算 ≤ 报告预留 |
| `DEADLINE_REACHED` | `Workflow.getInfo().getCurrentTime() ≥ deadline - 2min` |
| `NO_NEW_EVIDENCE` | 本轮新增证据 < 5% 且无新子问题被回答 |
| `CANCELED` | 收到 Temporal cancellation |

每次停止都记录 `stopReason` 并写入报告 meta；**绝不无限循环**（M0 验收项）。

### 4.5 Signal / Query / 取消

| 能力 | 实现 | 语义 |
|---|---|---|
| 暂停 | `@SignalMethod pause()`；workflow 在阶段边界 `Workflow.await(() -> !paused)` | 已在跑的 activity 让它跑完；不强制中断（简单、可预期） |
| 恢复 | `@SignalMethod resume()` | 解除等待 |
| 进度查询 | `@QueryMethod progress()` 返回 phase/round/计数/usage/warnings | 只读、可随时查；SSE 兜底 |
| 取消 | `WorkflowStub.cancel()` + detached scope 收尾 | 终态 `CANCELED`，已产生报告仍可读 |
| 预算追加 | 不做（MVP） | 二期可加 `@SignalMethod extendBudget()` |

### 4.6 确定性约束与 Payload 边界

- workflow 内禁止：`System.currentTimeMillis()`、`Math.random()`、`UUID.randomUUID()`、任何 IO/网络/DB、`HashMap/HashSet` 遍历顺序参与逻辑。
- 需要时间/随机时用 `Workflow.getInfo().getCurrentTime()`、`Workflow.randomUUID()`；集合一律 `List`/`LinkedHashMap` 且按稳定键排序。
- **活动入参/出参上限**：单 payload ≤ 512KB（应用层自检），列表长度硬限制（topK ≤ 10、单次 hits ≤ 50、claims ≤ 60）。
- 正文、HTML、全文 embedding **永不进 workflow**；fetch 落库后只返回 `sourceId`。
- 活动名带 `.v1`；workflow 有行为变更时用 `Workflow.getVersion("change-id", DEFAULT_VERSION, 1)`；DTO 只增不改。
- `continue-as-new`：历史事件 > 20k 或轮次异常多时启用（MVP 预期不触发，预留 hook）。

### 4.7 并发与限流

| 层级 | 控制 | 默认值 |
|---|---|---|
| workflow 扇出 | 分批 `Async.function` + `Promise.allOf`，批内并发 | search 6、fetch 8、extract 6、verify 6 |
| io worker | `maxConcurrentActivityExecutionSize` | 50 |
| llm worker | 同上 | 20 |
| 外部限流 | Redis 令牌桶（Lua 原子），按 `provider` / `modelProfile` | Tavily 由账户配额反推；网关档位并发 20 |
| API | 每 Key 新建任务 10/min；SSE 连接上限 500 | 可配 |

> 单租户下不做跨任务优先级；二期可拆 priority queue。

---

## 5. 进度与 SSE 设计

### 5.1 为什么用 Redis Stream，而不是轮询 Temporal Query

| 方案 | 问题 |
|---|---|
| API 轮询 `WorkflowStub.query("progress")` | 只能看到 workflow 级状态，看不到"抓取第 7/32 个来源"；轮询频率与外推成本耦合 |
| API 直接转发 activity 内存事件 | API 与 worker 不同进程，做不到 |
| **Redis Stream（选定）** | 可重放（`Last-Event-ID`）、任意 API 实例可服务、activity 粒度事件、与 Temporal 历史解耦 |

**写入路径**：activity 内 `ProgressPublisher.emit(taskId, event)` → `XADD drs:task:{id}:events MAXLEN ~ 2000` + 更新 `drs:task:{id}:state` Hash（最新快照），TTL 7 天。每任务节流 ≤ 5 事件/秒（进度类事件合并）。

**读取路径**：SSE 端点从 `Last-Event-ID`（或 `cursor`）开始 `XREAD BLOCK 15000 COUNT 50 STREAMS ...`；超时发 keep-alive 注释行；收到终态事件或任务终态后关闭。

**并发模型选型（ADR-14）**：`dra-api` 用 Spring MVC + Java 21 虚拟线程，不用 WebFlux。
- DRS 的并发模型是"少量长连接 + 长时间等待"，不是网关那种"海量短连接 + 反压转发"；虚拟线程让每个 SSE 连接一个廉价线程，阻塞式 `XREAD` 天然形成背压。
- 高并发/响应式的叙事放在项目 A（网关），这里避免双栈复杂度（WebFlux + 阻塞 MySQL 需要 R2DBC，代价大）。

### 5.2 事件契约

SSE 帧：

```
id: 1731398400000-0
event: progress
data: {"type":"progress","taskId":"01J...","ts":"2026-11-11T10:30:00Z","payload":{"phase":"FETCH","done":12,"total":32}}

: keep-alive
```

| 事件 `type` | payload | 何时发 |
|---|---|---|
| `phase` | `{phase, round}` | 阶段切换 |
| `progress` | `{phase, done, total, percent}` | 批处理进度（节流） |
| `source` | `{url, title, status}` | 单个来源抓取完成 |
| `evidence` | `{subQuestionId, count}` | 抽取完成 |
| `claim` | `{claimId, verdict}` | 单条校验完成（可选，默认关闭） |
| `warning` | `{code, message}` | 降级/失败可见化 |
| `done` | `{reportId, stopReason, usage}` | 终态成功 |
| `error` | `{code, message, retryable}` | 终态失败 |

- 断线重连：客户端带 `Last-Event-ID`，服务端从该 id 之后重放；首次连接可带 `?replay=true` 从头回放（≤ 2000 条）。
- 客户端断开的写失败会终止该连接的读取循环，不会泄漏线程。
- SSE 只是"通知通道"，**真相来源**：状态查 MySQL，进度兜底查 `drs:task:{id}:state` / Temporal Query。

---

## 6. 数据模型

### 6.1 ER 概览

```
research_task 1─n task_plan 1─n sub_question
research_task 1─n search_query
research_task 1─n source 1─1 source_content
source 1─n evidence n─1 sub_question
research_task 1─n claim 1─n citation n─1 evidence
claim 1─n verification
research_task 1─n conflict
research_task 1─n report
research_task 1─n usage_record
```

主键统一 `CHAR(26)` ULID：按时间有序、对外不可枚举、无自增泄露。

### 6.2 表清单

| 表 | 作用 | 关键约束/索引 |
|---|---|---|
| `research_task` | 任务主表、幂等、终态、成本汇总 | `uk(idempotency_key)`；`idx(status, created_at)` |
| `task_plan` | 每次规划的版本化大纲 | `uk(task_id, version)` |
| `sub_question` | 子问题、覆盖状态、缺失点 | `idx(task_id, status)` |
| `search_query` | 每次检索的输入/结果数/成本 | `uk(task_id, round, query_hash)` |
| `source` | 来源元数据、抓取状态、内容哈希 | `uk(task_id, url_hash)`；`idx(task_id, domain)` |
| `source_content` | 压缩正文（gzip BLOB），抽取时读取 | PK `source_id` |
| `evidence` | 逐字摘录 + 锚点 + 摘录版本 | `uk(source_id, sub_question_id, quote_hash)` |
| `claim` | 报告中的原子结论及状态 | `idx(task_id, section_key)` |
| `citation` | claim ↔ evidence 多对多 | `uk(claim_id, evidence_id)` |
| `verification` | 每轮校验的规则/judge 结果 | `uk(claim_id, round, verifier_profile)` |
| `conflict` | 多来源冲突记录（显式标注） | `idx(task_id)` |
| `report` | Markdown + 结构化 JSON + 统计 | `uk(task_id, version)` |
| `usage_record` | 每次 LLM 调用的 token/成本明细 | `idx(task_id, activity)` |

### 6.3 核心表伪 DDL（M2 转 Flyway）

```sql
research_task(
  id char(26) primary key,
  idempotency_key varchar(80) not null,
  request_hash char(64) not null,          -- 同 key 不同请求体 → 409
  question text not null,
  status varchar(16) not null,             -- QUEUED/RUNNING/PAUSED/SUCCEEDED/FAILED/CANCELED
  depth varchar(16) not null,
  limits_json json not null,               -- deadline/token/cost/域名约束
  used_tokens bigint not null default 0,
  used_cost_micros bigint not null default 0,
  workflow_id varchar(128), run_id varchar(128), trace_id char(32),
  stop_reason varchar(32), error_code varchar(64), error_msg varchar(1024),
  report_id char(26),
  created_at datetime(3), started_at datetime(3), finished_at datetime(3),
  unique key uk_idem (idempotency_key),
  key idx_status_created (status, created_at)
);

source(
  id char(26) primary key,
  task_id char(26) not null,
  url text not null,
  url_hash char(64) not null,
  canonical_url text,
  domain varchar(255),
  title varchar(1024),
  published_at datetime(3) null,
  http_status int,
  content_hash char(64), content_len int,
  fetch_status varchar(16),                -- OK/FAILED/SKIPPED
  fetched_at datetime(3),
  unique key uk_task_url (task_id, url_hash),
  key idx_task_domain (task_id, domain)
);

evidence(
  id char(26) primary key,
  task_id char(26) not null,
  source_id char(26) not null,
  sub_question_id char(26) not null,
  round int not null,
  quote text not null,                     -- 必须是原文逐字摘录（服务端校验）
  quote_hash char(64) not null,
  anchor_json json,                        -- {start,end,headingPath}；snippet 来源为 null
  origin varchar(16) not null,             -- FULL_TEXT / SEARCH_SNIPPET
  aspect varchar(256),
  extract_version varchar(16) not null,
  unique key uk_src_sub_quote (source_id, sub_question_id, quote_hash),
  key idx_task_sub (task_id, sub_question_id)
);

claim(
  id char(26) primary key,
  task_id char(26) not null,
  section_key varchar(64) not null,
  statement text not null,
  importance tinyint not null default 1,
  status varchar(16) not null,             -- SUPPORTED/PARTIAL/UNSUPPORTED/REFUTED/CONFLICTED
  confidence decimal(4,3),                 -- 计算值，不让模型自报
  created_at datetime(3)
);

verification(
  id char(26) primary key,
  claim_id char(26) not null,
  round int not null,
  rule_verdict varchar(16) not null,       -- PASS/FAIL（引用存在性、quote 命中、URL 存活）
  llm_verdict varchar(16) not null,        -- SUPPORT/PARTIAL/REFUTE/UNCERTAIN
  final_verdict varchar(16) not null,
  rationale text,
  checked_source_ids json,
  verifier_profile varchar(32) not null,
  prompt_version varchar(16) not null,
  unique key uk_claim_round_profile (claim_id, round, verifier_profile)
);
```

### 6.4 Redis Key 设计

| Key | 类型 | TTL | 用途 |
|---|---|---|---|
| `drs:task:{id}:events` | Stream（MAXLEN~2000） | 7d | SSE 事件、断线重放、审计 |
| `drs:task:{id}:state` | Hash | 7d | 最新阶段/计数/usage 快照（SSE 首帧、状态兜底） |
| `drs:search:{provider}:{hash}` | String(JSON) | 24h | 搜索结果缓存（同 query 重试/重跑不重复计费） |
| `drs:rl:search:{provider}` | Hash（令牌桶） | — | provider 配额保护 |
| `drs:rl:llm:{profile}` | Hash（令牌桶） | — | 网关档位保护 |
| `drs:idem:{key}` | String(taskId) | 24h | 提交快速判重（DB 唯一键是最终真相） |

**存储取舍（ADR-21）**：正文压缩后存 MySQL（≤ 2MB/页，超限截断），原始 HTML 不落库；正文保留 30 天（清理任务放 M10）。这样做是为"引用校验 + Eval 可回溯"服务，而不是做内容平台。

---

## 7. API 契约

### 7.1 通用约定

- Base path：`/api/v1`；`Content-Type: application/json`。
- 鉴权：`X-API-Key: <key>`（单租户，key 来自环境变量/密钥管理）。SSE 在无 Web UI 的 MVP 里由 CLI/curl 带 header；为将来浏览器兼容预留 `?api_key=`（仅 HTTPS，二期换短期 token）。
- 错误体：problem+json 风格 + 业务码。
- 时间统一 ISO-8601 UTC；ID 为 ULID。

### 7.2 端点

| 方法 | 路径 | 说明 | 成功码 |
|---|---|---|---|
| POST | `/research/tasks` | 提交任务（必须带 `Idempotency-Key`） | 202 |
| GET | `/research/tasks/{id}` | 任务状态/进度/成本摘要 | 200 |
| GET | `/research/tasks/{id}/events` | SSE 进度流（支持 `Last-Event-ID`） | 200 |
| POST | `/research/tasks/{id}/pause` | 暂停（幂等） | 200 |
| POST | `/research/tasks/{id}/resume` | 恢复（幂等） | 200 |
| POST | `/research/tasks/{id}/cancel` | 取消（幂等，已终态返回当前状态） | 200 |
| GET | `/research/tasks/{id}/report` | 报告，`?format=json|markdown` | 200 |
| GET | `/research/tasks` | 列表，`?status=&limit=&cursor=` | 200 |
| GET | `/actuator/health` | 存活/就绪 | 200 |

### 7.3 提交任务

```json
POST /api/v1/research/tasks
Idempotency-Key: 8f1c2d9e-...
{
  "question": "2026 年 Java 微服务做长任务编排，Temporal 和 LangGraph 各自适合什么场景？",
  "options": {
    "depth": "STANDARD",
    "deadlineMinutes": 15,
    "budgetTokens": 500000,
    "budgetCostMicros": 2000000,
    "language": "auto",
    "allowedDomains": [],
    "excludedDomains": []
  }
}
```

```json
HTTP/1.1 202 Accepted
{
  "taskId": "01JB9K7N2Q8V3R5T7X1Y4Z6A0C",
  "status": "QUEUED",
  "statusUrl": "/api/v1/research/tasks/01JB9K7N2Q8V3R5T7X1Y4Z6A0C",
  "streamUrl": "/api/v1/research/tasks/01JB9K7N2Q8V3R5T7X1Y4Z6A0C/events",
  "createdAt": "2026-11-11T10:30:00Z"
}
```

**幂等语义**：同 `Idempotency-Key` + 同 `request_hash` → 返回已存在任务（202/200）；同 key 不同请求体 → `409 IDEMPOTENCY_CONFLICT`。DB 唯一键是最终真相，Redis 仅加速。

### 7.4 状态响应

```json
{
  "taskId": "01JB9K...",
  "status": "RUNNING",
  "phase": "VERIFY",
  "round": 2,
  "progress": { "done": 24, "total": 31, "percent": 77 },
  "counts": { "subQuestions": 5, "sourcesFetched": 28, "evidence": 96, "claims": 21 },
  "usage": { "tokens": 312400, "costMicros": 980000 },
  "warnings": [ { "code": "FETCH_FAILED", "message": "2 sources skipped" } ],
  "reportId": null,
  "stopReason": null,
  "createdAt": "...", "startedAt": "...", "finishedAt": null
}
```

### 7.5 报告响应（`format=json` 摘要结构）

```json
{
  "taskId": "01JB9K...",
  "version": 1,
  "markdown": "# ...",
  "sections": [
    {
      "key": "s1", "title": "长任务编排的核心诉求",
      "claims": [
        {
          "id": "clm_01JB...", "statement": "...", "status": "SUPPORTED", "confidence": 0.92,
          "citations": [
            { "ref": "[1]", "url": "https://...", "title": "...", "quote": "..." }
          ]
        }
      ]
    }
  ],
  "conflicts": [
    { "topic": "...", "positions": [ { "claimId": "clm_...", "urls": ["..."] } ] }
  ],
  "references": [ { "n": 1, "url": "...", "title": "...", "accessedAt": "..." } ],
  "stats": {
    "stopReason": "COVERAGE_REACHED", "durationMs": 214000,
    "tokens": 412000, "costMicros": 1450000,
    "sourcesFetched": 32, "evidence": 118, "claims": 24,
    "verifiedRatio": 0.88,
    "warnings": ["FETCH_FAILED:2", "SEARCH_FALLBACK:1"]
  },
  "meta": {
    "depth": "STANDARD",
    "models": ["dra-plan-reasoner", "dra-extract-fast", "dra-verify-strong"],
    "promptVersions": ["plan.v1", "extract.v1", "verify.v1", "report.v1"]
  }
}
```

### 7.6 错误码

| HTTP | code | 场景 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 参数非法（深度/预算/域名） |
| 401 | `UNAUTHORIZED` | 缺 key / key 错 |
| 404 | `TASK_NOT_FOUND` | — |
| 409 | `IDEMPOTENCY_CONFLICT` | 同 key 不同请求体 |
| 409 | `TASK_TERMINAL` | 对终态任务 pause/resume（cancel 幂等返回 200） |
| 429 | `RATE_LIMITED` | 建任务/连接超限 |
| 503 | `TEMPORAL_UNAVAILABLE` | 提交失败，客户端可重试 |
| 503 | `PROGRESS_UNAVAILABLE` | Redis 故障时 SSE 不可用，可退回轮询 |

---

## 8. Agent 核心算法设计

### 8.1 规划（Plan）

- 输出 JSON（服务端 schema 校验）：`outline[]{id, title, intent, keyQuestions[], queries[]}` + `stopConditions`。
- 约束：子问题 3~8 个；每个子问题 1~3 条检索词；禁止输出 URL；语言跟随问题语言。
- 校验失败 → 带错误信息重试 1 次 → 仍失败则**确定性降级模板**（按问题切分为 3 个子问题，使用原问题关键词），并记 `warning=PLAN_FALLBACK`。
- 预算按 `depth` 预估一轮成本，写入 plan meta。

### 8.2 检索（Search）

- Provider 链：Tavily 主 → Serper 备（端口 `SearchPort`，配置驱动）；记录实际 provider 与消耗。
- 查询归一化：小写、去多余空白、保留引号短语；同一 `(round, query_hash)` 命中缓存。
- topK 按档位（5/8/10）；**canonical URL 去重**（host 小写、去 utm 参数/锚点/尾斜杠）；每域名每轮 ≤ 2 条；排序 = provider rank + 时效 + 域名多样性（MVP 不做 LLM rerank）。
- 返回的 `SearchHit` 只含 url/title/snippet/rank/publishedAt，不含正文。

### 8.3 抓取与正文提取（Fetch）

- JDK `HttpClient`；连接 5s、总 20s；手动跟随重定向 ≤ 3 次，**每一跳重新做 SSRF 校验**；大小上限 3MB；仅 `text/html`、`text/plain`、`application/xhtml+xml`；PDF/JS 渲染页 MVP 不支持（记 `FETCH_UNSUPPORTED`）。
- 正文化用 Jsoup + readability 启发式（`article/main` 密度），保留标题/标题层级；正文压缩存 `source_content`。
- **SSRF 防护**：只允许 http/https、端口 80/443；DNS 解析后拒绝私网/回环/链路本地/云元数据地址（`169.254.169.254`）；重定向目标重复校验。
- 抓取失败 → 该来源标记 `FAILED`；若搜索摘要存在，可生成 `origin=SEARCH_SNIPPET` 的降级证据（只能支撑 PARTIAL，见 8.7）。

### 8.4 证据抽取（Extract）

- 输入：`sourceId` + 子问题列表 + 正文（activity 内按 ID 加载，**不进 workflow**）。
- 模型只输出逐字摘录：`{facts:[{quote, aspect, subQuestionId}]}`；**服务端校验 `quote` 必须是正文的子串**（空白 + 排版字符归一化后比对），不匹配直接丢弃并计数 `extract_quote_rejected`。
- 计算并存储字符区间（`anchor_json`，M6.5 起为模型 quote 回填后的原文区间）；每来源每子问题 ≤ 6 条；`quote_hash` 去重。
- 这一层是"来源 → 可定位证据"的防幻觉闸门：模型只负责定位，落库文本一律回填为原文精确区间，因此库里不可能出现改写过的句子。

### 8.5 覆盖率评估与追检（Coverage / Refine）

- 评估每个子问题为 `COVERED / PARTIAL / OPEN`，并列出 `missingAspects`；整体覆盖率 = 已覆盖子问题数 / 总数。
- 仅对 `OPEN/PARTIAL` 生成追检索词；追检受轮次、预算、域名配额约束。
- 覆盖率 ≥ 80% 或轮次用尽或预算不足 → 停止研究（见 §4.4）。

### 8.6 结论合成（Synthesize）

- 按大纲分节生成**原子 claim**，每条必须绑定 1~n 个 `evidenceId`；无证据绑定的 claim 直接丢弃（不可进入报告）。
- claim 数量上限：STANDARD 30 / DEEP 60；按 importance 排序，超出部分并入附录。
- `confidence` 不让模型自报，由 §8.7 计算（来源多样性、校验通过率、冲突惩罚）。

### 8.7 引用与事实校验（Verify）——核心护城河

**双通道校验，先规则后模型：**

| 通道 | 内容 | 判定 |
|---|---|---|
| 规则（确定性代码） | evidence 存在且属于本任务；`quote` 命中 source 正文；source 抓取成功；snippet 来源降级 | `PASS / FAIL` |
| LLM judge（跨模型） | 引用片段是否支撑 claim；输出 `SUPPORT / PARTIAL / REFUTE / UNCERTAIN` + 理由 | 用**与生成不同的模型档**（`dra-verify-strong`），并关闭网关语义缓存 |

**claim 级汇总规则：**

```
任一引用 rule FAIL                     → UNSUPPORTED（该引用不可渲染）
存在 REFUTE 且来源质量可接受            → REFUTED（进入冲突/反证章节）
至少 1 条 SUPPORT + 无 REFUTE           → SUPPORTED
至少 1 条 PARTIAL，无 SUPPORT           → PARTIAL
仅 snippet 证据                         → 最高 PARTIAL
其余情况                                → UNSUPPORTED
```

`confidence = 0.5×来源多样性 + 0.3×judge 平均分 + 0.2×规则通过率 − 0.2×冲突数`（截断到 [0,1]）。

**定向补证（Remediation）**：高 importance 的 `REFUTED/UNCERTAIN/UNSUPPORTED` claim 最多 3 条，生成"反证/最新数据"类查询，补 1 轮（预算允许时）；仍不通过则保留状态并在报告中显式说明。

### 8.8 冲突检测（Conflict）

- 按章节批量喂给模型：claims + 引用摘要，找出互相矛盾的陈述对。
- 产出 `conflict{topic, positions:[{claimId, urls, stance}]}`；**不裁决谁对**，报告里并列呈现双方来源。
- 冲突同时影响相关 claim 的 confidence 与报告"争议与反证"章节。

### 8.9 报告生成（Report，fail-closed）

- 输入只包含 `SUPPORTED/PARTIAL` 的 claims + 冲突记录 + 统计元信息；模型输出 Markdown，引用标记用 `[[claimId]]`。
- **服务端渲染（确定性代码）**：
  1. 每个 `[[claimId]]` 必须能解析到本次输入中的 claim；解析失败 → 修复重试（≤ 2 次）；仍失败 → 删除标记并加 `warning=REPORT_MARKER_STRIPPED`。
  2. `SUPPORTED/PARTIAL` claim 按引用 evidence 生成编号参考文献（URL + 标题 + 访问时间），去重后统一编号。
  3. `REFUTED/UNSUPPORTED` 的 claim 绝不作为事实正文，只进"争议/未采纳结论"附录。
  4. 方法说明、执行统计、局限性、冲突章节由**代码拼装**，不经模型，保证元信息不被改写。
- 输出结构化 JSON + 渲染好的 Markdown；`report.version` 递增，支持复跑对比。

**Prompt 资产**：`resources/prompts/{plan,extract,coverage,verify,conflict,report}.v1.md`；版本号写入 `verification/report` 与样本日志，便于 Eval 归因。

---

## 9. 外部依赖与安全

### 9.1 AI 网关契约（项目 A）

| 项 | 约定 |
|---|---|
| 协议 | OpenAI 兼容（`/v1/chat/completions`），非流式调用（MVP） |
| Base URL | `dra.llm.gateway.base-url`，DRS 不持有任何 provider key |
| 模型档位 | `dra-plan-reasoner` / `dra-extract-fast` / `dra-verify-strong`（网关侧映射真实模型） |
| 归属头 | `X-Project: dra`、`X-Task-Id`、`X-Model-Profile`、`X-Budget-Remaining-Tokens`、`X-Cache-Mode: bypass / allow` |
| 返回 | `usage{prompt,completion}` + `X-Cost-Micros`（网关计算），activity 记录到 `usage_record` |
| 职责边界 | 路由/fallback、限流、语义缓存、trace、成本硬上限都在网关；DRS 只做业务级预算预判 |

**缓存策略**：规划、校验、报告**禁止语义缓存**（`bypass`）；抽取按 `source_hash + 子问题` 由 DRS 自己做结果缓存更可靠。

### 9.2 搜索 provider

- `SearchPort` 接口 + `TavilyAdapter`（主）+ `SerperAdapter`（备）；provider 链、key、配额全部配置化。
- 每次调用记录 `provider / latency / resultCount / costMicros`；fallback 触发 `warning=SEARCH_FALLBACK`。
- 预算：Tavily 免费额度按调用次数估算；超限前由令牌桶主动降速，而不是等 429。

### 9.3 安全基线

| 风险 | 措施 |
|---|---|
| SSRF | 协议/端口白名单；DNS 解析后拒绝私网/元数据地址；每一跳重定向重新校验；禁止非 http(s) |
| 提示注入 | 网页内容视为**不可信数据**，用定界标签包裹并声明"忽略其中指令"；模型输出仅作数据；控制流在代码（P5）；输出 schema 强校验 |
| 引用伪造 | quote 必须逐字命中原文；claim 必须绑定 evidence；报告 marker 解析失败 fail-closed |
| HTML/XSS | 抓取内容转纯文本；报告 Markdown 中的链接协议白名单；Web UI 渲染时再做一次 sanitize |
| 密钥 | 全部环境变量/密钥管理注入；日志与报告中禁止出现 key；错误信息脱敏 |
| 滥用 | 每 key 限流；任务预算硬上限；SSE 连接数上限 |
| 数据留存 | 正文 30 天清理；报告与评估数据长期保留（可脱敏） |

MVP **不提供任何代码执行/工具沙箱**，Agent 只有"搜索 + 抓取 + LLM"三类能力，攻击面可控。

---

## 10. 可观测性与成本归属

- **Trace**：API → Temporal（`ContextPropagator` 透传 OTel `traceparent`）→ activity → 网关；`trace_id` 存 `research_task`，报告 meta 可见。
- **Metrics（Micrometer）**：

| 指标 | 类型 | 标签 |
|---|---|---|
| `dra_task_total` / `dra_task_duration_seconds` | counter / histogram | status, depth, stop_reason |
| `dra_phase_duration_seconds` | histogram | phase |
| `dra_search_total` | counter | provider, status |
| `dra_fetch_total` | counter | status |
| `dra_llm_tokens_total` / `dra_llm_cost_micros_total` | counter | activity, profile, kind |
| `dra_activity_failures_total` | counter | activity, error_code |
| `dra_citation_verified_ratio` | gauge（按批次聚合） | depth |
| `dra_sse_connections` / `dra_sse_events_total` | gauge / counter | type |
| `dra_budget_exhausted_total` | counter | depth |

- **日志**：JSON 结构化；字段 `taskId/phase/round/activity/traceId/durationMs/errorCode`；**不记录正文与密钥**，quote 只记录 hash 与前 80 字符。
- **成本归属**：`usage_record` 明细 + 任务汇总；看板按 activity/模型档/天聚合，直接回答"一次研究花在哪"。

---

## 11. 可靠性设计与故障矩阵

| 故障 | 检测 | 系统行为 | 用户可见 |
|---|---|---|---|
| 搜索 5xx/超时 | 重试耗尽 | 切备用 provider；仍失败则该子问题 FAILED | `warning`；证据不足 50% 则任务 FAILED |
| 搜索 429 | provider 返回 | 指数退避 + 抖动；令牌桶降速 | 延迟增大，不报错 |
| 抓取 403/反爬 | HTTP 状态 | 跳过该源，降级为 snippet 证据（最高 PARTIAL） | `warning=FETCH_BLOCKED` |
| 抓取超时 | timeout | 重试 2 次后跳过 | 同上 |
| 网关 429 | HTTP 状态 | 退避重试；本地桶限速 | 进度变慢 |
| 网关 5xx/全路由失败 | 重试耗尽 | plan 失败 → 任务 FAILED；report 失败 → 确定性 Markdown 兜底 | 明确错误码/降级报告 |
| LLM 输出非法 JSON | schema 校验 | 带错误修复重试 ≤ 2；plan 走模板、report 走确定性渲染 | `warning`，任务可完成 |
| Temporal 不可用 | gRPC 异常 | 提交返回 503；worker 自动重连；SSE 不受影响 | 暂时无法新建任务 |
| worker 崩溃（kill -9） | Temporal 心跳/超时 | activity 重试，workflow 从 event history 重放；已落库副作用幂等 | 任务继续，进度可能重复一条事件 |
| MySQL 不可用 | 连接异常 | activity 重试；API 读写 503；workflow 自然等待 | 短暂不可用 |
| Redis 不可用 | 连接异常 | 任务继续执行；SSE 返回 503；限流降级本地桶；幂等回退 DB；搜索缓存 miss | 只能轮询状态，任务不丢 |
| SSE 客户端断开 | 写失败 | 结束该连接线程 | 重连带 `Last-Event-ID` 续播 |
| 重复提交 | 幂等键 | 返回同一任务 | 无重复任务 |
| 预算耗尽 | workflow 计数 | 停止研究 → 降级报告 + `BUDGET_EXHAUSTED` | 报告标注预算耗尽 |
| 提示注入内容 | 校验失败/异常输出 | 不改变控制流；quote 校验拦截；报告 sanitize | 可能缺少该来源结论 |

**恢复语义**：Temporal 保证 workflow 恢复；activity 是 **at-least-once**，不宣称 exactly-once——所有副作用靠幂等键收敛。这是面试里必须讲清楚的一条。

---

## 12. 容量假设与目标（M10 用真实数据校准）

**单任务画像（STANDARD，设计假设）：**

| 项 | 估计 |
|---|---|
| 检索调用 | 10~18 次 |
| 抓取来源 | 30~40 个 |
| LLM 调用 | 70~120 次（抽取占大头） |
| token | 30~50 万 |
| 耗时 | 2~4 min（P50），≤ 5 min（P95） |
| 任务内峰值并发 activity | 6~8 |

**并发目标（待压测校准）：**

- 2 个 io worker + 2 个 llm worker：同时承载 **20 个 STANDARD 任务**，进度 SSE 500 连接/API 实例。
- 瓶颈预期在**网关配额与搜索 provider 限流**，不在 CPU；压测报告要给出"扩 worker 后 QPS 曲线"和"网关限流触发率"。
- 所有数字在 M10 压测前只作容量规划输入，不作为对外承诺。

---

## 13. M1 关键决策记录（ADR）

| ADR | 决策 | 替代方案 | 理由 |
|---|---|---|---|
| ADR-01 | 单一 `ResearchWorkflow`，不用 child workflow | 每个子问题一个 child workflow | 拓扑中等复杂度；child 增加历史/调试成本，MVP 不划算 |
| ADR-02 | 三队列按资源类型隔离 | 单队列 | 防 activity 积压拖慢 workflow task；io/llm 独立扩缩 |
| ADR-03 | 进度走 Redis Stream + SSE 重放 | Temporal Query 轮询 | 支持 activity 粒度、多实例、断线续播；Query 太粗 |
| ADR-04 | API 用 Spring MVC + 虚拟线程 | WebFlux | DRS 是少量长连接；避免阻塞 MySQL 与响应式栈混用的复杂度；响应式故事留在网关 |
| ADR-05 | Jackson JSON + `.v1` 稳定别名 + DTO 只增 | Java 序列化/Proto | 兼容运行中的 workflow；避免部署期反序列化事故 |
| ADR-06 | 大对象落库，workflow 只传 ID | 正文作为 activity 入参 | 规避 2MB payload 限制与历史膨胀 |
| ADR-07 | 规则 + 跨模型 judge 双通道校验，报告 fail-closed | 只用 LLM judge | 引用真实性必须可确定性验证；跨模型降低自洽偏差 |
| ADR-08 | LLM 仅经 AI 网关 | DRS 直连 provider | 复用路由/限流/缓存/trace/计费，两项目成体系 |
| ADR-09 | Tavily 主 + Serper 备 | 单一 provider | 可用性 + 配额兜底；接口抽象便于换 Bing |
| ADR-10 | SSRF/提示注入防护为内置必做项 | 后补 | Agent 读取公网内容，这是安全底线也是面试加分点 |
| ADR-11 | 正文压缩存 MySQL，不存原始 HTML，30 天 | 对象存储/永久保存 | MVP 简化；保留引用校验与 Eval 所需 |
| ADR-12 | 单租户 API Key + 每 key 限流 | 完整多租户 | 多租户/计费归网关项目，避免范围膨胀 |

---

## 14. M1 交付物与 M2 启动条件

**M1 交付（本文档）：** 模块划分、Temporal 拓扑与 activity 契约、进度/SSE 方案、数据模型、API 契约、Agent 算法、故障矩阵、ADR。

**M2（工程基建）范围：**

1. `dra-parent` + 7 个模块骨架；ArchUnit 规则（workflow 禁止依赖 infra、contracts 禁止 Spring）。
2. Flyway 建表（§6.3）+ Temporal 独立库/用户。
3. `docker compose`：mysql、redis、temporal auto-setup、temporal-ui、api、worker-io、worker-llm。
4. 配置 profile、健康检查、JSON 日志、OTel 接入。
5. CI：`mvn verify`（单测 + ArchUnit + Testcontainers 冒烟）。
6. 垂直切片冒烟：提交任务 → 假的 plan/search/fetch activity → SSE 能看到事件 → kill worker 后任务继续。

**M2 完成标准：** `docker compose up` 一键起；提交任务拿到 SSE 进度；杀掉 worker 进程后任务自动恢复并成功结束；`mvn verify` 全绿。

**后续映射：** M6 规划/检索/抽取主链路 → M7 暂停恢复/预算/崩溃演练 → M8 校验/引用/冲突/报告 fail-closed → M9 Eval → M10 压测与部署。

---

## 15. 待确认的默认值（M1 冻结前请拍板）

1. **搜索 key**：M2 前你能拿到 Tavily API key 吗？如暂时没有，M2 先用 `mock` provider 打通链路，真实 key 到了只换配置（Serper 作为可选备份）。
2. **深度档位/预算**：QUICK 15 万 / STANDARD 50 万 / DEEP 150 万 token，单任务成本上限按网关价格设 ¥1~3；这组数字是否按你的预期调整？
3. **报告语言**：默认跟随提问语言（`auto`）；是否强制中文？
4. **抓取范围**：MVP 只做静态 HTML，PDF 与 JS 渲染页记失败并降级到搜索摘要；接受吗？
5. **鉴权方式**：MVP 仅 `X-API-Key` header（无 Web UI，CLI/curl 直连）；接受吗？

> 以上确认后 M1 冻结，进入 M2（工程基建：多模块骨架 + Docker Compose + CI + 垂直切片冒烟）。

---

## 16. M1 冻结记录（2026-11-11）

用户确认的最终基线：

1. **LLM**：先用国产模型 **DeepSeek**（用户已有 key），走 OpenAI 兼容接口、配置化；后续可无痛切到自研 AI 网关（项目 A）。M2 的 Plan/Extract/Verify/Report 仍为 stub，M6 接入真实模型。
2. **搜索**：先用 `MockSearchProvider` 打通链路，Tavily key 到位后只替换 `SearchPort` 适配器（Serper 备选）。
3. **预算档位**：QUICK 15 万 / STANDARD 50 万 / DEEP 150 万 token；单任务人民币成本上限在 M6 按 DeepSeek 实际价格校准。
4. **报告语言**：`auto`（跟随提问语言）。
5. **抓取范围**：MVP 仅静态 HTML；PDF / JS 渲染页降级到搜索摘要。
6. **鉴权**：MVP 仅 `X-API-Key`（SSE 额外允许 HTTPS 下 `api_key` 查询参数）。
7. 其余 M1 设计保持不变；M2 实现见 `README.md` 与代码。

**M6 实现补充（2026-11-11）**：LLM 通过 Spring AI 的 OpenAI 兼容客户端接入（`dra.llm.enabled` 控制真实/stub），默认指向 DeepSeek；规划、抽取、claim 合成、规则+跨模型校验已真实化并落库；Search/Fetch/Report/Conflict 仍为 stub，按 M6.5/M8 推进。

**M6.5 实现补充（2026-11-11）**：

- **Search**：`TavilySearchProvider`（`SearchPort` 适配器，`dra.search.provider=tavily` 时装配，`Authorization: Bearer` 调用 `/search`，`include_raw_content=false` 省配额）；每个子问题最多取 2 条 query、topK 去重后按 `topK*2` 截断。
- **Fetch**：`WebFetcher` + `OutboundHttpClient`（统一 `DRA_HTTP_PROXY`）。手动跟随重定向、逐跳 SSRF 校验（scheme/端口 80·443/DNS 解析后私网·回环·链路本地·CGNAT·IPv6 ULA 拒绝）；3MB 上限、gzip 解压、仅静态 HTML；正文用 Jsoup 去掉 script/style/nav 等后按 `article/main/[role=main]/body` 抽块，块间以空行分隔，截断 50k 字符。失败返回 `FETCH_*` 错误码并记 warning，不阻塞其他来源。
- **Extract 防幻觉强化**：`QuoteValidator.resolve` 先做索引映射的排版归一化（弯引号/破折号/省略号/NBSP/零宽字符），去包装引号；命中后**回填原文精确区间**存入 `evidence.quote` 与 `anchor_json={start,end}`。省略号拼接仅在每段按序命中时接受。日志区分 `repaired` 与 `rejected`（含前 80 字符样例）。
- **实测**：Tavily + 真实抓取 6 来源全部 OK，18 条 evidence 全部为原文精确子串且 anchor 复原一致，1 claim / 2 citation / `SUPPORTED`（LLM 用本地 mock 返回确定性 JSON）。真实 DeepSeek 下的一轮任务曾出现 15/15 quote 被拒（当时只有空白归一化，且余额随后耗尽无法复测）；现在日志会打印被拒样例，恢复可用 key 后按 `extract repaired/rejected` 计数确认改写率。

**M8 实现补充（2026-11-11）**：

- **冲突检测**：新增 `Phase.CONFLICT` 与 `conflict.detect.v1`（llm 队列，60s）。输入只带 `claimIds`（§4.6 payload 边界），activity 内读库拿 claim 与引用证据。服务端只接受两类内容：`claimId` 必须是本任务且通过校验的 claim；`urls` 必须出现在该 claim 的引用来源里——模型无法凭空制造分歧方或来源。冲突落 `conflict` 表（`claim_group_key` 存 topic）。**冲突目前不回调 claim confidence**（§8.7 公式里的 `−0.2×冲突数` 留到 M9 与置信度校准一起做）。
- **报告生成**：`report.generate.v1` 先经 `JdbcReportDataRepository` 读全量事实（task / sub_question / claim / citation / evidence / source / usage_record / conflict），再让模型写正文，最后由 `ReportRenderer`（纯确定性代码，位于 worker，无 Spring 依赖）渲染：`[[claimId]]` 校验与剥离、URL 去重编号、章节结构、参考来源、争议、方法与统计、局限与未采纳结论。未知标记触发一次修复重试，仍失败则 fail-closed 并记 `REPORT_MARKER_STRIPPED`；无可用结论时记 `REPORT_NO_VERIFIED_CLAIMS`。
- **结构化报告**：`ReportDocument`（sections / conflicts / references / stats / meta）写入 `report.meta_json`，`report.version` 按 `(taskId, version)` 递增；`GET /report` 默认返回 `{taskId, version, markdown, document}`，`?format=markdown` 只返回正文。stub 模式（`dra.llm.enabled=false`）复用同一渲染器，只是没有模型正文。
- **实测**：mock LLM + 真实 Tavily/抓取，Phase 走完 `CONFLICT → REPORT`：2 个章节（标题取自子问题）、2 条带编号引用的 SUPPORTED 结论、1 条并列冲突、统计（6 来源 / 18 证据 / 13200 tokens / 23s）；`ReportRendererTest` 覆盖编号去重、标记剥离、REFUTED 只进附录、无结论 fail-closed、冲突并列。

**M9.5 实现补充（2026-11-15，闭环 M8 遗留项）**：

- **冲突惩罚落地**：`ConflictActivitiesImpl` 在保存冲突后按 `confidence ← max(0, confidence − λ × min(冲突数,1))` 回写命中 claim（`ClaimRepository.updateConfidence`，λ 默认 0.2）。报告在 `CONFLICT` 之后生成，读到的是回写后的值；§8.7 的 `−0.2×冲突数` 不再是空话。
- **权重配置化**：`VerifyActivitiesImpl` 的 `judge/rule/diversity` 权重组移到 `dra.verify.confidence.*`，默认保持 M6 行为（0.5/0.3/0.2）。M1 §8.7 文档中的 0.5/0.3/0.2（多样性优先）与代码默认值方向不同，暂不擅自翻转，等评估集扩到能拟合分量的规模再定。
- **校准度量**：eval 侧已能按 claim 计算 ECE / Brier / 高置信准确率并门禁（样本 ≥100 且 ECE >0.15 时失败）；冲突惩罚由 `ConflictActivitiesImplTest` 覆盖（0.9→0.7、0.7→0.5）。

**M9.6 真实链路修复（2026-11-15）**：

- **参考文献排序**：`ReportRenderer.references()` 按编号排序（此前按 claim 顺序，导致 I6 编号不连续/正文倒序）。
- **warnings 贯通**：`ReportDocument.stats.warnings` 合并任务级 warnings；`/status` 从 `lastWarning` 改为返回完整 `warnings` 列表（Redis state 累积）；冲突告警改走 `addWarning`，使报告与状态页看到同一份列表。
- **文案统一**：任务 warning 统一为 `CODE: message`（此前报告与事件侧冒号后空格不一致，I8 会误判）。
- 评估侧同步修复 URL 存活检查无整体超时的问题（每请求 `orTimeout`），judge 输出上限从 2048 提到 4096 以避免 J2 JSON 被截断。




