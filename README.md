# Deep Research Agent（M2~M8）

Java 21 + Spring Boot 3 + **纯 Temporal** 编排的 Deep Research Agent。设计基线见：

- [`M0-scope.md`](M0-scope.md)：范围、验收指标、不做什么
- [`M1-architecture.md`](M1-architecture.md)：模块、workflow/activity 契约、数据模型、API、ADR

当前交付：

- **M2**：多模块骨架 + Flyway 数据层 + Redis 进度流 + REST/SSE + Temporal 三队列 worker + 崩溃恢复。
- **M6**：真实 LLM 客户端（Spring AI OpenAI 兼容 → DeepSeek）；**真实规划、证据抽取、claim 合成、规则 + 跨模型校验**已接通并落库。
- **M6.5**：**真实数据源**。`Tavily` 搜索（`dra.search.provider=tavily`）+ 真实网页抓取（`dra.fetch.mode=http`，JDK HttpClient + SSRF 防护 + 逐跳重定向校验 + readability 启发式正文抽取）。证据 quote 由服务端回填为原文精确区间（`anchor_json`），Citation 可回溯到段落。
- **M8**：**可交付报告 + 冲突检测**。冲突检测独立成 `conflict.detect.v1`（只并列呈现、不裁决）；报告由模型写正文、**代码拼装事实**：`[[claimId]]` 标记校验失败 fail-closed 剥离、参考文献统一编号去重、统计/方法/局限/未采纳结论章节全部由服务端生成，`GET /report` 同时返回 Markdown 与结构化 JSON。

## 快速开始

### 0. 前置

- JDK 21、Maven 3.9+、Docker Desktop（Compose v2）
- 本机便携工具链（本仓库开发环境）：
  - `~/.tools/jdk21.0.13_0`（Amazon Corretto 21）
  - `~/.tools/apache-maven-3.9.11`
  - Maven 镜像：`~/.m2/settings.xml` 指向阿里云（Maven Central 下载大 jar 会断流）

Git Bash 里可以这样设置：

```bash
export JAVA_HOME=/c/Users/lxy/.tools/jdk21.0.13_0
export PATH=/c/Users/lxy/.tools/apache-maven-3.9.11/bin:$PATH
```

### 1. 起基础设施

```bash
docker compose up -d mysql redis temporal temporal-ui
```

- Temporal：`localhost:7233`
- Temporal UI：http://localhost:8233
- MySQL：`localhost:3306`，库 `dra`（root/root）
- Redis：`localhost:6379`

### 2. 构建

```bash
mvn -B -DskipTests package
```

### 3. 起应用（两种方式）

**方式 A：本地进程（开发推荐）**

```bash
java -jar dra-api/target/dra-api-0.1.0-SNAPSHOT.jar
java -jar dra-worker/target/dra-worker-0.1.0-SNAPSHOT.jar
```

默认 worker 启动全部角色：`orchestration,io,llm`。也可以拆开：

```bash
DRA_WORKER_ROLES=orchestration,io java -jar dra-worker/target/dra-worker-0.1.0-SNAPSHOT.jar
DRA_WORKER_ROLES=llm java -jar dra-worker/target/dra-worker-0.1.0-SNAPSHOT.jar
```

**方式 B：全部进 Docker**

```bash
mvn -B -DskipTests package
docker compose --profile app up -d --build
```

### 3.1 启用真实 LLM（DeepSeek / 任意 OpenAI 兼容端点）

默认 `dra.llm.enabled=false`，全部走确定性 stub，不需要 key。启用后 **Plan / Extract / Claim / Verify / Conflict / Report** 走真实模型（Search / Fetch 的真实数据源见 §3.2）。

```bash
export DRA_LLM_ENABLED=true
export DRA_LLM_BASE_URL=https://api.deepseek.com
export DRA_LLM_API_KEY=sk-...                 # 只放环境变量，不要提交
export DRA_LLM_PLAN_MODEL=deepseek-chat
export DRA_LLM_EXTRACT_MODEL=deepseek-chat
export DRA_LLM_CLAIM_MODEL=deepseek-chat
export DRA_LLM_VERIFY_MODEL=deepseek-reasoner # 校验尽量用与生成不同的模型
java -jar dra-worker/target/dra-worker-0.1.0-SNAPSHOT.jar
```

