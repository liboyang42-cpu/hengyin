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

function loadIntro(active) {
  let cfg
  global.Component = (c) => { cfg = c }
  const P = path.join(root, 'subpackageP3/components/album-intro/index.js')
  delete require.cache[P]
  delete require.cache[path.join(root, 'utils/motion-preference.js')]
  global.wx = { getStorageSync: () => '', getSystemInfoSync: () => ({}), redirectTo: (o) => { global.__redirect = o.url; o.complete && o.complete() } }
  global.getApp = () => ({ globalData: { statusBarHeight: 47, navBarHeight: 44 } })
  global.getCurrentPages = () => [{ options: { from: 'profile' } }]
  require(P)
  const self = { data: Object.assign({}, cfg.data, { active }), setData(p) { Object.assign(this.data, p) } }
  Object.assign(self, cfg.methods)
  cfg.lifetimes.attached.call(self)
  return self
}

test('r3 收集册环装卡堆:从所在那段开始,箭头绕环翻到下一张,点别的段就地换页,点本段收起', async () => {
  const c = loadIntro('stamp')
  assert.equal(c.data.idx, 1, '从「邮票」那张开始')
  assert.equal(c.data.navTop, 91)
  c.toRing()
  assert.equal(c.data.ring, true)
  c.next()
  assert.equal(c.data.flip, 1, '正面那张在翻')
  c.next()
  assert.equal(c.data.flip, 1, '翻页中不叠第二次')
  await new Promise(r => setTimeout(r, 600))
  assert.equal(c.data.idx, 2)
  assert.equal(c.data.flip, -1)
  c.enter({ currentTarget: { dataset: { key: 'medal' } } })
  assert.equal(global.__redirect, '/subpackageP3/pages/badge-wall/index/index', '不带 from,不重播')
  c.enter({ currentTarget: { dataset: { key: 'stamp' } } })
  assert.equal(c.data.out, true, '点本段 = 收起过场')
  c._clear()
})

test('r3 收集册卡堆样式:只动 transform/opacity,关闭在右上角,减少动态效果整段不出', () => {
  const wxss = read('subpackageP3/components/album-intro/index.wxss')
  assert.doesNotMatch(wxss, /transition:[^;]*\b(width|height|left|top|right|bottom)\b/)
  assert.doesNotMatch(wxss, /@keyframes|animation:/, '不新增 keyframes')
  assert.match(wxss, /\.ar__x \{ position: absolute; right: /)
  assert.match(wxss, /@media \(prefers-reduced-motion: reduce\) \{ \.ai \{ display: none; \} \}/)
  const wxml = read('subpackageP3/components/album-intro/index.wxml')
  assert.match(wxml, /class="ar__next" catchtap="next"/)
  assert.match(wxml, /catchtap="enter" data-key="\{\{item\.key\}\}"/)
})

test('r3 收集册切段:新的一段从切换方向滑进来(via=l/r),减少动态效果不滑', () => {
  const P = path.join(root, 'subpackageP3/components/album-head/index.js')
  const run = (via, reduced) => {
    let cfg
    global.Component = (c) => { cfg = c }
    delete require.cache[P]
    delete require.cache[path.join(root, 'utils/motion-preference.js')]
    global.wx = { getStorageSync: (k) => (reduced && /motion/i.test(k) ? true : ''), getSystemInfoSync: () => ({}), redirectTo: (o) => { global.__redirect = o.url; o.complete && o.complete() } }
    const calls = []
    global.getCurrentPages = () => [{ options: { via }, animate: (sel, frames, ms) => calls.push({ sel, frames, ms }) }]
    require(P)
    const self = { data: Object.assign({}, cfg.data, { active: 'stamp' }) }
    cfg.lifetimes.attached.call(self)
    cfg.methods.go.call(self, { currentTarget: { dataset: { key: 'object' } } })
    return calls
  }
  const r = run('r', false)
  assert.equal(r[0].sel, '.al-root')
  assert.equal(r[0].frames[0].translateX, 48)
  assert.deepEqual(Object.keys(r[0].frames[0]).sort(), ['ease', 'opacity', 'translateX'])
  assert.equal(global.__redirect, '/subpackageP3/pages/object-cards/index/index?via=r')
  assert.equal(run('l', false)[0].frames[0].translateX, -48)
  assert.equal(run('r', true).length, 0)
  assert.equal(run('', false).length, 0)
})
