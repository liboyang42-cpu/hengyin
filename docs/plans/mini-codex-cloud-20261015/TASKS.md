## 当前有效约束：保持现有入口，不新增

用户最新明确决定：**“保持现有入口，不新增”**。新增隔离MySQL/Redis集成入口方案已关闭，不再处于等待批准状态，也不再催批同一方案。现有VPS212和CI配置保持原样；现有自动CI自然运行，不手动触发或重复重跑。普通回归只在新真实源码候选或未解失败时由本地按现有212验证，已通过且未变的候选不补跑。

现有入口未覆盖的真实MySQL/Redis项继续为 **NOT_RUN／现有入口覆盖缺口**，包括E02_25MySqlIT2、第三Growth真MySQL11等；不改称通过，不以普通/mock/skip结果代替。已有精确SHA的PASS和失败/锁拒绝历史均保留；真实HTTP/设备业务/集成合并部署仍未验收。此为约束更新，无新增开发或补跑任务。详见[持久决定](VALIDATION_ENTRY_DECISION.json)。C00只维护全局账，各队只维护自有交付账。

### 以下为此前交付与状态历史；涉及新增入口待批的表述已被本决定覆盖

# Codex Cloud 任务卡

> 三方统一协作规则：[CY-COORD-20261015-v1](COORDINATION.md)。当前文件归属与候选见[OWNERSHIP.md](OWNERSHIP.md)；初始规划不等于已领单。最终提示词：[Codex](START_PROMPT.md)、[Claude](CLAUDE_START_PROMPT.md)、[iOS](IOS_START_PROMPT.md)。

2026年10月15日。先读 [README.md](README.md) 和 [CLAUDE.md](CLAUDE.md)。本任务不改原生iOS、Flutter；独立iOS agent另管；所有后端修改保持现有客户端合同兼容，但不因此补做两端代码或测试。

## 文件所有权规则

每个worker只改本单owned_files及专属新增/既有相关测试，开始前把实际文件清单报协调者。`existing_test_files`是已存在的入口，不表示只跑这些就足够。新增测试用本单前缀或已有明确专属套件。共享DTO/响应封装/Controller/Mapper若未在本单列表，须先向C00申领；其他worker不得同时编辑。

以下由协调者独占：根及小程序AGENTS、app.json、请求公共封装、公共DTO/接口、workflow/pom/lockfile、sql及迁移索引、动作/路由清单、门禁基线、共享golden fixtures。[BACKEND_CHANGES.md](BACKEND_CHANGES.md)及IOS_HANDOFF.md由C00/C99协调者独占维护，各worker只提交变化片段；不要并发改同一日志。默认只读；确需改动先核授权。无worker可以为通过测试自行改分母、关闭规则或删基线。

| 任务 | 级别/阶段 | 依赖 | 基础人日 | 原OPT归属 |
|---|---|---|---|---|
| C00 核公共基线并审查已有PR | P0 / 0 | 无 | 2—3 | OPT-001、OPT-002、OPT-003、OPT-071 |
| B01 小程序已有交互与账号作用域bug | P0 / 1 | C00 | 2—4 | OPT-042、OPT-053、OPT-070 |
| B02 作者保存重开与发布的数据保真bug | P0 / 1 | C00 | 3—5 | OPT-030 |
| B03 现有积分券库存与商家账务正确性 | P0 / 1 | C00 | 4—7 | OPT-010、OPT-011、OPT-013、OPT-014 |
| B04 已有系列排期时刻与候补保护bug | P1 / 1 | C00 | 2—4 | OPT-051 |
| B05 已有内容隐私与引用撤回bug | P0 / 1 | C00 | 3—5 | OPT-020 |
| A01 会话命令回执与结果未知恢复底层 | P1 / 2 | B01、B02、B03、B04、B05 | 5—8 | OPT-040、OPT-070、OPT-072 |
| A02 AI网关失败输入审核与用量一致底层 | P0修bug→P1架构 / 1→2 | C00；架构后半等bug批次收口 | 5—8 | OPT-080、OPT-081、OPT-072 |
| A03 剧情校验结局与内容版本兼容底层 | P1 / 2 | B01、B02、B03、B04、B05 | 5—8 | OPT-031、OPT-032 |
| C99 集中集成验证与Claude接口交接 | P0 / 3 | A01、A02、A03 | 3—5 | OPT-090、OPT-091、OPT-092 |

