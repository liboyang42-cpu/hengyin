'use strict'
// l-after(2026-11-10 launch wave):主题之外的页面 —— 结算四步、统一邀请页、票夹筛选 + 镜面、收集册卡堆插图、游戏主题库。
// 规格来源:docs/delivery/questo-ui-20261109/proto/game(业主批准)+ LAUNCH.md l-after 段 + MOTION.md。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const root = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')

function loadShareCard() {
  let config
  global.Component = (c) => { config = c }
  global.getApp = () => ({ globalData: {}, getImgUrl: (u) => u, sendRequest() {} })
  global.wx = {}
  const file = path.join(root, 'components/cy/share-card/index.js')
  delete require.cache[require.resolve(file)]
  require(file)
  const inst = Object.assign({}, config.methods, { data: JSON.parse(JSON.stringify(config.data)) })
  inst.setData = (p) => Object.assign(inst.data, p)
  inst.data.show = true
  return { config, inst }
}

test('结算四步(proto st-*):纯黑整屏、奖杯插图、两格只留分数 / 节点、榜单是导航以下的近全屏弹层、评价有标签片、分享一行 分享 + 完成', () => {
  const wxml = read('subpackageJourney/pages/settle/index.wxml')
  const wxss = read('subpackageJourney/pages/settle/index.wxss')
  const js = read('subpackageJourney/pages/settle/index.js')
  assert.match(wxml, /<cy-illus name="trophy" size="300"/)
  assert.match(wxml, /<view class="st-two__c" wx:if="\{\{report\.scored\}\}">.*分数/)
  assert.doesNotMatch(wxml, /用时|cy-celebrate/, '总览只留 分数 / 节点;结算不再撒彩旗(整局只庆祝一次)')
  assert.match(wxml, /class="st-panel \{\{rankOn \? 'is-on' : ''\}\}" style="top:\{\{navTop \+ 14\}\}px"/)
  assert.match(wxml, /<rank-board [^>]*preset="settle"/)
  assert.match(wxml, /class="st-tag \{\{item\.on \? 'is-on' : ''\}\}"/)
  assert.match(wxml, /class="st-footer \{\{step === 'share' \? 'st-footer--row' : ''\}\}"/)
  assert.match(wxml, /<button class="st-share" open-type="share" aria-label="分享">[\s\S]*<cy-btn class="st-footer__i" variant="ghost" bindtap="done">完成<\/cy-btn>/)
  assert.equal((wxml.match(/open-type="share"/g) || []).length, 1)
  // 动效只用过渡;减少动态效果直接终态
  assert.doesNotMatch(wxss, /@keyframes|animation:/)
  assert.match(wxss, /\.st--reduced \.st-rise, \.st--reduced \.st-glow \{ opacity: 1; transform: none; \}/)
  assert.match(wxss, /\.st-panel \{[^}]*transform: translateY\(100%\)/)
  // 标签片随文字一起提交;没有星级 / 没有内容各自红字
  assert.match(js, /const TAGS = \['故事好', '地点选得好', '谜题有意思', '路太远', '提示不够', '太难了'\]/)
  assert.match(js, /if \(!this\.data\.stars\) \{ this\.setData\(\{ rateError: '先选星级' \}\); return; \}/)
  assert.match(js, /if \(!text\) \{ this\.setData\(\{ rateError: '写一句再提交' \}\); return; \}/)
  assert.match(js, /thanks: true/)
})

test('榜单 preset=settle:只留 全体 / 队内(全体在前),不探队伍榜、没有「更多」;月桂 M13 两枝 + 名次 120ms 后滚', () => {
  const js = read('subpackageJourney/components/rank-board/index.js')
  const wxml = read('subpackageJourney/components/rank-board/index.wxml')
  const wxss = read('subpackageJourney/components/rank-board/index.wxss')
  assert.match(js, /preset: \{ type: String, value: '' \}/)
  assert.match(js, /\['quest', 'team'\]\.map\(k => t0\.primary\.find\(x => x\.key === k\)\)\.filter\(Boolean\), more: \[\] \}/)
  assert.match(js, /_probeTeams\(\) \{\n\s+if \(this\._settle\(\)\) return;/)
  assert.match(js, /const ROLL_DELAY_MS = 120;/)
  assert.match(js, /motion\.haptic\(\{ type: 'light' \}\)/)
  assert.equal((wxml.match(/rb-hero__laurel/g) || []).length, 4, '左右两枝(各含 class 两次)')
  assert.match(wxss, /\.rb-hero__laurel \{[^}]*transform: scale\(\.6\)/)
  assert.match(wxss, /\.rb-hero\.is-on \.rb-hero__laurel--r \{ transform: scaleX\(-1\); \}/)
  assert.match(wxss, /font-size: 168rpx/)
  assert.doesNotMatch(wxss, /@keyframes|animation:/)
  const svg = read('subpackageJourney/images/laurel.svg')
  assert.equal((svg.match(/<ellipse/g) || []).length, 24, '12 对小叶')
})

