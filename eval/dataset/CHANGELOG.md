# 评估集变更记录

> 任何对 `questions.jsonl` 的修改都必须在这里留痕，并同步升 `datasetVersion`。
> `verified=false` 的题只允许出现在诊断指标里；只有 `verified=true` 的题才能进 headline。

## 2026-11-16 · v2（剩余 4 题核验完成，全部 gold 已 verified）

- `datasetVersion` 升到 `2026-11-16`；`q-temporal-vs-langgraph`、`q-reactor-backpressure`、
  `q-agent-failure-modes`、`q-ssrf-defense` 置 `verified: true`、`checkedAt: 2026-11-16`。
- 核验中发现并在本次修正的问题（都会影响 GCR 判定，不只是措辞）：
  1. `q-agent-failure-modes/kp3`（上下文溢出与失控循环）原先引用 **NIST AI 600-1**，
     但该文档全文不含 `context window` / `runaway` / `agentic` 等任何相关表述，属错误引用；
     改用 **OWASP LLM10:2025 Unbounded Consumption** 与 Anthropic *Building effective agents*。
  2. `q-ssrf-defense` 全部要点原先引用 `owasp.org/www-community/attacks/Server_Side_Request_Forgery`，
     该页**完全没有**讲重定向逐跳校验/地址归一化，属弱引用；统一改为
     **OWASP SSRF Prevention Cheat Sheet** + Top 10 A10:2021 + CWE-918 + MDN。
  3. `q-temporal-vs-langgraph/kp5` 原写「需要部署维护服务与集群」，与 Temporal Cloud
     托管选项冲突 —— 自托管成本被写成了普遍硬要求，已加限定。
  4. `q-ssrf-defense/kp3` 把「端口白名单」硬编码成「仅 80/443」，并把「限制响应大小与超时」
     混进 scheme/端口一条里；后者在 OWASP SSRF 指南中并不存在（属防 DoS 加固），
     故拆出 `kp6`（权重 0.5）单独计分。
- 权重变化：`q-ssrf-defense` 总权重 4.6 → 5.1（新增 kp6）。

### 核验记录（2026-11-16）

> 规则同下：官方/权威原文 + 第二独立来源，实际抓取正文比对，才改 `verified: true`。
> 本次 WebFetch 被域名安全策略拦截，改用直接 HTTP 取 canonical URL / 官方 `.md` 源，
> 并以 Maven Central、PyPI、npm registry 交叉验证生态类断言。

