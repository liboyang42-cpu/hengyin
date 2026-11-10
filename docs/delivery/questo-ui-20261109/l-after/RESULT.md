# l-after 结果：主题之外的页面 —— 结算四步、统一邀请页、票夹、收集册卡堆、游戏主题库

- 分支 `claude/l-after-20261110`，从 `origin/launch/questo-20261110`（qx-base 写出 `STATUS: COMPLETE` 的 `ca1f85b6`）切出。草稿 PR 合入 `launch/questo-20261110`；没有合并、没有标为就绪；没有推 master / integrate / staging / 其他 lane 分支；没有碰生产、VPS、私有仓；没有新增或修改迁移；没有跑 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`；没有提交密钥。
- 只改小程序。没有改 Java、SQL、支付、退款、结算、权限。规格来源：`LAUNCH.md` l-after 段 + `proto/game` 的 st-overview / st-rank / st-rate / st-share / sh-invite / wl-wallet / al-album / ps-library（业主批准）+ `MOTION.md`。
- 截图在 `shots/`：390 宽、JPEG q70，都是走查 rig 的真实页面 + 真实后端（不是真机）。`-before` 用底座 `125de3bd` 的包（qx-base 之后只多一个 docs 提交），`-after` 用本分支的包，同一后端 jar（本 lane 不改后端）、同一身份（玩家 9004）、同一套步骤（`rig/shots.sh`）。每张图我都看过。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| __ROWS__ |

## 全局规则落在本 lane 的地方

1. 主题外观只在主题内：结算 / 排行榜 / 评价 / 分享 / 邀请 / 票夹 / 主题库全部是玩家黑底白字（`theme-dark` token），没有挂 `look--*`，也没有玻璃 / 锈蚀。
2. 数字只来自服务端：名次、分数、节点、剩余席位、通行证覆盖都读接口；读不到整格 / 整行不出，不写 0。
3. 动效全部是过渡 / 已有插图组件：本 lane 新增 `@keyframes` 为 0（棘轮仍 146），减少动态效果（系统 + 应用内开关）时直接终态；`cy-illus` 在分包里是 play 规范实现的逐字副本（底座单测锁定）。
4. 关闭在右上、底部按钮固定在底部、圆角走按钮 token；没有帮助文案（唯一一句「评价会公开，只显示昵称。」是业主批准原型里的）。
5. 不新建插件 / 引擎：榜单沿用 `rank-board`，邀请沿用 `cy-share-card`，主题库沿用公开目录接口与 `photo-card` 结构。

## 门禁

__GATES__

## Cross-lane

__CROSS__

## 待你决定

__ASK__

STATUS: __STATUS__
