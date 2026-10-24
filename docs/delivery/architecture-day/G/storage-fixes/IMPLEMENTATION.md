# G：H-G618 两项 P2 原 storage 修复候选

结论：仅修 H-G618-01 payer 来源身份与 H-G618-02 对象名冲突/已提交空阶段恢复。保留原30正文和fixture，加 H4（仅第四条切到专属恢复入口）及11条必要正负控，共45条 MySQL 测试源码。**本批 Java/MySQL 执行0；尚未证明45通过**，由root受控CI统一验证。无Reader、资金算法/消费者、D迁出/CmsTopic或旧历史migration变更，无生产DDL。

## 基线、范围与失败证据

- 公共仓库 `liboyang42-cpu/hengyin`；参考2ec68899099a1e250df0949a8383bf85b5d3ffe0。
- 本批实际父提交 `61834b1ba96fcfb025d8442955b69f1d6b35d5f3`；独立工作树 `/workspace/hengyin-g-storage-fixes`，分支 `codex/architecture-day-g-storage-fixes-20261024`，创建时干净。原618/30绿历史保留，旧worktree/分支不重写。
- 已读取 H 完整 `4b6bbf16ccd6cc554f6a26d02875db36312b08a8:docs/delivery/architecture-day/H/g-618-structure/REVIEW.md` 及 `structure-negative.review.patch`。
- root已逐source/XML/receipt/case/hash核验：97b823e5c8bb86675825476a07c9cdf671063f2a / run40044883897，原30pass+H4failure，0error/skip、CLEAN。四项分别为NULL单借payer未拒绝、零价从单自称payer未拒绝、index重名漏preflight、半迁移full rollback缺列。以上为root实际证据通报，不是本机执行，也不是生产事故。
- 生产相关diff仅自有 Mapper Java/XML和未合并/未生产执行的g_cash候选migration/precheck/full空回退；另有专属test-scope恢复工具/CI启动脚本与IT、交付目录。三DTO、REFUND_APPROVED事件SQL、claim NOTNULL/旧unique/真实member约束、非本批migration/CI登记均保留。

## P2-01：真实 payer 合同

来源：`CmsRegistrationServiceImpl.createRegistrationInternal` 原样存parent；独立单parent=NULL；`createMultiTicketRegistrationsInternal` 同一创建轮生成非空parent，**首个创建结果承担整单正payable，从单payable=0**；Checkout/RegistrationOrderResult用原primary，外部out_trade_no用该registrationNo。`RegistrationOrderServiceImpl.loadOrder` 对无parent仅返回原单。没有“同member/owner就能共享payer”合同，也没有用minID替代原primary的授权。

- 独立单：`payer.id=r.id`，原号的既有binary核对保留；新CHECK对NULL parent也强制self ID/self原号，防direct SQL绕过。
- 有父单的本批可证明正额路径：严格binary同parent、同member/owner；payer的原payable必须非NULL且>0，**整个同parent集合**不允许另一个正payable、未知/负payable、跨owner/member。这是既有建单的“整单主体/零从单”证据核对，不是最大金额算法，不取最小ID。
- 全零父单、缺金额/矛盾主体/空白父号不能证明原付款主体，零行插入明确抛 `CASH_EVIDENCE_PAYER_OR_SOURCE_IDENTITY_UNPROVEN`。本结构不造primary、不补原金额、不要求假VERIFIED事实；以后如要支持无法从现存行证明的零额父单，须先补真实不可变primary关系合同，本批不猜。
- source状态/金额/原号及payload/重复读回原有校验不减；同身份同载荷仍返回原回执，source退款后也不覆盖旧贡献。已有错误证据不自动修正/清除；新迁移遇已存在cash表照常拒绝覆盖。
- 非空父单的跨行证明由真实 INSERT...SELECT/可写事务断言实施，CHECK不伪装成跨表payer角色证明。全链topic fence及原单预锁协议仍待成组接入，本批未接生产调用者，不据此声明并发资金服务已验收。

## P2-02：首DDL前预检

SELECT-only precheck与migration首硬断言完全对应保留旧条件，同时枚举本候选引入的：

- 新三表（同名view/table亦阻断）；head5/adjustment2个新列。
- head5个、adjustment3个显式新index名，按实际表作用域检查；不自动覆盖/删除已有index。
- 11个FK及5个CHECK约束名，按schema检查；不靠模糊prefix推断对象归属。
- 原order unique必须一列，原adjustment unique必须完整claim/entry/event列序，继续保留。

postassert也按精确本批FK/CHECK名计数，避免与无关同前缀对象混淆。DDL仍auto-commit；预检不假称能消除网络/阶段故障。

