// x-fix4(2026-11-11 验收第二轮 ACCEPT-ISSUES-R2-20261111)回归
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const noComments = (s) => s.replace(/<!--[\s\S]*?-->/g, '')

test('N-1 故事流「继续探索」避开 Home 条:收尾区与底垫都含安全区', () => {
  const wxss = read('pages/play/index.wxss')
  assert.match(wxss, /\.chfull__pad\{height:calc\(440rpx \+ env\(safe-area-inset-bottom\)\);\}/)
  assert.match(wxss, /\.chfull__end\{ padding:42rpx 46rpx env\(safe-area-inset-bottom\); \}/)
  assert.match(read('pages/play/play-topbar.wxss'), /\.chfull\.is-navmap \.chfull__pad\{ height:calc\(560rpx \+ env\(safe-area-inset-bottom\)\); \}/)
})

test('N-6 骰子内嵌块与整屏台面读外观色,不再是暗色外观里的白矩形', () => {
  const w = read('pages/play/components/playkit-diceroll/index.wxss')
  const dzi = w.match(/\.dzi \{[^}]+\}/)[0]
  assert.match(dzi, /background: var\(--pk-embed-bg, radial-gradient/)
  assert.match(dzi, /color: var\(--pk-embed-ink, #2a2540\)/)
  assert.match(dzi, /border-radius: var\(--pk-qt-radius, 0\)/)
  assert.match(w.match(/\.dz \{[^}]+\}/)[0], /background: var\(--pk-embed-bg, radial-gradient/)
  assert.match(w.match(/\.dzi__roll \{[^}]+\}/)[0], /background: var\(--pk-embed-ink, #2a2540\)/)
})

test('N-7 玩家页根在 look 下给出 --pk-embed-*;抽卡屏/详情读外观色,不再亮蓝/浅灰', () => {
  for (const f of ['pages/play/look-tokens.wxss', 'pages/publish/fabu/look-tokens.wxss']) {
    const t = read(f)
    assert.match(t, /\.play\[class\*="look--"\] \{ --pk-embed-bg:var\(--lk-bg\); --pk-embed-ink:var\(--lk-ink\);/)
    assert.match(t, /--pk-embed-accent:var\(--lk-a\);/)
  }
  const r = read('pages/play/components/playkit-random/index.wxss')
  assert.doesNotMatch(r, /background: #0b6adc;/)
  assert.doesNotMatch(r, /background: #(e6e3e4|f1f0f0|e0dede);/)
  assert.match(r, /background: var\(--lk-card, #e6e3e4\)/)
  assert.match(r, /background: var\(--lk-card, #f1f0f0\)/)
  assert.match(read('pages/play/style/play-surface.wxss'), /--ci-scrim:var\(--pk-embed-scrim,/)
  // 其余弹层与玩法台面一并读外观色
  assert.match(read('pages/play/components/playkit-bingo/index.wxss'), /\.bg__cell\.pale \{ background: var\(--lk-ink/)
  assert.match(read('pages/play/components/fragment/index.wxss'), /var\(--lk-pop,/)
  assert.doesNotMatch(read('pages/play/components/playkit-stopwatch/index.wxss'), /background: rgba\(255, 255, 255, \.92\);/)
})
