# x-fix5 验收第三轮问题收口

分支 `launch/questo-20261110`。依据：`ACCEPT-ISSUES-R3-20261111.md`（取自 `claude/questo-ui-brief-20261109`）。统一单测：`chengyinhub-xcx/tests/unit/x-fix5-accept-r3-20261111.test.js`（15 项）。
取证：走查 rig（`claude/cloud-walk-20261101-rig` 的 mpweb 运行时 + `rig/stub-backend.mjs` 最小后端桩 + `rig/gen.js` 预览数据），脚本在 `x-fix5/rig/`，截图在 `x-fix5/shots/`。

## 一、R3-2（N-7 的根因，最优先）

**原因**：x-fix4 把 `--pk-embed-*` 定义在 `.play[class*="look--"]` 上，但 `<cy-playkit id="playKit">`、`<cy-playkit-album>`、`<cy-play-verdict>`、页面级 `<cy-playkit-journey-check>` 渲染在根 `.play` **之外**（index.wxml 根在 1631 行闭合），整屏玩法与相册从不继承外观变量，只能落回皮肤原色（#0b6adc 蓝、#e6e3e4 灰、白相册）。此外抽卡组件 `THEMES` 把底色写死成蓝/黄/红三色轮转，相册查看器背景与按钮写死白底，弹球场地写死 #0a0a0b，主按钮 `.g-btn` 写死白底。

**修法**：
- 四个宿主元素都加 `class="pk-lookhost {{lookClass}}"`，`look-tokens.wxss` 的 `--pk-embed-*` 规则改为 `.play[class*="look--"], .pk-lookhost[class*="look--"]`，并新增同选择器的 `--alb-*`（相册筒壁/文字/按钮取外观底、卡面、字色）。
- 抽卡 `THEMES` 改为 `var(--pk-embed-bg, #0b6adc) / var(--lk-card, …) / var(--lk-pop, …)`：主题内三屏轮转用外观的底/卡面/浮层，无外观回落原三色。
- 相册查看器背景、筒壁渐变、文字、按钮改读 `--alb-*`，无外观时仍是业主点名的白色相册空间。
- 弹球场地/字/球/按钮读 `--pk-embed-*`；抛硬币台面读 `--pk-embed-bg/ink`；`.g-btn`（提交、就标这些等整屏主按钮）读 `--lk-a / --lk-bg`，无外观仍白底。
- 文件：pages/play/index.wxml、look-tokens.wxss（与 publish/fabu 副本一致）、components/playkit-album|random|ballshake|coinflip、style/play-surface.wxss。
- 测试：x-fix5 R3-2 两项；x-fix4 N-7 断言同步新选择器。

**rig 证据**（STARDUST / PAPER / SURVIVAL / CYBER × 玩法，我逐张看过，暗色外观里没有任何浅色/蓝色块）：
- 整屏联系表：`shots/sheet-STARDUST-full.jpg`、`sheet-PAPER-full.jpg`、`sheet-SURVIVAL-full.jpg`、`sheet-CYBER-full.jpg`（每行 qa PICK / qa TYPE / coinFlip / diceRoll / ballShake / compare(时间轴) / random 抽卡 / 相册查看器）。
- 故事流内嵌 + 抽卡换牌 + 卡片详情：`shots/sheet-*-inline.jpg`。
- 单张原图 `shots/m2-<外观>-<玩法>-{inline,full}.jpg`、`m2-<外观>-draw-{full,detail}.jpg`、`m2-<外观>-album-full.jpg`。
- 修复前对照：round 3 报告里 player-124/125/126/132/163/164。

## 二、逐项

