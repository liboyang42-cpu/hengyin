# 下一批最小合同、逐指标集合与验收边界（方案，未接线）

结论：下一批应先修原到达实例写入并验证Generic TEAM真实读链，再按consumer建立兼容端口；不是新建无人消费Reader，也不是将三来源强行写成PG1。本批没有生产实施授权。下列合同只抽取现有身份/状态/原回执，不增加奖励或退款政策。

## 1. 单一完成写owner

由C负责play完成语义与事务owner；不是多个统计Service各自补完成/发奖。原有Legacy/Game/Generic真实写入可以保留各自存储与事务实现，交付同一明确的完成语义，而不是要求一日内替换全部runtime：

1. 服务端在真实认证actor、当前资格/归属、原scope、冻结配置、原actionId/request fingerprint/版本下执行；先区分ARRIVAL、TASK_VERIFIED、NODE_COMPLETED。拒绝不改变业务/权益/外部动作，允许原拒绝审计。
2. 对应本局原事实、确有资格的本地奖效果、原业务回执终态同事务；保留正常/揭晓/兜底、原submission/receipt/plan。失败不能留下已完成但可重领的裂缝。外部奖/动作使用既有持久意图/确定业务身份，不在展示投影触发；UNKNOWN原号查证，不因TTL释放身份。
3. 重试返回原结果，异载荷同身份冲突；重玩拥有新的session/instance完成事实，但平台奖继续遵守原default/主题唯一事实限制。不要为了兼容统计再调advanceProgress/grantOnce。
4. TEAM/WORLD owner完成仍是团队/世界事实；原actor只是命令执行者，不等于所有成员已个人完成。个人展示可以注明“所属团队完成”，不能自动写每人的PG1/奖/出勤/核销。
5. 原报名/票只有真实调用或SQL能证明时才关联；activity0自玩、无报名团队与历史缺receipt应显式unknown/不适用，不以topic/activity/session数值相等或当前默认版本补证。

## 2. 兼容读取端口的最小信息（供root下一次授写，非新增schema提案）

一个兼容读合同可在现有服务内实现、按调用者消费，不必先造公共平台。最少要让消费方选择：

| 维度 | 必须明确的值/来源 | 当前不能伪造的部分 |
| --- | --- | --- |
| subject | MEMBER / TEAM / WORLD，真实ownerId；原actorMember单列 | 不把队长、付款人、团队全员等同 |
| scope | 真实topic/activity、session/instance、node/chapter、有证据的registration/entitlement | 不通过ID相等匹配；缺报名不默认自玩或任意票 |
| source | LEGACY_PROGRESS / GAME_OUTCOME / GENERIC_PLAYER_SLOT / GENERIC_TEAM_SLOT；原row/owner+slot/event/receipt身份 | 不把Generic基础progress0当重复完成，不把receipt已接收当APPLIED |
| state | ARRIVAL / TASK_VERIFIED / NODE_COMPLETED / THEME_COMPLETED / ENTITLEMENT_REDEEMED / AWARD_EARNED分别返回；正常/兜底方式 | 不造单一completed=true供应所有经济资格 |
| version | 原冻结session snapshot/configVersion与当时规则引用；无历史冻结证据标unknown | 不将live模板或schemaVersion当原冻结配置 |
| time | 原时间+timeKind+是否确知首次终态；Legacy arrival/completion复用要保留不确定性 | 无completed_at不能回填now/checkin假装首次完成；不使用任意后续action时间 |
| lifecycle | CURRENT_RUN / EVER / 原票服务周期 / 明确时间窗；去重key含source身份与业务subject/scope | 不静默把历史全部换latest，或跨run拼成一局；HTTP去重不能替代原奖励唯一身份 |
| eligibility / awards | 原资格判定可引用但不扩大；已发看原award/ledger/徽章，撤销按原状态 | 展示回填零发奖，不把“完成”推成可领券/可核销/可结算 |

兼容读取不应自动跨域查询所有member；仍由入口执行原owner/role/paid/站点/商家/consent权限。只读投影如缺关键字段，应返回带来源的有限事实或明确缺证，不回退宽泛表行数。现有my-completed批量读口、Game分源SQL是可复用起点，不能另造并行口再无人迁移。

## 3. 逐指标期望集合与夹具（期望来自现有定义；全部待受控CI真验）

设P为del0原PG行，O为本session终态outcome，Q为原owner中首次完成node slot，R为原route session，E为原权益/ACTIVE核销；不将这五者无条件union。指标以真实subject/scope去重。

