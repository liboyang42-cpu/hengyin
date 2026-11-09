'use strict'

// 打磨第 3 轮 §4-6:首页浮在封面上的「正在更新…」用 .cy-refresh-float--pill 底,不再是封面上的一行淡字。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')

test('首页三处「正在更新…」都挂胶囊底', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/index/index.wxml'), 'utf8')
  const syncs = [...wxml.matchAll(/<text class="([^"]*v3-sec-sync[^"]*)"/g)].map((m) => m[1])
  assert.equal(syncs.length, 3)
  for (const cls of syncs) assert.match(cls, /\bcy-refresh-float cy-refresh-float--pill\b/)
})

test('页内 .v3-sec-sync 不再写 padding 盖掉胶囊内距', () => {
  const wxss = fs.readFileSync(path.join(ROOT, 'pages/index/index.wxss'), 'utf8')
  const rule = wxss.match(/\.v3-sec-sync \{[^}]*\}/)[0]
  assert.doesNotMatch(rule, /padding/)
  const shared = fs.readFileSync(path.join(ROOT, 'style/fade-in.wxss'), 'utf8')
  assert.match(shared, /\.cy-refresh-float--pill \{[^}]*border-radius: var\(--cy-radius-pill\)/)
})
