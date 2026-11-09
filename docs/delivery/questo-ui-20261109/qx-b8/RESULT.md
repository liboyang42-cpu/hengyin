# qx-b8 结果：完局与公开目录

分支 `claude/qx-b8-20261109`，基于 `origin/integrate/questo-ui-20261110` 的 `449fb80d`。只改小程序。没有改后端，没有新增迁移，也没有连生产。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验的 | 真机仍未验 |
|---|---|---|---|---|---|
| U08-01 正式结果 | fixed | 完成页结果词改为只认服务端事实。读 `GET /api/play/player-journey/report` 的 `completionKind`：`LEGITIMATE_COMPLETION` 显示「已通关」，`ASSISTED_COMPLETION` 显示「辅助完成」，`ENDED` 显示「已结束」。到达回执里服务端判过 `completed` 的，战报在途时先显示「已通关」。只有本地站点全部完成、但战报还没回或读不到时，结果未知，整行不显示，不再凭本地 `allDone` 写「已通关」。换局、换账号或关掉面板后到的迟到响应会丢弃（沿用 `_isFinishViewCurrent`）。照片和分享原本就是分开的：路线分享卡不画照片，「我的旅途照片」只在本人完成页出现，所以这部分没有改动。 | `pages/play/utils/finish-result.js`（新）、`pages/play/index.js`、`pages/play/index.wxml` | `tests/unit/qx-b8-post-game.test.js`。原来钉住旧表达式的 3 处断言已改为新合同：`play-ui-contract`、`r11-g1-finish-sheet-label`、`longrun-own-c01-13`。play-* 821 例通过 | 这项**没有截图**，原因见「rig 限制 1」。真实完局后战报返回 LEGITIMATE 与 ASSISTED 两种结果时的显示还没跑过 |
| U08-02 下一场推荐 | fixed | 候选只要还能报名的：服务端给了 `endDate` 且已经过期的场次跳过；没给结束时间的，仍按活动列表本身的公开闸放行。曝光和点击带同一个 `impressionId`，并附来源活动、来源主题和来源局（`sourceRunId` = 路线会话 id），推荐效果能追到原来那一局。点击只打开活动详情，不算报名；没有候选时自然不出卡；推荐读取失败时，完整结局照常显示（原逻辑）。 | `pages/play/finish-route-recommendation.js`、`pages/play/index.js` | `qx-b8-post-game.test.js`，加上原 `play-finish-route-recommendation.test.js` 8 例 | 无截图，原因同上。埋点能否进分析库没有验 |
| U08-02 问题反馈 | needs owner | 没做。要求地点和题目问题能追到原修订，但现有的 `/api/topic/report` 是举报审核，`/api/community-trust/reports/submit` 也是举报，两者都不带节点和修订身份。不能把「问题反馈」画成举报。 | — | — | 默认做法：完成页不放反馈入口，等后端提供带 `nodeId`/`contentRevisionId` 的反馈写接口 |
| §20.1 公开目录 | fixed | 新页 `pages/topic/catalog/index`，放在 subpackageTopic，不进主包。结构照抄 `pages/search2/result`（深色玩家域、主题大卡）。城市选择器只列服务端聚合出的、有编码且有名称的城市，不猜名。筛选的玩法、交付形态、可接团体、可接学校都来自 `/filters` 的真实取值；同一字段单选，再点一次取消；未知枚举交给服务端返回 400，不降级成「全部」。卡片：`priceState=UNKNOWN` 显示「价格待确认」，不回退旧价、不当 0；未在售显示「未开售」或「已停售」；`freeSelfPlay` 单独显示「免费自玩」，不因为未开售被封死；团体和学校只在值为 `true` 时出现，`null` 算未声明，不写「不接待」；语言只在缺译回退时标出实际语言。读取失败沿用 kit 的 `load-failure`（空白占位加自动 toast），和「暂时没有公开作品」的空态分开。 | `pages/topic/catalog/index.*`、`pages/topic/utils/public-catalog.js`、`app.json`（分包加 2 页） | `tests/unit/qx-b8-public-catalog.test.js` 9 例；rig 截图 S20-01、S20-02、S20-03 | 下拉刷新、分享卡样式、真实 OSS 封面 |
| §20.1 落地详情 | fixed | 新页 `pages/topic/catalog/detail?id=`。只显示封面、标题、城市、时长、站数、购买事实、简介（服务端截断的 summary）、游玩方式、首章前几站的钩子、章节名和无障碍说明；答案、正文和提示本来就不在投影里。主按钮「查看作品」进入真实主题详情 `pages/topic/index/index`，走原有的报名和购票链。这里不报价、不建单。 | `pages/topic/catalog/detail.*` | 测试同上；rig 截图 S20-04、S20-05 | — |
| UI-P3 主题合集落地 | fixed | 「合集」就是城市：`catalog/index?city_code=` 直达，不需要先经过首页，标题是服务端给的城市名，卡片列出实际公开的作品，不足 3 个就显示实际数量，不补假关卡。页面上没有评分、人数和完成次数，也没有「到货提醒」或任何订阅入口。分享路径只带合法筛选参数。 | 同上 | rig 截图 P3-01..04（P3-04 是点主按钮后进入的真实主题详情） | 外部投放链接参数的约定，见 needs owner |
| U08-05 榜单与公开内容治理（小程序部分） | fixed | 已下架、未公开或读不到的作品，详情页一律显示「作品已下架或未公开」，并收起分享入口（`wx.hideShareMenu`），不留指向空页的链接；只有公开作品才能分享。目录只展示服务端公开闸放出的作品，不靠前端隐藏。 | `pages/topic/catalog/detail.js` | `qx-b8-public-catalog.test.js`（分享收起与展开）；rig 截图 S20-06 | — |
| U08-05 榜单范围、异常标记 | already fixed / 他道 | 成长榜原本就区分空榜、读取失败、未登录和无权限，没有空榜伪装。§23.4 多作用域榜归 qx-b3，后台标记与处置归 qx-b6。服务端没有给出时间窗或异常原因，所以这道不加任何显示。 | — | 读代码 `subpackageP3/pages/growthcenter/leaderboard` | — |

