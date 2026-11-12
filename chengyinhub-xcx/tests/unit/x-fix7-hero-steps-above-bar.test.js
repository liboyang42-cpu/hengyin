const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

// x-fix7 P-5：自由探索站点详情的滚动内容尾部垫「底栏高度 + 安全区」，到店三步不被固定的到店打卡条压住。
const css = fs.readFileSync(path.join(__dirname, '../../pages/play/index.wxss'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
const wxml = fs.readFileSync(path.join(__dirname, '../../pages/play/index.wxml'), 'utf8')

function body(sel) {
  const m = css.match(new RegExp('(?:^|[}\\s])' + sel.replace(/[.]/g, '\\.') + '\\s*\\{([^}]*)\\}'))
  assert.ok(m, sel)
  return m[1]
}

test('滚动内容尾垫 = 底栏高度（按钮高 + 124rpx）+ 安全区', () => {
  assert.match(body('.fx-hero__pad'), /height:\s*calc\(var\(--cy-btn-h\)\s*\+\s*124rpx\s*\+\s*env\(safe-area-inset-bottom\)\)/)
  // 底栏盒高 = 上内边距 96 + 按钮 + 下内边距 28 + 安全区，尾垫不小于它
  assert.match(body('.fx-hero__cta'), /padding:\s*96rpx var\(--cy-page-x\) calc\(28rpx \+ env\(safe-area-inset-bottom\)\)/)
})

test('尾垫在三步与出示入场码之后、仍在滚动容器里', () => {
  const scroll = wxml.slice(wxml.indexOf('<scroll-view class="fx-hero__body"'), wxml.indexOf('<view class="fx-hero__cta'))
  const steps = scroll.indexOf('class="fx-step3')
  const pad = scroll.indexOf('<view class="fx-hero__pad"></view>')
  assert.ok(steps > 0 && pad > steps)
  assert.ok(pad > scroll.indexOf('openEntryQr'))
})
