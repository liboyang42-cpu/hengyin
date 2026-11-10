# l-misc 结果(2026-11-10)

分支 `claude/l-misc-20261110`,从 `origin/launch/questo-20261110` 的 `ca1f85b6`(qx-base 已写 `STATUS: COMPLETE`)切出。草稿 PR #775,合入目标 `launch/questo-20261110`。没有合并、没有标为就绪;没有动 master、integrate、staging、launch 或别的车道分支;没有碰生产;没有新增或修改迁移;没有改支付 / 退款 / 结算;没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`;推送前查过 diff 里没有密钥。

截图在 `shots/`,390×844、JPEG q70,来自走查 rig:`claude/cloud-walk-20261101-rig` 加 staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`(OSS 是占位值,不是凭据)和 club 分支的 `harness-patches.diff`;MySQL / Redis 用固定 digest(从 mirror.gcr.io 拉)。后端 jar 是本分支构建的,所以新接口是真回包。before 用底座 `ca1f85b6` 的小程序包,after 用本分支的小程序包,同一个库、同一个身份、同一个页面状态。夹具在 `rig/seed-l-misc.sql.txt`(只写 rig 一次性库)。每张截图我都看过:没有空标题、重叠、截断或前后矛盾的数字。

## 结果

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| R2-q 队伍二维码(后端) | 已修 | 新接口 `POST /api/team/invite-qrcode {teamId}`。只给本队在队成员(和 `/api/team/info` 看邀请码同一口径),邀请已过招募窗口就拒绝。码复用现有 `PlayerWxacodeService.getOrCreateScene`:微信 `getwxacodeunlimit`、全局频控、单飞锁、OSS 存对象 key、读时现签;接口自身再加每人每分钟 10 次限频。scene 最长 32 字符,放不下「前缀 + 32 位旧码」,所以新码用 `tm.<22 位码>`,迁移回填的 32 位十六进制旧码用 `th.<同 16 字节的 base64url>`。微信签发失败、频控、存储失败时回 `qrState=UNAVAILABLE`,只给加入码,不算失败;无权和邀请失效照常拒绝。 | `IPlayTeamService`、`PlayTeamServiceImpl#inviteQrcode / teamScene`、`ApiPlayTeamController#inviteQrcode` | `PlayTeamInviteQrcodeTest` 7 项(成员出码、旧码重编码且 ≤32、微信失败降级、形状不对不调微信、非成员拒绝且不签发、已结束拒绝、游客 / 无队伍拒绝);原有 `PlayTeamSeatAndScopeTest`、`PlayTeamServiceImplTest`、`PlayerWxacodeServiceImplTest` 通过。rig 真接口:队长 9004 → `qrState=UNAVAILABLE` + 加入码(rig 没有微信);非成员 9001 →「无权查看该队伍」;同一人第 11 次 →「访问过于频繁」。 | 真实微信签码和扫码 |
| R2-q 队伍二维码(小程序) | 已修 | 队伍详情招募中、本人在队时,「邀请队友」旁加「二维码」。点开是半屏:码图在白底方块上(扫码要白底),下面一行「加入码 + 复制」;微信不可用时只出加入码;被拒只写原因。码和加入码都只取接口回包。扫码落地 `pages/index/index` 时按 `tm.` / `th.` 转到加入页,能否加入仍由 `/api/team/info`、`/api/team/join` 裁决。「我的队伍」右上加「加入码」,进加入页手输,码不对红字写在框下。 | `pages/team/detail/*`、`pages/team/join/*`、`pages/team/utils/team-invite-qr.js`(新)、`utils/game-door-entry.js`(`parseTeamScene`)、`pages/index/index.js`(同一行内追加分流,行数不变)、`subpackageRoam/my-teams/*` | 新测试 `l-misc-team-invite-qr` 7 项(scene 解析与不误吃门口码 / 主题码、冷启动存 scene、落地转加入页、回包投影三态、手输规整、入口只给在队成员、迟到回包丢弃、手输红字);`team-p1-contract`、`round9-m1-address-share-profile`、`guest-browse-login-on-write` 同步放行纯函数后通过。截图 `team-qr-before/after`、`team-qr-after-sheet`(降级态,只有加入码)、`my-teams-before/after`、`join-code-entry-after`、`join-code-error-after`、`join-code-found-after`(9002 输入加入码读到真实队伍)。 | 码图态(READY)只能真机看;长按识别、复制到剪贴板 |
| R2-q 商家计数统一 0 | 已修 | 业主「统一0」:商家端计数没有数就写 0。营销活动详情四格(名单人数 / 已送达 / 未同意 / 频控跳过)、评价页「N 条已核销评价」和「已回复率」从「—」改成 0。主题「客户」三格 r2-merchant 已改。金额类(结算、到账)仍按「未知不写 0」,不在这次范围。 | `pages/merchant/crm-campaigns/index.wxml`、`pages/merchant/reviews/index.wxml` | 新测试 `l-misc-merchant-zero-counts` 2 项;原有 crm / 评价相关 100+ 项通过。 | rig 里没有「计数缺字段」的营销活动夹具,没拍截图 |
| R2-q 商家转发 | 已修 | 业主「商家不是也可以是俱乐部成员吗」:商家发现页里,本人创建或加入的俱乐部的帖文出「转发」,别的俱乐部帖文和社区帖文不出。成员关系只读 `/api/club/my`(owned + joined),不挡帖文流:回包到了再给卡补上转发;读失败按未加入处理。转发动作和玩家发现页同一个 `club-post-repost.toggleRepost`,后端原有可见性与参与闸不变。 | `pages/merchant/relation/index.{js,wxml}` | `merchant-relation-state-contract` 新增 1 项(本人俱乐部出、别的不出、点不出请求、读失败不出),`club-post-repost` 断言改为「只在本人所在俱乐部出转发」。截图 `merchant-repost-before/after`:9002 加入城瘾跑团后,只有城瘾跑团那条帖子多出转发图标,执行俱乐部那条没有。 | — |
| create-x-provenance-sheet | 已修 | 业主「不用加这个素材来源」:模板配置封面下的「素材来源」一行和它的半屏(来源 / 署名 / 授权说明 / 保存)整块去掉,连同只供它用的 `media-provenance.js`、数据、处理器、返回守卫分支和样式。上传封面本身不变。设置页「素材署名」(许可要求的第三方署名)不是这个,保留。 | `pages/publish/temp/index.{js,wxml,wxss}`、删 `pages/publish/utils/publish/media-provenance.js` 与 `tests/unit/r2-create-media-provenance.test.js` | `overlay-motion-20261023-contract` 半屏遮罩数 12 → 11;UI-GATE-0 通过(temp 6 条 U4 基线只做行号重锚)。 | rig 里封面上传走裁剪页,点不到上传完成那一步,这一行出现前后没能截图 |
| kits-b2-x-cat 玩法目录 | 已修 | 业主「只有选择类别标签的时候横滑 玩法是之前的列表」:「选择玩法」半屏里分类标签仍是一条横滑,点哪类就只列哪类;玩法从横滑胶囊改回竖排列表,每行是分类色图标 + 名称 + 一句说明(锁定 / 不支持时写原因并变淡)+ 右侧选中圈。按原型 `proto/editor` 的列表行。底部说明 / 预览和「完成」才换玩法不变。横滑条的测量与滚动高亮代码一并删掉。 | `pages/publish/temp/index.{wxml,wxss}`、`pages/publish/temp/r5-editor.js`、删 `tests/unit/r10-f3-game-strip-highlight.test.js` | `cy-dropdown-contract` 改为断言「分类横滑、玩法竖排、点一类只列一类、不再有胶囊条」;`temp-editor-dropdown-contract` 通过。截图 `game-picker-before/after`(互动类)、`game-picker-after-logic`(点「推理」只列排序 / 连线 / 分类)。 | 真机滑动手感 |
| visual-a7-v1-p59 邀请记录列表 | 已修(按默认理解) | 业主「下面列表还是和其他页面明显不对 看起来更小」:邀请记录列表行高 128 → 152rpx、头像 64 → 88rpx(和其它列表页同档)、状态和奖励字号 caption → label,分隔线跟着头像宽度挪。数据和状态口径不变。 | `subpackageA/components/scene-member-invite-history/index.{wxml,wxss}` | 原有引用该组件的 10 个测试文件 140 项通过。 | rig 一次性库里没有邀请关系夹具,没拍截图 |

