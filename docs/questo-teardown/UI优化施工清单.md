# 城瘾 UI 优化施工清单（原第2至8项 + 新增玩法 + 商业营销 + 玩家闭环P1–P9）

> **最新核对（2026-11-09 UTC）：** CI250原始2,222份XML合计18,231例，失败/错误0，651跳过不计已验；CI仍被架构consumer检查拦住。受影响真库10类117例在250/a462两个快照分批通过并独立核清理。个人收入9例通过；发布诊断、模板支付依赖和独立可信策略仍在后端收尾，不能写成只等UI。真实支付、Apple Sandbox、设备、现场与生产未验。完整证据见[后端执行结果](后端优化执行结果.md)。

> **交接阅读顺序：** 共用规则与各UI任务 → §23内容/版本合同 → §24玩家闭环P1–P9 → §26合作、收费模板/IAP及OTA → §27本轮增量。本文是唯一UI施工清单；各历史章节的旧SHA与SOURCE/NOT_RUN记录只代表当时状态，当前验证以本文件顶部与[后端执行结果](后端优化执行结果.md)最新结论为准。本轮未修改小程序、iOS或管理后台UI源码。
>
> **已批准的共用决定：** 合作模式复用同一真实团队局的权威判定与共享进度、计时、提示和局内分数；旧免费模板继续免费，新收费版本独立审核、购买许可及私有安装，不能从免费入口取得收费正文；Apple官方服务器库用于验付、通知与退款撤权；OTA先做通用兑换码，具体渠道联调后置。以上源码已有落点，设备、渠道和生产验收仍分别待证，真实收费未启用。
>
> **积分与金额：** 宝石就是现有可兑换积分，与解谜局内分分开；不新增币种或钱包。价格、折扣、应计、退款及权限由服务端决定，UI不本地计算，不把已记录、已批准、应计或待结算显示成到账或可提现。

版本 4.6 · 2026-11-10 UTC · 小程序 / iOS / 管理后台唯一 UI 交付文件；§28 记录 2026-11-09 实施波次；第一批修复已提交 `4eee5c943`，第二批后端源码提交 `c979a233c`，定向回归已通过；存储/CI按统一推送候选执行，未部署。
本文件是全部前端改动的唯一入口；原[iOS优化交接](iOS优化交接.md)按旧稿只作历史，不再承载新任务，也不在交包中重复带入。

## 0 版本增量与证据口径

### 0.1 v1.0 → v3.1 变更

| 变更 | 内容 |
|---|---|
| 标题 | 原「第2至8项」→「原第2至8项 + 新增玩法」 |
| 保留 | 原 31 条 UI 任务 U02-01…U08-05 全部保留，编号、页面范围与接口依赖不删不改语义 |
| 新增 | 第十章 UG-01…UG-12：逐字猜词、图像选项、凶手指认、提前结案、提示阶梯、次数与免费额度、点前代价、使用明细与净分、跳过、回执未知、谜面变换、作者配置与后台 |
| 新增 | 第十一章：上述新玩法的 iOS 改法（合并进本文件，不再分散） |
| 新增 | 第二章共同规则新增证据状态与「不得画入口」条款；第十二章列出未批准能力清单 |
| 修正 | U02-02 原写 `pages/template/index.js` 的 `goTopicConfig`，本基线该符号不存在；真实采用入口为 `pages/templatedetail/topic-detail/index.js:180` 与 `pages/square/utils/topic-template-remix.js:6`（均为 `/api/template/topic-template/use`） |
| 修正 | U07-05 原写 `finance/settlement/index.vue`；真实路径为 `chengyinhub-ui/src/views/business/settlement/index.vue` |
| 增量 v2.1 | 并入 `ui-inputs/G1.md`（猜词四字段与槽位编辑）、`G2.md`（选项图与指认配置）、`T1.md`（主题范本侧限制，新增 10.2）、`R5.md`（无改动，仅引用回执终态定义）；UG-01/02/03 由 PROPOSED 升级为「源码完成待挂载 / 未接线」 |
| 增量 v2.2 | 并入 `ui-inputs/R2.md`（B2 `/use` 增量字段与 error/warn 分支）、`R6.md`（B6 席位字段语义、阻塞码、三项不新增）、`R7.md`（B8 创作者收入应计口径、0 与未知分开、期间左闭右开）；三者均声明本域零 UI 源码改动 |
| 增量 v2.3 | 纠偏：① 取消「竞品类名 grep 到不到 → PROPOSED」的粗判，改为分层状态（SOURCE 候选／后端未验／客户端待实现／App 待验），`ImageSingleChoice` 归原 QA `PICK` 能力，`HelpPoolConfig`/`AppClaimFirstQuestHelpPack` 标 UNKNOWN/待承载；② 并入 `ui-inputs/G4.md`、`G6.md`、`T2.md`、`T2/contract.md` 与 G2 v2（删第二答案源、`requires`、自创留痕枚举、字数阈值），新增 10.4 四类题材装配；③ 补 10.3 内容合同 v1 审查状态（C1 已回、C2 待回，不作已实现、不计工期）；④ 明确 T04 无独立工作位、归 T2；⑤ 创作者收入改中文应计表述，技术 enum 不进用户界面；⑥ 普通问答/排序强制免费重答，作答次数不本地计数 |
| 增量 v2.4 | 按 root 两条纠偏改 10.3：**不采纳** C1 的三条推论（「读/校验 schema = 自研 DSL 不可行」「真源只能由客户端 JS 单方定」「失败反馈不进 schema」）——结构 schema 校验与业务规则引擎分开评估，共同合同须核服务端并保留原业务校验，**作者配置的成功/失败反馈（`outcomeRules.pass/fail` 的 `reply`/`images`）属作者合同、不得删**。范围收敛为「只收本轮新增玩法最小字段合同与对齐 fixture，不重写全量 validator/播放器」。 |
| 增量 v2.5 | 并入 `ui-inputs/G5.md`（提示的额度/代价/重看不扣/重发不重扣）：确立**无「剩余 N 次」计数器**，只有三态 `免费（本层首次）`／`消耗 N 积分`／`已解锁·重看不扣`；`freeQuota` 仅 `0|1`。**该口径已被 v3.0 的整局共享 N 取代，见 UG-06。** |
| **增量 v3.0** | 并入 G0 最终片段（含 UG-07 `helpReceipt`）、V2（提示/指认/猜词最终合同）、T3F（预设草稿）、T3（采用闭环）、M1/M2/M2-F/M3-F3/M4/M5/M6-F/M6-group-verify/M7/M8；按最终源码纠正上述相反结论；新增 §0.4 来源身份、§16–§22 商业营销逐域施工与状态矩阵。 |

### 0.1a 本次复查结果与使用边界

