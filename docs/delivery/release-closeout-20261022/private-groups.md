# 发布收尾 · 64 个私有语义组与历史来源去向（Lane G2，2026-10-22）

> 对应 Doc 1 §6.2 与 §6.3。**本文件只读分析**：本 lane 没有改动任何代码，没有对其他 PR 做任何写操作（没有合并、关闭、标签、评论），没有推送到 master 或私有仓库，没有访问生产资源。机器可读版本见同目录的 `private-groups.json`。

- **核对基线**：公开 master `063a6c3c`（#408 合并），本 lane 运行期间没有前移。
- **输入**：私有交接包中的私有语义组台账、私有提交语义迁移台账（408 提交 / 51 脏记录）、旧 PR 容器去重复核台账。私有来源**只以台账 ID 引用**：`PRIVATE-GROUP-<id>`、`PC-xxx`（私有提交台账第 xxx 条）、`PD-xx`（私有脏记录第 xx 条）。本文件不含私有代码、私有提交 SHA、本地路径、主机或凭据。
- **口径**：历史 `implementationClosed` 只作参考，每组都在当前 master 上重新核对源码与回归。“EQUIVALENT_VERIFIED”只表示源码与自动化回归在 master 上，不表示真机、部署或业务验收。各计数单位互相重叠，不能相加成缺陷数。

## 方法

1. 19 个“已合入并关闭”组：确认对应公开 PR 的 merge commit 是 master 的祖先；检查合并后相关文件是否又被改动；在 master 上逐组定位行为所在行；运行这些组的回归测试（38 个测试文件，`node --test`，**767/767 通过**）。另外完整跑了小程序单测 `npm run test:unit`：**13474/13474 通过**。
2. 其余 45 组：按台账描述的历史意图，由 4 个并行只读子代理逐组对照 master 源码（前端与后端 Controller/Service/DTO），给出分类、`file:line` 证据和最小缺失行为。主控对其中关键结论做了抽查复读（见文末“抽查记录”）。Java 测试没有执行。
3. 408 个历史提交与 51 条脏记录：私有提交台账为 122 个提交给出了组归属；其余提交按旧 PR 容器去重复核台账中“文件 → 历史来源提交”的引用，映射到 PR 子项（PR 号 + 文件路径）；两者都没有的列为“未映射”，并附台账给出的历史分类。

## 分类图例

| 分类 | 含义 |
|---|---|
| EQUIVALENT_VERIFIED | master 已有等价行为（附 file:line、测试或合并 PR） |
| MISSING | master 缺一个已定、可按现行合同实现的行为；给出最小缺失行为和建议 owner |
| NEEDS_OWNER | 文件或行为由活跃 owner 持有，动之前必须先取得 owner 交接 |
| NEEDS_DECISION | 依赖未定的产品规则、文案、字段、设计或资金/奖励政策；给出选项和未决时的行为 |

## 总表

| 分类 | 组数 |
|---|---|
| EQUIVALENT_VERIFIED | 20 |
| MISSING | 27 |
| NEEDS_OWNER | 10 |
| NEEDS_DECISION | 7 |
| **合计** | **64** |

| Doc 1 §6.2 历史状态 | EQUIVALENT_VERIFIED | MISSING | NEEDS_OWNER | NEEDS_DECISION |
|---|---|---|---|---|
| 已合入并关闭（19） | 19 | 0 | 0 | 0 |
| 后端依赖（14） | 0 | 9 | 0 | 5 |
| 活跃 owner（7） | 0 | 0 | 7 | 0 |
| 合同/门禁/文案（16） | 0 | 14 | 2 | 0 |
| 其他单项（8） | 1 | 4 | 1 | 2 |

## MISSING 清单与建议 owner

| # | 组 | 最小缺失行为 | 建议 owner |
|---|---|---|---|
| 1 | `PRIVATE-GROUP-gate-route-provenance` | 孤儿页检查和页面清理审计只应把真实导航消费者传入的路由算作入口（导航 API 调用，或能走到导航的点击处理）。未被使用的 URL 常量或字符串提及不算。还需要一个负控：只有闲置字面量时，页面必须仍判为孤儿。 | root UI 门禁 owner（与 #449 范围协调） |
| 2 | `PRIVATE-GROUP-gate-ledger-read-contract` | 在动作台账中把 GET /api/publish/home 判为读：用精确读例外实现，不放宽 /publish/ 规则；加测试证明它是读，而真实发布写端点仍判为写。旧的 cancel/change 端点在 master 上不存在，不要写回测试。 | root 动作台账集成 owner |
| 3 | `PRIVATE-GROUP-sensor-quiet-shout-compass-reaction` | 只缺 quiet-hold 的展示：校准后显示校准得到的相对阈值，以及“已校准/校准中”状态，数据取自组件自己算出的阈值。不得显示为分贝，也不与任何奖励或次数政策挂钩。 | play 组件 lane（#451 后续），低优先级；没有活跃的运行时 owner |
| 4 | `PRIVATE-GROUP-scan-evidence-copy` | 只在 playkit-scan 组件内加一句简短说明：本次扫码匹配的是本站的码。不得声称核验了到场或在场，也不改父组件的 playkit 奖励合同。 | #408 play 父 owner（只改组件本身） |
| 5 | `PRIVATE-GROUP-official-task-source` | 把漫游入口和列表改名为“官方活动任务”；出现在 my-events 中的行标注“来自你的报名”。不得把报名状态显示为当前资格，资格需要 V2 的 participationState/eligible 字段。 | #408 漫游 owner |
| 6 | `PRIVATE-GROUP-post-governance-F0521` | 在 pages/shezhi 下加一个小的帖子处置组件，从现有设置入口进入：列出本人的帖子处置（me/enforcements），可经 /appeals 提交纯文字申诉，UPHELD 显示“维持处置”，REVERSED 显示“已撤销处置”；ACTIVE 不得暗示已是最终结论。新增的 GET 登记到 get-auth-matrix-contract 测试。 | root 云端前端 lane（设置页），不在 F 当前四项中 |
| 7 | `PRIVATE-GROUP-invite-proposed-terms` | 发送时把 coop/invite 的条款标为“拟议条款”，说明对方接受并成单后才冻结；邀请对象可继续标为已锁定。两份合同测试与文案一起改。 | root coop/invite lane（不在 F invite-detail 范围内） |
| 8 | `PRIVATE-GROUP-task-resource-receipt` | 任务卡要说明：任务进行中不扣费用。只有在 QUEST_RESOLVE 未被拦截时，才按真实结算结果（resourceCosts 或结算输出）显示已扣金额。取消后说明“未扣除任何费用”。不得从 costLabel 或单凭 APPLIED 推出“已预留/已扣/已释放”。 | #408 play 父 owner |
| 9 | `PRIVATE-GROUP-game-support-copy` | 在 fabu 主题详情半屏加两句简短说明：支持状态来自玩法支持矩阵；反馈/工单是独立渠道，不改变支持状态。不改开关和能力。 | G 发布 owner |
| 10 | `PRIVATE-GROUP-activity-collaborators` | 前端：编辑态用 collaboratorsList 回填协作者，阻止 getUserData 覆盖，并把列表标为仅展示署名。后端：owner 更新时要替换协作者记录，删除才能保存；创建时应写入实际的协作者 id。 | 持久化（更新/创建）归后端 lane（Doc1 批次 3）；前端由 G publish/activity owner 跟进（所有权待确认） |
| 11 | `PRIVATE-GROUP-album-X019` | 相册证据的上传失败分两类：网络或 5xx 失败时重试同一张已选图片，且只提交一次；其他失败时请玩家重新选图。相册被接受后，组件显示玩家能看懂的“已提交、只读”说明。 | #408 play 父 owner（play/index.js 与共享上传客户端受保护） |
| 12 | `PRIVATE-GROUP-sandbox-source-label` | 作者本地 D20 预览的结果要标为“沙盒预览”，与正式判定区分；加值标为“作者在模板中设定的固定值”。正式玩法中，只有后端提供来源时才标注加值来源，不得自造。 | #408 play 父 owner |
| 13 | `PRIVATE-GROUP-plan-lock-materials` | 方案面板说明：锁定的物资（无论是交易锁还是方案锁）仍计入负重。PLAN_CHANGE 确认说明：它只释放方案锁和投票，不释放交易锁，不得承诺释放全部占用。 | #408 play 父 owner |
| 14 | `PRIVATE-GROUP-clue-empty-content` | 服务端线索没有内容块时，卡片显示一句中性说明：“暂无可显示的内容”。不得声称来源未确认、他队内容被隐藏或有内容被撤回。 | #408 play 父 owner |
| 15 | `PRIVATE-GROUP-route-card-unavailable` | 把 null 态卡片文案和 toast 改为泛称的“暂不可用”。不得把 null 说成已下架或不公开，因为网络失败也会得到 null。 | IM owner |
| 16 | `PRIVATE-GROUP-photo-fallback-truth` | 在拍照核验组件内，passed 与 flagged 同时为真时，显示兜底放行文案（“机会用完了，这张先算过”）而不是“这张过了”。degraded 保持现有“没有结论”文案。不需要父组件 prop，也不需要 photoVerified 字段。 | #408 play 父 owner（只改子组件，不动父层 play/index） |
| 17 | `PRIVATE-GROUP-coop-review` | 评价提交断网或响应不明时，不要断言“未提交”；先重跑 loadMyReview 回读已有评分，再允许重试，并保留现有身份/生命周期保护。评价阶段标签可选。 | F lane 后续（排在 F 当前包之后，由 root 指派） |
| 18 | `PRIVATE-GROUP-backend-story-alt-text` | 故事编辑器要能读取、编辑、写回图片/音频块的 altText（pro-editor-story 读取与提交 + fabu 输入框）；play 页要把 altText 带进条目并做无障碍呈现：章节图片加 aria-label，音频提供可见或读屏可读的文字稿。 | G 发布 owner（编辑器）+ #408 play 父 owner（玩家呈现） |
| 19 | `PRIVATE-GROUP-backend-calendar-sessions` | 后端按月返回真实俱乐部场次（带日期与 updatedAt）；在此之前前端应说明“月历仅展示已加载主题”，不暗示完整排期。 | 后端 lane（Doc1 批次 3）；前端由 club/detail owner（J）跟进 |
| 20 | `PRIVATE-GROUP-backend-merchant-review-filter` | /api/merchant/reviews/manage 服务端筛选（待回复、低分、有图、平台复审中）并返回全量计数；前端改为传筛选参数、展示服务端计数。 | 后端 lane（Doc1 批次 3）；前端由商家评价页 owner 跟进 |
| 21 | `PRIVATE-GROUP-backend-club-meeting-atomic` | edit-ops 的时间/地点修改改为“仅在未售出时生效”的单条条件更新；零行返回“已售出锁定”错误；响应附通知回执；topic-detail 展示该错误与回执状态。 | 后端 lane（Doc1 批次 3）；前端由俱乐部 topic-detail owner 跟进 |
| 22 | `PRIVATE-GROUP-backend-AI-draft-state` | 后端按 requestId 查询 AI 主题草稿请求的状态与结果；前端在结果未知时保留首份草稿并锁定重试，不把额度变化当作已生成证明。 | 后端 lane（Doc1 批次 3）；前端由 publish/simple owner 跟进 |
| 23 | `PRIVATE-GROUP-backend-share-token-redaction` | 后端按 token 撤销单条分享 + 发布时 version CAS；之后客户端才能提供“撤销这条分享”。“由接收方自述”类文案可作为单独前端候选。 | 后端 lane（Doc1 批次 3）；前端由 roam/privacy-sheet owner（J / #408 roam）跟进 |
| 24 | `PRIVATE-GROUP-backend-club-post-reference` | 后端按当前可见性规则为引用对象已不可见的俱乐部帖标记 refUnavailable；客户端显示安全的“引用不可用”标签并映射错误信息。 | 后端 lane（Doc1 批次 3）；前端由 club/detail owner（J）跟进 |
| 25 | `PRIVATE-GROUP-backend-profile-version-review` | 后端提供版本化资料/媒体提交与审核状态（待审/通过/驳回、当前生效版本）及头像审核回执；gerenziliao 与商家 AI-NPC 头像页据此显示待审/已通过，而不是假定修改已生效。 | 后端 lane（Doc1 批次 3）；前端由资料/商家装修 owner 跟进 |
| 26 | `PRIVATE-GROUP-backend-X039-edition-empty-reason` | 后端返回 editions 为空的原因（未承接、未开售、无权限等）；之后 club/detail 才显示具体空态而非通用空态。 | 后端 lane（Doc1 批次 3）；前端由 club/detail owner（J）跟进 |
| 27 | `PRIVATE-GROUP-backend-NPC-original-request-readback` | 按 requestId 只读返回 NPC 对话请求状态与回复的接口，使关闭/重开后可恢复待回答问题而不重新提交；组件增加“读取恢复”而非重发。 | 后端 lane（Doc1 批次 3）；前端由 #408 play 父 owner（npc-chat / play）跟进 |

**值得优先关注的发现**

- `PRIVATE-GROUP-activity-collaborators`：除了前端回填缺失外，master 后端还有两处问题。创建活动时，每条协作者记录写入的是发布者的 memberId 而不是 collaboratorId（`CmsActivityServiceImpl.java:895-902`，已复读确认）；owner 更新活动时完全不处理协作者（`:952`）。只修前端会显示一个实际从未保存的删除。
- `PRIVATE-GROUP-I-search`（活跃 owner）：搜索结果页在没有收到 `maxPrice` 时，`Number(null)=0` 会启用“0 元上限”，所有有价行都被滤掉（`pages/search2/result/index.js:38`、`utils/discover-search.js:118-125`；已用 node 复现）。正常从 search2 入口进入时总会带上 1000，所以只在不带该参数打开结果页时触发。归 I 搜索 owner 处理。
- `PRIVATE-GROUP-photo-fallback-truth`：次数用完兜底放行时，服务端同时返回 passed 与 flagged，组件先判 passed，于是显示“这张过了”，让人以为照片本身通过（`playkit-photocheck/index.js:62-71`）。只需在子组件内调整分支顺序。
- `PRIVATE-GROUP-backend-share-token-redaction`：现有 `/revoke` 会作废本人**全部**足迹分享（`ApiRoamShareController.java:100-105`）。在后端提供按 token 撤销之前，不得把旧客户端的“撤销这条”搬过来。
- 4 个已合文案组（tutorial-version-copy、group-code-copy、topic-comment-scope、profile-signature-copy）的源码都在 master 上，但**没有锁定文案的专项测试**，属于回归缺口，建议补一个文案合同测试（root 文案 owner）。