## C00 核公共基线并审查已有PR

主责：集成协调者；依赖：无；基础预算：2—3人日；状态：公共基线与三方交付已核，唯一控制账已发布；接收回执另列。

### 独占源码入口

协调任务，不独占业务源码；公共文件按下述规则集中处理。

### 实施顺序

1. 读取本目录全部文件与根/小程序AGENTS；复核master、PR11父链及PR12–15的SHA/状态/CI，不能以本机未提交内容或旧文档行号为基线。
2. 在公共历史上创建codex/mini-bug-arch-integration-20261015候选分支，保留本任务文档；评审并按父链组合必要提交，不能直接把PR11或任一子PR当master已包含。
3. 先复核PR12的GPS/显式点赞方向/徽章查询范围及PR13音频/退避，已成立修复复用；PR14/15只做既有变更的兼容集成及限制登记，不继续新功能开发。
4. 冻结候选SHA与现有API/DTO/状态/请求身份合同，列文件所有权；把每条历史线索标记复现/已修/误报/裁决不做/待证据，不直接照509个旧编号编码。
5. 核Cloud到专用VPS的实际可用路径、测试版本/库和必要身份；只把凭据放环境安全配置。没有路径时继续源码与GitHub托管CI，VPS/真机验收保持待验证。

### 验收

- [ ] 已有修复未重复实现，PR12/13成对验证；清楚记录PR14退款授权跳过、官方路线只读、认领材料待确认及动作清单失败项。
- [ ] 所有worker使用同一公共候选SHA；必要父提交来源清楚，无私有历史或本机WIP混入。
- [ ] 为每个worker记录独占文件与测试，已验证/作者声称/未跑分开，公共候选的合同与所需测试能力有清单。

### Claude边界和停止条件

不会费/群聊/群投票，不启动原四任务；UI/new-feature增量仍归Claude。

父PR含无法审查的变更则该依赖保持待处理；无网络不做假的合并/CI成功。

回退要求：保留旧请求/响应/数据语义；出现旧数据不可读、结果漂移、越权、重复资产变更即停止推广，回退当前任务代码，不删历史SQL、账本或其他任务改动。交付时附复现/反证、确切文件、测试输出、候选SHA和未验证层。

## B01 小程序已有交互与账号作用域bug

主责：Codex B01 worker；依赖：C00；基础预算：2—4人日；状态：源码与相关Node/候选CI通过，VPS与微信设备待验。

### 独占源码入口

- `chengyinhub-xcx/utils/subscribe.js`
- `chengyinhub-xcx/pages/mylike/mylike.js`
- `chengyinhub-xcx/pages/play/index.js`
- `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-detail/index.js`
- `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-history/index.js`
- `chengyinhub-xcx/subpackageMember/components/scene-member-participation-detail/index.js`

### 已有测试入口

- `chengyinhub-xcx/tests/unit/mylike-refresh-state.test.js`
- `chengyinhub-xcx/tests/unit/mylike-unlike-inflight-lock-contract.test.js`

### 实施顺序

1. 先核PR13已处理音频/常亮/退避，PR14已处理返回/预览/兜底，不能复制第二份工具；P1-048迟到回调和P1-069游标保护已存在，只回归不重做。
2. 补PR14跳过的退款点击授权：逐个核三个真实cancel-refund入口，在用户点击链请求refund/refundRejected；空模板保持真实未配置状态。
3. 复核mylike按下标删行和请求晚到场景，以稳定对象ID/现有在途锁更新当前列表，避免刷新/排序后误删。
4. 复现后修游玩切账号或退出时旧请求/本地状态串用；来源归因其他工具文件如不在本单所有权，先向C00申领，不广扫式清空storage。

