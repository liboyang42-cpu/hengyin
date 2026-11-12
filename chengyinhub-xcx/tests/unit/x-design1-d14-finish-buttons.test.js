'use strict'
// x-design1 D14:完成面板 = 一行「保存图片 · 分享」+ 一颗主按钮「查看结算」+ 文字按钮「继续」
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const read = (p) => fs.readFileSync(path.resolve(__dirname, '../..', p), 'utf8')
const css = read('pages/play/index.wxss')
const wxml = read('pages/play/index.wxml')

test('D14:保存图片与分享在同一行(finsheet__card-acts flex 一行)', () => {
  assert.match(css, /\.finsheet__card-acts\{ display:flex;/)
  const acts = wxml.match(/<view class="finsheet__card-acts"[\s\S]*?<\/view>/)[0]
  assert.match(acts, /保存图片[\s\S]*分享/)
})

test('D14:有「查看结算」时「继续」是无底色文字按钮,查看结算是唯一实心主按钮', () => {
  const rule = css.match(/\.finsheet__settle \+ \.finsheet__cta\{[^}]*\}/)[0]
  assert.match(rule, /background:transparent/)
  assert.match(rule, /color:var\(--cy-color-play-story-muted\)/)
  assert.match(css, /\.finsheet__settle\{[^}]*background:var\(--cy-color-play-story-text\)/)
})
