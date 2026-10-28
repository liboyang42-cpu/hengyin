# C / M1 同 run 双 source CI 实现

结论：已实际实现可选 `old_source_sha` 接线与 pair 正负控；本候选有实际 workflow/harness/test 代码。原47+新增17=**64条 Python 全通过，0fail/error/skip**。Java/MySQL/Redis/真实 GitHub Actions/两侧T14 canonical 均未执行，留给 root 统一验证；本地控制结果不代表真库或等价验收。

## 基线与实际文件

- root 基线/本分支 parent：`4197f6a6b11d62cb0a742aae73d7167c93114585`。
- 独立工作树 `/workspace/hengyin-c-m1-pair-ci`，分支 `codex/architecture-day-c-m1-pair-ci`。仅公共 hengyin 输入。
- 所有权移交后的实际修改：`.github/workflows/real-storage-it.yml`、`ci/real_storage_it.py`、`ci/test_real_storage_it.py`，以及本 C 交付目录。
- registry（含 root theme22）、所有 Java/原14 fixture、生产源码、D/其他工作均零改动。
- `021e16a7` 方案已转实际接线；没有应用其较宽的单 source matrix/summary 改动。现有单 source `plan` 和 summary 代码路径保留，pair 走独立窄命令/summary，不将两个source送入同一个原summary。

## 调用链与合同

旧 SHA 留空：原 manual plan → 原 class matrix/原 source SHA → 原 precheck/setup-java/storage-start/run/storage-stop → 原 `rs-class-*` artifact → 原 Batch summary。原 inputs 定义、batch/options、max-parallel20/fail-fast:false、原付款专属 DDL flag、普通模式重跑规则保持。单 source matrix 仍只有原 class/simple 字段；没有新增 side/source 字段改变其消费。

填写 `old_source_sha`：原 `source_sha` 是新侧；新增旧 source checkout；`m1-pair-plan` 要求两个不同完整40SHA、固定 `architecture-day-lead-admission`、classes留空、原时区。原registry必须仍是 hint10+lead4、无nested/新gate/其他storage；任何缺旧SHA/同SHA/错误SHA/裁剪/类数或计数漂移均拒绝。

`m1-pair-plan` 对每侧调用**实际原 cmd_plan**，沿用候选 HEAD 与 SOURCE_SHA 一致、原 Java 静态扫描/动态测试拒绝/JDK/gate 预检。任一侧失败不发布 matrix。全部通过才输出 old/new × 原两类，4行 matrix，每行绑定各自完整 source_sha；两个独立summary仍消费原未扩展的2类清单。

四个 storage job 均是独立 GitHub-hosted `ubuntu-latest`；SOURCE_SHA、VALIDATED_SOURCE_SHA、candidate checkout 同用该行 source；原 precheck → setup-java → storage-start → run → always storage-stop 命令与参数不变。默认模式使用原 inputs.source_sha fallback。候选运行路径在四台独立 runner 上均为 `candidate`，不改变 fixture/错误资源路径。

原 `database_suffix(run_id,attempt)` 和容器逻辑不改，也不覆盖 GITHUB_RUN_ID/GITHUB_RUN_ATTEMPT/任何 suffix。四 job 同 run/attempt 自然取同 suffix；同名容器位于各自独占 runner，不共用数据库/连接/资源。没有新增后台runner、VPS或生产连接。

## Artifact、来源与终态验证

- class：`rs-m1-{side}-{full40source}-{simple}-a{attempt}`，原 out/含receipt、XML及嵌套trace原样上传。
- summary：`rs-m1-summary-{side}-{full40source}-a{attempt}`。每侧仅下载自身 fullSHA/currentAttempt 的两份包，独立调用**原 summarize**，不能让 fqcn 索引覆盖另一source。
- plan：`rs-m1-plan-a{attempt}`，四行/source、每侧14、workflow/run/attempt、原harness/registry hash。
- compare：`rs-m1-comparison-{old40}-{new40}-a{attempt}`，包含来源核验和最终canonical结果（若到达）。

`m1-pair-verify` 先要求四 storage + 两 source summary 均 success；逐侧必须恰好两份原receipt，逐份核对：

