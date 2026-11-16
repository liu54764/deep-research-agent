# M9 · Eval 体系（评估集 + 打分 + 基线）

> 输入基线：`M0-scope.md`（冻结）、`M1-architecture.md`（冻结）、M2~M8 实现与验收记录（README §3 / M2 验收）。
> 本阶段回答："研究质量到底有多好、改动是变好还是变坏、数字能不能写进简历"。
> 本文冻结内容：**指标口径与公式、评估集 schema 与切分、打分协议、`dra-eval` 工程形态、运行节奏、M9 验收标准**。
> 状态：初稿（2026-11-15），v1 按「预算约束版」执行。
>
> **预算约束（2026-11-15 修订）**：API 余额有限，v1 采取瘦身配置——**8 题、QUICK 档、单 judge、单轮 ≈ ¥2~3 / ≤ 20 min**。
> 指标口径和打分协议不变，只缩小样本；n=8 时**不切 dev/test**，统一作为**回归集（regression set）**，
> 所有比例指标必须标注 `n` 与 95% CI，对外表述为"自建 8 题回归集上的实测值"，**不宣称统计显著**。
> 扩到 24 题时再恢复 sealed test split（见 §4.3）。

---

## 1. 定位与边界

### 1.1 这个阶段要回答的五个问题

1. 报告里的引用，有多少真的支撑结论？（引用正确率 / 无幻觉率）
2. 用户关心的点，报告答到了多少？（覆盖率）
3. 报告有没有与权威事实相反的话？（事实一致性）
4. `claim.confidence` 能不能当信号用？（置信度校准）
5. 每次改 prompt / 换模型 / 调权重重构，是变好还是变坏？（回归 + 基线）

### 1.2 In Scope

- 8 题评估集（预算约束版，含标准答案子集 + 争议子集；口径不变，可平滑扩到 24 题）。
- LLM-as-judge + 规则校验双轨打分；人工抽样校准 judge（抽样量随题量下调）。
- 指标聚合、置信区间、基线 diff、CI 退出码。
- 置信度校准（ECE / Brier）+ **冲突惩罚落地**（M8 遗留尾账）。
- 评估产物：`summary.md` + `metrics.json` + 基线文件。

### 1.3 Out of Scope（明确排除）

| 排除项 | 原因 |
|---|---|
| ❌ 自动 prompt 优化 / DSPy 类框架 | M9 只做度量，优化靠人工迭代 prompt v2 |
| ❌ 标注平台 / 人工评估后台 | 抽样用 JSONL + 离线标记即可 |
| ❌ 在线 A/B / 生产流量评估 | 单机 MVP，无生产流量 |
| ❌ 并发与容量评估 | 属于 M10 |
| ❌ 数据集一次扩到 40+ 题 | 单轮成本 ×5；先把 8 题口径打可信，预算允许再扩 |

### 1.4 一条原则

**只上报能被原始记录复算的指标。** `eval/runs/<runId>/` 里保留逐题、逐条 judge 的原始结果；口径变更必须升 dataset / metrics 版本，不允许"数字对不上代码"。

---

## 2. 评估分层与指标总表

```
L0 检索 → L1 证据 → L2 结论 → L3 报告 → L4 任务
```

| 层 | 看什么 | 主要指标 | 数据来源 |
|---|---|---|---|
| L0 | 来源质量与多样性 | 域名多样性、抓取成功率、官方文档占比（advisory） | `source` |
| L1 | 证据是否原文 | 锚点命中率 ACR、quote 拒绝率 | `evidence` + `source_content` |
| L2 | 结论是否被证据支撑 | 引用正确率 CAR、claim 支撑率 CSR | 外部 judge J1 + 规则 |
| L3 | 报告是否答到点、是否自洽 | 金标覆盖率 GCR、矛盾率 CTR、冲突召回 CR、不变量 | 外部 judge J2/J3 + 规则 |
| L4 | 成本 / 时延 / 稳定性 | P50/P95、成本、stopReason 分布、失败率 | `usage_record` / `research_task` / 报告 stats |

**Headline 指标（写进 README 与简历的只有这些）**：
`CAR_strict`、`HFR`、`GCR`、`L_total P50`、`单任务成本`、`ECE`、`不变量通过率 100%`。

其余指标作为诊断项出现在 `summary.md`，不单独对外宣称。

---

## 3. 指标定义（口径、公式、目标）

### 3.1 引用层

记 `C` = 报告中**已渲染**的全部引用（`report.meta_json → document.sections[*].claims[*].citations[*]`），`J1(c)` = 外部 judge 对单条引用给出的判定 ∈ `SUPPORT / PARTIAL / REFUTE / UNRELATED`。

| Key | 名称 | 公式 | 目标 | 说明 |
|---|---|---|---|---|
| `CAR_strict` | 引用正确率（严格） | `\|{c ∈ C : J1(c)=SUPPORT}\| / \|C\|` | **≥ 0.85**（M0） | headline |
| `CAR_weighted` | 引用正确率（加权） | `(\|SUPPORT\| + 0.5×\|PARTIAL\|) / \|C\|` | 诊断 | 反映"部分支撑"的量 |
| `ACR` | 锚点命中率 | 引用 quote 能在库内正文按 `QuoteValidator` 命中的比例 | 1.00 | M6.5 起由代码保证，评估用于回归 |
| `URL_LIVE` | 引用 URL 存活率 | 抽样 URL 返回 2xx/3xx（≤3 跳）占比 | ≥ 0.95 | 403/429 记 `UNVERIFIABLE`，不计入分母 |
| `HFR` | 无幻觉引用率 | `1 − \|{claim: 至少一条 citation 无效}\| / \|有 citation 的渲染 claim\|` | **≥ 0.90**（M0） | headline；"无效"= J1 REFUTE/UNRELATED，或 URL 404/410，或 quote 不在原文 |