> 只有 worker 需要 LLM 配置；API 不直接调用模型。`DRA_LLM_BASE_URL` 后续可无痛切到自研 AI 网关（OpenAI 兼容）。

**没有 key 也能验证真实代码路径**（本地 mock，返回确定性 JSON）：

```bash
python scripts/mock-llm.py 8099
DRA_LLM_ENABLED=true DRA_LLM_BASE_URL=http://127.0.0.1:8099 DRA_LLM_API_KEY=mock \
  java -jar dra-worker/target/dra-worker-0.1.0-SNAPSHOT.jar
```

M6 的降级语义：

- 规划 JSON 非法 → 带错误修复一次 → 仍失败则确定性 fallback，workflow 记 `PLAN_DEGRADED`。
- 抽取 quote 必须能命中原文，否则丢弃；日志记录被拒绝条数与样例。
- claim 必须绑定真实 `evidenceId`，否则丢弃；无 claim 时用证据生成降级 claim。
- 校验先跑规则通道；规则失败直接 `UNSUPPORTED`，不调用 judge；LLM 校验失败时退化为 rule-only `PARTIAL`。

### 3.2 启用真实搜索与抓取（M6.5）

默认 `dra.search.provider=mock`、`dra.fetch.mode=stub`。换成真实数据源只改配置，不动代码：

```bash
export DRA_SEARCH_PROVIDER=tavily
export TAVILY_API_KEY=tvly-...            # 只放环境变量，不要提交
export DRA_FETCH_MODE=http                # http = 真实抓取
export DRA_HTTP_PROXY=http://127.0.0.1:7890   # 需要代理才能出网时（Tavily 与抓取共用）
java -jar dra-worker/target/dra-worker-0.1.0-SNAPSHOT.jar
```

抓取器的约束（M1 §8.3）：仅 `text/html` / `text/plain` / `application/xhtml+xml`，静态 HTML，不做 JS 渲染与 PDF；单页上限 3MB、正文截断 50k 字符；手动跟随重定向 ≤ 3 跳且**每一跳重新做 SSRF 校验**（仅 http/https、端口 80/443、拒绝私网/回环/链路本地/云元数据地址）。`DRA_FETCH_MODE=http` 时抓取失败记 `FETCH_*` 错误码并在任务里记 warning，不影响其他来源。

**quote 防幻觉闸门（M6.5 强化）**：模型只负责"定位"，服务端负责"回填"。模型给出的 quote 会先做空白/排版归一化（弯引号、破折号、省略号、NBSP、软连字符），去掉模型自己加的包装引号；命中后**存回原文的精确区间**并写入 `evidence.anchor_json`（`{"start":n,"end":m}`），所以库里每条 quote 都是 `source_content` 的逐字子串。省略号拼接只在每一段都按顺序命中原文时才接受，其余一律丢弃并记 `EXTRACT_EMPTY` warning。日志会打印 `extract repaired N quotes...` / `extract rejected N non-verbatim quotes...`，便于观察模型改写率。

> 真实链路验证（2026-11-11）：Tavily 搜索 + 真实抓取 6 个来源全部 `fetch_status=OK`，抽取 18 条 evidence（全部为原文精确子串且 anchor 可还原）、1 claim、2 citation、`SUPPORTED`，报告无 warning。

### 3.3 报告与冲突（M8）

报告分两层：**模型只写正文，事实由代码拼装**。

