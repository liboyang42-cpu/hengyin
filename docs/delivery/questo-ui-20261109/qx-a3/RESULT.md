# qx-a3 结果：订单、个人资料、我的项目（2026-11-09）

分支 `claude/qx-a3-20261109`，基于 `origin/master` 2bb81143。只改小程序，没有后端、迁移或权限改动。
截图在 `shots/`，390×844 JPEG q70，来自走查 rig（真实小程序包 + 真实后端 jar，不是手机）。

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机仍未验证 |
|---|---|---|---|---|---|
| V3-02 | fixed | `.ocard` 补 `margin-bottom: var(--cy-scene-card-gap, var(--cy-space-3))`。信息卡后面直接接 cy-card（报名人信息→权益明细、凭证与有效期→报名与客服等）时不再连成一块。后接段标题时与它的 margin-top 折叠，原间距不变。 | `subpackageMember/components/cy/scene-member-order-detail/index.wxss` | `V3-02-before/after`（真实已付订单 74，滚到报名人信息），`qx-a3-20261109.test.js` | 真机滚动到「凭证与有效期 → 报名与客服」那一处（rig 订单是场次票，没有「可玩至」行；同一条规则覆盖） |
| V3-03 | fixed | 订单详情不再出「开始游戏」（`resolvePrimaryCta` 不返回 `play`）。已完成订单的「查看记录」保留。开始游戏只从票夹进。 | `scene-member-order-detail/index.js`, `index.wxml`; 测试 `dw29-v20-order-detail`, `k2-no-game-ticket` 更新 | `V3-03-before/after`（同一订单，底部 sticky 键消失），单测 | — |
| V3-22 | fixed | 订单卡重排：去掉订单号行（订单号在详情首行，可复制）；封面 176rpx 方图与文字列顶边对齐；第一行「类别 tag（城市定向/自由探索/活动）＋ 状态词」，然后主题名、时间、地点；没有地点时不再画「📍 —」；卡距 space-3，卡内统一 space-4，底行实付与动作和封面同一左边。不写张数、不放核销码。 | `subpackageMember/components/cy/scene-member-order-history/index.wxml`, `index.wxss` | `V3-22-before/after`，单测 | 封面图（rig 没有 OSS，封面 404 显示灰底占位）。before 时订单是「未开始」带「申请退款」，after 拍时已到开场时间变成「进行中」，所以不再有退款键：这是服务端状态，不是改动造成 |
| V3-33 | fixed | 「更换背景」从头像右侧的悬浮药丸挪到名字下方，随头像一列居中；命中区 88rpx，可见药丸 56rpx。 | `pages/gerenziliao/gerenziliao.wxml`, `.wxss` | `V3-33-before/after`，单测 | 真机背景图上的对比度 |
| V3-37 | fixed | 根因：设置页「个人资料」图标指向 `/pages/topic/images/icon_qa_person.svg`，那是另一个分包。真机上分包不能引用别的分包的资源，所以图标不显示。复制到本分包 `pages/shezhi/images/icon_qa_person.svg`。单测检查设置页所有图标都在主包或本分包内。 | `pages/shezhi/shezhi.wxml`, `pages/shezhi/images/icon_qa_person.svg` | 单测（会对原代码判红）。`V3-37-before/after` 两张看起来一样：rig 的浏览器能读到任何文件，复现不了真机的分包隔离 | 真机上图标出现（需要真机或开发者工具） |
| V1-P53 | fixed | 设置里补「投诉建议」行（在「我的申诉」上方），打开已有的 `settings-feedback` 场景（提交 `/api/coop/complaint/report`）。P-53 从「我的」撤掉这一行时写着「设置里已有」，其实设置里没有。图标是同族 lucide `message-square-text`。 | `pages/shezhi/shezhi.wxml`, `shezhi.js`, `pages/shezhi/images/icon_lc_message-square-text.svg`, 动作台账重建 | `V1-P53-before/after`、`V1-P53-open-after`（点开后的场景），单测 | 提交本身没在 rig 里点（会写真实投诉记录）；场景内容是原有的，未改 |
| V3-38 | fixed | 我的项目主题卡：状态词从档期行挪到标题行右侧（标题省略号，状态不缩不折行）。 | `subpackageA/pages/myproject/index.wxml`, `.wxss` | `V3-38-before/after`（真实主题 990015），单测 | 「审核中」状态的主题卡没有在 rig 里造（同一行布局，状态词不同） |
| V3-39 | fixed | 模板卡重做：标题行 = 标题 + 短状态词（通过 / 审核中 / 未通过 / 草稿 / 待确认，新增 `marketView()._statusShort`，原长文案 `_statusText` 留给其它入口）；「节点玩法 · 广场状态」一行次要字；「N 次引用 · M 分钟」一行。不再出现换行的药丸和粘连的「0 次引用15 分钟」。 | `myproject/index.wxml`, `.wxss`, `utils/template-market.js`, `scripts/fixtures.json`, `scripts/shot-matrix.js`, `tests/unit/screenshot-fixture-completeness.test.js` | `V3-39-before/after`（商家 9002 的真实模板，审核中），单测 | 「通过」状态的卡（rig 里没有审核通过的个人模板） |
| V3-28 | fixed | 探索目标空态换成标准 `cy-empty kind="empty" size="lg"`（两层插画）。 | `subpackageTools/exploration-goals/index.wxml`, `.json`, `.wxss` | `V3-28-before/after`，单测 | — |

## 验证

- `node --test chengyinhub-xcx/tests/unit/qx-a3-20261109.test.js`：8/8 通过。
- 受影响的现有测试全部通过：订单详情/列表相关 40 个文件，myproject 相关，`screenshot-fixture-completeness`、`visual-report-content-p1-contract`、`exploration-goals`，以及设置页、个人资料页相关测试。
- `node chengyinhub-xcx/scripts/uiaudit/build-action-ledger.js --write` 重建了台账（新增 `goFeedback`，其余是行号漂移）。
- `node chengyinhub-xcx/tests/lint/ui-integrity.js`：`UI-GATE-0:PASS`，没有新增基线条目。
- 主包只改了 `utils/template-market.js`（多一行），其余都在分包里。

## Rig 说明（没有提交到产品代码）

- rig 分支 `claude/cloud-walk-20261101-rig` + `claude/cloud-walk-20261101-club` 的 `harness-patches.diff`；`up.sh` 的候选 SHA 改成 master 2bb81143；before 截图用 master 包，after 截图用本分支包（`CAND=<本仓> build.sh`，`server.mjs` 同样指向本仓）。
- rig `app.py` 给后端加了离线占位的 OSS 配置（`oss-rig.invalid` 等，不是密钥）。没有它，`/api/registration/list` 序列化时报「对象存储配置缺失」，订单列表是空白。
- 订单数据：`chains.py player` 在当前 master 上卡在 sale_close（俱乐部开场次规则变了）。改用 `walk-fix-20261102/integ/proof/m6-walk/fe_walk.py` 的 setup→buy 阶段走真实接口：俱乐部建主题、商家承接、定价、开售审核、开场次；玩家 9004/9009 通过 `/api/user/address/action` 加参与人后真实下单，支付走 rig 的 `pay.py`（已验证支付事实 + 真实 `/api/registration/callback`）。没有直接写支付状态。

## Cross-lane

无。

## needs owner

无。两处我按默认做了：
- V3-03 只去掉「开始游戏」，已完成订单的「查看记录」保留（它不是开始游戏）。如果也要去掉，删 `resolvePrimaryCta` 的 `record` 分支即可。
- V3-22 卡面去掉了订单号（详情首行有订单号和复制）。

STATUS: COMPLETE