## 第二轮「要改」逐条归属(`proto/owner-notes-round2.txt`)

| 条目 | 业主原话(摘) | 归属车道 |
|---|---|---|
| create-a5-v3-46 玩法模板选择 | 为什么没有公共玩法 | l-editor(玩法 = 公共 / 我的模板) |
| create-a5d-0-1 … 0-9 故事编辑器 | 这个出原型 不需要复杂的文字编辑 | l-editor |
| create-b4-u02-01 采用条件 / 能力页 | 能力这个页面不是之前设计好了吗 | l-editor(能力页 = 现有 UI) |
| create-x-provenance-sheet | 不用加这个素材来源 | **l-misc,已做** |
| finish-b3-23-4-3 榜单两条 | 不用这么多榜单;全屏弹窗不超过导航栏 | l-after |
| finish-s0-b1-run-ending | 应该是绿色的 动画 获得了什么 | l-play |
| finish-x-chapter-end | 做的太丑了 | l-play |
| finish-x-settle-overview / rank / rate-error / share-post / share-story | 太丑了 看 X 的设计 | l-after |
| join-b4-u06-01 席位卡 | UI 看起来太乱 没有结构 | l-after(统一邀请页里席位 + 剩余席位);队伍详情席位卡本车道未改,见待你决定 6 |
| join-b5-x-p6-buy-refused | 不应该有蓝色的提示 | l-pass(通行证购买页) |
| join-x-b3-p1-5-referral / seat / seat-team | 没有描边的这种 需要动态设计 | l-after(统一邀请页) |
| kits-b2-ug-02 图片选项 | 不需要灰色的底 很大的图片 | l-kits |
| kits-b2-ug-04 结案结果三条 | 要是动态的设计 | l-kits |
| kits-b2-x-cat 玩法目录 | 玩法是之前的列表 | **l-misc,已做** |
| play-a1-v3-06 背包 / 站点 | 按钮放底部 第一站放顶部… | l-play |
| play-a1-v3-10-53 玩法进入 | 弹窗提示进入 嵌入和全屏 | l-play |
| play-a1-v3-15 延伸任务两条 | 改成弹窗 三个横滑任务 | l-play |
| play-a1-v3-17 入场码 | 二维码可以大一点 | l-play |
| q 会员 | 你看 questo 怎么处理的 | l-pass |
| q 商家「数字没有就是 0」 | 统一0 | **l-misc,已做** |
| q 摇一摇 | 掷骰子也可以摇晃的 | l-kits |
| q 动图素材 | 找免费素材或自己制作 不要 icon | qx-base(`cy-illus`)+ l-kits |
| q 商家转发 | 商家不是也可以是俱乐部成员吗 | **l-misc,已做** |
| q 队伍二维码与加入码 | 可以的 需要的 | **l-misc,已做** |
| visual-a7-v1-p59 邀请页列表 | 下面列表看起来更小 | **l-misc,已做(按默认理解)** |

