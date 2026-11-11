# f-merchant 修复线结果（2026-11-11）

分支 `claude/f-merchant-20261111`，基于 `origin/launch/questo-20261110`（afa90ffd），对应 AUDIT-GAPS 第 8 组「商家页收尾」。
截图都在 `shots/`，由走查台（cloud-walk rig）对真实后端（launch 底座的 jar、MySQL、Redis）拍摄：「之前」用 launch 底座的小程序包，「之后」用本分支的包，390px，JPEG q70。每张截图都已逐张看过。

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| M-18 经营团队 | fixed | 照「门店信息」的版式重排：左上大标题；白卡行左边是名称、右边是值；「门店」「我的岗位」两行只读，不带箭头。团队成员、待接受邀请各一张白卡。成员行带头像和岗位，点开原来的岗位面板，面板底部加一行红色「移除」，走原来的三段式确认。「邀请员工」改成底部主按钮。撤掉了「详情」开关和那句说明。岗位列表没拉到时，面板里仍可以移除。cy-cell 新增两个 CSS 变量，用来控制图标尺寸和圆角，默认值不变 | `pages/merchant/team/index.{wxml,wxss,js,json}`、`components/cy/cell/index.wxss` | 截图 `M-18-before`、`M-18-after`、`M-18-after-sheet`：真实数据，含 1 名核销员和 1 条财务邀请，均由 rig 走真接口创建。商家岗位相关单测、导航和标题契约测试通过 | 真机上的面板手势和分享按钮 |
| M-35 / G-10 商家浅色清查 + 门禁 | fixed | 扫了 `pages/merchant/**` 和 `pages/coop/**` 能跳到的每个页面。商家能进、但根节点写死玩家深色的有两处：工作台的搜索（search2 和它的结果页），以及「发现」里点开的帖文详情（square/detail）。这三页改为共享页，根节点按 `roleGuard.isMerchantView()` 在 theme-merchant 和 theme-dark 之间二选一，并引入商家浅色作用域，导航栏颜色随之切换；玩家看到的不变。新增门禁测试：商家页能跳到的玩家域页面，如果不在写明原因的清单里就判红，清单里的死条目也判红，附负控。其余 8 个保持深色的目标都写了原因（主题域、漫游域、俱乐部域、公开主页跟随被看者） | `pages/search2/index.*`、`pages/search2/result/index.*`、`pages/square/detail/index.*`、`scripts/ui-theme-unit-lint.js`（共享域规则加两条）、`tests/unit/f-merchant-light-reach.test.js` | 截图 `M-35-search2-before/after`、`M-35-search2-result-before/after`；`ui-theme-unit-lint --theme` 通过；新测试 4 条通过，包括负控 | 帖文详情没拍到：新库里没有帖文，rig 进不去有内容的详情；真机上导航栏换色 |
| G-5 身份同步 | fixed | 店铺参谋：原来已订阅身份广播。这次补了 onShow 时比对 epoch，变了就把本页缓存的店名和 Logo 作废，以 `/api/merchant/info` 回读整体覆盖（没变时只补缺）。消息页：新增订阅，身份广播过之后回到列表，丢掉已确认的会话快照整表重拉，不走静默保留；页面卸载时退订 | `pages/merchant/marketing/ai-insight/index.js`、`subpackageB/pages/im/list/index.js`、`tests/unit/f-merchant-identity-sync.test.js` | 新测试 2 条：真页面加真的 identity-sync，覆盖广播、回读覆盖、补缺不覆盖、整表重拉、退订 | 两页都没有静态的视觉变化，所以没有截图 |
| G-8 / M-12 俱乐部和主题标签接入标签库 | fixed | 标签库新增俱乐部「活动倾向」组。词和顺序沿用原两页的 10 个词，后端按原词存，所以不改数据。创建俱乐部第 2 步和编辑页的「活动倾向」改为从库里读同一份选项，并带上库里的图标：俱乐部域是暗底，图标反白，选中时用原色。主题标签由快速配置页的兴趣标签接入（见下一行）。图标按库的约定放在各自分包里：`pages/club/images/tags/` 10 个，`pages/publish/images/tags/` 17 个 | `utils/tag-library.js`、`pages/club/create/index.*`、`pages/club/components/cy/scene-club-edit/index.*`、两处图标目录、`tests/unit/f-merchant-tag-library-wiring.test.js` | 截图 `G-8-club-before/after`：真实俱乐部 7002 的编辑页「活动倾向」面板。测试校验每个库标签的图标文件都存在，两页不再写死选项 | 创建页第 2 步 rig 进不去：fixture 账号不符合创建条件，会被转到申请页 |
| P-43 / SPEC-3.4-doc11 AI 快速配置页 | fixed | 按商家套件清理：灰底白卡；撤掉三团柔光、引导问句、模式小字和「选了 N 个」；漂浮气泡改成白卡里的标签库主题标签，共 18 个，带图标，实底无描边，选中黑底白字；「历史起草记录」「本机保留记录」从灰按钮改成白卡里的 cy-cell 行；强调色从主蓝改为黑白。生成、草稿卡、逐站确认、额度、AIGC 标识和备案号都不变。选中的标签仍然拼进发给 AI 的描述 | `pages/publish/simple/index.{wxml,wxss,js,json}`、`pages/publish/images/tags/`、`tests/unit/secondary-nav-consistency.test.js`（负控锚点随结构更新） | 截图 `P-43-before/after`：选中了「夜游」「解谜」。快速配置相关的契约和单测全部通过 | 真机字体、键盘顶起输入栏 |
| P0-42 / P1-016 核销台「复核」态 | fixed | 核销回执是 409（CONFLICT，HTTP 或业务码）时，不再落成「结果待确认」，改进入「待复核」态：标题是「待复核」；后端给了中文原话就原样显示，空串或英文码则显示「核销状态有变化，请先在核销记录里复核」；主按钮改为「查看核销记录」，跳到核销记录页；原核销待办保留，再扫同一目标只回读、不重发。据点核销用同一口径，弹「待复核」 | `utils/merchant-verification.js`、`pages/merchant/utils/citynode-redeem-outcome.js`、`pages/merchant/index/index.{js,wxml}`、`pages/merchant/citynode/index.js`、`tests/unit/f-merchant-verify-review.test.js` | 截图 `P0-42-before/after`：在 rig 里调用页面自己的 `showVerificationResult`，传入 409 回执（后端原话「单票核销状态不明确，请人工核对」）。新测试 5 条，原核销和据点回读测试全部通过 | rig 无法从真接口造出 409，截图里的回执是注入的；真机扫码 |
| CRM 远程搜索 | fixed | 后台「客户 CRM」的商家条件，从手填 `mms_merchant.member_id` 改为按店名远程搜索的下拉（复用现有 `listMerchant`），选中后直接查询；迟到的搜索结果不覆盖新结果；搜索失败时如实报错，不当成没有这个商家；「增量对账」的提示不再出现库字段名 | `chengyinhub-ui/src/views/business/crm/index.vue`、`chengyinhub-ui/tests/unit/f-merchant-crm-remote-search.test.js` | `npm run build:prod` 通过（EXIT 0）；新单测 2 条通过 | 后台页面没有截图（rig 只跑小程序） |
| W2-16 商家首次介绍 | already fixed | 已核对 launch 头：AI 店铺角色的首次介绍是上一版（3D 物件插图、分步动效，cf513532），没有积木 logo。没有改动 | — | 截图 `W2-16-after`：rig 里首次进入 AI 店铺角色时的介绍面板，第 1 步是 3D 便利店 | 真机动效 |
| 商家不见通行证 | already fixed | 「我的」里的「通行证与礼品」入口已按 `!isMerchantView` 隐藏。rig 中商家 9002 的「我的」页文本里没有「通行证」 | — | rig 文本转储 | — |

