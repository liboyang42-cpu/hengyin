'use strict'
// qx-a3(2026-11-09 业主 V3 走查):订单、个人资料、我的项目、探索目标、设置。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const strip = s => s.replace(/<!--[\s\S]*?-->/g, '')
const ORDER = 'subpackageMember/components/cy/scene-member-order-detail/'
const HISTORY = 'subpackageMember/components/cy/scene-member-order-history/'

test('V3-02 信息卡 .ocard 下接 cy-card 时有标准卡距(不再连成一块)', () => {
  const wxss = read(ORDER + 'index.wxss')
  assert.match(wxss, /\.ocard \{[^}]*margin-bottom: var\(--cy-scene-card-gap, var\(--cy-space-3\)\);/)
  // 「凭证与有效期」卡后面紧跟的是 cy-card,正是原来零间距的那一处
  const wxml = strip(read(ORDER + 'index.wxml'))
  assert.match(wxml, /<view class="sec-title">凭证与有效期<\/view>\s*<view class="ocard">[\s\S]*?<\/view>\s*<\/view>\s*<\/block>\s*<cy-card class="order-card"/)
})

test('V3-03 订单详情不出「开始游戏」,只保留已完成的「查看记录」', () => {
  const wxml = strip(read(ORDER + 'index.wxml'))
  assert.doesNotMatch(wxml, /开始游戏/)
  assert.doesNotMatch(wxml, /primaryCta === 'play'/)
  assert.match(wxml, /wx:elif="\{\{primaryCta === 'record'\}\}"/)
  assert.doesNotMatch(read(ORDER + 'index.js'), /return 'play'/)
})

test('V3-22 订单卡:类别与状态同一行,不写订单号,没有地点不画「—」', () => {
  const wxml = strip(read(HISTORY + 'index.wxml'))
  assert.doesNotMatch(wxml, /订单号：/)
  assert.doesNotMatch(wxml, /orderbox_li_top/)
  const heads = wxml.match(/<view class="moh-head">[\s\S]*?<text class="moh-state order-state-\{\{item\.orderStateKey\}\}">\{\{item\.orderStateText\}\}<\/text>\s*<\/view>/g) || []
  assert.equal(heads.length, 2, '场次卡与主题卡各一行「类别 + 状态」')
  const addr = wxml.match(/class="orderbox_li_con_info_txt[^"]*"[^>]*>/g) || []
  assert.equal(addr.length, 2)
  addr.forEach(tag => assert.match(tag, /wx:if="\{\{item\.addrTap\}\}"/))
  const wxss = read(HISTORY + 'index.wxss')
  assert.match(wxss, /\.orderbox_li\{[^}]*margin: 0 0 var\(--cy-space-3\);/)
  assert.match(wxss, /\.orderbox_li_con\{[^}]*align-items: flex-start;/)
  assert.match(wxss, /\.moh-head \.moh-state\{margin-left: auto;/)
})

test('V3-33 更换背景随头像一列居中,不再右侧悬浮;命中区 88rpx', () => {
  const wxml = strip(read('pages/gerenziliao/gerenziliao.wxml'))
  const head = wxml.match(/<view class="rv-head">[\s\S]*?<view class="rv-group">/)[0]
  assert.match(head, /<view class="rv-bg-hit" bindtap="changeHeroBg"/)
  assert.equal((wxml.match(/bindtap="changeHeroBg"/g) || []).length, 1)
  const wxss = read('pages/gerenziliao/gerenziliao.wxss')
  assert.doesNotMatch(wxss, /\.rv-bg-btn \{[^}]*position: absolute/)
  assert.match(wxss, /\.rv-bg-hit \{[^}]*min-height: 88rpx;[^}]*justify-content: center;/)
})

test('V3-37 设置页图标都在主包或本分包内(跨分包图片真机加载不到)', () => {
  const app = JSON.parse(read('app.json'))
  const roots = (app.subPackages || app.subpackages).map(s => '/' + s.root.replace(/\/$/, '') + '/')
  const own = '/pages/shezhi/'
  const icons = [...read('pages/shezhi/shezhi.wxml').matchAll(/'(\/[^'"{}]+\.(?:svg|png))'|"(\/[^'"{}]+\.(?:svg|png))"/g)].map(m => m[1] || m[2])
  assert.ok(icons.includes('/pages/shezhi/images/icon_qa_person.svg'), '个人资料行用本分包图标')
  for (const icon of icons) {
    const foreign = roots.find(r => icon.startsWith(r) && r !== own)
    assert.equal(foreign, undefined, icon + ' 引用了别的分包')
    assert.ok(fs.existsSync(path.join(ROOT, icon)), icon + ' 不存在')
  }
})

test('V1-P53 设置里有「投诉建议」,打开同一个 settings-feedback 场景', () => {
  const wxml = strip(read('pages/shezhi/shezhi.wxml'))
  assert.match(wxml, /<cy-cell id="settings-feedback"[^>]*title="投诉建议" bind:tap="goFeedback" \/>/)
  global.getApp = () => ({ globalData: {} })
  let page
  global.Page = cfg => { page = cfg }
  global.wx = new Proxy({}, { get: () => () => ({}) })
  const opened = []
  try { require(path.join(ROOT, 'pages/shezhi/shezhi.js')) } catch (e) { /* 只取 Page 配置 */ }
  assert.ok(page && typeof page.goFeedback === 'function')
  page.goFeedback.call({ openScene: id => opened.push(id) })
  assert.deepEqual(opened, ['settings-feedback'])
  assert.match(read('utils/scene-registry.js'), /'settings-feedback': \{[^}]*title: '投诉建议'/)
})

test('V3-38/39 我的项目:状态与标题同一行,状态只写短词(通过)', () => {
  const wxml = strip(read('subpackageA/pages/myproject/index.wxml'))
  const rows = wxml.match(/<view class="mp-tit-row">[\s\S]*?<\/view>\s*<cy-badge class="mp-badge"[^>]*\/>\s*<\/view>/g) || []
  assert.equal(rows.length, 2, '主题卡与模板卡的状态都在标题行')
  assert.match(wxml, /label="\{\{item\._statusShort\}\}"/)
  assert.doesNotMatch(wxml, /mp-when-row/)
  const { marketView } = require(path.join(ROOT, 'utils/template-market.js'))
  assert.equal(marketView({ draftStatus: 1, status: 1, checkStatus: 1 })._statusShort, '通过')
  assert.equal(marketView({ draftStatus: 1, status: 0, checkStatus: 0 })._statusShort, '审核中')
  assert.equal(marketView({ draftStatus: 1, status: 2 })._statusShort, '未通过')
  assert.equal(marketView({ draftStatus: 0 })._statusShort, '草稿')
  assert.equal(marketView({})._statusShort, '待确认')
  assert.equal(marketView({ draftStatus: 1, status: 1, checkStatus: 1 })._statusText, '个人审核通过', '其它入口的长文案不变')
  assert.match(read('subpackageA/pages/myproject/index.wxss'), /\.mp-badge \{ flex-shrink: 0;[^}]*white-space: nowrap; \}/)
})

test('V3-28 探索目标空态用标准两层插画 cy-empty kind="empty"', () => {
  const wxml = strip(read('subpackageTools/exploration-goals/index.wxml'))
  assert.match(wxml, /<cy-empty kind="empty" size="lg" title="还没有探索目标" \/>/)
  assert.doesNotMatch(wxml, /tu-empty__t/)
  assert.equal(JSON.parse(read('subpackageTools/exploration-goals/index.json')).usingComponents['cy-empty'], '/components/cy/empty/index')
})