## 门禁和测试

- 小程序全量单测(`node --test "tests/unit/**/*.test.js"`,本仓 `chengyinhub-xcx` 下):17148 项,10 项失败,和底座 `ca1f85b6` 同一批 10 项逐条相同,本分支没有新增失败。
- `node tests/lint/ui-integrity.js` → `UI-GATE-0:PASS`。没有新增基线登记;`publish/temp/index.js` 6 条 U4-DYNAMIC 锚点因删掉素材来源 −59 行只做行号重锚(逐行比对底座原行逐字相同,条数和理由不变),u4Dynamic keyDigest 按门禁同一算法重算,改动说明写在基线文件头。
- 动作台账按 `build-action-ledger.js --write` 重建(5935 个控件);`action-ledger.test.js` 五个计数按重建实测同步,各自写了注释。路由矩阵无变化。
- 后端:`mvn -q -DskipTests package` 通过;`PlayTeamInviteQrcodeTest`(7)、`PlayTeamSeatAndScopeTest`(19)、`PlayTeamServiceImplTest`(26)、`PlayerWxacodeServiceImplTest`(7)全部通过。本车道不碰管理端。
- 主包:`mainpkg-size-report.js` 的 `mainTotal` 3,286,816 字节 × 0.633 ≈ 2031.8 KiB(底座 2030.7 KiB,+1.1 KiB),低于 2048。
- 没有 `@keyframes` 新增;新弹层用现有 `cy-sheet`。

## 跨车道

- **l-after(统一邀请 / 分享页)**:原型 `sh-invite` 卡片右下角是小程序码。队伍邀请要放码时,直接调 `POST /api/team/invite-qrcode` 并用 `pages/team/utils/team-invite-qr.js` 的 `viewOf` 投影(READY 才有码;UNAVAILABLE 时卡上不放码、页面给加入码)。`cy-share-card` 的 `_loadCode` 本车道没改,避免和你们冲突。
- **l-editor**:`pages/publish/temp` 里「选择玩法」半屏已改成列表、素材来源已删;你们重做编辑器时如果碰到这两块,以本分支为准合并。

## 待你决定

1. **加入码的样子**。现在的邀请码是 22 位字母数字串(旧队伍是 32 位),能复制粘贴,但口头报不方便。默认:直接显示这串码,带「复制」,「我的队伍 → 加入码」可以粘贴进入。要 6 位短码,需要新加一列和一个迁移,并给短码加防枚举限频。
2. **谁能看到二维码**。默认:本队所有在队成员(和「邀请队友」分享链接同一口径),不只队长。
3. **扫码落到哪个版本**。和现有门口码、主题码一样,码固定 `env_version=release`、落地 `pages/index/index`。所以码要等这一版发布上线后才能扫进加入页;体验版扫出来会进线上旧版。默认照旧。
4. **「统一 0」的范围**。默认:只改计数(人数、条数、比率),金额仍「未知不写 0」;接待容量这种商家自己填的配置没填时仍写「—」。如果金额也要写 0,告诉我。
5. **邀请记录列表的尺寸**。默认按「和其它列表页同档」放大(行高 152rpx、头像 88rpx、状态字号 label)。如果你说的「更小」是别的(比如字体、卡片宽度),给我一张对比图。
6. **队伍详情的席位卡**(join-b4-u06-01「太乱 没有结构」)。默认:本车道没改,交给 l-after 的统一邀请页一起重排席位;如果你要队伍详情页单独重做,告诉我。

STATUS: PARTIAL - 代码、测试与门禁都已完成;截图缺:二维码出图态(rig 没有微信接口,只拍到降级为加入码)、商家计数两页、素材来源去掉前后、邀请记录列表(rig 无对应夹具或点不到上传完成)