### 验收

- [ ] 授权发生在用户手势且最多真实模板列表，拒绝授权不撤销已经成立退款申请，不承诺订阅送达。
- [ ] 刷新/排序/重复取消和晚响应不改变另一条记录；账号A的游玩或请求结果不会落入B。
- [ ] 原有音频/后台/定位/返回合同无回退；新增回归测试真实调用处理器，静态字符串断言不能单独关闭行为bug。

### Claude边界和停止条件

只改既有JS行为与必要测试；新提示、布局、页面、WXML/WXSS由Claude。pages/play交叉变更需C00串行整合。

回退要求：保留旧请求/响应/数据语义；出现旧数据不可读、结果漂移、越权、重复资产变更即停止推广，回退当前任务代码，不删历史SQL、账本或其他任务改动。交付时附复现/反证、确切文件、测试输出、候选SHA和未验证层。

## B02 作者保存重开与发布的数据保真bug

主责：Codex B02 worker；依赖：C00；基础预算：3—5人日；状态：配置保真修复随候选CI通过；首次create丢响应仍未知。

### 独占源码入口

- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiTopicController.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/TopicCreateDTO.java`

### 已有测试入口

- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiTopicSaveDraftContractTest.java`

### 实施顺序

1. 先核PR12对ApiTopicController点赞的修复已在候选，保留同文件其他行为；枚举现有create/save/edit/publish真实DTO与Mapper调用。
2. 用已有保存→读回→重开→修改→发布链复现章节/节点ID、顺序、绑定、规则与revision丢失，修确认有效的数据映射/持久化缺口。
3. 固定同一草稿/主题身份，丢响应先读已存事实；保留旧字段和已发布内容，不造新的草稿体系。
4. 若需要新增Mapper/服务修改，由C00先登记独占范围再编辑；公开分支不存在的本地server-draft实现不能作为已交付依赖。

### 验收

- [ ] 两次编辑/重排后独立读回语义相同，旧作品不被默认值重置；丢响应不重复造主题/章节。
- [ ] 后端编译及针对真实控制器/持久化路径的回归结果明确，前台合同交Claude对接。

### Claude边界和停止条件

作者页面、保存反馈、状态键UI与预演交Claude；Codex只修后端既有行为，不新增产品规则。

新增字段/DDL或变更发布政策先出具体方案，不能以新表补洞。

回退要求：保留旧请求/响应/数据语义；出现旧数据不可读、结果漂移、越权、重复资产变更即停止推广，回退当前任务代码，不删历史SQL、账本或其他任务改动。交付时附复现/反证、确切文件、测试输出、候选SHA和未验证层。

## B03 现有积分券库存与商家账务正确性

主责：Codex B03 worker；依赖：C00；基础预算：4—7人日；状态：冻结1e403b13真实MySQL七项通过，原69c失败历史保留；HTTP真实业务未验。

### 独占源码入口

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PointsAwardServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/SmsCouponHistoryServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantPayableServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantClawbackServiceImpl.java`
- `chengyinhub-system/src/main/resources/mapper/business/UmsMemberPointsDetailMapper.xml`
- `chengyinhub-system/src/main/resources/mapper/business/PmsSkuMapper.xml`

### 已有测试入口

- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/MerchantPayableServiceTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/MerchantClawbackServiceTest.java`

### 实施顺序

1. 沿小程序当前真实报名/核销/积分/券链核调用方、事务和数据库约束，先验证非空eventId三元唯一与现有并发保护，不把先查后写或缺requestId自动判重复入账。
2. 核NULL事件、同用户并发限领、不同业务同时扣同账户、库存与退款追加冲正，先写能复现的并发/事务用例，再做最小修复。
3. 核核销撤销/退款/倒扣对现有商家应付和查询的影响，以账本事实独立对账，历史迁移注释不当当前数据。
4. cart只在当前小程序真实调用或共享依赖证实相关时纳入；其已有锁/删除CAS保留，丢响应恢复先复用既有订单查询。

