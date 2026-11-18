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

test('D8 + F08:到店三步标题与步骤一起删掉,不留孤立标题', () => {
  assert.equal(wxml.indexOf('>到店三步</view>'), -1)
  assert.equal((wxml.match(/class="fx-step /g) || []).length, 0)
  assert.doesNotMatch(wxml, /class="fx-step3/)
})
