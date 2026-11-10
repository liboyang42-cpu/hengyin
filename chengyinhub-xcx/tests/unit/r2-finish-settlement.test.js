const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const v = require('../../subpackageJourney/utils/journey-view.js')
const card = require('../../subpackageJourney/utils/share-card.js')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

test('r2-finish 榜单口径:本关先给 队内/全体/队伍,城市国家世界俱乐部收进「更多」;没有队伍不出队内', () => {
  const t = v.boardTabs({ topicId: 3, teamId: 9, teamBoard: true, cityCode: '310100', countryCode: 'cn', clubId: 7 })
  assert.deepEqual(t.primary.map(x => x.label), ['队内', '全体', '队伍'])
  assert.deepEqual(t.more.map(x => x.key), ['city', 'country', 'world', 'club'])
  const solo = v.boardTabs({ topicId: 3 })
  assert.deepEqual(solo.primary.map(x => x.key), ['quest'])
  assert.deepEqual(solo.more.map(x => x.key), ['world'])
  assert.ok(!v.boardTabs({ topicId: 3 }).primary.some(x => /好友/.test(x.label)), '服务端没有好友作用域,不出好友页签')
})

test('r2-finish 名次大数字:只用服务端名次;前 N% 需要服务端上榜总数且不少于 5 人', () => {
  assert.equal(v.topPercent(3, 40), '前 8%')
  assert.equal(v.topPercent(1, 1), '', '一人上榜不写前 100%')
  assert.equal(v.topPercent(2, null), '', '总数缺失不算百分比')
  assert.equal(v.topPercent(9, 5), '', '名次大于总数是坏数据,不写')
  const r = v.rankView({ scope: 'quest', scopeId: '3', period: 'all', entries: [], me: { rank: 4, score: 2 }, coverage: { rankedMembers: 50 } },
    { scope: 'quest', scopeId: '3', period: 'all' })
  assert.deepEqual(r.hero, { rank: 4, digits: ['4'], percent: '前 8%' })
  const none = v.rankView({ scope: 'quest', scopeId: '3', period: 'all', entries: [], me: null }, { scope: 'quest', scopeId: '3', period: 'all' })
  assert.equal(none.hero, null, '没有名次不出大数字,不写 0')
})

test('r2-finish 队伍之间榜:/api/orient/team/ranking 行 → 本队名次 + 进度;读满上限不算百分比', () => {
  const rows = [1, 2, 3, 4, 5, 6].map(i => ({ rank: i, teamId: i, title: '队' + i, teamDoneCount: 7 - i, totalTasks: 6, activeCount: 3, mine: i === 2 }))
  const b = v.teamBoardView(rows)
  assert.equal(b.hero.rank, 2)
  assert.equal(b.hero.percent, '前 34%')
  assert.deepEqual(b.stats.map(s => s.value), ['5/6', '3'])
  const full = v.teamBoardView(Array.from({ length: 200 }, (_, i) => ({ rank: i + 1, teamId: i + 1, mine: i === 0 })))
  assert.equal(full.hero.percent, '', '服务端只回前 200 队时总数未知')
  assert.equal(v.teamBoardView([{ rank: 1, teamId: 1 }]).hero, null, '不在任何队伍时不出大数字')
})

test('r2-finish 分享卡只写战报事实:没计分不写分,没名次不写名次', () => {
  const report = v.reportView({ scopeKind: 'RUN', runState: 'COMPLETED', completionKind: 'LEGITIMATE_COMPLETION', completedNodes: 2, totalNodes: 2,
    score: { state: 'SCORED', score: 100 }, rank: { qualified: true, rank: 1, score: 2, scope: 'quest', scopeId: 3 } })
  assert.deepEqual(card.cardLines(report, '城市定向样板'), { kicker: '已通关', title: '城市定向样板', meta: '节点 2/2 · 100 分 · 第 1 名' })
  const plain = v.reportView({ scopeKind: 'RUN', runState: 'ENDED', completionKind: 'ENDED', completedNodes: 1, totalNodes: 2, score: { state: 'NOT_SCORED' }, rank: {} })
  assert.deepEqual(card.cardLines(plain, '主题'), { kicker: '已结束', title: '主题', meta: '节点 1/2' })
})

test('r2-finish 结算页:注册在分包、挂隐私弹窗、评价走既有评论写口(表单编码)、完成页只在整局走完时出入口', () => {
  const app = JSON.parse(read('app.json'))
  const sub = app.subPackages.find(s => s.root === 'subpackageJourney')
  assert.ok(sub.pages.includes('pages/settle/index'))
  const json = JSON.parse(read('subpackageJourney/pages/settle/index.json'))
  assert.ok(json.usingComponents['cy-privacy-gate'])
  const api = read('subpackageJourney/utils/journey-api.js')
  assert.match(api, /rate: .*'\/api\/comment\/add', 'POST', \{ owner_type: ownerType, owner_id: ownerId, rating, contents, reply_id: 0 \}, false, true\)/)
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /<view class="finsheet__settle" wx:if="\{\{allDone && !isPreview && !isMock && topicId\}\}" bindtap="openSettlement"/)
  const settle = read('subpackageJourney/pages/settle/index.wxml')
  assert.match(settle, /wx:if="\{\{videoOk\}\}"/, '视频分享只在基础库支持时出')
  assert.match(settle, /wx:if="\{\{report.elapsedText !== '未知'\}\}"/, '用时未知整格不出')
})
