# Codex 资金与结算进度

2026-11-05。只改公开仓库的集成候选；未合并、未部署、未接触生产。此报告是中间交接，不能作为上线通过记录。

**恢复后 R2 已重新开放：** 管理者报告已独立验证 da9 actuator／JAR，正常管理员与玩家登录，以及持久 bind 重启后的基线。root 另通过原唯一 MySQL 只读确认 `cy_e2e_51` 的新 UUID `19025e57-d901-11f1-b086-9ae8535e5aec`、457 张表、9 个基线账号和 `/home/ubuntu/previews/codex-play-map-followup-20261105/persistent-mysql` 挂载。原 `990029` 等自定义夹具确已丢失；旧浏览器继续离线／禁用 JS。新 root 前端源 `a47cd65aefc85cf2341ed880c3d60697bb3be7cd`、端口 18096，使用全新浏览器和 `codex-money-20261105-r2-owner` 身份，开始经 UI 重建 `MONEY-CODEX-1105-R2`；未导入旧草稿或重放旧写请求，未重启共享应用／DB。避开 Play 保留的 9008／9004／7002。R2 业务闭环仍待实际走查，恢复就绪不是业务验收。

**R2 当前停止位置：** 主理人／俱乐部和主题 `990018` 均由 UI 创建；新库重新分配 member/club `990005`，数值巧合不代表旧数据恢复。root 已认证 actuator 为 `65a1e33df848c3c225d13b58f8235a54feb2e100`、health=UP，新 UUID 未变。原 a47 前端封面换签名阻塞在刷新为 ce84（含 08c9a00df）并经 UI 手工补填后解除；一次回读网络失败也通过真实“重试”恢复。独立数据库读回两章 PERK、29 元票均已保存。当前发布被“专业主题至少需要一个章节和一个节点”阻断：自由探索的商家节点必须在发布招商后提供。root 正在共享 TopicBundleReviewService 中让零节点前检复用既有 awaitsMerchantGameplay 例外，保留普通专业主题／非招商／空章节的拒绝，未改鉴权、游戏能力或开售资金规则。六类定点单测 67 条零失败／错误／跳过，远端两份源码哈希已与本机核对一致；新 JAR 及修正后的 UI 发布仍待，修正尚未运行在 rig。详见 [R2 记录](proof/codex-money-r2-20261105/README.md)。没有发布、付款、核销或结算 PASS。

业主已明确允许时间夹具：全部内容经 UI 创建后，只能改 root 自有测试行的截止／结束日期，必须标注；生命周期、状态和金额必须由真实 job／endpoint 推进，不许 SQL 设置。目前尚未应用 SQL 日期变更。`a47cd65` 的五类／43 条资金关联单测请求实际返回 `75 / another_build_holds_lock / containerId=null`，未执行，不排队打 JAR，也不启动统一 CI。

**事故记录（恢复前）：** 环境管理者已确认 `2026-11-05 10:00:17Z` MySQL OOM（exit 137）后数据丢失：原 datadir 是 512 MiB cgroup 内的 3 GiB tmpfs，重启后 schemaCount=0、UUID 已变，未找到自有数据 dump。管理者仅替换这个空的原 MySQL 容器，保持名称、固定镜像、23316 端口、512 MiB，改用 VPS 持久 bind；没有第二套 DB，Redis 未动。恢复期间应用保持停止、root 暂停共享 HTTP／SQL，旧浏览器离线并禁用 JS，旧前端 18096 曾停止并确认无监听。root `990029`、K10 旧主题／asset12、Pages 旧 fixture ID 均不再是有效的写入或审批目标；旧 UUID 和以下读回仅为历史证据。root 未重启、重建或恢复数据库，未启动新预算请求；不以事故前证据声明恢复成功。

root 已保存六个编辑器存储项和当前界面草稿到 `/private/tmp/codex-money-recovery-20261105/browser-drafts-offline.json`，并保留原 `/tmp/codex-money-draft-990029-backup.json` 的独立副本；两份文件已复制到持久私有目录 `/Users/arlan/Documents/Codex-Recovery/chengyin-shared-rig-20261105/root-money/` 并逐一校验 SHA256。目录权限 700、文件 600，附清单，未导出登录存储或提交草稿原文。自己的本机与 VPS 任务目录未找到此共享库的完整 SQL dump；源码 migration／测试 seed 不能当成数据库备份。

已收到 K10 保全完成回执：浏览器先离线导出后禁用页面 JS，自有 18098／18102 前端停止，外部 standin 保留运行。root 已校验原 MANIFEST 的 32 项，并将整个 33 文件目录独立复制校验至 `/Users/arlan/Documents/Codex-Recovery/chengyin-shared-rig-20261105/k10-immersive/`，附 `COPY-VERIFICATION.json`；未将快照原文推到公开仓库。K10 当前快照只有 2 章／0 正式节点，早先照片模板／单节点仅余历史截图，并非完整 DB dump；asset12 没有本次审批证明。root 与 K10 快照不能证明数据库恢复，明确重新开放前继续停止共享 HTTP／SQL 写入。

