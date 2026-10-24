# F / WP01 phase2：两项私有 Mapper 编译引用约束

结论：完成已批准的两目标class引用收集、六文件冻结与可信post-build回读接口。原五项解析返回值、跨模块JSON/模块边观察口径和source规则保留；未建全仓新图、基线、SQL/字节码parser、依赖或job。141项Python自测通过（原115＋新增26），成本合同通过。**本候选未接真实CI、未执行Java/jdeps；同System真实compiled负控及撤除转绿仍须root完成，不能用合成ZIP替代。**

## 身份与独占范围

唯一公共仓库 `https://github.com/liboyang42-cpu/hengyin`。本片parent/实际开工HEAD `1c63e55a167f235930b5811bf28203a29231c8ef`，分支 `codex/architecture-day-f-private-mapper-compiled`，独立工作树、干净开工。parent中architecture执行/数据文件与8da相同；不从旧行号重做已修项。只改gate.py/test_gate.py/collect_jdeps.py/test_collect_jdeps.py/owners.json/README及本F目录。commands、public2ec legacy baseline、locking proposal、root workflow/registry/harness、所有生产Java/SQL均未改；原8da/685/663提交不重写。

## 失败机制、真实输入与批准边界

旧producer在 `a != b` 时才保存class引用，所以同System边存在raw中却不在module JSON中；旧source gate也未保护producer文件。先运行负控：传入额外收集参数报TypeError；candidate伪造producer时旧source CLI仍返回PASS/0。补齐collector与producer保护后，两项均通过；source伪造现返回POLICY_CHANGE。

root已实际下载864e/run40048901603/attempt1的artifact12542084119并核验11项hash、Boot Jar及四份class bytes。公开凭据是 [root发布的不可变证据](https://github.com/liboyang42-cpu/hengyin/blob/1c63e55a167f235930b5811bf28203a29231c8ef/docs/delivery/architecture-day/COORDINATOR/864e-private-mapper-raw-jdeps.json)。F本轮读该公共文件并用其中实际行/四记录测试现有解析，未独立下载全制品，也未把该摘录当完整post-check通过证据。旧下载通道两次403不绕过代理；详见 [evidence.json](evidence.json)。

| 精确目标（system） | 唯一允许的生产consumer（system，含只读依赖） |
| --- | --- |
| com.chengyinhub.business.mapper.AiQuotaReservationMapper | com.chengyinhub.business.service.ai.AiQuotaRecoveryService |
| com.chengyinhub.business.mapper.PhotoCheckIntentMapper | com.chengyinhub.business.service.ai.PhotoCheckIntentService |

这是root新增批准的技术私有持久端口边界，不是从observed writer自动推导。其它实际consumer必须报，不自动加allowlist；`$Nested`也不按Service前缀允许。它不改变产品/运行授权、额度/退款政策或原Service事务；方法调用必经、反射/XML和运行时依赖仍未证明。

## 最小实现与调用链

1. `owners.business_policy.private_ports` 独立声明两条target/物理owner/exact consumer；与原SQL pure_writers分开，避免今后加SQL fact时自动扩大编译依赖scope。loader核对其对应原writer/command公开Service类，拒绝空/重名/未知绑定；commands内容保持原样。
2. `check_source` 原完整SHA、clean树、公有2ec精确重算、默认主干实际merge-base、scope/cache/technical writer/business writer规则全部保留；protected files追加实际collect_jdeps.py，共六个。candidate修改binding/producer不能自己批准。
3. `parse_output(text,index,inputs,selected_references=None)` 在已确认source/target索引及target location后、跨模块过滤前额外记录两目标引用及实际class hashes；返回仍是原五元组。原collect默认不收集该额外映射，OBSERVATION_ONLY/module_edges/references格式保持，不制造System自环或新SCC/baseline。九边格式回归使用明确合成的模块行；不是新候选实际无环证据。
4. `gate.compiled_ports` 从独立可信checkout加载producer和两条冻结policy→`check_private_ports`→原bind_identity、stage_inputs和parse_output。stage_inputs回读重枚举实际Boot/模块original Jar/target/classes及hash，跳过外部library再提取，不运行工具；再与原11项文件、actual class-index、输入Jar、JDK tool/version/invocation、source/tree/Jar/run/attempt逐项核对。
5. post-check不读候选JSON的references/findings/complete当安全结论，重新解析raw。确定其它consumer为PRIVATE_MAPPER_CONSUMER；输入、目标、索引、位置、解析覆盖、预期Service入边缺失均UNKNOWN，不能空图通过。确定违规可与其它UNKNOWN并存，仍BLOCKED。结果始终声明仅两个目标，不是新全仓图。

## 实际测试与证据界限

运行命令、失败前置和实际输出见 [checks.txt](checks.txt)。新增26项：3项source冻结/绑定负控；2项原始行/九模块边口径回归；21项ZIP/协议回读测试，包括同System外来consumer、$Nested、伪造安全JSON、重签manifest/索引、实际input/class不符、空/缺raw、缺目标/Service、错location、不识别格式、run/source/Jar不一致、JDK替换/错误invocation、symlink/traversal manifest，以及CLI 0/1/2与结果落盘。原115项没有删减或skip。

ZIP fixture故意写入NOT_COMPILED字节，runner是合成stdout；positive还限制post-check只能启动Git命令，不能运行Java/jdeps。root原始两行测试只证明解析实际格式，不是F执行JDK或全产物回读。VM仅Python/Git/静态与秘密扫描；本轮Java/HTTP/MySQL/Redis/模型/CI dispatch均未运行。

hash自洽不能证明任意producer诚实。**真实接线必须同Backend job执行固定可信版本producer，再执行可信post-check**；原CI已有的嵌入commit/Jar资源/启动验证必须在前面保留，实际编译source用checkout SHA（PR merge与logical head分开），不能混候选/attempt。见 [CONTRACT.md](CONTRACT.md)。

## 集成、迁移与回退

root先review本片，独立冻结完整verifier SHA，把相同执行/数据delta集成candidate；再唯一repin source verifier与Backend可信checkout，单次jdeps＋post-check，保留原XML/Jar/raw/index及结果。F未改workflow；当前8da接线不能被称为已经启用phase2。真实负控必须在隔离候选增加同System直接依赖的生产class且XML不变，实际JDK/本检查红；撤除后同源新候选绿，再签phase2验收。

无DDL、schema、生产Java/SQL、数据/资金/权限、依赖或并行预算变化；新增baseline/豁免0。回退需root协调可信ref与六保护文件同版，不可只清空private_ports或换producer伪造绿。已存在的未知caller如被真实raw发现，应报root处理，不导入观察清单自动洗白。仍未验证全A01/A02/WP01/WP13或系统无环。
