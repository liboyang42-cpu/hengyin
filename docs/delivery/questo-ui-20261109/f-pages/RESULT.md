# f-pages 结果

- 分支 `claude/f-pages-20261111`，从 `origin/launch/questo-20261110` 切出；中途两次合入 launch 最新头：`afa90ffd5`（f-compliance、f-money、f-release）和 `e01a45ded`（l-icons、l-iconsB），冲突只在 `ci/migration-dependencies.json`，两边条目都保留。
- 草稿 PR 指向 `launch/questo-20261110`，没有合并，也没有标记 ready。
- 截图都在 `shots/`，390 宽、JPEG q70。`<ID>-before.jpg` 用 launch 底座 `afa90ffd5` 打包，`<ID>-after.jpg` 用本分支打包。两边用同一个后端（launch 底座的 jar）、同一份 rig 库，步骤也相同（`rig/shots.sh`）。

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| 第 6 组 P-20 / M-33 本人主页帖文 tab 发帖入口 | 已修 | 本人主页的帖文 tab（玩家和商家共用 `cy-profile`）在状态块之前加一行折叠发帖入口，形态和商家「发现」页一样（头像、名字、「有什么新鲜好玩的分享吗？」、图片 / 地点两个图标）。空态上方也会显示。点开是同一个 `cy-post-compose`，发布成功后收起，并重新读本人帖文。入口只看服务端 `communityPostWrite`（`app.canUseCommunity(true)`），他人主页不显示。rig 里发现：开关请求和主页读取同时发出，入口原来只在首次读完时同步一次，所以一直是 false。现在改在 `_syncCommunityProfile` 的 settle 里同步（开关读回来后会再跑一次）。行内的名字和头像改用主页自己的 `_displayName` / `_avatarSrc`。 | `subpackageMember/components/cy/profile/index.{js,wxml,wxss,json}` | `P-20-before/after`；单测 `f-pages-profile-post-compose`，以及引用 profile 的 1188 条单测 | 商家身份进本人主页时的发帖行（rig 只拍了玩家 9004） |
| 第 7 组 P0-79 官方路线 | 已修（接入口，不下线） | 判断依据：后端有 `/api/preset/route/list`、`/info`（permitAll，只返回已上架路线），后台有 presetRoute 的增删改查，数据有真实来源，所以给入口，不从 app.json 删页面。漫游护照的玩法大卡行里，只有服务端返回至少 1 条路线时才出现「官方路线 · N 条路线」卡，点卡进入已有的列表页。没有路线就不显示。这个读取不加 `silentError`，避免新增 U5 登记。去掉了 `nav-route-exists` 里的豁免。 | `pages/roam/index.js` | `P0-79-before/after`（rig 库放了两条已上架路线）、`P0-79-list-after`（列表页）；单测 `f-pages-dead-pages-entries`、`roam-passport-server-read`、GET 矩阵登记 | — |
| 第 7 组 P0-108 / P0-109 据点页评价 | 已修 | 评价区保留评价列表，原来的内联表单改成「写评价」按钮加 `cy-sheet fixed="half"` 半屏（星级、正文、提交）。按钮只对已完成挑战的玩家显示（服务端同样会拦）。提交成功后收起半屏并重新读取。rig 里发现：半屏嵌在 scene-sheet 面板里，叠层登记会把外层标成 under，外层被压暗并设为 `pointer-events:none`，里面的表单也跟着点不动。修法是给 `cy-sheet` 加 `noDepth`（默认 false，其他调用不受影响），评价半屏传 `no-depth`。 | `subpackageMember/components/cy/scene-roam-poi-detail/index.{js,json,wxml,wxss}`、`components/cy/sheet/index.js`、`behaviors/sheet-depth.js` | `P0-108-before/after`（评价区）、`P0-108-sheet-after`（半屏）；单测 `f-pages-dead-pages-entries`（含嵌套登记用例）及 sheet / depth 相关 378 条 | 键盘弹起时半屏的位置 |
| 第 7 组 OFF-FAV-01 我的收藏「据点」页签 | 已修 | 「帖文 / 主题」后面加「据点」页签，读 `GET /api/city/nodes/favorites`（服务端用 `uid()` 取本人身份，不接收用户 ID）。卡片和主题收藏用同一张 `favorite-card`，封面优先用据点封面，没有就用商家 logo。点卡进 `poi-detail`。取消收藏调用同一个 toggle 接口，只有回包 `favorited=false` 才把卡片移除；同一据点请求未返回时不能再点，结果不确定时重新读列表。取消后不弹成功 toast，卡片消失就是反馈。 | `pages/mylike/mylike.{js,wxml}` | `OFF-FAV-01-before/after`（rig 里 9004 通过真实接口收藏了一个据点）；单测 `f-pages-dead-pages-entries`、`mylike-card-name-contract`、`community-gate-detail-favorites`、GET 矩阵登记 | — |
| 第 9 组 P-32 主地图底部活动 / 队伍横滑卡 | 已修 | 漫游开始后的主地图（`screen=map`）底部加一行横滑卡，和入口页读同一份数据（附近活动 + 附近队伍），用同一张 `entry-card`，高度压到 200rpx。点队伍进队伍详情，点活动进主题或活动详情。两份数据都为空时不显示。门店横滑条在时让位。修了一个回归：上一次提交把 `entryCards / searchPlaces / entryLoadError` 写进了行尾注释，回到入口时这三个字段不会被清空，换账号后旧入口卡会残留（`roam-recovery-flow` 发现）。 | `pages/roam/index.{js,wxml,wxss}`、`utils/roam-entry-map.js` | 单测 `f-pages-roam-map-live-cards`、`roam-recovery-flow` | 没有截图：rig 地图是 canvas 替身，要进主地图得走完 GO → 定位 → 开场 4.2 秒，附近活动和队伍还需要先造数据。在真机上看横滑卡和底部 HUD 的间距 |
| 第 9 组 V3-35 33 个目录地点坐标导入 | 未做，原因见下 | `docs/roam-city-catalog-20261023.md` 规定首批坐标全部为空，必须由运营在腾讯 / 高德地图逐条核对，确认是 GCJ-02 后填写 `provider / providerPoiId / verificationSource / lat / lng / verifiedOn / matchConfirmed`。导入脚本对未核验的记录会拒绝生成 SQL。仓库里没有任何已核验坐标，猜坐标或用街区中心代替都违反这条规则（而且会变成可领奖励的地图点），所以没有写迁移。已经按文档流程用 `scripts/roam-catalog-import.js --template` 生成了 33 条核验模板。 | `f-pages/roam-catalog-coordinates.template.json` | — | — |
| 第 9 组 G-11 二维码出现动效 | 已修 | 通用分享卡和邀请卡的码图在 `bindload` 后从 0.6 倍弹到原位（只用 transition，没有新增 `@keyframes`）。`binderror` 也会把状态置为 ready，加载失败时不会一直透明。开启减少动态效果时直接显示终态（类名加 media query）。 | `components/cy/share-card/index.{js,wxml,wxss}` | 单测 `f-pages-roam-map-live-cards`、`r3-share-cards`、`reduced-motion-coverage-contract`、`motion-property-ratchet` | 没有截图：rig 的码图是占位图，弹出发生在加载那一瞬间，截图看不出变化 |
| 第 9 组 漫游打开动效还原 | 未改，原因见下 | 你在 10-27 的意见是「打开漫游不如之前，变奇怪了，优先还原到之前的版本」。公开仓的历史从 10-12 快照开始（`a20fb9b60`），快照之后 `roam-opening*`（弧形切场 → 品牌开场 → 吃豆 logo，4.2 秒）一行都没改过。「之前的版本」只在私有旧仓里，按规则不能从旧仓搬历史，凭印象重写也不算还原，所以保持现状。 | — | — | 见「待你决定」第 1 条 |
| 第 12 组 P-56 手机号行 | 已修 | 个人资料里手机号那一行，行尾的进入箭头换成修改图标（edit）。 | `pages/gerenziliao/gerenziliao.wxml` | `P-56-before/after` | — |
| 第 12 组 P-41 规则种子拆步 | 已修 | 新增数据迁移 `migration_20261111_rule_seed_split_steps.sql`，已在 `ci/migration-dependencies.json` 登记。四份官方模板种子的 `rule_instructions` 原来是一整行「1. … 2. …」，现在改成一步一行，拆法和小程序 `utils/rule-steps.js` 相同。只更新 WHERE 条件里仍是种子原文的行，人工改过的不覆盖，可以重复执行。只改数据，不改结构，也没有修改已有迁移。生成脚本见 `f-pages/tools/`。 | `chengyinhub-admin/sql/migration_20261111_rule_seed_split_steps.sql`、`ci/migration-dependencies.json` | 单测 `f-pages-rule-seed-split-migration`；迁移布局门禁、迁移安全门禁通过 | 需要走 chengyin-db-migrate 只读预检后再执行 |
| 第 12 组 O-02 合并 haversine | 已修 | `publish-stats.js` 的 `haversineKm` 改为调用 `utils/geo.js` 的 `haversine`，只做米到公里的换算。asin 和 atan2 两种写法数学上等价，`.toFixed(2)` 显示结果不变。 | `pages/publish/utils/publish/publish-stats.js` | 发布统计相关单测（111.19km ±0.1） | — |
| 第 12 组 O-15 revenue_share 校验 | 已修 | 新增 `ProductRole.requireValidRevenueShare`：写入前校验取值在 [0,1] 之间、最多 4 位小数，四条 insert 路径都经过这道校验。 | `ProductRole.java`、`ProductRoleServiceImpl.java` | `ProductRoleRevenueShareGuardTest` 5/5（`mvn -pl chengyinhub-admin -am test`） | — |
| 第 12 组 发布检查 warning 档 | 已修（只改显示） | 发布前检查弹层里，advisory（软建议）用琥珀色 warning 档显示，可定位的项有「去修改」，不会禁用「完成」。弹层什么时候出现保持不变：上一次运行改成了「只有 advisory 时也弹出」，和你 10-30 定的 V-24「删除发布确认，没有阻断项就直接发布」冲突，`fabu-ai` 五条单测因此变红，这次已改回。 | `pages/publish/fabu/index.{js,wxml,wxss}` | 单测 `f-pages-publish-check-warning-tier`（含 V-24 不回退）、`fabu-ai` | 只有 advisory 时作者看不到 warning（V-24 下不弹层），见「待你决定」第 2 条 |

