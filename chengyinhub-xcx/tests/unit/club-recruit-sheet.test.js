'use strict'
// 2026-11-02(ROUND3B):俱乐部主题页「承接商家」半屏 = 现成组件 cy-club-topic-onboarding + recruit/overview。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const { shapeRecruitSheet } = require('../../pages/club/utils/recruit-sheet.js')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

const stationOverview = {
  topicId: 70, productType: 1, canManage: true,
  chapters: [{ chapterId: 2, name: '第二章' }, { chapterId: 1, name: '第一章' }],
  nodes: [
    { nodeId: 11, chapterId: 1, index: 1, name: '旧钟楼', gameplayName: '门楣上的年份', merchantName: '慢咖啡', state: 'APPROVED', phone: '13800000001' },
    { nodeId: 12, chapterId: 1, index: 2, name: '邮政大楼', merchantName: '书店', state: 'PENDING' },
    { nodeId: 13, chapterId: 2, index: 3, name: null, state: 'OPEN' },
  ],
}

test('站点版:进度、按章节分组、已中标/待审核、未招站点「未命名站点 · 玩法待配置」', () => {
  const v = shapeRecruitSheet(stationOverview)
  assert.equal(v.mode, 'node')
  assert.equal(v.loadState, 'ready')
  assert.deepEqual([v.doneCount, v.totalCount, v.pendingCount, v.progressPct], [1, 3, 1, 33])
  assert.deepEqual(v.sections.map((s) => [s.chapterName, s.stationCount, s.wonCount]), [['第二章', 1, 0], ['第一章', 2, 1]])
  const [won, pending] = v.sections[1].nodes
  assert.deepEqual([won.state, won.statusLabel, won.sub, won.phone, won.phoneText], ['won', '已中标', '门楣上的年份', '13800000001', '138****0001'])
  assert.deepEqual([pending.state, pending.statusLabel, pending.sub, pending.phone], ['pending', '待审核', '玩法待配置', ''])
  const open = v.sections[0].nodes[0]
  assert.deepEqual([open.state, open.name, open.merchantName], ['open', '未命名站点', ''])
})

test('未确认的商家即使回包带了号码也不显示联系方式', () => {
  const v = shapeRecruitSheet({ productType: 1, chapters: [], nodes: [{ nodeId: 1, state: 'PENDING', merchantName: 'x', phone: '13800000001' }] })
  assert.equal(v.sections[0].nodes[0].phone, '')
})

test('自由探索版:按章节卡片,招募中/未开放、家数上限、权益门槛、申请数', () => {
  const v = shapeRecruitSheet({ productType: 2, nodes: [], chapters: [
    { chapterId: 1, name: '第一章', category: '咖啡 / 轻食', recruiting: true, termsMode: 'PERK', perkMinValue: 20, maxMerchant: 3, approvedCount: 2, pendingCount: 4 },
    { chapterId: 2, name: '第二章', category: null, recruiting: false, maxMerchant: 0 },
  ] })
  assert.equal(v.mode, 'chapter')
  const [a, b] = v.chapters
  assert.deepEqual([a.statusLabel, a.category, a.tags, a.approvedCount, a.capLimit, a.thresholdText, a.pendingCount],
    ['招募中', '咖啡 / 轻食', ['平台预设条款'], 2, 3, '权益门槛 ≥ ¥20 体验权益', 4])
  assert.deepEqual([b.statusLabel, b.category, b.capLimit, b.thresholdText], ['未开放', '不限品类', 0, ''])
})

test('空数据落 empty,坏回包为 null', () => {
  assert.equal(shapeRecruitSheet({ productType: 1, nodes: [], chapters: [] }).loadState, 'empty')
  assert.equal(shapeRecruitSheet(null), null)
})

test('主题页用现成组件,动作只给能审承接的人;组件不再渲染说明句', () => {
  const wxml = read('pages/club/topic-detail/index.wxml')
  assert.match(wxml, /<cy-club-topic-onboarding[\s\S]*?can-act="\{\{canReviewChapterApplications\}\}"/)
  assert.doesNotMatch(wxml, /title="商家" closable/)
  const js = read('pages/club/topic-detail/index.js')
  assert.match(js, /openRecruitDesk\(\) \{\n    if \(!this\.data\.canReviewChapterApplications/)
  const comp = read('components/cy/club-topic-onboarding/index.wxml')
  for (const caption of ['先选要招商的节点', '自由探索 · 按章节招商', '还要各自提交节点', '开放后，同品类商家']) {
    assert.ok(!comp.includes(caption), '说明句应删除: ' + caption)
  }
  assert.match(comp, /wx:elif="\{\{canAct\}\}" class="cto__cta"/)
  assert.match(comp, /wx:if="\{\{canAct\}\}" class="cto__pool-btn"/)
  assert.match(comp, /wx:if="\{\{node\.phone\}\}" class="cto__phone"/)
})