### 验收

- [ ] 相关并发条件下资产/库存/应付守恒，有独立读回，不靠返回200/同进程内存断言。
- [ ] 旧订单快照、当前积分政策和资金主体不变；支付失败/结果未知与通知失败严格区分。

### Claude边界和停止条件

积分明细/资金中心UI、新领券/储值/提现或支付产品交Claude另行范围；本单不新增业务。

政策单位未定则先证据/方案；需要新唯一索引/DDL或改变鉴权/RBAC另行确认，不改历史迁移。

回退要求：保留旧请求/响应/数据语义；出现旧数据不可读、结果漂移、越权、重复资产变更即停止推广，回退当前任务代码，不删历史SQL、账本或其他任务改动。交付时附复现/反证、确切文件、测试输出、候选SHA和未验证层。

## B04 已有系列排期时刻与候补保护bug

主责：Codex B04 worker；依赖：C00；基础预算：2—4人日；状态：时刻/候补修复及JS消费随候选CI通过，VPS与设备待验。

### 独占源码入口

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ClubEventScheduleMaterializer.java`
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubEventOpsController.java`

### 已有测试入口

- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ClubEventScheduleMaterializerTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiClubEventOpsControllerTest.java`

### 实施顺序

1. 读取现有时刻/时区/物化规则，复现保存重开被09:00覆盖、候补遗漏或editable与服务端保护不一致。
2. 只修已有数据保留/状态校验，创建与修改/删除都按同一真实报名和候补事实判断。
3. 核重复生成与并发修改的已有CAS或防重，保留原时区和系列原时刻，不新增排期策略。

### 验收

- [ ] 非09:00场次保存重开/生成保持时刻，跨日期边界按已有时区合同。
- [ ] 有正式报名或候补的场次均按既有规则受保护，重复调用不重复物化。

### Claude边界和停止条件

官方草稿/邀请转交/暂停恢复等新入口及排期UI归Claude；本单仅既有bug。

回退要求：保留旧请求/响应/数据语义；出现旧数据不可读、结果漂移、越权、重复资产变更即停止推广，回退当前任务代码，不删历史SQL、账本或其他任务改动。交付时附复现/反证、确切文件、测试输出、候选SHA和未验证层。

## B05 已有内容隐私与引用撤回bug

主责：Codex B05 worker；依赖：C00；基础预算：3—5人日；状态：用户批准的已删帖读取修复随CI通过，真实旧链接/媒体待验。

### 独占源码入口

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CommunityPostServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/CommunityPostLifecyclePolicy.java`
- `chengyinhub-system/src/main/resources/mapper/business/CommunityPostMapper.xml`

### 已有测试入口

- `chengyinhub-admin/src/test/java/com/chengyinhub/business/community/CommunityPostLifecyclePolicyTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/community/CommunityPostServiceImplTest.java`

### 实施顺序

1. 核当前实际内容链正文/媒体/评论/引用访问，复现删除关联将COMMUNITY变PUBLIC、旧引用快照持续授权等历史线索。
2. 按既有隐私政策恢复应有检查，退群、撤回、原帖变更后的读面/旧链接和缓存一并验证。
3. 不启用另一内容栈、不扩大授权；若修复必需改变RBAC/授权模型，先提交失败用例与最小方案，等待该变更明确批准。

### 验收

- [ ] 两身份访问原帖与引用、撤回后的相同旧链接结果符合原政策，私密内容不因解绑变公开。
- [ ] 已有公开内容与合法引用仍可用，权限改变没有通过把未知值降级为允许来完成。

### Claude边界和停止条件

举报/申诉新页面、媒体审核工作台、新内容链切换均归Claude；本单只恢复现有政策。

AGENTS规定的鉴权/RBAC逻辑改变必须另批；可先完成取证、回归和方案，不擅自落授权变更。

回退要求：保留旧请求/响应/数据语义；出现旧数据不可读、结果漂移、越权、重复资产变更即停止推广，回退当前任务代码，不删历史SQL、账本或其他任务改动。交付时附复现/反证、确切文件、测试输出、候选SHA和未验证层。

