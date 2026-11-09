'use strict'
// 第十轮 f2 · j2 F-09:招商弹窗的数字与自己的列表打架 ——
// 「0/2 已招站点」而路线有 3 站;「待处理申请 0」下面却有一张申请卡;同一家店在「已确认」里标「待审核」;
// 「还没人接 1」上面列着 2–3 行。数字改成就是列表的行数。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const { recruitSummary, confirmedMerchants } = require(path.join(ROOT, 'pages/topic/utils/recruit-summary.js'))

// 走查现场:城市定向,2 章 3 站;第一章开了承接,第二章未开放;一家咖啡申请了喷泉站
const chapters = [{ chapterId: 41, state: 'open' }, { chapterId: 42, state: 'off' }]
const openNodes = [{ nodeId: 71, nodeName: '人民广场音乐喷泉' }, { nodeId: 72, nodeName: '上海博物馆' }, { nodeId: 73, nodeName: '大世界' }]
const applies = { state: 'ready', rows: [{ id: 14, name: '探店日样板咖啡' }] }

test('F-09 城市定向:站点按节点数,待处理 = 收到的申请行数,还没人接 = 那张列表的行数', () => {
  const before = recruitSummary({ stations: chapters, recruit: { nodeTotal: 3, nodeFilled: 0, openNodes }, applies, topic: { productType: 1 } })
  assert.deepEqual(before, { taken: 0, total: 3, pending: 1, open: 3 })
  // 确认后:喷泉站已有商家,收到的申请清空
  const after = recruitSummary({ stations: chapters, recruit: { nodeTotal: 3, nodeFilled: 1, openNodes: openNodes.slice(1) },
    applies: { state: 'ready', rows: [] }, topic: { productType: 1 } })
  assert.deepEqual(after, { taken: 1, total: 3, pending: 0, open: 2 })
})

test('F-09 自由探索(承接后才有节点):仍按章节行计,章节承接申请计入待处理', () => {
  const sum = recruitSummary({ stations: [{ state: 'taken' }, { state: 'open' }, { state: 'open' }], recruit: { nodeTotal: 0, openNodes: [] },
    chapterApps: [{ actionable: true }, { actionable: false }], applies: { rows: [] }, topic: { productType: 2 } })
  assert.deepEqual(sum, { taken: 1, total: 3, pending: 1, open: 2 })
})

test('F-09「已确认」只列已中标 / 已通过的商家,待审核的留在「收到的申请」', () => {
  const rows = [{ registrationId: 1, auditStatus: 0, status: 0 }, { registrationId: 2, auditStatus: 1, status: 0 },
    { registrationId: 3, auditStatus: 0, status: 1 }, { registrationId: 4, auditStatus: 0, status: 2 }]
  assert.deepEqual(confirmedMerchants(rows).map((r) => r.registrationId), [2, 3])
})

test('F-09 弹窗三格读同一份汇总,「已确认」读过滤后的列表', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/topic/components/project-host/index.wxml'), 'utf8')
  assert.match(wxml, /\{\{ stationSum\.taken \+ '\/' \+ stationSum\.total \}\}<\/text><text class="cs-sum-l">已招站点/)
  assert.match(wxml, /\{\{ stationSum\.pending \}\}<\/text><text class="cs-sum-l">待处理申请/)
  assert.match(wxml, /\{\{ stationSum\.open \}\}<\/text><text class="cs-sum-l">还没人接/)
  assert.match(wxml, /wx:for="\{\{ confirmedMerchants \}\}"/)
  assert.doesNotMatch(wxml, /recruitStations\.length \? stationSum/)
  const js = fs.readFileSync(path.join(ROOT, 'pages/topic/components/project-host/index.js'), 'utf8')
  assert.match(js, /recruitSum\.recruitSummary\(\{ stations: rows, chapterApps: apps, recruit, applies, topic \}\)/)
})