> **分母约定**：`CAR` 以**引用**为单位（M0 的"引用真实存在且支撑结论"），`HFR` 以**结论**为单位（避免单条 claim 挂 5 条引用时权重被稀释）。两者都报，不要混用。
> **404/410 单独计数**为 `URL_DEAD`，只作为"外部漂移"诊断，不进 `HFR` 分子——除非 URL 从不出现在本任务的 `source` 表中（那是编造，直接判不变量 I2 违反）。

### 3.2 结论层

| Key | 名称 | 公式 | 目标 | 说明 |
|---|---|---|---|---|
| `CSR` | claim 支撑率 | 至少 1 条 citation `J1=SUPPORT` 的渲染 claim 占比 | 诊断 | = claim 级 CAR |
| `GCR` | 金标覆盖率（外部口径） | `Σ w_kp × covered_kp / Σ w_kp`，`covered` 由 J2 判定 | **≥ 0.80**（M0） | headline |
| `SQ_COVERAGE` | 内部覆盖率（自查） | `sub_question.status=COVERED` 占比 | ≥ 0.80 | 仅对照，**不上 headline**（系统自评） |
| `CTR` | 矛盾率 | J3 判 `CONTRADICTED` 的渲染 claim / 渲染 claim | ≤ 0.03（初值） | 只对有 gold 的题可算 |
| `CLAIM_PRECISION` | 结论精度 | J3 `SUPPORTED` / 渲染 claim | 诊断 | 可判定子集 |

### 3.3 冲突层

| Key | 名称 | 公式 | 目标 |
|---|---|---|---|
| `CR` | 冲突召回 | 争议题中报告至少标出 1 条**与金标冲突对相关**的 conflict 的比例 | ≥ 0.70 |
| `CP` | 冲突精度（人工抽检） | 抽样 10 条 conflict 中"双方 claim 确实对立且 URL 来自本任务"的比例 | ≥ 0.80 |
| `SIDESTEP` | "和稀泥"率 | 争议题报告只给单边结论且无冲突标记的比例 | ≤ 0.20 |

> `SIDESTEP` 是给"只并列不裁决"设计的反向指标：M8 的产品承诺是**显式标注分歧**，不是回避分歧。单边给答案可以，但必须在报告里说明另一方立场。

### 3.4 置信度校准

以 `J1/J3` 的外部判定为 ground truth（`correct = 1` 当且仅当 claim 的所有引用无 REFUTE 且至少一条 SUPPORT，或 J3=SUPPORTED）：

- **ECE**（10 桶）：`Σ (n_i / N) × |acc_i − conf_i|`，目标 **≤ 0.15**（校准后）
- **Brier**：`mean((conf − correct)²)`，用于权重拟合与版本对比
- **高置信桶正确率**：`conf ≥ 0.8` 的 claim 的实际正确率，目标 ≥ 0.85

样本 < 100 条 claim 时只输出"样本不足"，**不得宣称已校准**。

### 3.5 效率与稳定性

| Key | 口径 | 目标 |
|---|---|---|
| `L_total P50/P95` | 提交 → `SUCCEEDED` 的墙钟时间，按 depth 分桶 | QUICK ≤ 2 min；STANDARD ≤ 5 min（M0 / M1 §12） |
| `COST` | 单任务 `used_cost_micros`（生成部分），judge 成本单列 | ≤ 预算（QUICK 目标 ≤ ¥0.5） |
| `TOKENS` | `used_tokens` 分位 | ≤ depth 预算 |
| `SUCCESS` | `SUCCEEDED / submitted` | ≥ 0.98 |
| `STOP_REASON` | 分布（`COVERAGE_REACHED / MAX_ROUNDS / BUDGET_EXHAUSTED / DEADLINE_REACHED / NO_NEW_EVIDENCE`） | 无未知值、无超时任务 |
| `EXTERNAL_FAIL` | 报告 `warnings` 中 `SEARCH_* / FETCH_* / LLM_*` 占比 | < 0.02（M0，M10 复核） |

### 3.6 不变量（fail-closed 回归守门）

这些不是"准确率"，是**产品承诺**。任一违反 → 整轮 run 判无效，退出码 2，先修 bug 再谈分数。

| ID | 不变量 |
|---|---|
| I1 | 每条渲染引用的 `(claimId, evidenceId)` 在库中存在，且 `evidence.task_id == taskId` |
| I2 | 每条引用 URL 出现在本任务 `source` 表中（canonical/url_hash 匹配）——"编造来源"在结构上不可能，评估负责证明它仍然不可能 |
| I3 | 每条渲染 claim 至少 1 条 citation |
| I4 | `REFUTED / UNSUPPORTED` 的 claim 不出现在 `sections`，只出现在附录 |
| I5 | Markdown 中无残留 `[[...]]` 标记；每个标记都解析到参考文献编号 |
| I6 | 参考文献编号 1..N 连续、无重复 URL、编号顺序 = 正文首次出现顺序 |
| I7 | 报告 `stats`（来源数 / 证据数 / claim 数 / tokens / 成本）与 DB 一致（误差 ≤ 1%） |
| I8 | `report.meta.stopReason` 与 `research_task.stop_reason` 一致；`warnings` 集合一致 |

---

## 4. 评估集设计

### 4.1 结构（JSONL，一行一题）

```json
{
  "id": "q-kafka-eos",
  "datasetVersion": "2026-11-15",
  "split": "dev",
  "category": "distributed-systems",
  "question": "Kafka 的 exactly-once 语义在什么范围内成立？要做到端到端还需要什么？",
  "language": "zh",
  "depth": "QUICK",
  "gold": {
    "type": "keypoints",
    "checkedAt": "2026-11-15",
    "keyPoints": [
      {
        "id": "kp1",
        "weight": 1.0,
        "statement": "幂等 producer + 事务可保证 Kafka 内部的 exactly-once",
        "aliases": ["idempotent producer", "transactional"],
        "referenceUrls": ["https://kafka.apache.org/documentation/"]
      }
    ],
    "conflicts": [
      { "id": "cf1", "sideA": "需要下游幂等/事务协同才能端到端", "sideB": "Kafka 内部机制即可端到端" }
    ]
  },
  "contested": true,
  "notes": ""
}
```