后续 K10 交接 `cbc857d9da1d1d94b8e060f5f4219f9ed61e25cb`／结果 `a47cd65aefc85cf2341ed880c3d60697bb3be7cd` 已读，仍为 PARTIAL，非整单完成。追加清单 36 项全部校验，原 32 项未变；含 MANIFEST 的完整 37 文件另存至 `.../chengyin-shared-rig-20261105/k10-immersive-a47cd65/`，旧持久快照不覆盖。该 lane 报告 JS 111、ledger 5743 通过；之后又报告 65a 的五套相关 Java 零失败／错误／跳过，ce84 后 UI gate PASS。完整泛化分支保存／发布／游玩仍依赖 m1 的真实完成路径，手机验收未完成；未由 root 接管 Pages 或游戏改动。

## 已提交的修复

| 问题 | 改动与范围 | 来源 |
|---|---|---|
| 自有自由探索售出后无法结算 | 自有俱乐部场次按原活动结算入账；必须有合法冻结开售合同，四店旧合同继续走原专用入口。补齐待结算金额查询，保留 14 天规则。 | `528c130c2` |
| 三个开售前置条件互相挡住 | 定价票带上主题时间；自有自由探索补售票截止时间；定价中的自有俱乐部可先开场次，不要求向自己发合作邀请。 | 同上 |
| 平台退款扣回 | 保留云端 `66256f2d` 的平台实际收款人扣回、余额不足记债和重试幂等。多个实际平台收款人时拒绝猜测扣款对象。 | `5dbe613bc` |
| 新核销费 Mapper 被架构门禁拒绝 | 六条现有 SQL 原样归入既有 `SettlementDetailMapper`，调用方同步。没有新增架构豁免。 | `5dbe613bc` |
| 自玩主题结算后人工退款缺原始分项 | 无合作单的俱乐部自玩结算复用既有原始分项凭证；仍只有平台与主理人两条原应付。缺费率快照时先走既有冻结入口；没有放宽凭证与主题费率的一致性校验。 | `5cff928c8`、`6cd4e2572`、`f4245f5b2` |
| 清退退款确认缺后果清单 | 原有退款与移出名册文案进入既有危险确认清单。 | `3deea7cc2` |

发布招商补充修正：`TopicBundleReviewService` 的零节点前检复用既有 `awaitsMerchantGameplay`，让自由探索可先提交招商审核，再由商家创建节点。正控验证零节点快照可提交和审核；负控保留普通专业主题、非招商章节及空章节的拒绝。只改一行生产判断，未调整角色／金额／能力矩阵。

未新增依赖、表或 migration。自玩原始分项修复覆盖新结算；历史已结算且缺原始分项的订单未补账，生产存量也未查询。

## 已取得的证据

- 最新 [VPS 定点收据](proof/codex-money-r2-20261105/unit-summary.json)：六类共 67 条（原资金五类实际 42＋发布审核 25），零失败／错误／跳过。使用正式 budget212、离线 Maven、1 CPU／1.5 GB 总内存，1 GB JVM 堆；未打 JAR、未用共享 DB。包含最新缺快照冻结入口的单测与零节点招商发布／审核正反例。两个修改文件的远端 SHA256 与本机一致。它不是最新真 MySQL 运行或整轮 CI。