test('统一邀请页(proto sh-invite):kind=invite 眉题 / 邀请人 · 剩余席位 / 竖向路线点 / 宿主给码就不再取主题码', () => {
  const { inst, config } = loadShareCard()
  const open = (card) => { config.observers['show, card'].call(inst, true, card); return inst.data }
  let d = open({ kind: 'invite', id: '7', title: '老城三封信', cover: '', inviter: '旅人', seatsLeft: 2, stops: ['邮政大楼', '老书店', '钟楼', '多的'] })
  assert.equal(d.heading, '邀请好友')
  assert.equal(d.eyebrow, '邀请 · 和我一起走这条路')
  assert.equal(d.inviterText, '旅人 邀请你 · 还有 2 个席位')
  assert.deepEqual(d.steps, ['邮政大楼', '老书店', '钟楼'], '路线点最多三站')
  assert.equal(d.qr, '', '没给码时走主题码接口')
  d = open({ kind: 'invite', title: '一起探索', inviter: '', seatsLeft: null, qr: 'https://x/code.png' })
  assert.equal(d.inviterText, '', '没有邀请人也没有席位数就整行不出')
  assert.equal(d.qr, 'https://x/code.png', '宿主给了码(个人码)直接用')
  d = open({ kind: 'invite', title: 't', inviter: '岚', seatsLeft: '不是数' })
  assert.equal(d.inviterText, '岚 邀请你', '席位数不是数字就不写')
  d = open({ kind: 'theme', id: '7', title: 't' })
  assert.equal(d.heading, '分享主题')
  assert.deepEqual(d.steps, [])
  const wxml = read('components/cy/share-card/index.wxml')
  assert.match(wxml, /<view class="sc-card sc-card--invite" wx:if="\{\{kind === 'invite'\}\}">/)
  assert.match(wxml, /<text class="sc-h">\{\{heading\}\}<\/text>/)
  assert.equal((wxml.match(/open-type="share"/g) || []).length, 1, '仍只有一颗「发给微信好友」')
})

