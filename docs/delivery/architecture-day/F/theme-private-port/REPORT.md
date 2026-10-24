# F：第三私有 Mapper 的窄编译引用约束

结论：第三项精确技术端口绑定已复用 fe95 守卫实现；**153/153 Python 自测实际通过、0 skip，原 141 项 case 均保留**。这是可审查、可 fetch 的守卫候选；第三目标真实 Java/JAR/jdeps 正控、越界编译负控及移除转绿尚待 root 获准 CI。没有新增解析器、图框架、依赖、job、业务 owner 推断或 baseline。

## 公开基线与独占 delta

- Parent / 实际开工 HEAD：`4197f6a6b11d62cb0a742aae73d7167c93114585`，公共仓库 hengyin。独立工作树 `hengyin-f-theme-private-port`，分支 `codex/architecture-day-f-theme-private-port`，开工干净。
- 唯一执行/数据文件：`ci/architecture/gate.py`、`collect_jdeps.py`、`owners.json`、`test_gate.py`、`test_collect_jdeps.py`、`README.md`；另仅本 F 交付目录。
- Page d9f 候选冻结，不改。`commands.json`、legacy2ec baseline、locking proposal、原其它源级规则、模块图、五项 parser 返回值不变。
- 不改 `.github/workflows/ci.yml`；不改 C 拥有的 `.github/workflows/real-storage-it.yml`、`ci/real_storage_it.py`、`ci/test_real_storage_it.py`，也不改生产 Java/XML/SQL。
- [review.patch](review.patch) 是上述六文件相对 4197 的精确零上下文 delta（`git diff --binary --unified=0`；应用需 `git apply --unidiff-zero --check review.patch` / `git apply --unidiff-zero review.patch`，已在隔离公开基线应用并逐字节回读）；不含 CI repin，不含本目录自身。root 应独立审查后用同一完整 trusted verifier SHA repin 原两个 checkout。

## 失败机制、当前调用证据与最小修复

现有 raw parser 已在跨模块过滤**前**接受指定 target 的 class 引用；原只声明两个目标，所以第三 Mapper 即使有同 System 越界生产依赖，也不在私有端口检查范围内。仅增加 import 正则会易绕且不能证明实际编译 class，因此未新增源码 scanner，也不自造字节码 parser。

4197 下生产 caller 查询：

```sh
rg -n 'AiThemeDraftIntentMapper' chengyinhub-*/src/main
```

只得到 Mapper 本身、同 namespace XML，以及 `service.impl.AiThemeDraftRequestService` 的 import/原 `intentMapper` 字段。Controller 与 `AiQuotaRecoveryTask` 依赖该 Service，不直接依赖 Mapper。Mapper 是普通生产 interface；此为源码事实查询，不是当前候选已编译证明。查询输出、三个源码 blob / SHA256 见 EVIDENCE。

实际修复：

1. 保持原两个绑定，追加：
   `com.chengyinhub.business.mapper.AiThemeDraftIntentMapper` → `com.chengyinhub.business.service.impl.AiThemeDraftRequestService`，owner 均 `chengyinhub-system`。
