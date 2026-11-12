// qx-b8 U08-01 / U08-02:完成页结果词以服务端战报为准;推荐只给可报名候选,曝光与点击带同一身份。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const fr = require('../../pages/play/utils/finish-result.js')
const rec = require('../../pages/play/finish-route-recommendation.js')

test('U08-01 合法完局才说「已通关」;辅助完成单列;结束不是通关', () => {
  const local = { topicCompleted: false, allDone: true, chapterDone: true }
  assert.deepEqual(fr.resolveFinishResult({ completionKind: 'LEGITIMATE_COMPLETION' }, local, 'ok'),
    { label: '已通关', win: true, source: 'report', kind: 'LEGITIMATE_COMPLETION' })
  assert.equal(fr.resolveFinishResult({ completionKind: 'ASSISTED_COMPLETION' }, local, 'ok').label, '辅助完成')
  assert.equal(fr.resolveFinishResult({ completionKind: 'ASSISTED_COMPLETION' }, local, 'ok').win, false)
  assert.equal(fr.resolveFinishResult({ completionKind: 'ENDED' }, local, 'ok').label, '已结束')
})

test('U08-01 本地站点都完成但战报在途或读不到:结果未知,不写「已通关」也不写「已结束」', () => {
  const local = { topicCompleted: false, allDone: true, chapterDone: true }
  assert.equal(fr.resolveFinishResult(null, local, 'pending').label, '')
  assert.equal(fr.resolveFinishResult(null, local, 'failed').label, '')
  // 到达回执里服务端已判 completed 的,在途时可先说已通关
  assert.equal(fr.resolveFinishResult(null, { topicCompleted: true, allDone: true }, 'pending').label, '已通关')
  // 战报说仍在进行:不升级
  assert.equal(fr.resolveFinishResult({ completionKind: 'IN_PROGRESS' }, local, 'ok').label, '本章完成')
  assert.equal(fr.resolveFinishResult({ completionKind: 'IN_PROGRESS' }, { allDone: false, chapterDone: false }, 'ok').label, '已结束')
})

test('U08-01 战报读取:非 200 / 无 data 一律视为读不到', () => {
  assert.equal(fr.readReport({ code: 500, data: {} }), null)
  assert.equal(fr.readReport({ code: 200 }), null)
  assert.equal(fr.readReport(null), null)
  assert.deepEqual(fr.readReport({ code: 200, data: { completionKind: 'ENDED' } }), { completionKind: 'ENDED' })
})

test('U08-01 战报查询带活动或主题,本局 runId 只在有效时附上', () => {
  assert.deepEqual(fr.reportQuery(10, 100, 55), { activityId: 10, runId: 55 })
  assert.deepEqual(fr.reportQuery(0, 100, null), { topicId: 100 })
  assert.equal(fr.reportQuery(0, 0, 1), null)
})

test('U08-01 完成页结果行由 finishResult 驱动,不再以本地 allDone 判通关', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxml'), 'utf8')
  const line = wxml.split('\n').find((l) => l.includes('class="finsheet__result'))
  assert.match(line, /finishResult\.label/)
  assert.doesNotMatch(line, /allDone/)
  const js = fs.readFileSync(path.join(ROOT, 'pages/play/index.js'), 'utf8')
  assert.match(js, /\/api\/play\/player-journey\/report/)
})

test('U08-02 已过结束时间的场次不是可报名候选,跳到下一条', () => {
  const now = new Date('2026-11-09T12:00:00+08:00').getTime()
  const card = rec.pickCandidate([
    { id: 1, topicId: 2, name: '已结束场', endDate: '2026-11-01 18:00:00' },
    { id: 3, topicId: 4, name: '进行中', endDate: '2026-12-01 18:00:00' },
  ], '', '', now)
  assert.equal(card.id, 3)
  assert.equal(rec.pickCandidate([{ id: 1, endDate: '2026-11-01 18:00:00' }], '', '', now), null, '无候选自然结束')
  assert.equal(rec.pickCandidate([{ id: 5, endDate: null }], '', '', now).id, 5, '未给结束时间按列表公开闸')
})

test('U08-02 曝光与点击共用 impressionId,并带来源局与来源主题', () => {
  const source = rec.impressionSource({ activityId: 10, topicId: 100, runId: 77 }, 1000)
  assert.match(source.impressionId, /^frr-/)
  assert.deepEqual([source.sourceActivityId, source.sourceTopicId, source.sourceRunId], ['10', '100', '77'])
  const card = { id: 37, source }
  const events = []
  const scenes = []
  rec.openCandidate(card, { mode: 1, track: (n, p) => events.push({ n, p }), openScene: (id, params) => scenes.push({ id, params }) })
  assert.equal(events[0].p.properties.impressionId, source.impressionId)
  assert.equal(events[0].p.properties.sourceRunId, '77')
  assert.deepEqual(scenes, [{ id: 'play-activity-detail', params: { id: 37 } }], '点击只打开详情,不算报名')
  assert.equal(rec.impressionSource({}, 1).sourceRunId, null)
})