字段约定：

- `gold.type`：`exact`（单点可判定）/ `keypoints`（多要点）。
- `keyPoints` 3~6 条；`weight` 用于加权 GCR；`aliases` **只给人工和 `--verify-gold` 用，不喂给 judge**。
- `referenceUrls` 用于 gold 体检（可达 + 含别名），不参与打分。
- 争议题必须写 `gold.conflicts`，否则冲突召回没有金标。
- `checkedAt` 超过 180 天 → `--verify-gold` 提示复核。

### 4.2 规模与构成（v1：8 题）

| 子集 | 题数 | gold 类型 | 用途 | 主要指标 |
|---|---|---|---|---|
| A 标准答案 | 3 | `exact` | 可判定事实（语义保证、默认值、版本行为） | `CAR` / `HFR` / `CTR` |
| B 开放式 | 4 | `keypoints` | 对比、机制解释、安全/最佳实践 | `GCR` / `CSR` |
| C 争议 | 1 | `keypoints` + `conflicts` | 条件性/多派系答案 | `CR`（n=1，只记 0/1，纯诊断） |

- 其中 1 题的 `question` 用英文（语言跟随测试，不单列子集）。
- **不切 dev/test**（n=8 切了以后 test 只剩 3~4 题，CI 宽到没有意义）。8 题统一作为回归集，只用于"改动前后对比"，不用于调 prompt 参数——避免在同一批题上过拟合。
- 预算允许后扩到 24 题，构成按原计划（8/10/4/2）并恢复 dev 14 / test 10 切分；**schema 不变**，扩题只加数据。

### 4.3 切分与防过拟合

- **n ≤ 10 时不切分**：样本量不足以支撑"调参集 / 报告集"隔离，切分反而制造虚假严谨。此时把整套题当作回归集，规则是：
  1. 不针对具体题目改 prompt 话术（发现个别题答不好，先判断是普遍机制问题还是个例）；
  2. 每次改动都跑全量 8 题，只看**相对基线**的变化；
  3. 对外表述带 `n=8` 和 CI，不写"准确率 90%"这种脱离样本量的说法。
- **n ≥ 20 时恢复切分**：dev 调参、test 上报 headline，test 冻结。
- 比例指标一律给 **Wilson 95% 置信区间**；n=8 时差异 < 10pp 不当作结论（经验值，扩题后收紧）。
- 评测集**不可**同时用于 prompt 示例（few-shot）与打分。

### 4.4 gold 质量规则

1. 每条 keyPoint 至少 1 个 `referenceUrl`，且"两个独立来源一致"才入集。
2. 主观题（"哪家更好"）不设 gold，只测覆盖与冲突，不测对错。
3. gold 由人工编写 + `--verify-gold` 机器体检双保险；体检失败标 `unverified`，该题只进诊断不进 headline。

### 4.5 维护

- 文件：`eval/dataset/questions.jsonl` + `eval/dataset/CHANGELOG.md`
- `datasetSha256` 写入每轮 `run.json`，保证数字可追溯

---

## 5. 打分协议

### 5.1 三轨

| 轨 | 输入 | 输出 | 执行者 | 用途 |
|---|---|---|---|---|
| 规则 | DB / 报告 / HTTP | 布尔 / 比率 | 确定性代码 | 锚点、URL 存活、不变量、统计一致性 |
| Judge | 报告内容 + gold | 结构化 JSON | **独立模型** | 引用支撑、覆盖、对错 |
| 人工 | 抽样 20 条 J1 + 10 条 J2 映射 | 标签 | 你（离线，约 15 min/轮） | 校准 judge，算 Cohen's κ |

### 5.2 Judge 独立性（硬要求）

1. **不与生成同模型**：生成默认 `deepseek-chat`，judge 默认 `deepseek-reasoner`；有条件时用第二厂商（任一 OpenAI 兼容端点）复核 headline。judge 的 base-url / key / model **独立配置**。
2. **盲评**：judge 不接收 `status`、`confidence`、`verification` 任何内部判定。
3. **关缓存**：经网关调用时强制 `cache=off`（语义缓存会让 judge 结果不可复算）。
4. `temperature=0`，严格 JSON；解析失败重试 1 次，仍失败记 `JUDGE_INVALID`，不计入分母，并计入 `JUDGE_FAIL_RATE`（> 2% 则整轮 run 无效）。

### 5.3 Judge 任务契约

**J1 · 引用支撑**（每个 claim 一次，把它的所有引用合并进同一次调用）

```
输入：question, claimStatement, citations[{ref,url,title,quote}]
输出：{"citations":[{"ref":"[1]","verdict":"SUPPORT|PARTIAL|REFUTE|UNRELATED","rationale":"≤60字"}],"rationale":"≤60字"}
约束：
- 逐条判断，每条 ref 必须出现，不许合并或跳过
- 只判断 quote 是否支撑 statement，不做事实核查、不评价来源权威性
- quote 是否真实存在于原文由规则轨负责，不由你判断
```
> 实现（M9.3）：单 citation 的 `CAR` 需要逐 ref 判定，所以输出改成数组；解析器兼容旧的单 verdict 形态（应用到该 claim 的所有引用）。

**J2 · 金标覆盖**（每题一次）

```
输入：question, goldKeyPoints[{id,statement}], renderedClaims[{id,statement}]
输出：{"coverage":[{"kpId":"kp1","covered":true,"claimIds":["c1"],"note":""}]}
约束：
- claim 必须在语义上回答了 keypoint 才 covered=true
- 只沾边、只出现关键词 → false
- 每条 keypoint 必须给结论，不许跳过
```

**J3 · 与 gold 对错**（v1 仅用于 4 道 `exact` 题，每题一次批量判定所有渲染 claim，不逐条调用）

```
输入：question, goldAnswer, claimStatement
输出：{"verdict":"SUPPORTED|CONTRADICTED|NOT_IN_GOLD|UNKNOWN","rationale":"≤80字"}
约束：
- CONTRADICTED 仅用于与 gold 明确相反
- gold 未涉及但可能正确 → NOT_IN_GOLD，不算错
```

