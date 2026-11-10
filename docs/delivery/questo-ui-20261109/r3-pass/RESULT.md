# r3-pass 结果：通行证购买页与到账动效（第三轮）

分支 `claude/r3-pass-20261110`，从 `origin/claude/r2-store-20261110`（STATUS: COMPLETE，`9d3c450c`）切出。草稿 PR 合入 r2-store 分支。

只改小程序，并且都在 `subpackageMember` 分包里。Java、SQL、迁移都没有改，下单、支付、退款、结算的逻辑也没有动。动效参考 `refs4/pass-purchase-motion-frames.jpg` 的结构：黑底到暖橙渐变、金色物件由碎片拼起、+1 逐个弹出、拨盘转开、链条连起、分页点、白色「继续」、右上「跳过」。物件换成我们自己的金色城市通票，由票根、骑缝孔和城瘾印章组成，没有用保险库、钥匙或第三方美术。

截图都在 `shots/`，来自走查 rig：后端是 r2-store 基线 jar，用真实的 MySQL 8 和 Redis。before 用 r2-store 分支的浏览器包，after 用本分支。单张图 390×844、JPEG q70，`*-frames.jpg` 是同一段动效按时间顺序排的帧条，每张我都看过。

## 结果

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| R3P-1 通行证购买页 · 金色通票拼合 | 已修 | 通行证详情（购买页）在大标题下面加了一张金色城市通票。<br>• 进页时票身、票根、骑缝孔、印章四块碎片从四周飞回、拼合，然后整张票微微倾斜，表面扫过一道光，下方透出暖橙光。<br>• 票面上的字只用服务端的城市名；没有城市时写商品类型；两样都没有就空着。<br>• 页面其余部分（行、结算、底栏按钮）不变。<br>• 物件没有放在导航和大标题之间，因为全站标题间距合同不允许。 | `subpackageMember/components/cy/pass-object/*`（新）、`subpackageMember/market/pass/index.{js,json,wxml,wxss}` | 单测 `r3-pass-unlock`（8 例）。<br>截图：`R3P-1-city-before / after`（9301 上海城市通票，同页同状态）、`R3P-1-team-after`（9302 四人团体票）、`R3P-1-hero-frames`（拼合 5 帧） | 真机 3D 透视、扫光是否流畅 |
| R3P-2 到账分步动效 | 已修 | 只有服务端订单回 `statusLabel=PAID`、并且是「自己用」时，才出现整屏分步层。礼品订单仍然走原来的「礼品已买好」半屏，因为好友领取之前买家也不能玩，不能说「已到账」。<br>步骤全部来自服务端的商品详情和订单，没有这个事实就没有这一步：<br>① **已到账**：通票由碎片拼起，印章亮起，金光点散开，同时轻震一下。<br>② **可玩主题**：小通票在上方，下面按商品 `items` 一行一行打勾，每行弹出金色「+1」。标题写真实总数，最多画 4 行。<br>③ **有效期**：拨盘转开，下面的数字一格一格滚到服务端给的期限。相对期写「购买后 N 天内有效」；固定期写截止日期，例如 `2027.01.09`。<br>④ **同行**：`seatCount > 1` 时，金色链环一节一节连起来，标题写「N 人可用」；链环最多画 8 节。<br>界面上有分页点和白色胶囊按钮「继续」，最后一步是「完成」。「跳过」在右上，放在胶囊下面，避免和胶囊重叠。点「完成」或「跳过」后，仍然按原来的方式提示「已购买」，并进入「我的订单」。<br>下单、`requestId` 防重、现金支付、支付后只认服务端订单状态，这些代码一行都没改。 | `subpackageMember/components/cy/pass-unlock/*`（新）、`subpackageMember/utils/pass-unlock-view.js`（新）、`subpackageMember/market/pass/index.*` | 单测 `r3-pass-unlock`。rig 里用真实积分购买：9004 积分 2000 → 1700 → 1600，订单列表出现真实「已购买」。<br>截图：`R3P-2-paid-before`（原来付完直接进订单）；`R3P-2-step1/2/3/4-after`；帧条 `R3P-2-assemble-frames / themes-frames / dial-frames / chain-frames`；`R3P-2-done-after` | 真机轻震手感；安卓 `repeating-conic-gradient` 拨盘刻度渲染；现金支付回来后的同一路径（rig 里现金通道关闭，只走了积分） |
| R3P-2 减少动态效果 | 已修 | 打开「减少动态效果」后，物件不飞、不转、不倾斜，拨盘和数字直接停在最终状态，链环直接连好，也不震动。每一步只显示最终画面，还是靠「继续」翻页。 | 同上 | 单测检查两个组件都有兜底 CSS，JS 也直接设成最终状态。<br>截图：`R3P-2-reduced-frames`、`R3P-2-reduced-step1/step3-after`（rig 里打开 `play_reduced_motion` 后重启页面） | — |
| R3P-3 自玩票 / 平台会员购买页 | 不做（服务端没有） | 服务端没有会员商品、价格、权益和有效期，所以没有画会员页，也没有编价格或权益。<br>两个动效组件已经能直接用：会员上线后，用会员订单和商品事实生成步骤，交给 `cy-pass-unlock` 即可。<br>rig 里只在现有通行证购买页上展示真实的已付订单，没有为会员造数据。会员页需要的接口见 r2-store 的待你决定 1。 | — | — | — |
| R3P-4 主题自玩通行证到账（`cy-pass-reveal`） | 保持原样 | 主题详情买自玩通行证后的「通行证已到账」镭射卡动效，是你 2026-10-22／23 逐帧对照后定稿的，这次没有动。见待你决定 1。 | — | — | — |
| R3P-5 参考里的「连续打卡链」「稀有度勋章轮播」「提醒开启」三屏 | 不做（服务端没有） | 通票没有连续天数、勋章或稀有度的事实，也没有订阅消息模板 ID，所以这三屏都不做。<br>参考里的链条动效用在了真实的同行人数上。 | — | — | — |

