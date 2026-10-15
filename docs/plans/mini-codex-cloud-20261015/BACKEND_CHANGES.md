# 共用后端变化与iOS影响记录

2026年10月15日初始快照。**记录区分已有公共补丁、计划工作、现有接口和实际部署，计划不等于已变更。** 由C00/C99协调者独占更新；后端各worker提交变化片段，iOS agent按记录消费，不并行修改后端独占源码。iOS任务入口见 [IOS_HANDOFF.md](IOS_HANDOFF.md)。

## 已查询PR状态

| PR | HEAD | 目标分支 | 状态 | 部署证据 |
|---|---|---|---|---|
| [PR #11](https://github.com/liboyang42-cpu/hengyin/pull/11) | 0b301c9b8d18 | codex/mini-foundation-cloud-handoff-20261013 | OPEN草稿，未合并；checks返回0项 | 本轮未核 |
| [PR #12](https://github.com/liboyang42-cpu/hengyin/pull/12) | a5af41f57fa3 | claude/mini-foundation-abc-integration-squash-20261014 | OPEN草稿，未合并；checks返回0项 | 本轮未核 |
| [PR #13](https://github.com/liboyang42-cpu/hengyin/pull/13) | d6cd231e9354 | claude/mini-foundation-abc-integration-squash-20261014 | OPEN草稿，未合并；checks返回0项 | 本轮未核 |
| [PR #14](https://github.com/liboyang42-cpu/hengyin/pull/14) | 6a9647714286 | claude/mini-foundation-abc-integration-squash-20261014 | OPEN草稿，未合并；checks返回0项 | 本轮未核 |
| [PR #15](https://github.com/liboyang42-cpu/hengyin/pull/15) | 8884a678ea30 | claude/mini-foundation-abc-integration-squash-20261014 | OPEN草稿，未合并；checks返回0项 | 本轮未核 |

查询时PR16是文档PR，不能作为这些业务实现已合并/部署的证据。下表状态只对应本次查询，后续以实际提交和运行证据更新。

| 编号 | 主题 | 性质/当前状态 | 来源/责任 | 原生关联 |
|---|---|---|---|---|
| BK-GPS | GPS到点精度 | 已有后端补丁提案，未合并；部署未核 | [PR #12](https://github.com/liboyang42-cpu/hengyin/pull/12) | C00复用，iOS I01 |
| BK-LIKE | 显式点赞方向 | 已有后端补丁提案，未合并；部署未核 | [PR #12](https://github.com/liboyang42-cpu/hengyin/pull/12) | C00复用；iOS实际调用方 |
| BK-BADGE | 平台徽章范围 | 已有后端补丁提案，未合并；部署未核 | [PR #12](https://github.com/liboyang42-cpu/hengyin/pull/12) | C00复用；iOS I07 |
| BK-AUTHOR | 作者保存读回合同 | 计划修复，字段变更未冻结 | B02 | B02→iOS I03 |
| BK-ASSET | 积分券库存及商家账务 | 计划修复，政策不变 | B03 | B03→iOS I04/I07 |
| BK-SCHEDULE | 排期时刻及候补保护 | 计划修复，政策不变 | B04 | B04→iOS I09 |
| BK-PRIVACY | 私密内容引用及撤回 | 计划复核/修复；授权模型变更另批 | B05 | B05→iOS I05 |
| BK-SESSION | 会话回执及结果未知 | 计划整理；逐接口合同保留 | A01 | A01→iOS I02 |
| BK-AI | AI失败完整输入及用量 | 已有线索待修；新字段未冻结 | A02 | A02→iOS I08 |
| BK-NARRATIVE | 结局选择及内容版本 | 计划兼容整理；新策略未批准 | A03 | A03→iOS I03 |
| BK-EXISTING | 现有接口与小程序新入口 | 接口已存在；PR14/15是小程序接线，不是后端已改 | [PR #14](https://github.com/liboyang42-cpu/hengyin/pull/14)、[PR #15](https://github.com/liboyang42-cpu/hengyin/pull/15) | C00核合同；iOS I07/I08/I11按原计划 |

## BK-GPS GPS到点精度

性质：已有后端补丁提案，未合并；部署未核。来源：[PR #12](https://github.com/liboyang42-cpu/hengyin/pull/12)；交界：C00复用，iOS I01。

当前合同或计划：POST /api/play/arrive 坐标判定路径新增可选accuracy，单位米；空白兼容旧调用，非有限/负值拒绝，超过80拒绝。它不是每个游玩API统一新增的必填字段，也不代表已有专用错误码。

iOS影响：当前原生location证据类型与complete字段无accuracy，需要保留真实测量精度，核坐标系/时间/扫码兼容。不能故意省略差精度绕过。

验证：先单测契约，再按目标测试部署核阈值内外/缺失/无效与扫码；不可因PR已写就把服务端视为已上线。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-LIKE 显式点赞方向

性质：已有后端补丁提案，未合并；部署未核。来源：[PR #12](https://github.com/liboyang42-cpu/hengyin/pull/12)；交界：C00复用；iOS实际调用方。

当前合同或计划：POST /api/topic/like：显式type=1赞、type=0取消重复幂等；省略/空type保留旧toggle。非法type的现有回落尚未形成新严格校验合同。

iOS影响：核原生具体operation factory/适配器，明确发送方向；不能按“默认1”改。纯like_list读面不需要多加写权限。

验证：赞→取消→赞、重复/乱序与账号切换；用真实读回核结果，不只看按钮。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-BADGE 平台徽章范围

性质：已有后端补丁提案，未合并；部署未核。来源：[PR #12](https://github.com/liboyang42-cpu/hengyin/pull/12)；交界：C00复用；iOS I07。

当前合同或计划：selectAllActive限制club_id is null，俱乐部定义走独立scope查询。不是新全局徽章授权模型。

iOS影响：平台墙数量/分组/缓存可能变化，不把平台缺席补回俱乐部私有定义，不破坏身份卡降级语义。

验证：两账号/平台墙/俱乐部墙分别核归属与缓存。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-AUTHOR 作者保存读回合同

性质：计划修复，字段变更未冻结。来源：B02；交界：B02→iOS I03。

当前合同或计划：现有create/save/edit/publish的ID、顺序、节点绑定、规则与revision保真。尚未发布新的DTO版本/接口。

iOS影响：保留原生草稿/发布回执与媒体未决状态；等真实差异和fixture后再改decoder，不猜字段或造新草稿实体。

验证：保存重开/重排/二次编辑/丢响应/旧数据样本。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-ASSET 积分券库存及商家账务

性质：计划修复，政策不变。来源：B03；交界：B03→iOS I04/I07。

当前合同或计划：验证实际并发与冲正缺口；不自动改积分倍率、旧订单快照、退款范围或资金主体。cart仅小程序无调用不代表原生没有既有适配。

iOS影响：报价、付款/退款/核销与资产以服务器读回；不要用SDK布尔确认，不自行改旧订单显示单位。

验证：同业务重复/不同业务并发/超时未知/追加冲正及独立账本。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-SCHEDULE 排期时刻及候补保护

性质：计划修复，政策不变。来源：B04；交界：B04→iOS I09。

当前合同或计划：保留原集合时刻/时区；编辑与生成保护纳入真实报名及候补。没有新增已发布日程DTO。

iOS影响：原生已开编辑页和allowedActions需重新读事实，默认时间不能覆盖已存时刻。

验证：非默认时刻、跨日期边界、候补、重复生成、旧页面操作。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-PRIVACY 私密内容引用及撤回

性质：计划复核/修复；授权模型变更另批。来源：B05；交界：B05→iOS I05。

当前合同或计划：保留现有隐私政策，核正文/媒体/引用/旧链接与删除关联。没有批准切换内容链或放宽RBAC。

iOS影响：旧snapshot不是永久访问许可；适配失效/拒绝及缓存收口，不用仅本机隐藏替代后端校验。

验证：作者/非作者/退群/撤回/旧媒体链接的独立再次读取。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-SESSION 会话回执及结果未知

性质：计划整理；逐接口合同保留。来源：A01；交界：A01→iOS I02。

当前合同或计划：复用现有session/state/receipt与CAS；同requestId异命令仍冲突，相同命令定位原结果。没有新建已发布统一回执端点，也没有批准给所有写接口加requestId。

iOS影响：原生已有game/session/receipt、expectedRevision与retryExact；route advance等另有版本字段，不混成一个数字。进程重启保留原请求身份，媒体缺证不算有效完成。

验证：超时、重复/异命令、晚响应、epoch变更、重启与第二设备。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-AI AI失败完整输入及用量

性质：已有线索待修；新字段未冻结。来源：A02；交界：A02→iOS I08。

当前合同或计划：先修缺配置mock成功、完整输入未审核风险，再核provider真实usage/估算、失败归还和未知对账。价格/留存/付费场景不自动改变。

iOS影响：原生区分不可用、明确失败和未知；无usage不能显示成0真实成本。新的字段/原因码以协调者实际diff为准。

验证：缺配置、审核失败、超限、丢响应及额度恢复；没有真实付费调用当单测。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-NARRATIVE 结局选择及内容版本

性质：计划兼容整理；新策略未批准。来源：A03；交界：A03→iOS I03。

当前合同或计划：保留各旧运行时首命中/显式优先级与会话开始快照；不按数组下标降序反转，不硬要求旧内容版本等于新协议版本。

iOS影响：原生渲染消费权威结果；状态键/条件编辑与预演复用已冻结fixture，不重写一套结局真源。

验证：同一旧样本结果不漂移；旧局更新后仍可读；未知写入拒绝。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## BK-EXISTING 现有接口与小程序新入口

性质：接口已存在；PR14/15是小程序接线，不是后端已改。来源：[PR #14](https://github.com/liboyang42-cpu/hengyin/pull/14)、[PR #15](https://github.com/liboyang42-cpu/hengyin/pull/15)；交界：C00核合同；iOS I07/I08/I11按原计划。

当前合同或计划：POST /api/points/list当前一次全量；POST /api/roam/stamp/received；GET /api/preset/route/list、/route/info。style/list属发布预设；POST /api/ai/npc/chat/data/delete按本人全部NPC历史。商家claims proofType/assetId真实语义待确认。

iOS影响：原生WalletCommerce已区别assetPoints非分页与另一个分页points；NPCChatData当前为export只读。不要复制小程序UI或假称原生已接删除。官方路线PR15只有浏览没有按路线开局。

验证：方法/返回/账号隔离/分页/删除范围及认领审批读回；新增原生入口要先纳入已有产品范围。

部署状态：NOT_VERIFIED。本记录不声明目标环境已经具备上述能力。

## 后续每次变更必须补齐

| 字段 | 内容 |
|---|---|
| BK编号/主题 | 复用同一业务编号；精确标记新增、修复、兼容重构或只改小程序 |
| 所有者/相关任务 | 后端责任人或worker，iOS对接任务，Claude新功能归属 |
| 实现状态 | 计划 / 提案PR / 已合并 / 候选已测；对应commit、PR、父基线 |
| 协议差异 | 方法/路径/DTO/必填/单位/返回/错误/权限/分页/requestId/version；没有变化也明确写无协议变化 |
| 兼容影响 | 旧原生调用/旧会话/旧订单/未知枚举/缓存影响；不兼容先停并给可审阅方案 |
| fixture与测试 | 正常/失败/未知/冲突/越权向量；实际运行输出，作者声明与独立验证分开 |
| 部署证据 | 脱敏测试环境标识、运行包版本/commit、数据库目标核实、时间；不记录地址/口令/生产目标 |
| iOS验收 | 待接 / 已适配源码 / 测试通过 / 测试VPS联调 / 真机通过；关联iOS SHA/设备，未做不填通过 |
| 回退 | 兼容窗口、停止条件和回退操作；不删既有迁移或重置未决锁 |

没有新消息或没有部署证据时保持原状态，不把“后端统一”推导成iOS自动开通。测试数据使用明确synthetic fixture，凭据只进获准环境，不进GitHub。
