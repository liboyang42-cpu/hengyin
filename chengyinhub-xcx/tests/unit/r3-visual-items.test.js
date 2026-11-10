'use strict'
// r3-visual(2026-11-10 ROUND3):票夹翻页、收集册环装卡堆、邀请到账动效、加载与切段过渡
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const root = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')

function loadSignup() {
  const P = path.join(root, 'subpackageMember/signup/index.js')
  delete require.cache[P]
  global.getApp = () => ({ globalData: {}, getImgUrl: (x) => x })
  global.Page = () => {}
  global.wx = { getStorageSync: () => '' }
  return require(P)
}

test('r3 票夹:往左拖当前卡绕左边缘卷起,下一张从右侧跟手滑进;往右拖镜像', () => {
  const { pagePoses } = loadSignup()
  const left = pagePoses(3, 0, -150, 300)
  assert.match(left[0], /transform-origin: 0% 50%/)
  assert.match(left[0], /rotateY\(-35\.0deg\)/)
  assert.match(left[0], /--curl: 0\.400/)
  assert.match(left[1], /translate3d\(190\.0px/, '下一张 = 一卡宽 + 40 - 150')
  assert.match(left[1], /opacity: 1/)
  assert.match(left[2], /opacity: 0/, '其余卡不露出')
  const right = pagePoses(3, 0, 150, 300)
  assert.match(right[0], /transform-origin: 100% 50%/)
  assert.match(right[0], /rotateY\(35\.0deg\)/)
  assert.match(right[2], /translate3d\(-190\.0px/, '上一张从左侧滑进来')
  for (const pose of left.concat(right)) assert.doesNotMatch(pose, /\b(left|top|width|height):/, '只动 transform / opacity')
})

test('r3 票夹:松手阈值 —— 80px 或快甩才翻,单张不翻', () => {
  const { pageTurn } = loadSignup()
  assert.equal(pageTurn(3, -100, 0), 1)
  assert.equal(pageTurn(3, 100, 0), -1)
  assert.equal(pageTurn(3, -30, -0.8), 1)
  assert.equal(pageTurn(3, -30, -0.1), 0)
  assert.equal(pageTurn(1, -200, -2), 0)
})

test('r3 票夹顶栏:左「票夹」+「+」去探索 + 本人头像(没有就不出);无状态筛选', () => {
  const wxml = read('subpackageMember/signup/index.wxml')
  assert.match(wxml, /class="wl-nav__add" bindtap="goExplore"/)
  assert.match(wxml, /class="wl-nav__me" wx:if="\{\{meAvatar\}\}" bindtap="goMe"/)
  assert.match(wxml, /class="wc__curl" aria-hidden="true"/)
  assert.doesNotMatch(wxml, /wl-tab|即将开始|wl-filter/)
  const js = read('subpackageMember/signup/index.js')
  assert.match(js, /goExplore\(\) \{ wx\.switchTab\(\{ url: '\/pages\/index\/index' \}\); \}/)
  assert.match(js, /goMe\(\) \{ wx\.switchTab\(\{ url: '\/pages\/member\/index\/index' \}\); \}/)
  assert.match(read('subpackageMember/signup/index.wxss'), /\.signup--reduced \.wc \{ transition: none; \}/)
})