## 分阶段、仅空、精确shape恢复

现有 `_deploy_g_cash_evidence_empty_rollback_20261023.sql` 保留完整空结构回退；首段新增full-stage元数据硬断言，半迁移先报阶段不符，而不是先引用不存在column。原30的完整回退正文/断言不改。

新增test-scope `GCashEvidenceEmptyRecovery` 与 `_deploy_g_cash_evidence_empty_recovery.sh` 为**受控CI恢复入口**：只接受显式本机 `chengyin_mybatis_g_cash_expand_<UUID>...` 库、autoCommit连接，不可对生产库名/远程host运行；不引入依赖、不读取应用配置/复制凭据。

阶段为0旧形状、1仅close、2再contribution、3再event、4再head ALTER、5再adjustment ALTER。工具：

1. 在同一受控MySQL上创建并记录**本次自己创建**的随机reference schema，以冻结真实base DDL及本候选DDL依次生成0–5阶段标准元数据。reference无业务数据，不把自身任意目标形状当VERIFIED。
2. 精确比较目标5张affected表的table属性、完整列/默认/生成表达式/字符集/注释、全部index定义/可见性、完整CHECK表达式/enforced、FK列序/引用/动作，以及入向FK/trigger。仅归一自身schema名称；同服务器生成的真实元数据避免猜测MySQL CHECK格式。未知列/index/不同CHECK/外来引用即停。
3. **任何目标DDL之前**确认本阶段存在的新证据表均0行；head阶段确认没有cash链接/ACCOUNTING/null会员头；adjustment阶段确认没有cash事件/close链接。只查询该阶段确实存在的列。
4. 严格按依赖逆序撤本阶段已提交前缀：adjustment → head → event → contribution → close；表名/DDL固定，无模糊DROP、无DELETE/TRUNCATE、无target数据库DROP。每一步回到下一个已审前缀；DDL故障会抛异常，不能假称事务全回滚。
5. 恢复后目标完整旧shape与标准stage0一致；reference在成功/异常的finally中只DROP其自己成功创建的UUID schema并读回不存在。旧资金行保留。

这是限定当前冻结旧shape、独占IT库/停止相关写侧时的恢复路径，不是生产恢复授权，也不自动接纳未知旧索引/真实库漂移。未知shape或任何证据不空必须停止、保留账与结构，交root具体审核；不能清空数据换取回退。原 full SQL 是stage5入口，不声称它独自恢复其它阶段。

已编译受控CI中可直接由IT调用；手工受控CI入口需显式 `G_CASH_RECOVERY_CLASSPATH`（已有test/runtime依赖）、`G_CASH_RECOVERY_REPOSITORY`（冻结公共源码根），及 `CHENGYIN_MYBATIS_IT_HOST/PORT/USER/PASSWORD/DB_SUFFIX/RECOVERY_DATABASE`，再运行本批shell入口。密钥不放命令参数、不打印连接凭据。本机未编译/运行该入口。

## 原30+H4+新增11的验证边界

- 原30方法正文与原fixture/helper尾部逐字对比，保持不变；root旧30 green仅按其旧source声明。
- H前三项源码/断言原样采纳；第四项仅恢复调用换专属入口，其真实执行前5生产语句、3表/head前缀、旧列/索引/金额恢复断言保留。
- 新11：正父单原payer/从单及重复；全零父单拒绝；双正额/缺金额父单拒绝；direct SQL的NULL借payer拒绝；全部8 index重名首DDL前挡住；CHECK/FK符号冲突首DDL前挡住；5种auto-committed前缀逐一恢复并可再前向应用；非空close拒绝恢复；未知列/同名但变弱CHECK拒绝恢复；head阶段ACCOUNTING占用拒绝恢复；full SQL遇partial阶段安全拒绝。

实际仅完成静态SQL gate/分句、XML解析、45源码计数、原30/fixture逐字保持、限定对象名清单、patch/密钥文件与逐文件hash扫描。**45项Java编译/MySQL执行未跑**，无Mock/H2或Python金额模拟冒充真库证据；root负责同SHA受控CI和XML/CLEAN读回。

本候选不关闭late SUCCESS/共同fence/挂账后退款全头调整或跨head债务P1；不采用退款分摊/多人固定费政策，不开放VERIFIED/PAYMENT事件、现金dispatch、生产DDL或任何消费者。

原 cash-evidence-expand 交付目录的618历史报告/指纹不重写；本候选当前字节以本目录新source清单为准。阶段恢复采用test-scope工具，不进入生产jar；root应将45项与受控清理receipt同SHA核验，不能沿用原30绿标记。
