'use strict'
// 1119 验证单 B 道（MC-* / OR-*）源码合同：逐条钉住业主 2026-11-17 批准的小程序改动。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')

const ROOT = path.join(__dirname, '..', '..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

test('MC-004 / MC-043:俱乐部结算直连账本只给主理人,余额未知不再单摆「—」', () => {
  const wxml = read('pages/club/settlement/index.wxml')
  assert.match(wxml, /<cy-club-direct-ledger class="section" wx:if="\{\{summary && summary\.isOwner && clubId\}\}"/)
  assert.doesNotMatch(wxml, /: '—'\}\}<\/text>/)
})

test('MC-005:俱乐部报名页场次净额改名「实收减退款」', () => {
  const wxml = read('pages/club/enroll/index.wxml')
  assert.match(wxml, /实收减退款 \{\{m\.netText\}\}/)
  assert.doesNotMatch(wxml, /净额 \{\{m\.netText\}\}/)
})

test('MC-015:核销行金额未成立按 MC-011=B 显示 ¥0.00,不再回落「待定」', () => {
  const js = read('pages/merchant/ledger/index.js')
  assert.match(js, /NO_CASH_SETTLEMENT' \? null : '¥0\.00'/)
  assert.doesNotMatch(js, /: '待定'\)/)
})

test('MC-019:台账三张列表 pageNum 递增触底加载', () => {
  const js = read('pages/merchant/ledger/index.js')
  assert.match(js, /onReachBottom\(\) \{/)
  assert.match(js, /const pageNum = this\._pageState\[key\]\.pageNum \+ 1;/)
  assert.match(js, /this\._setPageState\('redemptions', 1, res\.data\)/)
  assert.match(js, /this\._setPageState\('entries', 1, res\.data\)/)
  assert.match(js, /this\._setPageState\('batches', 1, res\.data\)/)
  assert.match(read('pages/merchant/ledger/index.wxml'), /wx:if="\{\{loadingMore\}\}"[^>]*>正在加载更多…/)
})

test('MC-020:核销记录超出首批 1000 条不静默截断,计数标「+」并触底续读', () => {
  const vmod = require('vm')
  const requests = []
  let def = null
  const ctx = {
    getApp: () => ({ globalData: {}, sendRequest: (o) => requests.push(o) }),
    Page: (d) => { def = d },
    require: (rel) => {
      if (rel.endsWith('page-motion.js')) return { enter() {} }
      if (rel.endsWith('merchant-theme.js')) return { merchantPageShow() {}, merchantPageRestore() {} }
      if (rel.endsWith('toast.js')) return () => {}
      if (rel.startsWith('./')) return require(path.join(ROOT, 'pages/merchant/verify-records', rel))
      return require(path.join(ROOT, 'pages/merchant/verify-records', rel))
    },
    wx: { getWindowInfo: () => ({}), stopPullDownRefresh() {} },
    JSON, Number, Array, Object, String, Math,
  }
  vmod.runInNewContext(read('pages/merchant/verify-records/index.js'), ctx)
  const page = Object.assign({ data: JSON.parse(JSON.stringify(def.data)), setData(p) { Object.assign(this.data, p) } }, def)
  page._merchantAccess = { canReadVerifyRecords: true }
  page.loadList()
  const rows = (n, base) => Array.from({ length: n }, (_, i) => ({ recordId: String(base + i), occurredAt: '2026-11-02 10:00:00' }))
  for (let p = 1; p <= 20; p += 1) {
    requests[p - 1].success({ code: 200, data: { total: 1030, rows: rows(50, p * 100) } })
  }
  assert.equal(requests.length, 20)
  assert.match(page.data.tabs[0].label, /1000\+$/)
  page.onReachBottom()
  assert.equal(JSON.parse(requests[20].data).pageNum, 21)
  requests[20].success({ code: 200, data: { total: 1030, rows: rows(30, 9000) } })
  assert.match(page.data.tabs[0].label, /1030$/)
  page.onReachBottom()
  assert.equal(requests.length, 21, '读完不再续读')
})

test('MC-021:合作中心列表非空时刷新失败轻提示,不再无声', () => {
  const js = read('pages/merchant/coop-center/index.js')
  assert.match(js, /_toastRefreshFailed\(kind\) \{/)
  assert.equal((js.match(/this\._toastRefreshFailed\('routes'\)/g) || []).length, 2)
  assert.equal((js.match(/this\._toastRefreshFailed\('events'\)/g) || []).length, 2)
})

test('MC-029:俱乐部详情切后台/卸载时冲刷待发的移出成员请求,且只发一次', () => {
  const js = read('pages/club/detail/index.js')
  assert.match(js, /onHide\(\) \{[^\n]*const pending = this\._pendingRemoval; this\._pendingRemoval = null; if \(typeof pending === 'function'\) pending\(\);/)
  assert.match(js, /onUnload\(\) \{[^\n]*if \(typeof pendingRemoval === 'function'\) pendingRemoval\(\);/)
  const rm = js.slice(js.indexOf('  removeMember(e) {'), js.indexOf('  _doRemoveMember('))
  assert.match(rm, /if \(done\) return;\s*done = true;/)
  assert.match(rm, /onUndo\(\) \{ done = true;/)
})

test('MC-024:商家首页章节回退名不再拼原始编号', () => {
  assert.doesNotMatch(read('pages/merchant/index/index.js'), /name: '章节 ' \+ id/)
})

test('MC-036:模板页发布能力卡失败不再吞掉,置 capabilityError,点发布补拉仍失败给提示', () => {
  const js = read('pages/template/index.js')
  assert.doesNotMatch(js, /fail\(\) \{ \/\* 能力卡失败不阻断页面 \*\/ \}/)
  assert.match(js, /that\.setData\(\{ capabilityError: true \}\);/)
  assert.match(js, /if \(!this\._pubLoaded\) this\.getPublishHome\(true\);/)
})

test('MC-037:自由探索有下限时滑杆下显示地板行', () => {
  assert.match(read('pages/topic/pricing/index.wxml'), /<view class="pg-range" wx:if="\{\{!isFreeExplore \|\| floorPositive\}\}">/)
  assert.match(read('pages/topic/pricing/index.js'), /floorPositive: floor > 0,/)
})

test('MC-038:俱乐部主办方视角 17 页根节点浅色并归商家域;申请页仍是玩家深色', () => {
  const lint = require(path.join(ROOT, 'scripts/ui-theme-unit-lint.js'))
  const pages = ['badges', 'checkin-detail', 'create', 'customer-detail', 'customers', 'edit', 'enroll', 'event-ops',
    'governance', 'group-code', 'join-requests', 'notify', 'payout-receiver', 'roles', 'settlement', 'topic-detail', 'workbench']
  for (const p of pages) {
    const route = `pages/club/${p}/index`
    const themes = lint.topLevelThemeClasses(read(route + '.wxml'))
    assert.equal(themes.has('theme-merchant'), true, route)
    assert.equal(themes.has('theme-dark'), false, route)
    assert.equal(lint.classifyPageDomain(route), 'merchant', route)
    const json = JSON.parse(read(route + '.json'))
    assert.equal(json.navigationBarTextStyle, 'black', route)
    assert.equal(json.backgroundColor, '#F3F4F4', route)
  }
  assert.equal(lint.classifyPageDomain('pages/club/apply/index'), 'player')
  assert.equal(lint.topLevelThemeClasses(read('pages/club/apply/index.wxml')).has('theme-dark'), true)
})