| 编号 | 原因 | 修法 | 文件 | 测试 | rig 证据 |
|---|---|---|---|---|---|
| R3-1 队长「开始准备」只见笼统 toast | `rejectedActionText` 只对 START 取回执 `reason`，PREPARE 被拒（GAME_TEAM_REQUIRED「共享玩法需要本场有效队伍」）落到「操作未生效」 | START 与 PREPARE 都原话回显后端原因 | pages/club/topic-detail/director.js | x-fix5 R3-1 | 逻辑层，无页面截图 |
| R3-3 导演台日期是主题日期 | 头部 `dateRange` 只读主题起止 | 选定本场（`_directorActivityId`）后读 `activityList` 里该场 startDate；maybeEnterDirector 选定唯一一场后立即刷新；没有场次日期才回落主题 | pages/club/topic-detail/index.js | x-fix5 R3-3（沙箱执行 headerDateRange） | — |
| R3-4 `/shared/control` 对非共享场次 500 | 服务抛 GAME_ACTION_UNSUPPORTED，控制器统一 `AjaxResult.error` | 控制器对该码回 200 空体；权限等其它拒绝原样 500；客户端 `present(null)` 本来就按「无共享控制」处理 | ApiGameSessionController.java、ApiGameSessionControllerTest | `sharedControlOnNonSharedSessionIsEmptySuccessNotServerError`（见下节结果） | — |
| R3-5 手记全站挂在第一章下 | `.sheet-meta` 只写当前章名，节点列表不分章 | 多章时按 `chapterId` 分组，每章第一站前出该章标题；单章仍是顶部一行 | pages/play/index.js(buildJournal)、index.wxml | x-fix5 R3-5 | 逻辑层 |
| R3-6 通关后工具菜单仍有「开始这一局」、三行同图标 | 行只判 `mode!=2`；三行共用 `pic--route` | `&& !allDone`；本局读数用 info、开始/暂停用 play/pause、切换章节保留 route | pages/play/index.wxml | x-fix5 R3-6 | — |
| R3-7 casebook / encounter / share-code 500 | ① casebook：进页无条件探测，还没有这一局（路线态无 sessionId）时后端回 500「这一局还没开始」；② encounter：同理，仅在节点卡打开时问，已静默处理；③ share-code「个人码存储失败」= `PlayerWxacodeServiceImpl.OssWxCodeStorage` 把小程序码传 OSS 失败，测试环境没有 OSS（R3 环境限制已写明） | ① 路线态没有 sessionId 时不探测（旧入口不传参保持原行为）；② 不改（探测失败本就静默，且线性主题的局由服务端按需建，客户端不能凭空判无局）；③ 环境问题，不改代码，证据是 `OssWxCodeStorage.store` → `FileOssUploadUtils.upload` 抛错才会落这句 | pages/play/index.js、utils/play-topbar.js | x-fix5 R3-7 | — |
| R3-8 用时「未知」 | `durationText` 缺值返回「未知」 | 缺值返回「—」（用时是服务端事实，缺值不补 0），页面 muted 判据同步；解谜分「本局不计分」是服务端 `scored=false` 的如实文案，不动 | subpackageJourney/utils/journey-view.js、pages/report/index.wxml、qx-b3 断言 | x-fix5 R3-8；qx-b3-player-journey | — |
| R3-9 通关卡文案被卡压住、标签被切 | finale 文案区 `white-space:pre` + `overflow:hidden` + 固定宽度，长标题/三格数据超出被裁 | 版面兜底（不依赖 WebGL）：标题/说明/章眉折行，数据行折行居中，文案区限宽且底边让出按钮区 | components/chapter-flip/index.wxss | x-fix5 R3-9；chapter-flip.test.js | WebGL 画布部分 DevTools/rig 仍不渲染，真机未验 |

## 三、仍开行