- **冲突检测**（`conflict.detect.v1`，Phase=`CONFLICT`）：把已通过校验的 claims + 引用摘要喂给模型，产出 `{topic, summary, positions[]}`。服务端只接受 `claimId` 属于本任务、且 URL 出现在该 claim 引用里的 position，其余丢弃；冲突落 `conflict` 表，**只并列呈现，不裁决**。
- **报告正文**：模型输出 Markdown + `[[claimId]]` 标记；`REFUTED/UNSUPPORTED` 的 claim 不进入正文，只进附录。
- **fail-closed 渲染**（`ReportRenderer`，纯确定性代码）：
  1. 每个 `[[claimId]]` 必须能解析到本次输入中的可渲染 claim；解析失败的标记被删除、记 `REPORT_MARKER_STRIPPED`，并触发一次模型修复重试，仍失败则按 fail-closed 输出。
  2. 参考文献由引用 URL 去重后统一编号 `[1][2]…`，编号顺序 = 正文首次出现顺序。
  3. 「参考来源」「争议与反证」「方法与执行统计」「局限与未采纳结论」四节全部由代码生成，模型无法改写统计与元信息。
  4. 没有任何通过校验的结论时，正文直接给出"未形成可验证结论"并记 `REPORT_NO_VERIFIED_CLAIMS`。
- **结构化输出**：报告同时存 Markdown 与 `report.meta_json`（`ReportDocument`：sections / conflicts / references / stats / meta），`GET /report` 默认返回结构化 JSON，`?format=markdown` 返回渲染后的 Markdown。`report.version` 递增，便于复跑对比。

```bash
curl -sS "http://localhost:8080/api/v1/research/tasks/<taskId>/report" -H 'X-API-Key: dev-key'
curl -sS "http://localhost:8080/api/v1/research/tasks/<taskId>/report?format=markdown" -H 'X-API-Key: dev-key'
```

> 验证（2026-11-11）：mock LLM + 真实 Tavily/抓取跑通 `CONFLICT → REPORT`，产出 2 个章节（标题取自子问题）、2 条带编号引用的结论、1 条并列冲突、完整统计（6 来源 / 18 证据 / 13200 tokens / 23s），`report.meta_json` 与 Markdown 内容一致。

### 3.4 评估 CLI（M9.2 ~ M9.4）

`dra-eval` 是独立 CLI：黑盒走 REST 提交任务，白盒读 MySQL 做不变量与规则打分，judge 走独立模型端点（设计与口径见 `M9-eval.md`）。

```bash
mvn -q -pl dra-eval -am package

# 不花钱：只打印工作量与成本预估，不发请求
java -jar dra-eval/target/dra-eval-0.1.0-SNAPSHOT.jar --mode=estimate --limit=3

# 不花钱：mock LLM + mock 搜索 + stub 抓取，验证管道、不变量与产物（judge 强制关闭）
java -jar dra-eval/target/dra-eval-0.1.0-SNAPSHOT.jar --mode=self-test --limit=2 --parallel=2

# 真实链路 + 外部 judge（必须显式给 judge key；生成仍由 worker 配置决定）
java -jar dra-eval/target/dra-eval-0.1.0-SNAPSHOT.jar --mode=real --judge-enabled=true \
  --judge-api-key=$DRA_EVAL_JUDGE_API_KEY --judge-model=deepseek-reasoner --max-cost-cny=3

# 冻结基线 / 后续运行自动 diff（dataset、depth、judge 配置不同则标 not comparable）
java -jar dra-eval/target/dra-eval-0.1.0-SNAPSHOT.jar --mode=real ... \
  --baseline=eval/baselines/baseline-v1.json --freeze-baseline=true

# 复用已有 run 重新打分（不提交任务；judge 命中缓存时成本为 0）
java -jar dra-eval/target/dra-eval-0.1.0-SNAPSHOT.jar --mode=rescore \
  --rescore-from=eval/runs/<runId> --judge-enabled=true --judge-api-key=$DRA_EVAL_JUDGE_API_KEY

# 人工标注后算 κ；κ < 0.7 退出码 1
java -jar dra-eval/target/dra-eval-0.1.0-SNAPSHOT.jar --mode=calibrate \
  --labels=eval/labels/<runId>.jsonl
```

