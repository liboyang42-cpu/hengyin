# docs/screenshots 的入库规矩

## 为什么需要这份规矩

`coop-finance-merchant-white-2026-08-04.png` 展示的分润页布局是
「顶部合计 + 核销销售额 / 平台服务费 / 商家应收 三行」。
当前 `components/cy/scene-merchant-profit/index.wxml` 渲染的是
`cy-stat-card` 双格 + 主题卡片列表 —— 那三个字段名在组件里一个都不存在。

页面被重写过，截图没跟着重拍，文件名里的日期看起来还挺新。
2026-08-27 的一次审查就据此得出过一个错误结论(以为分润页的合计对不上账)，
把它当证据用了半小时才发现页面早就不长那样。

**过期截图不是「有点旧的资料」，它是会让人得出相反结论的证据。**
所以这份目录的规矩是：证明不了拍摄时点的截图，不许当 UI 依据用。

## 入库规矩

1. **一图一条 provenance**：新增截图必须在下面的清单里加一行，写明
   `文件名 — 拍摄时的 commit SHA — 拍的是哪个页面/组件 — 拍它是为了说明什么`。
   写不出 commit 就说明当时没记，那这张图只能当装饰，不能当依据。
2. **改了页面就重拍或删图**：改动某页面的 PR，如果这里有它的截图，
   要么在同一个 PR 里重拍，要么把过期的删掉。留着比删掉更贵。
3. **宁删勿留**：拿不准是否还准确的，删。git 里永远找得回来，
   而留在目录里的过期图会被下一个人当真。
4. **发版 checklist 已挂钩**：见 `docs/发版checklist.md`。

## 清单

| 文件 | 拍摄 commit | 对象 | 状态 |
|---|---|---|---|
| `coop-finance-merchant-white-2026-08-04.png` | 未记录 | `pages/coop/finance` | **已删除(2026-08-27)**：与 `scene-merchant-profit` 当前实现完全不符 |
| `coop-list-merchant-white-2026-08-04.png` | 未记录 | 合作列表(商家视角) | ⚠️ 同批次、同样无 provenance，未逐项复核，勿作 UI 依据 |
| `baoming-checkout-*-2026-08-04.png` | 未记录 | 结算页 | ⚠️ 已被 `*-2026-08-23` 一批取代，仅作对比留存 |
| `baoming-checkout-poster-check-*-2026-08-23.png` | 未记录 | 结算页海报校验 | ⚠️ 无 provenance |
| `baoming-success-sheet-2026-08-04.png` | 未记录 | 支付成功 Sheet | ⚠️ 已被 `-2026-08-23` 取代 |
| `baoming-success-sheet-2026-08-23.png` | 未记录 | 支付成功 Sheet | 被 `docs/小红书_60篇发布顺序与Figma配图生产表_20260823.md` 引用 |
| `orderinfo-dark-2026-08-04.png` | 未记录 | 票夹(深色) | 被同一篇发布表引用 |
| `orderinfo-qantas/*.png` | 未记录 | 订单详情 | ⚠️ 无 provenance |
| `merchantinfo-0[1-6]-*.png` | 未记录 | 商家报名各状态 | ⚠️ 无 provenance，但六态成套，作状态枚举参考尚可 |
| `roam-intro-nrc.png` | 未记录 | 漫游引导 | ⚠️ 无 provenance |
| `doc-agreement.png` / `doc-privacy.png` | 未记录 | 协议/隐私文档页 | ⚠️ 法务文本会变，引用前必须对照 `utils/agreement-docs.js` |

> 表里「未记录」是存量事实，不是给新图开的口子。新增的必须填 commit。