### 5.4 人工校准

- 每轮抽 **12 条 J1 + 4 条 J2 映射**（约 8 分钟）。CLI 自动写模板 `eval/labels/<runId>.jsonl`（`humanVerdict` 为 `null`），人工填完后执行：
  ```bash
  java -jar dra-eval/target/dra-eval-0.1.0-SNAPSHOT.jar \
    --mode=calibrate --labels=eval/labels/<runId>.jsonl
  ```
  输出 `<labels>.kappa.json`，按 kind 分别给 κ；`min κ < 0.7` 时退出码 1（judge 数字不可用）。
- 计算 Cohen's κ，门槛 **0.7**；低于门槛 → 改 judge prompt 或换 judge 模型，**不得直接使用 judge 数字**。
- 运行结束时若该 runId 已有标注文件，会顺带算 κ 并写入 `calibration.json`；未标注时标 `UNLABELED`，不阻塞运行但 summary 会标注"judge 未校准"。
- n=16 时 κ 本身也不稳，只作方向性判断；抽检记录累积到 60 条以上再下结论。

### 5.5 反偏差措施

- claim 顺序随机化；不展示内部顺序、置信度、状态。
- 不做"整篇报告打分"（长度/文风偏差），只做逐条事实 + 覆盖映射。
- 同一 claim 的多条引用合并成一次 J1，避免"引用越多越占便宜"。
- 不把 aliases 喂给 judge，避免答案泄漏。

---

## 6. `dra-eval` 工程设计

### 6.1 形态

**独立 Spring Boot CLI（非 Web），黑盒走 API、白盒读 DB：**

- 提交 / 轮询 / 取报告走 `dra-api` HTTP —— 反映真实产品路径（含渲染、鉴权、序列化）。
- claim / citation / verification / usage 明细直接读 `dra-infra` 的 Jdbc 仓储 —— 不给 API 加评估专用端点。
- 依赖：`dra-common`、`dra-contracts`、`dra-domain`、`dra-infra`；`spring.main.web-application-type: none`；加 `spring-boot-maven-plugin` 打可执行 jar（与 `dra-api` / `dra-worker` 一致）。

### 6.2 包结构

```
com.dra.eval
├─ EvalApplication                    # CommandLineRunner
├─ config/EvalProperties              # dra.eval.* + dra.eval.judge.*
├─ dataset/{DatasetLoader, EvalQuestion, GoldKeyPoint}
├─ runner/TaskRunner                  # POST /tasks、轮询、超时、幂等键
├─ collect/{ReportCollector, DbDetailReader}
├─ invariant/InvariantChecker
├─ score/rule/{AnchorScorer, UrlLivenessScorer, StatsConsistencyScorer}
├─ score/judge/{JudgeClient, J1Citation, J2Coverage, J3Correctness, JudgeResultParser}
├─ score/{MetricCalculator, Calibration, ConfidenceInterval}
├─ report/{SummaryRenderer, MetricsWriter, BaselineComparator}
└─ support/{CostGuard, RunManifest}
```

**Judge 客户端实现要点**：复用 `dra-infra` 的 `SpringAiLlmClient`，用构造器注入第二份 `LlmProperties`（judge 专用 base-url / key / model），绕开 Spring 单例绑定 —— 不需要新写 HTTP 客户端。

### 6.3 CLI 与运行模式

```bash
mvn -q -pl dra-eval -am package

java -jar dra-eval/target/dra-eval-0.1.0-SNAPSHOT.jar \
  --mode=real --split=test --depth=QUICK --parallel=2 \
  --judge-model=deepseek-reasoner \
  --out=eval/runs --baseline=eval/baselines/baseline-v1.json \
  --max-cost-cny=10
```

| 模式 | 作用 | 网络/成本 |
|---|---|---|
| `self-test` | mock LLM + mock 搜索 + stub fetch，验证管道、不变量、报告格式；judge 强制关闭 | 无 |
| `real` | 真实 LLM + Tavily + 抓取；judge 由 `--judge-enabled` 控制 | 有 |
| `rescore` | 复用已有 run 的报告，只重跑 judge/规则（已实现：`--rescore-from`） | 仅 judge（命中缓存则免费） |
| `calibrate` | 读填好的 labels 文件算 Cohen's κ，写 `<labels>.kappa.json`；κ < 阈值退出码 1 | 无 |
| `verify-gold` | 体检 gold 的 `referenceUrls` 是否可达且含别名 | 有 |
| `estimate` | 只打印预计 token / 成本 / 时长，不发请求 | 无 |

### 6.4 配置

```yaml
dra:
  eval:
    api-base-url: ${DRA_API_URL:http://localhost:8080}
    api-key: ${DRA_API_KEY:dev-key}
    dataset: ${DRA_EVAL_DATASET:eval/dataset/questions.jsonl}
    depth: QUICK
    parallel: 2
    poll-interval-ms: 2000
    task-timeout-minutes: 20
    max-cost-cny: 3
    judge:
      base-url: ${DRA_EVAL_JUDGE_BASE_URL:https://api.deepseek.com}
      api-key: ${DRA_EVAL_JUDGE_API_KEY:}
      model: ${DRA_EVAL_JUDGE_MODEL:deepseek-reasoner}
      temperature: 0.0
    url-check:
      enabled: true
      sample-limit: 50
      timeout-seconds: 8
```

### 6.5 产物布局

```
eval/runs/<runId>/
  run.json            # dataset sha256、配置快照、模型名、prompt sha256、judge 调用/缓存/成本
  task-index.json     # questionId → taskId（幂等键 eval:<runId>:<qid>）
  per-question.jsonl  # 每题：状态、耗时、成本、规则指标、judge 指标、不变量
  invariants.json
  thresholds.json     # M0 阈值判定（仅在启用时写）
  baseline-diff.json  # 与冻结基线的逐项 delta 与 regression（有基线时写）
  calibration.json    # κ（按 kind）+ labeled 状态；ECE / Brier 在 M9.5 加入
  metrics.json        # 聚合指标
  summary.md          # 人读：headline、judge、阈值、基线、校准、分题表、不变量、成本
eval/cache/judge/<sha256>.json   # 全局、内容寻址的 judge 缓存
eval/labels/<runId>.jsonl        # 人工标注模板（judgeVerdict 预填，humanVerdict 待填）
eval/baselines/<name>.json       # 冻结基线（稳定指标子集 + 指纹）
```