## NEEDS_DECISION 选项

### `PRIVATE-GROUP-citylink-visual`

- 选项 A：采用统一的 Citylink 矢量标（浅色页黑、深色页白），新素材和硬币面经设计批准后整包交付
- 选项 B：保持当前 PNG 品牌标与硬币面，只在对比度不足的深色面补一个变体
- 选项 C：完全保持当前品牌，以“已被取代”结案
- 未决时：没有品牌变更证据，也不是运行阻断，设计未确认。未决时保持现有品牌标，不恢复旧 SVG 素材或 mockData。

### `PRIVATE-GROUP-direct-ledger-record-identity`

- 选项 A：确认 SettlementClaim id 可对用户展示，保留现有“原结算单 #”标签，再加一个引用它的客服/争议入口
- 选项 B：改标为内部记录引用（如“记录编号”），仅供联系客服时使用，并加客服入口；不得称为收据或平台结算单号
- 选项 C：在后端提供真实业务单号或收据号之前隐藏该 id；争议入口不带编号
- 未决时：master 已用“结算单”标签展示内部 id，这正是台账警告的措辞，需要产品复核。未决时保持现有显示不变，不新增争议入口或新的结算措辞，也不把 claimNo 当作业务字段引入。

### `PRIVATE-GROUP-backend-X036-identity`

- 涉及行为：发布者实名身份自助变更（申请/查询最新/撤回 + 人工复核队列）及 merchant/apply、publish/fabu 入口。
- 选项 A：自助变更申请 + 人工复核 + 冷却期，并规定已发布内容的归属
- 选项 B：不提供自助，用户联系客服，由后台人工改绑
- 选项 C：只允许修正被驳回/待审的注册，不允许已通过身份改绑
- 未决时：属于账号身份/实名政策，复核方式与已发布内容归属未定。未决时保持现有注册/状态流程，不新增身份变更路由。

### `PRIVATE-GROUP-backend-experience-card-review`

- 涉及行为：后端版本化体验卡投影（封面版本、卡号、审核预览）+ exp-card 组件及券夹/奖励选择器接线。
- 选项 A：审核通过时由后端固定卡号与封面，编辑生成新版本须重新审核
- 选项 B：只有封面、无序列号；小改不重审
- 选项 C：暂缓体验卡，保持现有券/奖励界面
- 未决时：涉及奖励/券与卡号封面设计，不应按旧 UI 重建。未决时保持现有奖励选择器与券夹。

### `PRIVATE-GROUP-backend-X034-reception`

- 涉及行为：商家已接待团列表 + 撤销接待（原因、确认、后端回读）+ 团码倒计时。
- 选项 A：仅在结算/分账前且 N 小时内允许撤销，撤销冲正核销
- 选项 B：商家不可撤销，争议走客服/后台
- 选项 C：先做只读列表，撤销暂缓
- 未决时：撤销影响结算与分账，历史来源也承认撤销时限未定。未决时不上线撤销；后端补列表后可先做只读列表。

### `PRIVATE-GROUP-backend-C1-numeric-attempt-policy`

- 涉及行为：传感器挑战的数值 attemptId、按核验等级的 rewardCeiling、服务端 photoVerified 标记。
- 选项 A：服务端为每次挑战签发 attemptId 与 rewardCeiling，自报结果只给经验/徽章
- 选项 B：不设每次奖励上限，仅靠现有服务端时间复核判定有效
- 选项 C：在核验方案设计前，传感器玩法不发奖励
- 未决时：不得恢复旧的次数/奖励政策。未决时客户端只展示服务端返回内容，字段缺失时不承诺奖励、不显示“已核验”。

### `PRIVATE-GROUP-backend-withdraw-summary`

- 涉及行为：/api/withdrawal/summary 投影（可提、冻结/处理中、渠道），收益页与提现渠道 UI 改用该投影而非用户余额。
- 选项 A：summary = 余额 − 处理中/冻结提现，单一银行卡渠道
- 选项 B：按渠道（银行卡/微信）分别给出可提与限额
- 选项 C：在提现重设计前保持用户余额 + 客服流程（earnings/index.js:78）
- 未决时：资金投影，前端不得自算或虚构可提金额。未决时保持现有余额 + 预检 + 客服流程。

## NEEDS_OWNER（须先交接）

| 组 | owner | master 已有 / 尚缺（摘要） |
|---|---|---|
| `PRIVATE-GROUP-gate-factory-definition` | 现有云端 D owner / open PR #449（UI 静态门禁的 AST 精确定义重写） | master 已具备工厂感知的 U3 主体行为及负控，只差精确定义键。这一项落在 #449 的重写范围内（brief 称 #449 仍有 options 成员写入的漏报待处理），不应另行单独打补丁。 |
| `PRIVATE-GROUP-gate-finite-api-nav` | 静态部分归现有云端 D owner / PR #449；运行时导航守卫由各页面 owner 逐个消费者适配 | 没有证据表明当前有业务故障。不得恢复旧的约 155 页整包白名单。静态门禁正由 #449 重写，所以这一项要等交接。 |
| `PRIVATE-GROUP-club-like-unknown` | club/detail J / #424 后续 owner（改动前先协调） | 台账记载 club/detail 被 J/#424 后续队列占用，需 owner 交接。回读部分现在就能基于现有 isLiked 实现；幂等切换依赖后端。 |
| `PRIVATE-GROUP-play-protected-recovery` | root / #408 play 父 owner | 全部子项位于 #408 保护的 play/roam 文件。master 的 epoch、账号与场次隔离更强，修复时必须保留。上面每个缺口都应逐项交接给 #408 owner，不搬旧补丁。 |
| `PRIVATE-GROUP-nucleation-original-request` | F city-node owner（J #424 部分已合） | 四个 J 入口已经通过 #424 在 master 上覆盖。只剩 city-node 核销路径，由活跃的 F city-node owner 持有。 |
| `PRIVATE-GROUP-F-current-favorites-AI` | F 当前云端四项任务包 | F 正在修改 mylike/收藏与 ai-insight，补任何内容前先与 F 的最终交付去重。偏好弹层不得暗示推荐排除已上线。 |
| `PRIVATE-GROUP-H-voice-original-result` | H 原音色任务 owner | 剩余缺口是：原录音还没确认，UNKNOWN 就被清除。后端原子化的原任务恢复是另一项依赖。 |
| `PRIVATE-GROUP-G-player-host-publish` | G 主持/玩家状态及发布 owner；play 部分归 #408 父 owner | 只展示 master 实际实现的 RUN_PAUSE/INTERRUPT 后果。不承诺冻结 HP、延长倒计时或不计结局，也不沿用旧退款文案。 |
| `PRIVATE-GROUP-J-privacy-purpose-and-play-cache` | 采集状态显示归 J（#439 已合）；清缓存文案归 #408 缓存父 owner | 按用途撤回的核心已经通过 #439 在 master 上实现，不恢复 stopAllHolders。剩余的采集状态显示与清缓存文案都有活跃 owner。 |
| `PRIVATE-GROUP-I-search` | I 搜索云端任务 owner | 0 元上限过滤是 master 上真实存在的正确性缺陷（入口受限），应在 I 包中优先处理。排序/日期文案不得声称后端有排序保证。 |

## 逐组明细

### 已合入并关闭（19）

#### `PRIVATE-GROUP-gate-login-matrix` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 1 个（PC-017）；相关公开 PR：#443
- 合入：#443 (merge 175c4385, ancestor of master)；回归：PRESENT_AND_PASSING
- master：chengyinhub-xcx/scripts/uiaudit/build-route-matrix.js:52 — usesLoginPageFactory 识别实际导出的登录工厂
- master：chengyinhub-xcx/scripts/uiaudit/build-route-matrix.js:92 — needsLogin = 读用户态 或 使用登录页工厂
- 测试：chengyinhub-xcx/tests/unit/route-matrix-freshness.test.js — 'route-matrix 生成物必须与当前 app.json 和 scene 扫描逐字一致' 等 5 项（:49-52 断言 club/merchant needsLogin=true）
- 备注：route-matrix.json 在合并后按当前源码重新生成过，新鲜度测试通过。

#### `PRIVATE-GROUP-sensor-ballshake` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 1 个（PC-115）；相关公开 PR：#437, #450
- 合入：#450 (merge 6325974f, ancestor of master)；回归：PRESENT_AND_PASSING
- master：chengyinhub-xcx/pages/play/components/playkit-ballshake/index.js:75,82 — 隐藏/中断走 _interrupt 隔离
- master：chengyinhub-xcx/pages/play/components/playkit-ballshake/index.wxml:26 — 本机测量与校准说明
- 测试：chengyinhub-xcx/tests/unit/ballshake-recovery.test.js — 19 项，如 'local notice is visible after an interruption, not while awa…'

#### `PRIVATE-GROUP-tutorial-version-copy` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 1 个（PC-048）；相关公开 PR：#447, #450
- 合入：#450 (merge 6325974f, ancestor of master)；回归：NO_DEDICATED_TEST
- master：chengyinhub-xcx/subpackageA/components/scene-how-to-play-detail/index.wxml:24 — “适用玩法版本暂未标注；…以游玩页为准”
- master：chengyinhub-xcx/subpackageA/pages/infomation/infomation.wxml:71 — 列表同口径
- master：chengyinhub-xcx/subpackageA/pages/infomationdetail/infomationdetail.wxml:37 — 分享可达的独立详情同口径
- 测试：chengyinhub-xcx/tests/unit/scene-how-to-play-detail-state-contract.test.js — 空/异常内容态（不锁定版本文案）
- 备注：源码在 master 上；没有锁定该文案的专项测试（回归缺口，建议补文案合同测试，归 root 文案 owner）。

#### `PRIVATE-GROUP-stopwatch-local-copy` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 1 个（PC-084）；相关公开 PR：#450
- 合入：#450 (merge 6325974f, ancestor of master)；回归：PRESENT_AND_PASSING
- master：chengyinhub-xcx/pages/play/components/playkit-stopwatch/index.wxml:23,25 — 本机测量、切后台中断须重新开始
- 测试：chengyinhub-xcx/tests/unit/playkit-stopwatch-miss-contract.test.js — 4 项

#### `PRIVATE-GROUP-merchant-entitlement-F0520` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 1 个（PC-113）；相关公开 PR：#445, #450
- 合入：#450 (merge 6325974f, ancestor of master)；回归：PRESENT_AND_PASSING
- master：chengyinhub-xcx/pages/merchant/decor/index.js:552 — 支付回执只进入 entitlementPending，等同类权益回读
- 测试：chengyinhub-xcx/tests/unit/merchant-decor-entitlement-readback.test.js — 11 项，如 'capabilities accepts only actual boolean'
- 测试：chengyinhub-xcx/tests/unit/merchant-commerce-checkout.test.js

#### `PRIVATE-GROUP-group-code-copy` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 1 个（PC-206）；相关公开 PR：#450
- 合入：#450 (merge 6325974f, ancestor of master)；回归：NO_DEDICATED_TEST
- master：chengyinhub-xcx/pages/club/group-code/index.wxml:13,42 — “活动核验团码”，并注明玩家组队邀请码不能用于核销
- master：chengyinhub-xcx/pages/team/join/index.wxml:19 — 组队邀请码不能用于签到/核销，入队不代替报名购票
- 备注：源码在 master 上；没有锁定该文案的专项测试（回归缺口，建议补文案合同测试，归 root 文案 owner）。

#### `PRIVATE-GROUP-topic-comment-scope` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 1 个（PC-221）；相关公开 PR：#450
- 合入：#450 (merge 6325974f, ancestor of master)；回归：NO_DEDICATED_TEST
- master：chengyinhub-xcx/pages/topic/index/index.wxml:333 — 未参与也可评价；首评积分按当前规则、以实际记录为准
- master：chengyinhub-xcx/pages/topic/index/index.wxml:362 — “评论积分须符合参与及首评条件，以实际积分记录为准”
- 备注：源码在 master 上；没有锁定该文案的专项测试（回归缺口，建议补文案合同测试，归 root 文案 owner）。

#### `PRIVATE-GROUP-IM-original-business-card` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 1 个（PC-256）；相关公开 PR：#451
- 合入：#451 (merge 0636b6ab, ancestor of master)；回归：PRESENT_AND_PASSING
- master：chengyinhub-xcx/subpackageB/pages/im/chat/index.js:8 — 引入 system-card-target
- master：chengyinhub-xcx/subpackageB/pages/im/chat/system-card-target.js:16-17 — 只按已证实的 bizType/bizId 定位当前原单
- 测试：chengyinhub-xcx/tests/unit/im-system-card-record.test.js — 15 项，如 'signup and record sheet explicitly separate generation-time…'
- 测试：chengyinhub-xcx/tests/unit/im-chat-audit-detail.test.js
- 测试：chengyinhub-xcx/tests/unit/bl239-im-nav-guard.test.js

#### `PRIVATE-GROUP-settlement-notification-scope` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 2 个（PC-315, PC-383）；相关公开 PR：#423, #448, #450
- 合入：#450 (merge 6325974f, ancestor of master)；回归：PRESENT_AND_PASSING
- master：chengyinhub-xcx/pages/merchant/ledger/index.js:176 — generation 只在换主体/拒权/卸载时递增
- master：chengyinhub-xcx/pages/merchant/ledger/index.wxml:117 — 结算到账提醒只说明申请通知权限，不承诺到账
- master：chengyinhub-xcx/utils/subscribe.js:30,74 — 只信任本次请求模板的明确 accept/reject/ban
- 测试：chengyinhub-xcx/tests/unit/merchant-ledger.test.js — 17 项
- 测试：chengyinhub-xcx/tests/unit/subscribe.test.js — 12 项