| 题 | 断言 | 证据（原文摘录） | 来源 |
|---|---|---|---|
| `q-temporal-vs-langgraph` | 事件历史重放恢复状态、崩溃续跑 | "If the process running it crashes, the Temporal Service hands the work to another process, which rebuilds the state of the execution and resumes at the point where it stopped" | [Temporal · Understanding Temporal](https://docs.temporal.io/evaluate/understanding-temporal)、[ThoughtWorks Radar](https://www.thoughtworks.com/radar/platforms/temporal) |
| `q-temporal-vs-langgraph` | 确定性 + 外部 IO 进 Activity | "A Workflow Definition must be deterministic to support replay… put them in Activities" | [Temporal · Workflow Definition](https://docs.temporal.io/workflow-definition) |
| `q-temporal-vs-langgraph` | LangGraph 图编排 + checkpoint | "You define workflows as graphs of shared state, nodes, and edges"；"A checkpointer saves a snapshot of graph state at each super-step" | [LangGraph overview](https://docs.langchain.com/oss/python/langgraph/overview)、[checkpointers](https://docs.langchain.com/oss/python/langgraph/checkpointers) |
| `q-temporal-vs-langgraph` | 生态以 Python 为主、Java 无官方实现 | 官方仅 Python(238 页)/TS(245 页) 两条运行线；PyPI `langgraph` 近 30 天 57.3M 下载 vs npm 18.9M；`langgraph4j` 为社区移植 | [pypistats](https://pypistats.org/api/packages/langgraph/recent)、[npm](https://api.npmjs.org/downloads/point/last-month/@langchain/langgraph)、[langgraph4j](https://github.com/bsorrentino/langgraph4j) |
| `q-temporal-vs-langgraph` | 组合仅限 Python 侧 | "Temporal's integration with LangGraph gives your LangGraph AI agent workflows durable execution"；`ai-integrations/java/` 下无 langgraph | [Temporal × LangGraph](https://docs.temporal.io/develop/python/integrations/langgraph)、[ai-integrations](https://github.com/temporalio/ai-integrations) |
| `q-temporal-vs-langgraph` | 自托管 vs Cloud | "Run the Temporal Service yourself, or use Temporal Cloud and skip operating the database, the shards, and multi-region failover" | [Temporal · Understanding Temporal](https://docs.temporal.io/evaluate/understanding-temporal) |
| `q-reactor-backpressure` | request(n) 式背压 | "it can use the request mechanism to signal the source that it is ready to process at most n elements"；"flow control (method Flow.Subscription.request(long))" | [Reactor §3.5](https://projectreactor.io/docs/core/release/reference/reactiveProgramming.html#reactive.backpressure)、[JDK 21 Flow](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Flow.html) |
| `q-reactor-backpressure` | 四个 onBackpressure 操作符 | `onBackpressureBuffer/Drop/Latest/Error` 方法签名与语义均在 javadoc；无参 `buffer()` 为无界缓冲 | [Flux javadoc](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html)、[operator choice](https://projectreactor.io/docs/core/release/reference/apdx-operatorChoice.html) |
| `q-reactor-backpressure` | SSE 无应用层流量控制 | 规范全文 `flow control`/`backpressure`/`rate limit` 均 0 次命中；仅有 Last-Event-ID 重连 | [WHATWG SSE](https://html.spec.whatwg.org/multipage/server-sent-events.html)、[RFC 9113 §5.2](https://www.rfc-editor.org/rfc/rfc9113.txt) |
| `q-reactor-backpressure` | JDK 21 虚拟线程已正式 | "This JEP proposes to finalize virtual threads in JDK 21"，Status Closed / Delivered | [JEP 444](https://openjdk.org/jeps/444)、[JDK 21 项目页](https://openjdk.org/projects/jdk/21/) |
| `q-agent-failure-modes` | 间接提示注入与最小权限 | LLM01:2025 区分 direct/indirect；"Enforce privilege control and least privilege access… Restrict the model's access privileges to the minimum necessary" | [OWASP LLM01:2025](https://genai.owasp.org/llmrisk/llm01-prompt-injection/) |
| `q-agent-failure-modes` | 引用不可验证是真实高发失败 | "only 51.5% of generated sentences are fully supported by citations, and only 74.5% of citations support their associated sentence" | [arXiv 2304.09848](https://arxiv.org/abs/2304.09848)、[NIST AI 600-1](https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.600-1.pdf) |
| `q-agent-failure-modes` | 上下文溢出/失控循环（修正引用） | LLM10:2025 含"输入超过上下文窗口导致资源过度消耗"；"common to include stopping conditions (such as a maximum number of iterations)" | [OWASP LLM10:2025](https://genai.owasp.org/llmrisk/llm102025-unbounded-consumption/)、[Anthropic · Building effective agents](https://www.anthropic.com/engineering/building-effective-agents) |
| `q-agent-failure-modes` | 重试风暴与熔断 | "Retries can amplify the load on a dependent system… retries can make the overload worse"；"Temporarily block access… after failures reach a threshold" | [AWS Builders' Library](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/)、[Azure Circuit Breaker](https://learn.microsoft.com/en-us/azure/architecture/patterns/circuit-breaker) |
| `q-agent-failure-modes` | 成本/延迟可观测 | "latency, token usage, cost per task, and error rates can be tracked on a static bank of tasks" | [Anthropic · Demystifying evals](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents) |
| `q-ssrf-defense` | 重定向绕过初始校验 | "Disable the support for the following of the redirection in your web client in order to prevent the bypass of the input validation"；"Attackers may use redirects or redirect chains to evade validation" | [OWASP SSRF Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)、[MDN SSRF](https://developer.mozilla.org/en-US/docs/Web/Security/Attacks/SSRF) |
| `q-ssrf-defense` | 169.254.169.254 属 link-local；CGNAT 不可全球路由 | IANA：`169.254.0.0/16,Link Local,[RFC 3927],Globally Reachable=False`；`100.64.0.0/10,Shared Address Space,[RFC 6598]` | [IANA IPv4 Special Registry](https://www.iana.org/assignments/iana-ipv4-special-registry/iana-ipv4-special-registry-1.csv)、[RFC 6598](https://www.rfc-editor.org/rfc/rfc6598.txt) |
| `q-ssrf-defense` | scheme/端口白名单 | "Enforce the URL schema, port, and destination with a positive allow list"；"use allow lists of: … URL schemes and ports" | [OWASP Top 10 A10:2021](https://owasp.org/Top10/2021/A10_2021-Server-Side_Request_Forgery_(SSRF)/index.html)、[OWASP API7:2023](https://owasp.org/API-Security/editions/2023/en/0xa7-server-side-request-forgery/) |
| `q-ssrf-defense` | DNS rebinding / TOCTOU | "Domain allowlisting alone does not prevent DNS rebinding. Validate the resolved destination IP addresses … then ensure the HTTP client connects only to validated addresses" | [OWASP SSRF Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)、[GitLab webhook 防护](https://docs.gitlab.com/ee/security/webhooks.html) |
| `q-ssrf-defense` | 网络层白名单/出网限制 | "use allowlists to ensure that outgoing requests are restricted to these known entities… enforced by firewalls or network segmentation" | [CWE-918](https://cwe.mitre.org/data/definitions/918.html) |

## 2026-11-15 · v1（全部 gold 未核验）

- 建立 8 题回归集（预算约束版）：
  - `exact` 3 题：`q-mysql-isolation`、`q-redis-persistence`、`q-java-virtual-threads`
  - `keypoints` 4 题：`q-temporal-vs-langgraph`、`q-reactor-backpressure`、`q-agent-failure-modes`（英文）、`q-ssrf-defense`
  - 争议 1 题：`q-kafka-eos`（含 1 组 conflict 金标）
- 不切 dev / test：n ≤ 10 时统一作为 regression set（见 `M9-eval.md` §4.3）。
- **v1 初始所有 gold 标 `verified: false`、`checkedAt: null`**（AI 起草）；核验通过后改为 `true` 并填 `checkedAt`。
- **2026-11-15 已核验 4 题**（`q-kafka-eos`、`q-mysql-isolation`、`q-redis-persistence`、`q-java-virtual-threads`），证据见下节。
- 仍未核验 4 题：`q-temporal-vs-langgraph`、`q-reactor-backpressure`、`q-agent-failure-modes`、`q-ssrf-defense`（只进诊断指标）。

### 核验记录（2026-11-15）

> 规则：官方文档原文 + 第二来源（另一官方页 / 官方镜像 / 运行时实测）一致，记下 URL 与原文，才改 `verified: true`。
> 本机网络屏蔽了 `dev.mysql.com`(403)、`kafka.apache.org`、`raw.githubusercontent.com`，故 MySQL 用 Oracle 官方镜像手册、Kafka 用 Confluent 的 design 文档、Redis 默认值直接读官方镜像运行时配置。

| 题 | 断言 | 证据（原文摘录） | 来源 |
|---|---|---|---|
| `q-java-virtual-threads` | 虚拟线程在 `synchronized` 内阻塞会 pinning | "a virtual thread cannot be unmounted during blocking operations because it is pinned to its carrier: When it executes code inside a synchronized block or method…" | [JEP 444](https://openjdk.org/jeps/444)（JDK 21） |
| `q-java-virtual-threads` | JDK 24 修复该行为 | "Status Closed / Delivered, Release 24"；"eliminate nearly all cases of virtual threads being pinned to platform threads" | [JEP 491](https://openjdk.org/jeps/491) |
| `q-java-virtual-threads` | 不应池化 / CPU 密集无收益 | "Virtual threads are cheap and plentiful, and thus should never be pooled"；"The workload is not CPU-bound, since having many more threads than processor cores cannot improve throughput" | [JEP 444](https://openjdk.org/jeps/444) |
| `q-redis-persistence` | `appendonly` 默认 `no` | 运行时实测 `redis:7.4-alpine` → `appendonly = no`，`appendfsync = everysec`，`save = 3600 1 300 100 60 10000` | 官方镜像 `CONFIG GET` |
| `q-redis-persistence` | RDB 默认、everysec 最多丢约 1 秒 | "By default Redis saves snapshots of the dataset on disk, in a binary file called dump.rdb."；"appendfsync everysec: … you may lose 1 second of data if there is a disaster." | [Redis Persistence](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/) |
| `q-mysql-isolation` | 默认 REPEATABLE READ | "The default isolation level for InnoDB is REPEATABLE READ." | [MySQL 8.4 Manual · Isolation Levels](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/innodb-transaction-isolation-levels.html) |
| `q-mysql-isolation` | 快照读避免不可重复读 | "Consistent reads within the same transaction read the snapshot established by the first read." | 同上 |
| `q-mysql-isolation` | next-key lock 抑制幻读 | "InnoDB locks the index range scanned, using gap locks or next-key locks to block insertions by other sessions into the gaps covered by the range." | 同上 |
| `q-kafka-eos` | 幂等 producer 去重 | "The idempotent delivery option guarantees that resending a message will not result in duplicate entries in the log…" | [Kafka Design · Delivery Semantics](https://docs.confluent.io/kafka/design/delivery-semantics.html) |
| `q-kafka-eos` | 内部 EOS vs 端到端 | "Kafka supports exactly-once delivery in Kafka Streams… To enable exactly-once delivery for other consumer and producer systems, you can use… Kafka Connect… Otherwise, by default Kafka guarantees at-least-once delivery." | 同上 |
| `q-kafka-eos` | read_committed 与 offset | "In read_committed, the consumer only reads messages from committed transactions."；"storing the consumer position, and then storing the consumed data… Kafka stores the position…" | 同上 |