- **judge 缓存**：键 = `prompt 版本 + system + user` 的 sha256，全局共享；同一输入只调用一次，`rescore`/重跑同题时免费复算。缓存只存成功解析的 JSON，无效输出先修复重试、不落缓存。
- **URL 存活检查**：默认开启（`url-check.enabled=true`），每次最多抽 20 条，避免为指标再付一次网络与时间成本；`self-test` 强制关闭。
- **基线**：`--freeze-baseline=true --baseline=eval/baselines/baseline-v1.json` 冻结（只存稳定子集 + dataset/depth/judge 指纹）；之后每次运行带 `--baseline=...` 自动 diff。dataset sha、depth 或 judge 配置不同 → 标 `not comparable`，不参与门禁。
- **diff 规则**：比例指标相对变化 > 5% **或** 绝对 > 3pp 才报 regression；时延 / 成本容忍 +15%；`rescore` 不做性能对比（没有重新生成）。
- **`rescore`**：`--mode=rescore --rescore-from=eval/runs/<runId>`，复用已有任务与报告，只重跑规则 + judge；prompt 未变时 judge 全部命中缓存，成本为 0。

### 6.6 退出码（给 CI 用）

| 码 | 含义 |
|---|---|
| 0 | 全部达标 |
| 1 | 质量门禁未过：指标低于阈值、相对基线回退、或 judge 的 κ 低于阈值 |
| 2 | 不变量违反（run 无效，先修 bug） |
| 3 | 基础设施不可用（API / DB / 模型端点） |
| 4 | 成本护栏触发 |

### 6.7 隔离与容错

- eval 任务写同一个 `dra` 库；幂等键带 `eval:<runId>:` 前缀，`task-index.json` 维护映射，**不做库表改造**。
- 需要彻底隔离时，可切独立 schema + 独立 Temporal namespace（列为二期选项，不阻塞 v1）。
- 单题失败不中断整批；连续 3 题失败 → 熔断退出（exit 3），避免把配额烧在坏链路上。

---

## 7. 置信度校准与 M8 尾账

### 7.1 现状与文档不一致（M9 必须解决）

| 位置 | 公式 |
|---|---|
| M1 §8.7（文档） | `0.5×来源多样性 + 0.3×judge 平均分 + 0.2×规则通过率 − 0.2×冲突数` |
| `VerifyActivitiesImpl`（代码） | `0.5×judgeConfidence + 0.3×规则通过率 + 0.2×多样性`，再按 verdict 截断；**无冲突项** |

权重方向相反，且冲突惩罚从未实现。M9 用数据决定，不靠拍脑袋。

### 7.2 方法（M9.5 已实现）

1. **样本收集**：judge 对每条渲染 claim 给出外部正确性——`exact` 题优先用 J3（`SUPPORTED=true` / `CONTRADICTED=false`），其余用 J1（至少一条 SUPPORT 且无 REFUTE/UNRELATED 记为正确）。逐条写入 `calibration-samples.jsonl`，可离线复算。
2. **ECE / Brier**：10 桶，`ECE = Σ (n_i/N)·|acc_i − conf_i|`；同时输出高置信桶（≥0.8）经验正确率与分桶表，写 `confidence-calibration.json` 并在 summary 展示。样本 < `confidence-min-samples`（默认 100）时只报告、不门禁；达到样本量且 `ECE > confidence-max-ece`（默认 0.15）→ 退出码 1。
3. **冲突惩罚**：`confidence ← max(0, confidence − λ × min(冲突数, 1))`，λ 默认 0.2，由 `dra.verify.confidence.conflict-penalty` / `max-penalized-conflicts` 控制；在 `ConflictActivitiesImpl` 落库后回写 `claim.confidence`，报告在冲突之后生成，天然读到最终值。
4. **权重**：`judge-weight / rule-weight / diversity-weight` 已配置化（默认保持 M6 行为 0.5/0.3/0.2）。**网格拟合暂缓**：`verification` 表只存 verdict、不存 judge 分数/规则比例/多样性，缺少可拟合的自变量；与其在小样本上拍脑袋改权重，不如等分量落库（后续 migration）后再做，避免把噪声固化成默认值。

### 7.3 产品代码改动（已完成 2/3）

1. ✅ `VerifyActivitiesImpl`：权重从常量改为 `ConfidenceProperties`（`dra.verify.confidence.*`）。
2. ✅ **冲突回写**：`ConflictActivitiesImpl` 保存冲突后对命中 claim 重算并写库（新增 `ClaimRepository.updateConfidence`）。
3. ✅ 报告在冲突之后生成，读到的是回写后的 confidence；归因信息（ECE、分桶、样本）记录在 eval 产物而不是产品表里，避免污染业务 schema。

### 7.4 验收（截至 2026-11-15）

- ECE / Brier / 分桶 / 高置信正确率**已可计算并门禁**（样本不足时不宣称已校准）。
- 冲突惩罚**已生效**：单测覆盖 0.9→0.7、0.7→0.5，未参与冲突的 claim 不变。
- 本机 stub + mock judge 实测：3 道 exact 题共 6 条样本，`ECE=0.100`、`Brier=0.010`、高置信桶准确率 1.000，因 n<100 标 `insufficient`、不门禁。
- **未做**：权重网格拟合（缺分量落库）、真实链路下的 ECE 复核（需要一次 ≥100 条 claim 的真实运行）。

---

## 8. 与 M0 §6 的映射

