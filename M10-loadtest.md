# M10 · 压测与容量（骨架）

> 输入基线：`M0-scope.md` §6（验收指标）、`M1-architecture.md` §12（容量假设）。
> 本文只做**压测骨架**：跑得通、指标口径固定、产物可复现。容量结论要等真实 LLM + 多 worker 跑过才算数。

---

## 1. 目标与边界

**要回答的问题**

1. 给定 worker 数量，C 个并发任务时的吞吐与延迟分布是多少？
2. 延迟花在哪：排队（queue wait）、服务端执行（server run）、还是客户端轮询开销？
3. 扩 worker 后曲线怎么变？瓶颈在 Temporal 调度、MySQL、搜索配额还是 LLM？
4. 故障注入（杀 worker / 杀 API）时，成功率与恢复时间是多少？

**In Scope（本次骨架）**

- `dra-bench` CLI：提交 N 个任务、C 并发、轮询到终态，输出吞吐 / 延迟分位 / 排队时间 / 阶段分布。
- 阈值门禁与退出码，可直接接 CI。
- 产物：`perf/runs/<runId>/`（run.json / metrics.json / tasks.jsonl / summary.md）。

**Out of Scope（后续或不做）**

- ❌ 分布式压测（单机发压即可覆盖本次规模）
- ❌ SSE 连接数压测（单独场景，当前 CLI 只轮询）
- ❌ 长稳测试（>1 小时）与内存火焰图
- ❌ 与网关项目的联合压测（等网关落地）

---

## 2. 工具形态

`dra-bench` 是独立模块，**不依赖 Spring**，JDK 21 + `HttpClient`，可执行 jar 约 2.6 MB：

- 黑盒：只走公开 REST API（`POST /tasks`、`GET /tasks/{id}`），不读库。
- **只轮询、不订阅 SSE**：把"进度推送"与"任务吞吐"两类负载分开，数字才有可比性。
- 每个任务独立 `Idempotency-Key = bench:<runId>:<index>`，重复运行不冲突。
- 客户端用服务端时间戳拆解：`queueWait = startedAt − createdAt`，`serverRun = finishedAt − startedAt`；`wall` 是客户端从 submit 到观察到终态的墙钟。

```bash
mvn -q -pl dra-bench -am package

# 默认 10 个任务、并发 4、QUICK
java -jar dra-bench/target/dra-bench-0.1.0-SNAPSHOT.jar --tasks=10 --concurrency=4

# 指定问题池 / 压 STANDARD / 带 ramp
java -jar dra-bench/target/dra-bench-0.1.0-SNAPSHOT.jar \
  --tasks=20 --concurrency=8 --depth=STANDARD --ramp-seconds=30 \
  --questions=perf/questions.txt --max-p95-seconds=300
```

---

## 3. 指标口径

| 指标 | 口径 | 用途 |
|---|---|---|
| successRate | `SUCCEEDED / tasks` | 门禁（默认 ≥ 0.98） |
| throughput | `succeeded × 60 000 / durationMs`（tasks/min） | 容量曲线 |
| wall P50/P95/P99 | 客户端 submit → 观察到终态 | 用户体验 |
| submit P50/P95 | `POST /tasks` 往返 | API/受理链路 |
| queue wait P50/P95 | `startedAt − createdAt` | Temporal 排队 / worker 饱和 |
| server run P50/P95 | `finishedAt − startedAt` | 单任务执行耗时 |
| tokens / cost | 终态 usage 求和 | 单任务成本 |
| statusCounts / stopReasons | 终态分布 | 失败与降级归因 |
| phase presence | 每个任务首次见到的阶段集合 | 粗粒度阶段覆盖（不是阶段耗时） |

> 阶段耗时需要 SSE 事件时间线，当前只做"是否经过"的诊断；M10 后续用 SSE 场景补。

---

## 4. 场景清单

| 编号 | 场景 | 目的 | 状态 |
|---|---|---|---|
| S1 | QUICK × N，C=4 | 基线数字 | 骨架已跑通 |
| S2 | 并发梯度 C=1/2/4/8 | 找拐点与排队增长 | 待真实 LLM |
| S3 | 同并发扩 worker-llm/io 数量 | 吞吐曲线与瓶颈定位 | 待 M10 部署 |
| S4 | 压测中 kill worker | 成功率、恢复时间（Temporal 价值） | 复用 `scripts/crash-test.sh` 思路 |
| S5 | SSE 连接数（500/API 实例） | 进度通道容量 | 待实现 |

---

## 5. 产物与退出码

```
perf/runs/<runId>/
  run.json      # 配置（API key 只记录是否设置，不落明文）、运行时信息
  metrics.json  # 聚合指标
  tasks.jsonl   # 每任务：状态、submit/wall/queue/server、tokens、stopReason、phases
  summary.md    # 人读：headline、状态分布、阶段、分任务表、门禁
```

| 码 | 含义 |
|---|---|
| 0 | 通过（成功率与 P95 门禁均满足） |
| 1 | 门禁失败（成功率或 P95） |
| 2 | 参数错误 |
| 3 | 基础设施错误（API 不可达导致有任务 submit/status 失败） |

---

## 6. 骨架验收（2026-11-15，本机）

| 项 | 结果 |
|---|---|
| 构建 | `mvn -pl dra-bench -am test`：22 个单测全绿 |
| 2 任务 / C=2 / QUICK | 2/2 成功，wall P50/P95 = 20.4s，queue wait 2.0s，server run 17.3s，吞吐 5.89 tasks/min，exit 0 |
| 门禁失败 | `--max-p95-seconds=0.001` → exit 1，summary 标 FAIL |
| API 不可达 | `--api-url=http://127.0.0.1:9` → exit 3，infra errors=1 |
| 参数校验 | `--min-success-rate=1.01` → exit 2 + usage |

> 以上是 **stub 模式**（mock LLM / stub 搜索抓取）的骨架验证，数字只证明管道正确，不代表真实容量。

---

## 7. 下一步

1. **真实链路基线**：QUICK × 10，C=4，记录 wall/queue/server 分位与成本（约 ¥1.5~3）。
2. **并发梯度 + 扩 worker**：C=1/2/4/8 对齐 `docker compose --scale worker-llm=N`，出"扩 worker 后 QPS 曲线"。
3. **故障演练**：压测中 kill worker-io，统计成功率与恢复时间，补 S4。
4. **SSE 场景**：新增 `--sse-clients` 模式，测 500 连接/实例下的内存与事件延迟。
5. **固定镜像版本**：M10 部署时把 Temporal/UI 从 `latest` 固定到具体版本。