截图在 `shots/` 下，宽 390，JPEG q70。新页面只有 `-after`。U08-01 和 U08-02 没有 before/after，原因见下文。

结构模板：目录列表照抄 `pages/search2/result`；落地详情的头图沿用同一张主题大卡，行和分组的层级照完成页的 `.fsec`/`.frow`；筛选药丸复用全局 `.cy-chip`、`.cy-chip--on`，外层包到 88rpx 触控高度。封面上的文字固定为白色，这是照片语境，与订单卡的 `tpl-big` 相同，已标 `ds-ok`。

## 门禁

- `node tests/lint/ui-integrity.js`：结果是 `UI-GATE-0:PASS`。基线没有新增条目。我的插入让两个已有锚点只发生了行号位移：play U4-DYNAMIC 从 1847 移到 1849，U5 loadMilestone 从 8809 移到 8831。调用行逐字相同，理由和条数都没变。按仓库惯例，我重新标注了这两个锚点，按 `baselineKeyDigest` 重算了 keyDigest，并在基线文件头写了日期注释。
- `build-route-matrix.js --check` 通过，共 159 条路由；`build-action-ledger.js` 通过，共 5758 个控件。台账在基线上原本就已漂移，这次用 `--write` 重建后提交，所以 diff 很大。
- 单测：qx-b8 两个文件、play-*、topic* 和相关旧测试一起跑，1088 例全部通过。全量结果见 PR 描述。
- 没有运行 `ci/xcx-check.sh`，也没有运行 `ds-hardcode-gate-selftest.sh`。

## Rig 说明（HARNESS，不是产品问题）