| M0 指标 | 本文口径 | 目标 | 何时测 |
|---|---|---|---|
| 引用正确率 ≥ 85% | `CAR_strict` | ≥ 0.85 | 每次 real run |
| 无幻觉引用率 ≥ 90% | `HFR` | ≥ 0.90 | 每次 real run |
| 子问题覆盖率 ≥ 80% | `GCR`（外部）/ `SQ_COVERAGE`（对照） | ≥ 0.80 | 每次 real run |
| 报告时长 ≤ 5 min | `L_total P50`（STANDARD） | ≤ 5 min | 每次 real run |
| 任务恢复成功率 100% | 沿用 M2 崩溃演练 | 100% | M2 已验，M9 不复测 |
| 外部调用失败率 < 2% | `EXTERNAL_FAIL` | < 0.02 | M9 首测，M10 复核 |
| 单任务成本 ≤ 预算 | `COST` | ≤ 预算 | 每次 real run |
| 防死循环 100% | `STOP_REASON` 枚举 + 无超时任务 | 100% | 每次 real run |

---

## 9. 里程碑拆解与验收

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| **M9.1** | 数据集 v1（8 题）+ schema + CHANGELOG | 全部通过 `--verify-gold` 或显式标 `unverified`；JSON Schema 校验通过 |
| **M9.2** | CLI 骨架 + TaskRunner + 采集 + 不变量 + 规则轨 + summary（先关 judge） | `self-test` 全绿；real 跑 3 题出不变量/规则指标 |
| **M9.3** | Judge 三任务 + 人工抽检 κ | 3 题 run 的 judge 结果可复算；κ ≥ 0.7（40 条抽样） |
| **M9.4** | 指标聚合 + 95% CI + 基线 + 退出码 | 8 题全量产出完整 `metrics.json` + `summary.md`，基线 diff 可跑 |
| **M9.5** | 置信度校准 + 冲突惩罚落地 | ECE 达标；冲突 claim 置信度下调可观测 |
| **M9.6** | 基线固化 + README §3.4 + 简历数字 | `baseline-v1.json` 入库；README 附可复现命令与真实数字 |

粗略工作量（不含等模型/网络）：M9.1 0.5d、M9.2 1.5d、M9.3 1d、M9.4 0.5d、M9.5 1d、M9.6 0.5d。

---

## 10. 成本与运行节奏

| 运行 | 范围 | 生成成本 | Judge 成本 | 时长 | 频率 |
|---|---|---|---|---|---|
| `self-test` | 2 题 mock | ¥0 | ¥0 | ~2 min | 每次改 eval 代码 |
| smoke | 2 题 real QUICK | ~¥0.3 | ~¥0.3 | ~5 min | 每次改 prompt / 产品代码 |
| **regression** | **8 题全量 QUICK** | **~¥1.2** | **~¥1.3** | **~20 min** | 合并前 / 每周 |
| `rescore` | 8 题已有报告 | ¥0 | ~¥1.3（命中缓存则更低） | ~5 min | 改 judge prompt 时 |
| standard-spot | 2 题 STANDARD | ~¥1.5 | ~¥0.4 | ~12 min | 里程碑末，可跳过 |

> **单轮硬上限 ¥3**（`--max-cost-cny=3`，触发即 exit 4）。
> 估算依据：2026-11-11 实测 QUICK 真实 DeepSeek 约 108 s / ¥0.15；judge 按每题约 20 条 claim × (1.2k in / 0.15k out)、reasoner 价格 ¥4 / ¥16 每百万 token 估算。
> **首次 regression 后回填实测值**；若实测超 ¥4，优先砍到 6 题而不是降低 judge 质量。

---

## 11. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| judge 自洽 / 偏见 | 指标虚高 | 跨模型 judge；盲评；人工 κ；报告里公开 judge 型号与 prompt 版本 |
| 循环论证（拿自家 verdict 当 ground truth） | 数字好看但无意义 | headline 只认外部 judge；内部 verdict 仅作对照 |
| 网页漂移 / 404 | `CAR` 被外部因素拉低 | URL 失效单独统计；quote 校验用库内正文；抽样重抓只做漂移监测 |
| 搜索非确定性 | 同题两次结果不同 | 记录来源集合；5 题 ×3 次估方差；关键指标看区间 |
| gold 过时 / 写错 | 覆盖与对错失真 | 双来源校验 + `checkedAt` + `--verify-gold` |
| 小样本 | CI 很宽 | Wilson CI + 不下强结论；后续扩到 40 题 |
| 成本失控 | 烧钱 | `--max-cost-cny` 硬护栏 + `estimate` 模式 + `rescore` 复用 |
| 过拟合 test | 数字虚高 | test 冻结；调参只用 dev |

---

## 12. ADR 摘要

| ADR | 决策 | 替代方案 | 理由 |
|---|---|---|---|
| ADR-13 | eval 走 API 黑盒 + DB 白盒 | 只读 DB / 只走 API | 既反映真实路径，又能算明细指标 |
| ADR-14 | 规则 + 外部 judge 双轨，headline 只认外部 | 只用内部 verdict | 避免循环论证 |
| ADR-15 | 8 题回归集，n ≤ 10 不切 dev/test | 大而全数据集 / 强行切分 | 预算受限；n=8 切分只会制造虚假严谨，用 CI + 相对基线替代 |
| ADR-16 | judge 与被测模型隔离、独立配置 | 复用 verify 模型 | 降低自洽偏差 |
| ADR-17 | 不变量失败直接判 run 无效 | 只降分 | fail-closed 是产品承诺，退化必须先亮红灯 |
| ADR-18 | 置信度权重配置化 + 数据拟合 | 固定常量 | 不拍脑袋，也便于回归 |

---

## 13. 决策记录（2026-11-15）

