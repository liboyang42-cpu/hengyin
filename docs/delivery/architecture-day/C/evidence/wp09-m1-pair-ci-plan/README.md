# C / M1 T14 同 run CI 接线评估与 review.patch

结论：推荐给**现有** `real-storage-it.yml` 增加一个可选 `old_source_sha`，复用现有 `source_sha` 作为新侧。旧 SHA 留空保持单 source 路径；填写时只允许 `architecture-day-lead-admission` 完整原 hint10+B1 4，展开同一 workflow run 的四个独立 GitHub-hosted scratch job。**仅交 C 目录 review.patch，实际 workflow/harness/registry 未改，未运行 Java、数据库、容器或 CI。** 1c138a 原观察 helper、比较器16负控和严格14输入未改。

## 来源与隔离

- 只读基准：公开 root `c6668b81cc4da875294e860fcdf4c94db830c347`。
- 新 docs 候选 parent：`1c138a000387583e224c9acb5369140fdf2dba18`。
- 工作树 `/workspace/hengyin-c-m1-pair-ci-plan`；分支 `codex/architecture-day-c-m1-pair-ci-plan`。
- `source-manifest.json` 固定 c666 的 workflow/harness/registry SHA256、拟议完整 workflow SHA256 与冻结 comparer source。
- 唯一拟议共享文件：`.github/workflows/real-storage-it.yml`，交付 `real-storage-it-m1-pair.review.patch`，可直接在 exact c666 对应文件上 `git apply --check` 后审阅应用。实际文件仍原样。**ci/real_storage_it.py、registry、14 测试、生产源码均零差异。** c666 Theme/payment 批次和 `B_PAYMENT_INTENT_DDL_APPROVED` 原表达式保留。

最初评估独立 manual pair workflow；该选择需先解决新文件在默认分支可见的入口前提。现有 workflow 已有手动入口，因此最终只交可选旧 SHA 方案，避免另一入口的发布/发现步骤，也不增加可复用 runner/framework。

## 已核实的原行为

`database_suffix(run_id,attempt)` 原样为 `_gh%06da%d`（run_id mod 1000000）。它不依赖 source/class/job；同一 run、同一 attempt 的四个 job 天然得到同一 suffix。原 `container_name` 含 run/attempt/class，不含 side，但每个 `ubuntu-latest` job 获得独立 runner/容器命名空间，同名不会共用数据库。候选路径在各存储 job 均为 `candidate/`。

现有 `summarize()` 以 fqcn 选最新回执，不能直接塞入 old/new 同类四份回执：同 attempt 下可能选中一侧，另一 source 不会形成独立整批。新方案先按 side+source 分目录、分别调用原 summarize，最后执行既有冻结 canonical comparer；不用一个“28条通过”替代两份独立 source 的14条证明。

## 最小接线合同

新增输入 `old_source_sha`（可选，默认空）；其余输入定义/批次选择/时区保持原样。

| 模式 | source 输入 | 计划与执行 | 汇总 |
|---|---|---|---|
| 原单 source | old 留空，原 source_sha | 原班次/可选 classes 原样，仅矩阵增加内部 single/source 标记 | 单 source，一份原 artifact/summary 命名 |
| M1 pair | old=旧观察候选40SHA，source_sha=新观察候选40SHA | 两 SHA 必须不同；固定完整 C batch，classes 必须留空；两侧分别走原 plan/源码HEAD/静态 precheck，然后 exact 2×2 | old/new 两份 source 专属原 summary，再比严格 canonical14 |

四个矩阵行分别是 old×hint10、old×lead4、new×hint10、new×lead4。保留原 `fail-fast:false`、`ubuntu-latest`、`contents:read`、45分钟与每类独立 scratch；原 max-parallel20 不需扩大，矩阵本身只有4行。SOURCE_SHA、VALIDATED_SOURCE_SHA、candidate checkout、precheck.candidate.sha 与最终 receipt 各绑定**该行真实完整40SHA**；不能填未带观察层的 owner SHA。

原 `precheck → setup-java → storage-start → run → always storage-stop` 的命令/参数逐字不动，仍调用 c666 现有 harness/registry。两侧静态 plan 各执行一次，原10/4普通@Test、无skip、XML/CLEAN规则完全复用。没有改 GITHUB_RUN_ID/GITHUB_RUN_ATTEMPT、没有手工覆盖 suffix、没有 host/VPS/生产凭据。

填写 old 才启用 pair 计数/类集合检查；缺/重复/其他类、计数漂移、其他 batch 或 subset 均拒绝。留空时，原 matrix 的 class/simple 投影、source SHA、其余批次/门控与 artifact/summary 命名保持原语义；不将 M1 特定检查套到其他批次。

## Artifact 与整批证明

Pair artifact 命名：

