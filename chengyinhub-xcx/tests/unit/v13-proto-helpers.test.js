'use strict'
// 2026-10-22 业主「和 HTML 一模一样」(v13 原型)新增的纯函数:地图点位规格、路线说明、购买小牌、主要角色、自由探索数据条
const test = require('node:test')
const assert = require('node:assert/strict')
const P = require('../../subpackageRoam/utils/map-pins.js')
const m = require('../../pages/topic/utils/topic-motion.js')
const { buildTopicStatBar } = require('../../pages/topic/utils/topic-detail-facts.js')

test('点位规格:id 与页面 marker id 同一套,地标 / 商家 / 主题 / 活动 / 队伍各自的样子', () => {
  const specs = P.pinSpecs({
    items: [{ kind: 'topic', id: 5, productType: 2, picUrl: 'a.jpg' }, { kind: 'activity', id: 11, name: '上海戏剧节·安福路', picUrl: 'b.jpg' }, { kind: 'hangout', id: 9 }],
    pois: [{ id: 3, type: 1, tags: '公园,绿地', coverImg: 'c.jpg' }, { id: 4, type: 2, tags: '', coverImg: 'd.jpg' }],
    teams: [{ teamId: 7, joinedCount: 2, maxMembers: 6, memberAvatarsJoined: 'x.jpg,y.jpg' }],
  })
  const byId = Object.fromEntries(specs.map((s) => [s.id, s]))
  assert.equal(specs.length, 5, '局(hangout)不画')
  assert.deepEqual([byId[53].kind, byId[53].tag], ['spot', '自由探索'])
  assert.equal(byId[112].tag, '上海戏剧节·', '活动名截 6 个字当小牌')
  assert.deepEqual([byId[36].kind, byId[36].tag], ['spot', '公园'])
  assert.deepEqual([byId[47].kind, byId[47].tag], ['shop', '商家'], '没有标签的商家写「商家」')
  assert.deepEqual([byId[74].kind, byId[74].count, byId[74].faces.length], ['team', '2/6', 2])
})

test('点位图替换:有图换图并去掉旧名牌,没图的 marker 原样保留', () => {
  const out = P.applyPins([{ id: 36, width: 14, height: 14, callout: { content: 'x' } }, { id: 9, width: 20 }], { 36: { icon: 'p.png', kind: 'spot' } })
  assert.equal(out[0].iconPath, 'p.png')
  assert.equal(out[0].callout, undefined)
  assert.equal(out[0].width, P.BOX.spot.w)
  assert.deepEqual(out[1], { id: 9, width: 20 })
})

test('路线说明只用已下发的数:城市定向写站数 / 里程 / 到店礼站数,自由探索写家数', () => {
  const shop = (gift) => ({ registrationMerchantList: [{ mmsMerchant: { name: 's', derivativeBenefits: gift } }] })
  const info = { productType: 1, totalMileage: 4.2, chaptersList: [{ nodes: [shop('手冲一杯'), {}] }, { nodes: [shop('')] }] }
  assert.equal(m.routeNote(info), '按顺序到站，到一站解锁下一站，全程 3 站，步行约 4.2 公里，其中 1 站有到店礼。')
  assert.equal(m.routeNote({ productType: 2 }, 6), '有效期内按任意顺序去完 6 家，每家核销一次。')
  assert.equal(m.routeNote({ productType: 1 }), '按顺序到站，到一站解锁下一站。', '没有站点与里程就不写数')
})

test('购买半屏三颗小牌 + 主要角色去重', () => {
  const chips = m.buyChips({ productType: 2, endDate: '2099-01-01' }, '4 家商家 · 任意顺序 · 约 3 小时')
  assert.equal(chips.length, 3)
  assert.match(chips[0], /^可玩至 \d\d\/\d\d$/)
  assert.equal(chips[1], '4 家商家 · 任意顺序')
  assert.equal(chips[2], '任意顺序')
  const chars = m.buildChars([{ npc: { profileId: 1, name: '沈既白', avatar: 'a' }, place: '武康大楼' }, { npc: { profileId: 2, name: '沈既白' }, place: 'x' }, { npc: { name: '' } }])
  assert.deepEqual(chars, [{ key: 'c-1', name: '沈既白', avatar: 'a', sub: '武康大楼' }])
})

test('自由探索数据条:有效期 / 参与商家 / 路线不限 / 已参加,不写预计游玩与总里程', () => {
  const cells = buildTopicStatBar({ productType: 2, averageRating: 4.7, registrationMerchantCount: 6, endDate: '2099-12-31', playerCount: 1280, totalTime: 7200, totalMileage: 3 })
  assert.deepEqual(cells.map((c) => c.key), ['rating', 'valid', 'merchant', 'route', 'players'])
  assert.equal(cells[1].value, '90', '有效期取购买后 90 天与主题结束日的较早者')
  assert.equal(cells[4].value, '1,280')
  const city = buildTopicStatBar({ productType: 1, totalTime: 7200 })
  assert.equal(city[0].key, 'hour', '城市定向数据条不变')
})
