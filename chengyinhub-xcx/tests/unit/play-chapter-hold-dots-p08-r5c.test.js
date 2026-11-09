// P-08 第五轮 C(业主 2026-11-03,shots/P-08-r4-after.jpg):等待放行屏中间的三点几乎看不见。
// 这一屏是深底(同章节翻转门 #1F1F21),集合屏三点用的 --cy-color-text-tertiary 在这里太暗;
// 只在这一屏把三点换成与标题「等待放行」同一个已有 token --cy-color-text-primary,别的不动。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const WXSS = fs.readFileSync(path.join(__dirname, '../../pages/play/index.wxss'), 'utf8')

test('等待放行屏的三点用 text-primary token,与标题同色', () => {
  assert.match(WXSS, /\.chhold__wait \.lg-wait__d\{ background:var\(--cy-color-text-primary\); \}/)
  assert.match(WXSS, /\.chhold__t\{[^}]*color:var\(--cy-color-text-primary\)/, '标题仍是 text-primary')
})

test('集合屏三点不受影响:仍是 tertiary、仍按原节奏明暗,减弱动态静止', () => {
  assert.match(WXSS, /\.lg-wait__d\{ width:14rpx; height:14rpx; border-radius:50%; background:var\(--cy-color-text-tertiary\); animation:fade \.9s ease-in-out infinite alternate; \}/)
  assert.match(WXSS, /\.chhold--still \.lg-wait__d\{ animation:none; opacity:\.6; \}/)
  const scoped = WXSS.match(/\.chhold__wait \.lg-wait__d\{([^}]*)\}/)[1]
  assert.doesNotMatch(scoped, /width|height|animation|opacity/, '只换颜色,大小与动画不动')
})
