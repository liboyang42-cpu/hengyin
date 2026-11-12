const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const JS = fs.readFileSync(path.resolve(__dirname, '../../pages/roam/index.js'), 'utf8')
const WXML = fs.readFileSync(path.resolve(__dirname, '../../pages/roam/index.wxml'), 'utf8')
const WXSS = fs.readFileSync(path.resolve(__dirname, '../../pages/roam/index.wxss'), 'utf8')
const NEARBY_JS = fs.readFileSync(path.resolve(__dirname, '../../subpackageRoam/nearby/index.js'), 'utf8')

test('漫游 tab 默认进入双菜单首页，地图预览不采集定位', () => {
  assert.match(JS, /screen:\s*'entry-map'/)
  assert.match(WXML, /class="intro" wx:if="\{\{screen=='entry-map'\|\|screen=='intro'\}\}"/)
  assert.match(WXML, /<free-map[^>]+entryMap/s)
  assert.match(WXML, /interactive="\{\{false\}\}"/)
  // 2026-10-27 owner P-25:头部还原到 7f4bcee9 之前 —— 标签回到 cy-tabs(撤掉 v13 自绘短线标签)
  assert.match(WXML, /<cy-tabs plain tabs="\{\{introTabs\}\}" active="\{\{introTab\}\}" bind:change="switchIntroTab" \/>/)
})

test('首页保留章节入口，移除浮动记录、搜索与图层按钮', () => {
  const home = WXML.slice(WXML.indexOf('class="intro"'), WXML.indexOf('<!-- ===================== B1'))
  assert.match(home, /bindtap="goStart"/)
  assert.doesNotMatch(home, /cy-map-address-search|entry-map__layers|entry-map__roam|class="go-wrap"/)
  assert.match(JS, /key: 'start'[^\n]+title: '开始漫游'/)
  assert.match(JS, /key: 'map'[^\n]+title: '打开地图'/)
  assert.match(JS, /key: 'my-teams'[^\n]+title: '我的队伍'/)
})