#### `PRIVATE-GROUP-C13-fixture` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_public_fixture_package`；历史来源提交 1 个（PC-337）；相关公开 PR：#441
- 合入：#441 (merge 4d57c295, ancestor of master)；回归：PRESENT_AND_PASSING
- master：chengyinhub-xcx/scripts/fixtures.json:9631 — C13 夹具存在（合并后按生成器重新生成）
- 测试：chengyinhub-xcx/tests/unit/ui-review-0831-marks-contract.test.js:211 — 'C13 营销空态夹具把优惠券落成零张，不是数据待同步'
- 备注：fixtures.json 在合并后重新生成过，C13 测试通过。不等于真机截图验收。

#### `PRIVATE-GROUP-badge-fallback` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 1 个（PC-349）；相关公开 PR：#432
- 合入：#432 (merge a4d19b05, ancestor of master)；回归：PRESENT_AND_PASSING
- master：chengyinhub-xcx/subpackageP3/pages/badge-3d/index.js:37-38 — 渲染 epoch 守卫（隐藏/销毁后不再渲染）
- master：chengyinhub-xcx/subpackageP3/pages/badge-3d/index.js:214 — 3 秒资源超时
- 测试：chengyinhub-xcx/tests/unit/badge-render-recovery.test.js — 9 项
- 测试：chengyinhub-xcx/tests/unit/badge-render-lifecycle-integration.test.js
- 备注：不代表所有 XR 设备验收。

#### `PRIVATE-GROUP-profile-signature-copy` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 1 个（PC-376）；相关公开 PR：#450
- 合入：#450 (merge 6325974f, ancestor of master)；回归：NO_DEDICATED_TEST
- master：chengyinhub-xcx/pages/gerenziliao/gerenziliao.wxml:45 — “修改签名文字不会改变定位城市。”
- 备注：源码在 master 上；没有锁定该文案的专项测试（回归缺口，建议补文案合同测试，归 root 文案 owner）。

#### `PRIVATE-GROUP-invite-name-null-guard` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 1 个（PC-398）；相关公开 PR：#444
- 合入：#444 (merge 6c039616, ancestor of master)；回归：PRESENT_AND_PASSING
- master：chengyinhub-xcx/pages/club/detail/index.wxml:1086 — 邀请组件 target-name 对象存在与空串兜底
- master：chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml:728 — 同上
- 测试：chengyinhub-xcx/tests/unit/coop-invite-flow-contract.test.js
- 测试：chengyinhub-xcx/tests/unit/ui-batch-cleanup-0830-contract.test.js

#### `PRIVATE-GROUP-a11y-shared` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 5 个（PC-031, PC-042, PC-112, PC-215, PC-385）；相关公开 PR：#428
- 合入：#428 (merge ef924e7e, ancestor of master)；回归：PRESENT_AND_PASSING
- master：chengyinhub-xcx/components/cy/btn/index.wxss:14-15 — 最小 88rpx 触控尺寸
- master：chengyinhub-xcx/components/cy/search/index.wxml:18 — aria-busy/状态读屏
- master：chengyinhub-xcx/components/cy/upload/index.wxml:8 — 上传中 aria-busy
- master：chengyinhub-xcx/style/reduced-motion.wxss:2 — prefers-reduced-motion
- 测试：chengyinhub-xcx/tests/unit/shared-a11y-contract.test.js — '共享组件名称、角色、状态与文本替代完整' 等 11 项
- 测试：chengyinhub-xcx/tests/unit/shared-reduced-motion-subscription.test.js
- 测试：chengyinhub-xcx/tests/unit/shared-state-a11y-contract.test.js

#### `PRIVATE-GROUP-adaptive-variants` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 1 个（PC-072）；相关公开 PR：#438
- 合入：#438 (merge 7c2eaff1, ancestor of master)；回归：PRESENT_AND_PASSING
- master：chengyinhub-xcx/pages/publish/utils/publish/advanced-game-config.js:358 — variantWhenError 校验条件类型/运算符/字段
- master：chengyinhub-xcx/pages/publish/utils/publish/advanced-game-config.js:385 — relaxedVariantsError 每行独立克隆校验
- 测试：chengyinhub-xcx/tests/unit/advanced-game-variants-recovery.test.js — 'variants拒绝畸形when而不是只检查存在' 等 7 项
- 测试：chengyinhub-xcx/tests/unit/publish-relax-contract.test.js

#### `PRIVATE-GROUP-nearby-route-and-home` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 3 个（PC-087, PC-235, PC-237）；相关公开 PR：#435, #429
- 合入：#435/#429 (merge f3fb0cb6, ancestor of master)；回归：PRESENT_AND_PASSING
- master：chengyinhub-xcx/pages/topic/index/index.js:60 — 按章节规范化，不跨章连线
- master：chengyinhub-xcx/pages/topic/index/index.wxml:524 — 只展示直线距离，不虚构步行时长
- master：chengyinhub-xcx/pages/index/index.wxml:58-61 — 编辑精选区块（不宣称个人兴趣）
- 测试：chengyinhub-xcx/tests/unit/topic-route-preview-truth.test.js — '道路与直线分段，章节之间不偷偷连成道路' 等 11 项
- 备注：topic/index.wxml 合并后被 #450 文案改动过，路线测试仍通过。

#### `PRIVATE-GROUP-club-draft` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 1 个（PC-209）；相关公开 PR：#430
- 合入：#430 (merge debae9ef, ancestor of master)；回归：PRESENT_AND_PASSING
- master：chengyinhub-xcx/utils/club-edit-draft.js:3-5 — 草稿 key 按账号+俱乐部隔离
- 测试：chengyinhub-xcx/tests/unit/club-edit-draft.test.js — '草稿key按账号和club隔离，匿名及不精确ID不落盘' 等 30 项
- 测试：chengyinhub-xcx/tests/unit/v4-club-edit-cancel.test.js
- 备注：不存在服务端原子 CAS，不宣称跨设备冲突保护。

#### `PRIVATE-GROUP-POI-unknown` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 1 个（PC-334）；相关公开 PR：#433
- 合入：#433 (merge b415c2ab, ancestor of master)；回归：PRESENT_AND_PASSING
- master：chengyinhub-xcx/subpackageMember/components/cy/scene-roam-poi-detail/index.js:22,34 — 写前持久化 owner+poi+attempt 记录并严格校验
- 测试：chengyinhub-xcx/tests/unit/poi-checkin-pending-recovery.test.js — 21 项
- 测试：chengyinhub-xcx/tests/unit/poi-checkin-transport-contract.test.js

#### `PRIVATE-GROUP-signup-voucher` — **EQUIVALENT_VERIFIED**

- 历史处置：`merged_source_contract_closed`；历史来源提交 1 个（PC-377）；相关公开 PR：#426
- 合入：#426 (merge 6c29b493, ancestor of master)；回归：PRESENT_AND_PASSING
- master：chengyinhub-xcx/subpackageMember/mycanyu/mycanyu.js:6 — registrationId 校验
- master：chengyinhub-xcx/subpackageMember/mycanyu/mycanyu.js:100-102 — verificationStatus 只认 0/1，其余 UNKNOWN
- master：chengyinhub-xcx/subpackageMember/mycanyu/mycanyu.js:225 — 用原 registrationId 跳 signup?focusId
- 测试：chengyinhub-xcx/tests/unit/mycanyu-refresh-state.test.js — 10 项
- 测试：chengyinhub-xcx/tests/unit/signup-state-contract.test.js
- 测试：chengyinhub-xcx/tests/unit/signup-return-refresh.test.js

### 后端依赖（14）

#### `PRIVATE-GROUP-backend-X036-identity` — **NEEDS_DECISION**

- 历史处置：`backend_dependency`；历史来源提交 6 个（PC-008, PC-021, PC-177, PC-210, PC-279, PC-304）
- master：chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiMemberIdentityController.java:34-63 — /api/publisher/identity 只有注册 POST / 与 /status，无变更/最新变更/撤回端点
- master：master 上无 identity-change 小程序页面
- **缺失**：发布者实名身份自助变更（申请/查询最新/撤回 + 人工复核队列）及 merchant/apply、publish/fabu 入口。
- **建议 owner**：决定后：后端 lane（Doc1 批次 3），再由发布/商家入驻前端 owner 接线
- **选项**：A：自助变更申请 + 人工复核 + 冷却期，并规定已发布内容的归属；B：不提供自助，用户联系客服，由后台人工改绑；C：只允许修正被驳回/待审的注册，不允许已通过身份改绑
- 备注：属于账号身份/实名政策，复核方式与已发布内容归属未定。未决时保持现有注册/状态流程，不新增身份变更路由。

#### `PRIVATE-GROUP-backend-calendar-sessions` — **MISSING**

- 历史处置：`backend_dependency`；历史来源提交 1 个（PC-029）
- master：chengyinhub-xcx/pages/club/detail/index.js:2050 — 月历读取 /api/club/topics（主题列表，不是带日期的场次）
- master：chengyinhub-xcx/pages/club/detail/index.js:30 — 月格由 utils/club-event-calendar.js 的 buildMonthCalendar 本地生成
- master：admin 控制器中没有俱乐部场次/月历接口
- **缺失**：后端按月返回真实俱乐部场次（带日期与 updatedAt）；在此之前前端应说明“月历仅展示已加载主题”，不暗示完整排期。
- **建议 owner**：后端 lane（Doc1 批次 3）；前端由 club/detail owner（J）跟进
- 备注：诚实的已加载范围说明与切月刷新可前端单拆，但 club/detail 由 J 持有；不得虚构场次。

#### `PRIVATE-GROUP-backend-merchant-review-filter` — **MISSING**

- 历史处置：`backend_dependency`；历史来源提交 1 个（PC-073）
- master：chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiMerchantReviewController.java:41-50 — /manage 只收 pageNum/pageSize，无筛选/状态参数与计数
- master：chengyinhub-xcx/pages/merchant/reviews/index.wxml:79-88,135 — 全部/待回复/低分/有图 只过滤已加载页（filteredItems）
- **缺失**：/api/merchant/reviews/manage 服务端筛选（待回复、低分、有图、平台复审中）并返回全量计数；前端改为传筛选参数、展示服务端计数。
- **建议 owner**：后端 lane（Doc1 批次 3）；前端由商家评价页 owner 跟进
- 备注：当前计数只覆盖已加载页，可能失真；只改文案不解决问题。

#### `PRIVATE-GROUP-backend-club-meeting-atomic` — **MISSING**

- 历史处置：`backend_dependency`；历史来源提交 1 个（PC-083）
- master：chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubLeadController.java:259-262 — 先 countOpenSaleRegistrations 再单独 updateCmsActivity（检查与写入非原子）
- master：ApiClubLeadController.java:263 — 更新行数未校验
- master：ApiClubLeadController.java:264-267 — 通知发起人只写日志，响应中无通知回执
- 测试：chengyinhub-xcx/tests/unit/club-director-ops-time-contract.test.js — 现有运营时间修改合同测试
- **缺失**：edit-ops 的时间/地点修改改为“仅在未售出时生效”的单条条件更新；零行返回“已售出锁定”错误；响应附通知回执；topic-detail 展示该错误与回执状态。
- **建议 owner**：后端 lane（Doc1 批次 3）；前端由俱乐部 topic-detail owner 跟进
- 备注：已售锁定规则已有裁决（:256-258 注释），只缺无竞态实现与回执；前端无法提供原子性。

#### `PRIVATE-GROUP-backend-experience-card-review` — **NEEDS_DECISION**

