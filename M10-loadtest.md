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
| S2 | 并发梯度 C=1/2/4/8 | 找拐点与排队增长 | 调度层曲线已出（§6.1）；真实链路 C=1/2 已跑，C=4/8 等 Tavily 额度 |
| S3 | 同并发扩 worker-llm/io 数量 | 吞吐曲线与瓶颈定位 | 待 M10 部署 |
| S4 | 压测中 kill worker | 成功率、恢复时间（Temporal 价值） | 已完成（mock，§6.2）；真实链路待复测 |
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

## 6.1 S2 调度层并发梯度（2026-11-17，mock 搜索 + stub LLM）

真实 Tavily 额度本月已用满（`/usage`：1001/1000，所有搜索返回 HTTP 432），无法补真实链路 C=4/C=8。为把调度层容量单独量出来，使用 `DRA_SEARCH_PROVIDER=mock`、`DRA_LLM_ENABLED=false`、`DRA_FETCH_MODE=stub` 跑完整梯度。**这组数字只反映本机 Temporal/MySQL/Redis/API 的调度与并发能力，不代表真实链路容量。**

| C | wall P50 | wall P95 | queue P50 | server P50 | submit P95 | 吞吐 (tasks/min) | 成功 |
|---|---|---|---|---|---|---|---|
| 1 | 16.3s | 17.4s | 0.1s | 15.9s | 0.12s | 3.57 | 8/8 |
| 2 | 16.2s | 16.3s | 0.1s | 15.2s | 0.17s | 7.49 | 8/8 |
| 4 | 15.2s | 18.1s | 0.1s | 14.9s | 0.69s | 14.39 | 8/8 |
| 8 | 16.6s | 16.6s | 0.2s | 15.6s | 0.27s | 28.89 | 8/8 |

结论：

- 吞吐随 C 线性增长（3.57 → 7.49 → 14.39 → 28.89，每档约 ×2），到 C=8 没有拐点。
- queue wait 全程 ≤0.2s，server run 稳定在 15~16s：瓶颈不在调度层；stub 任务耗时由固定 sleep 决定，本机 worker 并发（io=50 / llm=20）远高于 C=8。
- 与真实链路对照：真实 C=1/2 的 server run 恒为 138s、queue 0.1s，瓶颈是外部依赖串行等待而非本机容量；真实 C=4/C=8 需在 Tavily 额度恢复后补（见 §7）。

产物：`perf/runs/20261117T020556Z`（C=1）、`020811Z`（C=2）、`020450Z`（C=4）、`020530Z`（C=8）。

---

## 6.2 S4 故障演练（2026-11-17，mock 搜索 + stub LLM）

脚本 `scripts/fault-drill.sh`：起 API + worker-io（orchestration,io）+ worker-llm 三个进程，`dra-bench` 以 C=4 提交 8 个 QUICK 任务；提交后约 4s `kill -9` worker-io，20s 后重启。

| 指标 | 无故障（§6.1 C=4） | 演练 | 变化 |
|---|---|---|---|
| 成功率 | 8/8 | 8/8 | 0 |
| wall P50 | 15.2s | 17.3s | +2.1s |
| wall P95 | 18.1s | 47.0s | +28.9s |
| server P95 | ~16s | 44.5s | +28.5s |
| 吞吐 | 14.39/min | 7.47/min | −48% |
| 总时长 | 33.4s | 64.3s | +30.9s |

分任务看得很清楚：第一波 4 个在飞任务 wall/server ≈ 47s/44.5s；第二波 4 个（kill 后提交）正常 ≈ 16.3s。**8/8 成功、0 失败、0 重复副作用**——演练后 8 份 report / 16 条 claim，与无故障基线完全一致。

恢复时间分解：

- kill 发生在提交后约 4s，worker-io 停机 20s；
- 在飞 io activity 的 `startToCloseTimeout` 是 30s，worker 死后不会立刻重派，要等超时（约提交后 31s）才重试；此时 worker-io 已恢复约 11s；
- 因此这一轮的延迟几乎全部来自"无心跳 activity 的超时窗口"，而不是 Temporal 恢复本身。

结论：Temporal 在 worker 进程崩溃后自动重派 activity、补齐 workflow 状态，全部任务最终成功；代价是**一个 activity 超时窗口**。可优化项：给 search/fetch 这类长 activity 加 heartbeat，把故障检测从 30s 降到秒级（已在 §6.3 落地并复测）。

Caveat：单机 Temporal、mock 依赖、单次演练；真实链路（外部依赖 + 真实 LLM）的恢复时间还需额度恢复后复测。

---

## 6.3 S4 复测：activity heartbeat（2026-11-17）

改动：

- 新增 `HeartbeatGuard`：把阻塞的 io 调用放到 helper 线程，activity 线程每 2s heartbeat 一次；不在 activity 上下文（单测直接调用）时自动降级为普通等待。
- workflow 给 search/fetch 设置 `setHeartbeatTimeout(5s)`；Search/Fetch 的真实与 stub 实现都用 guard 包裹。
- `scripts/fault-drill.sh` 增加 `SKIP_KILL=1` 控制组；用 `DRA_STUB_SEARCH_MS` 拉长 activity，确保 kill 落在执行中。

验证方式：2 任务 / C=2，`DRA_STUB_SEARCH_MS=15000`，kill 落在 search 执行中；同配置不 kill 作为对照（wall 64.9s）。

Temporal history 的直接证据（kill 后 5s 判定超时、worker 重启后立即重派）：

```
ACTIVITY_TASK_STARTED attempt=2
lastFailure: "activity Heartbeat timeout" (TIMEOUT_TYPE_HEARTBEAT)
```

结果：2/2 成功，wall 82.5s（对照 64.9s，+17.6s）。这 17.6s 由两部分构成：被杀的 search 已执行约 10s、重试从头再跑（+10s）；worker 停机 + Spring 启动约 10s。**不再包含 30s 的 startToClose 等待**。

对比 §6.2（无 heartbeat）：恢复检测固定为 5s 心跳窗口；停机时间越长，收益越接近原来的 25s。真实链路的恢复时间（外部依赖 + 真实 LLM）仍需额度恢复后复测。

---

## 7. 下一步

1. **真实链路基线**：QUICK × 10，C=4，记录 wall/queue/server 分位与成本（约 ¥1.5~3）。
2. **并发梯度 + 扩 worker**：C=1/2/4/8 对齐 `docker compose --scale worker-llm=N`，出"扩 worker 后 QPS 曲线"。
3. ✅ **故障演练（S4，mock）已完成**，并已用 activity heartbeat 把恢复检测压到 5s（§6.2/§6.3）；真实链路恢复时间待额度恢复后复测。
4. **SSE 场景**：新增 `--sse-clients` 模式，测 500 连接/实例下的内存与事件延迟。
5. **固定镜像版本**：M10 部署时把 Temporal/UI 从 `latest` 固定到具体版本。