## 闸门

- 单测：本线新增 6 个测试文件，全部通过。凡是引用了本线改动文件的既有单测（约 227 个文件）都跑了，结果见下方「全量相关单测」；修过 4 处测试沙箱的依赖桩，只补了 `merchant-theme`、`tag-library` 两个模块的桩，没有改断言。
- 动作台账：用 `build-action-ledger.js --write` 重建，没有新增基线条目。台账测试里的三个计数随经营团队重排同步更新：控件 6007→6005，tap 3021→3020，事件合同 2986→2985。
- `node chengyinhub-xcx/tests/lint/ui-integrity.js`：输出 `UI-GATE-0:PASS`，没有改基线文件。新增的语句并在原行上写，所以 5 条按行号登记的基线锚点都不位移。
- 主包：`mainpkg-size-report` 显示合计 3223KB，launch 底座是 3221KB，按 0.633 折算约 2040 KiB，低于 2048 KiB。新增的图标都放在分包里。
- 管理后台：`npm run build:prod` 通过。
- 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。没有新增迁移，不涉及生产，没有密钥。

## 待你决定

1. 商家从「发现」点进玩家的公开主页（`pages/userinfo`），页面仍是深色。这是 cy-profile 现有的规则：主页配色跟随被看者身份，不跟随观看者。我按现状保留，并在门禁清单里写明了原因。如果你希望改成「商家看谁都是浅色」，需要改 cy-profile。
2. 快速配置页的标题仍然居中在导航栏里，这是 10-28 p86 的定稿，有标题盘点测试锁着，我没有改成左上大标题。兴趣标签用的是标签库「主题」组的全部 18 个词，原来是 7 个。
3. 俱乐部「活动倾向」10 个词的图标对应是我选的，例如「RPG体验」配手柄、「城市定向」配脚印，可以直接在 `utils/tag-library.js` 里改。

## 跨线

无。

STATUS: COMPLETE
