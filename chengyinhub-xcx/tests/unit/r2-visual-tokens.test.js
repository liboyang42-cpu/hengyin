'use strict'
// r2-visual(2026-11-10)共享 token:玩家弹层面、灰字对比度、底部按钮圆角。
// 业主原话:「弹窗不能是黑色的 所有黑色弹窗都需要改 不然看不见弹窗啊」
//          「这种灰色的弹窗看起来还是看不太清」「我发现玩家的灰色都不是很清楚？」
//          「底部按钮的圆角全站可以统一一下」
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')
const tokens = read('style/tokens.wxss')

function pageBlock() {
  const i = tokens.indexOf('page {') >= 0 ? tokens.indexOf('page {') : tokens.indexOf('page{')
  return tokens.slice(i, tokens.indexOf('\n}', i))
}
function val(name, src = pageBlock()) {
  const m = src.match(new RegExp(name.replace(/[-]/g, '\\-') + ':\\s*([^;]+);'))
  assert.ok(m, '缺 token ' + name)
  return m[1].trim()
}
function lum(hex) {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((x) => (x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4))
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
}
function contrast(a, b) {
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p)
  return (x + 0.05) / (y + 0.05)
}
function overWhite(alpha, bg) {
  const b = [1, 3, 5].map((i) => parseInt(bg.slice(i, i + 2), 16))
  return '#' + b.map((v) => Math.round(255 * alpha + v * (1 - alpha)).toString(16).padStart(2, '0')).join('')
}

test('玩家弹层面:比页面明显抬起(#2C2C2E 实底 + 顶边高光 + 向上投影)', () => {
  assert.equal(val('--cy-comp-sheet-player-glass'), 'rgba(44, 44, 46, .98)')
  assert.match(val('--cy-comp-sheet-player-lift'), /^inset 0 1px 0 rgba\(255, 255, 255, \.18\), 0 -12px 40px rgba\(0, 0, 0, \.55\)$/)
  assert.equal(val('--cy-comp-sheet-player-inset-shadow'), 'var(--cy-comp-sheet-player-lift)')
  // 面板对黑页的亮度差:≥ 1.4:1 才算「看得出是一层面」(旧 #1C1C1E 只有 1.2)
  assert.ok(contrast('#2C2C2E', '#000000') >= 1.4)
  // 面内卡比面板高一档,不能比面板还暗(旧 #242424 比新面板暗)
  for (const k of ['--cy-comp-sheet-player-card', '--cy-comp-sheet-player-elevated', '--cy-comp-sheet-player-raised', '--cy-comp-sheet-player-surface']) {
    assert.equal(val(k), '#3A3A3C', k)
  }
  // cy-sheet(读 role 档)与 scene-sheet / 原型 .psheet / 漫游入口弹层都挂上抬升
  assert.match(read('components/cy/sheet/index.wxss'), /box-shadow: var\(--cy-comp-sheet-role-lift\)/)
  assert.match(tokens, /--cy-comp-sheet-role-lift: var\(--cy-comp-sheet-player-lift\)/)
  // 2026-11-12 细节打磨 G1(业主):商家白弹层也分层 —— 顶边高光 + 向上两层柔投影(浅色档),不再是 none
  assert.match(tokens, /--cy-comp-sheet-role-lift: var\(--cy-shadow-hi-light\), 0 -1px 2px rgba\(0, 0, 0, .06\), 0 -12px 32px rgba\(0, 0, 0, \.12\);/, '商家白弹层用浅色档两层投影')
  assert.match(read('components/cy/scene-sheet/index.wxss'), /box-shadow: var\(--cy-sheet-inset-shadow\)/)
  assert.match(read('style/proto-sheet.wxss'), /box-shadow:var\(--cy-comp-sheet-player-lift\)/)
  assert.match(read('pages/roam/index.wxss'), /\.entry-sheet\{[^}]*box-shadow:var\(--cy-comp-sheet-player-lift\)/)
})

test('灰字对比度:页面与弹层上的二/三级字都 ≥ 4.5:1', () => {
  const sec = val('--cy-color-text-secondary')
  const ter = val('--cy-color-text-tertiary')
  assert.equal(sec, '#C7C7C7')
  assert.equal(ter, '#A8A8A8')
  for (const bg of ['#000000', '#0A0A0B', '#1C1C1E', '#2C2C2E', '#3A3A3C']) {
    assert.ok(contrast(sec, bg) >= 4.5, 'secondary on ' + bg)
    assert.ok(contrast(ter, bg) >= 4.5, 'tertiary on ' + bg + ' = ' + contrast(ter, bg).toFixed(2))
  }
  // 弹层内 rgba 白字:二级 .80、弱化 .62,压在面板与面内卡上都 ≥ 4.5
  const a2 = Number(val('--cy-comp-sheet-player-text-secondary').match(/,\s*(\.\d+)\)/)[1])
  const am = Number(val('--cy-comp-sheet-player-muted').match(/,\s*(\.\d+)\)/)[1])
  for (const bg of ['#2C2C2E', '#3A3A3C']) {
    assert.ok(contrast(overWhite(a2, bg), bg) >= 4.5, 'sheet secondary on ' + bg)
    assert.ok(contrast(overWhite(am, bg), bg) >= 4.5, 'sheet muted on ' + bg)
  }
  // 深色逃生口块必须与 page{} 同步(嵌套暗子树不许回到旧灰)
  assert.doesNotMatch(tokens, /--cy-color-text-tertiary:\s*#919191/)
  assert.doesNotMatch(tokens, /--cy-color-text-secondary:\s*#B7B7B7/)
})

test('底部按钮圆角:一个 token,底部动作条 / 弹层底部 / 原型半屏按钮都读它', () => {
  assert.equal(val('--cy-comp-bottom-btn-radius'), 'var(--cy-radius-pill)')
  assert.match(read('components/cy/footer-bar/index.wxss'), /--cy-comp-btn-radius: var\(--cy-comp-bottom-btn-radius\)/)
  assert.match(read('components/cy/sheet/index.wxss'), /\.sh__footer \{ --cy-comp-btn-radius: var\(--cy-comp-bottom-btn-radius\); \}/)
  assert.match(read('components/cy/scene-sheet/index.wxss'), /--cy-comp-btn-radius: var\(--cy-comp-bottom-btn-radius\)/)
  assert.match(read('style/proto-sheet.wxss'), /\.btn2\{[^}]*border-radius:var\(--cy-comp-bottom-btn-radius\)/)
  // 负控:写死圆角会判红
  const bad = read('style/proto-sheet.wxss').replace('border-radius:var(--cy-comp-bottom-btn-radius)', 'border-radius:999rpx')
  assert.doesNotMatch(bad, /\.btn2\{[^}]*border-radius:var\(--cy-comp-bottom-btn-radius\)/)
})