## A01 会话命令回执与结果未知恢复底层

主责：Codex A01 worker；依赖：B01、B02、B03、B04、B05；基础预算：5—8人日；状态：新局结局正文快照与既有请求合同随候选CI通过，跨设备待验。

### 独占源码入口

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/GameSessionRuntimeServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImpl.java`
- `chengyinhub-system/src/main/resources/mapper/business/TopicRouteSessionMapper.xml`

### 已有测试入口

- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/GameSessionRuntimeServiceImplTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImplTest.java`

### 实施顺序

1. 沿已有session/state/query/requestId/CAS与回执链列出成功、明确失败、结果未知、冲突的合同，复用当前存储而非另造一层。
2. 稳定保留原请求身份与指纹；同requestId异命令仍冲突、同命令重放可定位原结果；不在唯一键加action放宽保护。
3. 跨设备恢复先读服务端会话及开始时快照；媒体待上传与有效完成分开，缺必需证据不能发奖。
4. 仅补必要原因码、版本和脱敏trace关联，客户端消费规范交Claude；没有业务需要不引入事件总线/新VM/状态中心。

### 验收

- [ ] 丢响应重进/重复命令/异命令/过期epoch/晚到响应/第二设备都能按原身份读回事实。
- [ ] 不会另建并行会话真源；现有状态、积分/道具只发生一次有效变更，日志不含坐标原文/聊天/凭据。

### Claude边界和停止条件

待核对/恢复/人工协助等新UI交Claude；已有必要JS bug修复由B01所有者整合，A01不写页面文件。

如必须新增持久化字段/索引，先提交合同与兼容方案；本批不执行DDL。

回退要求：保留旧请求/响应/数据语义；出现旧数据不可读、结果漂移、越权、重复资产变更即停止推广，回退当前任务代码，不删历史SQL、账本或其他任务改动。交付时附复现/反证、确切文件、测试输出、候选SHA和未验证层。

## A02 AI网关失败输入审核与用量一致底层

先修已确认的缺配置mock成功、完整输入审核bug，不因A编号推迟。随后同一所有者等待bug批次证据处置再做用量/额度架构后半，文件不交另一个worker并发修改。

主责：Codex A02 worker；依赖：C00；基础预算：5—8人日；状态：配置/完整审核/未知额度修复随CI通过；删除屏障/语音政策/后台未知展示未闭合。

### 独占源码入口

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/AiSceneRouter.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImpl.java`

### 已有测试入口

- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/AiSceneRouterContractTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImplTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/AiQuotaRecoveryServiceTest.java`

### 实施顺序

1. 核两个provider选择分支，当前场景缺配置均明确不可用，生产链不能以mock返回成功；测试替身只在测试域。
2. 核组装后的完整输入与审核实际输入边界，禁止只审前2000字符却把后缀送模型；超限按已有能力分段审核或明确拒绝。
3. 沿现有trace/request身份区分provider真实usage与估算，核失败归还、结果未知对账和文本/语音额度分池；不新定价格与留存政策。
4. PR15的NPC删除入口复用，确认晚到请求与本人全NPC删除语义；必要删除服务文件改动先申领，不能与Claude重复改UI。

### 验收

- [ ] 缺配置/审核失败/超时不造假成功；未审核后缀不能进入模型请求。
- [ ] 失败额度只归还一次，未知结果不盲退款重发；未知usage不显示成0真实成本。
- [ ] 不发生真实付费provider调用来证明单测，不擅自开启新场景/长期记忆。

### Claude边界和停止条件

AI创作入口、新对话UI、模型新场景和新功能后端交Claude；共用网关合同由本单维护。

价格/留存/付费行为与新依赖须明确决策；无真实usage字段能力就标估算，不伪造。

回退要求：保留旧请求/响应/数据语义；出现旧数据不可读、结果漂移、越权、重复资产变更即停止推广，回退当前任务代码，不删历史SQL、账本或其他任务改动。交付时附复现/反证、确切文件、测试输出、候选SHA和未验证层。

