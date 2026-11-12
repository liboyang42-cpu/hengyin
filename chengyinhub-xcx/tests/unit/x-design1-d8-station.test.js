'use strict'
// x-design1 D8:站点详情无图时不再是空地图大占位;「到店三步」标题与步骤同生同灭
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/index.wxml'), 'utf8')

test('D8:卡面无图用 look 渐变 + 小路线插画,不再是整卡大图钉占位', () => {
  assert.match(wxml, /<view class="fx-hero__ph cy-cover-fb" wx:else aria-hidden="true"><image class="cy-cover-fb__art" src="\/images\/cover-route\.svg"/)
  assert.doesNotMatch(wxml, /fx-hero__ph"[^>]*><cy-icon name="pin" size="52"/)
})

test('D8:到店三步标题与步骤在同一个 block 里,没有步骤就没有标题', () => {
  const i = wxml.indexOf('>到店三步</view>')
  const open = wxml.lastIndexOf('<block wx:if="{{hero.node.isMerchant}}">', i)
  const close = wxml.indexOf('</block>', i)
  assert.ok(open > 0 && close > i)
  const inner = wxml.slice(open, close)
  assert.match(inner, /class="fx-step3/)
  assert.equal((inner.match(/class="fx-step /g) || []).length, 3)
})
