# eval/ · 评估资产

M9 评估体系的**数据与产物**目录。设计与口径见仓库根目录 `M9-eval.md`。

```
eval/
├─ dataset/
│  ├─ questions.jsonl    # 评估题（一行一题）
│  ├─ schema.json        # 题目的 JSON Schema（draft-07）
│  └─ CHANGELOG.md       # 数据集变更记录 + 待核验清单
├─ labels/               # 人工抽检标注模板（每轮一个 .jsonl + .kappa.json）
├─ cache/judge/          # judge 结果缓存（内容寻址，可删）
├─ baselines/            # 冻结基线（baseline-v1.json）
└─ runs/                 # 每轮评估产物（run.json / metrics.json / summary.md …）
```

## 当前状态（2026-11-15）

- 数据集 v1：8 题，其中 **4 题 gold 已核验**（3 道 exact + 1 道争议题），
  4 题仍待核验（`q-temporal-vs-langgraph`、`q-reactor-backpressure`、`q-agent-failure-modes`、`q-ssrf-defense`）。
- 核验证据逐条记在 `dataset/CHANGELOG.md` 的「核验记录」表里（原文摘录 + 来源 URL）。
- 未核验的题只能进诊断指标；`CAR / HFR / GCR / ECE` 的 headline 目前只覆盖已核验的 4 题（n=4，CI 很宽）。
- **M9.5 CLI 已可用**：`estimate` / `self-test` / `real`+judge / `rescore` / `calibrate` 均可跑；
  judge 三任务输出 CAR/HFR/CSR/GCR/矛盾率/claim 精度，结果有内容寻址缓存；
  阈值门禁、基线冻结/diff、`rescore`、κ 校准、ECE/Brier 置信度校准均已接通，退出码按优先级合并。
- 产物新增 `confidence-calibration.json` 与 `calibration-samples.jsonl`；URL 存活检查默认开启（self-test 关闭）。
- 尚未实现：权重网格拟合（`verification` 表缺 judge 分数/规则比例/多样性分量）。

## 校验数据集

```bash
python scripts/validate-dataset.py            # 结构 + 唯一性 + 交叉字段检查
```

## 运行评估

```bash
# 不花钱：先看工作量与成本预估
java -jar dra-eval/target/dra-eval-0.1.0-SNAPSHOT.jar --mode=estimate --limit=3

# 不花钱：mock LLM + mock 搜索 + stub 抓取，验证管道与不变量（judge 强制关闭）
java -jar dra-eval/target/dra-eval-0.1.0-SNAPSHOT.jar --mode=self-test --limit=2 --parallel=2

# 真实链路 + 外部 judge；judge key 独立配置，缺省关闭
java -jar dra-eval/target/dra-eval-0.1.0-SNAPSHOT.jar --mode=real --judge-enabled=true \
  --judge-api-key=$DRA_EVAL_JUDGE_API_KEY --judge-model=deepseek-reasoner --max-cost-cny=3

# 冻结基线（之后带 --baseline 自动 diff；dataset/depth/judge 不同则 not comparable）
java -jar dra-eval/target/dra-eval-0.1.0-SNAPSHOT.jar --mode=real ... \
  --baseline=eval/baselines/baseline-v1.json --freeze-baseline=true

# 复用已有 run 重新打分（不提交任务；judge 命中缓存则成本 0）
java -jar dra-eval/target/dra-eval-0.1.0-SNAPSHOT.jar --mode=rescore \
  --rescore-from=eval/runs/<runId> --judge-enabled=true --judge-api-key=$DRA_EVAL_JUDGE_API_KEY

# 填完 labels 模板后算 κ（不花一分钱）
java -jar dra-eval/target/dra-eval-0.1.0-SNAPSHOT.jar --mode=calibrate \
  --labels=eval/labels/<runId>.jsonl
```

> 运行需要本地 `api` + `worker` 已启动（self-test 用 stub 配置即可，不需要任何 key），
> 另需 MySQL / Redis / Temporal；CLI 只读库，不跑 Flyway。
> 阈值默认取 M0 验收线，可用 `--min-car/--min-hfr/--min-gcr/--max-contradiction-rate/--min-success-rate` 覆盖。