| 项 | 结论 | 依据 |
|---|---|---|
| 数据集规模 | **8 题**（3 exact / 4 开放式 / 1 争议，含 1 题英文） | API 预算受限；口径不变，只缩样本 |
| dev/test 切分 | **不切**（n ≤ 10 统一作回归集） | 切分后 test 仅 3 题，CI 过宽 |
| judge 模型 | 单 judge `deepseek-reasoner`（生成 `deepseek-chat`） | 不同模型档已满足隔离；跨厂商复核推迟 |
| 单轮预算 | **硬上限 ¥3**，预计 ¥2.5 | 8 QUICK ≈ ¥1.2 生成 + ¥1.3 judge |
| 数据隔离 | 同库 + `eval:<runId>:` 幂等键前缀 | 零运维；需要时再上独立 schema |
| 人工抽检 | **12 条 J1 + 4 条 J2 / 轮**（约 8 分钟） | κ 门槛 0.7，n=16 只作方向性 |
| 冲突召回目标 | 0.70 起（n=1，只记 0/1，纯诊断） | 题量不足，不作对外指标 |
| 扩题触发条件 | API 余额恢复后扩到 24 题并恢复切分 | 见 §4.3 |

> 以上为默认执行项，不阻塞开工；任何一项想改，直接说，改动只影响配置与数据，不影响代码结构。

---

## 14. 与后续阶段的衔接

- **M10**：直接用 `metrics.json` 的 P50/P95 / 成本分布作容量校准输入；压测脚本复用 `TaskRunner`。
- **项目 A（网关）**：`dra.llm.base-url` 指向网关后，eval 可顺带产出"网关路由 / 缓存对研究质量与成本的影响"对比 —— 两个项目叙事的连接点。
- **简历素材**：引用正确率、覆盖率、ECE、P50 时延、单任务成本、回归拦截次数（口径与 provider 无关，可复现）。

---

## 15. M9.3 实现记录（2026-11-15）

- **三个 judge 任务**：J1 每 claim 一次调用、输出逐条 citation 判定（CAR 需要引用级分母）；J2 每题一次、按 `weight` 加权算 GCR；J3 仅 `exact` 题、每题一次批量判定 claim 对错（矛盾率 / claim 精度）。
- **独立性**：judge 使用独立的 base-url / key / model（默认 `deepseek-reasoner`），请求中不含 status / confidence / verification 任何内部判定；prompt 版本 `j1.v1/j2.v1/j3.v1`，sha256 写入 `run.json`。
- **缓存**：内容寻址（prompt 版本 + system + user 的 sha256），全局目录 `eval/cache/judge/`；只缓存成功解析的 JSON，无效输出先修复重试、不落缓存；命中数进 `metrics.json` 与 manifest。
- **校准**：每轮按 hash 稳定抽样 12 条 J1 + 4 条 J2，自动写 `eval/labels/<runId>.jsonl`（不覆盖已有文件）；`--mode=calibrate` 算按 kind 的 Cohen's κ，写 `<labels>.kappa.json`，`min κ < 0.7` 时退出码 1；运行结束若标注已存在，κ 会一并写入 `calibration.json` 与 summary。
- **成本**：judge token / 成本单独记账（`judgeCostMicros`），计入 `--max-cost-cny` 护栏；金额按 judge 自己的价格参数计算（默认 reasoner 价格）。
- **实测（2026-11-15，本机 stub 生成 + 本地 mock judge，零真实花费）**：4 题跑出 J2×4 + J3×2，`GCR=1.000`、`claimPrecision=1.000`、`invalidOutputs=0`、judge 成本 ¥0.0137（mock 价格）；κ 全一致 = 1.000 → `calibrate` exit 0，故意改 1 条 → κ = 0.000 → exit 1。J1 在 stub 下无引用可判，由 25+ 单测覆盖（真实链路首次跑时才会产生 J1 调用）。
- **未做（当时）**：URL 存活检查默认关闭、ECE/Brier 与权重拟合；前两项已在 §17 补齐，权重拟合仍暂停（见 §7.2）。

---

## 17. M9.5 实现记录（2026-11-15）

- **ECE/Brier**：`ConfidenceCalibration` 按 10 桶计算 ECE、Brier、高置信（≥0.8）准确率与分桶表；样本来源 J3（exact 题）优先、J1（引用支撑）兜底。产物 `confidence-calibration.json` + `calibration-samples.jsonl`，summary 有独立章节。
- **门禁**：样本 ≥ `confidence-min-samples`（100）且 `ECE > confidence-max-ece`（0.15）→ 退出码 1；样本不足只报告并在 summary 标注"insufficient, ECE not gated"。
- **冲突回写**：`ClaimRepository.updateConfidence` + `ConflictActivitiesImpl` 在保存冲突后按 `−λ×min(冲突数,1)` 回写 claim confidence（λ=0.2），报告随后生成因此读到新值；权重与惩罚参数都在 `dra.verify.confidence.*`。
- **URL 默认开启**：`url-check.enabled` 默认 `true`（每次最多 20 条），`self-test` 仍强制关闭；`rescore` 沿用配置。
- **实测（stub 生成 + 本地 mock judge，零真实花费）**：8 题 11 次 judge 调用，3 道 exact 题产生 6 条校准样本，`ECE=0.100`、`Brier=0.010`、高置信准确率 1.000；`sufficient=false`（n<100）→ 不门禁。冲突回写由单测覆盖（0.9→0.7、0.7→0.5、未涉及 claim 不变）。
- **刻意没做**：权重网格拟合。`verification` 表只有 verdict，没有 judge 分数/规则比例/多样性，缺自变量；先在小样本上拟合会把噪声写成默认值。等分量落库（后续 migration）或扩题到 ≥20 再拟合。
- **下一步（M9.6）**：跑一次真实链路（≥100 条 claim）复核 ECE，冻结 `baseline-v1.json`，把数字写进 README。

---

## 16. M9.4 实现记录（2026-11-15）