- 原schema、fqcn集合、source_sha、precheck.candidate.sha、batch一致；禁止跨source/缺类/重复类/额外包。
- repository、workflowSHA、run_id、run_attempt 必须是**当前**workflow事实；只取当前attempt的artifact。
- 两个原公开suffix字段必须等于现有database_suffix函数结果；禁止自定义/跨run后缀。
- 原PASS、Maven0、独立CLEAN且leftovers精确空列表；原10/4 passed、0fail/error/skip、完整唯一testcase/status。

核验输出 `pair-provenance.json`，明确 `not_canonical_verdict=true`；来源PASS不代表行为等价。随后 compare job checkout并调用冻结 **`1c138a000387583e224c9acb5369140fdf2dba18`** 的原比较器/manifest，不传替代manifest、不改变16负控/14输入/错误文字或任何归一白名单。只有它读回两侧完整原XML/trace/CLEAN并比较成功才是T14 canonical PASS。

某个job/summary缺失、skip、失败、非CLEAN、wrong source/run/attempt均阻止通过。核验失败保留FAIL来源JSON；原class/summary包保留。若下载阶段已无artifact，Actions任务直接失败，不伪造来源或canonical成功。

**pair 重跑只能 rerun all jobs，或重新完整 dispatch。** 仅重跑失败job会产生新attempt；当前attempt的四份回执不齐，必须FAIL，不能混借旧attempt补齐。普通单source路径不顺手改变其原重跑策略。

## root 使用

root 集成代码后使用现有 manual workflow的已审ref，填写：

- batch=`architecture-day-lead-admission`、classes为空、统一UTC或Asia/Shanghai；
- source_sha=已推送并经审查、含新M1 owner与原完整观察接线的新候选40SHA；
- old_source_sha=已推送并经审查、旧9a2 owner加原观察接线的旧候选40SHA。

两者必须是实际含观察层的提交，不能填裸owner SHA或把新生产源码复制到旧侧。root所有hint10观察接线仍须分别到位；本CI实现不改14测试来制造证据，不更改其原TX/connectionId/latch/cleanup断言。来源检查核对明确选择的40SHA，不额外宣称已证明候选历史角色/人工审查；root仍需确认旧/新owner装配。

四job/两summary/来源核验/冻结canonical共同成功后，下载全部原artifact并核source-bound hashes；保留rawtrace，不因新差异扩大schema/金额/错误/日期归一。

## 实际验证

1. 修改前 baseline 原47控制：首次在受限socket上下文出现既有 `test_handshake_and_ping_over_loopback` PermissionError；未删/skip该case。允许网络执行上下文重跑 **47/47 PASS**，`baseline-47.txt`。该case是原临时loopback握手字节模拟，不启动真正MySQL/Redis。
2. 最终代码：`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v ci/test_real_storage_it.py` 在允许loopback上下文实跑 **64/64 PASS，0skip**，`python-64.txt`。日志中原`OWN...FAIL`为期望的门禁负例输出，不是真实数据库回执。
3. 新17条：原完整类/计数及只允许C batch、源身份/缺旧/同源拒绝、实际原静态预检复用、两侧wrong HEAD均不得发布matrix、动态测试拒绝、普通plan原JSON形状、四回执来源与独立CLEAN、跨run/attempt/workflow/repo、suffix、缺/重/异类、Maven/skip/计数/失败summary拒绝、workflow paired artifact/frozen comparer/模式负控。数据均为控制层合成输入，不含可冒充T14的完整trace/XML。
4. `check_static.py`（现有环境PyYAML仅静态用，无新增依赖）：PASS，`static-proof.json`。原47测试正文AST不变；原harness既有函数除parser/main注册外AST不变；普通plan步骤及summary正文/原storage步骤保留；原inputs与registry hash保留；YAML/Bash/内嵌Python语法检查通过。不是GitHub服务端表达式/actionlint或执行证明。
5. `git diff --check` PASS；秘密扫描见 `secret-scan.json`，原ignore/config未改。

没有运行 Java/Maven、真实数据库、容器、HTTP、GitHub CI/dispatch；没有真实canonical receipt，不借用旧源555unit/14真库绿证明本source。root统一编译、真实14/CLEAN与同run双source比较仍待执行。

## 回退

仅回退这三文件实现差异即可返回原单source manual入口；registry、Java、fixture及冻结1c138a工具不受影响。无DDL/数据迁移、资金或生产回退动作。已执行的原artifact保留；不得把部分pair artifact当其他source整批结果。