| 编号 | 结论 | 说明 / 修法 | 文件 | 测试 | 证据 |
|---|---|---|---|---|---|
| P-5 到店三步空 | 不改（未复现） | `.fx-hero__body` 底边已抬到 CTA 之上（btn-h+104rpx+安全区），「到店三步」标题与三步在同一滚动区；DevTools 自动化滚不动内层 scroll-view 是 R3 自己记的环境限制，标题落在渐隐边缘即该现象。预览态无自由探索卡包，rig 无法打开 hero | — | 版面公式未动 | 尝试见 `shots/p5-*.jpg`（hero 未渲染，非证据） |
| P-6 卡包首屏白色毛边、缺标题 | 缺标题是业主 2026-11-02 g21 第三轮裁决（开包前包面与页头不露主题名，index.wxml 注释），不改；毛边需真实自由探索主题才进得了卡包页，rig 无法复现，未盲改 | — | — | — |
| P-9 手记未到站的说明句 | 已修 | 未到过的站不写「这一页尚未书写…」，行内只剩站名 | pages/play/index.js | x-fix5 P-9 | — |
| A-3 / N-19 标题截断、票务底栏压行 | 已修 | 标题改自适应高度多行框折行显示全文（去回车）；票种编辑器展开后若落在视口下半，`pageScrollTo` 滚到上 20% 处，不再被底栏压住 | pages/publish/fabu/index.wxml/.js/.wxss | x-fix5 A-3 | 页面需真机/DevTools 验 |
| L-4 品牌中心商家主页「链接已失效」 | 未复现 | 品牌中心「看公开主页」走 `merchantHomeUrl(memberId)+&preview=1` → userinfo；「链接已失效」是 `pages/merchant/profile` 兼容壳无 id 参数时的态，站内无任何入口跳该壳（全仓搜索），疑为验收环境里的旧链接；未盲改 | — | — | — |
| L-6 / L2-1 首页定位卡 | 不改版式 | 实现是带遮罩（`mo__mask`）、`catchtouchmove` 的标准同意弹窗，x-fix4 已加「点空白 = 暂不定位」；隐私合规弹窗不能做成不拦的浮层。若要彻底不遮 feed 需业主定稿成顶栏内联卡 | — | x-fix4 L2-1 | — |
| ME-4 商家浅色页底栏字透图标 | 已修 | 渐隐收到顶上 12%，图标行整行在实底上（原 38%/30%） | components/tabBar/index.wxss | x-fix5 ME-4；x-fix4 断言同步 | — |
| C-3 老路由 play 仍显示开始游戏 | 不改 | play 页已把 `GAME_SESSION_NOT_PREPARED` 当「无会话」（index.js 2868），控制台 500 是后端「未准备」既定语义；队长开场入口是导演台，其被拒原因现已见 R3-1 | — | — | — |
| N-2 单票种不预选 | 已修 | 仅一种票且可买（窗口已开、未售罄）时自动选中，「下一步」不再灰着 | pages/topic/components/cy/session-picker/index.js | x-fix5 N-2 | — |
| N-5 免费票需补全姓名手机号 | 产品规则，不改 | 后端 `RegistrationOrderCreateGateImpl`（参与人姓名 / 手机号，免费票同样建单必填）；结算页「参与人（建单必填）」入口与缺失态正确。要减摩擦只能改规则，需业主定 | — | — | — |
| N-8 弹球外观缺失 | 已修（随 R3-2） | 弹球场地/球/按钮读外观；传感器本体 DevTools 无 | playkit-ballshake | x-fix5 R3-2 | `sheet-*-full.jpg` 第 5 列 |
| N-10 主题库缺 9912702..4 | 不改（数据） | x-fix4 已核：后端 `officialSelfPlayTopic` 要求 `self_play=1`、`lifecycle IN (3,4)` 且有 PLATFORM CONTENT 角色，种子主题不满足；前端无类型过滤 | — | — | — |
| N-11 通关卡 | 版面已修（见 R3-9） | 同上 | chapter-flip | x-fix5 R3-9 | — |
| N-12 地图黑底只剩搜索条 | 环境 | DevTools 无地图渲染；同一环境漫游地图正常 | — | — | — |
| N-13 就标这些压 Home 条 / 「右侧」 | 压 Home 条已修 | 故事流 `.chfull__kit` 与 `.chfull__end` 底内边距再加 32rpx；rig 实测 STARDUST 与 CYBER 末行按钮底到屏底距离一致（均 ≥ 34px 安全区），原 CYBER 取证应是进场动画中途。「右侧」是作者题面文案，两列在小程序里竖排，改题面不属客户端 | pages/play/index.wxss | x-fix5 N-1/N-13；x-fix4 同步 | `shots/m2-*-compare-*.jpg` |
| N-1 继续探索压 Home 条 | 已修（各外观） | 同上 | pages/play/index.wxss | 同上 | `shots/eSTARDUST-end.jpg`、`eCYBER-end.jpg` |
| N-17 通过入会申请标题过长 | 已修 | 标题只写名字，默认昵称「城瘾用户」的申请时刻进正文「申请于 …」；拒绝弹层保持原样 | pages/club/join-requests/index.js | x-fix5 N-17；club-join-request-confirm-name-contract | — |
| N-18 `/api/coop/list` 403 | 不改 | C-1 已把 403 当「没有合作邀约」处理（`readCoopListResult`）；服务端按会员解析商家上下文，客户端没有可靠的「是否有商家行」事实，按角色门控会漏掉兼任商家的主理人，故仍请求 | — | — | — |

## 四、门禁

见本文件末尾「门禁结果」节（全量单测、台账、路由矩阵、danger-confirm、ui-integrity、keyframes、WXS、mvn、主包体积、CI）。

STATUS: IN PROGRESS