test('邀请入口全部走统一邀请页:同行席位(带席位数 + 信封插图)、队伍详情(队长 + 空席)、我的 → 邀请好友(个人码)', () => {
  const seats = read('subpackageJourney/pages/seats/index.wxml')
  assert.match(seats, /<cy-share-card show="\{\{inviteShow\}\}" card="\{\{inviteCard\}\}" bind:close="onCloseInvite" \/>/)
  assert.doesNotMatch(seats, /<cy-sheet/, '邀请半屏撤掉;已邀请列表留在页面里')
  assert.match(seats, /class="lb-invited"/)
  const seatsJs = read('subpackageJourney/pages/seats/index.js')
  assert.match(seatsJs, /seatsLeft: l\.openCount, art: illus\.layers\('envelope'\)\.fg/)
  const lobby = require(path.join(root, 'subpackageJourney/utils/lobby-view.js'))
  const v = lobby.lobbyView({ seatCount: 3, canInvite: true, seats: [{ key: 'a', no: 1, state: 'BUYER' }, { key: 'b', no: 2, state: 'AVAILABLE' }, { key: 'c', no: 3, state: 'INVITED', label: '已邀请' }] }, {}, { name: '我' })
  assert.equal(v.openCount, 1)
  const team = read('pages/team/detail/index.wxml')
  assert.match(team, /class="team-action primary" bindtap="openInvite"/)
  assert.doesNotMatch(team, /open-type="share"[^>]*>邀请队友/)
  assert.match(team, /<cy-share-card show="\{\{inviteShow\}\}" card="\{\{inviteCard\}\}" bind:close="closeInvite" \/>/)
  const profile = read('subpackageMember/components/cy/profile/index.js')
  assert.match(profile, /goInvite: function \(\) \{\n[\s\S]*?kind: 'invite'[\s\S]*?qr: this\._playerCode \|\| ''/)
  assert.match(profile, /url: '\/api\/user\/player-code'/)
  assert.match(profile, /ds\.shareKind === 'invite' \|\| \(ds\.shareKind === 'card' && this\.data\.inviteShow\)/)
  assert.match(read('subpackageMember/components/cy/profile/index.wxml'), /<cy-share-card wx:if="\{\{isSelf\}\}" show="\{\{inviteShow\}\}" card="\{\{inviteCard\}\}" bind:close="closeInvite" \/>/)
})

test('票夹(proto wl-wallet):「票夹 ▾」筛选只按 topicCompleted 分;卷页时镜面高光带 --sx 跟手;卡下倒影', () => {
  const P = path.join(root, 'subpackageMember/signup/index.js')
  delete require.cache[P]
  global.getApp = () => ({ globalData: {}, getImgUrl: (x) => x })
  global.Page = () => {}
  global.wx = { getStorageSync: () => '' }
  const { filterTickets, pagePoses } = require(P)
  const list = [{ wKey: 'a', topicCompleted: true }, { wKey: 'b', topicCompleted: false }, { wKey: 'c' }]
  assert.deepEqual(filterTickets(list, 'all').map(t => t.wKey), ['a', 'b', 'c'])
  assert.deepEqual(filterTickets(list, 'done').map(t => t.wKey), ['a'])
  assert.deepEqual(filterTickets(list, 'upcoming').map(t => t.wKey), ['b', 'c'], '没有通关事实的票算还没通关,不算已通关')
  const left = pagePoses(3, 0, -150, 300)
  assert.match(left[0], /--spec: 0\.750/)
  assert.match(left[0], /--sx: 45\.0%/)
  assert.match(left[0], /skewY\(-2\.0deg\)/)
  const wxml = read('subpackageMember/signup/index.wxml')
  assert.match(wxml, /class="wl-pop \{\{walPop \? 'is-open' : ''\}\}"/)
  assert.match(wxml, /bindtap="pickWalFilter"/)
  const wxss = read('subpackageMember/signup/index.wxss')
  assert.match(wxss, /\.wc__spec \{[^}]*opacity: var\(--spec, 0\)/)
  assert.match(wxss, /\.wc \{ -webkit-box-reflect: below 12rpx/)
  assert.doesNotMatch(wxss.split('===== l-after')[1] || '', /@keyframes/)
})

test('收集册卡堆:三张卡上的图换成 cy-illus(勋章 / 邮戳 / 宝石),段名在左下;副本与 play 规范实现一致由 launch 底座测试保证', () => {
  const wxml = read('subpackageP3/components/album-intro/index.wxml')
  assert.match(wxml, /<cy-illus wx:if="\{\{ring\}\}" name="\{\{item\.illus\}\}" size="300"/)
  assert.doesNotMatch(wxml, /album-intro\/\{\{item\.key\}\}\.png/)
  assert.match(wxml, /<text class="ar__lb" aria-hidden="true">\{\{item\.label\}\}<\/text>/)
  const { ALBUM_SEGS } = require(path.join(root, 'subpackageP3/components/album-head/routes.js'))
  assert.deepEqual(ALBUM_SEGS.map(s => s.illus), ['badge', 'stamp', 'gem'])
  const json = JSON.parse(read('subpackageP3/components/album-intro/index.json'))
  assert.equal(json.usingComponents['cy-illus'], '/subpackageP3/components/cy-illus/index')
  for (const sub of ['subpackageJourney', 'subpackageP3']) assert.ok(fs.existsSync(path.join(root, sub, 'components/cy-illus/art.js')), sub + ' 有 cy-illus 副本')
})

test('游戏主题库(proto ps-library):读公开目录,一行一张俱乐部式卡,标题不写数量,¥0 只认服务端 passCovered === true', () => {
  const lib = require(path.join(root, 'subpackageMember/utils/pass-library.js'))
  assert.deepEqual(lib.KINDS.map(k => k.label), ['全部', '城市定向', '自由探索'])
  assert.equal(lib.query('city', 2).product_type, '1')
  assert.equal(lib.query('free', 1).product_type, '2')
  assert.equal(lib.query('all', 1).product_type, undefined)
  assert.equal(lib.query('all', 3).pageNum, 3)
  const row = { id: 9, name: '老城三封信', imgUrl: 'c.jpg', productType: 1, cityName: '上海', locationCount: 7, totalTime: 3600, passCovered: true }
  const card = lib.libraryCard(row)
  assert.equal(card.title, '老城三封信')
  assert.equal(card.data, '经典定向 · 7 站 · 约 60 分钟')
  assert.equal(card.free, true)
  assert.equal(lib.libraryCard(Object.assign({}, row, { passCovered: 'true' })).free, false, '字符串不算覆盖')
  assert.equal(lib.libraryCard(Object.assign({}, row, { passCovered: undefined })).free, false, '没有事实不写 ¥0')
  assert.equal(lib.libraryCard({ id: 1 }), null)
  const wxml = read('subpackageMember/market/library/index.wxml')
  assert.match(wxml, /<cy-page-title title="游戏主题库" \/>/)
  assert.match(wxml, /<text class="lb-card__free" wx:if="\{\{item\.free\}\}">¥0 可玩<\/text>/)
  assert.match(wxml, /class="photo-card lb-card"/)
  assert.doesNotMatch(wxml, /\d+ 个主题|主题库 · /)
  const app = JSON.parse(read('app.json'))
  const sub = (app.subPackages || app.subpackages).find(s => s.root === 'subpackageMember')
  assert.ok(sub.pages.includes('market/library/index'))
  assert.match(read('subpackageMember/market/passes/index.wxml'), /bindtap="goLibrary"/)
})