## 待你决定

1. **自玩通行证的到账动效要不要换成新的分步层？**
   - 默认做法：不换。主题详情里的自玩通行证仍然用你定稿的 `cy-pass-reveal`（镭射卡 + 放进票夹）。新的分步层只用在通行证页（城市包、时限通票、年卡、团体票）。
   - 如果要统一，可以把 `cy-pass-unlock` 接到主题页的付款成功回调上。自玩通行证只有一个主题、一人、一个可玩期限，所以只会出现「已到账」和「有效期」两步。
2. **「可玩主题」一步要不要显示主题封面？**
   - 默认做法：只显示主题名。商品 `items` 只下发 `topicTitle`，没有封面。
   - 如果要像参考那样带图，需要在 `/api/marketing/product/{id}` 的 `items` 里加 `coverUrl`。
3. **付款后请求订阅消息**（参考最后一屏「别错过」）
   - 默认做法：不加，因为没有模板 ID。与 r2-store 的待你决定 8 是同一件事。

## rig 夹具（HARNESS，只写 rig 测试库 cy_e2e_51，不在产品代码里）

`rig/seed-pass.sql.txt`，内容如下：
- 两个在售通票，都覆盖 990013：
  - `9301` 上海城市通票：城市包，¥88 或 300 积分，购买后 30 天有效。
  - `9302` 上海四人团体票：4 人，¥198 或 400 积分，固定期 2026.11.01 – 2027.01.09。
- 9004 的积分设为 2000。
- 为了让服务端真实接受这单购买，主题 990013 补了四样东西：
  - 分买价 `self_play_price=200`
  - 平台费率 `fee_platform_rate=0.1`
  - 履约窗 `start_date` / `end_date`
  - 平台自营内容槽 `product_role CONTENT=PLATFORM`
  - 每补一样之前，服务端都按原规则拒单，拒单原文写在了 SQL 注释里；产品代码没有动。
- rig 分支 `claude/cloud-walk-20261101-rig` 打了 r2-store 用过的同样三个补丁：
  - `harness-patches.diff`
  - `rig-up-sha-param.diff`
  - `rig-app-oss-seam.diff`
- 截图用 `walk.mjs`：在「300 积分兑换」上点击，再点确认框的主按钮，都是真实点击。拼合帧条 `R3P-1-hero-frames` 是用 `eval` 把 `heroState` 设回 `scattered` 重放一次拼合；用的是页面自己的状态，没有画替身。

## 门禁

- 新单测 `r3-pass-unlock`：8 例全过。
- 全量单测（`node --test tests/unit/*.test.js`）：17004 例，补完台账前有 6 例失败：
  - 台账两例：已重建台账并更新计数，5882 → 5886（「继续 / 完成」「跳过」tap +2，整屏 catchtouchmove +1，`bind:done` +1）。
  - 组件 WXSS 不允许标签选择器：已改成逐个类名。
  - 标题间距合同：物件已挪到大标题下面。
  - 剩下两例是 r2-store / r2-join 基线上本来就红的：keyframes 棘轮 155 > 146，以及「减少动态效果」覆盖率。名单里只有 r2-join 的 `team` / `landing` / `gift`。本分支**没有新增任何 keyframes**，全部用 transition（只过渡 transform / opacity / box-shadow），也没有过渡布局属性。
- `build-action-ledger.js --write`：5886 controls，PASS。
- `ui-integrity.js`：`UI-GATE-0:PASS`。
- 主包：本分支没有改任何主包文件，新增文件都在 `subpackageMember` 分包里，所以仍是 r2-store 的约 2001.6 KiB（< 2048）。
- 没有运行 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`。
- 硬编码颜色：物件固有的金色、暖橙色都标了 `ds-ok` 并写明理由，其余颜色走 `--cy-*` 令牌（`--cy-color-medal-gold`、`--cy-ref-amber-*`）。

## 跨 lane

- r2-join：keyframes 棘轮和「减少动态效果」覆盖率这两项红灯仍归它修，本分支没有替它改。
- r2-visual：底部白色胶囊用 `--cy-radius-pill`，等它的统一圆角令牌。

STATUS: COMPLETE