产物：`eval/runs/<runId>/`（`run.json` / `task-index.json` / `per-question.jsonl` / `invariants.json` / `thresholds.json` / `baseline-diff.json` / `calibration.json` / `metrics.json` / `summary.md`）；
judge 缓存写在 `eval/cache/judge/`（内容寻址、全局），人工标注模板写在 `eval/labels/<runId>.jsonl`，冻结基线放 `eval/baselines/`。

退出码优先级：不变量 `2` > 基础设施 `3` > 成本护栏 `4` > κ / 阈值 / 基线回退 `1` > 通过 `0`。

阈值默认取 M0 验收线（CAR ≥ 0.85、HFR ≥ 0.90、GCR ≥ 0.80、矛盾率 ≤ 0.03、成功率 ≥ 0.98），可用 `--min-car/--min-hfr/--min-gcr/--max-contradiction-rate/--min-success-rate` 覆盖，`--thresholds-enabled=false` 关闭。
基线 diff：比例指标相对 > 5% 或绝对 > 3pp 判回退；时延/成本容忍 +15%；`rescore` 不做性能对比。
置信度校准：`confidence-calibration.json` + `calibration-samples.jsonl`，ECE 在样本 ≥ `--confidence-min-samples`（默认 100）时按 `--confidence-max-ece`（默认 0.15）门禁；URL 存活检查默认开启（`self-test` 强制关闭）。

> 状态（2026-11-15）：`self-test` / `estimate` / `real`+judge / `rescore` / `calibrate` 均可用。
> judge 三任务：J1 逐条引用支撑（每 claim 一次调用）、J2 金标加权覆盖（每题一次）、J3 exact 题对错（每题一次）；
> 输出 CAR/HFR/CSR/GCR/矛盾率/claim 精度，judge 成本单独记账。
> M9.5：冲突回写已生效（`dra.verify.confidence.*`，默认每个冲突 −0.2、最多计 1 个）；ECE/Brier 已可计算并门禁。
>
> **真实链路实测（2026-11-15，8 题 QUICK，DeepSeek + Tavily + 真实抓取）**：
> 8/8 成功、**0 不变量违反**；208 claims / 252 citations；锚点 1.000、URL 存活 1.000、HFR 0.990、CSR 0.831；
> **CAR strict 0.741 / weighted 0.867**、**GCR 0.610**、ECE 0.109、高置信准确率 0.881；
> P50/P95 129s/177s，单轮成本约 ¥2.64（生成 ¥1.03 + judge ¥1.60）。
> 阈值门禁因 CAR strict 与 GCR 未达标 → exit 1；基线冻结在 `eval/baselines/baseline-v1.json`，
> `rescore` 复核 `comparable=true`、无回退、judge 217/219 命中缓存、成本 0。
> 真实链路还发现并修复了 5 个缺陷（参考文献排序、报告 warnings 丢失、status 只回 lastWarning、
> URL 检查无整体超时、judge 输出截断），详见 `M9-eval.md` §18。
> 仍未做：权重网格拟合（缺分量落库）；self-test 仍容忍 `I3`/`I8`，`real` 模式下是阻塞项。

### 3.5 Web 测试台（M10 演示）

启动 `dra-api` 后打开 <http://localhost:8080/>（默认 API Key `dev-key`，可用 `DRA_API_KEY` 覆盖）。

- 左侧提交问题、选择 QUICK / STANDARD / DEEP，或从「最近任务」里挑一个；Ctrl/⌘+Enter 提交。
- 右侧实时显示 SSE 阶段、进度、tokens、成本，支持暂停 / 恢复 / 取消；告警与完成状态同屏可见。
- 完成后渲染结构化报告：结论与置信度、逐条引用原文、争议、参考来源、统计；可切换 Markdown、事件流、原始 JSON，并下载 `.md`。
- 页面是纯静态资源（`dra-api/src/main/resources/static/`，零构建、无 CDN）；静态页面本身不鉴权，`/api/**` 仍要求 `X-API-Key`；EventSource 用 `?api_key=` 传 key（仅限本地测试）。