2. `private_ports` 是第三项**技术编译依赖合同**。原 `business_policy.scope/facts/pure_writers` 和 commands 仍只覆盖 AI_QUOTA_RESERVATION / PHOTO_CHECK_INTENT 两个 whole-table fact；没有因新增端口而授予第三张表/字段/command 机器业务写权。验证器保留原两端口与原公开 Service 合同的关联校验，再单独验证第三项精确 class/module 绑定；候选不能改 owners/producer 后自批准。
3. 复用实际 Boot JAR / 原模块 JAR / target classes 重枚举、class 字节 SHA、同 source/tree/run/attempt / JAR / JDK 工具 / 原 11 份 evidence / invocation 回读。不更改 `parse_output`、采集命令、依赖输入枚举或模块边格式。原 `-verbose:class -filter:none` raw 中同 System 行可进入选定 target 的 references，仍不增加 System 自环。
4. 私有引用回读要求三个目标齐全，kind/coverage 明确为 THREE_PRIVATE_MAPPER_COMPILED_REFERENCES / THREE_DECLARED_TARGET_TYPES_ONLY。仍为 exit0 scoped pass、exit1 确定 PRIVATE_MAPPER_CONSUMER、exit2 UNKNOWN。缺目标/consumer/class-index/原行/位置/完整采集均不冒充空图通过；确定违规和其它 UNKNOWN 并存仍为 BLOCKED。只读引用也禁止，Service `$Nested` 不按前缀获准。
5. 原六保护文件按独立可信 verifier 字节冻结的机制不变。新增第三项不意味着允许候选自扩 scope、增加 baseline 或批准其它域。原 source gate、缓存/SQL/write scope/依赖规则全保留。

## 实际 Python 验证与负控

运行命令（仅 Python 标准库、临时 Git/ZIP fixtures）：

```sh
python3 -m unittest discover -s ci/architecture -p 'test_*.py' -v
```

- 原 4197：实际 **141 tests / OK / 0 skip**，7.036s。
- 本候选：实际 **153 tests / OK / 0 skip**，7.665s。原 141 个 test id 全部存在且通过，新增12个；原 raw 九条模块边/五返回值不变控制保留。
- 新8项回读负控：第三 Service 合法边；具体 foreign 同 System 只读边硬红；第三 Service 内部类不自动允许；第三 raw 行缺失 UNKNOWN；第三实际输入 class 缺失 UNKNOWN；第三索引/位置伪造 UNKNOWN；只交原两绑定不能声称第三覆盖；真实 CLI 0/1/2 输出持久化。
- 新4项 policy 控制：3 private ports 不扩大2事实/2writer/2command；候选改第三 consumer/删第三项不能自批准；可信数据的非法 module/class 不能静默关规则；新增第三项也不能删除原 writer 端口。
- 原 CI 成本合同：`python3 -m unittest discover -s ci -p 'test_ci_cost_contract.py' -v` 实际6/6 PASS、0 skip。其“Exact SHA has successful full Main and Funds CI”是原测试 fixture 输出，不是本候选实际 CI 成功。首次按模块名调用缺少原测试 import 路径后，已改用该现成 discover 入口；未改测试、未跳过。
- Python fixture 的 class bytes 故意标识为 `NOT_COMPILED_FIXTURE_...`，runner 是已有协议 double，**没有执行 Java/javac/jdeps**。负控是对实际原 JAR 形态重枚举/证据合同的自测，不是假称真实编译验收。
- 日志最后 `jdeps observation: UNKNOWN; no baseline/SCC acceptance` 来自已有 UNKNOWN 输出负控，不表示 Python 用例失败或真实 producer 已运行。原始输出与内容 hash 见 EVIDENCE/evidence。

## Root 唯一 CI 接线与第三目标真实负控

不新增 job、runner、依赖或第二次 jdeps。沿当前 ci.yml 的 source verifier checkout 与 `target/f-private-port-verifier` 两处固定完整 ref，由 root 同时 repin 本候选审定 SHA，保持原 6 文件保护、target 可信 checkout、绑定的 tested JAR/build identity、单次 producer、post-check 和红时原 artifacts 保全。

