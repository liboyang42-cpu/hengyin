# detail-pass-2 · 现有页面细节打磨二期（全应用铺开，2026-11-12）

- 分支 `claude/detail-pass-20261112`（接在一期之后）。草稿 PR 未合并、未标 ready；未碰 `master`、`launch/questo-20261110`、`ci/architecture/*`、`.github/workflows/ci.yml`；无生产、无密钥；没跑 `ci/xcx-check.sh` 与 `ds-hardcode-gate-selftest.sh`。`proto/motion-v2` 已忽略。
- 规则：版式、文案、配色方案一律不动，只做质感与动效（L1 层次 / L4 入场 / L5 按压 / L6 活的细节 / L7 读数）。

## 共享底座（一次写好，全应用吃）

| 项 | 内容 | 文件 |
|---|---|---|
| L1 层次 token | `--cy-depth-rest`（顶边高光 + 一层贴地影）、`--cy-depth-float`（浮起面），暗 / 亮 / 嵌套暗三处各自声明，随主题切换 | `style/tokens.wxss` |
| L5 组件按压 | 隔离组件内本地 `.cy-pressed` 与全局同口径（.97 / .92）；卡片、空态 CTA、错误态重试、弹窗按钮补松手回弹过渡 | `components/cy/{card,empty,error,modal}` |
| L4+L6 空态 / 错误态 | 块按文档顺序依次浮上来（40ms 错开、12px、420ms），主插画 / 图标缓慢呼吸（1→1.03，3 秒）；减少动态效果一概不起 | `components/cy/{empty,error}`、新增 `utils/life.js` |
| L1 浮层 / 指标卡 | toast 浮起面深度；指标卡贴地影 + 读数等宽 | `components/cy/{toast,stat-card}` |
| 静态替代 | 新增过渡的选择器补进系统减少动效名单 | `style/reduced-motion.wxss` |
| 测试 | 隔离组件 require 桩放行新工具；按压数值断言更新到 .97/.92 | `tests/unit/*` |

## 页面组（每组：改了什么 + 对比图）

每个页面文件按同一口径追加一段「细节打磨二期」样式（脚本逐文件生成，只追加）：①挂了 `cy-pressed` 的元素补松手回弹过渡（L5）；②金额 / 数量 / 距离等读数类补 `tabular-nums`（L7）；③有底色且圆角 ≥16rpx 的 `*card/*panel/*tile` 补 `--cy-depth-rest`（L1）。对比图 = 左 master（920141d5）/ 右本分支，390 宽，同页同状态。

| 页面组 | 范围 | 对比图 |
|---|---|---|
| 玩家（活动 / 主题 / 漫游 / 旅程 / 搜索 / 小队 / 合作 / 首页 / 游玩其余页） | `pages/activity,topic,search2,searchmap,square,team,templatedetail,coop,index,play`、`subpackageRoam,Journey,Pass` | `compare/player-*.jpg`、`compare/maps-*.jpg` |
| 商家浅色 | `pages/merchant`、`subpackageA,B,P3`（浅色深度档） | `compare/merchant-*.jpg`、`compare/messages-im-list.jpg` |
| 俱乐部 | `pages/club` | `compare/club-*.jpg` |
| 作者 / 编辑器 | `pages/publish,crop` | `compare/author-*.jpg` |
| 我的 / 设置 / 票夹 / 工具 / 消息 | `subpackageMember,Tools`、`pages/shezhi,gerenziliao,userinfo,mylike,address,addressinfo,privacy,agreement,deregister` | `compare/profile-*.jpg`、`compare/wallet-*.jpg`、`compare/messages-report-mine.jpg` |
| 空态 / 错误态 / 弹窗 / toast | 共享组件（见上表） | 各对比图中的空态页（如 `wallet-points`、`merchant-ledger`） |

每张对比图都看过：没有位移、尺寸、文案、配色变化。像素差值 ≤ 2.8%，来源是空态插画呼吸中途帧与按压/入场动画中途帧。

## 没做的 / 局限

- **逐页入场节奏（G7）与按钮就地变结果（L2）、单一主角（L3）**：需要逐页改 JS 与结构，会碰版式与流程，二期只在共享的空态 / 错误态里做了入场；其余页面保持原入场，没有强加。
- **G3 弹层后页面缩放**：组件拿不到页面根节点（一期已说明），未做。
- **对比图的数据局限**：rig 没有可用的业务数据，部分页面（`player-activity-*`、`merchant-*` 等）截到的是链接失效 / 空态；这些页面的深度与读数改动在有数据的状态下没有截图。
- **真机**：`animate` 呼吸与入场只在 rig 垫片里验过，没有真机验证。
- 对比 rig 无 Docker 以外的本地后端：用的是云容器里的 walkthrough rig（候选 a1d30fb 后端），before 用 master 920141d5 打包（8097），after 用本分支打包（8098）。

## 门禁

| 门禁 | 结果 |
|---|---|
| 全量单测（仓库根） | 17409 条 / 17408 过 / 1 红（`T03 数据条按稿` 在 master 同样红，一期已记录，本车道未碰） |
| ui-integrity | UI-GATE-0:PASS，无新增 baseline；A11Y PASS |
| 动作台账 / 路由矩阵 | 台账 `--write` 后 `--check` PASS（6025 控件）；路由矩阵 PASS（171 routes / 27 scenes） |
| WXS | PASS（14 个 .wxs + 4 段内联） |
| WXSS | 值内注释 PASS；选择器 / 组件选择器 PASS |
| keyframes 棘轮 | 无新增 `@keyframes`（动效走 `animate` 与过渡） |
| danger-confirm | PASS（53 个调用点，38 个不可逆有确认） |
| 主包体积 | 源码字节 3,343,172（一期 3,322,017，+21,155 B / +20.7 KiB）；该工具量的是压缩前源码，「< 2048 KiB」须在开发者工具里看打包体积，此处量不出来 |

STATUS: DONE