- 已推送批次：[草稿 PR #731](https://github.com/liboyang42-cpu/hengyin/pull/731)，仅原第2–8项；push 与 875 例通过不代表其 MySQL、HTTP、App 或生产通过。
- G-PACK 定稿为 **95 文件**，逐文件 SHA-256 已由主控核对，0 不符；仍待集成与运行。包含跨局 skip 隔离、冻结无 ladder 不回退现模板、旧提示入口避免与阶梯重复计罚的修复。
- M3-F4 **54 文件**已有定稿清单，已修礼品主入口和排行榜准入、覆盖同一订单的多主题行；待领无人可玩、领取后只有领取人，买家财务与退款归属不变。已写入口测试，未执行。
- MG 团体/学校已有商品→真实活动票→原结算的真实 caller 与 21 例测试源码；MP 已补原主题促销在活动报价中的实际可达性、旧 scope 归属校验及限次并发闸；M8F3 已接渠道资金事实与任务租约栅栏，均待运行。
- M402终态候选已含：成交快照席位、独立增购加礼品的无Team分支、verified组证据水位、退款先到的持久冲销任务；主控逐文件402/402哈希匹配，运行仍NOT_RUN。新复查另退视频组成员变化但水位不变时的幂等重算，以及逐投稿计费窗口；不再把已交源码反复列为未写。
- Flash 工作位在 2026-11-08 13:14 UTC 再次返回 `402 Insufficient Balance`。该次开发阻塞已在用户充值后恢复原Flash工作位；恢复不代表上述修复已完成，不据此放宽门禁或将功能画成可用。
- 新 UI 输入已补看 MG、MS、MP 与本轮复查报告；后端未定稿的加座 payload 不写死。下文若保留历史阶段用语，以本节与 §22 最新状态为准。

### 0.2 依据

- **唯一内容依据：** 用户私有 `/Users/arlan/Downloads/chengyin/docs/questo-teardown/施工文档.md` v1.3.2 的 BE-16（提示阶梯与代价）、CT-07（指认结案模板）、M.7（提示与指认模型），以及校准工具 `assets/tools/hint_probe.sh`。原文只读，原始竞品剧本、素材、流量、产物不进入本公开仓，本文件只引用其结构事实。
- 同目录[主题与模板游戏施工文档](主题与模板游戏施工文档.md)仅作结构与能力映射，其建议不覆盖用户原文；缺内容处标待填。
- 公开仓代码基线 `147754ba8740abf208bda75cf86ed5901cac4c02`（Java 21 / Spring Boot 4.1.1）。本文件所有「现有入口」均在该基线上核对过文件路径，未编译、未联调、未真机。

### 0.3 证据状态（每条任务必须标一个）

| 标记 | 含义 |
|---|---|
| **SOURCE** | 公开仓源码入口已存在，本文件写明文件与符号；仅代表代码在，不代表编译/联调/真机通过 |
| **PROPOSED** | 需要后端新增配置、字段或端点，当前代码不存在；前端不得先画成可用入口 |
| **NOT_VERIFIED** | 有源码但未跑过；桌面、联调、真机、实地分别登记，不能互相替代 |
| **NOT_RUN** | 本轮未编译、未单测、未真库、未 HTTP；不得写成 pass |
| **未启用** | 通道/权限/provider 未配置，界面只能显示不可用原因 |
| **不可售/未实现** | 后端 fail-closed 或没有实现；不得画可用入口 |

### 0.4 来源身份与校验（UTC）

- **归并时间：** 2026-11-08（UTC）。**源码基线：** `147754ba8740abf208bda75cf86ed5901cac4c02`（Java 21 / Spring Boot 4.1.1）。
- **唯一 UI 片段目录：** `/private/tmp/questo-parallel-20261107/ui-inputs/`（26 个文件）：G0/G1/G2/G3/G4/G5/G6、M1/M2/M2-F/M4/M5/M6-F/M6-group-verify/M7/M8、R2/R5/R6/R7、T1/T2/T3/T3F、V2、root-help-placement。本文已全部归并；不再存在“待并入片段”。
- **源码/报告依据：** `/private/tmp/questo-parallel-20261107/G-PACK/README.md`（95 文件、`READY_FOR_I_MERGE`、测试 NOT_RUN）、`G0/report.md`、`G0-F2/README.md`、`M0/FINAL-REPORT.md`、`M0/STATUS.md`、`M3/M3-REPORT.md`、`M3-F2/REPORT.md`、`M3-F3/REPORT.md`、`M4/REPORT.md`、`M5/REPORT.md`、`M6/REPORT.md`、`M7/report.md`、`M8/REPORT.md`、`M2F/report-w1.md`、`M2F3/report.md`、`T3F/report.md`。
- **交包校验：** 桌面 `城瘾-App端AI交接包-20261108.zip` 更新前与上一轮 root 产物逐文件 SHA-256 一致（UI 清单 `b8fee8d1…`、先读我 `ece5ab7e…`、内容合同 `718647dd…`、来源与校验 `00494ca8…`；ZIP CRC 无错）。v3.0 重新打包后再次做 CRC 与逐文件 SHA-256 校验，记录在包内 `来源与校验.json`。
- **仍需运行核验的端点：** `/api/marketing/product/*`（M3-F4 已定稿，已付席位快照仍退修）、`/api/marketing/revenue/*`（持久队列/渠道源与租约已接，费用依据缺失时待核）、`/api/channel/redeem`（已接原准入，真实 provider 未配置）、`/api/public-catalog/*`（作者 PENDING、运营审校已写，待真库/HTTP）、`/api/play/help*` 与 `/api/preset/gameplay/*`（G95 待集成验证）、`/api/marketing/promotions/catalog`（MP-F2 定稿待验）、`/api/marketing/group-checkout/{quote,create}`（MG 真实 caller 待验）。加座仍待共享购买分支合入，具体 payload 以修复后冻结版本为准。
- **禁止：** 把 SOURCE 候选写成已可用；把 fixture/schema 文件存在写成运行时已校验；把“SDK 返回成功”写成“已到账”；把 `UNBOUND/UNKNOWN` 写成 0。

## 一 交付范围和现有基础

本轮集中改善模板换城、发布状态、完整游玩、故障恢复、多人协作、交易售后、完局维护，并新增已获认可的玩法帮助与指认结案。暂停第1项的 AI 起草与创作入口优化，既有手动编辑和故事流继续使用。

原 31 项 UI 工作均先保留已有界面，再补差异；「已有」表示源码入口存在，「待接线」需要对应后端合同，「待核验」需要真实界面与接口读回确认。

| 产品范围 | UI任务 | 主要页面 | 本轮后端对应范围 |
|---|---|---|---|
| 2 模板换城市 | U02-01至05 | 模板货架、详情、采用、地点配置 | 采用来源、实例隔离、地点待办及授权摘要 |
| 3 发布明白 | U03-01至04 | 发布检查、结果、我的项目、审核工作台 | 检查定位、状态事实、已售编辑范围 |
| 4 完整游玩 | U04-01至04 | 游玩正文、背包、结局、售前兼容提示 | 冻结故事投影、条件可见性、端能力 |
| 5 故障恢复 | U05-01至04 | 到达、替代、待确认、媒体下载 | 定位拒绝原因、原动作回执、资源身份 |
| 6 多人合作 | U06-01至04 | 入队、邀请、角色、组织工作台 | 成员、席位、角色、职务及权威状态 |
| 7 交易售后 | U07-01至05 | 报名、订单、工坊、退款、财务 | 商品交付、支付事实、许可、退款及冲销 |
| 8 完局维护 | U08-01至05 | 完局、推荐、反馈、创作者、指标与榜单 | 完成事实、维护关联、收益和有效复报 |
| 9 新增玩法帮助 | UG-01至UG-12 | 游玩题面、提示面板、完局与榜单、作者编辑、后台 | 逐字槽位、选项配图、指认结局、提示阶梯与结算明细 |

不新建宝石钱包、独立动态流或通用玩法解释器。商品、通行证、组合优惠、联盟归因、视频悬赏及 host 的商业营销范围按第十六、十七章执行；源码候选、真实通道、端上可用和上线分别验收，未启用的功能不增加空入口。

## 二 所有页面共同遵守的规则

1. 沿用现有深色玩家域、创作域和商家域组件及样式。优先把已有信息排清楚，少加标语、说明文字和重复标签；保留读屏标签、字体缩放、键盘及触控可达性。
2. 公开、审核、销售、支付、退款、权益、核销、结算和实际付款分别显示。按钮是否可执行以服务端事实为准，不能靠一个 status 或前端隐藏按钮代替业务校验。
3. 读取失败、无数据、未知、处理中与确定失败分开显示。不能用 0 元、0 人、0% 或「已完成」填补读取失败。
4. 写入超时保留原动作号，先核对原回执或业务对象。版本冲突先刷新；切账号、换局、换商家后，旧响应不能覆盖当前界面。
5. 新增字段只在后端合同与样例确认后接入。没有接口时可先交互原型，但不能把字段塞进本地 JSON 后记作完成。
6. 作者答案、别名、其他角色私密线索、凭据和付费正文不进入公共卡片、公开预览或埋点。照片默认私人回顾，公开分享需明确动作。
7. 内容修订与运行进度版本分别处理。缓存、正文、媒体与恢复引用同一内容身份；发现新版不自动替换进行中旧局。
8. **状态先于控件。** 每个新按钮出现前必须先有服务端事实（可用性、剩余额度、代价、已用记录）；拿不到事实就不出按钮，不用本地默认值代替。
9. **未知不等于失败。** 任何写入的未知态只能查原回执（同一动作号），不能换新动作号重发，也不能把未知当失败自动降级。
10. **不得为凑界面而补业务。** 后端没有的币种、费率、加成、排名口径、失败规则一律不在界面上造，见第十二章。

## 三 模板换城市

### U02-01 模板详情讲清实际采用条件

- **证据状态：** SOURCE（页面在）／NOT_VERIFIED（未联调）。
- **落点：** 小程序 `pages/template/index.*`、`pages/templatedetail/topic-detail/index.*`、`pages/templatedetail/templatedetail.*`；iOS 既有模板或工坊详情；后台 `chengyinhub-ui/src/views/business/membertemplate/detail.vue`。
- **保留：** 现有主题模板与小游戏模板的对象区别、个人真源与公共货架投影、采用入口和权限。
- **补齐：** 玩家实际做什么、地点要求、人数、支持端、语言、物料、主持或商家依赖、证据等级及许可摘要。来源、试验状态、现场已核验分别说明。
- **接口依赖：** 现有主题模板 `info/list` 及模板详情；地点、能力、许可增量字段以 B2 合同 `contract-v2.md` 为准，未交付前显示「待补充」而不是空标签。
- **验收：** 看完详情能判断本城市能否配置；资料未提供显示待补充，公开摘要不带答案或执行秘密。

### U02-02 采用时选择沿用地点还是换城配置

- **证据状态：** SOURCE（`/use` 调用点已核）／PROPOSED（换城参数）。
- **落点：** 小程序 `pages/templatedetail/topic-detail/index.js:180`（`/api/template/topic-template/use`）、`pages/square/utils/topic-template-remix.js:6`；iOS 对应采用确认。
- **保留：** `POST /api/template/topic-template/use` 复制成自己的草稿后进入现有专业编辑器。
- **补齐：** 明确采用目的（沿用 / 换城）；换城前显示需要重新确认的内容。采用中防重复点击，失败保留选择，未知结果先恢复原意图。
- **接口依赖：** 当前返回 `data.topicId` 对象；换城参数必须兼容旧调用，不能按旧报告误改成标量返回。
- **B2 v2 已交增量（`/use` 回包，作者侧）：** 保留 `data.topicId`，增量返回 `bindingIntent` / `targetCityCode` / `cityCode` / `pendingRebindSlotKeys[]` / `bindingIssues[]{slotKey,code,level,required,message}` / `sourceTemplateTopicId` / `sourceConfigVersion`。旧客户端忽略新键即可。
- **`bindingIssues[].level` 的界面语义（必须照此分支）：** 只有 `error`（显式跨城采用且必填槽未重绑）才呈现为「挡住发布」；`warn`（旧版采用沿用未复核）**只能提示，不得在端上拦发布**。
- **作者专属接口：** `POST /api/template/topic-template/binding`（选点/解绑）与 `/binding-status`（只读合同与缺口）只由作者编辑器消费；**玩家端不展示** `observationNote` / `referenceMediaIds` / `previousBinding` / `confirmedBy`（R2 转述 B2 §1/§3）。
- **验收：** 采用后编辑的是自己的实例，原模板不变；换城不能让原城市事实被误认成本地已核验。

### U02-03 地点待办与现场题重新确认

- **证据状态：** SOURCE（`pages/publish/fabu/index.*` 有地点配置区）／PROPOSED（地点合同投影）。
- **落点：** 小程序 `pages/publish/fabu/index.*` 既有地点配置区；iOS 地点绑定界面；后台只读绑定摘要。
- **补齐：** 每个场景显示「看什么、站哪里、看不到怎么办」；模板要求和本次实际地点分开。换地点后列出受影响的题面、现场答案、素材、开放条件和核验记录。
- **接口依赖：** 后端地点要求、绑定、确认身份和发布检查；POI 坐标仍以原节点为真源。
- **验收：** 甲城和乙城独立保存；在家开场不要求住宅坐标；未重配项目允许存稿但不能显示已就绪。

### U02-04 来源版本与采用更新

- **落点：** 自己的模板或主题详情、采用历史；后台模板详情。

- **证据状态：** PROPOSED（来源血缘增量字段）。
- **补齐：** 显示原模板与采用来源身份；「继续当前版本」「查看新版差异」「另存新草稿」按已支持动作提供。保留自己的修改。
- **接口依赖：** 来源血缘和不可变内容身份；没有历史修订证据的旧数据显示历史来源待核对。
- **验收：** 原作者更新不改自己的在售版和进行中局；更新失败不覆盖旧稿。

### U02-05 素材署名 语言与公开预览

- **证据状态：** SOURCE（素材配置与审核证据页在）／NOT_VERIFIED。
- **落点：** 模板或作品详情、现有素材配置和后台审核证据。
- **补齐：** 素材来源及授权、必要署名；请求语言、实际语言和缺译提示；公开预览只显示允许公开的区域或片段。暂停 AI 起草不等于把既有素材来源伪标为原创。
- **验收：** 换语言不改变选项和节点身份；预览不泄漏完整路线答案；缺授权或缺译提示可定位。

## 四 发布明白

### U03-01 复用发布检查并精确定位

- **证据状态：** SOURCE（`publishCheck.blocking` + `previewIssueWhere(field)` + 服务端列表）；定位字段增量 PROPOSED。
- **落点：** 小程序 `pages/publish/fabu/index.js`（`publishCheck{show,blocking,advisory,summary,passed,preview}`、`_withIssueWhere`、`_buildPublishPreview`）、`pages/publish/utils/publish/publish-validation-bag.js`；iOS `ProjectEditIssueLocation`；后台审核问题列表。
- **保留：** 已有必填与建议分组、问题点击定位、发布确认页。无需另造检查页面。
- **补齐：** 接服务端检查项的章节、节点、块、字段及检查版本；本地检查和服务端检查不一致时提示同步后重查。区分结构通过、试玩通过、实地核验。
- **接口依赖：** `GET /api/topic/publish-check` 继续返回列表，保留 `level/code/nodeId/message`；B3 合同增补章节/块/字段与检查版本，未交付前 `where` 仍按本地 `field` 映射，不伪造章节定位。
- **验收：** 一个错误在顶部、问题列表和提交结果指向同一对象；未跑现场不会显示实地通过。

### U03-02 发布结果明确下一步

- **证据状态：** SOURCE（`_onServerDraftPublished:8231`、`commercialAwaitingReview/commercialNextStep`）／NOT_VERIFIED。
- **落点：** 小程序 `_onServerDraftPublished` 及结果弹层；iOS 发布结果；后台主题概览。
- **补齐：** 分别呈现已公开、审核中、商业审核或定价待完成、可售与不可售原因。现有 `commercialAwaitingReview/commercialNextStep` 应先正确消费；B3 已交付只读 `publish-status` 五段投影（draft/review/visibility/sale/playable），前端按段显示，不从一个 status 推全部。
- **验收：** 已公开审核中不写成已过审；商业待审核不写成已开售；丢回包后读回真实结果，不二次发布。

### U03-03 我的项目保持多条状态一致

- **证据状态：** SOURCE（`subpackageA/pages/myproject/index.js` 已有读取与失败分支）／NOT_VERIFIED。
- **落点：** 小程序 `subpackageA/pages/myproject/index.*`、主题详情；iOS 自己的作品列表。
- **补齐：** 列表、详情、发布结果共用同一事实映射，展示服务端允许动作。已售作品进入编辑前说明可改范围；下架、停售与删除分开。
- **验收：** 草稿、驳回、已公开待审、审核通过但停售、在售和取消各有准确入口，未知值不默认成功。

### U03-04 管理后台审核与模板状态

- **证据状态：** SOURCE（页面在）／NOT_VERIFIED。
- **落点：** `chengyinhub-ui/src/views/business/topic/{index,detail,workspace}.vue`、`views/business/audit/index.vue`、`membertemplate/*`。
- **补齐：** 当前公开版、被审版、原检查证据及差异；核对 `status/checkStatus/userStatus/lifecycle` 实际含义后统一显示。保留原审核任务版本冲突控制。
- **验收：** 同对象跨页状态一致；两人同时审核能读回最终结果；恢复内容生成新修订，不覆盖历史证据。

## 五 完整游玩

### U04-01 同顺序呈现完整故事

- **证据状态：** SOURCE（`chapters[].blocks` 与玩家过滤已存在）／NOT_VERIFIED。
- **落点：** 小程序 `pages/play/index.*` 及现有故事组件；iOS `PlayContracts/PlayExperienceView/PlayJourneyContentViews`。
- **保留：** 后端已有 `chapters[].blocks` 及玩家可见过滤，复用现有渲染器。
- **补齐：** 文字、图片、音频、节点、条件内容及开场按授权投影顺序呈现。iOS 需补运行章节解码与渲染，不只确认作者端能保存。
- **验收：** 同一内容身份两端关键证据与顺序一致；未知必需结构明确阻断；结局未解锁时不提前显示。

### U04-02 选择 证物与结局兑现

- **证据状态：** SOURCE（背包/角色卡/结局区在）／NOT_VERIFIED。
- **落点：** 游玩页、背包（模板 `pages/play/theme-backpack.{wxml,wxss}`，逻辑 `pages/play/utils/theme-backpack.js`）、角色卡及结局区。
- **补齐：** 选择后的已确认后果、证物取得和使用状态、锁定原因与当前可操作项。表达性选择与客观判题分别呈现。
- **验收：** 选 A 与选 B 得到各自可见后果；资源不足不显示成功；同一回执不重复领奖。

### U04-03 购买和开局前识别端能力

- **证据状态：** PROPOSED（B4 已交能力声明读模型，前端未接）。
- **落点：** 作品详情、报名确认、开局入口；后台支持端摘要。
- **补齐：** 服务端确认的可用端、最低能力和已审核替代方式。旧端仍可浏览和游玩其他兼容作品。
- **接口依赖：** `GET /api/play/nodes` 新增 `compatibility{supported,source,missingCapabilities[]}`；`source=UNDECLARED`（老客户端）不当作不支持，`MALFORMED` 也不当支持。
- **验收：** 不支持必需机制时在付款或开局前说明；不能静默删掉关键内容后算完成。

### U04-04 图文音频与阅读恢复

- **证据状态：** SOURCE（故事/音频/图片组件在）／NOT_VERIFIED。
- **落点：** 现有故事、音频和图片组件。
- **补齐：** 大字体、图片放大、同版音频文字、加载和重试状态、阅读位置恢复。没有音频不留空按钮。
- **验收：** 静音、系统打断、慢网和大字体仍能获取必要证据；阅读位置不冒充通关进度。

## 六 故障恢复

### U05-01 到达失败原因与恢复动作

- **证据状态：** SOURCE（50 m/80 m 与拒绝已存在）／PROPOSED（稳定原因码已由 B5 交付，前端未接）。
- **落点：** 小程序定位和到达区、iOS 定位提示。
- **保留：** 后端现有 50 米围栏及精度要求，不把旧报告的兼容提案覆盖当前规则。
- **补齐：** 未授权、缺精度、精度无效、精度不足、未到附近、当前方式不可用分别显示；使用稳定原因码 `errorCode`/`reasonCode`（B5 合同），不通过中文字符串猜状态。`retryable`/`refreshRequired`/`newActionIdRequired` 决定按钮文案与下一步。
- **验收：** 重新定位只重试原意图；精度差不扩大围栏；有商家的扫码事实不能由 GPS 代替。

### U05-02 地点关闭与批准替代

- **证据状态：** SOURCE（玩家现场问题入口在）／PROPOSED（批准替代的下发字段）。
- **落点：** 玩家现场问题入口、组织者运行页、商家站点工作台。
- **补齐：** 暂停原因、允许的备用站或替代证据、联系对象和后续安排。替代动作由原权限和服务端允许项决定。
- **验收：** 无批准替代时不出现无条件跳过领奖；迁点后旧到达凭证不可复用，已完成历史保留。

### U05-03 提交未知与重启恢复

- **证据状态：** SOURCE（`/api/play/action-receipt` 已在 `pages/play/index.js:237,832` 使用；`utils/game-session-client.js` 持久恢复）／NOT_VERIFIED。
- **落点：** 小程序 `utils/game-session-client.js` 及待确认提示；iOS `PlayDurableRecovery` 对应界面。
- **补齐：** 提交中、待核对、成功、确定失败、冲突分开；核对原回执、精确重试和重新同步入口。`receiptState=UNKNOWN/PENDING` 时只显示「待确认」，不显示失败。
- **验收：** 服务端成功而回包丢失，重启后只完成一次；切账号和换局不显示旧动作结果。

### U05-04 下载与有限离线阅读

- **证据状态：** SOURCE（媒体与缓存组件在）／NOT_VERIFIED。
- **落点：** 游玩准备、媒体下载状态和缓存管理。
- **补齐：** 必需与可选资源、大小、已校验状态、可离线范围和最近同步时间；逐资源重试。
- **验收：** 半包不覆盖完整版本，账号私有缓存隔离；离线阅读不显示已完成联网支付、竞争或结算。

## 七 多人合作

### U06-01 邀请 席位与成员分别显示

- **证据状态：** PROPOSED（B6 合同 v2 已交 `seats/invite/viewer`，小程序 `pages/team/detail/index.js` 目前无 seats 字段，未接）。
- **落点：** 小程序 `pages/team/{detail,join}/index.*`、报名页；iOS 队伍和邀请入口。
- **补齐：** 已购席位、预留、已领取、可领取、成员关系及邀请期限；共机人数和独立账号数分别表达。按 B6 合同显示 `seats.capacity/occupied/available/paidOccupied/paidTicketMissing/pendingApplications`、`invite.mode/available/expiresAt/expired`、`viewer.*`。
- **禁止：** 不要自行推算「可占未付款席位」或「预留申请席位」——B6 已删除 `claimable`/`reservedApplications`/`unpaidOccupied`。
- **验收：** 邀请接受不等于已付或已占席；末席只允许一个独立竞争者成功，过期邀请有下一步。
- **字段语义（B6，R6 转述，不得自造同义字段）：** `seats.occupied` = `joined_count`（名额唯一真源）；`seats.paidOccupied` = 已入队且仍持本场次有效票（`payment_status=2` 且 `registration_status=2`，按人去重）；`seats.paidTicketMissing` = 已入队但票已失效（**不是**可占的未付款席位）；`seats.pendingApplications` 只表示未过期待审申请，**申请不占席**；`invite.expiresAt` 只回放 `play_team.expire_time`，不另设到期规则。
- **已撤回、不得在 UI 重新引入：** `seats.claimable`、`seats.reservedApplications`、`seats.unpaidOccupied`、`viewer.memberId`。
- **阻塞码与写路径同名（可直接分支）：** `NOT_SIGNED_IN` / `ALREADY_JOINED` / `APPLY_REJECTED` / `TEAM_FULL` / `TEAM_UNDER_REVIEW` / `APPLY_BLOCKED` / `TICKET_REQUIRED` / `TEAM_NOT_PUBLIC`（仅 apply）/ `APPLY_PENDING`（仅 apply）/ `INVITE_UNAVAILABLE`（仅 join）。`canApply`/`canJoinWithCode` 只是提示，**末席并发仍由服务端 `reserveSlot` 裁决**，UI 不得用布尔替代写回包判定；`/join` 侧按 `INVITE_REVOKED`/`INVITE_EXPIRED`/`TEAM_FULL`/`TICKET_REQUIRED`/`ALREADY_JOINED`/`APPLY_REJECTED` 分支，不解析中文文案。

### U06-02 我的角色与私密线索

- **证据状态：** SOURCE（角色区与投影在）／NOT_VERIFIED。
- **落点：** 游玩角色区、队伍页、iOS 角色视图。
- **补齐：** 我的任务、我能贡献的证据、当前等谁；角色确认与共享动作沿原投影。
- **验收：** 他人角色私密线索不在响应或本地公共缓存中；隐藏按钮不能代替接口拒绝。

### U06-03 队长掉线 退队与接管

- **证据状态：** SOURCE（队伍管理页在）／PROPOSED（拒绝码已由 B6 交付）。
- **落点：** 队伍管理、玩家重入、组织者运行页。
- **补齐：** 队长职务、剧情角色及主持权限分开；离开影响确认、接管结果和重新同步。按 B6 的 `TEAM_FULL`/`TEAM_STATE_CHANGED`/`LEADER_HANDOVER_FAILED`/`APPLY_STATE_CHANGED`/`ALREADY_JOINED` 等错误码分别给文案。
- **本轮不新增（B6/R6 明确，需产品裁定 + 新合同才能进 UI）：** ① 不新增「预留席 / 超时释放 / 转赠」状态（现有源码只有待审申请行，无对应实体）② 不新增队长离线接管入口（`status=2` 局内移交属未裁决规则，只给 `LEADER_HANDOVER_FAILED` 机器码）③ 不提供同队同 `roleCode` 唯一性提示（配置层尚无 `perTeamLimit` 合同）。
- **验收：** 轮询失败不写成队友退出；旧角色不能继续写；原局已确认进度不倒退。

### U06-04 组织者 商家与共同成果

- **证据状态：** SOURCE（俱乐部进度/商家接待页在）／NOT_VERIFIED。
- **落点：** 俱乐部进度、商家接待、团队回顾。
- **补齐：** 当前谁需要处理、章节放行、暂停及共同成果；社会证明注明统计范围和时间窗。
- **验收：** 组织者不能凭切视图操作商家资产；俱乐部总人数不标成某一局参与人数。

## 八 交易售后

### U07-01 商品和报价讲清交付

- **证据状态：** PROPOSED（B7 合同已交 `delivery` + `paymentChannels`，前端未接）。
- **落点：** 小程序 `pages/activity/baoming/baoming.*`、订单详情；iOS 报名和支付确认。
- **补齐：** 自玩票、场次票、团队席位、模板许可、主持服务各自包含什么，谁付款、谁受益、期限、总价与退款规则。按 `delivery.{purchaseKind,ticketName,teamSize,minPeople,maxPeople,needAudit,serviceStartAt,serviceEndAt,refundRule,beneficiaryKind,amountFromServer}` 显示。
- **禁止：** 多票混选时这些字段为 `null`，界面必须显示「按票种分别说明」，不得替整单挑一个票种条款冒充全部交付；`refundRule=null` 时不显示「可退」。
- **验收：** 金额只来自服务端报价；报价过期、售罄、资格不足与渠道不可用分别呈现。

### U07-02 支付返回后核对订单

- **证据状态：** SOURCE（支付返回与订单详情在）／NOT_VERIFIED。
- **落点：** 现有支付返回、订单详情和冷启动恢复。
- **保留：** 小程序 checkout 及 iOS 的 SDK 返回与服务端到账验证分离。
- **补齐：** 查询中、已到账、待确认、已取消、确定失败及恢复入口。按商品和市场显示合法通道，`paymentChannels` 只表示微信通道就绪，不代表 IAP 或商店政策已通过。
- **验收：** SDK 返回成功而订单 pending 时不开放权益；重复回调与刷新不重复下单。

### U07-03 已购工坊安装与正文

- **证据状态：** PROPOSED / **后端 BLOCKED**（B7 核验：公开仓与私有仓对 `workshop` 全量搜索零命中；iOS 合同自身注明 `PAID_INSTALL_AUTHORITY_UNAVAILABLE`）。
- **落点：** 小程序模板详情和已购入口；iOS 既有 `WorkshopPurchased`、`PaidInstall`、`InstalledText` 界面。
- **补齐（待后端真实承载服务确认后）：** 拥有许可、选择安装目标、确认条款、安装中、未知、已安装、待地点绑定分别显示。
- **禁止：** 不得因为客户端已有调用合同就画成可用；免费 metadata 入口不能当付费安装权限。当前状态为不可售/不可安装，界面应显示不可用原因，不做假安装。
- **验收（待后端落地后）：** 已购 A 安装后能读授权正文，B 不可读；安装未知保留原请求号；退款后的新使用依原许可政策。

### U07-04 取消和部分退款的实际影响

- **证据状态：** PROPOSED（B7 `settlementSummary` 已交）。
- **落点：** 订单、取消预览、退款申请和售后进度。
- **补齐：** 可退金额、处理中金额、受影响席位或许可、部分履约和当前处理阶段。按 `refundStage`、`refundPendingAmount/refundProcessingAmount/refundCompletedAmount`、`amountsComplete` 显示；`amountsComplete=false` 时合计不得显示为确定值。
- **验收：** 受理不显示到账；退一席不撤整队；停售不自动显示已退款；渠道结果未知可查。

### U07-05 财务和创作者收入准确显示

- **证据状态：** SOURCE（`chengyinhub-ui/src/views/business/finance/order`、`views/business/finance/refund`、`views/business/settlement/index.vue`、`views/business/finance/ledger.vue` 在）／PROPOSED（B8 收益字段）。
- **落点：** 后台 `chengyinhub-ui/src/views/business/finance/order/*`、`views/business/finance/refund/*`、`views/business/settlement/index.vue`、`views/business/finance/ledger.vue` 及主题资金界面；两端收益明细。
- **补齐：** 成交、履约、退款冲销、待结算、已对账和已支付分开；父单、子票及账本引用可钻取。
- **验收：** 确认对账不写成打款；人工凭证复核不写成用户到账；快速切主体不串数据，读取失败不填 0 元。
- **与 §19 的关系：** 本条的 B8 `CreatorRevenueLedgerVO` 是**内容成交的应计投影**；§19 的 M5 `/api/marketing/revenue/*` 是**营销分配/冲销投影**。两者是不同数据链，**不得相加、不得互相替代**；用户界面按各自入口显示并标口径。
- **创作者收入显示口径（B8 `CreatorRevenueLedgerVO`，R7 转述）：** 用户界面显示中文「**应计**」口径，并展示后端 `revenueBasisNote` 的中文原句（「不等于实收，也不等于可提现；到账与可提现以打款事实为准」）。**技术枚举 `ACCRUAL_ORIGINAL_LEDGER`、`ORIGINAL_LEDGER_RECEIVER_MEMBER` 属实现字段，不得作为界面文案出现**；前端也不要自创英文或改写口径措辞。
- **金额关系：** `net = gross + refunded`（`refundedAmount` 为负值）。**禁止**把 `netAmount` 标成「可提现 / 余额 / 待打款」——本投影不读 `payout_time`，也不看钱包流水；需要「可提现」须走另一条打款事实链（本轮无接口）。
- **0 与未知必须分开：** 读取成功且期间内确实没有分项 → 显示 0（这是可信的 0）；读取失败/未装配 → **不展示任何金额**，显示「收益暂不可用」+ 后端给的中文原因与稍后重试提示，**不得填 0 元、不得写「暂无收入」**。前后端用 `revenueState` 的 `AVAILABLE`/`UNKNOWN` 分支，但该取值同样只作逻辑判断，不直接显示给用户。
- **平台行不展示给个人：** `entries` 只含本人 `receiverMemberId` 的会员行；平台/记账行在 `unattributed`（个人视角为 null），平台财务页需另开只读口径，不得复用创作者中心 `entries` 求和。
- **期间：** `periodStart`/`periodEnd` 是左闭右开（`toExclusive` 不含），账本时区固定 `Asia/Shanghai`；不要改写成「含最后一天」，不要按设备时区重算边界。
- **待定（不得由前端补默认值）：** 收入页默认期间文案（是否写「近 30 天」）、退款冲正是否单列还是只给净额、是否向创作者展示 `calculationVersions`。

## 九 完局维护

### U08-01 正式结果 私人照片与分享

- **证据状态：** SOURCE（完局页、手记、照片回顾在）／NOT_VERIFIED。
- **落点：** 两端完局页、手记和照片回顾。
- **补齐：** 服务端确认的结局、完成方式、奖励与共同完成者；私人回顾与公开分享分开。
- **验收：** 有照片、结束时间或阅读结尾不自动算合法完局；重复打开不重复发奖或公开照片。

### U08-02 下一场推荐与问题反馈

- **证据状态：** SOURCE（`pages/play/finish-route-recommendation.js`）／NOT_VERIFIED。
- **落点：** 小程序 `pages/play/finish-route-recommendation.js` 及完局页；iOS 结局区、既有活动详情。
- **保留：** 原推荐入口和现有详情、报名链，不另建 feed。
- **补齐：** 实际可报名候选、曝光与点击身份、来源局和问题节点。推荐失败仍保留完整结局。
- **验收：** 无候选自然结束；点击不能当报名成功；地点和题目问题能追到原修订。

### U08-03 创作者维护工作台

- **证据状态：** SOURCE（创作者中心 `chengyinhub-ui/src/views/business/creator/index.vue`）／PROPOSED（维护单链路）。
- **落点：** 既有创作者中心、收益组件；后台创作者只读工作台。
- **补齐：** 问题→原内容→修复草稿→复测→新版本→关闭。创作者准入、维护职责和财务查看权限分别使用原规则。
- **验收：** 修复新版本不改变旧局；关闭维护单需要修复证据或明确原因；退出作者身份不删除历史收入。

### U08-04 二次报名看板

- **证据状态：** PROPOSED（B8 已交 `GET /business/analytics/post-game-outcome`，前端未接）。
- **落点：** `chengyinhub-ui/src/views/business/analytics/index.vue`、创作者质量和归因报表。
- **补齐：** 有效报名定义、首报名观察窗、成熟人数、分子分母、水位、免费与付费口径、退款排除及跨端去重。
- **硬要求：** `mature=false` 时留存率显示「未成熟」而不是 0%；`retainedMembers=null` 显示 `—`；`sourceWatermarks`、`basisVersion`、`computedAt` 必显；`CLIENT_OBSERVED_EVENT` 必须标「客户端观测，非最终结果」；`historicalReplayable=false` 需注明为即时计算；`testExclusionBasis=NO_SERVER_MARKER` 不得写成「已排除测试数据」。
- **验收：** 同订单多席不当多次报名；未满观察窗不算未复报；无数据与 0% 分开；点击率与二次报名率分别列。

### U08-05 榜单和公开内容治理

- **证据状态：** SOURCE（成长榜、后台只读统计在）／PROPOSED（新玩法分数入榜）。
- **落点：** 已有榜单、后台只读统计和内容处置面板。
- **补齐：** 实际支持的榜单范围、时间段和分数来源；异常标记的原由；作品可见性、照片同意和下架后已购处理。
- **口径提醒：** 现 `LeaderboardRankPolicy` 分支/个人玩法按「到达结局优先 → 结局总用时短者优先」，线性主题按「完成节点数降序 → 最后完成时间早者优先」，并列同名次（1,1,3）。**榜单当前不读分数**，详见 UG-07 与第十二章。
- **验收：** 未接通的范围不增加空榜；标记不改写原对局事实；私有作品无公开分享路径，下架仍保留合法售后。

## 十 新增玩法 UI（UG 系列）

本章依据 BE-16 / CT-07 / M.7 原文与当前候选源码核对结果。**不得因为竞品类名在城瘾 grep 不到，就判定城瘾底层不具备该能力**——类名是竞品私有实现的命名，能力可能由城瘾既有机制以别的名字承载。每个名字按下表分层给状态，不用一个 PROPOSED 概括。

| 资料里的名字 | 城瘾真实承载 | 状态（分层） |
|---|---|---|
| `ImageSingleChoice`（竞品题型名） | 原 QA `mode=PICK` 判定能力（**SOURCE 基线已有；本轮未核生产**）；选项配图由 `QaOptionMediaSupport` 扩，保存/投影/局内可见性**已接线（G-PACK）** | 能力已有；图片扩展已接线但 NOT_RUN；客户端未实现 |
| `hangman.format`（竞品原数组字段） | `HangmanGameplay` 已接线保存/冻结/投影/`SUBMIT_HANGMAN`/完成闸/矩阵 PROOF（G-PACK）；`format` 是字符串元素数组，`"_"` 为空槽；副本内容不进本仓 | 后端已接线、NOT_RUN；客户端目录/渲染器未同步 |
| `hintLadder`（BE-16 原文配置名） | 源码统一读 `hintLadder`，保存/冻结/读取/整局 N/回执均已接线（G-PACK）；旧 `playHelp{}` 作废 | 已接线、NOT_RUN；接口字段按最终合同消费 |
| `accusation`（CT-07 原文块） | `AccusationTemplateSupport` + `AccusationOutcomePolicy` 已接保存/投影/判定/回执；正确人物只从原 QA `correct` 派生；`recordAs` 自由文本；提前由真实进度判定 | 已接线、NOT_RUN；客户端未实现 |
| `HelpPoolConfig` / `AppClaimFirstQuestHelpPack`（竞品私有配置名） | 城瘾**无同名映射**，也无需新造同名体系；免费额度由整局根标量 `hintFreeUsesPerRun` 承载，层内 `freeQuota` 已退役 | **无同名实现**；不得写成城瘾已有，也不得建同名类 |

### 10.0 现有可复用的两条提示链（先分清用途，不要混用）

| 链 | 位置 | 现状 |
|---|---|---|
| 共享/个人玩法的 `PLAYER_HINT` + `PLAYER_REVEAL` | `GameSessionRuntimeServiceImpl.playerHint:2879`、`GameSessionConfiguration.hintView:534` | 两级提示，按序解锁，`levels[].{level,text,impactLabel}`；下发 `hint{currentLevel,revealedTexts[],nextLevel,nextImpactLabel,revealAvailable}`；未配 `hintPolicy` 抛 `GAME_HINT_UNAVAILABLE` |
| 城市定向谜题提示/揭示/计分 | `PuzzlePlayServiceImpl`、`PuzzleScorePolicy` | `solvedScore(hintLevel)` = 100/70/40，`revealedScore()` = 0；与探索值、可兑换积分分离 |

两条链语义不同：前者是会话动作（有回执、有 revision），后者是谜题记录（影响解谜分）。BE-16 的「阶梯 + 免费额度 + 代价」当前两条都不满足，必须新增而非包装其中一条。

### 10.1 已收合同与片段来源（谁决定什么）

| 来源 | 决定的内容 | 本文件落点 |
|---|---|---|
| 私有 `施工文档.md` v1.3.2 BE-16（769）/ CT-07（429）/ M.7（12739） | 层级、代价、指认块、校准口径的唯一原文 | 全章 |
| `ui-inputs/root-help-placement.md`（用户定稿） | 作者端「结果反馈」区下方配置提示的位置与旧 `hint1/hint2/answerReveal` 归并 | UG-11 |
| `ui-inputs/G3.md` + `G3/contract.md` | `clueTransform{mode}` 三值，服务端下发倒序串，镜像只翻文字层 | UG-10 |
| `G0/contract.md` | 根层挂载 `hangman` / `hintLadder` / `accusation`；`SUBMIT_HANGMAN`；不含 `hintLadder` 下发 | UG-01/03/05 |
| `G4/contract.md` ＋ `ui-inputs/G4.md` | 两个路由、`PlayHelpViewVO{runId,nodeId,enabled,layers[]}` 与 `PlayHelpUseVO`、`enabled=false` 保持旧入口、请求级回执；G-PACK 已把 `SKIP` 独立路由接通（旧片段“SKIP 明确拒绝”作废） | UG-05/06/08/09 |
| `G5/contract.md` ＋ `ui-inputs/G5.md` | 早期每层0|1实现已被用户定稿的整局共享N次取代，见UG-06、重看不扣、只允许 `NONE/POINTS`、扣减落 `ums_member_points_detail` event_type=14、每局每级一次 | UG-06/07 |
| `G6/contract.md` ＋ `ui-inputs/G6.md` | `completionMode(SKIPPED)`、三态结局、第二答案源/留痕枚举已删；`receiptView()` 内部结构已由 G-PACK 的 `helpReceipt` 响应字段取代（以 UG-07 为准） | UG-04/07/08 |
| `ui-inputs/G1.md` + `G1/contract.md` | 猜词作者字段、槽位逐格编辑（`"_"` 为空槽）、payload 三种形状、回执字段；判定类已接线（G-PACK）| UG-01 |
| `ui-inputs/G2.md`（v2）＋ `G2/contract.md`（v2） | 选项图 `img`/`alt` 校验（**无专用字数上限**）；指认 `suspects[]` 与选项一一对应、姓名取选项 `label`；**已删** `accusation.correctSuspectId`、`earlyAccuse.requires`、留痕码枚举与默认值 | UG-02、UG-03、UG-04 |
| `C1/review.md`（只读审查） | 184 行内容合同 v1 的 8 条发现：27 段口径错、schema 不进 jar、字段级定位未成立、能力清单两处产品裁决未决 | 10.3 |
| `ui-inputs/T1.md` + `T1/contract.md` | 采用两入口不串用、`qa.options` 2..4、指认校验与接线状态 | 10.2 |
| `ui-inputs/R5.md` | 无 UI 改动；引用 B5 的终态/未知/刷新/换号权威定义 | UG-09 |
| `ui-inputs/R2.md` | 无 UI 改动；转述 B2 `/use` 增量的 error-only 拦发布与作者专属接口 | U02-02 |
| `ui-inputs/R6.md` | 无 UI 改动；转述 B6 席位字段语义、阻塞码、三项不予新增 | U06-01、U06-03 |
| `ui-inputs/R7.md` | 无 UI 改动；转述 B8 创作者收入应计口径与 0/未知区分 | U07-05 |
| `ui-inputs/T2.md` ＋ `T2/contract.md` | T04/T06/T07/T08 四类**无新题型**，全是 `sort`/`classify`/`qa(PICK)`/`branch`/`timer`/`accusation` 的配置装配；`classify.items` 无配图；普通问答与排序必须免费重答 | 10.4 |

**输入收齐状态（v3.0）：** `ui-inputs/` 的 26 个片段**全部已到并已按主题归并**，包括 G0 最终片段、V2、T3、T3F、M1/M2/M2-F/M4/M5/M6-F/M6-group-verify/M7/M8；**T04 无独立工作位，归 T2**。C1/C2 审查均已回，但**审查不等于实现**。营销片段的相反结论已按最终源码纠正（如 G0 每层免费次数、T3 的 `earlyAccuse` 被拒、M6 作者自报分组决定封顶）；本次复查又发现若干源码缺口，见 §0.1a 与 §22；片段收齐不代表实现已经完整。

### 10.2 主题范本侧界面事实（T1 片段）

这些是「作者端现在真的这样」的事实与限制，不是新视觉方案；适用于 T01 都市谜案 / T02 怪谈追踪 / T03 亲子观察 / T05 城市寻宝。

| 项 | 事实 | 界面要求 |
|---|---|---|
| 采用两个入口 | 整套主题走 `POST /api/template/topic-template/use`，返回 `data.topicId` **对象**；单个小游戏走 `POST /api/template/adopt-draft` → 个人模板 → 节点 `templateId` 绑**个人模板 ID** | 两个入口分开显示；选错时明确提示（整套→新草稿，小游戏→个人模板），不静默降级；**不得把公共库 ID 直接填进节点 `templateId`** |
| 选项数量 | 后端硬限 `qa.options` **2–4** | 编辑时就限制增删按钮；超过 4 个或多选只 1 个正确项，在服务端报错前先本地提示 |
| 单选/多选规则 | 单选必须且只能 1 个正确项；多选至少 2 个且不能全对 | 作者界面按此即时提示 |
| 选项文案与反馈 | 字数上限属**原 QA/sort 字段**（T2 合同 §3.1/§3.3 核对：`sort.prompt`≤200、`qa.title`≤120、`option.label`≤32、`fb`≤120），与 G2 v2 删除的「本类新增阈值」不同；反馈**不提前下发** | 按上列字段各自计数；不要沿用被删除的 `alt/identity/testimony/媒体/结局文字` 阈值 |
| 选项配图 | `QaOptionMediaSupport` 保存校验、公开投影与局内媒体可见性映射**均已接线（G-PACK）**；NOT_RUN | 可放开作者配图编辑（配图必须同时写替代文字）；玩家端不得显示破图或原始 `media-asset:` 引用。见 UG-02 |
| 提示 | H1/H2/揭晓/首字不在模板编辑自建，归 BE-16 阶梯（UG-05/UG-11） | 本清单不定义提示的界面形态，避免第二套提示界面 |
| 指认结案 | `AccusationTemplateSupport` 保存校验/公开投影、`AccusationOutcomePolicy` 判定、局内取证词与提交回执**均已接线（G-PACK）**；测试 NOT_RUN | 作者端可编辑，但 I 回读通过前**不得**给「指认模板已可发布」的绿色状态，显示「待运行验证」。见 UG-03 |
| 指认人数 | 人数 >4 不能塞进一题 | 提示作者拆题/分幕 |
| 排序/配对/分类 | 三种交互共用现有组件，**答案不在客户端** | 不做本地先判对错 |
| 图中找物 `hiddenObject` | 热点坐标不下发，玩家只靠点击；该玩法**不能单独作为完成条件** | 不要把它画成「完成本关」的唯一按钮 |

明确不做（原文显式反对）：不做「到达即解锁一段无关阅读」的 GPS 阅读器；不新增全案总计时（计时只在原 `timer` 支持范围内呈现）；不新建第二套提示/跳过界面；不在玩家端暴露内部字段名（`approvedFallbackNodeIds` 等）；`recordAs` 是作者自由文本，只在作者/审核配置里出现，不展示给玩家。

验收（各端自查）：① 采用整套主题后读 `data.topicId` 拿到新草稿，采用小游戏得到个人模板，两者不串用 ② 选项填 5 个或 0 个正确项时作者界面先给可读提示 ③ 指认提交前抓包看不到 `correct`/结局文案 ④ 排序/配对/分类提交前抓包看不到答案 ⑤ 只配「图中找物」的节点发布被拦，且错误定位到该节点。

### 10.3 内容合同 v1（184 行）审查状态：**未定稿，不作已实现**

`/Users/arlan/Downloads/chengyin/docs/questo-teardown/城瘾内容合同-v1-两端方案.md` 正在只读审查：**C1/C2 均已回，源码实现仍未因审查而完成**。该稿**不是**已实现功能，也**不是**已批准工期。当前范围（root 已定）：**只收本轮新增玩法的最小字段合同与对齐 fixture，不重写全量 validator/播放器**；已有投影白名单、冻结、回执继续复用，真正私有值仍由服务端过滤。

| 已核实的事实 | 对 UI 的直接约束 |
|---|---|
| 「27」是 `GameplaySupportMatrix` 的**结算矩阵段去重数**，不是配置总段数；作者 `SECTIONS` 37 / 服务端 39（读码快照，非测试覆盖率） | 作者端**不得**按「27 段」设计表单或验收；段清单以实际配置真源为准 |
| 发布检查现状只有 `level/code/nodeId/message/chapterId/chapterTitle/nodeName/blockId/field/source`，**无 `kind`/`status`**；`blockId` 是 `template:<id>`/`node:<id>` 合成串，不是稳定 `block.key` | 作者端**不得**承诺「定位到具体字段」；现有定位只到段级，字段级（`blockKey`/`fieldPath`/`kind`/`status`）属**提案**，未实现 |
| `kind`/`status` 若加，`UNVERIFIED`（机器证明不了）**不等于** `warn` | 作者端不得把「未核验」显示成「通过」，两者必须分开 |
| 能力清单：`ContentCapabilityRequirements.compatibility` 已实现（未提交），返回 `supported`/`missingCapabilities`；**无 `availableFallback`**；「未声明」当前是**未确认、不阻断** | 端能力提示可按 `compatibility` 显示「未确认兼容」，**不得**显示「已降级到替代玩法」（无该数据） |
| 合同 L129「缺能力拒开局」与 BE-07「未声明=未确认不阻断」**相反**，需 owner 裁决 | UI **不得**自行按任一侧实现阻断；等裁定 |
| 三视图（作者全量 / 玩家公开投影 / 提交回执）**已实现**，只需标可见性 | 不新开第三套呈现语义；沿用现有白名单 |
| golden fixture 示例里的 `scoreDelta:-15 / 300` 是**竞品样本**，不是城瘾计分 | 任何验收数字不得用该样本当期望值 |
| **作者配置的成功/失败反馈**（`outcomeRules.pass/fail` 的 `reply`/`images` 等）**属于作者合同**，必须随配置一起保存、读回、冻结 | 反馈编辑**不得**因任何「反馈不进 schema」的说法被删除；配置里的反馈内容与运行时「这道题答对/答错」是两个不同事实，不能混为一谈 |

**不采纳的三条推论（root 已明确，勿再写进本文件）：**

1. 「读取/校验 schema = 自研 DSL 或不可行」——**结构 schema 校验与业务规则执行引擎是两件事**。是否让服务端读同一份 schema 做结构校验属可评估方向，不因该推论被否。
2. 「唯一真源仍只能是客户端 JS」——**不是** root 裁定；共同合同须核服务端并保留原业务校验。
3. 「失败反馈不进 schema」——见上表末行，该说法把配置与运行时对错事实混在一起，**不能据此删反馈字段**。

`content-schema.v1.json` 的交付路径（`docs/` 仅 CI 期产物，还是进 `src/main/resources` + loader）**仍待 owner 决定**，本文件不就路径下结论、也不对外宣称「运行时已按 schema 校验」。

**C2 已回**：本地可保留已加载内容和未提交输入；不能承诺断网本地权威推进或自动补传写命令。结果未知保留 requestId 并读回，不切换新请求重扣。未声明能力保持“未确认”，不全局拒绝旧端；已明确缺失的必需能力按该内容处理。`runtimeSnapshotVersion=1` 是格式版本，不能当发布内容身份。schema/fixture 复用现有合同测试，未执行不计通过。

### 10.4 四类题材装配事实（T2 片段：T04/T06/T07/T08）

这四类**没有新增题型**，全部是既有段的配置装配；当前状态 `MAPPING_READY`——人物、剧情、谜底、真实地点、照片均为**待填**，不是成品内容。**T04 归 T2，无独立工作位。**

| 题材 | 复用段 | 作者能改 | 后端会拒的硬限制 |
|---|---|---|---|
| T04 年代追索 | `sort` + `qa(PICK)` | 排序项文案/配图、正确顺序、题干、选项与反馈 | 排序 2–8 项且 `answerOrder` 必须覆盖全部项；问答 2–4 选项、单选恰好 1 个正确 |
| T06 建筑观察 | `sort` + `classify` + `qa(PICK)` | 构件照片、分类名、每项归类、推断题 | 分类 2–4 个类、2–10 个待分类项，`answer` 必须覆盖全部项 |
| T07 原创奇幻节日 | `qa(PICK)` + `branch` + 物品发放 | 题、分支步骤与选项、数字物品 | 分支终点必须有 `outcomeCode` 且存在可达终点；物品必须先在本主题物资里声明 |
| T08 多线索调查 | `qa(PICK)` + `timer` + `accusation` | 线索题、节点级计时、指认人物卡与结局文案 | 计时 10 秒–24 小时；指认人物与选项一一对应 |

界面必须照做的五条：

1. **三层入口不新增第四层。** 整套主题走 `/api/template/topic-template/use`（返回 `data.topicId` 对象）；单个小游戏走 `/api/template/adopt-draft`（返回**新草稿 ID**，与来源模板 ID 不同）。两个 ID 不能串用；同一 `requestId` 重试只回原草稿，**不要**因超时提示「已新建」。
2. **答案不下发。** 排序顺序、分类映射、正确选项、分支跳转都不在公开投影里；作者端**不要**做「本地先判对错」的预览按钮。
3. **配图只在 `sort.items[].img` 与 `qa.options[].img` 可用。** `classify.items` 只有 `{id,label}`，**没有配图字段**——T06 的「图片分类」按文字分类做，或在编辑处标注「分类项暂不支持配图」，不得做出已支持的假象。是否给 `classify` 补图属 schema 决定，未批准。
4. **计时只有节点级。** 按原 `timer` 的 `durationSeconds`（10 秒–24 小时）与 `timeoutResult` 走原失败/继续规则；**不画「整案剩余时间」**，不做全案总时钟。
5. **存稿与可发布分开。** 缺地点绑定（节点无 `locationKey`）时允许存稿、发布闸拒绝；换城必须重绑节点/地点/现场事实/答案/照片/开放时段，只改标题不算已重绑。媒体全部 `NOT_PRODUCED`，不要显示「素材已完成」，也不要把「待填：…」占位当成品给玩家看。

指认部分与 UG-03 同源：正确嫌疑人**只能**来自 `qa.options[].correct`；`suspects` 与选项一一对应、姓名等于选项文案；`recordAs` 是**作者自写文字**，后端原样透传、**不设默认值、不限定枚举**。`earlyAccuse.allowed=true` **可以保存**并由真实进度判定；T3 片段“被保存闸拒”与 T2 旧表述均为过期结论，以最终源码为准。

### UG-01 逐字猜词：按真实 format 补槽与整词输入

- **证据状态（分层）：** 后端 **已接线（G-PACK）/ NOT_RUN**：`HangmanGameplay` 的保存校验、安全投影、开局状态、`SUBMIT_HANGMAN` 动作路由、完成闸、矩阵 PROOF 行均已接；**未编译、未跑测试**。客户端 **待实现**（`pages/publish/utils/publish/node-game-catalog.js` 与 `pages/publish/utils/publish/gameplay-support-matrix.js` 仍无 hangman 行，作者端编辑器/玩家渲染器未同步——这是客户端目录缺口，不是后端 fail-closed）。App **待验**。
- **现有入口（已核，均不等价）：** `pages/play/components/playkit-typein/index.wxml` 自述「目标文本是明牌 —— 这玩法考的是手速不是猜谜」，属性只有 `title/target/seconds/r5/deadlineAt/caseSensitive/tries/attempts/passed`；`playkit-qa` 是整词输入；后端 `playerHint` 只给两级文字提示。三者都不能顶替逐字补槽。
- **改前：** 玩家要么看到完整目标文本照着打，要么在 qa 输入整词；没有「按槽位补空白位」的状态。
- **改后：** 节点内的内嵌玩法块显示槽位行：已给字符**按原样**显示，分隔符原样显示，待补位显示 `_`；玩家**只填空白位**，也可以一次性输入整词（服务端忽略空格比较，自行重建）。解开后显示完整答案。
- **布局和控件：** ① 槽位行（等宽，长词横向滚动，不折行断字）② 逐格输入（点某个空白位就只编这一格）③ 整词输入框（与逐格输入并存，二选一提交）④ 提交按钮。不设「模式开关」，作者侧也没有。
- **操作顺序：** ① 看槽位 → ② 补空白位或直接输入整词 → ③ 提交 → ④ 按回执显示 `CORRECT` / `WRONG`；解开后显示答案。
- **加载/空/失败/未知：** 重进关卡用服务端 `publicProjection` 恢复「已补字符 + 是否解开」，**不在本地存答案或推断**；提交报错（缺槽、越界、与已给槽冲突）如实显示原因并**不清空玩家已填内容**；未知结果先查原回执，不换动作号重发。
- **作者配置（只有五项）：** `enabled` / `answer`（必填，秘密）/ `format[]`（可留空）/ `aliases[]`（可选）/ `caseSensitive`（可选开关）。槽位编辑器按答案字符逐格显示，作者可把某格填成该格字符 = 预填，留空 = 玩家要补，分隔符格自动锁定为原文。**没有**模式开关、次数上限、币种或扣分数值 —— 这些不属于本玩法配置。
- **保存即报（不静默）：** 槽数与答案字符数不等；预填字符与答案同位不符；全部格子都预填；答案只有标点；别名与答案重复或别名之间重复。被拒时保留草稿并**定位到具体格号**；切换题型前提示不兼容字段会被清理。
- **玩家玩法（禁止项）：** 不得本地判对错；不得本地存答案；不得把整份答案下发到客户端再截字符串；未解锁时不出现任何答案片段。
- **管理后台：** 节点配置摘要只读显示「逐字玩法 / 槽数 / 预填数 / 是否解开」，编辑仍在原高级玩法配置面板，后台不改内容。
- **iOS：** 同一 `publicProjection` 契约与同一动作名；槽位渲染可不同，判定必须同源。
- **后端已接线：** `HangmanGameplay` 的 `validate` / `initialState` / `publicProjection` / `judge` / `revealAt`；矩阵 `add("hangman",...,"PROOF")`、types=33；单测源码在包内但 **NOT_RUN**。
- **后端已接线：** 配置段 `hangman`、状态节点 `state.hangman`、动作名 `SUBMIT_HANGMAN`、载荷三种形状（`{slots:{i:ch}}` / `{input:"补空白位"}` / `{input:"整词"}`）、回执字段 `length/blankCount/caseSensitive/solved/slots[]/display/verdict/answerText?`。`answer`/`aliases`/`format` 永不下发。`format` 是**字符串元素数组**，字面量 `"_"` 是空槽（不是“非空=预填”），槽数=答案字符数、至少一个空槽。
- **未定：** 答题次数与答错后果归**共用题级配置**（由 G0 与 G6 定），不在本玩法另造一份；提示层级按钮走 G4 的 `/api/play/help`。
- **验收：** ① 作者填 20 槽给 15 预填能保存并回读，槽数不符立刻报错并定位格号 ② 已给 `E_____S` 时输入 `NDLES` 判对并显示 `ENDLESS` ③ 输入 `NICOLASPERNELLE` 判对 `NICOLAS AND PERNELLE`（服务端重建，不靠客户端补空格）④ 中文与补充平面字符各占一格不错位 ⑤ 重进恢复已补字符与已解开状态 ⑥ 未解锁不出现答案片段，抓包看不到 `answer`/`aliases`/`format` ⑦ 槽位冲突、缺槽、越界有明确错误且不清空输入。

### UG-02 图像选项：加载、替代文字与缺失降级

- **证据状态：** 后端**已接线（G-PACK）/ NOT_RUN**：保存校验与公开投影（`AdvancedGameConfigValidator`、`AdvancedGamePublicProjection`）、局内节点视图媒体可见性映射均已接；**未编译、未跑测试**。客户端：**待实现**（小程序与 iOS 均无选项图渲染）。
- **现有入口（已核）：** `pages/play/components/playkit-qa/index.wxml` 明确注释「选项配置只有 `{id, label, fb, correct}`，pickPlayKit 也只过桥 `{id, t}`，img / audio 全链路没有一处产出」，并说明删除角标的原因是「商家配不了」。全仓 `optionImg/optImg/optionImage` 零命中。
- **改前：** 选项只有文案（反馈文字/图片存在但从不下发）；没有选项配图。
- **改后：** 每个选项加「选项图」+「替代文字」。配置项 `options[].img`（仅 https 地址 / 站内路径 / `media-asset:{id}`）与 `options[].alt`（配图时**必填，非空即可**；**无专用字数上限**，只受整份配置既有字节预算约束）。
- **保存被拒（明确提示）：** 配了图不写替代文字（提示「配了图就必须写替代文字（图片下可读的姓名）」）；写了替代文字没配图；`http:` / `//host` / `data:` / 反斜杠路径。
- **不改：** 正确选项仍是勾选项本身的 `correct`；文案仍是 `label`；反馈文字/图片保持不下发。
- **布局和控件：** 单选/多选沿用现有 `qa__opt` 卡片；有 `img` 时同时显示图与 `label`/`alt`；图片区固定宽高比避免选中态跳动；`aria-label` 以文字为主标签，不把图片当唯一信息。
- **操作顺序：** 看图 → 看文字 → 点图即选中 → 提交该 `optionId` → 服务端原判定 → 原反馈。**图只是呈现，不改判定与重试规则。**
- **加载/空/失败/未知：** 服务端未下发 `img`（图未过审/当前不可见）→ **只显示文字，不显示破图，也不显示原始 `media-asset:` 或外链**；提交仍走原有 `requestId` 幂等与回执恢复；未解锁/未作答时响应中不出现任何 `correct`/`fb`。
- **作者配置：** 每个选项可选图片引用与替代文字，保存后读回原样；发布冻结后改原图不影响旧局。分类/排序题型若同样有图片，按同一规则处理，缺链时先只做文字。
- **玩家玩法：** 判定仍交原 `optionId`，不交客户端 `correct`；不新增图片选择专属判题器。
- **管理后台：** 模板/审核证据显示「选项含图片，共 n 张，替代文字完整/缺失」，并复用同一套保存校验提示。
- **iOS：** 同一节点 JSON 合同；图/替代文字渲染，图片缓存按内容身份，不跨内容复用。
- **后端已交签名：** `QaOptionMediaSupport.validateOptionMedia(JsonNode option)`、`copyOptionMedia(ObjectNode target, JsonNode option)`；投影走 `AdvancedGamePublicProjection` 白名单，不含 `correct`/秘密 `fb`。
- **接线状态：** 作者端可放开配图编辑；玩家端按「未下发 `img` 就只显示文字」降级。后端投影已接，但**未跑**；对外仍不得宣称「图片选项已上线」，需 I 回读确认。
- **验收：** ① 三选项图片题点图提交仍以 `optionId` 判对错，换图不改判定 ② 配图不写替代文字保存被拒，`http:`/`data:` 地址保存被拒 ③ 未过审的图游玩页不出现图与原始引用 ④ 两张相似图可区分 ⑤ 旧文字选项正常 ⑥ 答案标志与秘密反馈不提前下发。

### UG-03 凶手指认：人物卡档案与最终确认

- **证据状态：** 后端**已接线（G-PACK）/ NOT_RUN**：保存校验、公开投影、发布期线索注册表、局内 `clueObtained`、提交回执与三态结局均已接；**未编译、未跑测试**。客户端：**待实现**。作者端在 I 回读通过前仍**不得**显示「指认模板已可发布」，显示「待运行验证」。
- **现有入口（已核）：** 现有图片单选链（`playkit-qa` pick 模式）与结局呈现；`accusation{suspects,introScreens,winScreen,loseScreen,corkboardImage,earlyAccuse}` 与 `suspect`/`corkboard` 全仓零命中。
- **改前：** 只有普通问答，没有人物档案段、没有指认页、没有二次确认。
- **改后：** 配置了 `accusation` 的节点，问答后进入「人物档案 → 选择 → 确认指认」。
- **布局和控件：** 顶部案件板图（有则显示）；中部人物卡列表（头像 + 姓名 + 身份），点卡 = **查看档案**；底部「再想想」「确认指认」。确认用现有 modal 二次确认，文案必须写「这是最终指认」。
- **操作顺序（固定五步）：** ① 打开指认页看人物卡 ② 点卡看档案（只展示**已获得**的证词，后端已过滤，不本地补）③ 选人只是选中，可反复改选、来回看档案 —— **不消耗任何机会** ④ 「确认指认」二次确认后提交，**才计一次正式作答**（次数来自共用题级机会配置）⑤ 结论：正确 → win 结局段；错误且还有机会 → 不剧透的反馈，可继续调查；错误且机会用尽 → lose 结局段；允许提前指认且证据门槛未满足 → 记「提前结案」，**不是失败**。
- **作者配置（G2 v2，与 CT-07 原文逐字对齐，多一个键就被拒）：** `accusation.enabled`；`suspects[]` 与问答选项**一一对应**（同 `id`、`name` 必须等于该选项 `label`），每人 `avatar`（必填图）+ `identity`（可选）+ `testimony[]{key,text}`（`key` 必须是本主题**已登记**的线索键，如 `clue.a`；`text` 非空；同一人物 key 不重复）；`introScreens[]`（数组，单屏内部形状原文未给，原样展示）；`winScreen`/`loseScreen`（**必填**，错误指认必须有独立文案）；`corkboardImage`（图片地址，或 `{img,alt}`，`alt` 可选）；`earlyAccuse{allowed,recordAs}`（`allowed` 布尔；`allowed=true` 时 `recordAs` **必填，由作者自己写结束态记录名**）。
- **正确人物不另存：** 就是问答里 `correct=true` 的那个选项（原 QA 是唯一真源）。**在 `accusation` 段写 `correctSuspectId` 会被当未知字段拒绝**（G2 v2 已删除该键，不再兼容）。作者端**不提供**「正确人物」输入项。
- **保存/发布被拒（典型提示）：** 人物与选项数量不符、「不是问答的选项」、ID 重复、缺姓名、姓名与选项文案不一致、缺头像、缺 `loseScreen`、`allowed=true` 缺 `recordAs`、出现不支持的配置字段（含 `correctSuspectId`、`requires`）。**本项目没有**字数上限校验、也没有留痕码取值枚举校验（G2 v2 已删除）。**人数 >4 时不能塞进一题**，提示作者拆题/分幕（`qa.options` 上限 4）。
- **加载/空/失败/未知：** 提交需带幂等动作身份，网络未知先查原回执，不换身份重扣机会；重登按服务端已记录事实重建（已消耗机会、已获得证词、当前结局段），客户端不自算；档案加载失败显示姓名与身份并隐藏档案按钮。
- **下发边界：** 首页/列表预取面只含人物卡骨架、引导屏、线索板、`earlyAccuse.allowed`；**不含**正确答案、`winScreen`/`loseScreen` 文案、`recordAs`、证据门槛条件。
- **管理后台：** 审核快照需能看到人物卡与 win/lose 文案；审核页显示人物数、是否正确人物已配、是否有提前结案策略。
- **iOS：** 同一节点 JSON 合同；图/替代文字渲染、档案页、二次确认、结局段；**不得本地判对错或计机会**。
- **后端合同与不变量：** `AccusationTemplateSupport`（`validate`/`publicView`/`resultScreen`/`correctSuspectId(qa)`）为**唯一真源**；正确人物只来自 `qa.options[].correct`，`accusation.correctSuspectId` 被当未知字段拒绝。`AccusationOutcomePolicy.judge(...)` 统一算 `verdict/correct/early/canRetry/terminal`；`early` 由真实进度事实派生（`accusationGateSatisfied`），读不到进度则整笔回滚「结果待确认」，不默认 true/false。**运行验证通过前不得标可发布**。UI **不实现**任何留痕码枚举，`recordAs` 只做自由文本。
- **验收：** ① 看档案、反复改选不扣机会 ② 「确认指认」后只扣一次，双击/断网重试不重复扣 ③ 正常指认/提前结案/错误指认三种结局文案与留痕分开 ④ 提前结案不出现成功完局奖励 ⑤ 未获得证词在任何端都拿不到 ⑥ 提交前抓包看不到 `correct` 与结局文案。

### UG-04 提前结案：独立状态而不是失败

- **证据状态：** 后端**已接线（G-PACK）/ NOT_RUN**：提前结案由真实进度派生、读不到即回滚；客户端**待实现**；未编译未验。
- **现有入口：** 无。现有结局只有成功/失败两类呈现。
- **改前：** 提前结束会被显示成失败。
- **改后：** 结局区分三种事实：正常结案、提前结案、错误指认失败。提前结案**不是失败**，也**不是**「成功指认凶手」。
- **布局和控件：** 结局页顶部状态标签显示中文，三种取值固定为「正常结案 / 提前结案 / 指认失败」；颜色与图标沿用现有结局卡样式，不新增视觉体系。
- **操作顺序：** 玩家在作者允许随时指认的策略下确认指认 → 服务端按已获事实判定提前与否 → 进对应结局段。**作者可保存 `earlyAccuse.allowed=true`**（T3 早期片段称“会被保存闸拒”是过期结论）；运行期若进度不可判定，不落 verdict/奖励并提示待确认。
- **加载/空/失败/未知：** 结局事实未知 → 显示「结算确认中」并可回查，不显示任一结局文案；`REFUSED`（未配指认/未知嫌疑人/提前指认未允许）**不消耗机会**，按拒绝原因提示。
- **作者配置：** `earlyAccuse{allowed,recordAs}`。`recordAs` 是**作者自由文本**（结束态记录名），平台**不给候选列表、不给默认值、不限定取值集合**——因此作者端是输入框，不是下拉框。**不得**把 `EARLY_ACCUSATION`/`EARLY_CLOSURE` 等内部码画成用户可见选项。
- **玩家玩法：** 提前结案不发成功奖励、不伪造答对；已完成历史保留。界面只出现作者写的结局文案 + 中性「提前结案」标签，不显示 `recordAs` 原始值。
- **管理后台：** 对局详情显示结局类型（正常/提前/错误）与判定依据；`recordAs` 作为技术字段只读展示，不编辑、不做枚举筛选。
- **iOS：** 同一三态结局页；不新写一套结局模型。
- **验收：** 三态可区分；提前结案与错误指认不混淆；`REFUSED` 不扣机会；重复打开不重复发奖；界面无任何内部枚举码。

### UG-05 提示阶梯：按冻结层列表动态渲染

- **证据状态：** 后端 **SOURCE_COMPLETE（已接线）/ NOT_RUN**。G-PACK 已把 G0/G0-F2/G7/V2 合成 95 文件 `READY_FOR_I_MERGE`：`GET /api/play/help`、`POST /api/play/help/use`、`hintLadder` 保存校验与建局冻结、请求级回执、整局 N、新旧入口互斥、跳过均**源码已接线**；**未编译、未单测、未真库、未 HTTP**。客户端：**待实现**。
- **现有入口（已核）：** `pages/play/index.wxml` 的 `shownHints` 列表、`canHint`/`useHint`/`hintLabel`、`requestPlayerHint`；`pages/play/index.js` 的 `requestPlayerHint` 要求 `availableActions` 含 `PLAYER_HINT`、`level === hint.nextLevel`、`level ∈ {1,2}`、必须有 `nextImpactLabel`；后端旧链强制按一级、二级顺序。
- **改前：** 只有「看提示（1/2）」+「揭示答案」，两级都是文字，没有剩余额度与代价预览。
- **改后：** 按 `GET /api/play/help` 返回的 `layers[]` **动态渲染**，**不硬编码层数**：每行显示形式（文字/图片/首字/答案/跳过）、是否免费、当前代价、是否已解锁。`enabled=false`（`layers` 为空）时**保持现状不动**，继续用旧提示与揭示入口。
- **runId 口径：** 路线局 `runId` = **`topic_route_session.id`**（不是活动/主题 id，也不是 Orca run）；纯高级玩法局由服务端 `resolveAdvanced` 校验 `game_runtime_session` 后使用同一合同，作用域 kind 只由服务端赋值，客户端不能指定。两类局各自独立额度池（`help1:` 与 `help1a:` 前缀），不得互相读到对方的已解锁/剩余次数。
- **布局和控件：** 面板底部弹出（沿用现有浮层），每行「层级名 + 一句话效果 + 代价或免费额度」+ 右侧按钮；已解锁行按钮改「重看」；未轮到的层置灰并标「需先看上一层」。
- **操作顺序：** 进入节点 → GET → 面板按 `layers` 列出 → 选一层 → 二次确认展示 `costPoints` 或「免费」及后果 → POST `/use`（`{runId,nodeId,level,action:"HELP",requestId}`）→ 按 `content` + `type` 渲染（TEXT 文本 / IMAGE 图片 / LETTER/DIGIT 单字符 / ANSWER 答案文案）→ 显示 `chargedPoints` 与 `balanceAfter`（仅在有扣减时提示）。
- **加载/空/失败/未知：** 面板加载失败显示「提示暂不可用」并保留题目；某层无内容则整行不出；提交未知只查原回执并**复用同一 `requestId`**（同局同级只落一次，后到者 `alreadyUnlocked=true`、`chargedPoints=0`）；`usable=false` 按 `reason` 置灰；`POINTS_INSUFFICIENT` 提示「积分不足」且**不渲染任何内容**；401 走既有登录引导；重进重新 GET 拿 `unlocked`，不读本地缓存当真值。
- **作者配置：** 在原模板编辑里配 `hintLadder`（见 UG-11）。层号从 1 连续，作者写几层就几层、顺序即作者顺序；`type` 最终为 `TEXT/IMAGE/LETTER/DIGIT/ANSWER/SKIP`，`LOCATION` 保存期拒绝。`DIGIT` 已纳入统一校验/投影；若服务端返回 `HELP_TYPE_UNSUPPORTED` 仍按不可用显示，不猜内容。
- **玩家玩法：** 只返回本次获准的内容；`ANSWER` 层才给答案，其余层不得出现完整答案；`LETTER/DIGIT` 只发 1 个字符；客户端不拿完整答案切字符串。
- **管理后台：** 节点配置摘要显示可用层与内容是否齐全；对局详情可展开 `helpReceipt`（见 UG-07）。
- **iOS：** 同合同解码，按 `layers` 顺序渲染；**未知 `type` 必须降级为不可用**而不是猜内容。
- **已交接口（G4 contract v-final，G-PACK 已接线）：** `PlayHelpViewVO{runId,nodeId,enabled,layers[{level,type,usable,unlocked,free,costPoints,freeQuota,reason}]}`，**不含任何提示正文或答案**；`PlayHelpUseVO{runId,nodeId,level,type,content,applied,alreadyUnlocked,chargedPoints,balanceAfter}`。
- **不得在 UI 假设：** 配置块名已统一 `hintLadder`（旧 `playHelp{}` 作废）；UI 只消费接口返回的 `layers`，不读配置块名；`freeQuota` 字段对旧局保留，新局以整局 N 为准（UG-06）。
- **验收：** 无 `hintLadder` 的旧主题提示区与今天完全一致；配 2 层只有 2 个按钮且第 2 层在第 1 层前不可用；未解锁不返回答案；已解锁重看不弹扣费确认、余额不变；双击/断网重试同一 intent 只出现一次 `applied=true`；路线局与高级局不共享剩余次数。

### UG-06 次数与免费额度：每局共享 N 次（用户已定稿）

- **作者位置：** 玩法模板「成功反馈 / 失败反馈」下面的「提示」区。整组挂 `advancedConfigJson.hintLadder`；整局免费额度是**与 `hintLadder` 同级的根标量 `hintFreeUsesPerRun`**。模板可提供默认，主题采用后独立编辑；**整局只有一份**，不能每切一题重置。
- **唯一 N（G0-F2 修复后的最终规则）：** 根层、全部 `nodeConfigs[nodeId]`、全部 `nodeAnswers[nodeId]`（含嵌套 `advancedConfigJson` 字符串/对象）里所有**显式**声明必须一致；任意两处不同 → 保存/运行拒绝 `PLAY_HELP_RUN_QUOTA_DECLARED_CONFLICT`。根层不能掩盖节点，直层不能掩盖嵌套；节点未声明只继承，不制造假冲突。
- **缺省：** 无显式 N 但本局出现过非空 `hintLadder` → 整局默认 **0**（新规则不送免费次数）；无 N 且无阶梯（真旧局/旧模板）→ **null**，继续逐层旧语义。空 `hintLadder: []` 不算新阶梯。坏值（负数/小数/超上限）→ `PLAY_HELP_RUN_QUOTA_CONFIG_INVALID`。
- **层内 `freeQuota` 已退役：** 新配置只能留 `0`（老草稿占位）；填 `>0` 保存会报错并提示改填根层 `hintFreeUsesPerRun`。**UI 不再给每层一个免费次数输入**；G0 早期片段写的“每层 0–99”不是最终合同。
- **玩家显示：** 显示服务端返回的「本局免费提示剩余 N 次」（一个整局计数器），不要按层各显示一份。未解锁层在免费额度可用时显示「免费」，用尽后显示作者配置的「消耗 N 积分」；已解锁层显示「重看·免费」。积分是现有可兑换积分，解谜成绩另列，不显示宝石/金币或新余额。
- **扣减规则：** 每层**首次**解锁才可能消耗一次共享免费额度；重看不耗次数、不扣积分。同请求重试不重复扣；余额不足、网络结果未知、事务失败都不能由客户端先减次数或解锁内容。扣减落 `ums_member_points_detail`（`event_type=14` 既有「解锁提示」规则），不改解谜分。
- **并发与未知：** 两节点同时争抢最后一次免费额度，由服务端原子判定；前端只读回真实结果。保留原 `requestId`；同 ID 不能换题/换层/改代价重发。未知结果保持待确认，不当失败生成新请求。
- **费用配置：** 标价为 0 的层是作者明确免费，免费额度用完后仍不收费且**不消耗共享次数**；不能因额度用完擅自封禁。每层费用由作者配置，不预填竞品 2/5/30 等数字；`gems`/`coins` 无账本，保存拒绝，界面不显示这两个输入。
- **错误呈现：** 积分不足显示「积分不足，本层未解锁」；冲突显示「请求内容已变化，请刷新后重试」；待确认显示「结果待确认」；未知或不支持原因显示不可用。内容未解锁前不展示正文/答案。
- **验收：** ①N 次免费跨不同题共享且用尽显示配置价格 ②同层重看免费 ③同 requestId 重发只处理一次 ④跨节点争最后免费额度不会两次免费 ⑤余额不足与后续写失败全部回滚 ⑥改版后旧局剩余次数不被重置 ⑦新旧收费入口不双扣 ⑧积分余额与解谜净分分别展示 ⑨根层 N 与节点 N 冲突被拒，不是根层优先。

### UG-07 使用明细与净分：解谜分与积分严格分列

- **证据状态：** 后端**已接线（G-PACK §六）/ NOT_RUN**。`PlayHelpOutcomeSupport.assemble` 生成 `base/bonus/helpDeduction/skipDeduction/netScore` + `pointsCharged` + 逐层记录；`PlayHelpReceiptReadModel` 只读回放 `play_attempt_log` 的每局每级唯一键事实与 `selectPuzzleHintLevels`；`PlayNodeCompletionApplication#advanceProgress` 完成回包新增 `helpReceipt`；`AdvancedGameRuntimeServiceImpl#toView` 高级局会话视图新增 `helpReceipt`。**未编译、未跑测试。**
- **现有入口（已核）：** 游玩页有「当前得分」`sessStats.score`（`pages/play/index.wxml:182`）与「同行者榜」名次；旧 `PuzzleScorePolicy` 解谜分为 100/70/40/0；`LeaderboardRankPolicy` 分支/个人玩法按结局+用时、线性按节点数与最后完成时间排，**不读分数**。
- **回执接口：**
  - `GET /api/play/help/receipt?runId=&nodeId=`（路线局；`runId` = `topic_route_session.id`）
  - `GET /api/play/help/advanced/receipt?sessionId=&nodeId=`（纯高级玩法局）
  - 完成回包内嵌 `helpReceipt`；高级局会话视图内嵌同一结构。
- **`helpReceipt` 响应字段（只消费这些键）：** `scoreState`（`SCORED`/`NOT_SCORED`）、`baseScore`、`bonus`、`bonusSource`、`helpDeduction`、`skipDeduction`、`netScore`、`pointsCharged`、`skipped`、`assisted`、`reason`（仅 `NOT_SCORED`）、`levels[{level,type,free,pointsCharged}]`。
- **换算与展示：** `netScore = baseScore + bonus − helpDeduction − skipDeduction`；`helpDeduction` 只来自既有 `PuzzleScorePolicy.solvedScore(hintLevel)`（100/70/40），**不是积分**；`pointsCharged` 是积分消费，单独一行「本题解谜分 100，提示消耗积分 20」。**禁止**把积分写进 `netScore`，也禁止把 `netScore` 标成积分余额。
- **`NOT_SCORED`（无权威基准：纯高级玩法局、自由收集、非 1/3 验真方式）：** 显示「本题不计分」，`baseScore`/`netScore` 为 `null`，**不得显示 0 分**；`pointsCharged`、`levels`、`skipped`/`assisted` 照常显示。`reason` 例如 `NO_BASE_SCORE` 只作逻辑判断，不直接当文案。
- **跳过/揭示文案：** `skipped=true` → 「本题跳过 · 记 0 分 · 辅助完成」；`assisted=true` 且 `skipped=false` → 「揭示答案，未自行解出」。
- **布局和控件：** 完局页成绩卡内「分数明细」折叠区，每行「题号 / 事件 / 分值变化」；提示面板提交后就地显示本次积分扣减行；积分与解谜分用不同单位标签。
- **加载/空/失败/未知：** 明细缺失 → 只显示净分与「明细待补」，不补 0 行；规则版本缺失 → 不承诺可跨局比较；`helpReceipt` 读取失败 → 「结算明细暂不可用」，不自行拼算。
- **作者配置：** 本题是否计分、提示是否降低本题得分、跳过是否记 0。**不提供**「是否影响榜单资格」——榜单不读分。
- **玩家玩法：** 只显示服务端已结算事实；不允许客户端算分或改分。
- **管理后台：** 对局详情可展开同一份明细，用于申诉核对。
- **iOS：** 同明细结构，`scoreState` 决定是否显示分数；字段以后端为准。
- **榜单边界（必须照此写文案）：** `LeaderboardRankPolicy` **不读分数**，`MemberLeaderboardMapper` 只有积分/成长四张榜，也不读 `puzzle_score`；`puzzle_score` 唯一消费点是个人最佳。界面**只描述本局分数与是否辅助完成**，**不得**宣称「提示会掉全站排名」「净分决定名次」。全站榜口径变更未授权。
- **验收：** 有阶梯的节点完成回包与重进只读回执的 `netScore`/`pointsCharged` 完全一致；同一 `requestId` 重试 `levels` 不增加；跳过 `skipped=true`、`skipDeduction=baseScore`、`netScore=0`、`assisted=true`；高级局 `scoreState=NOT_SCORED`、`netScore=null` 但有 `levels`/`pointsCharged`；任一界面都不出现英文枚举或字段名。

### UG-08 跳过：0 分、辅助完成标记、不伪造成功

- **证据状态：** 后端 **SOURCE_COMPLETE（已接线）/ NOT_RUN**。G0 已交 `POST /api/play/help/skip` → 运行时跳过事实 → 既有完成链写 0 分与 `SKIPPED`；G-PACK 已并入候选。**未编译、事务回归与 HTTP 均未验证。** 客户端待实现；通过验证并收到服务端可用能力前不展示按钮。
- **现有入口（已核）：** `pages/play/index.js` 依据 `status === 'FALLBACK_COMPLETED' && completionSource === 'PLAYER_REVEAL'` 显示 `revealedAnswer` —— 那是「揭示答案」，**不是**跳过，也没有 0 分与辅助完成语义。
- **改前：** 只有「揭示答案」一种退化路径，显示成揭晓文本。
- **准入（服务端三条全中才允许，少一条即拒）：** ① 冻结 `hintLadder` 里确有 `SKIP` 层；② 节点不是现实凭证类（`scan/photoCheck/steps/album/predict` 任一启用即拒）；③ 节点模板验真方式不是扫码(4)或现场到达(5)。UI 只按服务端下发的可用性显示按钮，不自行判断。
- **改后：** 区分三种事实并分别显示：① 自己解出 ② 用了提示 ③ 跳过。跳过时本题记 0、打辅助完成标记、按作者配置给替代线索或后续；**不得**显示「答对」「成功指认」「已到达」「已扫码」。
- **布局和控件：** 跳过按钮**只在**服务端下发可用时出现；确认弹层写明「本题记 0 分，并标记为辅助完成」；跳过后的题目卡片显示「已跳过」标签。
- **操作顺序：** 点跳过 → 确认（不可逆）→ 服务端结算 → 显示替代线索/后续 → 完局明细出现跳过行。
- **加载/空/失败/未知：** 替代线索未配置/节点类型不允许 → 不提供跳过按钮（发布期就该拦住或运行期拒绝并给原因）；跳过提交未知 → 查原回执，**不换号重发**；同一 `requestId` 重发只回放，已跳过换号再跳也不重复结算。
- **作者配置：** 是否允许跳过（即是否配置 `SKIP` 层）、跳过是否揭晓、替代线索/后续。运行验证前作者端标「待验证」，不得把保存成功当玩家已可用。
- **玩家玩法：** 队伍奖励与终局不因跳过重复触发；跳过不制造答对、到达、扫码或支付证明。
- **管理后台：** 审核显示该题是否允许跳过、跳过后续是否可达。
- **iOS：** 同确认文案与标签；未通过运行验证前不显示按钮。
- **后端已交口径：** 跳过事实落 `play_attempt_log`（`attempt_type=1`、`result=2` 放弃）+ `puzzleScore=0` + `completionMode=SKIPPED`；`advanceProgress` 强制 `rewardPolicy=XP_BADGE_ONLY`、`advancedOutcomeCode=null`；`SKIPPED` 与 `EARLY_ACCUSATION` 是两个不同事实，不共用结束态；`PuzzleScorePolicy.COMPLETION_SKIPPED` 与 `SOLVED`/`REVEALED` 分开。
- **已知风险：** 任何按 `SOLVED/REVEALED` 白名单判断的读取方需确认不会把 `SKIPPED` 当「已完成」而挡住后续。
- **验收：** 跳过记 0 且明细一致；无成功文案与答对动画；重复提交不重复结算；辅助完成标记可见；现实凭证节点没有跳过按钮。

### UG-09 网络未知：只查原回执

- **证据状态：** SOURCE（回执机制已存在）／已接线（新动作与回执读口已接，NOT_RUN）。
- **现有入口（已核）：** `pages/play/index.js:237,832` 已使用 `GET /api/play/action-receipt`；`utils/game-session-client.js` 负责持久恢复；后端先查回执再校验（B5 合同 §6）。
- **改前：** 提示/揭示走会话命令，有回执；新玩法的提交路径尚无对应处理。
- **改后：** 所有新玩法提交（猜词、提示使用、指认、跳过）沿用同一套：保留原动作号 → 未知时先按原号查回执 → 回执未结算显示「待确认」→ 只有服务端明确失败才允许新动作号。
- **布局和控件：** 待确认态用现有 `gameModule.write.status === 'unknown'` 呈现（`pages/play/index.js` 已有该分支），提供「继续核对」按钮，不给「重试提交」。
- **操作顺序：** 提交 → 超时/断网 → 「待确认」→ 查原回执 → 得到结果或继续等待。
- **加载/空/失败/未知：** 查不到回执 → 仍是未知，不是失败；换账号/换局 → 旧回执结果不得覆盖当前界面。
- **作者配置：** 无（属运行时机制）。
- **玩家玩法：** 双击与断网重发只消费一次。
- **管理后台：** 无。**iOS：** `PlayDurableRecovery` 已有该模型，新动作接同一路径。
- **验收：** 双击提示只扣一次；断网重发后重登一致；未知不降级为失败。
- **幂等字段（G4 v-final）：** `POST /api/play/help/use` body `{runId,nodeId,level,action?:"HELP",requestId}` —— `requestId` **必填**；跳过走独立路由 `POST /api/play/help/skip {runId,nodeId,requestId}`，不把 `/use` 的 HELP 请求改成 SKIP。同 `requestId`/同局同级重放只回原结果，后到者 `alreadyUnlocked=true`、`chargedPoints=0`，内容照发；事实键被异身份/异代价占用则冲突、不返回内容。返回 `PlayHelpUseVO` **没有** `replayed` 字段，用 `applied`/`alreadyUnlocked` 判断。扣款失败整事务回滚、事实行不落。
- **只读回执：** `GET /api/play/help/receipt?runId=&nodeId=`、`GET /api/play/help/advanced/receipt?sessionId=&nodeId=`；与完成回包 `helpReceipt` 同一数值，重进/重试不增加 `levels`。
- **终态/未知权威定义（B5 `PlayCommandErrorContract`）：** 终态 = 回包含 `intentTerminal=true`，或同时含 `newActionIdRequired=true`；未知 = HTTP 200 但既无 `intentTerminal` 也无 `intentNotExecuted`，或 `520` + `receiptState ∈ {PENDING, UNKNOWN}`；必须先刷新 = `refreshRequired=true`；必须换动作号 = `newActionIdRequired=true`；可原样重发 = `retryable=true`。

### UG-10 谜面变换：镜像与倒序的有限呈现

- **证据状态：** SOURCE（G3 已交两侧合同：`mode` 三值、保存/发布拒绝 `CYPHER`/`FLIP`）／NOT_VERIFIED（未编译未联调）。
- **现有入口（已核）：** 无对应呈现；题面走现有 blocks/题干媒体渲染。作者配置位在节点玩法模板 `advancedConfigJson` 根层，与 `present`/`qa` 同级。
- **字段：** 只有 `clueTransform:{"mode":"STANDARD"|"MIRROR"|"REVERSE"}`，缺省 = `STANDARD`。**不接受 shift/密钥/脚本等多余键，多余键报错**；`CYPHER`/`FLIP` 在保存与发布期直接报错，作者端不出这两个选项。
- **改前：** 题面只有原文，呈现固定。
- **改后：** 作者三选一：`原样`（不写或 `STANDARD`）、`镜像`（`MIRROR`）、`倒序`（`REVERSE`）。玩家侧服务端已给 `question`（**已是变换后的文字**）与 `clueTransform.mode`（**仅非 STANDARD 时存在**）。
- **布局和控件：** 作者端在题面卡片下加一行「谜面呈现」三选一；**作者端始终显示原始题面**，编辑态不得把原文替换成倒序串。玩家端 `REVERSE` 原样渲染服务端文字（不二次反转）；`MIRROR` 只把**题面文字层**水平翻转（小程序 `transform: scaleX(-1)`；iOS `layer.transform = CGAffineTransform(scaleX: -1, y: 1)`）。
- **禁止：** 不翻转输入框、选项、按钮、完成按钮、提示入口；不翻转整页；不用预制镜像图片替代。
- **操作顺序：** 作者选模式 → 保存进同一份 `advancedConfigJson`（沿用原序列化，不新增接口）→ 读回显示同一选项 → 玩家读题面 → 作答 → 原判定。
- **加载/空/失败/未知：** 题面为空却选了镜像/倒序 → 保存或预检报错并定位到题面字段；读回缺失 → 显示「原样」；未知 `mode` → 原样显示文字并上报，不猜变换；旧客户端无该键 → 呈现降级（看到倒序串或未翻转文字），**不影响作答与判定，不得因此改判定或放行**。
- **无障碍：** `MIRROR` 文字内容不变，读屏正常；`REVERSE` 会读出倒序串，**不建议**给读屏额外传原文（等于免费提示），是否提供归 UG-05 的提示规则决定。
- **玩家预览：** 复用现有预览链，按玩家侧同样规则渲染；预览只读，不产生作答事实、不发完成证明、不扣次数。
- **iOS：** 同一字段、同一个 `scaleX` 翻转；与小程序看到同一串文字。
- **验收：** 不配置或 `STANDARD` 时响应与现在逐字节一致（无 `clueTransform` 键）；旧局/旧主题不受影响；倒序后仍能用原答案答对；未解锁提示、答案、`correct` 旗标不出现在响应；`MIRROR` 不用任何预制镜像图片交付。

### UG-11 作者配置面板：提示配置挂在「结果反馈」区下方

- **证据状态：** 位置与既有字段 **SOURCE**；新提示配置的保存校验/冻结/读回 **已接线（G-PACK）/ NOT_RUN**；客户端**待实现**。
- **现有入口（已核，用户定稿位置）：** `pages/publish/temp/index.wxml` 的「结果反馈」区（约 1162 行）；成功/失败反馈由 `r5Editor.fbRows` 渲染，内容存 `outcomeRules.pass/fail` 的 `reply/images`；**同一区域上方已有旧提示输入 `formData.hint1`、`hint2`、`answerReveal`**。保存走 `pages/publish/temp/index.js` 的原高级配置序列化/读回，写 `CmsMemberTemplate.advancedConfigJson`。
- **改前：** 提示是反馈区上方三个零散输入（hint1/hint2/answerReveal），与反馈动作混在一屏，没有类型与代价概念。
- **改后：** 保留成功反馈、失败反馈不动；在其**下方**新增「提示」配置组：先选提示类型，再配置该类型的内容与规则。可选类型为 `TEXT/IMAGE/LETTER/DIGIT/ANSWER/SKIP`（`LOCATION` 保存期拒绝，不列）；**不要求每题固定六级**；不做独立提示内容库。旧 `hint1/hint2/answerReveal` **并入这个新位置**，避免上下两份提示表单；旧字段只作兼容展示，不能与新阶梯同时生效。
- **布局和控件：** 顺序为「成功反馈 / 失败反馈 / 提示」。提示组内是分层列表（可增删），每层一个折叠块：类型 → 内容 → 使用代价（积分）；**整局免费额度在分组顶部一个输入框**（`hintFreeUsesPerRun`），不在每层放次数。顶部一个「本题是否启用提示」总开关。底部「预览玩家看到的样子」。每层 `freeQuota` 只能为 0；填 >0 保存报错并指向根层。
- **操作顺序：** 开关 → 加层 → 选类型 → 填内容 → 设整局免费额度与该层积分代价 → 保存 → 读回 → 发布检查。UI 上的上下顺序**不表示**把提示塞进成功/失败结果动作；JSON 挂载由 G0 核定。
- **配置块名（不得在界面暴露）：** 已统一 `hintLadder`，旧 `playHelp{}` 草稿作废。作者端**不显示** JSON 段名，只显示「提示」；保存根键为 `hintLadder`，整局额度为根标量 `hintFreeUsesPerRun`，不在界面暴露内部字段名。
- **作答机会不得自造：** 普通问答与排序**必须免费重答**（`qa.maxTries=0`、`sort.maxAttempts=0`）；作者填 1 会被**发布闸直接拒**。因此本版**不给**「有限答题次数」开关，UG-06 的「剩余作答 n 次」只在后端确实下发该字段时才显示（当前不下发即不显示）。有限次数属另一套需审核的规则，未批准前不画入口。
- **加载/空/失败/未知：** 保存失败保留输入；读回缺字段标「未保存」；题面或该层内容为空 → 保存/预检报错并定位到该层该字段；`GEMS`/`COINS`/`RANK_PENALTY_TIME` 这类代价**显示「不可配置（无真实账本）」而不是留空输入框**；不填平台不存在的币种或默认费率。
- **玩家端：** 读取本局已冻结的可用项/剩余次数/实际代价，使用由服务端授权与扣减，收到回执后展示；未知结果查原动作，不重复计次或扣减（见 UG-09）。
- **管理后台与 iOS：** 同一模板的同一帮助配置与状态映射，只加原配置区字段与消费界面，不另设计字段或经济规则，不重做视觉体系。
- **验收：** 旧 H1/H2/答案不丢；所选类型保存读回一致；采用后同配置；新发布不改旧局；未解锁不泄答案；同次重发不重复扣；原成功/失败反馈不被提示配置覆盖。

### UG-12 管理后台：审核、额度与明细只读

- **证据状态：** PROPOSED。
- **落点：** `chengyinhub-ui/src/views/business/topic/detail.vue`、`workspace.vue`、`views/business/audit/index.vue`；`business/analytics/index.vue`（复报，见 U08-04）。
- **补齐：** ① 该题/该结局的玩法配置摘要（层、额度、代价、跳过策略）② 审核时的答案与秘密字段隔离（审核可见，运营只读不可导出）③ 对局明细展开（提示/跳过/净分逐笔）④ 申诉核对所需的回执号与原动作。
- **禁止：** 后台不得改写原对局事实、不得代玩家使用提示、不得绕过 owner/scope 校验。
- **验收：** 审核看到的是被审版本；运营只读不可导出答案；明细与玩家侧一致；两人同时操作有版本冲突提示。

## 十一 iOS 改法（合并进本文件）

原[iOS优化交接](iOS优化交接.md)的 APP-01…APP-10 保留为历史入口，不再新增任务。以下为新增玩法与上述增量的 iOS 落点，App 负责人按此实施；后端字段以实际交付提交为准，不照抄旧报告。

| 项 | iOS 落点 | 改法 | 依赖状态 |
|---|---|---|---|
| UG-01 逐字猜词 | `App/PlayJourneyContentViews.swift`、`Core/PlayExperienceContracts.swift` | 新增槽位视图 + 键盘；`"_"` 为空槽；整词输入交服务端重建；客户端不切答案字符串 | 后端已接线；NOT_RUN；客户端目录/渲染器未同步 |
| UG-02 图像选项 | `App/PlayJourneyContentViews.swift`（QA 选项渲染） | 选项加图 + 图下文字，加载失败回文字等价；判定仍交 optionId | PROPOSED |
| UG-03 凶手指认 | `App/PlayJourneyContentViews.swift` + 结局区 | 人物卡横滑队列、可展开档案、底部「确认指认」二次确认 | PROPOSED |
| UG-04 提前结案 | 结局区 | 三态结局标签（正常/提前/错误），不新增结局模型 | PROPOSED |
| UG-05/06 提示面板 | `App/SessionPlayRuntimeView.swift`、`Core/PlayGameSessionRuntime.swift` | 面板按后端层列表渲染；顶部一个「本局剩余免费 N 次」；按钮三态（免费/代价/不可用）；重看免费 | 后端已接线；NOT_RUN；客户端待实现 |
| UG-07 分数明细 | 完局页、结局区 | 折叠明细：`scoreState` + base/加成/提示/跳过/net + `pointsCharged`；NOT_SCORED 不显示 0 分 | 后端已接线；NOT_RUN；客户端待实现 |
| UG-08 跳过 | 完局页与题目卡 | 只在服务端下发可用时显示；「已跳过」标签 + 「本题记 0 分并标辅助完成」确认文案；无成功动画/奖励 | 后端已接线；NOT_RUN；客户端待实现 |
| UG-09 回执未知 | `Core/PlayDurableRecovery.swift` | 沿用既有持久恢复，新动作接同一路径 | SOURCE（机制） |
| UG-10 谜面变换 | 题面渲染 | 呈现变换，判定不变；缺配置显示原文并标注 | PROPOSED |
| U03-02 发布状态 | `Core/ProjectEditIssueLocation.swift` + 发布结果页 | 消费 `publish-status` 五段投影 | PROPOSED |
| U04-03 端能力 | `Core/PlayContracts.swift` | 消费 `compatibility`，`UNDECLARED` 不当不支持 | PROPOSED |
| U06-01 席位 | 队伍与邀请页 | 消费 `seats/invite/viewer`，不推算可占席位 | PROPOSED |
| U07-01 交付 | `App/OrderLifecycleView.swift` | 消费 `delivery`，多票混选显示「按票种分别说明」 | PROPOSED |
| U07-03 已购工坊 | `Core/WorkshopPaidInstall*` | **当前不可用**，显示不可用原因 | BLOCKED |
| U08-04 复报 | 分析适配入口 | `mature=false` 显示未成熟，不显示 0% | PROPOSED |

| 商业营销（商品/促销/视频/小费/目录/渠道） | 见 §16–§20 | 只消费服务端目录/报价/应计/状态；价格、资格、剩余时限、应计、渠道可用性不在本地计算 | 各域见 §22 状态矩阵；多数 NOT_RUN / 未启用 |

约束：不因客户端已有调用合同就认定后端可用；不把 fixture、模拟器或另一端的通过当 iOS 通过；真机、支付沙盒、实地分别签收。

**本表状态按五层读：** ① **SOURCE 候选**＝后端类已写、未接线；② **已接线**＝有真实 caller（仍不等于验证）；③ **NOT_RUN**＝未编译/未单测/未真库/未 HTTP；④ **客户端待实现**＝小程序与 iOS 都还没改；⑤ **App 待验**＝真机/支付沙盒/实地未做。表内 `PROPOSED` 只表示「后端字段或合同尚不存在或未统一」，**不代表**已可用；`BLOCKED` 表示后端承载服务未找到，端上只能显示不可用原因。

## 十二 未批准能力：不得画成已可用入口

下表为资料里的竞品观测、过期片段或本文件外的建议。凡城瘾没有实现、未启用或未批准的，界面上不得出现可用入口、数值或承诺。

| 能力/旧结论 | 资料出处 | 城瘾实际（最终源码口径） | 处置 |
|---|---|---|---|
| L6 位置类提示（`exRadius` 扩半径 / `unlockLocation` 解锁 / `map` / `revealPicture`） | M.7 道具枚举 | 无实现；扩半径等于放宽到达规则，须另行批准；`hintLadder.type=LOCATION` 保存期拒绝 | 不出现按钮；不写进提示面板 |
| 暂停扣 15 XP/分钟 | M.7 代价 | 无实现 | 不显示、不承诺 |
| 2 宝石/提示、5 宝石/跳过、1 金币 | M.7 代价、G0 早期片段 | 无宝石/金币账本；`cost.gems`/`cost.coins` 非零保存拒绝；宝石只作为现有积分的说法，不新增 GEMS 币种 | 币种不可配置、界面不显示 |
| 每层免费次数 `freeQuota:0–99` | G0 早期片段 | **已过期**：最终为整局共享 `hintFreeUsesPerRun`；层内 `freeQuota>0` 保存拒绝 | 不提供每层次数输入；见 UG-06 |
| `action=SKIP` 被拒 / 跳过未接线 | G4/G5 早期片段 | **已过期**：`POST /api/play/help/skip` 已接线，0 分 + `SKIPPED` + 辅助完成；NOT_RUN | 按服务端可用性显示；不得标 pass |
| `early` 恒 false / `earlyAccuse.allowed=true` 被保存闸拒 | G0/T3 早期片段 | **已过期**：`allowed=true` 可保存，`recordAs` 为作者自由文本；运行期按真实进度判定，读不到进度则整笔回滚「结果待确认」 | 作者端提供开关与自由文本；玩家端不推断「提前」 |
| 留痕码枚举 `EARLY_ACCUSATION`/`EARLY_CLOSURE` | CT-07 误读 | 无枚举、无默认；`recordAs` 原样透传 | 不做下拉、不显示内部码 |
| 提示导致掉全站名次 / 净分替换榜序 | M.7、旧草稿 | `LeaderboardRankPolicy` 不读分数；全站口径变更未授权 | 只描述本局分数与辅助完成 |
| 竞品私有配置名 `HelpPoolConfig` / `AppClaimFirstQuestHelpPack` | BE-16 竞品侧 | 不复刻同名体系；整局额度由 `hintFreeUsesPerRun` 承载 | 不新建同名类 |
| 竞品题型名 `ImageSingleChoice` | CT-07 | 对应原 QA `mode=PICK`；选项配图 `QaOptionMediaSupport` 已接线 | 能力已有；图片扩展已接线，NOT_RUN |
| `hangman.format` 字符数组 | BE-16 | 已接线：`"_"` 为空槽、固定字符/空格保留、槽数=答案字符数、至少一个空槽；矩阵 types=33、PROOF 行 | 客户端目录与渲染器仍未同步，作者端先看不到 |
| 选项中 `alt` ≤32 字 | G0 早期片段 | **无专用字数上限**；配图时必填且非空，只受整份配置字节预算约束 | 不做 32 字硬校验 |
| `originGroupKey` 决定跨平台封顶 | M6 早期片段 | **已过期**：作者自报组键只作建议、不参与封顶；封顶只看运营核验的 `verifiedGroupKey`，未核验零应计 | 作者端标「建议值」；不得显示应计 |
| 视频应计/`payoutState` = 可提现 | 竞品 | 恒 `RECORDED_ONLY`；现金兑付开关拒绝非 0 | 只显示「已记录（未开放兑付）」 |
| host 小费分成比例可配 | 竞品 | `hostSharePercent` 恒 100；通道手续费承担方独立；无通道时 501 `CHANNEL_NOT_ENABLED` | 不提供比例输入；不显示支付成功 |
| 作者自批译文 `APPROVED` | M7 早期实现 | 作者提交恒 `PENDING`；带 `APPROVED` 直接 403；审校权独立、权限未 seed | 作者端只显示「待审校」 |
| OTA 渠道码可用 | CO-09 | 源码有，但依赖 M3、真实 provider 未配置；M0 记 CO-09 L1 **未实现**、未进候选 | 入口可保留但必须显示不可用，不显示成功 |
| 普通 pass/城市包/年卡当前可买 | 商业设想 | `RefundPolicy` 只表达 ③ 探索票；其它 SKU `REFUND_POLICY_NOT_DEFINED`；首次使用型 fail-closed；现金默认 false | 只有明确 ③ 探索票档位显示购买 |
| 加座可直接购买 | M3 方案 | route=3 已裁为独立增购一人准入、无Team；共享分支仍待合入与测试，不先收款 | 显示不可用原因 |
| 固定期年卡=自动续费 | — | 无自动续费新系统 | 文案写固定有效期，不写订阅 |
| 券抵现金 / 券抵报名 | M2 | `couponDeductYuan` 恒 0；券是商家核销型 | 不做抵扣视觉 |
| 竞品金额（€2/1000、€500 封顶、48h/30d、300/站、30/提示） | 竞品/校准脚本 | 只作可配置参考值，不是城瘾默认价 | 不预填、不写死 |

## 十三 接口接线顺序

先完成后端合同与真实样例，再实施前端。下表「增量」只是接线范围；**完成状态以后端最终提交、I 的编译/测试/HTTP 回读为准，不以报告或片段替代**。

| 批次 | UI范围 | 后端先交付 | 前端交付判据 | 当前状态 |
|---|---|---|---|---|
| A 已有事实准确显示 | U03、U04、U05 的既有能力 | 当前返回形状、状态码、内容身份与错误码清单 | 不改业务含义，旧客户端兼容，真实读回一致 | 部分 SOURCE；NOT_RUN |
| B 模板本地化/预设 | U02、§21 | 采用参数、地点待办、来源、保存与发布检查、预设草稿 | 两城市实例独立，待办准确，不泄漏答案；预设草稿可编辑 | T3F 已接线；NOT_RUN |
| C 多人和交易 | U06、U07 | 成员、席位、订单、许可与退款事实 | 多身份、末席竞争、丢回包、部分退款对账 | B6/B7 合同；部分 NOT_RUN |
| D 完局与维护 | U08 | 完成、推荐来源、维护和成熟报名口径 | 真实完成到第二笔有效报名可追溯 | B8 合同；NOT_RUN |
| E 新增玩法读取 | UG-01/02/03/04/10 | 配置块、冻结投影、公开白名单、指认判定 | 未解锁看不到答案；三端一致 | G-PACK 已接线；NOT_RUN |
| F 帮助与结算 | UG-05/06/07/08/09 | 层列表、整局 N、回执、扣减明细、跳过 | 同动作只扣一次；明细可逐笔对账；跳过 0 分 | G-PACK 已接线；NOT_RUN |
| G 作者与后台 | UG-11/12 | 保存校验、发布拦截、审核摘要 | 存稿→读回→发布→冻结一致 | 已接线；NOT_RUN |
| H 商品/礼品/加座/团体学校 | §17 | 商品目录、成交桥接、礼品领取、checkout 指针 | 只有服务端可售项出现购买；礼品一份支付一份准入；加座显示不可用 | M3-F4 已定稿；源码待集成/NOT_RUN |
| I 促销/报价/券 | §17 | 促销目录、报价分项、`marketingSign`、阶梯、券只读 | 不本地算价；409 重新报价；券不抵现 | M4 已暂存；M2-F final 依赖 M3；NOT_RUN |
| J 视频/小费/归因/收入 | §18–§19 | 投稿/审核/核验组/应计、小费意向、归因汇总、创作者收入 | 应计/小费/归因未知不显示 0；无可提现；管理员权限未 seed | M6/M1 已暂存；M5 HOLD；NOT_RUN |
| K 目录/SEO/渠道 | §20 | 公开目录、落地译文审校、渠道兑换 | 无供给不造空页；作者不能自批；渠道未配置显示不可用 | M7 已暂存；M8 未进候选；NOT_RUN |

### 对既有 API 的兼容要求

- 主题模板 `/use` 现有响应是 `AjaxResult.data.topicId` **对象**；前端按真实返回消费。
- 发布检查继续为 `AjaxResult.data` 列表，不改成嵌套新外壳。
- `/api/play/nodes` 已有玩家态 `chapters[].blocks`；先检查消费端，不再新增平行正文接口。
- `/api/play/arrive` 已有 `accuracy` 入参和定位闸；新增原因码不改变现有精度与距离政策。
- 已购工坊端点必须先找到真实承载后端，不能把提案路径发往生产试错。
- 支付、退款、收入和榜单只读投影不能成为另一套账本或权限真源。
- 提示与揭示两条旧链（会话 `PLAYER_HINT/PLAYER_REVEAL`、谜题 `PuzzleScorePolicy`）语义不同；新阶梯不得改写其中任一条的旧行为。
- `GET /api/play/help` 的 `runId` 是 `topic_route_session.id`；高级玩法局由服务端会话作用域解析，客户端不传 kind。

## 十四 验收与交接记录

每项交付记录：UI 任务编号、证据状态、实际修改页面、客户端提交和构建、后端提交及环境、所用接口样例、真实对象 ID、正常与失败状态、读回证据和未测项。测试中的 token、私密线索、原始答案和完整个人信息不进入截图、日志或文档。

小程序、iOS、后台分别验收。同一内容修订的故事顺序、条件、角色可见性、资源后果和最终事实一致；布局可按平台不同。桌面截图、纯逻辑测试、后端联调、设备和实地分别登记。

新增玩法的验收至少覆盖：并双击/断网重试只消费一次、重登恢复正确、未解锁无答案、余额不足零副作用、猜错消耗一次而看档案不消耗、用完机会进入配置后续、跳过不发成功奖励、各端与榜单同源、旧题免费重答不变。

商业营销验收另加：价格/优惠/资格/剩余时限只读服务端；`marketingSign` 变化 409 后重新报价；券不抵现、`couponDeductYuan` 恒 0；礼品一份支付一份准入且买家财务不变；加座/普通 pass/年卡/首次使用型不可售时显示原因且不收款；视频未核验零应计、应计不显示可提现；host 小费通道未启用时 501 且无“支付成功”；OTA 未配置显示不可用、不显示他人订单；目录 `priceState=UNKNOWN` 不显示 0；作者不能自批译文；所有金额/状态均有服务端读回证据。

停止新销售或新开局时，原订单、合法已购内容、进行中局、回执和退款路径仍可恢复。回退内容以新发布修订承接，不覆盖历史。

## 十五 本轮责任边界

- **UI 接手者：** 按本文件改小程序、iOS 与后台，先验证已有实现，再补差异；不重做 AI 起草或创作入口。
- **本轮后端执行者：** 在独立分支核对并补原第2–8项与新增玩法的后端缺口、调用者及测试，更新实际接线合同；不改前端源码。
- **未授权动作：** 本轮不操作生产库，不部署，不发布微信或 App，不改费率、退款政策或账号权限体系，不新增付费服务，不新增币种或排名口径。
- **资料边界：** 私有施工文档只读；竞品剧本、素材、抓包、流量与原始产物不进入本公开仓。

本文件完成表示 UI 范围与交接要求已整理，**不表示**任何一项 UI 已改好，也**不表示**新增玩法后端已可用。逐项状态只能按四层读：

- **SOURCE 候选 → 已接线（仍 NOT_RUN）：** UG-01 `HangmanGameplay`（保存/投影/`SUBMIT_HANGMAN`/完成闸/矩阵 PROOF）、UG-02 `QaOptionMediaSupport`、UG-03 `AccusationTemplateSupport` + `AccusationOutcomePolicy`、UG-04 三态结局、UG-05/06 `PlayHelp*` + G7 整局额度 + G0-F2 唯一 N、UG-07 `PlayHelpOutcomeSupport` + `helpReceipt`、UG-08 `/api/play/help/skip`、UG-10 `ClueTransformSupport`。G-PACK 95 文件 `READY_FOR_I_MERGE`，**尚未合入、未编译、未跑任何测试**。
- **后端未验：** 以上全部**未编译、未跑单测、未连库、未 HTTP 回测**（由 I 在批准 VPS/CI 统一执行）；`play_help_run_quota`、`preset_source` 两份 migration **未执行**。
- **客户端待实现：** 小程序、Vue、iOS **均未改任何源码**；逐字猜词的客户端目录/渲染器未同步；本文件只是改法。
- **App 待验：** 真机、支付沙盒、实地场景全部未做。
- **商业营销：** M3-F4/M2F4/MP-F2/M8F3/MG 已有源码交付与定稿清单，M0 正统一候选；仍有已付席位快照、加座接线、视频水位/整组分摊与退款先到消费的退修，且新包全部 NOT_RUN。provider、视频兑付、小费通道与角色配置按服务端实际状态显示，见 §22。

**已知未决（2026-11-08 最终，不得在 UI 假设已解决）：** ① G-PACK 待合入并在 VPS 验证；提示整局 N 的并发、回滚、跨节点争抢只有 MySQL IT 源码，未执行。② `DIGIT`/`SKIP` 已在最终类型表中，但服务端若回 `HELP_TYPE_UNSUPPORTED` 仍按不可用显示。③ 指认的“提前”依赖真实进度；读不到进度即回滚「结果待确认」，不得当失败。④ 跳过只在非现实凭证节点可用。⑤ `content-schema.v1.json`、完整 golden fixtures、字段级发布定位仍未交付；审查不等于实现。⑥ M3 商品/礼品/加座、M2-F final、M5 收入、M8 渠道的端点仍在变化，标「待确认」。⑦ 用户已定：每局共享 N、免费＋可兑换积分、局内净分且全局榜不变；旧稿 0|1 只记录历史。⑧ 不擅自添加权限授予、竞品费率、币种或全局排名口径；新增权限串不等于角色已获授权。

## 十六 商业营销完整 UI 范围（服务端是价格/资格/钱的唯一真源）

本章覆盖用户授权的全部商业营销范围。**任何金额、折扣、期限、席位、资格、应计、到账状态都只读服务端**；UI 不本地算价、不采信本地时钟、不新增币种/钱包/自动续费、不把“记录/应计/待结算”写成“已到账/可提现”。固定期通行证是**固定有效期**，不是订阅。

| 能力 | 页面/控件 | 服务端真源 | 当前状态（不得写成可用） |
|---|---|---|---|
| 单关票、城市/时限包、固定期年卡 | 商品详情、报价、订单 | 商品目录 `purchase_kind/explore_capacity/validityKind/validDays/fixedValidFrom/To/seat_count/coveredTopics/price/refundPolicy` | M3-F4 已定稿；按服务端可售性/真实履约钟与退款快照判定，不能整类写死不可售；NOT_RUN |
| 组合包 offer | 商品/票卡“省 ¥X”、报价分项 | `GET /api/marketing/promotions/catalog` 的 `offers[]` | M4 已暂存；M2-F final 依赖 M3；NOT_RUN |
| 完局限时/首关优惠 | 报价页资格与剩余时间 | `promotions[]` 的 `eligible/windowEndsAt/sourceFactTime` + `serverNow` | 同上；只认 `player_badge` 通关事实 |
| 人数阶梯 | 票种“3–5 人 ¥80/人” | `tiers[]` 单价 + 重新报价 `payAmount` | 同上；阶梯是单价变化，不是折扣行 |
| 独立礼品价 | 礼品商品卡、送礼/领取页 | M3 礼品 SKU `kind=7` 的 `cashPrice/giftAllowed/giftClaimDays` | M3/M3-F3 已接线；NOT_RUN；一份支付一份准入 |
| 加座 | 报名页加座项 | route=3 报价/下单 | 已裁为独立增购一人准入、礼品同源领取、无 Team；共享分支仍待合入，NOT_RUN |
| 团体/学校 | 团体/学校详情、报名 | route=2 + 真实 `oms_ticket`；报价回 `checkout` | 源码已接线；NOT_RUN；不建平行席位/价格 |
| 自带流量/联盟码 | 分享、落地页、下单后归因 | `/api/marketing/referral/token`、`/api/marketing/attribution/claim` | M1 已暂存；NOT_RUN；UI 不展示金额/佣金 |
| 创作者/联盟分佣与锁价 | 创作者中心收入 | `/api/marketing/revenue/author/mine`、`/order` | M5/M8F3 已接投递和渠道源；费用缺失待核，仅应计读面，NOT_RUN |
| 视频悬赏 | 创作者中心“视频悬赏”tab | `/api/video-bounty/*` | M6 已暂存；NOT_RUN；应计恒 `RECORDED_ONLY` |
| 视频核验组/封顶 | 运营审校台“原视频组” | `POST /business/videobounty/submission/{id}/verify-group` | 已接线；NOT_RUN；作者自报组键**不**参与封顶 |
| host 小费 | 主持设置、玩家打赏 | `/api/host-tip/*` | 源码齐；通道未启用，`prepare` 501；NOT_RUN |
| OTA 渠道码 | 兑换入口、渠道后台 | `/api/channel/redeem`、`/business/marketing/channel/*` | 已有真实 adapter→M2→原准入及渠道退款链源码；provider 未配置，NOT_RUN |
| 城市/关卡/团体学校目录与 SEO | Web 落地页、小程序/App 列表 | `POST /api/public-catalog/*`、`/api/content-landing/*` | M7 已暂存；NOT_RUN；sitemap/hreflang 未做 |

---

## 十七 商品、礼品、加座、团体学校与促销报价（M3 / M3-F3 / M2 / M2-F / M4）

### 17.1 商品与通行证（M3）

- **唯一可玩真源是 `cms_registration`。** `marketing_product`/`marketing_product_order` 只是目录、购买来源与映射；成交必须在同一事务产出 `cms_registration`；`PlayNodeAccessService` 与主入口只增加礼品受益人过滤；**不得**把营销 entitlement/座位当成第二准入。
- **目录字段：** `purchase_kind`（2 普通/城市包/限时/年卡/团体/学校/礼品/加座；3 探索票）、`explore_capacity`（只有 3 才为 1）、`validityKind`（-1 首次使用型 / 0/1/2 固定或相对有效期）、`validDays`、`fixedValidFrom/To`、`seat_count`、覆盖主题与冻结 `contentRevision`、价格、退款政策。
- **当前可售性（必须如实显示）：**
  - 普通/城市包/时限/固定期年卡/团体/学校/礼品/加座使用 `purchase_kind=2 && explore_capacity=0`，探索票使用 `purchase_kind=3 && explore_capacity=1`；不能仅凭档位在客户端判定是否可购买。
  - ② 复用原真实履约钟与冻结退款截止，③ 沿原退款策略快照；缺真实履约时钟/期限等按后端明确原因阻断，不在客户端自创退期或补默认值。原“②全部 REFUND_POLICY_NOT_DEFINED”已过期。
  - 首次使用型已有按同一 SKU 首次激活时刻冻结有效期与激活行回挂源码；仍需运行验收，且已付席位快照退修未结束，不画成已可用。
  - 现金沿 `marketing.product.cash-enabled=false` 默认关闭。只有服务端返回该 SKU 当前可售、支付方式可用、报价成功，才允许提交；新源码未验收不能当上线事实。
- **控件：** 商品卡（名称/覆盖作品/期限/席位/价格单位）、详情（含什么、谁受益、退款规则）、购买按钮、订单状态。`priceState=UNKNOWN` 不显示 0 元；`coveredByPass` 只提示“你已可直接游玩”，**仍允许购买**，不自行禁用按钮。
- **失败/未知：** 不可售原因、SKU 币种不支持、退款政策缺失、现金通道关闭分别显示后端 `reasonCode`；下单未知查原 `requestId`/订单，不重复建单。
- **权限：** 商品配置与购买归属按 M3 服务校验；后台未 seed 新权限，普通运营 403 是预期。
- **读回：** 购买后读订单与实际 `cms_registration`；激活读 `POST /api/marketing/product/activate` 的真实到期时间；不把“支付成功”当“已激活”。

### 17.2 礼品（M3 + M3-F3，单行语义）

- **独立礼品价来自 M3 礼品 SKU**（`kind=7`，`cashPrice` + `giftAllowed` + `giftClaimDays`），**不在 M4 促销接口**；M4 的 `offers[]` 只有组合包。礼品价是商品定价，不用负调整/溢价字段表达，也不与报名票面混算。
- **一份支付、一份准入：** 买家支付后保留原 `cms_registration` 的全部财务事实（金额、支付、退款权）；领取**不新建 registration、不产生第二份 payable**，只把 `gift_entitlement.claimed_member_id` 指向同一行（`LINKED_SAME_REGISTRATION`）。
- **谁现在能玩：** 待领取（status=0）与已领取给他人（status=1 且不是本人）⇒ **买家也不可玩**（`isWithheldFromBuyer` 对 0/1 都 true）；已领取给本人 ⇒ 受益人可玩。M3-F3 已把该准入过滤接到 11 个真实调用点（`PlayNodeAccessService`、`TopicRouteRuntimeServiceImpl`、`AdvancedGameRuntimeServiceImpl`、`GameSessionRuntimeServiceImpl`），**NOT_RUN**。
- **UI 状态：** 待领取 / 已领取给本人 / 已领给他人 / 已撤销 / 已过期；购买页与领取页分开。领取失败按原因显示（码无效/过期/已领/源单不可领/已开始/已核销），**不泄露他人订单**。
- **禁止：** 第二张票、第二个 payable、礼品钱包/余额、“已支付即可玩”、买家领取不校验。退款撤销只把礼品置 status=3 回到买家，原 registration 退款仍走 C07 原链。
- **复查修复（待运行）：** M3-F4 已按 `parent_order_no` 覆盖同一订单的多主题来源行，并逐行复核领取条件；主 `/api/play/nodes`、排行榜及历史读面统一礼品资格。查询只取当前买家/领取人相关事实；这不等于已验证整包可玩。

### 17.3 加座（route=3，已裁决、源码退修中）

- 已选“每次独立购买一位的准入＋复用礼品同源领取”；N 人允许 N 个真实业务订单。买家保留该次购买的财务与退款事实，领取不产生第二份 payable，不改原单数量或 `parent_order_no`。
- 自玩域没有原队伍名册，`partyBinding=NOT_APPLICABLE`；不能展示“已加入原队”或把活动的 `teamMaxMembers` 套成独立增购上限。活动团队加座尚不支持。
- MS 共享购买/报价 patch 仍待完整合入与测试，已退回自造容量限制及过期的“只 primary 所以禁多主题”理由。按服务端可用性显示，不能先收款；payload 以退修后的冻结合同为准。

### 17.4 团体/学校（route=2）

- 商品必须绑定真实 `oms_ticket`（`fulfillment_route=2` + `ticket_id`，规则层校验票种存在）。
- 商品报价返回 `checkout` 指针；MG 适配入口为 `POST /api/marketing/group-checkout/quote`、`/create`，由服务端解析商品→真实活动票→主题/场次，再调用原 `IRegistrationCheckoutService`。客户端不提交金额、owner 或 ticket 归属；按回包传 `quoteSign/marketingSign`，报名信息同意门禁保留。21 例单测只有源码，运行 NOT_RUN。
- `groupEligible/schoolEligible=null` 是“未声明”，不显示为“不接待”；人数/年龄/无障碍来自 M7 落地声明。

### 17.5 促销目录与展示（M4）

**唯一数据源：** `GET /api/marketing/promotions/catalog?ownerType={1|2}&ownerId={topicId|activityId}`（需登录）。返回 `serverNow`、`offers[]`、`promotions[]`、`tiers[]`；`offers` 只含组合包（`offerKind=1`）。

| 位置 | 用哪个字段 | 禁止 |
|---|---|---|
| 商品/票卡“省 ¥50” | `offers[].differenceAmount` | 前端 `listAmount - priceAmount` 自算 |
| 组合包可选 | `offers[].available` | 用 `validTo` 或本地时间自判 |
| “限时优惠还剩 X 小时” | `promotions[].windowEndsAt` 减 `serverNow` | 用 `new Date()`；离线不刷新 |
| “你有首关优惠” | `promotions[].eligible` | 用 `sourceFactTime` 非空推断 |
| 不可用原因 | `reasonCode` | 解析中文文案 |
| 人数阶梯 | `tiers[].unitPrice` | 当作折扣行；逐行提示“还差 1 人” |

**reasonCode 文案（码不可改，文案可改）：** `TRIGGER_FACT_MISSING`→通关后解锁；`TRIGGER_WINDOW_EXPIRED`→优惠已过期；`ALREADY_USED`→已使用过；`MEMBER_LIMIT_REACHED`→已达使用次数上限；`BELOW_MIN_AMOUNT`→未满使用门槛；`STACK_GROUP_TAKEN`/`EXCLUSIVE_ALREADY_APPLIED`→已享受更优优惠，本项不叠加；`OFFER_MEMBER_LIMIT`→已达购买次数上限；`OFFER_ITEMS_MISMATCH`→该优惠与当前票数不匹配；`OFFER_WINDOW_CLOSED`→优惠已结束。

**空态：** `offers`/`promotions` 都为空→整块不渲染，不写“暂无优惠”；`tiers` 为空→只显示票面单价；401→未登录；5xx→报价待确认且暂停提交，**不得**把票面价或缓存旧优惠当作可成交报价。

**规则（写在服务端）：** 顺序固定“阶梯改单价 → 组合包替换折前额 → 触发式促销按 priority desc, id asc 在余额上减”；组合包与阶梯互斥；金额守恒 `list − tier − offer − promotion == payable`；同 `stackGroup` 只取一个；`exclusive` 生效后其余排除；资格只认 `player_badge` 通关事实；`windowHours` 必配、无默认；**完局限时 `trigger=1` 绑定具体主题**，活动报价通过所属主题读取这些配置（MP 修复后源码，待验），**首关优惠 `trigger=2` 取会员最早一次通关事实（不是最近一次）**；部分退款累计实退 ≥ 原支付总额才撤销资格，单笔部分退款不撤；多票订单按父单归并。

### 17.6 报价、建单与券（M2 / M2-F）

**重试规则：** 同一请求原样重发保留 `requestId`；改优惠或重新报价后的 `quoteSign` 变化，必须生成新的 `requestId`。报价与建单的票数组顺序保持一致。整单实付看主单/原回包，不累加各子行 `wechat_payment_amount`。

**报价 `POST /api/registration/quote` 新增字段：**

| 字段 | 含义 | 界面怎么用 |
|---|---|---|
| `listAmount` | 票面合计（促销前） | “原价”划线锚点 |
| `marketingDiscountAmount` | 促销减免合计（阶梯+组合包+触发式） | 显示“优惠 -¥X”；为 0 不显示优惠行 |
| `marketingAdjustments[]` | `kind`(TIER/OFFER/PROMOTION)、`code`、`label`、`amount` | 优惠明细逐条列，标签直用 `label` |
| `marketingExclusions[]` | `code`、`reasonCode`、`label`、`detail` | 只在用户主动选了优惠码时提示 |
| `marketingSign` | 促销报价指纹 | 下单原样回传；不展示、不改 |
| `payAmount` | 最终应付（促销后，含积分抵扣） | **唯一收款数字** |
| `coveredByPass` | 是否已持覆盖本主题的通行证 | 提示“你已可直接游玩”，仍允许购买 |
| `coveredTopicId` | 判定依据主题 id | 跳转用 |

- **不要用 `listAmount - marketingDiscountAmount` 自算应付**——积分抵扣不在里面；一律 `payAmount`。
- 优惠码只提交业务码 `offerCode`；选码后重新报价，新 `marketingSign` 覆盖旧值，切码丢弃旧 sign。
- 下单带 `quoteSign + marketingSign + offerCode`；两种 409 分开提示：“价格已更新，请重新确认” / “优惠已变化或与本单不一致，请重新报价后再提交”，都重新报价，**不当网络错误重试同一请求**。
- 促销生效但客户端没带 `marketingSign` ⇒ 后端一律 409（刻意的，不允许“看到优惠、实付不是这个价”）。
- **锁价与费率快照：** 成交时固化 `quoteSign`/`marketingSign`/`discount_amount`/促销分项/`fee_platform_rate`（每行按覆盖主题快照；缺费率拒单，不填 0）；事后不按 live 规则回读；参考费率不得画成已生效费率。
- 零应付 = 该优惠不可用：报价/建单都拒绝 `PROMO_WOULD_MAKE_FREE`，**不得**静默去掉优惠按原价收，也不得显示 0 元。
- 会员价与促销价叠加未配置 ⇒ fail-closed；正常不出现（会员价全站停用）。
- **数量口径：** `tickets[].count` 是票张数，唯一下单数量；`min_people/max_people` 是成团人数、`team_size` 是建议组队人数，**都不是价格阶梯或限购**。库存不足显示服务端固定文案「库存不足,仅剩 N 张」，不用缓存余票自算。
- **完局奖励券 `completeRewardCoupon`：** 只读期望展示，`payAmount` 不因它变化、`couponDeductYuan` 恒 0；已结束/失效/停发/删除/本人已领 → null，整块不显示；不显示“满 X 可用”门槛。
- **券包 `POST /api/coupon/usability`：** 只读列表；`usable/reason`（`AVAILABLE/USED/EXPIRED/DISABLED/NOT_STARTED/COUPON_MISSING`）；商家券才有 `merchantId`；核销并发失败按原核销结果展示。无自助领取接口，不画“立即领取”。

**状态：** M4 43 文件已暂存；M2-F final 24 文件**未暂存**（依赖 M3）；M2-F3 已接渠道冲销/份数席位；全部 **NOT_RUN**。零元单语义、普通 pass 退款档位、会员价叠加规则仍需产品/退款政策 owner 决定——UI 不替它们做默认。

---

## 十八 视频悬赏、主持小费与成交归因（M6 / M6-group-verify）

> 45 文件源码已交接、校验值一致；编译、四类单测、DB 与 HTTP 均 **NOT_RUN**。真实支付/兑付通道未启用。本章不是上线承诺。

只给文档，不改任何小程序 / Vue / Swift / 网页源码。本域**没有任何“提现/余额/收益到账”能力**：视频应计恒为“仅记录”，主持人小费通道未启用时返回 501。所有页面文案不得暗示“已完成收款/可提现”。

**统一约定：** 所有拒绝响应体带 `reasonCode`（顶层字段），客户端按码分支，不解析中文文案。

### A 作者端：视频悬赏投稿

**入口：** 创作者中心「我的作品」新增「视频悬赏」tab（复用现有 tab 容器，不新开页面层级）。**改前：** 无此入口。

| 控件 | 接口 | 字段 |
|---|---|---|
| 活动选择 | 运营下发；空列表显示“暂无进行中的悬赏” | `campaignKey` |
| 作品归属 | 复用现有主题选择器 | `topicId`（必须已上架） |
| 平台 | 三选一 TikTok / Instagram Reels / YouTube | `platform`=`tiktok`/`reels`/`youtube` |
| 视频链接 | 单行 URL | `sourceUrl`（只接受 https；必须与平台一致） |
| 同一原视频分组 | 可选输入 | `originGroupKey`（**仅建议值，不参与封顶**；不填则本条自成一组） |
| 提交 | `POST /api/video-bounty/submission` | — |

**状态：** `GET /api/video-bounty/submission/mine?campaignKey=` 返回 `statusLabel`：`SUBMITTED`/`UNDER_REVIEW`/`APPROVED`/`REJECTED`/`REVOKED`/`APPEALING`/`CANCELLED`；通过后才有 `publishedAt`/`windowEndsAt`。

**行内操作：** `REJECTED`→「申诉」（必填说明，`POST .../{id}/appeal {note}`）；`SUBMITTED/UNDER_REVIEW/REJECTED/APPEALING`→「取消投稿」(`.../cancel`)；**`APPROVED` 不显示取消**（只能运营撤销；作者取消被拒 `CANCEL_NOT_ALLOWED`）。**审核只能运营做，作者不能自批 `APPROVED`。**

**失败按码提示（不要合并成“提交失败”）：** `VIDEO_ALREADY_SUBMITTED`→这条视频已经投过稿；`PLATFORM_MISMATCH`→平台不一致；`LINK_PLATFORM_UNSUPPORTED`→只支持三平台；`LINK_VIDEO_ID_MISSING`→请用视频页而非主页；`LINK_SCHEME_NOT_ALLOWED`/`LINK_USERINFO_NOT_ALLOWED`→只支持 https 公开链接；`TOPIC_NOT_FOUND`→主题不存在或未上架；`CAMPAIGN_NOT_ACTIVE`→当前不接受投稿。

**不要做：** 前端拼视频 id；用短链自动换算（短链只能对完全相同的短链去重，后端已标注）。

### B 作者端：我的应计（只读）

**入口：** 上述 tab 的第二段「播放与应计」。**接口：** `GET /api/video-bounty/accrual/mine`。

**必须显示：** `basisViews`（本条窗口内有效自然播放）、`groupBasisViews`（同组跨平台合计）、`payableAmount` + `currency`、`payoutState`。`payoutState=0` 一律「已记录（未开放兑付）」——**不要**显示“待提现/可提现/已到账”。空态与失败分开：无记录→“还没有应计记录”；请求失败→“读取失败，重试”，**不显示 0 元**。

### C 主持端：小费配置

**入口：** 俱乐部场次/主持资料页的「小费」设置项。**接口：** `POST /api/host-tip/config`、`GET /api/host-tip/config/mine`。

| 控件 | 字段 | 约束 |
|---|---|---|
| 开通开关 | `enabled` | 通道未启用时置灰并显示“暂不可开通”；强行打开 `CHANNEL_NOT_ENABLED` |
| 作用域俱乐部 | `clubId` | 0=不限定 |
| 单笔最低 | `minAmount` | ≥0 |
| 币种 | `currency` | 3 位大写 ISO-4217 |
| 通道手续费谁承担 | `channelFeeBearer` | `0`=主持 / `1`=平台（独立设置项，不藏进文案） |

**只读：** `hostSharePercent` 恒 100（“小费 100% 归主持”）；**不要**提供分成比例输入框。100% 分成不等于扣渠道费后实收与原金额相同；`hostSharePercent` 与 `channelFeeBearer` 是**两块独立展示**，不得合并成“平台纯支出”。

### D 玩家端：给主持小费

**入口：** 场次结束页/主持主页「打赏」。**接口：** `POST /api/host-tip/prepare {hostMemberId, clubId?, amount, orderRefId?, requestId, note?}`。

- 金额快捷项 + 自定义；显示“100% 归主持”；`requestId` 重试复用（同一顾客同一号只产生一笔意向）。
- `payable=false` + `CHANNEL_NOT_ENABLED`（501）→「收款暂未开通，已记录你的意向」，**不得**出现“支付成功”，不跳支付页；`payable=true` 才进支付。
- 取消 `POST /api/host-tip/intent/{id}/cancel`（仅未支付；已支付 `CANCEL_NOT_ALLOWED`）；记录 `GET /api/host-tip/intent/mine`（顾客）、`.../received`（主持）。
- 状态：`CREATED`/`AWAITING_PAYMENT`/`PAID`/`CANCELLED`/`REFUNDED`/`CHANNEL_UNSUPPORTED`；后两者必须分开（“没开通” vs “你取消了”）。
- **不要做：** 把小费做成订单应付项、改原订单金额/票券、在前端算手续费。

### E 运营后台（Vue）

**入口：** 新增菜单「视频悬赏」「主持小费」；菜单行未配置，超管 `*:*:*` 可直接访问，未授权普通运营 403（刻意 default-closed）；菜单与角色授权由 root 决定。

| 页面 | 接口 | 要点 |
|---|---|---|
| 活动列表/编辑 | `GET/POST/PUT /business/videobounty/campaign*` | 费率/封顶/窗口是可配置参考值，表单写“参考值，非已批准费率”；已有应计时锁定（`RULE_LOCKED_BY_ACCRUAL`），前端置灰“请新建活动” |
| 现金兑付开关 | 同上 | **不提供**（非 0 值 `CASH_PAYOUT_NOT_AVAILABLE`） |
| 待审/审核 | `GET /business/videobounty/submission/list`、`POST .../{id}/review` | 通过必填 `publishedAt`；拒绝必填 `reviewNote` |
| 证据录入 | `POST .../{id}/evidence` | `sourceRef` 必填且是外部来源（平台报表号/导出文件/核验截图摘要）；自然流量、可核验开关关掉即不计应计 |
| 应计列表 | `GET /business/videobounty/accrual/list` | 只读；无“打款”按钮 |
| 撤销投稿 | `POST .../{id}/revoke {note}` | 必填理由；应计置“已撤销”而非删除 |
| 小费审计 | `GET /business/hosttip/intent/list?hostMemberId=` | 只读；无“标已付”（只能由真实通道回执推进） |

**权限串：** `business:videobounty:config`、`business:videobounty:review`、`business:hosttip:audit`。代码没有自动授予、没有 seed 菜单；普通运营 403 是预期。

### F 原视频组核验（M6-group-verify，取代作者自报分组决定封顶）

| 端点 | 权限 | 说明 |
|---|---|---|
| `POST /business/videobounty/submission/{id}/verify-group` | `business:videobounty:review` | 核验原视频组 |
| `POST .../{id}/evidence` | 同上 | 录入外部播放证据（不变） |
| `POST .../{id}/accrual/recompute` | 同上 | 重算应计（**现在要求先核验组**） |

请求体：`{verifiedGroupKey, sourceRef, expectedVersion, reason}`。

- 审校台「原视频组」区块显示：作者自报组键（只读，标「建议值，不参与封顶」）、当前核验组键、核验人、核验时间、证据引用、核验版本。
- 提交核验前必须填**证据引用**与**理由**，两者后端都必填；缺任一项点击无效并提示。
- 已有有效应计时组键输入框置灰（后端也拒），提示「该投稿已有应计，原视频组不可再变更」。
- `GROUP_VERIFY_VERSION_CONFLICT`→刷新后重试，**不自动重试**；`ACCRUAL_GROUP_UNVERIFIED`（触发应计时）→「请先核验原视频组」，不显示 0 元。
- 投稿列表列增加「原视频组」状态：未核验/已核验（组键）；未核验即使审核通过，也不显示应计金额，只显示「未核验，暂无应计」。
- 核心不变量：`verified_group_key` 取代作者自报；未核验零应计；已计后不可改组；**同作者约束**（`GROUP_AUTHOR_MISMATCH`）；CAS + 审计；重算只追加新行并把旧行置 `SUPERSEDED`，撤销置 `REVERSED`，永不删行/改金额。

### G 成交归因（接归因域真实数据）

后端已接线 `IMarketingAttributionService.aggregateOwnedCampaign(callerMemberId, campaignKey, targetType, targetId)`。**聚合主体（sharer）与目标（target）全部由服务端从投稿行 / `video_bounty_campaign.owner_member_id` 读取**，请求里没有 sharer/owner/target 参数。

| 使用者 | 接口 | 谁能看 |
|---|---|---|
| 作者（小程序） | `GET /api/video-bounty/submission/{id}/attribution` | 仅该投稿作者；他人投稿 404（不泄露存在性） |
| 活动 owner（小程序） | `GET /api/video-bounty/campaign/{id}/attribution?topicId=` | 仅 `owner_member_id == 当前会员`；`topicId` 必须是该活动下已通过投稿，否则 `TARGET_OUT_OF_SCOPE` |
| 运营（后台） | `GET /business/videobounty/submission/{id}/attribution` | 具备 `business:videobounty:review`；聚合主体仍是投稿作者，不是运营自己 |

返回形状：`{campaignKey, targetType, targetId, status, confirmed, attributedOrders, paidOrders, paidAmount, refundedOrders, refundedAmount, amountCurrency, reasonCode}`。

| `status` | `confirmed` | 数字 | 必须的 UI 文案 |
|---|---|---|---|
| `BOUND` | `true` | 有值 | 正常展示计数与合计 |
| `UNBOUND` | `false` | **全部 `null`** | 「未绑定推广来源，暂无确认数据」——**绝不能**显示“成交 0 单 / ¥0.00” |
| `OWNER_NOT_SET` | `false` | **全部 `null`** | 「该活动未设置归属作者，成交数据不可用」——配置缺口，不是“没有成交” |

- **`null` 必须保留**：不要 `?? 0`、`|| 0`。
- 已付与退款分别显示；不要自行算“净成交”。
- 币种固定 `CNY`（归因域按订单实付快照），与活动 `currency` 是两套口径，不混行、不换算。
- 只有计数与合计；没有 orderId/memberId/registrationId，不提供“按订单查看”。
- 作者端放投稿列表展开区；后台放待审/已审列表只读展开项。
- 活动表单 `ownerMemberId`：不填→`OWNER_NOT_SET`；会员不存在/状态异常→`OWNER_UNAVAILABLE`；已有应计时改归属→`OWNER_LOCKED_BY_ACCRUAL`，置灰并提示“请新建活动”。

---

### I 推广来源凭据（M1，自带流量/联盟码）

**入口：** 主题/活动详情现有「分享」按钮旁，不新增主入口。**原则：** UI 只做“拿到分享凭据”和“把凭据挂到刚下的单”，**不展示、不计算任何金额/佣金/折扣**。

- 点击分享 → `POST /api/marketing/referral/token`（body：`targetType` 1=主题 / 2=活动、`targetId`、可选 `campaignKey`、`ttlSeconds`）→ 把返回 `token` 拼进分享链接 query（建议键 `ref`）。
- 失败态：401→「登录后可生成专属分享链接」并跳登录；`TARGET_NOT_FOUND`→不显示分享入口（内容未上架）；其它→静默降级为普通分享（不带 token），**不提示“分享失败”**，不阻断分享。
- 落地页：读 query `ref`，按登录账号隔离存本地（换号失效）；**不展示“来自 XX 的推荐”**；不参与报价，价格/优惠/库存/可售性仍由服务端报价决定。
- 下单成功（拿到 `registrationId`）后调 `POST /api/marketing/attribution/claim`（`{orderType:1, orderId, token}`）；这是**尽力而为的旁路调用**：失败不影响下单成功页、不上屏、重试不超过 1 次；成功后清本地 `ref`，避免挂到下一单。
- **归因快照只写一次；同单换来源 409 不可改写；不写佣金、不改报价/锁价/结算/票源冻结。**
- 结果码按 `reasonCode` 分支（不解析中文）：`INVALID_TOKEN`/`TOKEN_EXPIRED`/`TOKEN_REVOKED`→静默丢弃；`TARGET_MISMATCH`→静默丢弃；`ORDER_OUTSIDE_TOKEN_WINDOW`→静默丢弃；`ORDER_NOT_OWNED`/`ORDER_NOT_FOUND`→静默丢弃 + 埋点；`ATTRIBUTION_CONFLICT`→静默丢弃（已归属别的来源）；`SELF_REFERRAL`→静默丢弃（自己给自己下单）；`ORDER_NOT_ATTRIBUTABLE`→静默丢弃（已取消/过期/已退款）。
- **明确不做：** 推广码/邮箱兑换入口（无真实渠道合同）、推广收益/佣金/排行榜/结算页（本域只有归因事实，没有钱）、运营端发码/撤销界面（无批准的新权限点）、客户端展示或计算 `campaignKey` 金额。
- **状态：** 19 文件已暂存；无编译/测试/HTTP；NOT_RUN。端点标**待确认**。

---

## 十九 创作者与联盟收入分配（M5，只读）

- **入口：** 复用现有创作者中心收益区（`subpackageA/pages/assetcenter/income-detail`），不新增主入口。**与 U07-05 的 B8 创作者应计是两条不同投影**：本域是营销分配/冲销，U07-05 是内容成交应计；不得相加或互相替代。
- **接口：** `GET /api/marketing/revenue/author/mine`；订单级 `GET /api/marketing/revenue/order?orderType=1&orderId=<id>`。
- **列表字段：** `allocationId / orderType / orderId / scenario / currency / status / statusText / settleableAt / reversedAmount / contractRef / ruleVersion / lines[]`。
- **状态映射（按码分支）：** `accrued`→「已入账 · 待可结算」+ `settleableAt`；`settleable`→「可结算」；`paid`→「已打款」；`partially_reversed`→「部分退款已冲回」+ `reversedAmount`；`reversed`→「已全额冲回」；`unknown`→「状态待确认」（**不显示 0 元**）。
- **金额：** 优先 `lines[].amountFen`（整数分）累加再除 100；`amount` 是同值小数视图，二选一，**不要两个相加**。
- **冲销：** `entryType=2`、金额为负；展示「退款冲回 −¥x.xx」，与正向分录分开列，不直接相加成“负收益”；同一退款可能分多条（作者/推荐人各冲），按 `sourceFact`（`refund:<id>`）分组。
- **明确不做：** 提现按钮（本域没有钱包、没有 markPaid）；不向普通用户展示费率/`ruleVersion`；不展示“预估收益/预计到手”；不做客户端分配计算。
- **权限/读回：** `NOT_PARTICIPANT`（403）不展示入口，不是错误弹窗；`data=null` →「收入尚未入账」，不显示 0；`lines[]` 已剔除平台分录，不自行补“平台”行。
- **状态：** 45 文件 SOURCE_COMPLETE，107 用例全 **NOT_RUN**；**未进候选（HOLD）**；`marketingRevenuePostingTask` 无 `sys_job` 行（只能准备暂停登记，不启用）；`business:revenue:*` 未建菜单 → 后台 fail-closed；只支持 CNY；无真实打款。所有端点标**待确认**。

---

## 二十 城市/关卡/团体学校目录、SEO 与 OTA 渠道（M7 / M8）

### 20.1 公开目录与落地页（M7）

**匿名只读：** `POST /api/public-catalog/city/list`、`/list`、`/map`、`/ids`、`/info`、`/filters`。卡片字段：`id`（字符串化 bigint）、`canonicalPath`、`cityCode`、`cityName`、`name`、`subtitle`、`summary`、`imgUrl`、`productType`、`totalTime`、`totalMileage`、`locationCount`、`chapterCount`、`deliveryModes`、`priceUnit`、`priceFrom`、`priceState`、`saleState`、`freeSelfPlay`、`groupEligible`、`schoolEligible`、`groupMinPeople/MaxPeople`、`ageMin/Max`、`accessibilityNote`、`requestedLocale/actualLocale/translationState/translationFields/availableLocales`、`revision{state,saleSnapshotId,frozenAt,publishedConfigVersion}`、`updatedAt`。

- **城市页：** 只出现真实有公开作品的城市，**不造空城市页**；`cityName` 缺失就 null，不猜名、不翻译补名。
- **筛选：** 只接受合法枚举（未知枚举 400，不降级“全部”）：`city_code`、`locale`、`keyword`、`product_type`、`delivery_mode`、`group_eligible`、`school_eligible`、`audience_age`、`min/max_minutes`、`sort`。
- **价格：** `priceState=UNKNOWN` 不回退旧价、不当 0；`priceUnit` 区分 `SELF_PLAY_PASS` 与 `TICKET`；购买仍走原报价/订单。
- **预览：** 只给 `chapterNames` + `hook_text`/截断 `description`；**答案、完整付费正文、提示、核销码、商家邮箱不进预览**。
- **团体/学校：** `groupEligible/schoolEligible=null` 是未声明，不显示“不接待”；只有 `content_landing_metadata` 的显式布尔参与筛选，不按关键词自创分类。
- **作者落地配置：** `POST /api/content-landing/info`（读）与 `POST /api/content-landing/save`（写，本人主题、`expectedVersion` 乐观锁）；字段 `landingSummary/groupEligible/schoolEligible/groupMinPeople/MaxPeople/ageMin/Max/accessibilityNote`。**不改发布/售卖/价格/退款**。
- **译文：** `POST /api/content-landing/translation/save` 只写 `title/subtitle/summary`，状态**恒 PENDING**；带 `APPROVED` 直接 403。作者改已审校译文后界面必须回到「待审校」并提示「已审校内容被修改，需重新审校后才对外可见」；不显示审核人姓名/意见正文。
- **审校台（后台）：** `GET /business/content-landing/list`（`business:topic:query`，只含已公开主题）、`GET .../translation/digest`（`business:contentlanding:review`）、`POST .../translation/review`（只翻状态，不覆盖作者文字）。打开先取摘要，审校原样回传；409→「内容已被作者修改，请刷新后重新审校」，不自动重试；理由必填 ≤200 字并进审计；**不做批量批准**、不做机翻自动置已审校、不编辑作者正文。
- **权限：** `business:contentlanding:review` **未 seed、未授角色**，仅超管；普通运营 403 是预期，提示「该功能尚未对当前角色开放」，不用“权限不足”吓用户。
- **SEO 边界：** 本域只提供稳定路由 `canonicalPath` 与公开读面；**sitemap/hreflang/robots 未做**，不得写“SEO 已上线”。正文多语言不因摘要译文而标已完成。
- **状态：** 36 + M0 修 5 文件已暂存；漂移双哈希已记录并复核；迁移未执行；编译/单测/IT/HTTP 全 **NOT_RUN**；`PublicContentCatalogMyBatisIT` 尚未接入 CI 清单（patch 已备）。端点标**待确认**。

### 20.2 OTA 渠道码兑换（M8 / CO-09）

**当前不能宣称可售/可用：** 真实渠道 provider 未配置，验证阶段返回 `PROVIDER_NOT_CONFIGURED`；M8 包依赖 M3，未进候选；M0 记 CO-09 L1 **未实现**。即使有已验证码，M2 退款政策/绑定缺口未收口时兑换会失败。

**玩家侧：** 一个「兑换码」输入框 +「兑换」按钮 → `POST /api/channel/redeem`。

| 场景 | 展示 | 数据来源 |
|---|---|---|
| 成功 | 「已兑换」+ 商品名 + 有效期 + 席位；「去使用」跳原商品 | `productName/seatCount/validFrom/validTo/registrationNo` |
| 同请求重放 | 直接原回执，不重复成功动画 | `replayed=true` |
| 渠道未配置 | 「该兑换码暂不可兑换」；不显示码已使用 | `PROVIDER_NOT_CONFIGURED` / `REFUND_DECISION_POINT_MISSING` |
| 已过期 / 已撤销 | 「兑换码已过期」/「兑换码已撤销」 | `CODE_EXPIRED` / `CODE_REVOKED` |
| 他人已用 | 「兑换码已被使用」；不显示他人订单 | `ALREADY_REDEEMED_BY_OTHER` |
| 不属于当前账号 | 「该兑换码不属于当前账号」 | `SUBJECT_MISMATCH` |
| 无效码 | 「兑换码无效」；不区分不存在/哈希不符 | `INVALID_CODE` |
| 首次使用型商品 | 「该商品暂不支持渠道兑换」 | `PRODUCT_VALIDITY_UNSUPPORTED` |
| 外币订单 | 「该渠道订单币种暂不支持」 | `CURRENCY_UNSUPPORTED` |

- 不在客户端展示渠道单号、验证金额、合同编号、原注册单内部 id；失败只展示 `reasonCode` 人话。
- 成功后展示的是既有报名/权益事实（`registrationNo`），不是现场核销票，不复用扫码核销页。
- 未接线时**入口可保留但必须显示不可用**，不得隐藏后宣称“功能可用”。

**管理端：** 渠道主体、渠道订单、发行、兑换码、撤销、对账；新权限串 `business:marketing:channel:{list,manage,verify,issue,revoke,reconcile}`，未 seed/未授角色，403 是预期。明文码只在发行成功弹窗显示一次；对账差异定位到渠道单号/兑换码，含「已兑换但无原注册事实」；对账金额只读渠道验证事实，**不展示平台已结算/可提现**（本域不产生）。

**状态：** 源码有（5 张新表、typed CHANNEL 事实、退款撤销 adapter），但依赖 M3、provider 未配置、迁移未执行、测试 **NOT_RUN**；按**未启用/未进候选**处理。

---

## 二十一 模板预设与采用闭环（T3F / T3）

### 21.1 三层入口，不要第四层

| 作者想要 | 入口 | 返回 |
|---|---|---|
| 从固定结构起一个**小游戏** | `POST /api/preset/gameplay/draft` | `draftId`（个人模板草稿）+ `authorTodos` |
| 把这小游戏装进**主题** | `POST /api/topic/v2/create`（`saveDraft=1`），节点 `templateId` 填上一步 `draftId` | 主题草稿 |
| 沿用别人已上架的货架内容 | `POST /api/template/adopt-draft`（既有） | 采用者新草稿 |
| 采用整个主题 | `POST /api/template/topic-template/use` | `data.topicId` **对象** |

**两个 ID 不能串：** `/preset/gameplay/draft` 返回**个人模板 ID**（节点 `templateId` 用它）；市场采用回执的 `originalTemplateId` 是**货架 ID**；预设草稿的 `original_template_id` 恒为 0。整套主题采用回 `topicId`，不是模板 ID。把货架 ID 填进节点 `templateId` 形状闸不报错，**发布期才以“模板缺失”失败**——作者端两份 ID 分字段展示，不共用一个下拉。

### 21.2 预设列表与作者输入

`GET /api/preset/gameplay/list` 返回 `catalogVersion + presets[]`，每个含 `presetKey/version/sourceMark/title/purpose/category/capabilities/presentationSections/requiredFields/cityBinding{appliesAt,requiredRebinds}/authorTodos`。

- **没有答案、没有模板、没有题目正文**；不要试图前端“预填一份完整玩法”。
- `requiredFields[].kind` 决定控件：`text`→单行（`maxLength`）；`number`→数字（`min/max`）；`choice`→单选（显示文案作者填）；`order`→拖拽排序（全部项各一次）；`bin_map`→每项指派一个分类（全覆盖）。
- `authorTodos` 是作者还要自己做的事（上传并审核头像、补结局文案、绑定真实地点），用列表展示，不折叠成一行提示。
- 创建失败原样显示，不改写“保存失败”：一次列全缺项（如“预设还缺这些必填内容，补齐后才能建草稿：限时秒数（timerSeconds）、指认问题（question）”）；`预设没有声明这个参数`、`玩法预设不存在`、`不能修改已有模板的预设来源`、`预设草稿不能同时声明市场来源` 均按原文处理。前端不静默丢弃未声明键。
- `requestId`（8–64 位 `[A-Za-z0-9._:-]`）同一作者同号只建一次；超时重试不提示“已新建”，看回执 `writeReceipt.state`。
- 作者列表 `templateSource` 三档：`预设起头` / `自主创建` / `引用模板`（或 `引用已下架`）；**不是权限或可售判据**。
- 新建草稿 `draftStatus=0/status=0/publishStatus=0`；编辑不回流预设目录、不影响别人；预设升版（`sourceMark@N` 变大）不改已建草稿，**不做静默升级**；要新版本重新建一份。

### 21.3 采用闭环（T3）

- **不可采用：** `adoption-source` 404「源模板不存在或已下架」；`adopt-draft` 回 `REJECTED + SOURCE_VERSION_CONFLICT`。列表中不出现；旧深链进来提示「该玩法暂不可采用」，**不要**提示“版本冲突”。
- **可采用：** `adoption-source` 回 `sourceVersion`（64 位哈希）→ 显示「采用」；采用完成回执 `draftId ≠ 来源 ID` → 直接跳进自己那份草稿。
- 同 `requestId` 重试只回原草稿，超时不要提示“已新建一份”。
- **待填显眼：** 标题/正文/答案/反馈里的 `待填：…` 是占位，用同一套「待填」高亮；`publishBlockers` 是发布前置（待填替换、城市重绑、媒体已产出、坐标已确认）。**后端没有机械的“待填”文本拦截**（已列入交接缺口），端上这层提示不能省。
- **换城必须重绑五项：** 节点 ID、地点、现场观察事实、答案、照片（+开放时段）；不得把原城市的这五项带过去；后端会在发布期以「外来节点」「备用点不属于本主题」「请先在章节里设置该站点的到达地点」拒绝，**原样展示**，不改写“保存失败”。
- **采用后：** 改自己副本不回流；来源改版不追旧（要新内容按新 `sourceVersion` 重新采用，产生新 `draftId`）；出身不可改（不能把 `originalTemplateId` 换成另一个来源）。
- **指认/答题（最终口径）：** 普通问答与排序必须免费重答（`qa.maxTries=0`、`sort.maxAttempts=0`）；填有限次数被发布闸拒。答案不下发；配图只支持 `sort.items[].img` 与 `qa.options[].img`（成对 `alt`），`classify.items` 无配图。`accusation` 是呈现段，必须挂在已启用 `qa(PICK)` 上，不自己产生完成证明。**`earlyAccuse.allowed=true` 可以保存**（T3 早期片段说“被保存闸拒”是过期结论）；运行期按真实进度判定，读不到进度整笔回滚「结果待确认」。
- **状态：** T3F 15 文件已进 G-PACK；`preset_source` 新列 migration **未执行**；真实预设草稿写入/主题绑定/发布冻结 **NOT_RUN**；四类题材仍是 `MAPPING_READY`（正文/人物/谜底/地点/照片待填）。

---

## 二十二 全局状态矩阵与 blocker（2026-11-08 UTC）

### 22.1 状态矩阵

| 域 | 源码/真实 caller | 运行证据 | 端/启用状态 |
|---|---|---|---|
| 原第2–8项 | 已推 `962ae9cf`，草稿 PR #731 | VPS 编译与 90 类/875 例全过；MySQL/HTTP/真机未验 | UI 未改；未合并/部署 |
| 新提示/整局N/回执/跳过/猜词/指认/预设 | G95 定稿，主控逐文件 SHA 全匹配，真实 caller 已接 | 新包全部 NOT_RUN | UI 未改；不可标上线 |
| 商品/通行证/礼品 | M402纳入M3-F4/M2F5；主入口、多主题礼品与已付席位快照源码已修 | 新包全 NOT_RUN | 按服务端可售性，不按旧档位整类禁用 |
| 团体/学校 | MG 真实活动票→原 checkout；21 例测试源码 | 全 NOT_RUN | UI 未改 |
| 加座 | M402纳入MS-F2；独立增购＋gift，无Team容量限制已移除 | 全 NOT_RUN | 无 Team 绑定，不能写成入队 |
| 促销/报价/券 | MP-F2 定稿43，原主题促销可达、scope与限次闸源码修复 | 全 NOT_RUN | UI 未改；价格以新报价为准 |
| 归因/创作者收入/渠道冲销 | M402已含真实调用、lease fence及退款先到consumer修复 | 全 NOT_RUN；费用依据缺失待核 | 无打款；不能把待核当0 |
| 视频/小费 | 整组分摊与verified水位已修；新增组成员幂等键/逐稿窗口问题退修 | 全 NOT_RUN | 应计仅记录；小费通道未启用 |
| 目录/译文 | 作者PENDING、运营审校与CAS源码有；MDB-F2已补真库装置清理 | 全 NOT_RUN | SEO外部站点未发布 |
| OTA渠道 | adapter→原准入、已验真渠道退款与资金源源码有 | 全 NOT_RUN；migration未执行 | 真实provider未配置 |
| 新权限 | 只有权限串，无角色seed | 未做角色验收 | 现有超管通配可用；普通角色无授权403 |

### 22.2 当前剩余与阻塞

1. G95 与营销最终包仍须集成、编译、单测及真实 MySQL 门禁；原875通过不能覆盖新包。
2. M402已收口成交席位快照、加座分支、视频整组分摊/verified水位和退款先到冲销；仍须真实编译与测试。当前具体退修是视频成员变动但证据最大ID不变时的重算、各投稿独立窗口，以及提示从正式冻结节点配置读取的应用链回归。
3. M3-F4、M2F4、MP-F2、M8F3 已有定稿交付；不再将“M3没有manifest”列成阻塞。新修复必须刷新后像清单，不能回退已授权修改。
4. 新营销真库IT已有9类/54条源码（MDB-F2），真实consumer、租约栅栏、渠道类型隔离及组证据水位负例已写，由原CI入口执行；不能以Python静态检查代替MySQL执行。
5. 真实渠道provider、视频兑付、小费通道与费用依据仍按实际配置决定；未知不填0，也不宣称真实到账。
6. 首次使用型、普通②退款沿原履约/退款链，不新增虚构政策码；100% off无真实支付事实时保持明确拒绝，不静默按原价成交。
7. 自玩无Team，不为加座扩活动Team或新增平行席位账本。UI必须区分“多一份准入”和“加入原队”。
8. 权限字符串不等于角色已授权；不自动seed菜单或给运营授权。
9. 内容合同：首批hint/accusation/hangman结构schema与共同JSON夹具已交候选，Java与客户端未运行；新增提示冻结应用回归仍在修。全量玩法schema与两端执行均未完成，原nodeId/field发布定位不等于全量合同完成。
10. 该次402阻塞后用户已充值，原Flash工作位已恢复活动，继续同一返工任务；不据活动状态标完成。


---

## 23 本轮补缺的端侧改动（候选源码，运行NOT_RUN）

### 23.1 作者保存、发布及版本

| 位置 | 需要修改 | 服务端合同与边界 |
|---|---|---|
| 发布按钮与未知结果恢复 | 保留本次`publishRequestId`，网络结果未知不生成新号重复发布；展示返回的修订身份 | BE-06增`contentRevisionId/contentRevisionNo/contentSchemaVersion/contentSha256`；空值是旧内容，无需伪造1。真实发布入口重放仍在F1修复，最终参数以集成接口为准 |
| 文字答案玩法配置 | 可填写明确可接受的别名；保留原标准答案与成功/失败反馈 | 私有`advancedConfigJson.answerPolicy`，具体字段按BE-08合同；不在公开模板/预览里显示别名，不做模糊匹配/拼音/AI判题。未配置沿用各旧路径比较规则 |
| 地点与城市配置 | 增可选国家ISO码，缺失保持未填写 | `contentContract.placeCountryCode`须明确填写、ISO两位大写；不默认CN，不从用户定位推断。城市沿已有作者城市码，发布与开局冻结后不被新编辑改写历史归属 |
| 节点到达设置 | 在原地点条件下配置可选半径、精度上限、定位时效；下方加可选到达叙事`level/log` | 存于场景`nodeConfigs[真实nodeId]`，包含`schemaVersion:1`；`arrivalPolicy{version:1,radiusMeters,maxAccuracyMeters,maxAgeSeconds,fallbackMode}`、`arrivalReading{level:0..3,log}`。没有配置继续旧50m/80m、无时效要求；不得以精度差放大半径 |

国家/城市冻结链仍在BE06-F1收口，线性/活动局的冻结覆盖未证实；不得仅凭配置编辑器有字段就宣称所有历史局已归入国家/城市榜。

### 23.2 玩家到达与叙事

- `/api/play/arrive`增可选`locationTimestamp`（定位采样时间，epoch毫秒字符串）；只有冻结政策要求时效才校验。旧调用缺此字段不改变原指纹；结果未知重试仍保留原请求身份。
- 真实到达成功才展示返回的`data.arrivalReading{level,log}`；按原到达事实/回执去重播放，跳过不产生到达叙事，不把`level`换算成奖励。
- 被拒时保留原`errorCode/retryable/msg`，新增`arrivalReasonCode/canRetry/approvedFallbacks`。替代项只认服务端已经批准的真实节点；作者填写`fallbackMode`不构成新的授权，客户端不得自行标记到达或跳站。
- `newActionIdRequired`及同号异payload冲突按原回执合同处理；重新定位形成新输入与原未知结果重放要分开，不能统一自动换号。

### 23.3 预取清单与模板交付信息

`GET /api/play/content-manifest`沿原登录、报名/准入及原局作用域校验，参数`activityId/topicId/revisionId`；取清单不建局、不记到达、不发奖。三种身份：`REVISION`（正式修订）、`RUN_SNAPSHOT`（本局冻结）、`ONLINE_LEGACY`（无可用修订清单的旧内容）。历史局不能拿当前发布内容覆盖；下载与游玩仍校验真实权益。

| 数据 | 端侧处理 |
|---|---|
| `contentSha256` | 是该修订的内容身份，来自服务端冻结内容；**不是可拿公开清单JSON重算的摘要**。除非接口另提供该公开表示的权威digest，不据此显示“公开包校验通过” |
| `assets[]` | 用稳定`assetKey`做缓存身份，刷新签名URL不换身份；`scope/priority`决定开始/路线/可选预取。`sha256/bytes/mime`缺失及`verified=false`保持“未校验”，不要伪造值 |
| `requiredCapabilities` | null表示未声明/无法推导，不能当成没有能力要求；明确缺失的必需能力沿已有兼容策略处理 |
| 清单可读与离线 | 有revision或`onlineRequired=false`都不证明完整离线可玩；该旗标语义待集成澄清。答案/到达/扣积分/发奖仍以服务端回执为准，旧内容显示联网要求 |

模板详情`/api/template/topic-template/info`增可选`contentDelivery`：修订、能力、地点需求、运营投入、证据级别、许可与端支持状态。仅在详情取修订，列表不逐行补N+1查询；没有权威信息不补默认结论。

- `operatingRequirementsVisibility=PARTNER_ONLY`仅显示受限状态，不把未下发的合作方数据还原或记成“作者没填”。
- `license.status=UNKNOWN`显示“许可信息未提供”；`evidenceLevel=ESTIMATED`不显示已验证，`platformExecution=UNKNOWN`不显示两端已可玩。
- 内容源语言仍`zh-CN`、`SINGLE_LOCALE`；营销目录的译文不代表玩法正文已多语言，不伪造翻译可用性。

### 23.4 新多作用域榜（不替换原玩法榜）

`GET /api/rank/list?scope=world|country|city|quest|club&scopeId=&period=week|all&limit=`。国家传明确ISO码、城市传作者城市码、主题/俱乐部传其真实ID；不固定`country=CN`，不由用户位置推导。俱乐部可见性由后端判定。

- 新榜`scoreBasis=COMPLETED_NODES`，显示“完成节点数”；不与积分钱包、`puzzle_score`、原定向`scoreCenti`或局内提示净分混加。原分支榜保留自己的结局优先规则，不用新榜替换。
- 直接显示服务端`rank`、并列名次和`me`，不按数组下标重排名；无有效对局不伪造0分名次。
- 周界与时间区间用服务器返回的`periodStart/periodEnd`（结束排他），不按手机时区另算。
- 城市/国家只认本局冻结`themeConfig.contentGeography`；旧局或未覆盖类型缺冻结值即UNKNOWN，以`coverage.unattributedRuns`说明未计入，不能用主题当前城市补位。
- world/quest/club与city/country分别按返回的`coverage.attributionBasis`解释；接口拒绝或归属未知时不静默切世界榜冒充所选榜。

OPS-01仅增加已有权限保护的内部指标读取，不新增本轮UI页面或客户端埋点需求。

**交付边界：** 本文件完成表示 UI 范围、字段、状态与验收要求已整理，**不表示**任何 UI 已改好，也**不表示**新游戏与商业营销包已编译/测试/上线。所有`SOURCE`只代表代码在；所有`NOT_RUN`须有对应源版本的实际回执才可改变；“未启用/未知”不得被界面文案绕过。

## 24 玩家闭环 P1–P9：统一落地清单

本节按用户提供的 Questo 流程与城瘾页面盘点新增；完整范围是 **9 项**，原参考稿末尾“7个页面”为计数笔误。本轮只实施后端和接口，**小程序、App、管理后台 UI 源码均暂不改**。P项是现有任务的页面闭环子项，不把已有73卡再重复计数。

### 24.1 实施范围、顺序与证据

- 首批展示顺序：**P7 战报 → P1 被邀请入口 → P4 玩法选择 → P5 票内席位**；P2/P3/P6/P8/P9的后端与合同同期补齐，UI团队按此优先级接入。
- 复用已有页面、组件和导航；一个流程可用现有详情中的区块或抽屉完成，不要求机械新增9个路由。以下入口名称为产品标识，**不是已经存在的源码路径**。
- 原文“无法付费”“邀请转化归零”“ARPU最高”等是排查假设，不能当成当前已测结论。来源为用户提供的页面/竞品流程盘点；本轮尚未实跑110个页面。
- 运行证据：P波次前G/M及六包生产源码编译通过；最终两处测试类型修复待统一复跑。P波次新增代码均未运行，客户端实现、真机、真实支付与实地仍未验。
- 后端分工：JOURNEY负责P4/P5/P7/P8/P9；MARKETING负责P1/P2/P3/P6；CONTRACT负责schema、冻结地理与工坊合同。实际请求路径及字段以本节最终接收表为准，未定端点不得擅自拼成生产请求。

| 编号 | 页面/区块 | 原施工卡映射 | 后端事实来源 | 客户端实施 |
|---|---|---|---|---|
| P1 | 推荐/赠送落地 | CO-05/07、MP-14、IOS-10 | 推广凭据、活动资格、礼品领取与商品目录 | 待UI团队 |
| P2 | 玩家推广中心 | CO-05/07/10、WEB-03 | 本人归因及分配明细、应计/冲销、实际提现能力 | 待UI团队 |
| P3 | 主题合集落地 | BE-13、WEB-05、MP-04 | 已公开内容目录、交付元数据、已审商品 | 待UI团队 |
| P4 | 玩法模式选择 | BE-07/12、MP-09/13、IOS-02/08 | 内容支持能力、既有个人/共享局与组队入口 | 待UI团队 |
| P5 | 票内席位报名/管理 | CO-01/02、MP-09/13、IOS-07/08 | 原订单冻结人数、RegistrationSeat、邀请/领取状态 | 待UI团队 |
| P6 | 城市包/时限通票商品 | CO-01/07、MP-09、IOS-07 | 既有营销商品目录、服务端报价、冻结覆盖与有效期 | 待UI团队 |
| P7 | 本局战报 | BE-12/14、CO-06、MP-14、IOS-10 | 本局完成/回执/计分/奖励事实，非管理后台cohort报表 | 待UI团队 |
| P8 | 本局同行回忆 | C08、MP-14、IOS-10、QA-03/05 | 同局授权成员、原有收藏/卡片和已同意媒体事实 | 待UI团队 |
| P9 | 单关/队内榜 | BE-14、MP-13/14、IOS-08/10 | 同作用域、同口径的真实榜单，原全局榜不改 | 待UI团队 |

### UI-P1 推荐/赠送落地

**入口与目标：** 分享链接或小程序分享卡打开后，先落到本次推荐对应的内容/商品，不丢失邀请上下文；登录成功回到同一对象。建议产品标识invite-landing，具体路由由客户端团队复用现有结构确定。

**页面内容：** 邀请人最小公开称呼、本次作品/商品、真实权益说明、有效期和一个主操作。只有后端证实赠送/优惠权益时才出现“朋友送你一局”或优惠文案；普通推荐只说明“朋友邀请你一起玩”。不展示手机号、邮箱、内部memberId或推广凭据全文。

**接口依赖：** 读取真实推荐/礼品凭据状态与可见商品；后续领取/报名使用原业务接口与requestId。打开链接只是读取，不自动领取、扣积分、支付、建局或确认归因。原始inviter参数不构成领券/赠送/佣金授权。

**状态与操作：** 未登录、可领取、已领取、本人重复进入、他人已领、过期、撤销、商品不可用分别显示；失败后可重试读取。领取超时保留原请求号，先查状态再重试，不能重新领一份；切账号重新判资格。

**验收：** ①正常邀请→登录→返回对应作品；②篡改/过期凭据无权益且不泄漏收件人；③同请求重复领取只发生一次；④普通分享不承诺免费；⑤App深链/小程序分享保留同一内容和上下文。

### UI-P2 玩家推广中心

**入口与目标：** 个人中心进入“邀请与收益”，承接P1的有效推荐；优先复用既有分享能力，不复用商家工作台权限。

**页面内容：** 本人可用的推广链接/分享卡、按真实口径计算的有效邀请及成交、待确认/已确认/已冲销/待核金额和明细。没有真实计数时显示“暂无可核实记录”，不把读取失败显示为0。

**接口依赖：** 本人范围的归因与收入只读接口，附来源、统计窗口/更新时间、币种及提现能力。应计、结算、实际到账三种事实分开展示；余额与退款冲销取原账本。链接使用服务端现有凭据，不由客户端拼用户ID生成奖励资格。

**状态与操作：** 无推广资格、暂无记录、费用待核、退款冲销、提现通道未启用分别处理。只有服务端明确有真实提现能力才显示可执行按钮；不能把商家提现API借给玩家，也不能显示假“可提现”。

**验收：** ①甲看不到乙的链接和收入；②退款后原明细反映冲销；③未知费用不显示为0成本或可提现；④分享可回到P1；⑤页面刷新不触发结算或发钱。

### UI-P3 主题合集落地

**入口与目标：** 外部投放、分享或目录卡直达一个主题/合集，再进入具体作品、活动或P6商品；复用现有目录和详情布局。

**页面内容：** 真实标题、封面、城市/语言、简明介绍、实际公开的样板关卡和对应报名入口。样板不足3个就展示实际数量，不补假关卡；评价、人数、完成次数等仅展示有来源与范围的事实，不能跨城市/时间混标。

**接口依赖：** 公开目录、已审交付元数据、商品可售状态和有限预览。目录语言、正文语言和缺译回退分别表达；上架不等于已实地核验。公开投影不能包含答案、完整付费路线、未审作品、内部授权材料或个人信息。

**状态与操作：** 无可售内容、售罄/下架、合集有内容但无商品、语言回退、读取失败分别呈现；主按钮始终落到实际作品/商品，不指向空报名页。

**验收：** ①直达链接无需先经过首页；②下架内容不出现在样板列表；③未知社会证明不造数；④免费预览不泄露付费正文；⑤主题/城市筛选和最终报名对象一致。

### UI-P4 玩法模式选择

**入口与目标：** 在实际开局前明确“协作共享”或“各自挑战”的玩法语义；可放在详情/报名确认步骤，不能仅把加入队伍邀请码当成模式选择。

**页面内容：** 只列本作品和当前客户端实际支持的选项，说明进度、提示额度、计分是共享还是独立，以及人数/席位要求。多人到场不等于共享局；共享提示与个人净分不得自行混搭。

**接口依赖：** 服务端支持模式、requiredCapabilities、当前run状态及既有开局/组队入口。能力不足显示原因；不得客户端改一个mode值就绕过服务端能力闸。单局开始后模式固定，切换必须依原业务允许的新局流程。

**状态与操作：** 只有一种模式时可直接呈现其规则并继续；已有进行中局优先“继续本局”。不支持竞争榜就不显示“各玩各的比名次”，不创建好友关系链或第二套游戏引擎。

**验收：** ①不支持的模式不可提交；②同局各成员看到相同共享规则；③提示实际扣减范围与页面一致；④重复点击不重复建局；⑤已开局不能静默改变模式。

### UI-P5 票内席位报名与管理

**入口与目标：** 从付款结果/我的订单进入“同行席位”；购买多张票、票内多人席位、额外加座、队伍成员分别表达。

**页面内容：** 原单冻结的包含人数及已用/邀请中/可用数量；每个席位的领取状态、邀请到期时间、脱敏领取人和当前允许操作。数量由服务器返回，不用当前SKU重新算旧票；不默认所有邀请7天有效。

**接口依赖：** 原席位查询/邀请/领取/释放/过期恢复接口与服务端状态、expiresAt、操作能力。额外加座调用原加座报价/支付，再回读实际新增席位；不能只改页面容量。买家保留财务/退款归属，领取人获得约定游玩资格。

**状态与操作：** 可邀请、等待领取、已领取、已过期、已释放、订单退款/撤销和正在履约时的限制分别显示。发错的待领邀请按原规则撤销；不能释放已在游玩的成员来腾位。所有写操作保留requestId，超时显示处理中并查权威状态；被撤销的旧链接不可恢复席位。

**验收：** ①不是订单管理者不能发/收回席位；②两人同时抢最后一位只成功一个；③过期/撤销链接不可领取；④重复请求不重复占位；⑤退款与加座后席位守恒；⑥买家与领取人切账号看到各自权限。

### UI-P6 城市包与时限通票 SKU

**入口与目标：** 主题合集、作品详情或完局推荐进入真实商品页，购买后明确“能玩哪些、从何时开始、何时到期”。

**页面内容：** 服务端商品类型、覆盖城市/作品清单、包含人数、激活条件、时区、期限、售价/积分价、折扣资格和退款责任。只展示已配置且可售的城市包/年卡/时限票；“季卡”不能只改文案却沿用另一有效期。

**接口依赖：** 原营销商品目录、服务端报价、原订单/支付/礼品领取/权益读回。价格展示以报价为准，确认时重算并处理变化；客户端不自行乘人数、拼折扣、延长优惠窗口。售出覆盖按原订单冻结，不自动纳入之后新增的关卡。

**状态与操作：** 已有权益、尚未激活、有效/到期、部分退款、礼品待领、支付未知分别处理。没有自动续费合同就只卖固定期限票；不加3天免费试用。支付渠道按SKU/市场能力下发，不自行绕过渠道限制。

**验收：** ①改本地价格不能影响成交；②旧订单不随当前商品扩容/改价；③到期边界以服务端和时区为准；④重复支付回执不重复出权益；⑤退款后覆盖及资格按原合同回读。

### UI-P7 本局战报

**入口与目标：** 真实结束回执后进入；“我的记录”可再次打开同一局。用一个战报容器承接总览、榜单、评价、分享，不照抄竞品8屏。

**页面内容：** 作品/城市、这一局的完成/提前结案/辅助跳过等真实结果、有效用时、完成节点、权威分数及提示扣减、同口径名次、实际获得的卡片/奖励。没有可靠评分基准显示“本局不计分”；未知数不显示成0分、未到账不显示为已到账。

**接口依赖：** 玩家本人有权读取的run报告及既有回执/奖励/榜单/分享API。管理后台cohort统计不能充当个人战报。读取报告不会补完局、发奖励、写进度或建局；runId、内容修订、局类型、scoreBasis和奖励状态保持一致。

**状态与操作：** 进行中显示进度，不显示成功结算；结果待确认时可刷新权威回执；奖励处理中单独显示。分享预览不带答案、私密线索、同行人手机号或精确私密路线；取消分享不影响结算。评价接原评论/审核能力，不新增一套评价系统。

**验收：** ①甲不能读取乙的私有战报；②退出/endedAt不冒充有效完成；③跳过/提示影响与回执一致；④名次缺失不伪造排名；⑤重复打开奖励不重复发；⑥分享卡与战报同一局。

### UI-P8 同行回忆

**入口与目标：** 战报“本次回忆”或我的游玩记录，按“这一局”组织，复用已有印章/物品卡和媒体能力。

**页面内容：** 本局日期/作品、获准展示的同行称呼、实际收藏、经授权可见的本局照片。默认私人；同局不代表自动同意互看全部照片，也不自动建立好友关系。

**接口依赖：** 同局访问资格、原收藏/分享快照、媒体隐私/同意和删除事实。没有可信本局媒体关联就显示暂无照片/能力不可用；不能把账号全部相册混进某一局。

**状态与操作：** 无同行、无收藏、照片已删除/撤回同意、成员退出或权限失效分别处理。公开分享是独立明确动作；发布失败/取消不能偷偷变公开。

**验收：** ①局A看不到局B私有内容；②其他成员私密照片不默认可见；③撤回/删除后重新进入不可继续访问；④公开分享过滤个人信息与私密线索；⑤回忆页不新增权益/进度写操作。

### UI-P9 单关与队内榜

**入口与目标：** 从战报或当前队伍进入；复用现有榜单组件，以清晰作用域切换展示。

**页面内容：** 作用域名称、作品/队伍、周/总时间窗、计分口径、本人名次及真实覆盖范围。现有BE14的COMPLETED_NODES显示“完成节点数”，不能标成净分榜；未知分数/无资格不补0混排。

**接口依赖：** 原作用域榜、当前队伍成员资格、冻结内容地理和真实有效游玩事实。队内榜仅当前获准范围；不做好友榜/关系链，不新增Crew/公会，不改变原全局积分/成长榜。

**状态与操作：** 无记录、未计分、队伍不可访问、国家/城市冻结信息不足分别显示；不能用用户当前定位/资料城市给历史局补地理。切榜只读，不重新报名或结算。

**验收：** ①非成员不能看私有队内数据；②同榜不混完成节点、积分、解谜净分；③退款/无效/预览局按既有资格排除；④历史未知地理不强塞某城市；⑤排名与P7的scope/period/basis一致。

### 24.2 客户端接线与验收交付要求

1. 每项记录实际修改文件、真实请求方法/路径、字段、权限、版本、异常状态和截图；不能只交设计图或示例JSON。
2. 小程序与App消费同一语义合同；成功/失败反馈、提示和模式配置不各加一套解释。缺能力时如实阻止/回退，不默认跳过必需内容。
3. 首批四项连跑：真实完成→P7→分享→P1新账号承接→P4支持模式→P5真实席位→返回同一局。用实际返回ID并独立回读，不注入假进度、假支付或假邀请成功。
4. P2/P3/P6/P8/P9各跑至少一条正常、一条越权、一条过期/失效、一条读取或提交结果未知恢复；已有权限规则不为页面演示放宽。
5. UI实现、后端编译/单测、真库/接口、真机/现场、渠道支付与生产发布分栏签收；当前页面均“待UI实施”。

### 24.3 后端接收表与真实请求合同（2026-11-08）

**当前状态（2026-11-08 UTC）：** P1/P2落地、P4/P5/P7/P8玩家闭环、P9队内成员名次及营销源码均已在当前候选树；P5有13个源码/SQL文件变更及7个MySQL IT用例，P9增加队内成员榜。当前候选353个生产源码和2,423个测试源码编译通过；选定138类/1,768例通过（0失败、0错误、4跳过），另有提示额度专项33例通过。全量测试套件、MySQL、HTTP和最终CI尚未完成。P5 MySQL IT已加入登记，marketing-db-acceptance为13类/82项；登记校验92项只证明清单配置正确。**P4整局免费提示额度已实现：** 同一服务端核验的团队游戏局共用N次免费额度，个人路线仍独立；提示费用仍记到实际使用者；聚焦测试通过。它不代表全队计时、跳过额度、解谜分都共享。所有接口仍是源码合同，未部署、未执行迁移。

所有响应沿用AjaxResult的code/msg/data；身份来自登录态，不能由参数伪造。下面的POST只读落地接口是查询动作，不能因使用POST就认为它会领取或建单。

| 子卡 | 方法与实际路径 | 请求/关键返回 | 当前证据 |
|---|---|---|---|
| P1推荐 | POST /api/referral/landing/referral | JSON {token}；state、inviter的nickname/avatar、target、expiresAt、registrationState、freeSelfPlay、nextAction | 源码+回归源码；未运行 |
| P1礼品 | POST /api/referral/landing/gift | JSON {token}；state、product、claimExpiresAt、recipientBound；不带买家/订单/领取人身份 | 源码+回归源码；未运行 |
| P2 | GET /api/marketing/affiliate/summary?limit=20 | credentials、campaigns、allocations、held、payout；无明文token | 源码+单测+真实SQL测试源码；未运行 |
| P2生成链接 | POST /api/marketing/referral/token | 复用原签发；targetType=1或2、targetId、campaignKey/ttlSeconds按原合同；token仅签发返回 | 原链路复用，待统一验证 |
| P3目录 | POST /api/public-catalog/city/list、/list、/map、/ids、/info、/filters | city_code/locale/keyword/product_type等按原控制器；/info用id与locale、/ids用ids与locale；普通参数，不擅自改JSON body | 原目录复用；不是新增CMS |
| P6商品 | GET /api/marketing/product/list；GET /api/marketing/product/{productId} | 实际商品与冻结覆盖；list参数city/kind/limit（默认20） | 原M402源码复用，待验 |
| P6报价/建单 | POST /api/marketing/product/quote、/order | 原JSON DTO；金额、资格、折扣、覆盖、人数均以服务端结果为准 | 原M402源码复用，待验 |
| P6后续 | GET /api/marketing/product/orders；POST /orders/{orderNo}/pay、/cancel；POST /activate?topicId= | 保留原订单/支付/激活合同；支付未知先查权威状态 | 原链路复用，待验 |
| P6礼品领取 | POST /api/marketing/product/gifts/claim | JSON {token,requestId}；GET /gifts/bought、/gifts/received读本人记录 | 原礼品链路复用，待验 |
| P4模式 | GET /api/play/player-journey/choices?activityId=或topicId= | choices、configState、runtimeScope、worldMode、routeMode、requiredCapabilities、teamModeSource | 新源码+回归源码；未运行 |
| P5兼容摘要 | GET /api/play/player-journey/seats?registrationId= | 原单N/核销事实+新的邀请摘要；动作走独立邀请接口 | 源码已交付；未运行 |
| P5邀请/领取 | POST /api/play/seat-invitations/invite；POST /api/play/seat-invitations/claim | invite JSON {registrationId,seatNo?,requestId}；claim JSON {token,requestId} | 源码已交付；未运行 |
| P5查询 | GET /api/play/seat-invitations?registrationId=；GET /api/play/seat-invitations/{invitationId} | 原购票人列表；单条仅买家或已领本人；均只读 | 源码已交付；未运行 |
| P5撤销/释放 | POST /api/play/seat-invitations/{invitationId}/cancel、/release | JSON {requestId}；只能操作原范围，已使用/核销时拒绝释放 | 源码已交付；检查实际游玩事实，未运行 |
| P7战报 | GET /api/play/player-journey/report?activityId=或topicId=&runId=可选 | scopeKind/runId/playInstanceId、progressScope、runState、score、rank、elapsedSource、rewardsScope | 新源码+历史局/冻结回归源码；未运行 |
| P8回忆 | GET /api/play/player-journey/memory?activityId=或topicId=&runId=可选 | 同一run绑定；本人moments、participantCount、identityPolicy、photoShareCapability | 新源码+隐私/历史局回归源码；未运行 |
| P7/P8历史入口 | GET /api/play/theme/history?topicId=&beforeSessionId=&limit= | sessionId作为上面runId，重新打开原历史局 | 复用现有历史接口，待验 |
| P9单关 | P7响应data.rank；已有作用域榜另见第23节 | scope=quest，period=all，scoreBasis=COMPLETED_NODES；不是净分榜 | 新读面复用原榜；未运行 |
| P9队伍之间榜 | GET /api/orient/team/ranking?ownerType=1或2&ownerId= | rank/teamId/title/activeCount/teamDoneCount/totalTasks/mine | 已有源码；不是队内成员名次 |
| P9队内成员 | GET /api/orient/team/detail?teamId= | members[].doneTaskIds/doneTaskCount/rank/ranked/mine；顶层rankBasis/rankScope/rankedMemberCount | 源码与回归测试已交付；未运行 |

#### 接线时必须遵守的返回语义

**P1/P2：** 推荐不是自动赠送。VALID的推荐仅允许查看真实目标；registrationState=UNKNOWN时由原报价/报名重新判断，不能画“已获免费/保证可报名”。免费自玩和在售是独立事实：freeSelfPlay=true的免费入口不能因NOT_ON_SALE被封死。礼品CLAIMED/过期/撤销不显示重新领取。P2的credentials不含原token；需要分享时调用原签发命令，读取页面不签发。pending/confirmed/paid/reversed分别为应计、可结算、已标付款、冲销，不能统称已到账；held.amountFen=null，不编待结金额；payout.available=false时不显示可提现/提现按钮。allocations中同一笔分配只计一次：部分冲销与全额冲销合并展示仍保留真实总笔数。amountUnavailableReason=MULTIPLE_CURRENCIES时，金额分值与展示金额均为null、currency为null，显示“多币种暂不合计”，不得拼成一个收益总额或补0。部分退款在冲销明细反映，原campaign退款汇总只按原整单退款口径，不能把它宣称为全部部分退款统计。

**P4：** configState=FROZEN_RUN使用本局冻结配置；PUBLISHED使用当前已发布配置；UNAVAILABLE及RUN_CONFIG_SNAPSHOT_MISSING返回空choices，不能借当前发布给老局换规则。teamModeSource=CURRENT_TOPIC_COLUMN只说明旧团队开关来自当前主题列，不得声称此列也已冻结。只有实际支持的模式可选；提示和计分仍是PER_MEMBER时不能画成全队共享扣减。

**P5：** 原N由cms_registration.order_num冻结，买家第1位，最多邀请第2..N位。默认TTL168小时可配置，并受原票/活动期限截断，页面只读expiresAt。PENDING可撤销；CLAIMED仅后端能确认未使用、未核销时可释放；当前源码只查进行中路线局的保护已退修，已结束局/其他游戏会话/开始与释放并发仍需补证，不得据此开放可用按钮；过期/撤销/释放的旧码失效。free=(N-1)-pending-claimed，终态历史不占名额。领取不生成新的已付单，买家金额与退款归属不变，也不会自动加入队伍。token仅签发及同请求重放返回，查询不返明文；同一发起人的同requestId同内容重放，不同内容冲突。不同账号拥有独立请求号空间，不能借他人的请求回执读数据。看列表/状态不写过期；过期仅投影，实际状态在授权命令持锁时处理。可玩准入仍由原入口再次核验，不能仅凭页面的CLAIMED或originalPaid布尔跳过资格检查。新迁移仍未执行，不能把写入口标已可用。

**P7/P8：** 指定runId读那一局；不传读当前局；显式局不存在或不匹配返回RUN_NOT_FOUND/RUN_SCOPE_MISMATCH，不静默切最新。LEGACY_TOPIC_SCOPE只是旧主题历史，不是单局；UNATTRIBUTED_RUN不补当前内容。历史局没有自己的用时事实则elapsedSource=UNAVAILABLE_OTHER_RUN，显示未知；rewardsScope=TOPIC_ACTIVITY是原主题/活动背包，不标“本局新获得”。rank是主题完成节点数，不能和解谜净分混排。P8仅本人完成节点与本人照片；NO_PRIVATE_PHOTO_SHARE_CONSENT_FACT时不展示同行私照。

**P9：** 队伍间榜teamDoneCount依团队任务阈值计算，和队内每名成员完成数是两张不同的榜。现有成员榜进度数据需active成员权限且受orient.team.board.enabled开关约束；关闭/非定向内容/非成员时隐藏或说明，不造排名。待补成员名次按COMPLETED_TASK_COUNT独立标识，未完任务者不补第一名；没有实现前UI不伪装成可用名次。

#### 客户端与后台交付边界

- 新后端接口均沿用登录态或控制器既有Anonymous规则；客户端不能以匿名页可以读取为由直接调用受保护的领取、收入或席位命令。
- 作者/运营的content-landing写接口只服务有权限的作者与审核，不是玩家公开页面的数据源。
- App/小程序分享URL或深链方案尚由客户端团队绑定；服务器已给出真实目标与凭据状态，但没有指定某个已存在的invite-landing源码路由。
- 本节是root归并后的唯一接线表；临时worker的UI-input、旧桌面包和旧交接稿不能覆盖本节。Flash工作位已恢复；本节合同与运行结果以当前候选复验和最终CI回执为准。

### 24.4 v3.8 当前验收状态

- 当前生产源码编译：353 个 admin Java 源码，PASS（批准 VPS 离线构建）。
- 提示与整局免费额度聚焦测试：33 个测试方法，0 失败/错误/跳过，PASS；不覆盖全量营销、P1–P9或数据库并发。
- 测试源码编译：2,423个源码通过（VPS离线容器，临时`-Xmx1024m`配置）；选定138类/1,768例执行，0失败、0错误、4跳过。完整测试套件尚未执行。
- P2、P5、P9及营销新代码：选定候选测试已随138类批次执行；完整测试套件与 MySQL IT 待 GitHub-hosted CI。13类/82项登记、92项清单自检不等于真实数据库执行。
- 未执行迁移、HTTP、支付/provider调用、真机/实地验收或生产操作。当前仅源码候选，不是已部署后端。

## 25 最新主文档六张页面卡与玩家闭环对账（2026-11-08）

主文档新增MP-15…MP-20及附录P（Android流程截图来源）；它们与第24节不是两套页面。截图为用户研究来源，本文未重新操作竞品。图片包含账号信息，不随本包分发。主文档的竞品文案与提案字段不能直接冒充当前服务端合同；实际接线以第24.3节及后续真实验证回执为准。

| 主卡 | 唯一UI清单落点 | 实施限制 |
|---|---|---|
| MP-15邀请落地 | UI-P1、UI-P5 | 推荐、礼品、票内席位是不同凭据；打开链接不自动入队/领票；未配置权益不得写送一局或固定奖励 |
| MP-16推广中心 | UI-P2 | 玩家只读归因/应计，不借商家提现通道；首关完成奖励属于竞品模式，不能把成交分佣假称首关奖励 |
| MP-17主题专题 | UI-P3 | 实际公开目录/已审投影；无可信剩余数/评分就隐藏；无订阅能力不画“到货提醒”成功 |
| MP-18模式选择 | UI-P4 | 只展示服务端实际支持模式；不得客户端自造solo/together/race后直接传入原开局 |
| MP-19战报 | UI-P7、UI-P8、UI-P9 | 一个战报容器+榜单/评价弹层，复用分享卡；进度查看与正式完局结算分开，不把退出画成成功 |
| MP-20城市通票 | UI-P6 | 固定覆盖与退款规则取成交快照；不硬写“已用不退”；不新增自动续费 |

### 25.1 共享提示额度与完整合作模式的边界

竞品一起玩规则是“第一个答对，全队推进；提示/跳过/计时共享；全队一个分数”。城瘾当前仅完成其中的**整局免费提示额度共用**：用服务端核实的同一团队局身份作为额度范围，授权成员跨节点消耗同一N池；个人局仍按个人范围；重看和同请求重放不重复扣额度，付费仍记到实际使用者。该路径的33个聚焦测试已通过。

**未实现/未验证：** 全队共享计时、共享跳过额度和全队单一解谜分/排行榜语义。本次代码也未声称实现以上规则；MySQL并发最后一次额度测试、完整测试套件和离线恢复尚未通过最终 CI。客户端可以按后端响应接入提示额度字段，但在相应服务端能力没有明确下发并验收前，不得展示“全队共用计时/跳过/一个分数”模式，也不能凭“组队”二字改变现有计分范围。不得另造钱包、平行游戏引擎或客户端权威结算。

### 25.2 并入原卡的端侧细节

六张主卡的端侧实现都并入原 UI 任务，没有另开页面卡。2026-11-09 波次的落点（详见 §28）：

| 主卡 | 原卡 | 实施工位 | 端侧细节 |
|---|---|---|---|
| MP-15 邀请落地 | UI-P1、UI-P5 | qx-b3 | 推荐、礼物、同行三种落地分别读各自凭据；席位领取只在服务端报告可写时显示 |
| MP-16 推广中心 | UI-P2 | qx-b3 | 只读归因与应计，不出现提现 |
| MP-17 主题专题 | UI-P3 | qx-b8 | 只用公开目录投影；没有可信剩余数和评分就不显示；不画到货提醒成功 |
| MP-18 模式选择 | UI-P4 | qx-b3 | 只列服务端下发的模式，暂时只放在落地页，详情页未放 |
| MP-19 战报 | UI-P7、UI-P8、UI-P9 | qx-b3 | 一个战报容器加榜单和回忆弹层，复用分享卡；进度查看和正式完局分开 |
| MP-20 城市通票 | UI-P6 | qx-b5 | 覆盖与退款规则取成交快照，不写死“已用不退” |

同时并入的端侧合同同步（小程序合同分片）：

- **默认内嵌名单补 `hangman`**（UG-01）：客户端 `pages/publish/utils/publish/advanced-game-config.js` 的默认内嵌玩法段名单缺少服务端 `PRESENT_SECTIONS` 已有的 `hangman`，分片 20/20 的“默认呈现和内嵌限制与实际服务端真源一致”判红。已在 `a0824a43c` 补齐。
- **根层 `hintLadder` 不按玩法段核对**（UG-05、UG-11）：`hintLadder` 是根层提示阶梯，不是玩法段。分片 14/20 的“后端 Validator 段名单与本地 SECTIONS 一一对上”把它当成缺段。已在 `a0824a43c` 的名册测试里注明并排除。
- **玩法 GET 守卫见证对齐礼品与受让席位**（UI-P1、UI-P5）：见 §25.3。
- **提示 GET 入权限矩阵**（UG-05..07）：`GET /api/play/help`、`GET /api/play/help/receipt` 两条登记在 qx-b1（`151a544d5`）上，随该 PR 合入。

### 25.3 当前 CI 指出的客户端合同同步（run `42553085609`，SHA `47cb972e773307aa93c55589adea8e871b55a362`）

该次 CI 的小程序部分有两个分片判红，`Mini program static checks` 只是汇总这两个结果：

| 分片 | 失败用例 | 原因 | 修复 |
|---|---|---|---|
| 20/20 | #447 偏好题组与新生活 OS GET 绑定当前会员及其游玩/完成事实；#448 排行榜 GET 绑定当前会员及本场报名支付或自玩通行证；#449 玩法 GET 真实身份和 owner 守卫被改动时合同必须判红 | 后端把玩法准入入口改成带礼品和席位受让人事实的新调用形状（`CurrentPlayRegistration.require/requireToContinue`、`PlayNodeAccessService.resolveCtx`、`PlayCompletionFacts.isRegistered → hasSeatRecipientFact`），合同测试的见证还是旧形状 | C00 鉴权合同改动 `24f5021ab`（业主 2026-11-09 批准，只改 C00 保留文件 `get-auth-matrix-contract.test.js`） |
| 20/20 | #643 默认呈现和内嵌限制与实际服务端真源一致 | 客户端默认内嵌名单缺 `hangman` | `a0824a43c` |
| 14/20 | #3 后端 Validator 的段名单与本地 SECTIONS 一一对上 | 根层 `hintLadder` 被当成玩法段 | `a0824a43c` |

C00 鉴权合同改动的内容：

- 值校验改在 `passesValueRules` 中断言，入口改为新调用形状。
- 新增正向合同：礼品事实 SQL 绑定当前会员（买家或领取人）和同一主题，只取待领取或已领取；席位事实 SQL 绑定受让人、同一活动或主题、已领取、原单已报名已支付；锁内复核比对邀请行的受让人、原单和归属；两类事实查询都只读。
- 新增负控：去掉会员绑定、去掉场次绑定、放入撤销/取消/过期状态、去掉只读、入口不传席位事实，合同都必须判红。

复验：CI `42717309700`（候选 `24f5021a`，合并检出 `025e4cba`）20 个小程序分片和小程序静态检查全部通过。该次 CI 仍然红，原因不在客户端：`Backend tests` 的测试本身 0 失败，最终被架构门禁 `EXACT_STORAGE_PORT_CONSUMER`（`ApiPresetController → AuthorWriteReceiptService` 未登记为允许的 consumer）拦住；`Fast gates` 是同一架构门禁。登记见草稿 [#748](https://github.com/liboyang42-cpu/hengyin/pull/748)。


## 26 用户 1A/2A/3A 后端增量交接

本节属于 `2af68e1b` 之后的新施工波次。各项源码交付和真实测试分别记录，旧 CI 不覆盖本节。

### 26.1 完整合作玩法

仅供 root 归并主 UI 清单；无 UI/Swift/小程序/Vue 源码修改。本文件替代上轮“choices/facilityKey 全局拒绝、外部 QUEST_RESOLVE 不可用”的候选限制。

#### 新版本与正常入口

原合法主题配置中显式冻结 `themeConfig.gameplayRuntime.cooperationVersion=1`、`runtimeScope=TEAM`，保留原 worldMode、acts、quests、facilities、choices、费用与奖励合同；不自动升级旧 TEAM/个人局。保存/发布继续原 ThemeRuntimeConfig/GameplayRuntimeConfig 校验，没有新增独立 publish-check issue code/fieldPath。

既有 compatibility.requiredCapabilities 增加 `{key:"game.teamCooperation",minVersion:1}`；新端每位玩家先用下述 VIEW 声明能力。未声明旧端只读 compatibility 仍 UNKNOWN/null，新规则写入单独要求显式声明；不能由客户端自证 owner、支付、答案或完成。

从服务端取真实 activityId/gameSessionId/nodeId/runtimeInstance/authorityEpoch；runtimeInstance 为当前题实际 QUEST_START 实例，不凭 teamId 拼局。原 QUEST_START、到达、队长、活动窗口与费用选择规则保留。

#### 帮助入口（沿登录态与 AjaxResult）

`POST /api/play/help/shared`：

```json
{
  "activityId": 70,
  "gameSessionId": 50,
  "nodeId": 11,
  "runtimeInstance": "服务端原题实例",
  "authorityEpoch": 1,
  "capabilities": "game.teamCooperation@1",
  "operation": "VIEW"
}
```

示例数字不是实际可用业务 ID。VIEW 返回 levels、remainingFreeUses、cooperation；首次为能力协商。启动时已绑定真实到达/扫码/工作人员验真 receipt 的题，在 VIEW 协商后可据原 proof 自动结算或进入待确认；不接受 passed/score。

USE 另带 level/requestId，返回获准正文 content、alreadyUnlocked、本次 chargedPoints；全队同层一次解锁，其他人重看不扣。只有本人实际首用时有本人 balanceAfter，不透出队友余额。免费 N 整局同队共池；零价层不吃免费份，付费扣实际使用者现有积分。积分与局内解谜分分开。

SKIP 带 requestId；作者冻结配置允许且不是现实/设施证明依赖时，全队辅助完成、puzzleScore=0，不伪造答对/到达/扫码/照片/核销/支付。受限层 VIEW 标 usable=false/reason=REAL_OPERATION_REQUIRED。普通配包题的辅助 skip 不发配包物资。实际高级 TEAM 子会话同步 SKIPPED 终态。

答对待确认后不能新买提示或改成跳过；已解锁层仍能重看。未知请求只按原身份/requestId 重试；同号异动作冲突，不能换新号猜结果。cancel/restart 保留同一冻结 quest 的帮助解锁，免费池不重开。新合作局不能回旧 advanced help/skip 路径形成第二份个人收费。

#### 答对后的三种状态

- 无需选择/容量/实际操作确认：原事务自动结算，原真实完成槽成立后才推进、发原物资和计局分。
- WAITING_CONFIRMATION：真实答对已提交，常见 reason 为 CHOICE_REQUIRED/CAPACITY_CONFIRMATION_REQUIRED/FACILITY_CONFIRMATION_REQUIRED/COST_CONFIRMATION_REQUIRED；展示原配包/装载确认输入，不能显示已最终完成或先加分。
- WAITING_OPERATION：缺原 operationNodeId 的实际证明，或需真实参与集合/方案；沿原 OPERATION_START、真实判定、FACILITY_USE/PLAN_EXECUTE、参与集合/提案/投票/出资步骤，不能代选、假签或伪造通过。

原 epoch/binding 变化逐题 UNAVAILABLE/ORIGINAL_AUTHORITY_CHANGED，明确取消为 CANCELLED；刷新原状态，不能把旧答案/预览搬到新局或新实例。

#### 原显式确认 caller 已恢复

对当前真实待确认题，原 `sharedPreview(action=QUEST_RESOLVE)` 和原共享局 `command(action=QUEST_RESOLVE)` payload 保留 questKey/choiceKey/discardRewards/previewVersion/costOption，新增数值 `answerRevision` 和原题 `runtimeInstance`：

```json
{
  "questKey": "服务端原questKey",
  "runtimeInstance": "原题QUEST_START实例",
  "answerRevision": 12,
  "choiceKey": "玩家从作者既有选项真实选择的key",
  "discardRewards": [],
  "previewVersion": "本次实际预览回包的版本"
}
```

外层仍带原 requestId/expectedRevision/authorityEpoch。先按原预览入口发送同一选择和 discardRewards（不带 previewVersion），再用实际回包版本确认；无配包则不传 choiceKey，费用组沿 QUEST_START 原冻结选择。原预览、库存/奖励、当前队长、真实 proof 检查全部保留。所有成员可答题和读同一待确认；显式资源确认仍遵守原当前队长规则。

预览过期/库存变动的确认拒绝仅回滚该次确认，已提交答案仍在；刷新后重做真实预览。确认未知必须保留原 expectedRevision/requestId/payload；新 expectedRevision 不属于同一次请求。重复原确认不重发奖励，换号同原选择也不重结算；不能更换已落地配包/放弃清单。

绑定设施的独立操作节点不同于题目节点时，后端允许该真实待确认任务进入其原绑定设施 OPERATION_START，保留题目答案并等待真实 operationFacts，再由 QUEST_RESOLVE 同事务消费；不会直接独立执行 FACILITY_USE 来双份发奖。同节点已经通过的题直接确认，不重新开始操作抹掉通过事实。

参与集合设施/非终局方案由服务端 questions[].confirmation 给出原 action=FACILITY_USE/PLAN_EXECUTE 与 requiredOperation 指引；不得自动执行第一个指引，作者配置的其他合法设施/方案仍由原工具/世界清单供玩家实际选择，后台继续核验原动作。沿原 participants/proposalKey/discardRewards/previewVersion 输入。它们不改走普通 QUEST_RESOLVE，不由客户端选全队名单或假定其他队已通过；只有原引擎实际完成槽后更新合作完成/分数。

#### 共享投影

原共享局 player 回包和 shared 帮助回包的 cooperation 包含 version/gameSessionId/authorityEpoch/serverTime/startedAt/elapsedMillis/puzzleScore/scoreStatus/questions。

各题带 runtimeInstance/nodeId/questKey/sourceNodeId/bindingVersion、startedAt/elapsedMillis；真实通过后带 answeredAt/answerReceiptId/answerRevision/answerOwnerRevision/answerAuthorityEpoch、confirmationStatus/reason 与 confirmation（原 action、所需 inputs、原实例/答对版本、当前 expectedRevision、requiresCurrentLeader、必要 requiredOperation）。实际完成后才带 completedAt/completionMode/assisted/puzzleScore。

计时统一按服务端事实，暂停冻结并在恢复扣除暂停段；客户端只做显示补间，不提交计时/分数。谜题局分与现有可兑换积分/成长积分榜分开；普通文字/选项沿现有 100/70/40，揭示/跳过为 0，原没有权威解谜基准的玩法保持 NOT_SCORED/null，不补 100 或未知填 0。帮助使用明细不含私有余额或未解锁正文。旧响应仍按原 revision/ownerRevision + 局/题/epoch 隔离。

#### 证据边界

当前仅后端源码和测试源码候选；编译、29 新测试、真实 MySQL/HTTP/钱包、端侧与真机均未验。没有付费 provider/发布/生产完成证明。root 统一验证后再把状态写入主清单。

### 26.2 付费玩法模板

状态：SOURCE / NOT_RUN。后端源码候选不代表渠道、数据库、HTTP、App或真实支付验收；完整2A仍为PARTIAL。

#### 支付接入边界

- 本轮唯一实现适配是既有微信移动应用支付 `WECHAT_APP`，复用原 GenericApp 网关、原 `wx_pay_order_intent`、验签/原单查询后的 `wx_verified_payment_fact`，不等同 Apple IAP。
- `workshop.wechat-app-enabled` 默认 `false`，且原支付服务必须 `isAppReady()`。本轮没有修改配置、开通渠道或启用收费；未配置时创建订单及发起支付均明确返回 `CHANNEL_NOT_CONFIGURED`。
- Apple IAP **服务端仍未实现**：App Store交易/产品/金额/币种/环境/应用身份校验、账户及购买版本绑定、服务器通知验签与去重、退款/撤销及恢复购买对账均仍需后端实现和渠道资料；不能由App自行验签后声称服务端已经接通。
- App只请求订单和支付参数，支付SDK成功仅触发查询 `/v2/purchase/reconcile`。许可只由原验签回调或服务端原单查询得到的不可变事实激活；没有接收客户端 `paid`、IAP回执或手工交易号授予权益的接口。

#### 兼容升级

`GET /api/workshop/capabilities` 返回 `schema=workshop-capabilities-v2`、`contractVersion=2`、`privateDraftInstall=true`、`executionGranted=false`、`wechatApp={available,reason}`、`appleIap={available:false,reason:SERVER_VERIFICATION_UNAVAILABLE}`、`legacyAction=UPGRADE_REQUIRED`。

旧 Swift 硬断言的 `contentUseStatus=PAID_INSTALL_AUTHORITY_UNAVAILABLE`、`mode=UNRESOLVED` 和旧七种安装状态不静默改义。所有原 `/api/workshop/purchased/...` 路径明确返回既有 AjaxResult 错误信封 `code=409,msg=WORKSHOP_CONTRACT_UPGRADE_REQUIRED`；不伪造空已购、不通过旧入口发付费内容。App须显式启用v2解码器及原有审批流程后才调用以下新路径；v2成功响应使用项目标准 AjaxResult 信封，接受标准 `code/msg/data`。

#### 作者冻结与真实定价

路径均以 `/api/workshop` 为前缀，除capabilities外均须登录。JSON端点下文注明，其余使用form参数。

| POST路径 | 请求 | data与规则 |
|---|---|---|
| `/v2/author/source` | `source_id` | 真实owner本人已审核公开原作的 `moduleId/contentHash/title`，不发答案 |
| `/v2/author/versions/freeze` | JSON `{requestId,sourceId,contentHash,priceFen,terms:{version,text,commercialUse,allowedRegions:[地区],installLimit}}` | 仅原作者配置实际价格/条款；服务端锁canonical及库记录、冻结完整配置与秘密、计算内容/条款hash，返回 `purchasedVersionId/moduleId/contentHash/termsHash/priceFen/currency/terms/capabilities` |
| `/v2/offer/detail` | `purchased_version_id` | 同上安全offer投影，无正文、答案或完整配置 |

`priceFen`为正整数分、上限原微信金额接口 `Integer.MAX_VALUE`，不从买方请求定价。只有原作者能冻结，拒绝别人的模板、未审核/未公开/删除来源、二次采用稿及携带其他商家券资产的来源；不存在缺配置时自动定价或自动上架的逻辑。冻结生成的是可引用offer版本，没有新增全站商品货架。购买后原作修改或下架不会替换已冻结正文；安装仍执行原能力/媒体/奖励归属校验。

**免费来源关系与未完成项：** 当前原 `/api/template/adopt-draft` 仍按既有免费授权复制当前公开来源，原主题 `/use` 及既有免费副本也未被本轮撤销。初次冻结时付费快照可与公开免费内容相同；本轮没有把原免费内容变成独占付费内容，也未实现付费独占商品审核与免费/付费版本隔离。App不得显示“必须购买才能取得该公开玩法的内容/同等商业权限”，这项销售政策及隔离是完整2A的未完成项，需要另行确定原授权与商品边界，不能用通道默认关闭代替。

#### 购买、退款、许可

| POST路径 | 请求 | 结果 |
|---|---|---|
| `/v2/purchase/create` | JSON `{requestId,purchasedVersionId,contentHash,termsHash,provider:"WECHAT_APP"}` | 实际订单 `orderNo/orderLineId/purchasedVersionId/amountFen/currency/provider/state/licenseStatus`；唯一购买人是登录本人，禁止购买自己的版本 |
| `/v2/purchase/pay` | `order_no` | 原订单投影加 `payParams`，本步绝不置ACTIVE |
| `/v2/purchase/status` | `order_no` | 原订单持久状态 |
| `/v2/purchase/reconcile` | `order_no` | 服务端查询原微信单，真实成功事实才能推进；无客户端支付成功参数 |
| `/v2/purchase/refund` | `order_no` | 仅买家全额原路退款，先持久 `REFUND_PENDING/SUSPENDED` 后调用原网关；使用稳定 `R+orderNo`，可重试同一退款 |
| `/v2/purchase/refund/reconcile` | `order_no` | 原支付引用约束的渠道退款查询；验付成功退款才 `REFUNDED/REVOKED` |
| `/v2/purchased/list` | 可选 `before_order_line_id>0` | `schema/scope/items/hasMore/nextBeforeOrderLineId?/checkedAt`，50条游标分页 |
| `/v2/purchased/detail` | `license_id` | 许可明细；`licenseId=orderNo`，只认已付订单 |
| `/v2/purchased/revoke` | `license_id` | 买家主动关闭许可，保留付款、退款、安装审计及原草稿，不触发退款打款 |

订单：`PENDING → PAID → REFUND_PENDING → REFUNDED`。许可：`UNPAID → ACTIVE → SUSPENDED/REVOKED`；旧支付SUCCESS重放不会恢复已撤销权益。退款未确定/失败时保持暂停，不能擅自恢复ACTIVE或显示已退款。

许可v2沿用原22个业务字段，动态 `contentUseStatus=PRIVATE_DRAFT_INSTALL_AVAILABLE|LICENSE_DISABLED`、`purchaseActionStatus=EXACT_VERSION_PURCHASED`；固定个人购买、精确已购版本、禁止再分发/翻译。`themeLimit.maximum`表示本许可可安装的私有副本总数，不是全局主题发布额度；`merchantLimit.maximum=runLimit.maximum=0`表示本轮购买没有额外授予商家或执行额度，不能显示成“可以发售/执行0次”的伪额度账本。安装不解锁平台高级能力，原发布/媒体/奖励/审核规则继续生效。

#### 私有草稿安装

| POST路径 | 请求 | data |
|---|---|---|
| `/v2/purchased/install/targets` | `license_id,before_draft_id?` | 本人私有草稿 `items[{targetDraftId,targetRevision,targetPayloadHash,businessType:"MEMBER_TEMPLATE",title}],hasMore,nextBeforeDraftId?` |
| `/v2/purchased/install/submit` | 下文原始JSON字节 | 不改目标草稿，只创建一个新私有模板副本 |
| `/v2/purchased/install/status` | **只有** `request_id` | 原回执或 `{schema,requestId,state:"NOT_FOUND"}` |
| `/v2/purchased/install/history` | `license_id,before_command_key?` | 50条历史回执分页，`nextBeforeCommandKey`是持久安装行ID |
| `/v2/purchased/installed-text/detail` | `license_id,request_id` | 原九个文本字段加 `schema=w18-member-text-v1,bindings={}`，须当前ACTIVE且有同owner成功安装回执 |

安装命令全部必需：

```json
{
  "schema":"workshop-private-draft-install-command-v2",
  "requestId":"客户端稳定动作号",
  "licenseId":"订单即许可号",
  "moduleId":8,
  "purchasedVersionId":"服务端冻结版本",
  "contentHash":"服务端内容hash",
  "termsHash":"服务端条款hash",
  "targetDraftId":20,
  "targetRevision":1794182400000,
  "businessType":"MEMBER_TEMPLATE",
  "targetPayloadHash":"targets返回的完整草稿hash",
  "planningRegion":"条款地区之一",
  "commercialUse":false
}
```

成功回执 `schema=workshop-private-draft-install-outcome-v2`，保留全部命令业务字段，增加 `state=INSTALLED_PRIVATE_DRAFT,ownedDraftId,installedAt,holdDeadline,mode=PRIVATE_COPY,professionalTemplateStatus=MATERIALIZATION_REQUIRED,published=false,executable=false`。`holdDeadline=installedAt`只表示同一同步事务已结束，本版没有PREPARED/RESERVED预留流程，不能画预留倒计时。

同owner同requestId**完整原始UTF-8字节**必须重放，连空格变化也冲突；App保留已提交原始JSON，超时先查status。命令、完整回执与新草稿同事务提交，失败回滚后status为NOT_FOUND，可用同一原动作重试。新动作须重新检查许可、安装限次、目标owner、完整payload及revision；目标变化用 `TARGET_REVISION_CONFLICT` 刷新处理。退款后只允许读取旧无正文审计回执，不能新增安装或读取工坊保护正文。

#### 撤销后新发布

付费安装有持久lineage，原模板发布、重新提交审核、商家节点发布、后台模板重提、模板市场提交/上架、审核通过落状态及主题组包批准均增加同一原许可锁检查；SUSPENDED/REVOKED后不能由这些入口新增发布授权。ACTIVE也不能将付费副本再投免费模板市场（`WORKSHOP_REDISTRIBUTION_PROHIBITED`）。普通免费草稿继续原流程；不删除或自动下架已有玩家内容，原稿编辑/删除不改变。保护对象是安装lineage，并非阻止用户手工重写已知文本的内容DRM。

#### App验收待办

明确不可支付提示、v2解码器、地区及条款确认、保存原请求字节、原单查询回补、目标冲突刷新、账号切换隔离、退款UNKNOWN/暂停展示、ACTIVE/SUSPENDED/REVOKED读回和安装≠发布均待App团队实装及联调。真实微信支付/退款、IAP、HTTP、真机、发布与生产全部未验。

### 26.3 通用 OTA 兑换码

本轮没有端侧源码改动；具体 OTA provider 接入后置。真实 provider 未配置时保持 PROVIDER_NOT_CONFIGURED，不显示“已支付/已验真/已兑换成功”，不新增本地免费发码入口。

- 玩家沿用 `POST /api/channel/redeem {code,requestId}`。真实登录会员由服务端确定；同一动作保留 requestId，未知结果用原 `POST /api/channel/receipt {requestId}` 或 `{receiptNo}` 查回，不换新请求号连点。成功只能以服务端真实 registrationId/receiptNo 为准；输入 code 不进日志、埋点、截图公开区或持久缓存。
- 管理发行沿用 `POST /business/marketing/channel/vouchers`，只能使用真实已验证 verificationId；商品/数量来自服务端事实。原 issue 权限必需，list 权限不能签发，没有新增批量投放接口或自动授予角色。
- 明文 code 仍只在首次成功签发响应返回一次。`codeAvailable=false` / `replayed=true` 时仅恢复已有发行元数据，不显示“重新取码”按钮，不从 publicId/codeHash 拼码。默认到期不传 expiresAt 的同 requestId 重试已修为元数据重放，不生成第二张码。
- 商品/期限/条款/作品及金额分摊按发行快照生效；后改目录不追溯扩大或缩短原码权益。新拒绝 `VOUCHER_SNAPSHOT_UNAVAILABLE` 表示旧码缺发行快照，提示联系发行方核对，不能自动换成当前商品继续兑。
- `VERIFICATION_EXPIRED` 表示验证事实已过期；`CHANNEL_ORDER_AMBIGUOUS` 表示外单号跨主体歧义；与 CODE_EXPIRED / CODE_REVOKED / SUBJECT_MISMATCH / ALREADY_REDEEMED_BY_OTHER 分开处理，不把它们当成功或空数据。
- 管理已兑换码撤销返回 `revokePending=true` / REFUND_REQUIRED 时，只显示“待渠道退款确认”。验证记录此时仍可为 VERIFIED，这不是撤销失败，也不代表退款到账；真实退款完成事实驱动原准入、码与回执撤销后才显示撤销终态。
- 退款先到的未兑换码会被封闭；尚未发码的验证记录会被撤销。NO_REVENUE_TO_REVERSE 仅表示尚无准入/应计可冲销，不显示“已撤销游玩权益/已冲销收入”。
- 回执始终按当前登录会员读取；跨账号回执返回 RECEIPT_NOT_FOUND。切账号清理旧响应，不缓存其他账号 registrationId 或 bearer code。

未验：HTTP/客户端/真机/真实provider与生产；以上为本轮后端源码合同，须 root 统一验证和归并主 UI 清单。


### 26.4 Apple IAP 服务端合同增量（2026-11-09 UTC）


状态：**SOURCE 已接真实 controller/service 与内部订单端口；编译、测试执行、HTTP、Apple Sandbox、真机及生产均 NOT_RUN/NOT_VERIFIED。** 本文件仅供 root 归并唯一 UI 清单，不是另一份主清单。本位未改 Swift、小程序、Vue 或主 UI 文档。

#### 购买前与端侧支付

- 新收费版本、审核上架、服务端订单/SKU/token 返回由 PAID 的 Workshop 合同负责；只有服务端 capabilities 和已审核版本明确可售时才提供支付动作。`workshop.apple-iap.enabled` 默认 false；缺服务端应用、信任根、明确 SKU 金额映射即不可用。本轮没有授权或启用真实收费。
- 仅支持永久、精确版本的 **Non-Consumable**，单份且本人购买；不支持消耗品、订阅、家庭共享或赠送。服务端订单返回的 `productId` 和服务端生成的 UUID `appAccountToken` 必须原样用于 StoreKit 购买；App 不生成另一个账户 token。
- 展示 StoreKit 实际商品价格/币种，服务端以已审核订单冻结映射及受控 SKU 表再次核对 Apple milliunits。新订单无明确币种/SKU 映射不可购；旧单确认/恢复/退款按原订单冻结映射，不能因现SKU停卖/变价阻断售后。不在 App 或服务端自动换汇。具体订单字段以 PAID-ui 为准。

#### App 新增请求

统一前缀 `/api/workshop/v2/apple-iap`，以下三个 App 请求必须登录；身份由现有登录会话取得，body 无 buyer、角色、环境、bundle、根证书、价格或 paid=true 字段。

| 请求 | JSON | 含义 |
|---|---|---|
| `POST /confirm` | `{"orderNo":"服务端原订单","signedTransactionInfo":"StoreKit返回的完整交易JWS"}` | 官方 SDK 验签后匹配本人订单、账户 token、应用、环境、SKU、金额币种及原交易，再交真实 Workshop 许可端口 |
| `POST /restore` | 同上 | 只读回本人已绑定的原交易许可，不创建新购买、不恢复暂停/撤销 |
| `POST /reconcile` | `{"orderNo":"服务端原订单","transactionId":"Apple交易ID"}` | 原订单授权、冻结环境核对后由受控服务器 API 查交易，返回 JWS 再走相同验证链；未配只读 key/issuer/keyId 或关闭 query 时 503 |

使用现有 AjaxResult：成功 `code=200,data={state,orderNo,...}`；本 controller 的 ServiceException 同时使用真实 HTTP 状态码与 body code。`VERIFIED` 只表示交易事实已验证并交给订单端口，不等于 UI 可自行认定许可 ACTIVE；超时或成功均再查原 Workshop `/v2/purchase/status` 或许可详情，以权威持久状态展示。

- `state=REVOKED`：已存在原交易撤销事实，本次旧成功/恢复不会发新权益。
- `state=RESTORED`：`data.license` 是订单端口的既有本人许可读回，仍按其真实状态展示；不直接当作 ACTIVE。
- `APPLE_IAP_UNAVAILABLE / APPLE_TRUST_ROOTS_UNAVAILABLE / APPLE_ORDER_MAPPING_UNAVAILABLE / APPLE_SERVER_QUERY_UNAVAILABLE`：渠道未就绪，保留原订单，不展示支付成功或用假权益兜底。
- `APPLE_TRANSACTION_VERIFICATION_FAILED / APPLE_PRODUCT_TYPE_UNSUPPORTED / APPLE_PRODUCT_CONFLICT`：不得发权益；保留实际交易及原订单供核对，不本地改价格/币种/类型重试。
- 账户、环境、商品、订单金额、原交易或交易事实冲突：停止当前动作，刷新本人原订单；账号切换时隔离订单/token/JWS，不能把一个账号购买恢复给另一个账号。
- `APPLE_RESTORE_ORIGINAL_REQUIRED`：尚无此前绑定的本人原交易，不能通过恢复创建许可；先处理原购买确认/查单链。App 失去本地记录时使用服务端本人订单/许可清单找原单。

#### Apple 通知与退款

`POST /notifications` body `{"signedPayload":"Apple完整通知JWS"}` 仅供 Apple 服务器。此方法使用项目已有 `@Anonymous`，业务信任仅来自官方 SDK 外层通知与内层交易双验签；App 不调用它表达退款、成功或恢复。

通知处理完成才返回 2xx/APPLIED；未匹配订单、元数据不完整、不支持的通知或处理失败持久为 PENDING 并返回 503，允许同一完整签名通知重投。真实退款/撤销先持久原交易撤销闸，并按已绑定的应用/环境/原交易立即撤销既有许可；旧token/金额缺失或SKU配置变化只影响完整匹配待核，不阻塞既有绑定撤销。通知先于订单、旧成功重放与重试都不能重激活。未支持的 REFUND_REVERSED 不自动恢复许可，保留待核。

App 不提供本域新增的“服务端直接 Apple 退款”动作；Apple 退款后以后端原许可状态为准，原安装≠发布、暂停/撤销后的正文/新安装/发布限制沿 PAID/WORKSHOP 合同。无须在 UI 展示 notificationUUID、证书、inbox 或内部错误堆栈。

#### 待真实验收

App Store Connect 商品/账户/通知配置、实际 Sandbox 购买/通知/退款/恢复、换账号、断线原单恢复、已暂停或撤销状态回读、真机及发布均待验。源码 mock 业务结果、官方 SDK 签名负例和独占 MySQL 测试均不能当成 Apple Sandbox 正向验收。


### 26.5 独立收费版本与旧免费版的边界（2026-11-09 UTC）


状态：SOURCE / NOT_RUN；交 root 归并唯一 UI 清单，本位未改 UI。此增量替代 WORKSHOP-ui 的“公开免费来源冻结”与“IAP未实现”历史描述；Apple验签/通知控制器合同另见 APPLE-ui.md，端到端仍未验收。

#### 免费与收费边界

旧免费库、原免费采用、原主题/use和既有免费副本保留。收费来源必须是作者本人的独立私有原作草稿：未公开、未挂主题、无公共库投影、无采用来源及付费安装lineage；不修改旧免费行的授权或价格。冻结后该源稿仍可编辑供下个收费版本使用，但不能再经普通发布/广场/主题引用变成免费来源；审核驳回或撤回也不解除这个保留事实。需要发布免费内容时用独立免费版本。

#### 作者与审核

均以/api/workshop为前缀，须登录，保持标准AjaxResult信封。

| 路径 | 请求 | 含义 |
|---|---|---|
| POST /v2/author/source | source_id=私有cms_member_template.id | 返回sourceKind=PRIVATE_TEMPLATE、moduleId、contentHash、title；与旧public library ID分开 |
| POST /v2/author/versions/freeze | JSON原Freeze字段加必需sourceKind=PRIVATE_TEMPLATE | 冻结完整秘密、条款和CNY priceFen，返回DRAFT；缺sourceKind拒绝PRIVATE_SOURCE_CONTRACT_REQUIRED，旧客户端不得静默改义 |
| POST /v2/author/versions/submit | JSON {purchasedVersionId,contentHash,termsHash,priceFen} | owner和内容/条款/价格CAS，提交原统一审核，返回PENDING及auditTaskId/reviewHash |
| POST /v2/author/versions/detail | purchased_version_id | 本人版本状态；未审核正文仍仅原作者私有编辑器访问 |
| POST /v2/author/versions/withdraw | purchased_version_id | WITHDRAWN，停止新销售/支付准备，保留原已购许可 |
| POST /v2/offer/list | before_version_id?为正整数游标 | 仅APPROVED、无正文货架；items/hasMore/nextBeforeVersionId? |
| POST /v2/offer/detail | purchased_version_id | 仅APPROVED，title/内容及条款hash/审核hash/条款/channelPrices等安全元数据 |

版本生命周期为DRAFT→PENDING→APPROVED或REJECTED，可撤回为WITHDRAWN；冻结正文、条款、CNY价格不可原地改版，修订须创建新独立版本并重审。审核通过不付款、不发许可、不发布作者源稿。

原管理后台统一审核增加bizType=WORKSHOP_VERSION；详情snapshot为完整不可变内容、条款、CNY分价格及当次受控Apple映射，包含敏感谜底，只用于原审核权限内的详情。审批沿原/review，必传expectedVersion和稳定requestId，reject沿原reasonText规则；过期审核版本/hash冲突须刷新，不能假显示通过。原媒体、能力、奖励、传感与偏好验证在作者冻结及批准时继续执行。

新收费Terms.allowedRegions使用现有地点合同的6位cityCode，例如310100；planningRegion及真实主题城市按相同编码核对，不用城市展示名。历史已冻结旧快照不批量改写。

#### Apple价格、购买与售后

server SKU映射由受控服务端资料配置，没有作者/买家改映射接口。先冻结DRAFT，服务端配置该版本的bundle/environment/productId/明确currency及priceMilliunits，再由作者submit；审核快照包含映射，审核后改映射不可售，须独立新版本重审。默认没有映射且通道未启用，不能展示已可真实支付。

offer.channelPrices包含WECHAT_APP的CNY amountFen，以及被审核的APPLE_IAP productId/environment/currency/amountMilliunits。Apple milliunits是货币单位千分之一，例如USD 990=USD 0.990；不能当人民币分或编汇率。货架报价并非通道就绪证明，创建仍核当前配置与审批映射，缺配置如实返回CHANNEL_NOT_CONFIGURED/APPLE_PRODUCT_NOT_CONFIGURED。

POST /v2/purchase/create沿原JSON合同，provider=APPLE_IAP时新增必需reviewHash，须与展示报价的审核hash一致；WECHAT_APP原v2字段仍可使用。Apple返回orderNo、purchasedVersionId、provider、currency、amountMilliunits、productId、服务端UUID appAccountToken，绝不返回amountFen；许可为UNPAID。StoreKit须用服务端productId及appAccountToken进行本人购买，随后走APPLE-ui中的签名交易确认/本人原单恢复，不能自报paid。

旧WECHAT_APP下单、原GenericApp支付、原验签fact与原路退款均保留；通用微信pay/reconcile/refund入口拒绝Apple订单，Apple退款走App Store及原交易通知，不创建钱包、分账或自动打款。

已购买Apple订单冻结bundle/environment/SKU/token/币种/milliunits与审批hash；停卖、SKU变价或撤回货架不改变原单售后。恢复仅回本人已绑定原交易的既有许可，不能创建第二份购买。官方退款/撤销先于付款到达也会封住许可；旧成功重放绝不把SUSPENDED/REVOKED恢复。缺旧token/price的合法退款可按已绑定bundle/environment/originalTransactionId立即撤销，未知原单仍待核重试，不能假ACK。

#### 安装、主题复用与撤销

原v2私有安装命令/稳定原始字节/回执合同保留，付款后仍只创建私有草稿，须原能力与审核才能发布。收费新版本安装不再带公共免费library来源ID。普通免费采用无法获取独立收费正文。

合法同owner组主题或换城优先复用原已授权模板引用；NPC/节点引用需要独立副本时，复制继承原order/许可lineage并计入同一installLimit，不能复制后丢失退款围栏。themeLimit.maximum现包含安装副本及此类forced副本；界面不要承诺无限复制。跨owner主题/use不能顺带转移付费许可；已授权受益人须符合原许可地区/商业条款。普通主题返回/新期、主题实际发布及组包批准都检查当前许可，撤销后禁止新复制/发布，原审计和草稿保留。

#### 端侧待验

作者旧客户端升级提示、DRAFT送审及驳回、只元数据货架、审核后改版CAS、不同币种报价确认、StoreKit账户token、丢ACK原单重读、恢复/退款先到/重放、私有安装和主题合法同城/跨城/forced副本、账号切换及撤销后新发布均待真实HTTP/App联调。SOURCE不等于支付、真机、迁移或生产通过。

## 27 本轮核对后的接线增量

本节只列新增端侧合同；源码完成与运行验证分开，实际通过范围见[后端执行结果](后端优化执行结果.md)的对应冻结快照。无需新建第二套版本库、素材库、钱包或报名系统。

### 27.1 采用主题时的真实来源修订（BE-03）

沿原 `POST /api/template/topic-template/use`，可选发送 `sourceContentRevisionId`，为正整数字符串；继续读取对象 `data.topicId`，不能改成标量响应。响应、地点绑定状态及 `contract.adoption` 增加同名可空字符串。

- 非空只表示采用了当前发布的游玩内容；`sourceConfigVersion`仍是原编辑版本，不能替代内容修订。无真实修订的旧来源为null，显示未锁定来源修订。
- 仅支持当前发布版。旧/外主题修订、源草稿已偏离发布、来源快照不一致或原复制链无法完整复制的结构，沿原业务错误返回；提示作者先发布或刷新当前版本，不静默换ID。
- 保留换城清空与待绑定问题、付款许可/地区/退款围栏及作者权限。采用后仍为独立草稿，不能直接显示已发布、可售或实地已验。

### 27.2 开始游玩时声明实际端能力（BE-07）

| 入口 | 原请求保留，新增可选项 |
|---|---|
| `POST /api/play/orient/session/prepare`、`/start` | 表单 `clientPlatform`、`clientVersion`、`supportedCapabilities` |
| `POST /api/play/advanced/start` | 原JSON中相同三项 |
| 原 `GET /api/play/nodes` | 继续原 `capabilities` 参数名，仅隐式新建局前校验；已有局读其冻结兼容结论 |

统一优先传 `key@version,key@version` 字符串，由各端只登记已实现并已验证的渲染能力。advanced JSON也接受字符串数组或 `{key,version}` 数组；不必为这些等价格式增加UI选择。

缺省/null是旧端未声明，结论未确认；显式空串/空数组是声明零能力。服务端从实际冻结内容推导需求，显式缺失、过旧或格式无效时拒绝新开局，不能客户端删需求、自动跳关或伪造降级。orient保持409及 `err=CONTENT_CAPABILITY_UNSUPPORTED`；advanced沿原错误信封及升级提示处理。成功响应及旧参数保持兼容；声明不授予创作、购买或游玩权益。

能力声明的线格式须区分：不提供或null表示未声明，`supported=null/source=UNDECLARED`，不得据此拦住旧端；显式空串或空列表是“声明不支持任何能力”，有必需能力时返回明确不支持。不要为省字段把未提供自动补成空串或空数组。测试向量现已分别覆盖两者。

### 27.3 普通到达成功后的读数（BE-15）

普通GPS到达也可在原成功响应 `data.arrivalReading` 取得原局冻结的 `{level,log}`；只作作者叙事展示，不是奖励、积分或排行榜等级。无配置的旧局不补值，未通过到达不展示。按原动作回执去重播放，重试读取原结果；不能用当前作者配置覆盖老局读数。

### 27.4 管理后台的明确复报口径（CO-06）

只改原复报报表。30日请求新增 `repeatSignupV1`，其 `definitionVersion=repeat_signup_v1`，与现有V2结果并列；7/90日为null。新字段按首个有效报名后严格30×24小时观察，同日另一独立订单可计，同单多席或同时刻不推断再次报名。旧V2标签和数值保持。

- `paid`为现金付费未全退主口径，`all`为含免费全报名并列口径，分别展示服务端定义与分子分母，不混算。
- 分母读取各组 `matureMembers`，分子/比例读取 `retainedMembers`/`retentionRate`，同时显示 `eligibleMembers`/`notMatureMembers`；不用顶层旧V2的 `mature` 判断新指标。
- 零成熟样本的null显示未成熟/无可计算样本；`available=false`显示 `unknownReason`，不能补0%。可查看本响应的时间基准、水位和部分退、待审及迟到诊断。
- 这是报名账户的当前状态重算，不能称实际参加人留存，也不能以测试fixture宣称真实经营效果。

### 27.5 素材来源声明与署名（CT-04）

沿现有上传位和assetId编辑，来源选择为original、licensed、ai-generated、public-domain；旧unknown单列待确认，不建新素材库。本人 `PUT /api/media/asset/{id}/provenance` 完整替换声明，JSON为 `sourceType,licenseNote,attribution,aiModel?,createdAt?`，时间为UTC ISO；省略可选项会清空。本人状态与列表新增 `mediaAsset`、`issues`，缺声明保存为 `ASSET_LICENSE_MISSING/warn`。

`licenseNote`是作者公开前应填写的声明，不代表真实版权已核验。玩家只沿 `GET /api/media/asset/attribution?reference=...` 读取 `sourceType,attribution,aiGenerated,sourcePendingConfirmation`，按需展示署名、AI标识或待确认；不得展示私有许可备注、模型、创建时间、存储key或归属。优先传原 `media-asset:id`，也支持原内容输出的媒体URL。

新登记APP素材缺声明时公开发布返回422及 `ASSET_LICENSE_MISSING`，预检warn不能当发布许可。旧登记NULL、原G3/官方图/模板采用许可按既有合同保留，不批量补造来源或追改已售权益。实际授权材料仍由作者提交；运营可沿本人原分页清单导出声明，不能把它标为已获法务认证。

### 27.6 主持预约沿原场次报名（CO-04）

只对新建系列显式 `hostReservationRequired:true` 开启；默认false，旧系列/普通票不改。配置沿原club/event-ops，`offerMinutes`继续5–1440分钟及服务端票面截止。既有系列列表/详情回同名字段，未来重排不能改历史策略。

| 操作 | 原owner接口与接线 |
|---|---|
| 玩家请求主持 | `POST /api/club/event-ops/host/request`，`{activityId,ticketId}`；不传actor、不当作已持位，请求也有服务端截止 |
| 本场授权主持接受/拒绝 | `POST /api/club/event-ops/host/decide`，`{activityId,ticketId,entryId,accept}`；沿原roster处理REQUESTED，接受响应不发玩家token |
| 玩家查状态/撤销 | 原 `/waitlist/status`、`/waitlist/cancel`，`{activityId,ticketId}`；只本人有效OFFERED的status取得offerToken |
| 报价与付款 | 继续原quote/create，携带 `waitlistOfferId/waitlistOfferToken`；未接受、过期或跨人/跨票均不能下单，通知不等于已付 |

展示服务端 `reservationState`：REQUESTED申请中、ACCEPTED_AWAITING_PAYMENT已接受待付款、CONFIRMED原订单真正已付；REJECTED/EXPIRED/CANCELLED分别处理。`PAYMENT_REVIEW_REQUIRED/ORDER_REVIEW_REQUIRED`显示待处置，不能显示已确认或退款到账。CLAIMED后的取消走原订单售后。

集合点、时段、容量、钱款和退款规则按原quote.delivery/订单回读；服务订单与独立内容权益分别展示，不按标题拆价格。真实迟到付款进入原人工恢复队列，尚未证明自动补偿；服务完成沿原attendance，内容完成独立。主持供给、天气/迟到票面材料与现场排练须由运营核验。

### 27.7 组合包退款保留项级身份（CO-07）

退款项必须由服务端真实交付registration及下单冻结分摊确定，不能由客户端金额或当前标价猜目标。已到账单项保留其余权益，礼品按实际领取人核使用；只有全部可证项到账才显示整包已退款。未交付、零价、已用、身份或金额未知继续原人工售后。

父现金付款与真实交付行的原路退款适配已形成源码，相关14例helper测试及原退款、派发、回调、恢复回归通过；真实MySQL全链仍待执行。当前复用 `GET /api/marketing/product/orders` 的真实 `registrationIds/primaryRegistrationId`；可审入口仍为原后台 `POST /business/refund`，`sourceType=registration`，目标是实际交付行ID，金额只能是该行冻结份额扣除已到账后的完整余款。`GET /business/refund/list` 回读原ID、退款号和状态，`PUT /business/refund/review` 保留原审批权限及申请人与审批人分离。礼品退款仍退回原买家付款。

界面区分申请、批准、派发、UNKNOWN及实际SUCCESS；批准不等于到账。UNKNOWN保留原退款号查询，不新建补退单。不支持整包金额塞进首行、任意部分金额或已用内容自动回收。未新增玩家逐项退款端点或可退报价API；后续若做玩家自助退款，须先补受权的逐行冻结金额、剩余额和原申请状态读合同，再做正常UI验收，不得由客户端根据ID数组自行推算可退金额。

### 27.8 三端同修订核对输入（BASE/QA）

沿现有 `scripts/e2e/run_metadata.py` 使用 `fixtures/run_metadata.UNKNOWN.json` 登记一次Run：后端候选与实际运行身份、测试目标、三端build/角色设备、真实内容修订和地点绑定，以及正常入口取得的脱敏响应和截图。卡片releaseId/bindingRevision只映射已有事实并保留依据，未知继续UNKNOWN，不造新注册表。

各端按同一章节/请求/内容与绑定身份捕获公共投影；管理后台私有作者快照不能混当玩家公共正文。工具只离线定位字段、顺序、文案和已说明差异，生成 `diff-report.json`；截图存在不证明渲染一致，工具始终保留人工渲染复核与实际验收PENDING。真实手机、正常身份、旧局改版隔离须各端另行实跑。

### 27.9 指认结案回执与可收场分开（CT-07）

直接消费服务端 `accusation.result` 的 `verdict/correct/early/recordCode/canRetry/terminal` 及本次公开 `screen`，不根据提交选项或本地完成数自行判定。`readyForBase=true` 只表示原高级玩法允许收场：有限次数QA答错并耗尽时也会为true，此时仍是FAILED、未答对、该题零分。不要据此展示成功或发成功奖励。QA得分与成功完局奖励分别按各自服务端事实展示，不能单凭early标志将得分改为0或自行补奖。

提前结案的进度由服务端实际作用域查询；未知或读取失败不虚构完成。作者 `recordAs` 原文保留，界面按既有结局标签展示；同requestId重试重放原回执，不再次判定、扣机会或计分。提前指认开关不绕过原节点访问权限，不能把尚不可达的后续节点做成客户端直达按钮。

上述运行时8例行为测试已通过；正常UI、真机、实际城市内容和现场仍待各端验收。


### 27.10 个人分佣只展示本人实际分项

- 既有 `GET /api/marketing/revenue/author/mine` 与 `/order` 的 `lines` 按服务端登录身份，仅返回 `recipientMemberId` 等于本人的非平台分项；同一人同时为作者和推荐人时保留两项，不由客户端合并去重。
- `reversedAmount` 等父订单上下文字段不等于个人收入，不据此显示个人到账、扣回或可提现。创作者中心原合作账本与该订单分佣账本不直接相加。
- 原非参与者拒绝保留；无分项、读取失败、待结算与已到账分别显示。b0f9581定向9例通过，未据此宣称HTTP/App验收。

## 28 2026-11-09 实施波次

业主要求把剩余代码缺口和 UI 改动一次做完，先以 HTML 评审页给业主看，看完再合并。本节按各工位分支上的 `docs/delivery/questo-ui-20261109/qx-*/RESULT.md` 汇总，状态照抄 RESULT，不另行升级。所有 PR 都是草稿，未合并；证据路径在对应工位分支上（`claude/qx-<工位>-20261109`）。截图（`shots/`）只代表 rig 里的真实页面，真机、真实支付、渠道和现场均未验。

### 28.1 工位总览

| 工位 | 范围 | PR | RESULT 结论 |
|---|---|---|---|
| qx-b1 | 游玩核心：发布号重放、能力声明、到达原因与读数、提示阶梯 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | PARTIAL：代码、测试和门禁齐，rig 到不了可玩局和可发布草稿，缺截图 |
| qx-b2 | 新玩法：逐字猜词、图片选项、指认结案、谜面变换、作者提示 | [#740](https://github.com/liboyang42-cpu/hengyin/pull/740) | PARTIAL：§23.1 国家码和到达设置当时没有写口（qx-c1 已补后端） |
| qx-b3 | 玩家闭环 P1–P9 与多作用域榜 | [#741](https://github.com/liboyang42-cpu/hengyin/pull/741) | PARTIAL：完局和支付事实用 SQL fixture；P4 未放进详情页；队伍之间榜未接 |
| qx-b4 | 模板换城、预设、席位、合作帮助 | [#744](https://github.com/liboyang42-cpu/hengyin/pull/744) | PARTIAL：§26.1 没截到图；U02-05 作者侧来源声明编辑未做；采用流程曾被 `FOR UPDATE OF` 阻塞（qx-c1 #1 已修） |
| qx-b5 | 商业营销 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | PARTIAL：通票购买成功、主持接受到付款、真实应计未截图；悬赏活动列表等后端（qx-c1 #6 已补） |
| qx-b6 | 管理后台 | [#743](https://github.com/liboyang42-cpu/hengyin/pull/743) | PARTIAL：UG-12、U08-03、U08-05 缺后端读面；§20.2 发码成功和 §19 有数据两种状态未截到 |
| qx-b7 | iOS（`liboyang42-cpu/chengyin-ios`） | chengyin-ios #144 | PARTIAL：代码未经类型检查，未挂进游玩流程，无设备截图 |
| qx-b8 | 完局结果、推荐、公开目录、城市合集 | [#742](https://github.com/liboyang42-cpu/hengyin/pull/742) | PARTIAL：U08-01/02 rig 无真实完局，仅单测；问题反馈等后端写接口（qx-c1 #7 已补） |
| qx-c1 | 后端十项缺口 | [#747](https://github.com/liboyang42-cpu/hengyin/pull/747) | PARTIAL：5b 冻结进发布快照只有单测证明，其余九项真实 HTTP 加 MySQL 回读 |
| qx-c2 | 文档、部署清单、真库复验 | 见 qx-c2 RESULT | 见 `docs/delivery/questo-ui-20261109/qx-c2/RESULT.md` |
| qx-a1 … qx-a7、qx-a5d | 走查（vision 3） | 见 28.3 | 见 28.3 |

### 28.2 Questo UI 任务（B 工位）

| 任务 | 工位 | PR | 状态 | 证据 |
|---|---|---|---|---|
| U03-01 | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed | `docs/delivery/questo-ui-20261109/qx-b1/` |
| U03-02 | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed | `docs/delivery/questo-ui-20261109/qx-b1/` |
| U03-03 | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed（此前已修） | `docs/delivery/questo-ui-20261109/qx-b1/` |
| U04-01 | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed | `docs/delivery/questo-ui-20261109/qx-b1/` |
| U04-02 | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed（此前已修） | `docs/delivery/questo-ui-20261109/qx-b1/` |
| U04-03 / §27.2 | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed | `docs/delivery/questo-ui-20261109/qx-b1/` |
| U04-04 | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed（此前已修） | `docs/delivery/questo-ui-20261109/qx-b1/` |
| U05-01 | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed | `docs/delivery/questo-ui-20261109/qx-b1/` |
| U05-02 | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed | `docs/delivery/questo-ui-20261109/qx-b1/` |
| U05-03 / §27.3 | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed | `docs/delivery/questo-ui-20261109/qx-b1/` |
| UG-05 | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed | `docs/delivery/questo-ui-20261109/qx-b1/` |
| UG-06 | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed | `docs/delivery/questo-ui-20261109/qx-b1/` |
| UG-07 | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed | `docs/delivery/questo-ui-20261109/qx-b1/` |
| UG-08 | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed | `docs/delivery/questo-ui-20261109/qx-b1/` |
| UG-09 | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed | `docs/delivery/questo-ui-20261109/qx-b1/` |
| Shards | qx-b1 | [#746](https://github.com/liboyang42-cpu/hengyin/pull/746) | fixed | `docs/delivery/questo-ui-20261109/qx-b1/` |
| UG-01 player | qx-b2 | [#740](https://github.com/liboyang42-cpu/hengyin/pull/740) | fixed | `docs/delivery/questo-ui-20261109/qx-b2/` |
| UG-01 author | qx-b2 | [#740](https://github.com/liboyang42-cpu/hengyin/pull/740) | fixed | `docs/delivery/questo-ui-20261109/qx-b2/` |
| UG-02 | qx-b2 | [#740](https://github.com/liboyang42-cpu/hengyin/pull/740) | fixed | `docs/delivery/questo-ui-20261109/qx-b2/` |
| UG-03 | qx-b2 | [#740](https://github.com/liboyang42-cpu/hengyin/pull/740) | fixed | `docs/delivery/questo-ui-20261109/qx-b2/` |
| UG-04 / §27.9 | qx-b2 | [#740](https://github.com/liboyang42-cpu/hengyin/pull/740) | fixed | `docs/delivery/questo-ui-20261109/qx-b2/` |
| UG-10 | qx-b2 | [#740](https://github.com/liboyang42-cpu/hengyin/pull/740) | fixed | `docs/delivery/questo-ui-20261109/qx-b2/` |
| UG-11 | qx-b2 | [#740](https://github.com/liboyang42-cpu/hengyin/pull/740) | fixed | `docs/delivery/questo-ui-20261109/qx-b2/` |
| Catalog / matrix | qx-b2 | [#740](https://github.com/liboyang42-cpu/hengyin/pull/740) | fixed | `docs/delivery/questo-ui-20261109/qx-b2/` |
| §23.1 answer aliases | qx-b2 | [#740](https://github.com/liboyang42-cpu/hengyin/pull/740) | fixed | `docs/delivery/questo-ui-20261109/qx-b2/` |
| §23.1 placeCountryCode | qx-b2 | [#740](https://github.com/liboyang42-cpu/hengyin/pull/740) | needs owner | `docs/delivery/questo-ui-20261109/qx-b2/` |
| §23.1 arrivalPolicy / arrivalReading | qx-b2 | [#740](https://github.com/liboyang42-cpu/hengyin/pull/740) | needs owner | `docs/delivery/questo-ui-20261109/qx-b2/` |
| 首链 P7→P1→P4→P5 | qx-b3 | [#741](https://github.com/liboyang42-cpu/hengyin/pull/741) | fixed（链路部分为 fixture） | `docs/delivery/questo-ui-20261109/qx-b3/` |
| P7 本局战报 | qx-b3 | [#741](https://github.com/liboyang42-cpu/hengyin/pull/741) | fixed | `docs/delivery/questo-ui-20261109/qx-b3/` |
| P9 单关与队内榜 | qx-b3 | [#741](https://github.com/liboyang42-cpu/hengyin/pull/741) | fixed | `docs/delivery/questo-ui-20261109/qx-b3/` |
| §23.4 多作用域榜 | qx-b3 | [#741](https://github.com/liboyang42-cpu/hengyin/pull/741) | fixed | `docs/delivery/questo-ui-20261109/qx-b3/` |
| P8 同行回忆 | qx-b3 | [#741](https://github.com/liboyang42-cpu/hengyin/pull/741) | fixed | `docs/delivery/questo-ui-20261109/qx-b3/` |
| P1 推荐/礼物/同行落地 | qx-b3 | [#741](https://github.com/liboyang42-cpu/hengyin/pull/741) | fixed | `docs/delivery/questo-ui-20261109/qx-b3/` |
| P4 玩法模式 | qx-b3 | [#741](https://github.com/liboyang42-cpu/hengyin/pull/741) | partial（只放在落地页） | `docs/delivery/questo-ui-20261109/qx-b3/` |
| P5 票内席位 | qx-b3 | [#741](https://github.com/liboyang42-cpu/hengyin/pull/741) | fixed | `docs/delivery/questo-ui-20261109/qx-b3/` |
| P2 推广中心 | qx-b3 | [#741](https://github.com/liboyang42-cpu/hengyin/pull/741) | fixed | `docs/delivery/questo-ui-20261109/qx-b3/` |
| U02-01 采用条件 | qx-b4 | [#744](https://github.com/liboyang42-cpu/hengyin/pull/744) | fixed | `docs/delivery/questo-ui-20261109/qx-b4/` |
| U02-02 沿用 / 换城采用 | qx-b4 | [#744](https://github.com/liboyang42-cpu/hengyin/pull/744) | fixed | `docs/delivery/questo-ui-20261109/qx-b4/` |
| U02-02 后端阻塞 | qx-b4 | [#744](https://github.com/liboyang42-cpu/hengyin/pull/744) | fixed（后端阻塞已交 qx-c1） | `docs/delivery/questo-ui-20261109/qx-b4/` |
| U02-03 地点待办 | qx-b4 | [#744](https://github.com/liboyang42-cpu/hengyin/pull/744) | fixed | `docs/delivery/questo-ui-20261109/qx-b4/` |
| U02-04 来源版本 | qx-b4 | [#744](https://github.com/liboyang42-cpu/hengyin/pull/744) | fixed | `docs/delivery/questo-ui-20261109/qx-b4/` |
| U02-05 / §27.5 素材署名 | qx-b4 | [#744](https://github.com/liboyang42-cpu/hengyin/pull/744) | partial | `docs/delivery/questo-ui-20261109/qx-b4/` |
| §21 预设列表与草稿 | qx-b4 | [#744](https://github.com/liboyang42-cpu/hengyin/pull/744) | fixed | `docs/delivery/questo-ui-20261109/qx-b4/` |
| U06-01 席位与邀请 | qx-b4 | [#744](https://github.com/liboyang42-cpu/hengyin/pull/744) | fixed | `docs/delivery/questo-ui-20261109/qx-b4/` |
| U06-02 角色与私密线索 | qx-b4 | [#744](https://github.com/liboyang42-cpu/hengyin/pull/744) | fixed（此前已修） | `docs/delivery/questo-ui-20261109/qx-b4/` |
| U06-03 队长掉线、退队、接管 | qx-b4 | [#744](https://github.com/liboyang42-cpu/hengyin/pull/744) | fixed（此前已修） | `docs/delivery/questo-ui-20261109/qx-b4/` |
| U06-04 组织者与共同成果 | qx-b4 | [#744](https://github.com/liboyang42-cpu/hengyin/pull/744) | fixed（此前已修） | `docs/delivery/questo-ui-20261109/qx-b4/` |
| §26.1 合作玩法 | qx-b4 | [#744](https://github.com/liboyang42-cpu/hengyin/pull/744) | fixed（未截图） | `docs/delivery/questo-ui-20261109/qx-b4/` |
| U07-01 | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed | `docs/delivery/questo-ui-20261109/qx-b5/` |
| U07-02 | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed | `docs/delivery/questo-ui-20261109/qx-b5/` |
| U07-04 | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed | `docs/delivery/questo-ui-20261109/qx-b5/` |
| §17.5 | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed | `docs/delivery/questo-ui-20261109/qx-b5/` |
| §17.6 | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed | `docs/delivery/questo-ui-20261109/qx-b5/` |
| §17.1 / P6 | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed | `docs/delivery/questo-ui-20261109/qx-b5/` |
| §17.2 | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed | `docs/delivery/questo-ui-20261109/qx-b5/` |
| §17.3 | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed（只显示不可用原因） | `docs/delivery/questo-ui-20261109/qx-b5/` |
| §17.4 | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed | `docs/delivery/questo-ui-20261109/qx-b5/` |
| §19 / §27.10 | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed | `docs/delivery/questo-ui-20261109/qx-b5/` |
| §27.6 | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed | `docs/delivery/questo-ui-20261109/qx-b5/` |
| §18 A | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed | `docs/delivery/questo-ui-20261109/qx-b5/` |
| §18 B | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed | `docs/delivery/questo-ui-20261109/qx-b5/` |
| §18 I | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed | `docs/delivery/questo-ui-20261109/qx-b5/` |
| §18 C | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed（只显示不可用原因） | `docs/delivery/questo-ui-20261109/qx-b5/` |
| §18 D | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | needs owner | `docs/delivery/questo-ui-20261109/qx-b5/` |
| §20.2 | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed（只显示不可用原因） | `docs/delivery/questo-ui-20261109/qx-b5/` |
| U07-03 | qx-b5 | [#745](https://github.com/liboyang42-cpu/hengyin/pull/745) | fixed（只显示不可用原因） | `docs/delivery/questo-ui-20261109/qx-b5/` |
| U03-04 | qx-b6 | [#743](https://github.com/liboyang42-cpu/hengyin/pull/743) | fixed | `docs/delivery/questo-ui-20261109/qx-b6/` |
| UG-12 | qx-b6 | [#743](https://github.com/liboyang42-cpu/hengyin/pull/743) | needs owner | `docs/delivery/questo-ui-20261109/qx-b6/` |
| U07-05 | qx-b6 | [#743](https://github.com/liboyang42-cpu/hengyin/pull/743) | partial | `docs/delivery/questo-ui-20261109/qx-b6/` |
| §19（U07-05 关联） | qx-b6 | [#743](https://github.com/liboyang42-cpu/hengyin/pull/743) | fixed | `docs/delivery/questo-ui-20261109/qx-b6/` |
| U08-03 | qx-b6 | [#743](https://github.com/liboyang42-cpu/hengyin/pull/743) | needs owner | `docs/delivery/questo-ui-20261109/qx-b6/` |
| U08-04 + §27.4 | qx-b6 | [#743](https://github.com/liboyang42-cpu/hengyin/pull/743) | fixed | `docs/delivery/questo-ui-20261109/qx-b6/` |
| U08-05 | qx-b6 | [#743](https://github.com/liboyang42-cpu/hengyin/pull/743) | needs owner | `docs/delivery/questo-ui-20261109/qx-b6/` |
| §27.7 | qx-b6 | [#743](https://github.com/liboyang42-cpu/hengyin/pull/743) | fixed | `docs/delivery/questo-ui-20261109/qx-b6/` |
| §18 E | qx-b6 | [#743](https://github.com/liboyang42-cpu/hengyin/pull/743) | fixed | `docs/delivery/questo-ui-20261109/qx-b6/` |
| §18 F | qx-b6 | [#743](https://github.com/liboyang42-cpu/hengyin/pull/743) | fixed | `docs/delivery/questo-ui-20261109/qx-b6/` |
| §20.1 | qx-b6 | [#743](https://github.com/liboyang42-cpu/hengyin/pull/743) | fixed | `docs/delivery/questo-ui-20261109/qx-b6/` |
| §20.2 | qx-b6 | [#743](https://github.com/liboyang42-cpu/hengyin/pull/743) | fixed | `docs/delivery/questo-ui-20261109/qx-b6/` |
| 菜单与权限 | qx-b6 | [#743](https://github.com/liboyang42-cpu/hengyin/pull/743) | fixed | `docs/delivery/questo-ui-20261109/qx-b6/` |
| 共用 | qx-b6 | [#743](https://github.com/liboyang42-cpu/hengyin/pull/743) | fixed | `docs/delivery/questo-ui-20261109/qx-b6/` |
| U08-01 正式结果 | qx-b8 | [#742](https://github.com/liboyang42-cpu/hengyin/pull/742) | fixed | `docs/delivery/questo-ui-20261109/qx-b8/` |
| U08-02 下一场推荐 | qx-b8 | [#742](https://github.com/liboyang42-cpu/hengyin/pull/742) | fixed | `docs/delivery/questo-ui-20261109/qx-b8/` |
| U08-02 问题反馈 | qx-b8 | [#742](https://github.com/liboyang42-cpu/hengyin/pull/742) | needs owner | `docs/delivery/questo-ui-20261109/qx-b8/` |
| §20.1 公开目录 | qx-b8 | [#742](https://github.com/liboyang42-cpu/hengyin/pull/742) | fixed | `docs/delivery/questo-ui-20261109/qx-b8/` |
| §20.1 落地详情 | qx-b8 | [#742](https://github.com/liboyang42-cpu/hengyin/pull/742) | fixed | `docs/delivery/questo-ui-20261109/qx-b8/` |
| UI-P3 主题合集落地 | qx-b8 | [#742](https://github.com/liboyang42-cpu/hengyin/pull/742) | fixed | `docs/delivery/questo-ui-20261109/qx-b8/` |
| U08-05 榜单与公开内容治理（小程序部分） | qx-b8 | [#742](https://github.com/liboyang42-cpu/hengyin/pull/742) | fixed | `docs/delivery/questo-ui-20261109/qx-b8/` |
| U08-05 榜单范围、异常标记 | qx-b8 | [#742](https://github.com/liboyang42-cpu/hengyin/pull/742) | fixed（此前已修／他道） | `docs/delivery/questo-ui-20261109/qx-b8/` |

qx-b7（iOS，chengyin-ios #144）：U07-03/§26.2 工坊 v1 返回 409 时显示“需要更新”为 fixed；UG-01、UG-02、UG-03/04、UG-05、UG-07、UG-08、UG-09、UG-10 为 fixed（默认关闭，未挂进游玩流程）；U05-01 为 fixed（部分接线）；UG-06 和 U04-03/§27.2 为 needs owner；挂进游玩流程和 §26.4 Apple IAP 为 not done。

后端缺口回补（qx-c1 #747，均为 fixed）：

| qx-c1 项 | 解决的前端阻塞 |
|---|---|
| 1 `lockLicenseForDraft` 改为不用 `FOR UPDATE OF` | qx-b4 采用流程在真库被 Druid wall 拒绝 |
| 2 Questo 表排序规则对齐（新迁移） | qx-b3 礼品领取 join 报 1267 |
| 3 选项图与嫌疑人头像投影为可访问地址 | qx-b2 UG-02/UG-03 图片显示 |
| 4 UG-12 后台只读投影与 `business:playhelp:query` | qx-b6 UG-12 needs owner |
| 5a `placeCountryCode` 写口 | qx-b2 §23.1 needs owner |
| 5b 到达设置存 `scenes[].nodeArrivals` 兄弟表 | qx-b2 §23.1 needs owner（不再与模板覆盖语义冲突） |
| 6 作者侧进行中视频悬赏活动列表 | qx-b5 §18 A |
| 7 完局问题反馈写接口 | qx-b8 U08-02 问题反馈 |
| 8 `/use` 的 `requestId` 幂等 | qx-b4 采用重试 |
| 9 预设缺必填项返回结构化 `requiredFields` | qx-b4 §21 |
| 10 审计 `domain_type` 长度收敛 | qx-b6 视频悬赏审计 |

qx-c1 补上的后端，前端工位还没有接线（qx-b2 的国家码与到达设置、qx-b5 的悬赏列表、qx-b6 的 UG-12、qx-b8 的问题反馈），接线前这些入口保持不显示。

### 28.3 Vision 3 走查状态（A 工位，PR 进 master）

编号沿用业主 2026-11-06 真机走查，不等于旧测试里的内部 V3-xx（例如测试 `r29-v3-41` 是这里的 V3-47）。

| 编号 | 工位 | PR | 状态 | 证据 |
|---|---|---|---|---|
| V3-06 | qx-a1 | [#736](https://github.com/liboyang42-cpu/hengyin/pull/736) | fixed | `docs/delivery/questo-ui-20261109/qx-a1/` |
| V3-10/53 | qx-a1 | [#736](https://github.com/liboyang42-cpu/hengyin/pull/736) | fixed | `docs/delivery/questo-ui-20261109/qx-a1/` |
| V3-11 | qx-a1 | [#736](https://github.com/liboyang42-cpu/hengyin/pull/736) | fixed（无 rig 截图，需真机） | `docs/delivery/questo-ui-20261109/qx-a1/` |
| V3-13/14 | qx-a1 | [#736](https://github.com/liboyang42-cpu/hengyin/pull/736) | fixed | `docs/delivery/questo-ui-20261109/qx-a1/` |
| V3-15 | qx-a1 | [#736](https://github.com/liboyang42-cpu/hengyin/pull/736) | fixed | `docs/delivery/questo-ui-20261109/qx-a1/` |
| V3-16 | qx-a1 | [#736](https://github.com/liboyang42-cpu/hengyin/pull/736) | fixed | `docs/delivery/questo-ui-20261109/qx-a1/` |
| V3-17 | qx-a1 | [#736](https://github.com/liboyang42-cpu/hengyin/pull/736) | fixed | `docs/delivery/questo-ui-20261109/qx-a1/` |
| V3-21 | qx-a1 | [#736](https://github.com/liboyang42-cpu/hengyin/pull/736) | fixed | `docs/delivery/questo-ui-20261109/qx-a1/` |
| V3-18 | qx-a2 | [#732](https://github.com/liboyang42-cpu/hengyin/pull/732) | fixed | `docs/delivery/questo-ui-20261109/qx-a2/` |
| V3-19 | qx-a2 | [#732](https://github.com/liboyang42-cpu/hengyin/pull/732) | fixed | `docs/delivery/questo-ui-20261109/qx-a2/` |
| V3-20 | qx-a2 | [#732](https://github.com/liboyang42-cpu/hengyin/pull/732) | fixed | `docs/delivery/questo-ui-20261109/qx-a2/` |
| V3-25 | qx-a2 | [#732](https://github.com/liboyang42-cpu/hengyin/pull/732) | fixed | `docs/delivery/questo-ui-20261109/qx-a2/` |
| V3-30 | qx-a2 | [#732](https://github.com/liboyang42-cpu/hengyin/pull/732) | fixed | `docs/delivery/questo-ui-20261109/qx-a2/` |
| V3-23/24/32 | qx-a2 | [#732](https://github.com/liboyang42-cpu/hengyin/pull/732) | fixed | `docs/delivery/questo-ui-20261109/qx-a2/` |
| V3-02 | qx-a3 | [#734](https://github.com/liboyang42-cpu/hengyin/pull/734) | fixed | `docs/delivery/questo-ui-20261109/qx-a3/` |
| V3-03 | qx-a3 | [#734](https://github.com/liboyang42-cpu/hengyin/pull/734) | fixed | `docs/delivery/questo-ui-20261109/qx-a3/` |
| V3-22 | qx-a3 | [#734](https://github.com/liboyang42-cpu/hengyin/pull/734) | fixed | `docs/delivery/questo-ui-20261109/qx-a3/` |
| V3-33 | qx-a3 | [#734](https://github.com/liboyang42-cpu/hengyin/pull/734) | fixed | `docs/delivery/questo-ui-20261109/qx-a3/` |
| V3-37 | qx-a3 | [#734](https://github.com/liboyang42-cpu/hengyin/pull/734) | fixed | `docs/delivery/questo-ui-20261109/qx-a3/` |
| V1-P53 | qx-a3 | [#734](https://github.com/liboyang42-cpu/hengyin/pull/734) | fixed | `docs/delivery/questo-ui-20261109/qx-a3/` |
| V3-38 | qx-a3 | [#734](https://github.com/liboyang42-cpu/hengyin/pull/734) | fixed | `docs/delivery/questo-ui-20261109/qx-a3/` |
| V3-39 | qx-a3 | [#734](https://github.com/liboyang42-cpu/hengyin/pull/734) | fixed | `docs/delivery/questo-ui-20261109/qx-a3/` |
| V3-28 | qx-a3 | [#734](https://github.com/liboyang42-cpu/hengyin/pull/734) | fixed | `docs/delivery/questo-ui-20261109/qx-a3/` |
| V3-26 | qx-a4 | [#739](https://github.com/liboyang42-cpu/hengyin/pull/739) | fixed | `docs/delivery/questo-ui-20261109/qx-a4/` |
| V3-35 | qx-a4 | [#739](https://github.com/liboyang42-cpu/hengyin/pull/739) | fixed | `docs/delivery/questo-ui-20261109/qx-a4/` |
| V3-27 | qx-a4 | [#739](https://github.com/liboyang42-cpu/hengyin/pull/739) | fixed（勋章段此前已修） | `docs/delivery/questo-ui-20261109/qx-a4/` |
| V3-34 | qx-a4 | [#739](https://github.com/liboyang42-cpu/hengyin/pull/739) | fixed | `docs/delivery/questo-ui-20261109/qx-a4/` |
| V3-36 | qx-a4 | [#739](https://github.com/liboyang42-cpu/hengyin/pull/739) | fixed | `docs/delivery/questo-ui-20261109/qx-a4/` |
| V3-40, V1-P37 | qx-a4 | [#739](https://github.com/liboyang42-cpu/hengyin/pull/739) | fixed | `docs/delivery/questo-ui-20261109/qx-a4/` |
| V3-42 | qx-a4 | [#739](https://github.com/liboyang42-cpu/hengyin/pull/739) | fixed | `docs/delivery/questo-ui-20261109/qx-a4/` |
| V1-P32 | qx-a4 | [#739](https://github.com/liboyang42-cpu/hengyin/pull/739) | fixed | `docs/delivery/questo-ui-20261109/qx-a4/` |
| V3-29 | qx-a5 | [#737](https://github.com/liboyang42-cpu/hengyin/pull/737) | fixed | `docs/delivery/questo-ui-20261109/qx-a5/` |
| V3-31 | qx-a5 | [#737](https://github.com/liboyang42-cpu/hengyin/pull/737) | fixed（此前已修） | `docs/delivery/questo-ui-20261109/qx-a5/` |
| V3-43 | qx-a5 | [#737](https://github.com/liboyang42-cpu/hengyin/pull/737) | fixed | `docs/delivery/questo-ui-20261109/qx-a5/` |
| V3-44 | qx-a5 | [#737](https://github.com/liboyang42-cpu/hengyin/pull/737) | fixed | `docs/delivery/questo-ui-20261109/qx-a5/` |
| V3-45 | qx-a5 | [#737](https://github.com/liboyang42-cpu/hengyin/pull/737) | fixed | `docs/delivery/questo-ui-20261109/qx-a5/` |
| V3-46 | qx-a5 | [#737](https://github.com/liboyang42-cpu/hengyin/pull/737) | fixed | `docs/delivery/questo-ui-20261109/qx-a5/` |
| V3-47 | qx-a5 | [#737](https://github.com/liboyang42-cpu/hengyin/pull/737) | fixed | `docs/delivery/questo-ui-20261109/qx-a5/` |
| V3-48 | qx-a5 | [#737](https://github.com/liboyang42-cpu/hengyin/pull/737) | fixed | `docs/delivery/questo-ui-20261109/qx-a5/` |
| V3-50 | qx-a5 | [#737](https://github.com/liboyang42-cpu/hengyin/pull/737) | fixed | `docs/delivery/questo-ui-20261109/qx-a5/` |
| A5D-1 One continuous article | qx-a5d | [#735](https://github.com/liboyang42-cpu/hengyin/pull/735) | fixed | `docs/delivery/questo-ui-20261109/qx-a5d/` |
| A5D-2 Fixed top insert bar | qx-a5d | [#735](https://github.com/liboyang42-cpu/hengyin/pull/735) | fixed | `docs/delivery/questo-ui-20261109/qx-a5d/` |
| A5D-3 Long-press drag for every block | qx-a5d | [#735](https://github.com/liboyang42-cpu/hengyin/pull/735) | fixed | `docs/delivery/questo-ui-20261109/qx-a5d/` |
| A5D-4 Divider block | qx-a5d | [#735](https://github.com/liboyang42-cpu/hengyin/pull/735) | fixed | `docs/delivery/questo-ui-20261109/qx-a5d/` |
| A5D-5 Saving | qx-a5d | [#735](https://github.com/liboyang42-cpu/hengyin/pull/735) | fixed | `docs/delivery/questo-ui-20261109/qx-a5d/` |
| W2-01 | qx-a6 | [#738](https://github.com/liboyang42-cpu/hengyin/pull/738) | fixed（此前已修） | `docs/delivery/questo-ui-20261109/qx-a6/` |
| W2-07 | qx-a6 | [#738](https://github.com/liboyang42-cpu/hengyin/pull/738) | fixed（此前已修） | `docs/delivery/questo-ui-20261109/qx-a6/` |
| W2-11 | qx-a6 | [#738](https://github.com/liboyang42-cpu/hengyin/pull/738) | fixed（结算明细）／needs owner（收款账户） | `docs/delivery/questo-ui-20261109/qx-a6/` |
| W2-15 | qx-a6 | [#738](https://github.com/liboyang42-cpu/hengyin/pull/738) | fixed | `docs/delivery/questo-ui-20261109/qx-a6/` |
| W2-16 | qx-a6 | [#738](https://github.com/liboyang42-cpu/hengyin/pull/738) | fixed | `docs/delivery/questo-ui-20261109/qx-a6/` |
| W2-17 | qx-a6 | [#738](https://github.com/liboyang42-cpu/hengyin/pull/738) | fixed | `docs/delivery/questo-ui-20261109/qx-a6/` |
| W2-03 | qx-a6 | [#738](https://github.com/liboyang42-cpu/hengyin/pull/738) | fixed（此前已修） | `docs/delivery/questo-ui-20261109/qx-a6/` |
| W2-04 | qx-a6 | [#738](https://github.com/liboyang42-cpu/hengyin/pull/738) | fixed（收口） | `docs/delivery/questo-ui-20261109/qx-a6/` |
| W2-05 | qx-a6 | [#738](https://github.com/liboyang42-cpu/hengyin/pull/738) | fixed（此前已修） | `docs/delivery/questo-ui-20261109/qx-a6/` |
| W2-19 | qx-a6 | [#738](https://github.com/liboyang42-cpu/hengyin/pull/738) | fixed（此前已修） | `docs/delivery/questo-ui-20261109/qx-a6/` |
| V1-P36 通用分享页 | qx-a7 | [#733](https://github.com/liboyang42-cpu/hengyin/pull/733) | fixed | `docs/delivery/questo-ui-20261109/qx-a7/` |
| V1-P36 勋章卡带码 | qx-a7 | [#733](https://github.com/liboyang42-cpu/hengyin/pull/733) | fixed（此前已修） | `docs/delivery/questo-ui-20261109/qx-a7/` |
| V1-P59 邀请好友动效（IqRophy） | qx-a7 | [#733](https://github.com/liboyang42-cpu/hengyin/pull/733) | fixed | `docs/delivery/questo-ui-20261109/qx-a7/` |
| G-11 页面动效（uixsiam09） | qx-a7 | [#733](https://github.com/liboyang42-cpu/hengyin/pull/733) | fixed | `docs/delivery/questo-ui-20261109/qx-a7/` |

A 工位的 RESULT 除 qx-a1 外都是 `STATUS: COMPLETE`。qx-a1 为 PARTIAL：V3-11 相册在无头渲染器里崩溃（master 和本分支都一样），没有 rig 截图，内嵌卡片逻辑只有单测，需要真机确认。W2-11 的“收款账户”待业主：后端没有商家可读写的收款账户接口。

### 28.4 本波次仍待业主决定

| 事项 | 来源 | 当前默认 |
|---|---|---|
| UG-06 iOS 是否显示“本局剩余免费 N 次” | qx-b7 | 不显示；小程序已读 `remainingFreeUses` |
| U04-03/§27.2 iOS 能力声明 | qx-b7 | 只解码，不声明 |
| U08-03 维护链、U08-05 新玩法分数入榜与后台榜单治理 | qx-b6 | 不画入口，不加空榜 |
| §18 D 玩家主持小费 | qx-b5 | 渠道返回 501 时不显示任何入口 |
| W2-11 收款账户 | qx-a6 | 不加 |
| UG-12、U08-02、§23.1、§18 A 前端接线 | qx-c1 已补后端 | 接线前不显示 |