1. 正控：干净整合源码在原获准 GitHub-hosted Backend job 编译/JAR/startup 后，运行原单次 jdeps 和 `compiled-ports`。须实际读回第三 Service→Mapper 的 raw 行及两端 class-index 记录，并以本次 System JAR 真实 class 字节校验，三个目标均证据完整才签 scoped pass。
2. 隔离负控：将本目录 [Java fixture](ArchitectureThemeDraftIntentForeignReference.java.txt) 复制到独立公共负控分支的 `chengyinhub-system/src/main/java/com/chengyinhub/business/architecture/ArchitectureThemeDraftIntentForeignReference.java`，保持 XML/SQL 不变，由 root 运行同一批准 Backend 路径。该 class 不注册 Spring bean，不调用 Mapper 方法；字段、构造及返回值类型足以形成真实 class 依赖。它必须被打入 System JAR，不能仅放测试目录。
3. 预期 raw 形态（**待测，不是已采集行**）：

   `com.chengyinhub.business.architecture.ArchitectureThemeDraftIntentForeignReference -> com.chengyinhub.business.mapper.AiThemeDraftIntentMapper chengyinhub-system.jar`

   实际 gate 必须 exit1，finding 的 source_class/target_class/allowed_consumer、System owner 与真实 class SHA 精确对应；模块图不能靠同模块过滤漏掉它。保留 build/startup/原 XML、JAR、identity、11项 jdeps evidence 和结果。
4. 仅移除隔离负控类，再用新的同源候选跑批准路径，预期第三目标恢复 scoped pass；不能取负控红前的旧绿，也不能把前置 compile/test/startup 失败算目标闸负控通过。
5. 缺图或前置构建失败必须 UNKNOWN/未验，不签“无越界”。范围外反射、XML 运行时、方法调用/必经 Service、权限/资金/额度、其它 business owners 和完整 SCC 仍未知。

root 新消息提供的旧两目标实际证据：86e/source run40057561361，build+startup成功，故意 PrivateMapperBoundaryNegativeControl→AiQuotaReservationMapper 唯一 finding，原1842 XML/11 jdeps hash及实际类/JAR回读已核验。这是 **root 报告、F本轮未独立下载** 的原两目标证据，不计入本第三目标通过。root 同样报告联合 Page 204 Node/UI gate通过；没有挪作本架构候选验证。

独占 delta 的 `ci/secret-file-gate.py scan_paths` 与原配置 gitleaks 8.21.2 检查均通过、0 findings；不新增扫描豁免。

## 未验证、迁移与回退

本轮没有 Java/DB/Redis/真实 provider/微信操作，没有 CI 派发或新 agent，也没有生产访问。未取得第三目标真实编译证据，不宣称全域 owner、无环或完整 WP01 完成。没有 schema / DDL / 新依赖；回退此守卫仍只能按 root 独立批准的 trusted ref 执行，回退到 fe95 会失去第三目标约束，不能把该盲区说成已覆盖；不改/删除业务事实。


## 新职责方向源码补片（从545精确追加）

冻结verifier源码SHA `355fb5a9773fce34db8764649a977ffb93164de2`，parent为原545；仅六份architecture文件及一份定点输出。它登记d49已落地的8个技术职责/13个类型，原三Mapper和两whole-table fact不动，不赋业务写权。生产CLI在原同一次JAR/raw/index回读中强制执行方向、回依赖原God、scoped owner SCC规则；policy自改仍被原六文件冻结挡住。未登记职责/间接调用/同owner内部环保持未证明，不把refresh GET当只读。

按最新批次优先级，仅实际跑12项必要Python协议/CLI负控，0fail/skip；缺类/模块/原始输入继续UNKNOWN，确定违规与UNKNOWN并存仍红。没有重跑完整153、Java/DB/CI。旧2ec baseline/commands/locking proposal、全部生产/主线融合代码和工作流未改；root仅取545→355的精确architecture delta，不用旧整树覆盖540或主线#567/#585/#586。

`owner-directions-ci-repin.patch`只替换两个545 ref→355完整SHA（零上下文，使用`git apply --unidiff-zero --check`后应用）。补丁按root已明确报告的545待提交CI状态构造，并在隔离副本逐字节验证；公开540快照本身仍是fe95，不把它误写为已发布545。root当前旧值不是545时应停止并自行核对，不恢复fe95，不改job/并行/producer次数。真实编译正反控与统一回归仍由root调度。