1. **完成页到不了。** rig 自带的 `chains.py player` 在当前基线上卡在 sale_close：俱乐部开场次返回「自由探索只开放内容所属俱乐部的场次」，后面的 player 链没有执行。fixture 玩家 9004 打开游玩页时得到 402「请先购买自玩通行证」。没有真实完局，就不往页面里注入假进度去拍完成页，所以 U08-01 和 U08-02 只有单测证据。
2. **目录接口在 rig 里 500。** 原因是 rig 没有 OSS 配置：`imgUrl` 序列化需要 `CHENGYIN_OSS_BUCKET_NAME`。我只在 rig 的 `app.py` 里补了一组假的 OSS 身份（bucket=rig-bucket，endpoint=oss-rig.invalid），只用于读路径签名，不访问任何 OSS 主机。产品代码没动。所以截图里的封面都加载不出来（ERR_TUNNEL）。
3. **目录 fixture。** 在一次性的 rig 库（cy_e2e_51）里，把公开主题 990014 克隆成 990101、990102、990103，分别覆盖上海免费自玩、上海在售 ¥39、杭州在售但价格未知三种情况；给俱乐部 7001、7002 填了 city，因为服务端的城市名只取自 `club.city`；给 990101 加了一行落地元数据（可接团体 6–30 人，8–60 岁）。这些都是测试数据，不进仓库。

## Cross-lane（需要别的道或后端处理）

1. **后端，可能是真缺陷。** `migration_marketing_product_20261108.sql` 建 `marketing_product_order` 时只写了 `DEFAULT CHARSET=utf8mb4`，没有写 COLLATE，于是在 MySQL 8 上落到默认的 `utf8mb4_0900_ai_ci`。`GiftEntitlementMapper` 用 `o.order_no = r.parent_order_no` 把它和 `cms_registration`（`utf8mb4_general_ci`）连接，报 1267 Illegal mix of collations。结果是 rig 里 `/api/play/nodes` 和 `/api/play/player-journey/report` 全部 500。生产库的默认排序规则如果也是 0900，这个错就会出现在线上。按规定不能改已有迁移，需要后端补一个新迁移，把该列或整张表钉到 `utf8mb4_general_ci`，或者在 SQL 里显式写 COLLATE。我只在 rig 库里手动改过这一列。
2. **后端。** 公开目录的 `min_minutes`/`max_minutes` 拿 `cms_topic.total_time` 直接比较，但主题详情把 `total_time` 当秒来格式化，单位可能不一致。这次没有接时长筛选。
3. **后端。** 公开目录的城市名只来自所属俱乐部的 `club.city`，不属于任何俱乐部的作品永远没有城市名，也就不会出现在城市选择器里。这是服务端口径，前端照实显示。
4. **qx-b1 / qx-a1。** 我改了 `pages/play/index.js` 和 `index.wxml` 完成页的结果行、`openFinish` 和推荐埋点。改动集中在 `finish-result.js` 和 `finish-route-recommendation.js`，play 主文件只加了约 25 行。如果合并时冲突，以 `finishResult` 驱动结果行这条为准。
5. **qx-b3。** P7 战报容器归 b3。我只在完成页读同一个 `/report` 端点取 `completionKind`，没有画战报。

## needs owner（每条都已按默认值实施）

1. App 里在哪里放目录入口？默认不加，只能通过分享或外链直达 `pages/topic/catalog/index?city_code=` 和 `detail?id=`。
2. 本地站点全部完成、但服务端战报读不到时，完成页的结果行怎么办？默认不显示结果词，标题和数据照常。旧逻辑会直接写「已通关」。
3. 完局问题反馈：默认不放入口，等后端提供带节点和修订身份的写接口。
4. 「共同完成者」：`/report` 没有返回同局成员，默认不显示。

STATUS: PARTIAL - U08-01/U08-02 rig 无真实完局、未截图（仅单测）；完局问题反馈待后端写接口