- [Funds 真 MySQL 回归](https://github.com/liboyang42-cpu/hengyin/actions/runs/41988658683)：候选 `76d1a3b5c`，739 条，失败／错误／跳过均为 0。包含自有自由探索 2×29 元，平台 1.74、主理人 56.26，14 天前不结算，退款与重复执行不重复扣款。这是该中间候选的证据。
- [自玩原始分项真库回归](https://github.com/liboyang42-cpu/hengyin/actions/runs/41994563030)：候选 `6cd4e2572`，4/4，零跳过，独立清理回读 CLEAN。真实结算服务生成原分项；受控历史打款夹具后，真实退款分项入口扣主理人 9 元，重试不重复扣。不是 HTTP 或真机走查。
- `9f2bee366` 的后端执行记录：四个关联类共 38 条通过（费率收敛 24、原始分项 9、中文字段 2、主题绑定文本安全 3）。后端整体当时仍红，不能当作全量通过。
- 资金修复前两轮 VPS 定点单测分别 75 条、31 条通过；提交钩子编译在测试 VPS 执行，没有在 Mac 运行 Java。
- `a8b142029` 的五类资金关联 VPS 定点请求在测试执行前遇到 `Java heap space`，不能记为通过。保留日志后准备复用 Play 现有脚本的 1 GB 堆／256 MB 元空间／SerialGC 参数；请求实际被 `another_build_holds_lock` 拒绝（75，containerId=null），未启动。helper 的 1.5 GB 总内存、1 CPU、离线模式及互斥锁不变。root 不继续抢锁；预算入口只用于 Java 单测，JAR 不再在 VPS 排队。资金重试脚本在 VPS 的 `codex-launch-fixes-20261105/money-a8b142029/money-focused-1g.sh`；需等当前单测锁释放后安排。
- [GitHub JAR 构建 42006455345](https://github.com/liboyang42-cpu/hengyin/actions/runs/42006455345) 成功，来源 `da9b30f1dd770ce0c4cc55b278e9d08e1ad99a7f`。下载后校验附带 SHA256、SOURCE_SHA、内嵌 git 提交号与 ZIP CRC 均通过，[核验收据](proof/codex-money-20261105/backend-jar-da9-verification.json)。SHA256 为 `f4c1fe89441cf2cba7feb03addf3774c17aed23fe6bff17e6d5d1a02c681f429`，下载目录 `/tmp/codex-launch-backend-jar-da9b30f1dd770ce0c4cc55b278e9d08e1ad99a7f`。artifact 保留 1 天；没有重复触发构建、没有部署、没有执行 Java 测试。产物已交给 Play 这一共享应用管理者；root 未新建后端，也未复制进或重启 Play 容器。运行态 SHA 仍须重启后独立读回，构建成功不等于运行态已更新。
- 真库登记负控 92 条通过；130 个登记类源码预检无漂移。统一真库计划按类去重，不重复执行重叠批次来凑并行数。
- 两个旧走查请求编号的密钥误报只按具体路径和完整原行放行；22 个负控通过，换值、改成凭据字段、追加凭据或换路径仍拒绝。候选历史扫描零发现。

缺快照兼容分支最后一轮改为复用真实费率冻结入口，定点真库执行尚待收据；以上 4/4 不能替代该分支的验证。[较早的定点执行](https://github.com/liboyang42-cpu/hengyin/actions/runs/41999412349)早于 `Date` 编译修正，不能证明最新分支；按协调人要求暂不读 CI 或重启工作流。

## 已处理的已知 CI 红项

- `RegistrationFundsMybatisIntegrationTest` 实际 114 条，真库登记与总数同步；没有删测试。
- 签到时间夹具补既有 `create_time`；注销／迎新夹具显式提供启用的 50 分规则，保留回滚与隐私断言。
- 主题绑定玩法拒绝提示补“题目、一级／二级／三级提示”映射，保留文本拦截断言。
- `PublicVisibilityMyBatisTest` 给手工控制器注入同一真实 SQL 会话的模板 Mapper；后续单测阶段已不再报此错误。
- 客户名册夹具补既有主题／活动 `end_date`（`a8fb71f46`）；完整真实 MySQL 阶段尚待复跑。
- `ThemeRuntimeMySqlIT` 的 `Date` 歧义由 Play 与 root 并行修正（`35b3dc6df`、`abfa99068`、`52a084f76`）。当时后续预算构建已越过编译并执行 285 条，出现 K10 所有的 2 个失败、1 个错误；后续 K10 报告 65a 的修正后五套相关 Java 零失败／错误／跳过，但不代表 root 最新资金分支或全量已通过。

活动统一内容审核只返回汇总裁决，没有可复用的具体字段元数据；已向 Pages 说明，未猜测标题／正文命中位置，也未增加逐字段供应商调用。

## 尚未完成

1. 用定点测试读回最新资金分支与上述测试夹具。真实存储用例的运行准入保持原样，不能把 VPS 冒充 GitHub-hosted runner。
2. [资金 UI 中间记录](proof/codex-money-20261105/README.md)：事故前，独立身份 `990007` 从界面成为主理人并创建俱乐部 `990005`，专业编辑器创建草稿 `990029`。当时共享后端为 `a2285b3dd`，第一章承接配置曾独立读回，第二章和档期仍在本机；上述行当前不保证存在。本机内容已保全，继续走查须先恢复并独立确认数据库、身份和运行版本。事故前 AI 预检的配置缺失按现有规则是非阻断建议，不能算预检通过。尚未开售、付款、核销或结算，未使用 DB 或创建接口替代作者操作。浏览器 rig 不是手机。
3. 等协调人明确通知所有 worker／云 lane 完成，再冻结一个不带 `[skip ci]` 的提交，统一执行全部 CI、Funds、全部登记真库类与 ROUND8。当前停止逐推送启动／监看 CI。
4. DevTools、包体最终点验与真机业务验收尚未执行；未获 DevTools 启动许可。现有其他 lane 的截图不当作本轮新证据。

后续提交使用 `[skip ci]`。共享周限额达到 8% 或以下时，交付可用代码与此报告的停止位置后结束，剩余统一验收交给协调人。

STATUS: PARTIAL - 资金代码与中间回归已提交；最新定点证据、完整资金 UI 闭环和最终同提交验收未完成。
