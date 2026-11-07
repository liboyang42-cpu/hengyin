'use strict'
// 第十轮 f3 j5 L8:俱乐部页的 tab、错误与欢迎半屏用约定用语「帖文」,不再写「帖子」「发帖和大家聊」。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const read = (f) => fs.readFileSync(path.resolve(__dirname, '../..', f), 'utf8')
const strip = (s) => s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')

test('俱乐部详情用户可见字符串里没有「帖子」', () => {
  const js = strip(read('pages/club/detail/index.js'))
  const wxml = strip(read('pages/club/detail/index.wxml'))
  const strings = (js.match(/'[^'\n]*'/g) || []).join('\n')
  assert.doesNotMatch(strings, /帖子/)
  assert.doesNotMatch(wxml, /帖子/)
  assert.match(js, /\{ key: 'posts', label: '帖文' \}/)
  assert.match(js, /title: '发帖文和大家聊'/)
})
