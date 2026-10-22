'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const read = (rel) => fs.readFileSync(path.resolve(__dirname, '../..', rel), 'utf8')

test('设置页商家视角所有菜单图标同色(ink),不再 tint=""', () => {
  const wxml = read('pages/shezhi/shezhi.wxml')
  assert.doesNotMatch(wxml, /tint="\{\{isMerchantView \? '' :/)
  assert.match(wxml, /tint="\{\{isMerchantView \? 'ink' : 'mono'\}\}"/)
  assert.match(read('components/cy/cell/index.wxss'), /\.cell--tint-ink \.cell__icon\s*\{[^}]*brightness\(0\)/)
  assert.match(read('pages/shezhi/shezhi.wxss'), /\.theme-merchant \.sz-logout-icon\s*\{[^}]*brightness\(0\)/)
})

test('素材署名从设置页移到关于页底部,三项许可署名齐全', () => {
  const settings = read('pages/shezhi/shezhi.wxml')
  const about = read('pages/shezhi/about/index.wxml')
  assert.doesNotMatch(settings, /素材署名|sz-attribution/)
  assert.doesNotMatch(read('pages/shezhi/shezhi.js'), /copyAttributionLink/)
  assert.match(about, /game-icons\.net[\s\S]*Creative Commons BY 3\.0[\s\S]*Lord Berandas/)
  assert.match(about, /Luis Zuno[\s\S]*CC0 1\.0/)
  assert.match(about, /Lucide 图标 · ISC License[\s\S]*Cole Bemis · MIT License/)
  assert.match(read('pages/shezhi/about/index.js'), /copyAttributionLink/)
})
