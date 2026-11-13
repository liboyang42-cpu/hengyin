# 确认订单修复(checkout-1113e)

## 改了什么

1. **章数不再把结局算进去**
   - 把「结局不算章」的规则从 `pages/topic/utils/topic-motion.js` 搬到共用的 `utils/chapter-count.js`(`isEndingChapter`、`chapterCountText`)。详情页继续从 topic-motion 取同一份,没有第二份规则。
   - 确认订单头部小标签(`checkout-view.js` 的 `headPills`)改用 `chapterCountText`:3 章 + 3 个结局现在写「3 章 · 3 个结局」,不再是「6 章」。章数取 `totalChapterCount`,和详情页一致。
2. **同意行移进固定底栏**
   - 同意行(原组件 `cy-consent-check`、原文案、原点击区域)放在底栏里,紧贴合计/支付行上方;`onConsent`、未勾选的提示、重试都没动。
   - 底栏改成正常排版的一块,滚动区自然停在底栏上方,去掉了 `ck__pad` 那段额外留白。
   - 未勾选时点支付仍走页面原有的「请先勾选同意」拦截,没有改。

## 没改的

- 票夹卡片、订单详情页本来就没有「N 章」文案(订单详情只列各章名称),所以没有可改的地方。
- 游玩页卡包头的「N 章 · M 家商家」是游玩中的章节卡数,不在这次范围,未动。

## 测试

- 新增 `tests/unit/checkout-chapter-count-consent-1113e.test.js`:3 章 + 3 结局、无结局、单章、自由探索、共用同一份口径、同意行在底栏内且在支付行上方、`ck__pad` 已移除。
- 已跑:新增测试 + topic-story-meta-chapter-count、r2-store-checkout-view、topic-motion-shaping、l-pass-platform-pass、x-fix2-player-20261111、topic-detail-walk2、topic-detail-chapter-guide,共 77 条全过。
- `scripts/uiaudit/action-ledger.json` 已用 `build-action-ledger.js --write` 重新生成;`route-matrix.json` 用 `build-route-matrix.js --json` 重新生成后无变化。
- 没有改 `ci/architecture/*`、工作流、`ui-integrity-baseline.js`;没有跑 `ci/xcx-check.sh` 和会写 fixture 的自检。

- `tests/lint/ui-integrity.js` 直接运行通过(UI-GATE-0:PASS),基线文件未改。
- PR #827 的 CI 全部通过(未跑的后端门禁为按改动范围跳过)。

STATUS: DONE