### 4. 冒烟

```bash
./scripts/smoke-test.sh
```

它会提交任务、打印 SSE 事件流、状态和报告（stub 报告）。预期看到 `phase: PLAN → SEARCH → FETCH → EXTRACT → COVERAGE → SYNTHESIZE → VERIFY → CONFLICT → REPORT → DONE`，最终 `SUCCEEDED`。

> Windows/Git Bash 注意：脚本用 `--data-binary @-` 传中文请求体。直接把中文写在 curl 参数里会被 MSYS 转成 GBK，服务端报 `Invalid UTF-8`。

### 5. 崩溃恢复演练（M0 验收项）

**方式 A：Docker**

```bash
docker compose --profile app up -d --build   # 应用跑在容器里
./scripts/crash-test.sh
```

脚本会在任务执行途中 `kill` worker-io，然后重启，验证 Temporal 从 event history 恢复、任务最终 `SUCCEEDED`。

**方式 B：本地进程（已实测）**

```bash
# 终端 1：提交一个 STANDARD 任务后，在任务 RUNNING 时杀掉 worker
jps -l | grep dra-worker
taskkill //F //PID <pid>

# 终端 2：重启 worker，观察任务继续
java -jar dra-worker/target/dra-worker-0.1.0-SNAPSHOT.jar

# 轮询
curl -sS http://localhost:8080/api/v1/research/tasks/<taskId> -H 'X-API-Key: dev-key'
```

实测结果：worker 在 `FETCH` 阶段被 `taskkill` 后，任务保持 `RUNNING`；worker 重启后从 `FETCH` 继续，最终 `SUCCEEDED`。暂停/恢复/取消同样已验证（`PAUSED → RESUMED → SUCCEEDED`，取消终态 `CANCELED`）。

### 6. 压测（M10）

`dra-bench` 是独立 CLI（无 Spring，约 2.6 MB），只走公开 REST API 并轮询状态，不订阅 SSE——把「任务吞吐」与「进度推送」两类负载分开，数字才有可比性。

```bash
mvn -q -pl dra-bench -am package

# 快速冒烟：2 任务 / 并发 2
java -jar dra-bench/target/dra-bench-0.1.0-SNAPSHOT.jar --tasks=2 --concurrency=2 --depth=QUICK

# 基线：10 任务 / 并发 4，P95 门禁 300s
java -jar dra-bench/target/dra-bench-0.1.0-SNAPSHOT.jar --tasks=10 --concurrency=4 --max-p95-seconds=300
```

输出 `perf/runs/<runId>/`：`metrics.json`、`tasks.jsonl`、`summary.md`（吞吐、wall/submit/queue/server 分位、状态与 stopReason 分布、阶段覆盖）。
退出码：`0` 通过 · `1` 成功率/P95 门禁失败 · `2` 参数错误 · `3` API 不可达。

> 骨架实测（2026-11-15，stub）：2/2 成功、wall P95 20.4s、queue wait 2.0s、server run 17.3s、exit 0；
> `--max-p95-seconds=0.001` → exit 1；API 不可达 → exit 3。场景清单与后续见 `M10-loadtest.md`。

## API（M1 §7 的 MVP 子集）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/research/tasks` | 提交任务，必须带 `Idempotency-Key`；返回 202 |
| GET | `/api/v1/research/tasks/{id}` | 状态/阶段/进度/用量 |
| GET | `/api/v1/research/tasks/{id}/events` | SSE 进度流（支持 `Last-Event-ID` 重放） |
| POST | `/api/v1/research/tasks/{id}/pause` | 暂停（signal） |
| POST | `/api/v1/research/tasks/{id}/resume` | 恢复（signal） |
| POST | `/api/v1/research/tasks/{id}/cancel` | 取消（Temporal cancellation） |
| GET | `/api/v1/research/tasks/{id}/report?format=markdown\|json` | 报告；JSON 含 sections/conflicts/references/stats |
| GET | `/api/v1/research/tasks?limit=20` | 最近任务 |