test('附近活动只横滑且保持完整轮廓，失败空态留在抽屉内', () => {
  assert.match(WXML, /class="entry-peek"/)
  assert.match(WXML, /entry-peek__cards" scroll-x/)
  assert.doesNotMatch(WXML, /entryPeekExpanded|toggleEntryPeek|onEntryPeekTouch|entry-peek__cards"[^>]*scroll-y/)
  assert.doesNotMatch(JS, /entryPeekExpanded|toggleEntryPeek|onEntryPeekTouch/)
  assert.doesNotMatch(WXML, />查看全部</)
  assert.match(WXML, /<cy-state-shell wx:if="\{\{entryReadAccess\}\}" kind="no-permission"/)
  assert.match(WXML, /<cy-error wx:elif="\{\{entryLoadError\}\}" load-failure="\{\{true\}\}"\s*\/>/)
  assert.match(WXSS, /\.entry-peek\s*\{[^}]*position:relative[^}]*linear-gradient/s)
  assert.match(WXSS, /\.entry-peek__head\s*\{[^}]*font-size:calc\(29rpx \* var\(--cy-type-scale, 1\)\)/s)  // 第十二轮 k7(j4 F14):跟手机字号走
  assert.match(WXSS, /\.entry-card\s*\{[^}]*height:242rpx/s)
  assert.match(WXSS, /\.entry-card__title\s*\{[^}]*font-size:calc\(27rpx \* var\(--cy-type-scale, 1\)\)/s)
})

test('活动详情使用紧凑摘要卡，不再使用整幅英雄图', () => {
  assert.match(WXML, /entry-activity__summary/)
  assert.doesNotMatch(WXML, /\? '活动详情' : '开始漫游'/)
  assert.doesNotMatch(WXML, /entry-activity__hero/)
})

test('两个首页都有现有底栏，活动与章节首卡对齐', () => {
  assert.match(WXML, /intro-title">漫游</)
  assert.match(WXML, /<tabBar\s+wx:if="\{\{screen=='entry-map'\|\|screen=='intro'\}\}"/)
  // 2026-10-14 用户确认的漫游原型:章节卡与附近活动左缘对齐标题(46rpx = 24px),卡片尺寸不变(310×100)
  assert.match(WXML, /previous-margin="46rpx"/)
  assert.match(WXSS, /--roam-card-inset:46rpx/)
  assert.match(WXSS, /\.entry-peek__track\{[^}]*padding:0 var\(--roam-card-inset\)/)
})

test('报名按钮使用白色 token，入口弹层按内容定高并统一透明玻璃 token', () => {
  assert.match(WXML, /entry-sheet__primary[^>]+bindtap="openEntryActivityDetail"[^>]*>\{\{entryActivity\.ended \? '查看详情' : '去报名'\}\}</)
  assert.match(WXSS, /\.entry-sheet__primary\s*\{[^}]*background:\s*var\(--cy-comp-sheet-player-text\)/s)
  assert.match(WXML, /class="psheet \{\{entrySheetIn\?'is-in':''\}\} entry-sheet"/)
  assert.match(WXML, /class="pdim \{\{entrySheetIn\?'is-in':''\}\} entry-sheet__mask"/)
  assert.match(JS, /_showEntrySheet\(entrySheet, entryActivity, entryPlace\)[\s\S]*wx\.nextTick\([\s\S]*entrySheetIn:\s*true/)
  assert.match(WXSS, /\.entry-sheet\s*\{[^}]*background:var\(--cy-comp-sheet-player-glass\)[^}]*backdrop-filter:blur\(var\(--cy-comp-sheet-blur\)\)/s)
  assert.match(WXSS, /\.entry-sheet\s*\{[^}]*transform:translateY\(100%\)/s)
  assert.match(WXSS, /\.entry-sheet\.is-in\s*\{[^}]*translateY\(0\)/s)
  assert.doesNotMatch(WXML, /entrySheetExpanded|toggleEntrySheetHeight|onEntrySheetTouch/)
  assert.doesNotMatch(JS, /entrySheetExpanded|toggleEntrySheetHeight|onEntrySheetTouch/)
  // 2026-10-23 业主确认弹层不回弹:入口半屏改走弹层规范的档位时长 + 慢快慢,不再是 slow + spring
  assert.match(WXSS, /\.entry-sheet\s*\{[^}]*--cy-motion-sheet-out[^}]*--cy-ease-standard/s)
  assert.doesNotMatch(WXSS, /\.entry-sheet\s*\{[^}]*--cy-ease-spring/s)
  assert.doesNotMatch(WXML, /entry-sheet__eyebrow|entry-activity__status|城市定向\s*·\s*报名中|>先报名</)
})

test('队伍与规则在同一弹层内切换，现场动态直接显示图片', () => {
  assert.match(JS, /openEntryTeam\(e\)[\s\S]*entrySheetView:\s*'team'/)
  assert.match(JS, /openEntryRoamRules\(\)\s*\{\s*this\.setData\(\{\s*entrySheetView:\s*'rules'/)
  assert.doesNotMatch(JS, /openEntryPosts/)
  assert.match(WXML, /现场动态[\s\S]*entryActivity\.dynamicImages/)
  assert.match(WXML, /wx:if="\{\{entryActivity\.registered && !entryActivity\.ended\}\}"[^>]*[\s\S]*?发起组队/)
  assert.match(WXML, /<\/view>\n      <\/view>\n      <view wx:if="\{\{entryActivity\.registered && !entryActivity\.ended\}\}" class="entry-sheet__create-team"/,
    '未结束且已报名但暂无队伍时也必须能发起组队，入口不能嵌在 teams.length 区块内')
})

test('六个 Figma 状态都有真实落点', () => {
  assert.match(WXML, /entry-peek/)
  assert.match(WXML, /entrySheet=='activity'/)
  assert.match(JS, /pages\/topic\/index\/index\?id=/)
  assert.match(WXML, /entryActivity\.registered/)
  assert.match(WXML, /entrySheet=='layers'/)
  assert.match(WXML, /entrySheet=='roam'/)
})

test('活动、队伍同图展示，活动报名优先进入主题详情', () => {
  assert.match(JS, /\/api\/roam\/entry\/nearby/)
  assert.match(JS, /\/api\/team\/nearby/)
  assert.match(JS, /pages\/topic\/index\/index\?id=/)
  assert.match(WXML, /entryActivity\.teams/)
})

test('队伍入口复用附近队伍的持票申请链，定位失败在入口地图可见', () => {
  assert.match(JS, /\/subpackageRoam\/nearby\/index\?teamId=/)
  assert.match(NEARBY_JS, /_requestedTeamId/)
  assert.match(NEARBY_JS, /openTeam\(teamId\)/)
  assert.match(WXML, /intro-permission[^>]+wx:if="\{\{introError\}\}"/)
})

// 2026-11-09 V3-35:城市漫游目录不再是清单,已上架、带导入种子标签的平台点位直接画在入口地图上
test('V3-35 入口地图只把目录地点(type=1 + 种子标签 + 坐标)画成图钉,点图钉回到同一地点', () => {
  const entryMap = require('../../utils/roam-entry-map.js')
  const seed = { id: 7, type: 1, name: '山阴路', address: '虹口区山阴路', lat: 31.27, lng: 121.48, tags: '["roam:street","roam:seed:v1:sh-shanyin-road"]' }
  const rows = [
    seed,
    { id: 8, type: 1, name: '无种子', lat: 31.27, lng: 121.48, tags: '["roam:park"]' },
    { id: 9, type: 2, name: '商家', lat: 31.27, lng: 121.48, merchantId: 3, tags: '["roam:seed:v1:x"]' },
    { id: 10, type: 1, name: '无坐标', lat: null, lng: 121.48, tags: '["roam:seed:v1:y"]' },
  ]
  assert.deepEqual(rows.filter(entryMap.isCatalogPlace).map((r) => r.id), [7])
  const view = entryMap.build([], [], { activities: true, teams: true, posts: true }, (u) => u, rows)
  assert.equal(view.markers.length, 1)
  assert.deepEqual(entryMap.parseMarker(view.markers[0].id), { kind: 'place', id: 7 })
  assert.equal(view.markers[0].iconPath, '/images/roam-pushpin.png')
  assert.equal(view.cards.length, 0, '地点不进附近活动卡')
  const place = entryMap.placeView(seed)
  assert.equal(place.catLabel, '宝藏街道')
  assert.equal(place.tags, seed.tags)
  // 关图层不影响目录地点(它不属于活动 / 队伍 / 帖文三层)
  assert.equal(entryMap.build([], [], { activities: false, teams: false, posts: false }, (u) => u, rows).markers.length, 1)
})

test('V3-35 点地点图钉开同一个入口弹层,理由由分包理由组件回填,换卡后的迟到回填丢弃', () => {
  assert.match(JS, /hit\.kind === 'place'[\s\S]{0,200}this\._showEntrySheet\('place', null, entryMap\.placeView\(row\)\)/)
  assert.match(JS, /onEntryPlaceNote\(e\) \{[\s\S]{0,160}place\.tags !== d\.tags\) return;/)
  assert.match(WXML, /wx:elif="\{\{entrySheet=='place' && entryPlace\}\}"><text class="entry-sheet__title">\{\{entryPlace\.name\}\}<\/text>/)
})

// 2026-11-09 V1-P32:活动 / 主题与招募中队伍在漫游地图上常显深色小卡(标题 + 一行服务端事实),不再只是 14px 小点
test('V1-P32 地图上的活动与队伍都带常显卡片;没有人数时不写 0', () => {
  const entryMap = require('../../utils/roam-entry-map.js')
  const view = entryMap.build(
    [{ kind: 'topic', id: 5, productType: 2, name: '探店日', latitude: 31.24, longitude: 121.48, distance: 585 }],
    [{ teamId: 3, title: '周六小队', joinedCount: 2, maxMembers: 4, latitude: 31.23, longitude: 121.47 },
      { teamId: 4, title: '', latitude: 31.23, longitude: 121.47 }],
    { activities: true, teams: true, posts: true }, (u) => u, [])
  const byKind = (kind) => view.markers.filter((m) => (entryMap.parseMarker(m.id) || {}).kind === kind)
  const topic = byKind('topic')[0]
  assert.equal(topic.callout.display, 'ALWAYS')
  assert.equal(topic.callout.content, '探店日\n距你 585m')
  assert.ok(topic.width >= 20)
  const teams = byKind('team')
  assert.equal(teams[0].callout.content, '周六小队\n2/4 人 · 招募中')
  assert.equal(teams[1].callout.content, '玩家队伍\n招募中', '没有人数字段就不写 0/0')
  assert.ok(teams.every((m) => m.callout.display === 'ALWAYS' && m.width >= 22))
  // 图层关掉仍然整层不画
  assert.equal(entryMap.build([], [{ teamId: 3, latitude: 31, longitude: 121 }], { activities: true, teams: false, posts: true }, (u) => u, []).markers.length, 0)
})
