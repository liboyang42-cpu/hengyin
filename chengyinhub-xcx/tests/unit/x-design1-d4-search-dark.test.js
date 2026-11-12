'use strict'
// x-design1 D4:玩页地址条深色,通关后不再显示
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const read = (p) => fs.readFileSync(path.resolve(__dirname, '../..', p), 'utf8')

test('D4:玩页地址条传 dark,漫游页不传(仍是白条)', () => {
  const tag = read('pages/play/index.wxml').match(/<cy-map-address-search[\s\S]*?\/>/)[0]
  assert.match(tag, /dark="\{\{true\}\}"/)
  assert.doesNotMatch(read('pages/roam/index.wxml').match(/<cy-map-address-search[^>]*>/)[0], /dark=/)
})

test('D4:通关(allDone / topicCompleted)后整条地址条不挂', () => {
  const tag = read('pages/play/index.wxml').match(/<cy-map-address-search[\s\S]*?\/>/)[0]
  assert.match(tag, /!allDone && !topicCompleted/)
})

test('D4:深色收起态用玩页 chrome token,不再是 #FFF 底', () => {
  const css = read('components/cy/map-address-search/index.wxss')
  const rule = css.match(/\.searchbar--dark\{[^}]*\}/)[0]
  assert.match(rule, /background:var\(--cy-color-play-surface-solid\)/)
  assert.doesNotMatch(rule, /#fff/i)
  assert.match(read('components/cy/map-address-search/index.wxml'), /searchbar--dark/)
  assert.match(read('components/cy/map-address-search/index.js'), /dark: \{ type: Boolean, value: false \}/)
})