- **阈值门禁**：`ThresholdEvaluator` 按 M0 §6 检查 successRate ≥ 0.98、CAR ≥ 0.85、HFR ≥ 0.90、GCR ≥ 0.80、矛盾率 ≤ 0.03；**只检查实际存在的指标**（judge 关闭时 CAR/HFR/GCR 不当作 0）。阈值可通过 `--min-car/--min-hfr/--min-gcr/--max-contradiction-rate/--min-success-rate` 覆盖，`--thresholds-enabled=false` 关闭。
- **基线**：`--freeze-baseline=true --baseline=<file>` 冻结稳定子集（含 dataset sha、depth、judge 模型指纹）；之后带 `--baseline` 自动 diff，写 `baseline-diff.json` 与 summary 表格。dataset/depth/judge 配置任一不同 → `not comparable`，只提示不门禁。diff 规则：比例指标相对 >5% 或绝对 >3pp；时延/成本 +15%；`rescore` 跳过性能指标（没有重新生成）。从非 0 退出码的 run 冻结基线会打 WARN（不阻止，靠人判断）。
- **`rescore`**：`--mode=rescore --rescore-from=eval/runs/<runId>`，读 `run.json`（恢复 dataset 路径）与 `task-index.json`，对原任务重新取报告 + 读库 + 重跑不变量/规则/judge，不提交新任务；标签默认沿用源 run 的 `eval/labels/<sourceRunId>.jsonl`；不支持 `--freeze-baseline`（没有生成指标可冻结）。
- **退出码优先级**：抽成纯函数 `RunGate` 并单测——不变量(2) > 基础设施(3) > 成本护栏(4) > κ(1) > 阈值(1) > 基线回退(1) > 通过(0)。
- **修掉一个并发归因 bug**：per-question 的 judge token/成本/缓存命中原先用全局计数器差值，并行时重复计数（2 次命中被记成 4 次、成本虚高 3 倍）。改为 `JudgeClient` 每次调用返回本次 usage，`JudgeScorer` 本地累加；全局计数器只用于 manifest 汇总。新增缓存命中零成本单测防回归。
- **实测（stub 生成 + 本地 mock judge，零真实花费）**：
  - 阈值：`--min-success-rate=1.01` → `thresholds.json` 记录 `successRate=1.000`，exit 1。
  - 基线：冻结 2 题基线 → 第二次运行 `comparable=true`、无回退；把基线 latency 改成 1000ms 后复跑 → `latencyP50Ms: 1000 → 16442 (+1544%) REGRESSION`。
  - rescore：对源 run 重打分，`mode=rescore`、任务 ID 不变、`judgeCalls=2`、`cacheHits=2`、`judgeCost=0`，且基线对比自动跳过时延/成本。
  - 单测 62 个全绿。

---

## 18. M9.6 真实链路实测（2026-11-15）

**配置**：8 题 QUICK，`--parallel=4`；生成 DeepSeek `deepseek-chat` + 校验 `deepseek-reasoner`；Tavily 搜索 + 真实抓取；judge 用独立的 `deepseek-reasoner`；URL 存活检查开启。

### 18.1 结果

| 指标 | 值 |
|---|---|
| 任务成功 / 不变量 | 8/8；**0 violations** |
| 证据规模 | 208 claims / 252 citations |
| 锚点命中率 ACR | **1.000** |
| 引用 URL 存活率 | **1.000**（抽样） |
| CAR strict / weighted | **0.741 / 0.867** |
| 无幻觉引用率 HFR | **0.990** |
| claim 支撑率 CSR | 0.831 |
| 金标覆盖率 GCR | **0.610**（19.4/31.8 权重） |
| 矛盾率 / claim 精度（exact） | 0.000 / 0.320（25 条 exact claim，0 条矛盾） |
| ECE / Brier / 高置信准确率 | **0.109** / 0.134 / **0.881**（207 样本，高置信 159） |
| 时延 P50 / P95 | **129s / 177s** |
| 成本 | 生成 ¥1.03 + judge ¥1.60 ≈ **¥2.64/轮** |
| 退出码 | **1**（CAR strict < 0.85、GCR < 0.80；召回/幻觉/ECE 均达标） |

基线已冻结：`eval/baselines/baseline-v1.json`（记录的是**当前质量**，不是达标状态）。用 `--mode=rescore` 复核：`comparable=true`、无回退、217/219 次 judge 命中缓存、judge 成本 0。

### 18.2 真实链路发现并修复的问题

| # | 问题 | 影响 | 修复 |
|---|---|---|---|
| 1 | 参考文献列表按 claim 顺序而非编号排序 | I6：编号不连续、正文引用倒序 | 渲染器按 `n` 排序 |
| 2 | `ReportDocument.stats.warnings` 丢掉任务级 warnings | I8：报告看不到抓取失败 | stats 合并 bundle warnings 并去重 |
| 3 | `/status` 只返回 `lastWarning`；`CONFLICTS_FOUND` 只发事件、不进 workflow warnings | I8 误报、状态页信息缺失 | EventActivities 累积 `warnings` 列表；冲突改走 `addWarning` |
| 4 | URL 存活检查的 `join()` 无整体超时，一个 DNS/TLS 挂起可拖死整轮评估 | 评估进程卡死 19 分钟 | 每请求 `orTimeout`；加挂起服务器单测 |
| 5 | judge `max-tokens=2048` 截断 J2 的 JSON | 个别题的 GCR 变成 0（解析失败） | 默认提到 4096 |

### 18.3 解读与下一步

- **防幻觉链路可靠**：锚点 100%、URL 存活 100%、HFR 99%、0 矛盾——"引用真实存在且不互相矛盾"这条护城河是站得住的。
- **引用支撑偏弱**：strict CAR 0.741，但 weighted 0.867，差距来自大量 `PARTIAL`。下一步是收紧抽取/claim 提示词，让每条结论绑定更直接的证据，而不是放宽判定。
- **覆盖率是最大短板**：GCR 0.61，且题间波动大（0.22~1.00）。QUICK 只有 3 个子问题，金标要点按"完整回答"写的，天然覆盖不全；下一步可用 DEEP 档复测，或把 J2 判定标准与要点粒度对齐。
- **置信度基本可用**：ECE 0.109（≤0.15）、高置信准确率 0.881（≥0.85），说明冲突惩罚 + 现有权重的输出没有严重过度自信。
- **成本/时延**：QUICK 单题 ¥0.33、P50 129s；8 题并发 4 总耗时约 8 分钟。M10 压测可在此基线上做并发梯度。