默认 API Key：`dev-key`（`DRA_API_KEY` 覆盖）。health：`/actuator/health` 不鉴权。

```bash
curl -sS -X POST http://localhost:8080/api/v1/research/tasks \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: dev-key' \
  -H 'Idempotency-Key: demo-1' \
  -d '{"question":"Temporal 和 LangGraph 在长任务编排上的取舍？","depth":"STANDARD"}'

curl -N http://localhost:8080/api/v1/research/tasks/<taskId>/events -H 'X-API-Key: dev-key'
```

## 模块

```
dra-common       ULID、错误码、哈希
dra-contracts    @WorkflowInterface / @ActivityInterface、DTO（无 Spring、无 IO）
dra-domain       领域模型 + 端口（TaskRepository/SearchPort/ProgressStream...）
dra-infra        MySQL/Redis/Temporal 适配、Flyway、mock 搜索
dra-worker       ResearchWorkflow、activity（真实 Task/Event + stub 各阶段）
dra-api          REST/SSE/API Key/Temporal client + Web 测试台
dra-eval         M9 评估 CLI（M9.5：规则轨 + judge + 缓存 + κ + 阈值/基线 + ECE）
dra-bench        M10 压测 CLI（提交/轮询、吞吐与延迟分位、门禁）
```

依赖规则由 ArchUnit 守护：workflow 不得依赖 infra/Spring；contracts 不得依赖 Spring/实现模块；domain 不得依赖 infra。

## 关键实现点

- **workflow 只流转 ID 与小结构体**：抓取的正文落 `source_content`，activity 之间只传 `sourceId`。
- **进度**：activity → Redis Stream（`drs:task:{id}:events`，MAXLEN≈2000，7 天）+ 状态 Hash；API 用虚拟线程阻塞 `XREAD` 转 SSE，支持 `Last-Event-ID`；Redis 故障不影响任务执行，只影响实时进度。
- **任务队列**：`drs-orchestration` / `drs-io` / `drs-llm` 分离；io 与 llm worker 可独立扩缩。
- **暂停/恢复/取消**：Temporal signal + 取消；取消/失败在 detached cancellation scope 中写终态，避免"永远 RUNNING"。
- **预算与停止条件**：workflow 内存计数 + 每轮检查 token/deadline；停止原因写入任务与报告。
- **活动契约版本化**：`plan.create.v1` 等稳定别名，DTO 只增不改。
- **报告 fail-closed**：模型只写正文，标记解析、参考文献编号、统计/方法/局限/冲突章节由 `ReportRenderer` 生成；不可解析的 `[[claimId]]` 一律剥离并记 warning，未通过校验的 claim 不进正文。

## 当前 stub 与下一步

| 阶段 | 现在 | 后续 |
|---|---|---|
| Plan / Extract / Claim / Verify | **真实 LLM**（`dra.llm.enabled=true`），带校验、修复重试与降级 | 质量调优 + 真实评估 |
| Search | **Tavily**（`dra.search.provider=tavily`，默认 mock） | Serper 备链 + 每 provider 令牌桶（M10） |
| Fetch | **真实抓取**（`dra.fetch.mode=http`，SSRF 防护 + readability，默认 stub） | JS 渲染页 / PDF 走网关侧服务（二期） |
| Report | **可交付**：编号引用 + fail-closed 渲染 + 结构化 JSON | M9：外部一致性评估 |
| Conflict | **已实现**：`conflict.detect.v1`，只并列呈现不裁决 | M8+：按 importance 触发定向补证 |
| Eval | **M9.6 真实链路已跑通**：8 题 QUICK、0 不变量违反；CAR strict 0.741 / weighted 0.867、GCR 0.610、HFR 0.990、ECE 0.109；基线 `baseline-v1` 已冻结 | 收紧抽取/claim 提示词提升 CAR；DEEP 档复测覆盖率；权重拟合待分量落库 |