## 门禁

- 小程序全量单测：合入 launch `e01a45ded` 后的合并树 **17264 / 17264 通过**（0 失败 0 取消）。合入 launch 后先出现 13 条失败和 4 条取消，都是本线引入的问题，已全部修复：V-24 回退、漫游重置字段被注释吞掉、分享卡根节点 class 合同、收藏卡负控、成功 toast 棘轮、GET 矩阵两处登记、护照读取桩、台账计数、U1 锚点。
- `ui-integrity --warn-as-error`：**UI-GATE-0:PASS**。没有新增登记，u1Variable 9、u4Dynamic 62、u5Silent 42 条数不变。roam 17 条、profile 3 条已有锚点只是行号变了：用 difflib 对照 launch 底座逐行映射，并核对调用行文字完全相同，然后按门禁的 `baselineKeyDigest` 重算 keyDigest。
- 动作台账：已重建，`--check` PASS。控件数 6007 → 6021（再合入 l-icons / l-iconsB 后，合并树为 6010 → 6024）：tap +6（openComposer、onLiveCardTap、openReviewSheet、goNode、nodeUnfavorite、locatePublishIssue），事件合同 +8（码图 load/error ×2、发帖 close/published、评价半屏 close、据点页签 cta）。
- 主包：`mainpkg-size-report --json` 的 `mainTotal` 是 3,305,151 字节，×0.633 约 **2043.1 KiB，小于 2048**。底座是 2039.1 KiB，本线增加约 4 KiB（roam 页、我的收藏、分享卡、cy-sheet 一个属性）。
- Java：`ProductRoleRevenueShareGuardTest` 5/5。
- 迁移：`migration-layout-check.sh`、`migration-safety-check.sh` 通过。
- 没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。

