'use strict'
// N4(2026-11-21 新玩家走查):玩家面不说「坐标」(说「站」),完成页按钮不说「结算」(玩家读成付款)。商家 / 俱乐部的钱页「结算」不动。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const read = (p) => fs.readFileSync(path.join(__dirname, '..', '..', p), 'utf8')

test('章节入口 / 章末统计格写「站」不写「坐标」', () => {
  const src = read('pages/play/utils/chapter-flip-data.js')
  assert.doesNotMatch(src, /k: '坐标'/)
  assert.match(src, /k: '站'/)
})

test('完成页按钮是「查看成绩」,统计格是「点亮站点」', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /bindtap="openSettlement" aria-role="button" aria-label="查看成绩"[^>]*>查看成绩<\/view>/)
  assert.doesNotMatch(wxml, />查看结算</)
  assert.match(wxml, /<text class="fst__l">点亮站点<\/text>/)
  assert.doesNotMatch(read('pages/play/index.js'), /'点亮坐标'|还没标坐标/)
})

test('玩法内判定不写「结算」;商家钱页的「结算」保留', () => {
  assert.doesNotMatch(read('pages/play/components/playkit-journey-check/index.wxml'), />结算(中)?<\/view>/)
  assert.doesNotMatch(read('pages/play/components/playkit-accusation/index.wxml'), /'结算确认中'/)
  assert.match(read('pages/topic/merchantinfo/merchantinfo.wxml'), /待结算/)
})