## A03 剧情校验结局与内容版本兼容底层

主责：Codex A03 worker；依赖：B01、B02、B03、B04、B05；基础预算：5—8人日；状态：结局写校验与冻结正文读侧随CI通过，历史缺键限制保留。

### 独占源码入口

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ChapterFlowCompiler.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/JourneyStoryValidator.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/ThemeRuntimeConfig.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/ThemeRuntimeEngine.java`

### 已有测试入口

- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/support/JourneyStoryValidatorTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/support/ThemeRuntimeEngineTest.java`

### 实施顺序

1. 固化每个现有运行时的首命中/显式优先级、平局、fallback和旧内容样本，先确认已实现行为。
2. 修确认有效的校验/兼容缺口；读未知类型可受控降级，未知写入/授权值必须拒绝，不造新DSL。
3. 区分协议schema版本、内容版本与会话开始快照；旧会话继续可读，新内容只影响新开局或显式更新草稿。
4. 不得把数组下标回填成降序优先级而反转旧结局；不强行合并各运行时，新选择策略只出方案。

### 验收

- [ ] 相同旧样本得到原结局，旧局在内容更新后仍用开始时快照。
- [ ] 无效新写入被拒绝且有可观察原因；协议/内容版本混用不会使全部旧局被拒。
- [ ] API/数据合同有最小兼容fixture供Claude消费，没有复制出第二套状态键或运行时。

### Claude边界和停止条件

状态键面板、条件编辑器、结果卡、预演和新玩法归Claude；本单仅后端兼容底层。

新结局选择语义、旧内容迁移或新表字段须先确认；不自动采用新策略。

回退要求：保留旧请求/响应/数据语义；出现旧数据不可读、结果漂移、越权、重复资产变更即停止推广，回退当前任务代码，不删历史SQL、账本或其他任务改动。交付时附复现/反证、确切文件、测试输出、候选SHA和未验证层。

## C99 集中集成验证与Claude接口交接

主责：集成协调者；依赖：A01、A02、A03；基础预算：3—5人日；状态：冻结1e403b13本轮VPS自动化矩阵231/231闭环且CI成功；运行时业务/设备/部署未验，后续缺陷另候选。

### 独占源码入口

协调任务，不独占业务源码；公共文件按下述规则集中处理。

### 实施顺序

1. 按文件归属评审各lane结果，在单一候选整合；只由集成人处理公共DTO/迁移目录/请求封装/动作和路由清单/基线。
2. 先跑相关测试，再在候选上集中跑必要门禁和GitHub托管CI；VPS测试串行、隔离资源和限制内存，完整前端套件不由各worker重复跑。
3. 给Claude和独立iOS agent交接口/方法/DTO/错误/版本/权限/幂等/状态与fixture，更新BACKEND_CHANGES.md的实现/合并/部署状态；有新UI或新功能的需求保留Claude责任。
4. 独立VPS业务读回可自动化部分尽量完成；微信真机、设备权限、现场核销、上传和生产发布明确单列待验收，不伪称Cloud已完成。

### 验收

- [ ] 每项有改动或无需改动的证据，候选SHA/测试输出/PR/CI与环境目标可对应。
- [ ] 至少覆盖实际启用的保存发布、GPS/扫码、退出恢复、积分资产、隐私隔离及AI失败边界；未触达层级标未验证。
- [ ] 每个修复小PR或有清晰提交边界的候选草稿PR，发布/合并生产保持待批准；文档只有阶段性完成不得标业务READY。

### Claude边界和停止条件

输出Claude可读的稳定后端合同，不接手其UI/新功能，不做iOS/Flutter。

回退要求：保留旧请求/响应/数据语义；出现旧数据不可读、结果漂移、越权、重复资产变更即停止推广，回退当前任务代码，不删历史SQL、账本或其他任务改动。交付时附复现/反证、确切文件、测试输出、候选SHA和未验证层。