- 4份原 class 包：`rs-m1-{side}-{full40source}-{simple}-a{attempt}`，保留原 receipt、XML、嵌套14-case trace和日志。
- 2份原 batch summary：`rs-m1-summary-{side}-{full40source}-a{attempt}`；各自只下载 `rs-m1-{side}-{full40source}-*-a{attempt}`，独立执行原 summarize。PLANNED 仍是原未加 side 的2类清单。
- 输入证明：`rs-m1-plan-a{attempt}`，含四行完整SHA/class/side、每侧14、run/attempt/workflowSHA、原 harness/registry 哈希。
- 最终 canonical 比较：`rs-m1-comparison-{old40}-{new40}-a{attempt}`。

不使用 merge-multiple；每个原 class artifact 仍保留自己的 receipt.json 子目录，以符合既有 summarize 的 `*/receipt.json` 读回。old/new 绝不交同一个 summarize。pair 下载只选**当前 run 的当前 attempt**；不沿用原普通模式的广泛 `rs-class-*` 去混取两侧。

compare job 先要求四个 storage job 和两份 source summary 均 success，并逐侧核两份回执的原 class 集合、source、batch、workflow.run_id/run_attempt、实际公开 env_profile suffix，必须等于原 `database_suffix()` 的本 run 结果。任何跨 source/run/attempt、缺类、suffix漂移均拒绝。随后只调用冻结 `1c138a000387583e224c9acb5369140fdf2dba18` 的 comparer/manifest，**不修改其16测试、14原输入、归一白名单或 schema/原错误文字严格比较**。

比较器仍独立要求两侧原 XML14/0skip、两个 CLEAN、Maven0、precheck/source SHA、完整trace、金额/身份/资格/错误/锁序/最终行一致；workflow 汇总成功不替代 canonical。最终比较脚本只跑 Python，不执行候选 Java。

## 重跑、root 应用与限制

1. root 先审查本 patch，再在自己的共享 CI owner 路径应用；C 本轮没有应用或 dispatch。可用已存在 manual workflow 的已审 ref，不需新增 workflow 文件或修改 master 来注册全新入口。
2. root 分别提交旧9a2 owner+原 old观察补丁/共用helper、新M1+原 new观察补丁/共用helper；必须均包含 root 所有 hint10 观察接线。两侧各自代码不得复制成同一个生产 owner，所有14正文/断言/清理保持原样。
3. 一次 manual run：`batch=architecture-day-lead-admission`、`classes=''`、统一 timezone，source_sha 填实际新候选40SHA，old_source_sha 填实际旧候选40SHA。两个候选必须先公开推送并经 root 审查。
4. 四个真实 scratch job 完整执行，分别汇总/清理，保留 pair-plan、两侧class包/summary和最终比较。只有完整原trace与严格比较PASS才证明该对 source 的T14；不是HTTP/JWT/生产/所有WP09验收。
5. **pair 必须 rerun all jobs 或重新完整 dispatch。** 若只重跑失败 job，attempt 变更后，其余旧包不匹配当前attempt；summary/compare会缺证据而失败，不能拼接其余旧attempt“凑齐”。普通模式保留原重跑行为，不在本补丁顺手更改。

方案的同 run/suffix/路径解决已知缺表 driver prose/schema 差异；仍须满足冻结比较器的同环境、原case顺序/主键、原日期白名单与其他错误文字不变。若实跑发现其他差异，保留原rawtrace定位，不扩大归一。新/旧 JDK/MySQL事实仍由原artifact及冻结比较器逐字核对。

静态检查没有 actionlint 或 GitHub 服务端 workflow 解析/运行；PyYAML 只用于现有云环境静态检查（未安装或加入仓库依赖）。它不能证明 GitHub 表达式求值、artifact action服务行为、真实source编译、时序或实际等价。root 审查后仍需受控执行，不能以此10条 Python 证明T14已过。

## 本次实际证据与回退

`PYTHONDONTWRITEBYTECODE=1 python3 .../check_pair_patch.py`：**10/10 PASS**，原输出 `static-checks.txt`。包含 c666三文件哈希、精确patch应用、YAML/Bash/Python语法、四行矩阵/10+4、单source投影、原存储命令与付款门控保留、缺/重复/异类/异计数拒绝、跨source/run/attempt/suffix/缺summary拒绝、side/source/attempt artifact隔离。合成数据仅测试 JSON 输入/来源守卫，不含 canonical/完整XML/CLEAN，不能通过冻结 comparer。

`git diff --check` PASS；秘密扫描见 `secret-scan.json`。未执行 Java/Maven/MySQL/Redis/Docker/HTTP/CI/dispatch；未运行真实旧新14。1c138a freeze保持；本轮改动只有本 C docs 子目录。

root 尚未应用时撤销 docs 候选即可；应用后可仅回退该workflow hunk，原harness/registry/生产/fixture无迁移，不涉及数据库或业务回退。原 1c138a 观察候选独立保留。
