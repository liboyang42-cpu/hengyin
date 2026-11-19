'use strict'
// UI sweep 2026-11-19 · U5–U10:收益空态安全区 / 城瘾玩法卡 / 营销消息空态 / 3D 徽章首帧正面 /
// 排行榜「我」不重复钉行 / 优惠券两页玩家深色与关闭态。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const { createRequire } = require('node:module')

const ROOT = path.resolve(__dirname, '../..')
const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8')
const rule = (css, selector) => {
  const at = css.indexOf(selector + ' {')
  assert.ok(at >= 0, 'missing rule ' + selector)
  return css.slice(at, css.indexOf('}', at))
}

// ---------- U5 ----------
test('U5 收益空态:整页一屏高纵向 flex,空态底部留安全区,余额区收掉底部留白', () => {
  const page = read('subpackageA/pages/assetcenter/earnings/index.wxss')
  assert.match(rule(page, '.er-page'), /display:\s*flex;\s*flex-direction:\s*column/)
  assert.match(rule(page, '.er-shell'), /flex:\s*1/)
  assert.match(read('subpackageA/pages/assetcenter/earnings/index.wxml'), /<cy-scene-asset-earnings id="assetEarnings" class="er-earnings"/)
  const css = read('subpackageA/components/scene-asset-earnings/index.wxss')
  assert.match(rule(css, '.ae--empty'), /padding-bottom:\s*calc\([^;]*env\(safe-area-inset-bottom\)\)/)
  assert.match(rule(css, '.ae--empty .ae-hero'), /padding-bottom:\s*0/)
  const wxml = read('subpackageA/components/scene-asset-earnings/index.wxml')
  assert.match(wxml, /class="ae \{\{state === 'ready' && balance === '0\.00' && theme !== 'merchant' \? 'ae--empty' : ''\}\}"/)
  const empty = wxml.match(/<cy-empty[^>]*title="还没有收益"[^>]*>/)[0]
  assert.match(empty, /fill="\{\{true\}\}"/)
  assert.doesNotMatch(empty, /size="lg"/, '余额区下方的空态用默认档插画,lg 档会把按钮顶进 Home 指示条')
})

// ---------- U6 ----------
test('U6 城瘾玩法:模式卡「图标+名称」与「去看看」同排,不再上下分两行', () => {
  const css = read('subpackageA/pages/infomation/infomation.wxss')
  assert.match(rule(css, '.gl-mode'), /display:\s*flex;\s*align-items:\s*center/)
  assert.match(rule(css, '.gl-mode-hd'), /flex:\s*1/)
  assert.doesNotMatch(rule(css, '.gl-mode-act'), /margin-top/)
  const wxml = read('subpackageA/pages/infomation/infomation.wxml')
  assert.doesNotMatch(wxml, /gl-mode-desc|gl-mode-tag/, '不加说明句')
})

// ---------- U7 ----------
test('U7 营销消息:空态走标准两层插画(kind=empty fill lg),标题不变', () => {
  const wxml = read('pages/shezhi/components/marketing-consent/index.wxml')
  assert.match(wxml, /<cy-empty class="mc-fill" wx:elif="\{\{state === 'empty'\}\}" fill="\{\{true\}\}" size="lg" kind="empty" title="还没有商家消息" \/>/)
  assert.doesNotMatch(wxml, /class="mc-empty"/)
  assert.equal(JSON.parse(read('pages/shezhi/components/marketing-consent/index.json')).usingComponents['cy-empty'], '/components/cy/empty/index')
  assert.match(rule(read('pages/shezhi/marketing/index.wxss'), '.mk-page'), /display:\s*flex;\s*flex-direction:\s*column/)
  assert.match(read('pages/shezhi/marketing/index.wxml'), /<cy-marketing-consent class="mk-consent"/)
})

// ---------- U8 ----------
function loadXrBadge() {
  global.Component = () => {}
  const file = path.join(ROOT, 'subpackageP3/components/xr-badge/index.js')
  delete require.cache[require.resolve(file)]
  try { return require(file) } finally { delete global.Component }
}

