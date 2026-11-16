# 评估集变更记录

> 任何对 `questions.jsonl` 的修改都必须在这里留痕，并同步升 `datasetVersion`。
> `verified=false` 的题只允许出现在诊断指标里；只有 `verified=true` 的题才能进 headline。

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

