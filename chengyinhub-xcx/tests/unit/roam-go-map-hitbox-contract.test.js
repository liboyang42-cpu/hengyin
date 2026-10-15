const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const root = path.resolve(__dirname, '../..')
const wxml = fs.readFileSync(path.join(root, 'pages/roam/index.wxml'), 'utf8')
const wxss = fs.readFileSync(path.join(root, 'pages/roam/index.wxss'), 'utf8')
const home = wxml.slice(wxml.indexOf('class="intro"'), wxml.indexOf('<!-- ===================== B1'))
const shade = wxss.match(/\.intro-stage__shade\{([^}]+)\}/)[1]

test('首页地图不吞章节与附近活动点击，地图连续铺到卡片背后', () => {
  assert.match(home, /interactive="\{\{false\}\}"/)
  assert.match(wxss, /\.intro-map__el\{[^}]*pointer-events:none/)
  assert.match(wxss, /\.intro-stage\{[^}]*position:absolute; inset:0/)
  assert.match(home, /bindtap="goStart"/)
  assert.match(home, /bindtap="onEntryCardTap"/)
  assert.doesNotMatch(home, /class="go-wrap"|class="go-btn"/)
})

test('首页四周为线性渐变，中间保留长条地图', () => {
  assert.match(shade, /linear-gradient\(90deg/)
  assert.match(shade, /linear-gradient\(180deg/)
  assert.doesNotMatch(shade, /radial-gradient/)
  assert.match(shade, /transparent 26%,transparent 74%/)
  assert.match(shade, /pointer-events:none/)
})

test('首页没有纵向滚动，护照两个卡片区仍可横滑', () => {
  assert.doesNotMatch(home, /scroll-y/)
  assert.match(home, /class="pp-row" scroll-x/)
  assert.match(home, /class="pp-trips" scroll-x/)
  assert.match(wxss, /\.intro\{[^}]*overflow:hidden/s)
})
