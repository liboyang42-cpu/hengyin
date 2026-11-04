'use strict'
// 第十轮 f3 p3 F9 / j2 F-21:同一个人在俱乐部概览上被叫「主理人」又被叫「群主」。统一用「主理人」。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (f) => fs.readFileSync(path.join(ROOT, f), 'utf8')

test('俱乐部详情成员行与成员角色只用「主理人」,用户可见文本不出现「群主」', () => {
  const wxml = read('pages/club/detail/index.wxml')
  assert.doesNotMatch(wxml.replace(/<!--[\s\S]*?-->/g, ''), /群主/)
  assert.equal((wxml.match(/class="member-owner">主理人<\/text>/g) || []).length, 2)
  const js = read('pages/club/detail/index.js')
  assert.match(js, /role: m\.isOwner \? '主理人' :/)
})
