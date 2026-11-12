'use strict'
// x-design1 D2:通关卡标志与文案不重叠、文案区铺满内容宽、无字影(在 x-fix4~7 的规则上追加覆盖)
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const css = fs.readFileSync(path.resolve(__dirname, '../../pages/play/components/chapter-flip/index.wxss'), 'utf8')
const rules = (sel) => css.split('\n').filter((l) => l.startsWith(sel + ' {'))

test('D2:文案区在标志下方留 64rpx 以上间隙,左右取页面边距铺满', () => {
  const last = rules('.cf--finale .cf__copy').pop()
  assert.match(last, /top: calc\(33\.3vh \+ 45vw \+ 64rpx\)/)
  assert.match(last, /left: var\(--cy-page-x\); right: var\(--cy-page-x\); width: auto;/)
  assert.match(last, /padding: 0;/)
})

test('D2:通关卡里解码乱码字不显示,标题后没有第二层字影', () => {
  assert.match(rules('.cf--finale .cf__c.is-n').pop(), /opacity: 0/)
})

test('D2:x-fix7 的折行与 x-fix5 的底部避让仍在', () => {
  assert.match(css, /\.cf--finale \.cf__c \{ white-space: pre-wrap; \}/)
  assert.match(css, /bottom: calc\(190rpx \+ env\(safe-area-inset-bottom\)\)/)
})
