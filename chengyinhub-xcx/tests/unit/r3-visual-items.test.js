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

test('r3 票夹顶栏:左「票夹 ▾」+「+」去探索 + 本人头像(没有就不出);筛选只按 topicCompleted 分', () => {
  const wxml = read('subpackageMember/signup/index.wxml')
  assert.match(wxml, /class="wl-nav__add" bindtap="goExplore"/)
  assert.match(wxml, /class="wl-nav__me" wx:if="\{\{meAvatar\}\}" bindtap="goMe"/)
  assert.match(wxml, /class="wc__curl" aria-hidden="true"/)
  // 2026-11-10 l-after(业主批准 proto/game wl-wallet):「票夹 ▾」弹出 全部 / 即将开始 / 已通关;不是「可用 / 已用 / 已过期」
  assert.match(wxml, /class="wl-nav__flt" bindtap="toggleWalFilter"/)
  assert.match(wxml, /class="wc__spec" aria-hidden="true"/, '卷页镜面高光带')
  assert.doesNotMatch(wxml, /wl-tab|wl-filter|wl-status/)
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

function loadInvite() {
  const P = path.join(root, 'subpackageA/components/scene-member-invite-history/index.js')
  delete require.cache[P]
  global.getApp = () => ({ getPageSize: () => 20 })
  global.Component = () => {}
  global.wx = { getStorageSync: () => '', getSystemInfoSync: () => ({}) }
  return require(P)
}

test('r3 分享赚积分:余额与到账只取积分流水真实行;最新一条是邀请奖励才从 current 数到 after', () => {
  const { earnFacts } = loadInvite()
  const rows = [
    { id: 61, eventType: 5, eventId: 9008, currentPoints: 70, afterPoints: 180, changePoints: 110, createTime: '2026-11-10 17:53:25' },
    { id: 58, eventType: 3, eventId: 76, currentPoints: 50, afterPoints: 70, changePoints: 20 },
  ]
  const f = earnFacts(rows, [{ id: 9008, nickname: '阿岚', avatar: 'a.png' }])
  assert.deepEqual(f.balance, { from: 70, to: 180 })
  assert.equal(f.latest.points, '110')
  assert.equal(f.latest.nickname, '阿岚')
  const other = earnFacts([{ id: 70, eventType: 3, currentPoints: 180, afterPoints: 200, changePoints: 20 }].concat(rows), [])
  assert.deepEqual(other.balance, { from: 200, to: 200 }, '最新一条不是邀请奖励:余额静止')
  assert.equal(other.latest.nickname, '', '找不到被邀请人就不写名字')
  const none = earnFacts([{ id: 1, eventType: 3, changePoints: 20 }], [])
  assert.equal(none.balance, null, '读不到 afterPoints 不出余额,不写 0')
  assert.equal(none.latest, null, '没有到账的邀请奖励就没有到账卡')
  assert.deepEqual(earnFacts(null, null), { balance: null, latest: null })
})

test('r3 分享赚积分:数字滚动最后一定落在真实值,减少动态效果直接落值', async () => {
  const { countUp } = loadInvite()
  const seen = []
  const ctx = { setData(p) { seen.push(p.v) } }
  countUp(ctx, 'v', 70, 180, 140, false)
  await new Promise(r => setTimeout(r, 260))
  assert.equal(seen[0], '70')
  assert.equal(seen[seen.length - 1], '180')
  for (let i = 1; i < seen.length; i++) assert.ok(Number(seen[i]) >= Number(seen[i - 1]), '只往上数')
  const fast = []
  countUp({ setData(p) { fast.push(p.v) } }, 'v', 0, 110, 900, true)
  assert.deepEqual(fast, ['110'])
})

test('r3 分享赚积分界面:到账卡只在有到账时出;勾只在到位(s3)后弹出;只动 transform/opacity;奖励胶囊仍中性', () => {
  const wxml = read('subpackageA/components/scene-member-invite-history/index.wxml')
  assert.match(wxml, /class="ir-earn ir-earn--s\{\{earnStage\}\}" wx:if="\{\{latest && groups\.length\}\}"/)
  assert.match(wxml, /class="ir-bal" wx:if="\{\{balanceText\}\}"/)
  const wxss = read('subpackageA/components/scene-member-invite-history/index.wxss')
  assert.match(wxss, /\.ir-earn--s3 \.ir-earn-ok \{ transform: scale\(1\); \}/)
  assert.doesNotMatch(wxss, /@keyframes|animation:/)
  const earn = wxss.slice(wxss.indexOf('r3-visual 分享赚积分'))
  assert.doesNotMatch(earn, /transition:[^;]*\b(width|height|left|top|right|bottom|margin)\b/)
  assert.match(wxss, /\.ir-row:nth-child\(2\) \{ transition-delay/, '记录行逐行错开浮上来')
  assert.doesNotMatch(wxss.match(/\.ir-reward \{[^}]*\}/)[0], /success/)
})