test('U8 3D 徽章:起始角 0 = 正面朝相机;贴图到之前不自转,自转从正面起步', () => {
  const { INITIAL_ROT, advanceRot } = loadXrBadge()
  assert.equal(INITIAL_ROT, 0)
  assert.equal(Math.cos(INITIAL_ROT), 1, '正面法线 +z 正对相机')
  // 贴图加载期间(画布不可见)跑多少帧角度都不动 —— 首个可见帧必是正面
  let rot = INITIAL_ROT
  for (let i = 0; i < 300; i++) rot = advanceRot(rot, 16, { faceReady: false })
  assert.equal(rot, 0)
  rot = advanceRot(rot, 16, { faceReady: true })
  assert.ok(rot > 0 && rot < 0.05, '贴图到后从正面开始慢转')
  assert.equal(advanceRot(0.3, 16, { faceReady: true, dragging: true }), 0.3)
  assert.equal(advanceRot(0.3, 16, { faceReady: true, reduced: true }), 0.3)
  const src = read('subpackageP3/components/xr-badge/index.js')
  assert.doesNotMatch(src, /_rot = -0\.5/)
  assert.match(src, /this\._rot = INITIAL_ROT;\s*this\._faceReady = false;/)
  assert.match(src, /that\._faceReady = true;[^\n]*\n\s*that\.triggerEvent\('loaded'\)/, 'ready(页面换上画布)与自转起点是同一刻')
})

// ---------- U9 ----------
const vmLb = require('../../subpackageP3/pages/growthcenter/leaderboard/view-model.js')

test('U9 view-model:我在榜内(按 memberId)就不钉底部行;并列同 rank 不误判', () => {
  const list = [{ memberId: 1, rank: 1 }, { memberId: 2, rank: 2 }, { memberId: 9, rank: 5 }]
  assert.equal(vmLb.shouldPinMe(list, { memberId: 9, rank: 5 }), false)
  assert.equal(vmLb.shouldPinMe(list, { memberId: '9', rank: 5 }), false, '数字/字符串 id 同一人')
  assert.equal(vmLb.shouldPinMe(list, { memberId: 77, rank: 2 }), true, '同 rank 不同人照样钉')
  assert.equal(vmLb.shouldPinMe(list, { memberId: 77, rank: 88 }), true)
  assert.equal(vmLb.shouldPinMe(list, { memberId: 77, rank: null }), false, '没有名次不钉')
  assert.equal(vmLb.shouldPinMe(list, null), false)
  assert.equal(vmLb.shouldPinMe([{ rank: 1 }], { rank: 1 }), true, '缺 memberId 时不猜,保留钉行')
  assert.equal(vmLb.isMeListed([null, { memberId: 3 }], { memberId: 3 }), true)
})

function loadLeaderboard() {
  const file = path.join(ROOT, 'subpackageP3/pages/growthcenter/leaderboard/index.js')
  let config
  global.getApp = () => ({ getUserID: () => 9, sendRequest() {} })
  global.wx = { stopPullDownRefresh() {} }
  global.Page = (c) => { config = c }
  delete require.cache[require.resolve(file)]
  require(file)
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) })
  page.setData = (patch) => Object.assign(page.data, patch)
  return page
}

test('U9 页面:我在第 4 名之后 → 原位高亮,底部不重复;不在榜 → 钉底部', () => {
  const rows = (n) => Array.from({ length: n }, (_, i) => ({ memberId: i + 1, nickname: 'P' + (i + 1), score: 100 - i, rank: i + 1 }))
  let page = loadLeaderboard()
  page.buildBoard({ list: rows(6), me: { memberId: 5, nickname: 'P5', score: 96, rank: 5 } }, 'point')
  assert.equal(page.data.me, null)
  assert.equal(page.data.rest.find((r) => r.memberId === 5).isMe, true)
  assert.equal(page.data.rest.filter((r) => r.isMe).length, 1)

  page = loadLeaderboard()
  page.buildBoard({ list: rows(6), me: { memberId: 1, nickname: 'P1', score: 100, rank: 1 } }, 'point')
  assert.equal(page.data.me, null, '我在领奖台也不重复钉')

  page = loadLeaderboard()
  page.buildBoard({ list: rows(6), me: { memberId: 42, nickname: '我', score: 3, rank: 120 } }, 'point')
  assert.equal(page.data.me.rank, 120)
  assert.ok(page.data.rest.every((r) => !r.isMe))

  const wxml = read('subpackageP3/pages/growthcenter/leaderboard/index.wxml')
  assert.match(wxml, /class="gc-row \{\{item\.isMe \? 'gc-row-me' : ''\}\}" wx:for="\{\{rest\}\}"/)
})