配置见 `dra-api/src/main/resources/application.yml`、`dra-worker/src/main/resources/application.yml`，均可被环境变量覆盖。LLM 价格参数（`DRA_LLM_INPUT_MICROS_PER_M` / `OUTPUT_MICROS_PER_M`）默认按 ¥2/¥8 每百万 token 估算，按实际供应商价格调整。

## 测试

```bash
mvn -B test
```

- `ResearchWorkflowTest`：TestWorkflowEnvironment 跑完整拓扑（stub activities），验证终态、reportId、usage、stopReason。
- `ArchitectureTest`：ArchUnit 分层约束。
- `IdsTest` / `UrlCanonicalizerTest` / `QuoteValidatorTest`：纯单测。

M6 验收记录（2026-11-11，本地 mock LLM 跑通真实代码路径）：

| 项 | 结果 |
|---|---|
| 真实规划 | `plan.v1` 生成 3 个子问题并落 `task_plan` / `sub_question` |
| 真实抽取 | 6 个来源抽出 18 条 evidence；编造的 quote 被规则丢弃 |
| claim + citation | 1 条 claim、2 条 citation（claim 必须绑定 evidence） |
| 引用校验 | 规则 PASS + judge SUPPORT → `SUPPORTED`；规则 FAIL 时 judge 不执行 |
| usage | `usage_record` 记录 plan/extract/claim/verify 各自的 token/成本 |
| 测试 | worker 11 个测试（规划/抽取/校验单测 + 全流程 workflow + ArchUnit） |

> 以上用 mock 模型验证代码路径；真实 DeepSeek 只需设置 §3.1 的环境变量并重启 worker。

## M2 验收记录（2026-11-11，本机实测）

| 项 | 结果 |
|---|---|
| 全量构建 | `mvn -B test` 通过：common 3、domain 7、worker 4（含 TestWorkflowEnvironment 全流程 + ArchUnit 3 条） |
| 数据层 | MySQL 8.4 + Flyway 自动建 13 张表 |
| 端到端 | 提交 → SSE 实时事件（PLAN…DONE）→ `SUCCEEDED` → 报告可取 |
| 幂等 | 相同 Idempotency-Key 重放返回同一任务；不同请求体返回 409 |
| 崩溃恢复 | `taskkill` worker 于 FETCH 阶段 → 任务保持 RUNNING → 重启 worker 后继续 → SUCCEEDED |
| 暂停/恢复 | `PAUSED` → `RESUMED` → `SUCCEEDED` |
| 取消 | 终态 `CANCELED`，detached scope 正常写终态 |
| Temporal | Docker `auto-setup` + MySQL 持久化（`DB=mysql8`）；亦可用 Temporal CLI dev server 替代 |

> Docker 的 `app` profile（api/worker 容器）已提供 Dockerfile 与 compose 配置，但本机验收使用本地进程方式；首次使用需能拉取 `eclipse-temurin:21-jre` 基础镜像。

## 已知限制（当前范围）

- Report / Conflict 仍是 stub，**报告还不是最终可交付形态**（M8 做 fail-closed 渲染、引用编号与冲突标注）；Search / Fetch 已接真实数据源，质量差异主要来自抓取到的静态正文（JS 渲染页会退化为摘要）。
- 真实 LLM 需要 `DRA_LLM_API_KEY`；mock 模式只能验证代码路径，不能评估研究质量。
- 规划/抽取/claim/校验的 prompt 仍是 v1，M9 用评估集迭代。
- Compose 的 Temporal/UI 用 `latest` 镜像，M10 部署时固定版本；Temporal 使用 `DB=mysql8`（新版 auto-setup 的驱动名）。
- 国内网络下 Docker Hub 可能断流：可用镜像加速地址拉取后重新 tag（本项目实测 `docker.m.daocloud.io` / Docker Desktop 已配置的镜像源）。
- MySQL root 账号仅用于本地开发，生产需独立用户与 Temporal 独立库。
- 尚无限流、OTel、SQL 审计、正文清理任务；按 M1 排期在后续里程碑补齐。