| 指标/消费者 | 原期望集合 | 必须保留的夹具/最小新增负控 |
| --- | --- | --- |
| 到达/当前节点可操作 | 当前instance且checkin非空的P；既有同地点兄弟共享只arrival | default初局、fresh replay、old default已到达再replay（本patch2条）；跨topic、跨chapter、非同地点不共享；都保持status0/不发奖。拒绝/重试回执零业务变化 |
| Legacy节点完成/前驱 | 当前scope P.status1；原CAS首完，非所有P | 0到达不完成；CAS重试不重复奖；photo/ShopDay顺序；自玩两个topic隔离；原reward cap/重玩抑制不改 |
| Game非ROUTE节点复盘 | O中明确COMPLETED/ANSWER_REVEAL/FALLBACK_PLAN按原分类；P只arrival | PENDING/REJECTED不完成，首个outcome不改写；同member正常完成一个node再兜底另一个node时normal∩fallback非空，总人数用集合并而非加法 |
| Generic个人节点与主题 | 本member/current session Q；主题R终态COMPLETED及原result，不以全部raw节点行代替 | 任务验证成功但成本/CAS失败不完成；ANY_N/排他组；结束失败ENDED；重放原slot零二次成本/奖；旧PG0存在而主题已完成仍应源明示 |
| Generic TEAM team/recap | 同session/TEAM Q与冻结章节/终局规则；个人表现不得自动继承 | 复用GenericRoute可信完成夹具后走真实CLUB view/merchant recap/导出；普通ROUTE PG分支正控保持。两个team不串、ANY_N不要求全部station、authorityEpoch/资格失败零事实 |
| my-completed / route榜 | 已接线的CURRENT latest R终态、及该模式原合法完成来源；score仍原定义 | older completed+latest running/ended；仅Generic完成无PG1；源回执不足不走旧completed fallback；本人/全榜一致 |
| Stats.club / square | 先按现有topic/paid/visibility范围；Stats已有route EVER集合；square已有route/explore覆盖，旧进度行次单列 | 历史完成而新局未完、route-only候选、activity0、自玩/真实activity隔离；由root确认显示CURRENT还是EVER后再迁移，不预设两者等同 |
| 纯内容探索单票/批量 | 原报名的required chapter可完成节点集合；任何局内部必须按原instance合同，不能不同局各半拼全 | 同一票/两节点，runA仅n1、runB仅n2：batch与单票差集；有一局真全完成正控；空required/无可玩node原断言保留。当前G05-03无实际DB结论 |
| 勋章墙/成长/club里程 | 原模板章PG1；原player_badge source/code；原onNodeDone/TOPIC_CLEAR奖励事实 | 到达0/兜底cap/重复/default vs replay；Generic虚拟MEDAL不自动变平台奖；同TEAM队员不自动加个人里程；历史投影backfill零奖 |
| 商家visit/客群/现场名单 | 原paid探索票+merchant/offer+E与P到达，reg/chapter粒度；E ACTIVE核销 | 同member多票、跨merchant、退款/核销撤销、仅到达无解题仍是到店；没有真实票不进入付费客群；手机号consent与员工scope负控 |
| onsite completed/wait/inStore | 原票arrival/ACTIVE核销章数、等待时间、未核销权益；不是Q/O解题 | 同reg/chapter最早到达；核销fallback；退款排除、已核销不占未核销店内；重复场次时间差需真实数据，不擅改服务周期 |
| Orient/签到/Bingo/解锁 | 原明确instance/status/review/since、人工校正、历史格子口径分别保留 | ShopDay完成晚于到达的since边界；跨run与review0不能成为计分证据；新投影不能绕paid/人工出勤校正或触发新经济权益 |

“原30 cash结构/修后45”不是此矩阵的运行证据。这里不使用合成Python集合冒充业务验证。

## 4. 最小实施顺序、owner与不触碰面

| 片 | 可独立确定的改动 | owner/前置 | 验收与风险 |
| --- | --- | --- | --- |
| 0 本交付 | 原来源清单、两个review-only arrival反例；无生产代码 | G docs，本批已做 | `git apply --check`仅语法应用证据，不是编译/红例 |
| 1 arrival原写隔离 | 在真实原scope/current session下绑定本次与兄弟节点instance，并确保回读同实例；不改status0/奖励 | C授写后；先受控CI运行原patch定位；考虑调用外层事务/锁序，不在G先写helper | default与replay正控、旧default不变、并发/拒绝零变化；无需新表也不能掩盖原错误 |
| 2 Generic TEAM读链 | 在真实冻结runtime类型识别后，让已消费的CLUB/merchant recap读取该owner的原完成事实；普通ROUTE保留PG；先同一view与冻结recap出口成组 | C生产/Mapper，G合同；root分配。D authoring/发布冻结不碰。需确认当前原事实能证明哪些字段，不造completedAt | 真实command→state→CLUB view/recap/export链，独立团队/原receipt/ANY_N/旧ROUTE全部正负控。不得隐式给团队全员个人奖；历史已冻结recap如何呈现需明确只读策略 |
| 3 其他consumer迁移 | 按具体目的复用已有批量端口或在当前service加兼容读取并迁移实际caller；先读展示，再单独审资格读 | root逐入口授写；不是先造未消费Reader，不改无关controller | current/ever/票服务周期显式，避免N+1、访问控制/分页/去重/客户端语义兼容。任何资格变动另审原规则 |
| 4 历史归位 | 如必要只回填可证明的展示投影，保留源key/未知字段、可重复核对 | 非本批授权，无数据库样本不设计执行回填 | 不写原PG1骗资格、不补奖、不重跑核销/资金、不删原runtime/旧unique/回执。新的DDL必须独立预检/授权/回退方案 |

G不修改C的play/controller/mapper、D的CmsTopic/authoring、A/B资金/付款/退款、既有SQL迁移或CI；本批不新schema。不以测试缺失当bug，也不靠未知业务决定暂停已有合法路径。资金共同fence/lateSUCCESS与退款分摊、人类多人票费仍独立待决，本报告没有关闭它们。

## 5. 需要业务确认的最小问题（目前不新增经济问题）

本批两条优先技术边界不需要用户重新选择支付/分账或团队发奖规则。下一次若要统一页面显示，root可在具体入口确认“Stats.club主题通关人数继续表示历史曾完成，还是改为最新局完成”；影响仅显示集合，改动必须与my-completed/重玩UI说明一起审。当前保留各入口原行为，不要求用户从抽象架构选项猜政策。平台奖励、团队个人认领、多人费用、退款分摊没有新的批准依据，不能由兼容读取替用户决定。