// ---------- U10 ----------
function mountPage(file, { role = 'player', getCurrentPages = () => [{}], sendRequest = () => {} } = {}) {
  const navigations = []
  const themes = []
  const app = {
    sendRequest, getUserID: () => 7,
    getUserRole: () => role, getUserType: () => (role === 'merchant' ? 2 : 1),
  }
  const wx = {
    navigateBack: () => navigations.push('back'), redirectTo: (o) => navigations.push(o.url),
    navigateTo: (o) => navigations.push(o.url), showToast() {}, getStorageSync() { return '' },
  }
  let definition
  const req = createRequire(path.join(ROOT, file))
  vm.runInNewContext(read(file), {
    require: (id) => (/merchant-theme/.test(id)
      ? { merchantPageShow: () => themes.push('light'), merchantPageRestore: () => themes.push('dark') }
      : req(id)),
    getApp: () => app, wx, getCurrentPages, Page: (d) => { definition = d }, console,
  }, { filename: file })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done() },
  })
  return { page, navigations, themes }
}

test('U10 优惠券列表:玩家深色(theme-dark),商家口径才浅色;空态是标准插画', () => {
  const wxml = read('subpackageMember/coupon/coupon.wxml')
  assert.match(wxml, /<view class="coupon-page \{\{isMerchant \? 'theme-merchant' : 'theme-dark'\}\}">/)
  assert.match(wxml, /<cy-empty wx:if="\{\{loadState === 'ready' && list\.length === 0\}\}" kind="empty" fill size="lg"[^>]*title="还没有优惠券" \/>/)
  const wxss = read('subpackageMember/coupon/coupon.wxss')
  assert.match(wxss, /@import '\.\.\/\.\.\/style\/merchant-light-scope\.wxss';/)
  assert.doesNotMatch(wxss, /merchant-light\.wxss/, '整页浅色 page{} 会把玩家端也刷白')
  const json = JSON.parse(read('subpackageMember/coupon/coupon.json'))
  assert.equal(json.backgroundColor, undefined)

  const player = mountPage('subpackageMember/coupon/coupon.js')
  player.page.onLoad({})
  player.page.syncViewTheme()
  assert.equal(player.page.data.isMerchant, false)
  assert.deepEqual(player.themes, ['dark'])

  const merchant = mountPage('subpackageMember/coupon/coupon.js')
  merchant.page.onLoad({ scope: 'MERCHANT' })
  merchant.page.syncViewTheme()
  assert.equal(merchant.page.data.isMerchant, true)
  assert.deepEqual(merchant.themes, ['light'])
})

test('U10 优惠券列表:非商家身份后端 403 → 原地无权限态「当前身份不能查看优惠券」,不再落空白加载失败态', () => {
  const wxml = read('subpackageMember/coupon/coupon.wxml')
  assert.match(wxml, /<cy-empty wx:elif="\{\{loadState === 'denied'\}\}" kind="no-permission" fill size="lg" title="当前身份不能查看优惠券" cta="\{\{isMerchant \? '返回' : '申请入驻'\}\}" bind:cta="onDenyCta"(?: class="cy-fade-in")? \/>/)
  const requests = []
  const { page, navigations } = mountPage('subpackageMember/coupon/coupon.js', { sendRequest: (o) => requests.push(o) })
  page.onLoad({})
  page.getList()
  const list = requests.find((o) => o.url === '/api/coupon/mypublishlist')
  assert.ok(list, 'coupon list request sent')
  list.success({ code: 403, msg: '商家身份无效或权限不足' })
  assert.equal(page.data.loadState, 'denied')
  // followDenyCta 是宿主上下文里的共享模块,读全局 wx
  const prevWx = global.wx
  global.wx = { navigateTo: (o) => { navigations.push(o.url); if (o.complete) o.complete() } }
  try { page.onDenyCta() } finally { global.wx = prevWx }
  assert.deepEqual(navigations, ['/pages/merchant/apply/index'])
})

// PG-040(2026-11-17 业主批准):建券页 couponInfo 整页下线,U10 关闭态用例随页移除。
