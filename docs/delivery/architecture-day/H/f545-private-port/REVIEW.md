# H：第三私有 Mapper 规则及16行编译负控

结论：第三目标规则本身未发现新增可证 P1/P2；发现 **H-F545-01 / P2：现有 workflow 在 trusted checkout 后执行候选脚本，verifier 可被改写**。已先短报。这是继承的 CI 信任边界，不是本次第三规则回归，也不是已实跑的攻击或全CI绕过证明。

唯一候选 `5451672c72fa1fe10aef0315ec5b1e81cc8f1866`，tree `f6ddef8a8aeffb03064c9fccc4b8da6ac2f9961d`，parent `4197f6a6b11d62cb0a742aae73d7167c93114585`。H 开工 HEAD `2ab933cbb98e93e78c2a8766b3a41b43dd520d6b`，独立工作树 `/workspace/hengyin-h-review`、分支 `codex/architecture-day-h-review`，干净。仅写本 H 目录，不改规则/CI/fixture，不运行 Java/DB/CI。

## H-F545-01：可触发的 verifier 改写窗口

精确链为候选 `.github/workflows/ci.yml:568–575` 检出固定 ref 到 `chengyinhub-admin/target/f-private-port-verifier` → 580–584 执行候选的 `ci/verify-built-jar.sh`、`scripts/verify-candidate-resources.py` → 604、611直接执行该 verifier 目录的 producer/gate。中间没有重新检出或独立检查该子 checkout 的固定提交/文件字节。

最小反例设计：候选在上述一个脚本保留原验证后，增加对该可写子目录 gate.py 的改写，使 compiled-ports 写一个合成PASS并exit0；producer同理可以被改写。无需改候选的6个 protected policy文件。`gate.py:603–609` 的 POLICY_CHANGE 是比较候选规则与 HERE 文件，不检查另一个已经被改写的执行目录；父 checkout 的 clean/tree/hash 也不证明 ignored target 下 verifier 文件没变。此时源码 ref 固定，但实际执行字节已不是该 ref。

这证明 compiled-reference 阶段当前依赖的“同 job、不可变 producer”前提没有被该顺序强制；不声称其它 CI 审阅/检查一定全绿。H未改写任何真实 verifier 或运行该攻击。

最小修正建议：把可信 verifier 的最后一次 checkout/固定SHA及干净字节检查放在候选脚本之后，随后紧接 producer+post-check，不再插入候选代码；或由独立可信执行路径运行验证工具。两处 ref 的 repin 本身不能关闭此窗口。这里只指出直接可写顺序问题，不把普通同用户工作区的 git检查当成对任意恶意后台进程的OS隔离证明。根验证应在隔离负控源加入上述受控改写，要求执行前拒绝 verifier 字节漂移，而不是让合成PASS继续。

## 规则、原保护与负控

- `owners.json` 原两项绑定、scope/facts/pure_writers未变，追加精确 `AiThemeDraftIntentMapper` → `service.impl.AiThemeDraftRequestService`，owner为system。对应父源真实 Service 的 import/字段及 Mapper interface已核对；没有额外授予第三张表/字段/command的业务写权。
- `gate.py:361–371` 校验三绑定、模块/FQN和唯一目标；原writer仍必须有端口，原两项consumer与commands的关联检查保留。第三项由独立审阅的policy给定，不能从候选manifest自选consumer。source gate仍冻结原6个策略/producer文件；**在实际执行的可信代码未被改写这一前提下**，候选改consumer/删第三项不能自行批准。
- `collect_jdeps.py:245–347` 仅把目标数/coverage扩为3；实际原JAR/Boot/target class字节重枚举、source/tree/run/attempt/JDK/hash/invocation核对未删。`parse_output` 在模块边过滤前收集选中目标，因此同system只读类型引用也受限；Service `$Nested` 不按前缀获准。缺class、raw行、索引/位置/完整输入等为UNKNOWN，确定违规与UNKNOWN并存仍BLOCKED；CLI 0/1/2分别为scoped pass/确定违规/未知。不是全图、无环或授权验收。
- 原141个test ID都保留，候选153个，新增12个。AST比对原测试方法体仅精确consumer正控的目标数/coverage期望由2→3；其余旧测试方法体未变。原两项绑定和事实范围逐值一致，不扩大baseline。
- 16行 `ArchitectureThemeDraftIntentForeignReference.java.txt` 为普通main class、私有字段/构造器/返回值都静态引用第三Mapper，不注册bean、不调用方法、无业务写。放入指定main源路径并实际编入System JAR后，预期形成同模块越界引用；必须由实际class-index/JAR/raw jdeps及exit1证明，不能仅凭文本存在或前置编译失败认定负控红。

## 实际验证与缺口

H实际执行：18份文件 Git blob核对、Python AST/JSON比对；`python -m unittest test_collect_jdeps.PrivatePortsReadbackTest -v` 实际 **29 tests / 1.361s / OK**。这些是标明NOT_COMPILED的ZIP/class字节和mock runner协议测试，未调用Java/javac/jdeps。没有独立重跑原141/完整153，F自身日志不冒充H实证；大baseline正文未取得，未声称本轮独立重建全baseline。

候选 workflow 两处仍固定 `fe95acc58dd3049096e7dcea407d0728b4fa3f5f`；root尚须独立审定/同时repin，旧ref不能算第三目标覆盖。Java正控须实际包含三个target/consumer和完整输入；16行负控须实际编入main并只有该引用造成exit1；移除负控后的新同源正控、全部原证据hash和UNKNOWN负例须另验。H未改源码或派CI，无DDL/依赖/部署影响；回退到两目标会丢失第三约束，不能称已覆盖。