## rig

- 走查 rig 用的是 `claude/cloud-walk-20261101-rig`，在单独的 worktree 里。`up.sh` 的 SHA 改成可以由 `RIG_SHA` 传入（指向 launch 底座）。Docker Hub 返回 429，所以在 `/etc/docker/daemon.json` 加了 `mirror.gcr.io` 镜像源。
- rig 库里为截图造的数据，只存在于本机测试库：两条已上架的 `preset_route`；一个 `roam_poi`（type=2，挂样板咖啡 8001）；9004 通过真实接口收藏这个据点；一条 `roam_poi_reward_log status=1`（让「写评价」出现）。
- 和本线无关的 rig 问题（HARNESS）：据点页「评价暂时无法读取」是因为这个手造据点缺少字段，`reviewTargetExists` 不认它，before 和 after 一样；OSS 图片是占位图。

## 待你决定

1. **漫游打开动效**：要还原到「之前的版本」，需要你提供当时的录屏，或者旧仓里那次提交的说明（只要看效果，不搬代码）。没有这些我无法判断「之前」是什么样子。现在是 10-12 起就没变过的弧形切场 → 品牌开场。
2. **发布检查 warning**：默认按 V-24，没有阻断项就直接发布，warning 只在弹层因阻断项出现时才看得到。如果你希望只有 warning 时也先给作者看一眼，把 `_afterPrecheck` 的条件加上 `local.advisory.length` 即可（上一次运行就是这么做的），但这等于部分撤回 V-24。
3. **33 个目录地点坐标**：需要运营按 `f-pages/roam-catalog-coordinates.template.json` 填写已核验的 GCJ-02 坐标。填好后用 `node scripts/roam-catalog-import.js` 生成新迁移，登记后走只读预检。

## 交叉 lane

- **qx-u3（叠层景深）**：`scene-member-order-detail` 和 `scene-roam-poi-detail` 里原有的 `cy-option-sheet`（选择题）同样嵌在 scene 面板里，打开时外层会被压暗并设为 `pointer-events:none`，可能出现同样点不动的问题。本线只给自己新加的评价半屏传了 `no-depth`，其他调用没有改。建议由 u3 统一判断嵌套弹层是否应该跳过叠层登记。

STATUS: COMPLETE - 第 6、7、12 组全部完成；第 9 组完成横滑卡和码图动效，33 个坐标（没有已核验坐标，按目录文档规则不能猜）和打开动效（之前的版本不在公开仓）未做，原因已写明；UI-GATE-0 PASS、台账重建、主包 2043.1 KiB、全量单测 17243/17243、Java 5/5
