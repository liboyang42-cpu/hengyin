# 城瘾 DS · 改页自查清单(防回潮)

> 改任何 wxss/页面前后过一遍。配套 pre-commit 门禁 `scripts/ds-hardcode-gate.sh`(DS-13)自动拦硬编码回潮。
> 设计真源:vault《城瘾 Design System v1.1 · 双主题统一版》+《城瘾 DS 整改清单 TODO》。

## 一、提交前自查(DS-15)

- [ ] **颜色**走 token:`var(--cy-brand/info/danger/success/gold)`、文字 `var(--cy-text-title/body/secondary)`、底 `var(--cy-bg-page/card/card-2)`、描边 `var(--cy-border-line/card)`。不裸写 `#fff`/`#000`/`#7A5CFF` 等。
- [ ] **字号**走 6 档:`var(--cy-font-caption22/label24/body28/subtitle32/title36/display40/page-title48)`。不裸写 `font-size: 28rpx`。
- [ ] **圆角**走 4 档:`var(--cy-radius-sm16/md24/lg32/pill999)`。不裸写 `border-radius: 20rpx`(除 `50%` 圆、≤8rpx 刻意小角、>40rpx 自定义大角)。
- [ ] **页边距**走 `var(--cy-page-x)`(32rpx)。页级横向 gutter 不裸写 30/36/40rpx。
- [ ] **动效**时长/缓动走 `var(--cy-dur/-fast) var(--cy-ease)`;不裸写 `transition: .3s ease`。
- [ ] **可点击文字/链接**用蓝 `var(--cy-info)`,不用紫(紫 `--cy-brand` 只作选中/装饰)。
- [ ] 玩家页默认暗、商家页浅(`@import merchant-light` 或 `.theme-merchant`);别让玩家页越狱成浅色。
- [ ] 改动**截图模拟器**核对(automator 或人肉),不只信"0 console error"。

## 二、刻意例外(允许硬编码,门禁豁免)

游戏/地图/创作画布装饰色、渐变停止点、rgba 叠层/scrim/阴影、50% 圆、≤8rpx 小角、>40rpx 自定义大角、非精确近似值 —— 这些是**有据保留**的。若在门禁范围页(内容页)确需硬编码,**行尾加 `/* ds-ok */`** 豁免该行。

## 三、DS-13 门禁怎么用 / 装

pre-commit 已接入 `scripts/ds-hardcode-gate.sh`,提交内容页 wxss 若新增裸 hex/字号/圆角会被拦。

- **手动跑**:`bash chengyinhub-xcx/scripts/ds-hardcode-gate.sh`(在仓根,先 `git add` 待查文件)
- **单行豁免**:该行加 `/* ds-ok */`
- **整体绕过**:`DS_ALLOW_HARDCODE=1 git commit ...` 或 `git commit --no-verify`
- **排除页**(天然不查):`style/`(token 定义;`style/common.wxss` 例外受管)、`pages/index`、`*/creator*`、`pages/badge-wall`(装饰/画布页)。⚠️ 2026-09-18 纠偏:`pages/publish`(07-26 取消)、`pages/play`、`pages/roam` **已收编受管**,别再按旧清单以为它们免检;刻意值走行尾 `/* ds-ok */`,存量不受影响(门禁只看新增行)。
- **重装钩子**(clone 后 `.git/hooks` 不随仓库):在仓根 `.git/hooks/pre-commit` 末尾加
  ```bash
  bash chengyinhub-xcx/scripts/ds-hardcode-gate.sh || exit 1
  ```

## 五、标题体系(2026-08-04 产品拍板 · 真源=vault《最终视觉四目录全量终审复核_20260803》§12)

**规则**:普通二级页统一 `cy-nav-bar` + `cy-page-title`,`navigationStyle: "custom"`。
不得回到裸 `.h1`,也不得在有 `cy-page-title` 的页面里另画一个 `.cy-h1` 重复标题。

- [ ] 新增二级页:`.json` 里 `navigationStyle: "custom"` + `usingComponents` 同时注册 `cy-nav-bar`、`cy-page-title`。
- [ ] 页面标题只出现一次,用 `<cy-page-title title="…" />`,不裸写 `.h1` / 不重复画 `.cy-h1`。
- [ ] **`cy-nav-bar` / `cy-page-title` 不得被裹进只覆盖部分状态的 `wx:if`**。
      加载 / 空 / 错三态进去后,标题和返回出口会整段消失 —— 这是终审 §3 P1-4 记的那一类。
      条件分支要用,必须**互斥分支各自完整**(每个分支都有导航),并补进门禁白名单说明理由。

**例外只有两个**(其余一律按上面走,别自行加):

| 页面 | 为什么例外 | 替代承载 |
|---|---|---|
| `pages/club/create/index` | 建俱乐部向导,每步是一句问句,步骤条本身就是标题 | `.cc-q.cy-h1` 问句 |
| `subpackageP3/pages/badge-wall/index/index` | 整屏 WebGL 数字装置,普通 view 会被 canvas 原生层压住 | `cover-view` 字幕层 + `cover-view` 返回钮 |

**配套门禁(改完必跑,别只靠自查)**:

- `tests/unit/title-system-owner-contract.test.js` —— 存在性轴:从 `app.json` 全量盘点注册路由,
  逐路由集合断言谁有 nav / 谁有 page-title,两个例外写死在 `WIZARD_EXCEPTION` / `WEBGL_EXCEPTION`。
  新增页面会让集合漂移判红,**必须显式登记**,不能默默加页。
- `tests/unit/nav-title-unconditional-contract.test.js` —— 条件化轴:扫「祖先链带条件」+「标签自带条件」
  两轴,白名单只收互斥分支各自完整的合法形态。
  ⚠️ 存在性轴对「标签写了但被 `wx:if` 挡掉」恒绿,所以两条必须都在,少一条就有假绿缺口。

跑:`npm run test:unit`(或单跑上面两个文件)。