- 历史处置：`backend_dependency`；历史来源提交 2 个（PC-090, PC-371）
- master：master 上不存在 components/cy/exp-card/*
- master：admin 控制器中没有体验卡版本化封面/编号/审核预览投影
- **缺失**：后端版本化体验卡投影（封面版本、卡号、审核预览）+ exp-card 组件及券夹/奖励选择器接线。
- **建议 owner**：决定后：后端 lane（Doc1 批次 3），再由商家营销/发布奖励选择器 owner 接线
- **选项**：A：审核通过时由后端固定卡号与封面，编辑生成新版本须重新审核；B：只有封面、无序列号；小改不重审；C：暂缓体验卡，保持现有券/奖励界面
- 备注：涉及奖励/券与卡号封面设计，不应按旧 UI 重建。未决时保持现有奖励选择器与券夹。

#### `PRIVATE-GROUP-backend-X034-reception` — **NEEDS_DECISION**

- 历史处置：`backend_dependency`；历史来源提交 5 个（PC-108, PC-157, PC-249, PC-323, PC-406）
- master：chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiVerifyController.java:257,272 — 只有 groupcode/issue 与 groupcode/redeem，无接待列表/撤销
- master：master 上不存在 pages/merchant/group-reception/* 与 utils/group-reception.js
- **缺失**：商家已接待团列表 + 撤销接待（原因、确认、后端回读）+ 团码倒计时。
- **建议 owner**：决定后：后端 lane（Doc1 批次 3），再由商家前端 owner 接线
- **选项**：A：仅在结算/分账前且 N 小时内允许撤销，撤销冲正核销；B：商家不可撤销，争议走客服/后台；C：先做只读列表，撤销暂缓
- 备注：撤销影响结算与分账，历史来源也承认撤销时限未定。未决时不上线撤销；后端补列表后可先做只读列表。

#### `PRIVATE-GROUP-backend-AI-draft-state` — **MISSING**

- 历史处置：`backend_dependency`；历史来源提交 1 个（PC-126）
- master：chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAiController.java:149,168 — 只有 theme/draft 与 theme/draft/quota，无按请求查询状态/结果
- master：chengyinhub-xcx/pages/publish/simple/index.js:84,111 — 客户端只调 quota 与 draft
- 测试：chengyinhub-xcx/tests/unit/simple-ai.test.js — 现有简易发布 AI 测试
- **缺失**：后端按 requestId 查询 AI 主题草稿请求的状态与结果；前端在结果未知时保留首份草稿并锁定重试，不把额度变化当作已生成证明。
- **建议 owner**：后端 lane（Doc1 批次 3）；前端由 publish/simple owner 跟进
- 备注：本地锁与文案子集可前端单拆，但无法解决生成结果未知。

#### `PRIVATE-GROUP-backend-C1-numeric-attempt-policy` — **NEEDS_DECISION**

- 历史处置：`backend_dependency`；历史来源提交 2 个（PC-138, PC-245）
- master：chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AdvancedGameRuntimeServiceImpl.java:499 — START_CHALLENGE 只记录服务端开始时间与次数
- master：master 全库无 rewardCeiling；传感器挑战无数值 attemptId 与奖励上限
- master：master 上不存在 pages/play/utils/sensor-contract.js
- **缺失**：传感器挑战的数值 attemptId、按核验等级的 rewardCeiling、服务端 photoVerified 标记。
- **建议 owner**：奖励政策决定后：#408 play 父 owner + 后端 lane
- **选项**：A：服务端为每次挑战签发 attemptId 与 rewardCeiling，自报结果只给经验/徽章；B：不设每次奖励上限，仅靠现有服务端时间复核判定有效；C：在核验方案设计前，传感器玩法不发奖励
- 备注：不得恢复旧的次数/奖励政策。未决时客户端只展示服务端返回内容，字段缺失时不承诺奖励、不显示“已核验”。

#### `PRIVATE-GROUP-backend-share-token-redaction` — **MISSING**

- 历史处置：`backend_dependency`；历史来源提交 2 个（PC-143, PC-393）
- master：chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRoamShareController.java:57 — 发布快照只返回 token，无 version
- master：ApiRoamShareController.java:100-105 — revoke() 不收 token，调用 deleteByMember 作废本人全部分享
- master：漫游分享隐私链路无脱敏/version 字段
- 测试：chengyinhub-xcx/tests/unit/roam-share-snapshot.test.js — 现有快照测试
- 测试：chengyinhub-xcx/tests/unit/roam-route-privacy.test.js — 现有路线隐私测试
- **缺失**：后端按 token 撤销单条分享 + 发布时 version CAS；之后客户端才能提供“撤销这条分享”。“由接收方自述”类文案可作为单独前端候选。
- **建议 owner**：后端 lane（Doc1 批次 3）；前端由 roam/privacy-sheet owner（J / #408 roam）跟进
- 备注：原样搬旧客户端会把“撤销一条”变成“撤销全部”。敏感区域脱敏属于新隐私功能，启用前需产品决定。

#### `PRIVATE-GROUP-backend-club-post-reference` — **MISSING**

- 历史处置：`backend_dependency`；历史来源提交 1 个（PC-182）
- master：master 全部 admin/system Java 中无 refUnavailable 生产者
- master：chengyinhub-xcx/pages/club/detail/index.js — 帖子历史只有通用错误态（owner J）
- **缺失**：后端按当前可见性规则为引用对象已不可见的俱乐部帖标记 refUnavailable；客户端显示安全的“引用不可用”标签并映射错误信息。
- **建议 owner**：后端 lane（Doc1 批次 3）；前端由 club/detail owner（J）跟进
- 备注：只在客户端加标签会误导为已做可见性过滤。club/detail 由 J 持有。

#### `PRIVATE-GROUP-backend-withdraw-summary` — **NEEDS_DECISION**

- 历史处置：`backend_dependency`；历史来源提交 2 个（PC-239, PC-257）
- master：chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiWithdrawalController.java:52,77,111 — 只有 list/create/delete，无 /summary
- master：chengyinhub-xcx/subpackageA/pages/assetcenter/earnings/index.js:31,105,137 — 可提现金额仍取自用户信息余额
- master：master 上不存在 components/cy/withdraw-channel/*
- 测试：chengyinhub-xcx/tests/unit/earnings-withdraw-submit-state-contract.test.js — 现有提交状态合同
- **缺失**：/api/withdrawal/summary 投影（可提、冻结/处理中、渠道），收益页与提现渠道 UI 改用该投影而非用户余额。
- **建议 owner**：资金决定后：后端 lane（Doc1 批次 3），再由资产中心收益页 owner 接线
- **选项**：A：summary = 余额 − 处理中/冻结提现，单一银行卡渠道；B：按渠道（银行卡/微信）分别给出可提与限额；C：在提现重设计前保持用户余额 + 客服流程（earnings/index.js:78）
- 备注：资金投影，前端不得自算或虚构可提金额。未决时保持现有余额 + 预检 + 客服流程。

#### `PRIVATE-GROUP-backend-profile-version-review` — **MISSING**

- 历史处置：`backend_dependency`；历史来源提交 1 个（PC-258）
- master：chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiUmsMemberController.java:48-500 — 资料接口无版本化草稿/媒体审核状态
- master：admin/system 主源码中无 profileVersion/reviewVersion 字段
- **缺失**：后端提供版本化资料/媒体提交与审核状态（待审/通过/驳回、当前生效版本）及头像审核回执；gerenziliao 与商家 AI-NPC 头像页据此显示待审/已通过，而不是假定修改已生效。
- **建议 owner**：后端 lane（Doc1 批次 3）；前端由资料/商家装修 owner 跟进
- 备注：没有后端审核状态，客户端无法诚实地显示“审核中”。

#### `PRIVATE-GROUP-backend-X039-edition-empty-reason` — **MISSING**

- 历史处置：`backend_dependency`；历史来源提交 1 个（PC-350）
- master：chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubCompensationController.java:31,66,87 — 只有 hours/report 与 editions，无空原因
- master：chengyinhub-xcx/pages/club/detail/index.js:1174 — 读取 /api/club-compensation/editions，只拿到空数组
- **缺失**：后端返回 editions 为空的原因（未承接、未开售、无权限等）；之后 club/detail 才显示具体空态而非通用空态。
- **建议 owner**：后端 lane（Doc1 批次 3）；前端由 club/detail owner（J）跟进
- 备注：前端不得从空数组猜原因。club/detail 由 J 持有。

#### `PRIVATE-GROUP-backend-NPC-original-request-readback` — **MISSING**

- 历史处置：`backend_dependency`；历史来源提交 2 个（PC-353, PC-367）
- master：chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAiNpcController.java:173,204,273 — 只有 chat/shop-chat/voice-chat，无只读请求状态/结果路由
- master：chengyinhub-xcx/subpackageMember/components/cy/npc-chat/index.js:98-122 — 发送并重试幂等 requestId，但无回读恢复结果
- 测试：chengyinhub-xcx/tests/unit/npc-chat-component.test.js — 现有 npc-chat 组件测试
- **缺失**：按 requestId 只读返回 NPC 对话请求状态与回复的接口，使关闭/重开后可恢复待回答问题而不重新提交；组件增加“读取恢复”而非重发。
- **建议 owner**：后端 lane（Doc1 批次 3）；前端由 #408 play 父 owner（npc-chat / play）跟进
- 备注：重发原请求是否会重复计费/重复生成未经证实。play/index 归其 owner。

### 活跃 owner（7）

#### `PRIVATE-GROUP-play-protected-recovery` — **NEEDS_OWNER**

- 历史处置：`active_owner`；历史来源提交 10 个（PC-007, PC-167, PC-043, PC-045, PC-404, PC-160, PC-283, PC-300, PC-311, PC-316）；相关公开 PR：#408
- master：chengyinhub-xcx/pages/roam/index.js:5609-5620 — 已有：清除前确认、写存储失败可见、同时作废足迹分享链接
- master：chengyinhub-xcx/utils/roam-player-memory.js:149-155 — 已有：clearLocal 清空 tiles/reveals/sessions/recovery 并使缓存 epoch 失效；缺：清除前列出将丢失的恢复项、核对未结算的原场次、仅清显示的选项（PC-007/PC-167）
- master：chengyinhub-xcx/pages/roam/index.wxml:676-682 — 确认文案只提迷雾/足迹/分享链接，未提恢复记录与未结算场次
- master：chengyinhub-xcx/pages/play/finish-route-recommendation.js:7-35 — 已有：finishNearbyRoute===true 开关、距离有限值才显示、金额未知时 priceText 为空；缺：缺失/越界坐标拒绝（:8 处 Number(null)=0 被接受）、标注“直线距离”、区分参与条件与购票保证（PC-043/PC-404）
- master：chengyinhub-xcx/pages/play/index.js:7632-7670 — 已有 epoch/场次/账号隔离加载器；缺：加载/失败/空/位置未知状态，请求无失败分支（PC-045）
- master：chengyinhub-xcx/pages/play/index.wxml:1295 — 卡片 aria-label 仍写“查看并购票”
- master：chengyinhub-xcx/pages/play/index.js:1872-1875 — 已有：closeShopNpc 递增会话 epoch，丢弃迟到结果（PC-316 部分）
- master：chengyinhub-xcx/pages/play/index.js:1991-2016 — _sendVoice 缺 ASR 时退回“(语音)”占位；缺：UNKNOWN 期间保留 requestId、独立音频状态、跨重开的 UNKNOWN 锁（PC-160/PC-316）
- master：chengyinhub-xcx/pages/play/index.js:1785 — openShopNpc 没有真实对话/语音能力开关或资格校验（PC-311）
- master：chengyinhub-xcx/pages/play — play/index.js 与 utils/story-resume.js 中没有待同步心声与重试（PC-283）
- master：chengyinhub-xcx/pages/play/index.wxml:159 — 暂停行显示长按结束与剩余站提示；缺：说明“暂停只停本机计时”（PC-300）
- master：chengyinhub-xcx/pages/play/index.js:7934-7937 — 音频 onPause 只复位 audioPlaying，不复位 audioLoading
- **建议 owner**：root / #408 play 父 owner
- 备注：全部子项位于 #408 保护的 play/roam 文件。master 的 epoch、账号与场次隔离更强，修复时必须保留。上面每个缺口都应逐项交接给 #408 owner，不搬旧补丁。

#### `PRIVATE-GROUP-nucleation-original-request` — **NEEDS_OWNER**

- 历史处置：`mixed_merged_and_active_owner`；历史来源提交 2 个（PC-020, PC-205）；相关公开 PR：#424
- master：chengyinhub-xcx/utils/verification-readback.js:85-131 — 已有：按 owner/目标持久化的 UNKNOWN 记录，页面销毁重建后可恢复（J #424）
- master：chengyinhub-xcx/utils/merchant-verification.js:28-59 — 已有：失败/HTTP 异常/响应畸形统一归为 unknown/202，并提示不要重复扫码
- master：chengyinhub-xcx/pages/merchant/index/index.js:1096,1116-1117；subpackageMember/components/cy/profile/index.js:656,675-676；subpackageMember/components/scene-member-participation-detail/index.js:107,126-128 — 已有：三个入口接入持久工作流并可回读（PC-020 已覆盖）
- master：chengyinhub-xcx/pages/club/detail/index.js:939,958 — 已有：俱乐部扫码走持久工作流，unknown 时禁止重放（PC-205 俱乐部部分已覆盖）
- master：chengyinhub-xcx/pages/merchant/citynode/index.js:476-486 — 缺：city-node 核销仍直接调用 sendRequest，断网只提示“重试”，没有 unknown/工作流/核销码锁，couponGranted 未要求严格布尔
- 测试：chengyinhub-xcx/tests/unit/verification-unknown-adapters.test.js — '持久待办销毁重建'、'重建跨账号隔离，B明确成功只清B待办'、'deadline持锁、迟到成功不能解锁'
- 测试：chengyinhub-xcx/tests/unit/verification-readback.test.js
- **建议 owner**：F city-node owner（J #424 部分已合）
- 备注：四个 J 入口已经通过 #424 在 master 上覆盖。只剩 city-node 核销路径，由活跃的 F city-node owner 持有。

#### `PRIVATE-GROUP-F-current-favorites-AI` — **NEEDS_OWNER**

- 历史处置：`active_owner`；历史来源提交 4 个（PC-024, PC-025, PC-180, PC-233）
- master：chengyinhub-xcx/pages/mylike/mylike.js:343-394 — 已有：按 ID 防重复请求、按 ID 移除；缺：断网只提示重试，没有 unknown 状态、没有经 like_list 回读，也不保留原对象（PC-024）
- master：chengyinhub-xcx/components/cy/scene-route-content/index.js:346-372 — 已有按 ID 移除；缺：防重复请求与失败后的 unknown/回读
- master：chengyinhub-xcx/pages/mylike/mylike.wxml — 缺：说明“取消收藏不撤回推荐兴趣”（PC-025）；缺：偏好不可用说明弹层（PC-180）
- master：chengyinhub-xcx/pages/merchant/marketing/ai-insight/index.wxml:19,33 — 已有：生成时间与 facts.window
- master：chengyinhub-xcx/pages/merchant/marketing/ai-insight/index.js:40-45,141-159 — 已有 facts/profile/generatedAt 与未知事实状态；缺：来源/资料版本对照说明（PC-233）
- **建议 owner**：F 当前云端四项任务包
- 备注：F 正在修改 mylike/收藏与 ai-insight，补任何内容前先与 F 的最终交付去重。偏好弹层不得暗示推荐排除已上线。

#### `PRIVATE-GROUP-H-voice-original-result` — **NEEDS_OWNER**

- 历史处置：`active_owner`；历史来源提交 1 个（PC-139）
- master：chengyinhub-xcx/pages/merchant/decor/ai-npc/index.js:624-654 — 已有：状态白名单 0-3，voiceSample 与上传 url 一致时才清本地待处理录音；缺：只要状态有效就把 voiceUnknown 置为 false，即使样本与原录音不一致（:641-645）
- master：chengyinhub-xcx/pages/merchant/decor/ai-npc/index.js:920-955 — 已有：提交后注册失败会置 voiceUnknown 并回读状态；缺口：注册请求不带请求身份，成功时对 voiceStatus 直接 Number() 不做类型校验
- master：chengyinhub-xcx/pages/merchant/decor/ai-npc/index.wxml:226-235 — 已有：unknown 期间禁止重录/上传，并提供“查看生成状态”按钮
- **建议 owner**：H 原音色任务 owner
- 备注：剩余缺口是：原录音还没确认，UNKNOWN 就被清除。后端原子化的原任务恢复是另一项依赖。

#### `PRIVATE-GROUP-G-player-host-publish` — **NEEDS_OWNER**

- 历史处置：`active_owner`；历史来源提交 4 个（PC-156, PC-200, PC-336, PC-394）；相关公开 PR：#408
- master：chengyinhub-xcx/pages/club/utils/generic-director-ops.js:4-30 — 已有：RUN_PAUSE/RUN_RESUME/RUN_INTERRUPT/RUN_CANCEL 等中文标签与 irreversible 标记
- master：chengyinhub-xcx/pages/club/topic-detail/director.js:597-605,856-867 — 已有：站点暂停确认及后果说明、通用操作确认（区分不可逆与规则校验）；缺：RUN_PAUSE/INTERRUPT 各自的后果文案（PC-156）
- master：chengyinhub-xcx/pages/play/utils/theme-player-ops.js:6-11,110-125 — 已有：TEAM_EXIT 确认、已退队文案、结果未知时回读回执；缺：玩家侧场次 PAUSED/INTERRUPTED 提示，以及区分“离开页面/退出场次”的分模式后果说明（PC-156/PC-200）
- master：chengyinhub-xcx/pages/publish/fabu/index.js:1208-1230 — 已有：插图受操作保护，并核对 owner/章节/相邻块（PC-336 图片部分已覆盖）
- master：chengyinhub-xcx/pages/publish/fabu/index.js:1250-1262 — 缺：uploadStoryAudio 没有对应的操作保护（PC-336 音频部分）
- master：chengyinhub-xcx/pages/publish/fabu/index.js:2719-2728 — 锁定字段弹窗仍写“下架→退款→重发”；缺：“复制为独立草稿”路径（PC-394）
- master：chengyinhub-xcx/pages/publish/fabu/index.js:19-23,2845-2849 — writeAiDraft 跳转前不检查写入是否失败（PC-394）
- **建议 owner**：G 主持/玩家状态及发布 owner；play 部分归 #408 父 owner
- 备注：只展示 master 实际实现的 RUN_PAUSE/INTERRUPT 后果。不承诺冻结 HP、延长倒计时或不计结局，也不沿用旧退款文案。

#### `PRIVATE-GROUP-J-privacy-purpose-and-play-cache` — **NEEDS_OWNER**

- 历史处置：`active_owner`；历史来源提交 4 个（PC-168, PC-179, PC-361, PC-392）；相关公开 PR：#439, #408
- master：chengyinhub-xcx/pages/privacy/index.js:370-398 — 已有：先 bgTracker.revoke('roam') 再 recordConsent，审计失败也会停止本机采集，并提示重试/补传（J #439；PC-179/PC-361 已覆盖）
- master：chengyinhub-xcx/pages/shezhi/components/privacy-sheet/index.js:59-76 — 已有：同样按用途先撤回
- master：chengyinhub-xcx/pages/roam/index.js:2702,3771 — 已有：goStart 每次征求同意；用 observeRevocation('roam') 替代旧的全局撤回标志
- master：master 缺：撤回后在隐私页/漫游页显示采集状态；漫游“仅清显示”及无术语的清除文案（PC-168/PC-392），属于 #408 漫游清缓存组（pages/roam/index.wxml:676-682）
- 测试：chengyinhub-xcx/tests/unit/privacy-location-withdraw-immediate.test.js — '审计仍在途即同步停止真实漫游consumer，不等Promise'、'POST及latest均失败仍停该purpose，独立purpose继续持有系统采集'
- **建议 owner**：采集状态显示归 J（#439 已合）；清缓存文案归 #408 缓存父 owner
- 备注：按用途撤回的核心已经通过 #439 在 master 上实现，不恢复 stopAllHolders。剩余的采集状态显示与清缓存文案都有活跃 owner。

#### `PRIVATE-GROUP-I-search` — **NEEDS_OWNER**

- 历史处置：`active_owner`；历史来源提交 4 个（PC-190, PC-234, PC-289, PC-402）
- master：chengyinhub-xcx/utils/discover-search.js:103-107 — rowPrice 直接 Number(raw)：价格为 null 时得 0、布尔得 0/1，不拦负数，也没有 priceText（PC-190）
- master：chengyinhub-xcx/pages/search2/result/index.js:38 + utils/discover-search.js:118-125 — master 上的潜在缺陷：结果页未收到 maxPrice 时存为 null，Number(null)=0 会启用“0 元上限”，所有有价行都被滤掉（本 lane 已用 node 复现：filterRows('topic',[{price:20}],{maxPrice:null}) 返回空）。search2 入口总是传 1000，所以只在不带 maxPrice 打开结果页时触发（PC-289）
- master：chengyinhub-xcx/pages/search2/index.wxml:162-170 — 只剩“最新”排序；缺：默认排序口径说明（PC-234）
- master：chengyinhub-xcx/utils/discover-search.js:110-117 — 日期为客户端过滤，无日期的行直接通过；缺：已加载范围说明、dateText、未知日期标注（PC-402）
- **建议 owner**：I 搜索云端任务 owner
- 备注：0 元上限过滤是 master 上真实存在的正确性缺陷（入口受限），应在 I 包中优先处理。排序/日期文案不得声称后端有排序保证。

### 合同/门禁/文案（16）

#### `PRIVATE-GROUP-gate-factory-definition` — **NEEDS_OWNER**（门禁）

- 历史处置：`existing_contract_gate`；历史来源提交 2 个（PC-119, PC-142）
- master：chengyinhub-xcx/tests/lint/ui-integrity.js:526-571 — U3 在其它已挂载页面绑定共享工厂方法时放行，但共享定义只按 `${entry.file}#${name}` 作键（:540、:566），不是精确的定义区间
- master：chengyinhub-xcx/scripts/lib/xcx-scan.js:450,467 — extractOwnHandlerEntries 已为每个定义返回文件与起止偏移，可以构造 file:start:end 键，但 U3 没有使用
- master：chengyinhub-xcx/subpackageTools/anniversary/index.js:1,6 — 该页面经共享 subpackageTools/lib/runtime 工厂构建
- 测试：chengyinhub-xcx/tests/unit/xcx-factory-handler-scan.test.js — 'U3只合并同一实际shared定义的已挂载消费者，完全无消费仍红'、'未知methods可能覆盖公共函数时保守判红'、'条件return不是受支持静态合并形态，不假定实际走末尾分支'
- **缺失**：同一文件里的两个工厂若定义了同名方法，U3 按 file#name 作键，其中一个仍可能豁免另一个。master 缺精确定义键（file:start:end）和针对这种情况的负控。
- **建议 owner**：现有云端 D owner / open PR #449（UI 静态门禁的 AST 精确定义重写）
- 备注：master 已具备工厂感知的 U3 主体行为及负控，只差精确定义键。这一项落在 #449 的重写范围内（brief 称 #449 仍有 options 成员写入的漏报待处理），不应另行单独打补丁。

#### `PRIVATE-GROUP-gate-finite-api-nav` — **NEEDS_OWNER**（门禁）

- 历史处置：`existing_contract_gate_and_scoped_runtime_review`；历史来源提交 3 个（PC-014, PC-172, PC-381）
- master：chengyinhub-xcx/tests/lint/ui-integrity.js:409-492 — U1：请求路径没有后端 Mapping 判红；运行时变量路径判红，除非调用点在白名单中，或经受控包装函数且首参是可枚举的 /api 字面量
- master：chengyinhub-xcx/tests/lint/ui-integrity.js:77 — U1 变量路径白名单从基线读取
- master：chengyinhub-xcx/tests/unit/nav-route-exists.test.js:50-80,90-94 — 动态拼接的导航路径必须与显式清单一致，出现偏差即失败
- master：master 没有 utils/registered-navigation.js，也没有对未知站内导航目标的运行时守卫
- 测试：chengyinhub-xcx/tests/unit/nav-route-exists.test.js — '动态拼接的导航路径逐条列出,不静默放过'、'negative control：未盘点的模板串导航必须判红'、'negative control：url/path/focusPath 坏路径必须经真实 source scanner 判红'
- 测试：chengyinhub-xcx/tests/unit/ui-integrity-selftest-cli.test.js — UI 门禁 selftest 负控
- **缺失**：变量 API 路径与动态导航已经靠白名单或清单校验，大体等价。master 缺：(1) 对每个动态 API/导航值集合做有限枚举（而不是整个调用点放行）；(2) 对未知站内导航目标的运行时守卫。
- **建议 owner**：静态部分归现有云端 D owner / PR #449；运行时导航守卫由各页面 owner 逐个消费者适配
- 备注：没有证据表明当前有业务故障。不得恢复旧的约 155 页整包白名单。静态门禁正由 #449 重写，所以这一项要等交接。

#### `PRIVATE-GROUP-gate-route-provenance` — **MISSING**（门禁）

- 历史处置：`existing_contract_gate`；历史来源提交 2 个（PC-238, PC-352）
- master：chengyinhub-xcx/tests/unit/nav-route-exists.test.js:24 — PAGE_TARGET 把任何 `url:`/`path:`/`focusPath:` 字面量都算作导航命中，不要求真的被 navigateTo/redirectTo/switchTab/reLaunch 使用
- master：chengyinhub-xcx/tests/unit/nav-route-exists.test.js:195-204 — orphansOf 把这类字面量（以及 scene-registry 路由）都当作页面可达的证明
- master：chengyinhub-xcx/tests/tools/audit-page-cleanup.js:17-35 — 页面清理审计把任何包含该页路径的非测试行都算作引用
- 测试：chengyinhub-xcx/tests/unit/nav-route-exists.test.js — 'app.json 每一页至少有一处运行时跳转指向它,否则必须在豁免表里写明原因(孤儿页负控)'、'负控:把一页的唯一跳转拿掉,孤儿页负控必须变红'
- 测试：chengyinhub-xcx/tests/unit/page-cleanup-audit.test.js — 'page cleanup audit covers the manifest and retains unresolved external and cross-page contracts'
- **缺失**：孤儿页检查和页面清理审计只应把真实导航消费者传入的路由算作入口（导航 API 调用，或能走到导航的点击处理）。未被使用的 URL 常量或字符串提及不算。还需要一个负控：只有闲置字面量时，页面必须仍判为孤儿。
- **建议 owner**：root UI 门禁 owner（与 #449 范围协调）
- 备注：不恢复旧的原生标题清单；标题已由 master 上的 title-system-owner-contract 测试覆盖。

#### `PRIVATE-GROUP-gate-ledger-read-contract` — **MISSING**（门禁）

- 历史处置：`existing_contract_gate`；历史来源提交 2 个（PC-267, PC-403）；相关公开 PR：#426
- master：chengyinhub-xcx/scripts/uiaudit/build-action-ledger.js:66 — API_WRITE_RE 含 `/publish` 后接 `/` 或结尾，因此 /api/publish/home 会被匹配
- master：chengyinhub-xcx/scripts/uiaudit/build-action-ledger.js:85 — isWriteApi 对 /api/publish/home 没有读例外
- master：chengyinhub-xcx/pages/template/index.js:202、chengyinhub-xcx/pages/publish/activity/index.js:767 — /api/publish/home 的真实只读消费者
- 测试：chengyinhub-xcx/tests/unit/action-ledger.test.js — 没有断言把 /api/publish/home 判为读
- **缺失**：在动作台账中把 GET /api/publish/home 判为读：用精确读例外实现，不放宽 /publish/ 规则；加测试证明它是读，而真实发布写端点仍判为写。旧的 cancel/change 端点在 master 上不存在，不要写回测试。
- **建议 owner**：root 动作台账集成 owner
- 备注：#426（已合）只改了 mycanyu/signup 的台账数据与测试，没有改 isWriteApi。依赖闭包部分在 master 上已经更严，所以只缺这条读例外。

#### `PRIVATE-GROUP-scan-evidence-copy` — **MISSING**（文案）

- 历史处置：`existing_contract_bounded_copy`；历史来源提交 1 个（PC-066）
- master：chengyinhub-xcx/pages/play/components/playkit/index.wxml:166 — play 父组件仍可进入扫码玩法
- master：chengyinhub-xcx/pages/play/components/playkit-scan/index.wxml:1-112 — 没有说明扫码成功证明了什么（只是匹配本站静态码），也没有 evidence 属性
- master：chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AdvancedGameRuntimeServiceImpl.java:2386-2401 — submitScan 只把提交的码与节点静态 checkinCode 比较，然后记完成、加 xp；没有到场或时效核验
- 测试：chengyinhub-xcx/tests/unit/playkit-scan-overlay-contract.test.js、playkit-scan-reveal-ui-contract.test.js — 现有扫码 UI 合同，均未覆盖“匹配”说明文案
- **缺失**：只在 playkit-scan 组件内加一句简短说明：本次扫码匹配的是本站的码。不得声称核验了到场或在场，也不改父组件的 playkit 奖励合同。
- **建议 owner**：#408 play 父 owner（只改组件本身）
- 备注：后端行为只支撑“码已匹配”的说法，不支撑到场声明。master 现有文案没有错误声明，只是缺少这句澄清。

#### `PRIVATE-GROUP-official-task-source` — **MISSING**（文案）

- 历史处置：`existing_contract_bounded_copy`；历史来源提交 2 个（PC-080, PC-363）
- master：chengyinhub-xcx/pages/roam/index.wxml:628-630 — 漫游抽屉入口仍叫泛称的任务/附近挑战，而不是官方活动任务
- master：chengyinhub-xcx/components/cy/scene-roam-task-list/index.js:50,56 — 加载 /api/official/events 与 /api/official/my-events，但每行没有来源标注（例如“来自你的报名”）
- **缺失**：把漫游入口和列表改名为“官方活动任务”；出现在 my-events 中的行标注“来自你的报名”。不得把报名状态显示为当前资格，资格需要 V2 的 participationState/eligible 字段。
- **建议 owner**：#408 漫游 owner
- 备注：报名记录不等于资格，不照搬旧的资格文案。brief 提到的测试文件在 master 上不存在。

#### `PRIVATE-GROUP-post-governance-F0521` — **MISSING**（前后端包）

- 历史处置：`existing_backend_frontend_package`；历史来源提交 3 个（PC-092, PC-228, PC-395）
- master：chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCommunityPostController.java:25,215-224 — master 已有 POST /api/v1/community/appeals 与 GET /api/v1/community/me/enforcements
- master：chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CommunityPostServiceImpl.java:1233,1246,1278 — 申诉结论只接受 UPHELD 与 REVERSED，REVERSED 撤销原处置
- master：chengyinhub-xcx/pages/shezhi/components/ — 只有 marketing-consent 与 privacy-sheet，没有 post-governance 组件，小程序也没有任何地方调用 me/enforcements
- **缺失**：在 pages/shezhi 下加一个小的帖子处置组件，从现有设置入口进入：列出本人的帖子处置（me/enforcements），可经 /appeals 提交纯文字申诉，UPHELD 显示“维持处置”，REVERSED 显示“已撤销处置”；ACTIVE 不得暗示已是最终结论。新增的 GET 登记到 get-auth-matrix-contract 测试。
- **建议 owner**：root 云端前端 lane（设置页），不在 F 当前四项中
- 备注：后端在 master 上已完整，只缺前端消费者。实现应基于会持久化待处理 owner 状态的较新公开版本，而不是旧的纯内存版本。club/governance 是另一个功能。

#### `PRIVATE-GROUP-invite-proposed-terms` — **MISSING**（文案）

- 历史处置：`existing_contract_bounded_copy`；历史来源提交 3 个（PC-114, PC-243, PC-364）
- master：chengyinhub-xcx/pages/coop/invite/index.wxml:40 — 卡片标题写“发送确认时锁定”
- master：chengyinhub-xcx/pages/coop/invite/index.wxml:118 — 发送确认半屏把条款标为“已锁定”
- master：chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CoopInviteServiceImpl.java:728,840-851 — 条款快照实际在对方接受、生成合作单时才冻结，而不是发送时
- 测试：chengyinhub-xcx/tests/unit/coop-invite-flow-contract.test.js:261-266 — '发送确认半屏同时回显已锁定邀请对象与合作条款'（断言当前的“锁定”文案）
- 测试：chengyinhub-xcx/tests/unit/ui-final-gap-zero-0830-contract.test.js:85-90 — '协作对象列表是裸行头像样式，确认页回显锁定对象与条款'
- **缺失**：发送时把 coop/invite 的条款标为“拟议条款”，说明对方接受并成单后才冻结；邀请对象可继续标为已锁定。两份合同测试与文案一起改。
- **建议 owner**：root coop/invite lane（不在 F invite-detail 范围内）
- 备注：后端只支撑“拟议 vs 冻结”的区分。旧版关于资格/资源来源的额外陈述未经核实，不得带过来。

#### `PRIVATE-GROUP-task-resource-receipt` — **MISSING**（合同适配）

- 历史处置：`protected_contract_adaptation`；历史来源提交 1 个（PC-159）；相关公开 PR：#408
- master：chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/GameplayTaskEngine.java:127-133 — QUEST_START 只做结算预演，QUEST_CANCEL 只清状态，因此没有任何预留或释放
- master：chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/GameplayTaskEngine.java:138-144 — settlePendingOutcome 可能在 inventory.settle 实际扣费之前就返回 blocked
- master：chengyinhub-xcx/pages/play/utils/theme-gameplay.js:97 — 进行中的任务只显示“已选费用”（frozenText），没有未扣/已扣/未扣费结束等状态
- master：chengyinhub-xcx/pages/play/utils/theme-backpack.js:480-489 — 回执身份会核对，但单凭 APPLIED 并不展示为扣费证明
- 测试：master 上没有 theme-reservation-states 测试（旧测试未迁移）
- **缺失**：任务卡要说明：任务进行中不扣费用。只有在 QUEST_RESOLVE 未被拦截时，才按真实结算结果（resourceCosts 或结算输出）显示已扣金额。取消后说明“未扣除任何费用”。不得从 costLabel 或单凭 APPLIED 推出“已预留/已扣/已释放”。
- **建议 owner**：#408 play 父 owner
- 备注：旧的“已预留/已释放”文案与后端不符，因为开始任务时并不预留。按实际结算语义适配，不新定扣费政策。

#### `PRIVATE-GROUP-game-support-copy` — **MISSING**（文案）

- 历史处置：`existing_contract_bounded_copy`；历史来源提交 1 个（PC-169）
- master：chengyinhub-xcx/pages/publish/fabu/topic-detail-sheet.wxml:89-90 — fieldRuntimeState 卡只显示支持矩阵加载失败与重试（“仍可保存，支持情况以保存结果为准”），没有区分玩法支持状态与反馈/工单渠道
- master：chengyinhub-xcx/pages/publish/fabu/index.js:7705-7709 — 支持矩阵的加载与恢复
- **缺失**：在 fabu 主题详情半屏加两句简短说明：支持状态来自玩法支持矩阵；反馈/工单是独立渠道，不改变支持状态。不改开关和能力。
- **建议 owner**：G 发布 owner
- 备注：只改文案，具体措辞由 fabu owner 定。brief 提到的测试文件在 master 上不存在。

#### `PRIVATE-GROUP-activity-collaborators` — **MISSING**（前后端包）

- 历史处置：`existing_backend_frontend_package`；历史来源提交 1 个（PC-171）
- master：chengyinhub-xcx/pages/publish/activity/index.js:1181 — applyExistingActivity 不读取 /api/activity/info 返回的 collaboratorsList
- master：chengyinhub-xcx/pages/publish/activity/index.js:737,1249-1281 — getUserData 总会执行，把 formData.collaborators/collaboratorList 重置为当前 owner，没有编辑态保护
- master：chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiActivityController.java:1220-1224 — 后端活动详情确实返回 collaboratorsList
- master：chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsActivityServiceImpl.java:952 — updateActivityByOwner 从不读取 request.getCollaborators()，所以协作者的修改不会保存
- master：chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsActivityServiceImpl.java:895-902 — 创建时每条协作者记录写入的是发布者 memberId，而不是 collaboratorId（本 lane 已核读源码确认）
- **缺失**：前端：编辑态用 collaboratorsList 回填协作者，阻止 getUserData 覆盖，并把列表标为仅展示署名。后端：owner 更新时要替换协作者记录，删除才能保存；创建时应写入实际的协作者 id。
- **建议 owner**：持久化（更新/创建）归后端 lane（Doc1 批次 3）；前端由 G publish/activity owner 跟进（所有权待确认）
- 备注：只做前端回填会显示一个从未保存的删除，因为 owner 更新路径忽略协作者。创建路径给每个协作者都写发布者 id，是一个独立的潜在缺陷，值得确认。

#### `PRIVATE-GROUP-album-X019` — **MISSING**（受保护 owner 包）

- 历史处置：`protected_owner_package`；历史来源提交 2 个（PC-196, PC-358）；相关公开 PR：#408
- master：chengyinhub-xcx/pages/play/utils/playkit-r5-segments.js:38-63 — 服务端拒绝 SUBMIT_ALBUM 时复用已存的上传 URL；上传失败没有分类
- master：chengyinhub-xcx/pages/play/index.js:6713-6714 — 相册拍摄/提交走 _submitKitPhoto；上传失败只 toast，不保留同一图片的重试身份
- master：chengyinhub-xcx/utils/transport/upload-client.js:288-295 — 上传失败只带 msg（以及 oversize/upgradeRequired），没有把网络/5xx 标记为可重试
- master：chengyinhub-xcx/pages/play/components/playkit-album/index.wxml:6-7 — 有重新提交/重新选择操作，但没有失败分类态，也没有提交后只读说明
- 测试：chengyinhub-xcx/tests/unit/play-r5-album-feedback.test.js — '相册：服务端拒绝不完成，原上传证据原幂等键重发且不重新上传'、'相册：原证据被拒后可重新选择照片，不被重试按钮锁住'
- **缺失**：相册证据的上传失败分两类：网络或 5xx 失败时重试同一张已选图片，且只提交一次；其他失败时请玩家重新选图。相册被接受后，组件显示玩家能看懂的“已提交、只读”说明。
- **建议 owner**：#408 play 父 owner（play/index.js 与共享上传客户端受保护）
- 备注：服务端拒绝后用同一证据重发，master 已经实现。上传失败分类、上传失败后重试同一张图、已提交只读文案都还没有。play/index 父层接线由 #408 父 owner 持有。

#### `PRIVATE-GROUP-sandbox-source-label` — **MISSING**（受保护 owner 包）

- 历史处置：`protected_owner_package`；历史来源提交 1 个（PC-216）；相关公开 PR：#408
- master：chengyinhub-xcx/utils/game-preview-action.js:2-8 — previewD20Result 返回 values/kept/total/success/text，没有沙盒/预览标记
- master：chengyinhub-xcx/pages/play/components/playkit-diceroll/index.wxml:16 — 只显示“加值 ±N”，没有来源标注，也没有本地预览徽标
- master：chengyinhub-xcx/pages/play/utils/playkit-view.js:712 — 正式玩法 preview:false，modifier 取自分段配置；后端没有 modifierSource
- 测试：chengyinhub-xcx/tests/unit/game-preview-action.test.js — 现有预览动作测试，没有沙盒断言
- **缺失**：作者本地 D20 预览的结果要标为“沙盒预览”，与正式判定区分；加值标为“作者在模板中设定的固定值”。正式玩法中，只有后端提供来源时才标注加值来源，不得自造。
- **建议 owner**：#408 play 父 owner
- 备注：其他预览类型（note、photocheck、typein）已经返回仅预览提示，D20 还没有。不新增加值或奖励规则。

#### `PRIVATE-GROUP-plan-lock-materials` — **MISSING**（受保护 owner 包）

- 历史处置：`protected_owner_package`；历史来源提交 1 个（PC-306）；相关公开 PR：#408
- master：chengyinhub-xcx/pages/play/theme-plan-list.wxml:15 — 每种物资只显示“可用 N · 锁定 M”，没有说明
- master：chengyinhub-xcx/pages/play/utils/theme-plans.js:129-131 — PLAN_CHANGE 确认文案是泛称（“影响当前票轮与签名”），没说明释放哪些锁
- master：chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/GameplayCooperationEngine.java:748 — PLAN_CHANGE 只重置方案商议状态
- 测试：chengyinhub-admin/src/test/java/com/chengyinhub/business/service/support/GameplayCooperationEngineTest.java:492 — 围绕 PLAN_CHANGE 的锁定工具计数
- **缺失**：方案面板说明：锁定的物资（无论是交易锁还是方案锁）仍计入负重。PLAN_CHANGE 确认说明：它只释放方案锁和投票，不释放交易锁，不得承诺释放全部占用。
- **建议 owner**：#408 play 父 owner
- 备注：只改文案；当前后端语义已经支撑这些说法，不需要新的锁定政策。

#### `PRIVATE-GROUP-clue-empty-content` — **MISSING**（合同适配）

- 历史处置：`protected_bounded_copy_candidate`；历史来源提交 1 个（PC-319）；相关公开 PR：#408
- master：chengyinhub-xcx/pages/play/utils/theme-backpack.js:38-43 — 服务端线索被展平为 sources/blocks；blocks 为空数组时列表为空且没有文字
- master：chengyinhub-xcx/pages/play/theme-backpack.wxml:38 — 没有 blocks 的线索只显示名称和范围，没有空内容说明
- 测试：chengyinhub-xcx/tests/unit/theme-clue-cards.test.js — '服务端回读的线索：范围单列，正文与图片按来源原样保留；没有 clues 时与旧行为一致'
- **缺失**：服务端线索没有内容块时，卡片显示一句中性说明：“暂无可显示的内容”。不得声称来源未确认、他队内容被隐藏或有内容被撤回。
- **建议 owner**：#408 play 父 owner
- 备注：服务端隐私与不撤回是另一回事：服务端投影已把线索限定在本队和公开范围，这两个文件并不实现那些承诺。本项只做空态文案。

#### `PRIVATE-GROUP-route-card-unavailable` — **MISSING**（文案）

- 历史处置：`existing_contract_bounded_copy`；历史来源提交 1 个（PC-325）
- master：chengyinhub-xcx/subpackageB/pages/im/chat/index.js:454-464 — 非 200 响应和网络 fail() 都把 topicCache 置为 null
- master：chengyinhub-xcx/subpackageB/pages/im/chat/index.js:1026 — 值为 null 时提示路线“已下架”
- master：chengyinhub-xcx/subpackageB/pages/im/chat/index.wxml:84-88 — null 态对路线卡与报名卡都显示“已下架”
- master：chengyinhub-xcx/subpackageB/pages/im/chat/index.js:442-444 — 路线卡已使用消息投影数据（“路线已不可用”），隐私上更强
- 测试：chengyinhub-xcx/tests/unit/im-chat-route-card-null-mileage.test.js — 只覆盖里程与封面，不覆盖不可用文案
- **缺失**：把 null 态卡片文案和 toast 改为泛称的“暂不可用”。不得把 null 说成已下架或不公开，因为网络失败也会得到 null。
- **建议 owner**：IM owner
- 备注：旧改动中的后端摘要与可见性部分不是小程序改动，也没有显示后端缺口。

### 其他单项（8）

#### `PRIVATE-GROUP-sensor-quiet-shout-compass-reaction` — **MISSING**

- 历史处置：`runtime_repairs_merged_remaining_local_display_scope`；历史来源提交 4 个（PC-019, PC-067, PC-219, PC-301）；相关公开 PR：#440, #451
- master：chengyinhub-xcx/pages/play/components/playkit-quiethold/index.js:196-203 — 校准算出 _mid/_hot 阈值，但从不写入 data
- master：chengyinhub-xcx/pages/play/components/playkit-quiethold/index.wxml:11 — 只有固定说明（本机相对 PCM、不是分贝），没有实际阈值读数或校准状态
- master：#440（reaction）与 #451（quiet/shout/compass 运行时与文案）已合入 master
- 测试：chengyinhub-xcx/tests/unit/quiet-sensor-recovery.test.js — quiet 视图描述本机相对 PCM，不含 dB 或奖励承诺；无 PCM 或畸形 PCM 不能校准或完成，真实 0 也有效
- 测试：chengyinhub-xcx/tests/unit/reaction-recovery.test.js、shout-sensor-recovery.test.js、compass-sensor-recovery.test.js — 已合运行时修复的回归
- **缺失**：只缺 quiet-hold 的展示：校准后显示校准得到的相对阈值，以及“已校准/校准中”状态，数据取自组件自己算出的阈值。不得显示为分贝，也不与任何奖励或次数政策挂钩。
- **建议 owner**：play 组件 lane（#451 后续），低优先级；没有活跃的运行时 owner
- 备注：reaction、shout、compass 的运行时修复已通过 #440/#451 在 master 上。台账把这一展示子项暂缓，等优先级裁决（不是产品规则）。不恢复 attemptId、rewardCeiling 或跳过政策。

#### `PRIVATE-GROUP-citylink-visual` — **NEEDS_DECISION**

- 历史处置：`design_decision_unconfirmed`；历史来源提交 2 个（PC-131, PC-191）
- master：chengyinhub-xcx/images/cy_logo_mark.png、chengyinhub-xcx/images/d_logo.png — 当前 PNG 品牌标；master 上没有 SVG 或白色变体
- master：chengyinhub-xcx/components/cy/qr-voucher/index.wxml:26 — 二维码中心使用 cy_logo_mark.png（黑色版）
- master：chengyinhub-xcx/pages/index/index.wxml:34 — 首页 logo 使用 cy_logo_mark.png
- master：chengyinhub-xcx/pages/talent/list/index.js:24 — 俱乐部列表回退为 /images/d_logo.png，不按视图切换颜色
- master：chengyinhub-xcx/pages/play/components/playkit-coinflip/logo.js:1 — 硬币面使用原型自带的 logo
- **建议 owner**：root 产品/设计确认队列
- **选项**：A：采用统一的 Citylink 矢量标（浅色页黑、深色页白），新素材和硬币面经设计批准后整包交付；B：保持当前 PNG 品牌标与硬币面，只在对比度不足的深色面补一个变体；C：完全保持当前品牌，以“已被取代”结案
- 备注：没有品牌变更证据，也不是运行阻断，设计未确认。未决时保持现有品牌标，不恢复旧 SVG 素材或 mockData。

#### `PRIVATE-GROUP-club-like-unknown` — **NEEDS_OWNER**

- 历史处置：`existing_backend_readback_adaptation`；历史来源提交 1 个（PC-146）；相关公开 PR：#424
- master：chengyinhub-xcx/pages/club/detail/index.js:2903-2918 — 只有请求中锁；断网时删锁并 toast，不回读目标状态
- master：chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubController.java:1552-1556 — post/list 按会员投影布尔 isLiked
- master：chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubController.java:1673 — post/like 是切换并返回 liked，没有目标状态幂等
- 测试：chengyinhub-xcx/tests/unit/bl-197-club-post-like-report.test.js — '点赞业务失败提示服务端文案,网络失败提示「网络异常，请重试」,成功只回读'、'同一条动态返回前重复点赞只发一次,不同动态互不影响'
- **缺失**：点赞结果未知（断网）时应保持锁定，回读 post/list，用严格布尔 isLiked 与目标意图比对后再允许下一次切换；锁和意图按 owner+俱乐部隔离。目标状态幂等仍需后端合同。
- **建议 owner**：club/detail J / #424 后续 owner（改动前先协调）
- 备注：台账记载 club/detail 被 J/#424 后续队列占用，需 owner 交接。回读部分现在就能基于现有 isLiked 实现；幂等切换依赖后端。

#### `PRIVATE-GROUP-direct-ledger-record-identity` — **NEEDS_DECISION**

- 历史处置：`product_wording_and_field_dependency`；历史来源提交 1 个（PC-218）
- master：chengyinhub-xcx/components/cy/club-direct-ledger/view-model.js:10-16,48 — claimId 已被投影为 claimNo（带 Long 安全校验）
- master：chengyinhub-xcx/components/cy/club-direct-ledger/index.wxml:20 — 已显示“原结算单 #{{claimNo}}”；没有争议/客服入口
- master：chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ClubDirectMerchantReceivableService.java:56-60 — claimId 是内部 SettlementClaim 行 id，不是业务单号或收据
- 测试：chengyinhub-xcx/tests/unit/club-direct-ledger.test.js — '合法原结算单号原样展示，缺失或非法单号不伪造也不丢合法账目'
- **建议 owner**：root 产品/字段确认队列
- **选项**：A：确认 SettlementClaim id 可对用户展示，保留现有“原结算单 #”标签，再加一个引用它的客服/争议入口；B：改标为内部记录引用（如“记录编号”），仅供联系客服时使用，并加客服入口；不得称为收据或平台结算单号；C：在后端提供真实业务单号或收据号之前隐藏该 id；争议入口不带编号
- 备注：master 已用“结算单”标签展示内部 id，这正是台账警告的措辞，需要产品复核。未决时保持现有显示不变，不新增争议入口或新的结算措辞，也不把 claimNo 当作业务字段引入。

#### `PRIVATE-GROUP-W2-companion-wiring` — **EQUIVALENT_VERIFIED**

- 历史处置：`mixed_existing_and_unmigrated_package`；历史来源提交 1 个（PC-220）；相关公开 PR：#408
- master：chengyinhub-xcx/subpackageMember/components/cy/npc-chat/index.wxml:53 — 迁址后的 NPC 对话有重试按钮（retryAvailable/onRetrySend），带 hover-class 与 aria
- master：chengyinhub-xcx/pages/shezhi/shezhi.wxml:65,67 — 协议与隐私半屏接收 theme=settingsTheme
- master：chengyinhub-xcx/pages/shezhi/shezhi.js:89,236 — settingsTheme 在商家与玩家间切换，并传入 settings-* 场景
- 测试：chengyinhub-xcx/tests/unit/npc-chat-component.test.js
- 测试：chengyinhub-xcx/tests/unit/settings-action-locator.test.js
- 测试：chengyinhub-xcx/tests/unit/wxss-undefined-token-contract.test.js
- 备注：恢复按钮的按压态与设置半屏主题在当前位置都已存在。帖子处置的配色 token 样式没有现成组件（F0521 未迁移），应随 F0521 包一起交付（见 post-governance-F0521），不恢复旧路径，也不恢复旧 canRecover 接口。

#### `PRIVATE-GROUP-photo-fallback-truth` — **MISSING**

- 历史处置：`existing_contract_frontend_adaptation`；历史来源提交 1 个（PC-344）；相关公开 PR：#408
- master：chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AdvancedGameRuntimeServiceImpl.java:2807-2810 — 次数用完且兜底放行时，同时置 passed=true 与 flagged=true
- master：chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AdvancedGameRuntimeServiceImpl.java:3790-3791 — 投影只给 passed/flagged/degraded，没有 photoVerified 或判定结论
- master：chengyinhub-xcx/pages/play/components/playkit-photocheck/index.js:62-71 — 先判 passed 再判 flagged，所以兜底放行显示“这张过了”，像是照片本身通过（本 lane 已核读源码确认）
- 测试：chengyinhub-xcx/tests/unit/playkit-photocheck-frame-contract.test.js — 现有拍照核验合同，没有兜底真实性断言
- **缺失**：在拍照核验组件内，passed 与 flagged 同时为真时，显示兜底放行文案（“机会用完了，这张先算过”）而不是“这张过了”。degraded 保持现有“没有结论”文案。不需要父组件 prop，也不需要 photoVerified 字段。
- **建议 owner**：#408 play 父 owner（只改子组件，不动父层 play/index）
- 备注：旧修复加了一个父组件从未接线的子 prop，不能原样移植；按现有标志分支即可。

#### `PRIVATE-GROUP-coop-review` — **MISSING**

- 历史处置：`queued_owner_followup`；历史来源提交 1 个（PC-285）
- master：chengyinhub-xcx/pages/coop/invite-detail/index.js:246-268 — 已有：加载时回读本主题的 /api/coop/review/summary 并显示“已评价 ★N”
- master：chengyinhub-xcx/pages/coop/invite-detail/index.wxml:132-138 — 已有：回读成功时，评价入口换成已评价状态
- master：chengyinhub-xcx/pages/coop/invite-detail/index.js:571-573 — 缺口：提交时断网会提示“评价未提交”（实际可能已提交），也不重新回读 summary；没有评价阶段标签
- **缺失**：评价提交断网或响应不明时，不要断言“未提交”；先重跑 loadMyReview 回读已有评分，再允许重试，并保留现有身份/生命周期保护。评价阶段标签可选。
- **建议 owner**：F lane 后续（排在 F 当前包之后，由 root 指派）
- 备注：这是排队中的后续项，F 当前四项不包含它，不要报成“在修”。回读函数已经存在，改动很小。

#### `PRIVATE-GROUP-backend-story-alt-text` — **MISSING**

- 历史处置：`backend_merged_frontend_owner_dependency`；历史来源提交 1 个（PC-010）；相关公开 PR：#446, #450
- master：chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/ChapterBlockDTO.java:39-44 — 图片/音频块可选 altText（≤2000 字，#450）
- master：chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ChapterFlowCompiler.java:617 — 存储块保留 altText
- master：chengyinhub-system/src/main/java/com/chengyinhub/business/util/JourneyReviewTexts.java:456,498 — DTO 与存储块的 altText 都送审
- master：chengyinhub-xcx/pages/publish/utils/publish/pro-editor-story.js:301,360 — 前端缺口：读取与提交时媒体块被重建为 {key,type,url}，altText 丢失，编辑器从不保留
- master：chengyinhub-xcx/pages/play/index.js:3538-3539 — 玩家投影丢弃 altText
- master：chengyinhub-xcx/pages/play/index.wxml:280 — 章节图片无 aria-label/alt，音频无文字稿
- 测试：chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ChapterFlowWriteServiceTest.java — mediaAltTextJsonRoundTripSurvivesWriteAndPlayerProgressProjection、mediaAltTextMissingNullAndEmptyKeepExistingMediaCompatible
- 测试：chengyinhub-admin/src/test/java/com/chengyinhub/business/util/JourneyReviewTextsTest.java — mediaAltTextIsReviewedFromBothDtoAndStoredBlocksWithoutTreatingUrlsAsText
- **缺失**：故事编辑器要能读取、编辑、写回图片/音频块的 altText（pro-editor-story 读取与提交 + fabu 输入框）；play 页要把 altText 带进条目并做无障碍呈现：章节图片加 aria-label，音频提供可见或读屏可读的文字稿。
- **建议 owner**：G 发布 owner（编辑器）+ #408 play 父 owner（玩家呈现）
- 备注：后端合同已经通过 #450 在 master 上，Draft #446 已被它取代。前端还不保留也不消费 altText，所以功能端到端未闭环。实现时与 fabu 现有的媒体操作保护协调，不要替换它。

## 408 个历史私有提交的映射

| 映射结果 | 提交数 |
|---|---|
| 归入某个语义组（GROUP） | 122 |
| 归入旧 PR 容器子项（PR_SUBITEM，PR 号 + 文件） | 199 |
| 未映射（UNMAPPED） | 87 |
| **合计** | **408** |

PR_SUBITEM 按容器（一个提交可能出现在多个容器中）：#335 162、#17 122、#3 101、#338 59。这些容器在 Doc 1 §6.1 中都是“不整体合并、逐项移植”，子项去向以 Lane G 的 `disposition.md` 为准。

PR_SUBITEM 中按台账历史分类：current_behavior 154、superseded_generated_evidence 11、mixed_source_contracts_mapped 9、intentional_retirement 7、design_scope_unconfirmed 3、intentional_deferred 3、mixed_current_and_intentional_deferred 2、incompatible_current_contract 2、mixed_dependencies_and_active_owner 2、mixed_current_unmigrated_and_owner 2、mixed_current_replacements_and_protected_existing_gaps 1、unwired_historical_prototype 1、mixed_current_replacements_explicit_retirements_and_protected_play_gap 1、mixed_current_replacements_retired_lab_and_unmigrated_prototypes 1。

### 未映射提交（87）

这些提交既没有组归属，也没有出现在任何旧 PR 容器文件的来源引用中。台账为它们给出的历史分类如下；其中 `current_behavior` 表示台账认为 master 已有对应行为，`intentional_*` 表示已有退役/暂缓记录。**本 lane 没有逐条重新核对这 87 个提交的行为**，它们需要由 root 再做一次去向确认（建议：current_behavior 抽样复核后记为等价；deferred/retirement 核对既有决定证据；mixed 拆到对应组）。

| 台账分类 | 数量 |
|---|---|
| current_behavior | 61 |
| mixed_current_and_intentional_deferred | 12 |
| intentional_deferred | 8 |
| intentional_retirement | 6 |

| 台账 ID | 台账分类 | 涉及公开路径（节选） |
|---|---|---|
| PC-000 | current_behavior | xcx/utils/merchant-workbench.js |
| PC-011 | intentional_deferred | xcx/pages/expense-split/index.js, xcx/pages/expense-split/index.json, xcx/pages/expense-split/index.wxml |
| PC-018 | mixed_current_and_intentional_deferred | xcx/utils/features/activity-wish-client.js, xcx/utils/features/booked-calendar-export.js, xcx/utils/features/capsule-client.js …(+18) |
| PC-032 | intentional_deferred | xcx/components/cy/tool-reference-picker/index.js, xcx/components/cy/tool-reference-picker/index.json, xcx/components/cy/tool-reference-picker/index.wxml …(+17) |
| PC-033 | current_behavior | xcx/components/cy/scene-play-activity-detail/index.js, xcx/utils/activity-status.js |
| PC-036 | current_behavior | xcx/pages/merchant/coop-center/index.js, xcx/pages/merchant/coop-center/index.wxml, xcx/pages/merchant/coop-center/index.wxss …(+1) |
| PC-037 | current_behavior | xcx/pages/merchant/decor/perks/index.js, xcx/pages/merchant/decor/perks/index.wxml, xcx/pages/merchant/decor/perks/index.wxss |
| PC-040 | current_behavior | xcx/pages/club/topic-story/index.js, xcx/pages/club/topic-story/index.wxml, xcx/pages/club/topic-story/index.wxss |
| PC-046 | current_behavior | xcx/components/tabBar/index.wxss |
| PC-047 | mixed_current_and_intentional_deferred | xcx/subpackageTools/activity-wish/index.js, xcx/subpackageTools/activity-wish/index.json, xcx/subpackageTools/activity-wish/index.wxml …(+24) |
| PC-053 | intentional_deferred | xcx/components/cy/tool-reference-picker/index.js, xcx/components/cy/tool-reference-picker/index.json, xcx/components/cy/tool-reference-picker/index.wxml …(+17) |
| PC-057 | current_behavior | xcx/components/tabBar/index.wxml, xcx/components/tabBar/index.wxss, xcx/pages/template/index.js …(+2) |
| PC-061 | current_behavior | xcx/scripts/_analyze_shots.py, xcx/scripts/_audit_metrics.js, xcx/scripts/_audit_palette.js …(+47) |
| PC-071 | current_behavior | xcx/pages/merchant/utils/merchant-business-hours.js |
| PC-076 | current_behavior | xcx/pages/merchant/customer/index.js |
| PC-077 | current_behavior | xcx/pages/play/components/roadmap/index.wxss |
| PC-086 | intentional_deferred | xcx/pages/expense-split/index.js, xcx/pages/expense-split/index.json, xcx/pages/expense-split/index.wxml |
| PC-088 | current_behavior | xcx/components/cy/scene-play-activity-detail/index.wxml, xcx/components/cy/scene-roam-session/index.wxml |
| PC-089 | intentional_retirement | xcx/utils/danger-actions.js |
| PC-091 | current_behavior | xcx/subpackageA/components/scene-asset-income-detail/index.js, xcx/subpackageA/components/scene-asset-income-detail/index.wxml, xcx/subpackageA/components/scene-asset-income-detail/index.wxss …(+1) |
| PC-094 | current_behavior | xcx/pages/merchant/team/index.js |
| PC-096 | current_behavior | xcx/utils/danger-actions.js |
| PC-097 | current_behavior | xcx/components/cy/scene-member-order-detail/index.js, xcx/components/cy/scene-member-order-history/index.js, xcx/subpackageMember/order/order.js …(+1) |
| PC-098 | current_behavior |  |
| PC-099 | intentional_retirement | xcx/pages/merchant/decor/story/index.wxml |
| PC-101 | intentional_retirement | xcx/components/cy/merchant-card/index.wxss |
| PC-106 | current_behavior | xcx/components/cy/post-card/index.wxss, xcx/pages/square/list/index.wxml, xcx/pages/square/list/index.wxss |
| PC-107 | current_behavior | xcx/pages/publish/fabu/index.json, xcx/pages/publish/template-intro/index.json, xcx/pages/publish/templateadd/templateadd.json …(+2) |
| PC-110 | current_behavior | xcx/utils/deregister-flow.js |
| PC-127 | intentional_deferred | xcx/utils/features/expense-split.js |
| PC-128 | current_behavior | xcx/subpackageMember/signup/index.json, xcx/subpackageMember/signup/index.wxml, xcx/subpackageMember/signup/index.wxss |
| PC-133 | current_behavior | xcx/pages/topic/merchantinfo/merchantinfo.wxml, xcx/pages/topic/merchantinfo/merchantinfo.wxss |
| PC-137 | current_behavior | xcx/pages/topic/components/cy/session-picker/index.js, xcx/pages/topic/components/cy/session-picker/index.wxml |
| PC-148 | intentional_retirement | xcx/pages/merchant/relation/index.js, xcx/pages/merchant/relation/index.wxml |
| PC-155 | intentional_deferred | xcx/utils/features/expense-split.js |
| PC-162 | current_behavior | xcx/subpackageTools/lib/runtime.js |
| PC-170 | mixed_current_and_intentional_deferred | xcx/subpackageTools/activity-wish/index.js, xcx/subpackageTools/activity-wish/index.wxml, xcx/subpackageTools/capsule/index.js …(+6) |
| PC-174 | intentional_retirement | xcx/pages/merchant/decor/story/index.wxml |
| PC-176 | current_behavior | xcx/pages/talent/list/index.js, xcx/pages/talent/list/index.wxml, xcx/pages/talent/list/index.wxss |
| PC-184 | current_behavior | xcx/pages/topic/index/index.wxss |
| PC-187 | current_behavior | xcx/pages/club/event-ops/index.wxml |
| PC-188 | current_behavior | xcx/pages/searchmap/index.js, xcx/pages/searchmap/index.wxml, xcx/pages/searchmap/index.wxss |
| PC-192 | mixed_current_and_intentional_deferred | xcx/components/cy/tool-reference-picker/index.wxml, xcx/components/cy/tool-reference-picker/index.wxss, xcx/pages/expense-split/index.wxml …(+7) |
| PC-193 | current_behavior | xcx/pages/shezhi/about/index.wxss |
| PC-201 | intentional_deferred | xcx/pages/expense-split/index.js, xcx/pages/expense-split/index.json, xcx/pages/expense-split/index.wxml |
| PC-202 | current_behavior | xcx/pages/club/components/cy/club-topic-end-confirm/index.js |
| PC-204 | current_behavior | xcx/scripts/fixtures.json, xcx/scripts/shot-matrix.js |
| PC-207 | current_behavior | xcx/pages/searchmap/index.wxml, xcx/pages/searchmap/index.wxss |
| PC-213 | intentional_deferred | xcx/utils/features/expense-split.js |
| PC-222 | mixed_current_and_intentional_deferred | xcx/subpackageTools/anniversary/index.js, xcx/subpackageTools/anniversary/index.json, xcx/subpackageTools/anniversary/index.wxml …(+43) |
| PC-230 | current_behavior | xcx/pages/template/index.js |
| PC-232 | current_behavior | xcx/components/cy/scene-member-order-history/index.js, xcx/components/cy/scene-member-order-history/index.wxml |
| PC-241 | current_behavior |  |
| PC-246 | current_behavior | xcx/utils/deregister-flow.js |
| PC-252 | current_behavior | xcx/components/cy/scene-member-order-detail/index.wxss, xcx/components/cy/scene-member-order-history/index.wxml |
| PC-259 | mixed_current_and_intentional_deferred | xcx/subpackageTools/activity-wish/index.js, xcx/subpackageTools/activity-wish/index.wxml, xcx/subpackageTools/capsule/index.js …(+6) |
| PC-261 | current_behavior | xcx/scripts/selectors-overrides.json, xcx/scripts/selectors.json |
| PC-262 | current_behavior | xcx/components/cy/scene-roam-discover/index.js, xcx/pages/merchant/relation/index.js, xcx/pages/merchant/relation/index.wxml …(+1) |
| PC-265 | current_behavior | xcx/components/cy/publish-sheet/index.js, xcx/components/cy/publish-sheet/index.json, xcx/components/cy/publish-sheet/index.wxml …(+1) |
| PC-269 | current_behavior | xcx/pages/topic/components/project-drawer/index.js, xcx/pages/topic/components/project-drawer/index.wxml, xcx/pages/topic/components/project-drawer/index.wxss …(+1) |
| PC-271 | current_behavior | xcx/components/cy/profile/index.wxss |
| PC-276 | current_behavior | xcx/pages/club/settlement/index.js, xcx/pages/club/settlement/index.wxml |
| PC-284 | current_behavior | xcx/components/cy/scene-member-invite-history/index.wxml |
| PC-286 | current_behavior | xcx/pages/topic/merchantinfo/merchantinfo.js |
| PC-288 | current_behavior | xcx/pages/coop/invite-detail/index.json |
| PC-293 | mixed_current_and_intentional_deferred | xcx/components/cy/tool-reference-picker/index.wxml, xcx/components/cy/tool-reference-picker/index.wxss, xcx/pages/expense-split/index.wxml …(+7) |
| PC-297 | mixed_current_and_intentional_deferred | xcx/subpackageTools/activity-wish/index.js, xcx/subpackageTools/activity-wish/index.json, xcx/subpackageTools/activity-wish/index.wxml …(+39) |
| PC-298 | current_behavior | xcx/utils/order-status.js |
| PC-299 | current_behavior | xcx/components/cy/date-field/index.wxml, xcx/pages/merchant/customer/index.wxml, xcx/pages/merchant/customer/index.wxss |
| PC-310 | current_behavior | xcx/subpackageA/components/scene-asset-income-detail/index.js, xcx/subpackageA/components/scene-asset-income-detail/index.wxss, xcx/subpackageA/pages/assetcenter/income-detail/income-detail.wxml |
| PC-313 | intentional_retirement | xcx/utils/danger-actions.js |
| PC-324 | current_behavior | xcx/components/tabBar/index.wxml, xcx/components/tabBar/index.wxss |
| PC-340 | current_behavior | xcx/pages/topic/merchantinfo/merchantinfo.js |
| PC-345 | current_behavior | xcx/pages/club/event-ops/index.js |
| PC-346 | current_behavior | xcx/subpackageA/pages/myproject/index.wxss |
| PC-354 | mixed_current_and_intentional_deferred | xcx/subpackageTools/activity-wish/index.js, xcx/subpackageTools/activity-wish/index.json, xcx/subpackageTools/activity-wish/index.wxml …(+24) |
| PC-359 | current_behavior | xcx/subpackageMember/signup/index.wxml |
| PC-362 | mixed_current_and_intentional_deferred | xcx/subpackageTools/activity-wish/index.js, xcx/subpackageTools/activity-wish/index.json, xcx/subpackageTools/activity-wish/index.wxml …(+39) |
| PC-366 | current_behavior | xcx/components/tabBar/index.wxss |
| PC-370 | mixed_current_and_intentional_deferred | xcx/utils/features/activity-wish-client.js, xcx/utils/features/booked-calendar-export.js, xcx/utils/features/capsule-client.js …(+18) |
| PC-372 | current_behavior | xcx/pages/coop/nearby/index.wxml, xcx/pages/topic/components/project-host/index.wxml, xcx/pages/topic/merchantinfo/merchantinfo.js |
| PC-378 | current_behavior | xcx/pages/merchant/customer/index.js |
| PC-379 | current_behavior | xcx/pages/merchant/team/index.js |
| PC-389 | current_behavior | xcx/subpackageMember/signup/index.wxml, xcx/subpackageMember/signup/index.wxss |
| PC-391 | current_behavior | xcx/pages/club/governance/index.js |
| PC-399 | current_behavior | xcx/pages/merchant/customer/index.js, xcx/pages/merchant/customer/index.wxml, xcx/pages/merchant/customer/index.wxss |
| PC-407 | mixed_current_and_intentional_deferred | xcx/subpackageTools/anniversary/index.js, xcx/subpackageTools/anniversary/index.json, xcx/subpackageTools/anniversary/index.wxml …(+43) |

## 51 条历史私有脏记录的映射

| 映射结果 | 条数 |
|---|---|
| EQUIVALENT_PUBLIC_SNAPSHOT | 25 |
| GROUP | 10 |
| UNMAPPED | 9 |
| PR_SUBITEM | 7 |

- `EQUIVALENT_PUBLIC_SNAPSHOT`（25）：台账记载为与公开快照同 blob，或补丁已应用。
- `GROUP`（10）：按路径或台账说明归入语义组；PD-44→backend-withdraw-summary，PD-45→G-player-host-publish，PD-46→backend-X034-reception / backend-X036-identity，PD-48→play-protected-recovery / backend-share-token-redaction。这 4 条是按台账说明人工归入的。
- `PR_SUBITEM`（7）：路径属于旧 PR 容器 #3 / #17 的子项（动作台账生成物、UI 基线、同意客户端、IM 列表竞态）。
- `UNMAPPED`（9）：不属于任何组或 PR 容器。台账记载这些记录的有效新增行已在当前代码中保留；本 lane 确认这些路径在 master 上都存在，相关单测（7 个文件，98/98）也在 master 上通过。建议 root 记为“已保留”后结案。

| 台账 ID | 路径 | 台账分类 |
|---|---|---|
| PD-06 | chengyinhub-xcx/pages/merchant/reviews/index.js | semantic_review |
| PD-28 | chengyinhub-xcx/tests/unit/usage-consent-app.test.js | semantic_review |
| PD-33 | chengyinhub-xcx/tests/unit/club-gather-arrival.test.js | semantic_review |
| PD-34 | chengyinhub-xcx/tests/unit/gather-consent-action-contract.test.js | semantic_review |
| PD-35 | chengyinhub-xcx/tests/unit/merchant-review-read-session.test.js | semantic_review |
| PD-37 | chengyinhub-xcx/pages/publish/temp/index.js | semantic_review |
| PD-38 | chengyinhub-xcx/pages/templatedetail/templatedetail.js | semantic_review |
| PD-39 | chengyinhub-xcx/pages/templatedetail/templatedetail.wxml | semantic_review |
| PD-40 | chengyinhub-xcx/tests/unit/r9-04-own-template-resume-contract.test.js | semantic_review |

## 抽查记录（主控复读）

- `ApiClubLeadController.java:259-267`：先计数再更新，更新行数未校验，通知只写日志，证实 club-meeting-atomic 的缺口。
- `ApiRoamShareController.java:100-105`：`revoke()` 不收 token，调用 `deleteByMember`，证实“撤销全部”。
- `CmsActivityServiceImpl.java:895-902`：协作者记录 `setMemberId(memberId)`，证实写成发布者 id。
- `playkit-photocheck/index.js:62-71`：passed 先于 flagged 判定，证实兜底放行显示“这张过了”。
- `build-action-ledger.js:66,85`：API_WRITE_RE 含 `/publish`，isWriteApi 没有读例外，证实 /api/publish/home 被判为写。
- `coop/invite/index.wxml:40,118`：发送时就写“锁定/已锁定”，证实 invite-proposed-terms 的缺口。
- `im/chat/index.wxml:84-88`：null 态统一显示“已下架”，证实 route-card-unavailable 的缺口。
- `discover-search.js` + `search2/result/index.js:38`：node 复现 `maxPrice:null` 时滤掉有价行。

## 固定决策遵守

所有建议都遵守 D-11～D-18：没有提出改变会费预支付闸（D-12）、排行榜结构（D-13）、陌生人私信闸（D-14，只在 App）、App 平台（D-16）或主题类型（D-18）的建议；不恢复旧整文件、旧 `app.json`、旧奖励/次数政策或已暂缓/退役工具。涉及身份、资金、奖励、品牌的组一律列为 NEEDS_DECISION 并附选项。

## 未完成与边界

- 本文件不实现任何修复；MISSING 项需要各 owner 另开 PR。
- 87 个未映射历史提交只按台账分类汇总，没有逐条复核行为。
- Java 测试没有执行；后端结论基于源码阅读。
- 不构成真机、部署或业务验收。
