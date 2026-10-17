const { test } = require('node:test')
const assert = require('node:assert/strict')
const entryMap = require('../../utils/roam-entry-map.js')

test('活动与队伍同图，活动卡关联对应招募队伍', () => {
  const items = [{ id: 7, kind: 'activity', topicId: 3, name: '预制人生', picUrl: '/cover.jpg', latitude: 31.2, longitude: 121.4 }]
  const teams = [{ teamId: 8, topicId: 3, title: '新手慢玩队', latitude: 31.21, longitude: 121.41, joinedCount: 2, maxMembers: 4, viewerHasTicket: true, memberAvatars: ['a.png', '', 'b.png'] }]
  const view = entryMap.build(items, teams, { activities: true, teams: true, posts: true }, value => value)
  assert.deepEqual(view.markers.map(row => row.id), [72, 84])
  assert.equal(view.cards[0].teams[0].title, '新手慢玩队')
  assert.deepEqual(view.cards[0].teams[0].faces, ['a.png', 'b.png'])
  assert.deepEqual(view.cards[0].participantFaces, ['a.png', 'b.png'])
  assert.equal(view.cards[0].participantCount, 2)
  assert.equal(view.cards[0].topicId, 3)
  assert.equal(view.cards[0].registered, true)
})

test('活动和招募队伍始终同时显示', () => {
  const items = [{ id: 7, kind: 'topic', name: '城市主题', latitude: 31.2, longitude: 121.4 }]
  const view = entryMap.build(items, [], { activities: true, teams: true, posts: true }, value => value)
  assert.equal(view.markers.length, 1)
  assert.equal(view.cards.length, 1)
  assert.deepEqual(entryMap.parseMarker(73), { kind: 'topic', id: 7 })
})

test('空坐标不落到几内亚湾，活动沿用主题玩法类型', () => {
  const items = [
    { id: 7, kind: 'activity', productType: 2, name: '自由探索', latitude: 31.2, longitude: 121.4 },
    { id: 8, kind: 'activity', name: '无坐标', latitude: '', longitude: null },
  ]
  const view = entryMap.build(items, [], { activities: true, teams: true, posts: true }, value => value)
  assert.equal(view.cards.length, 1)
  assert.equal(view.cards[0].tag, '自由探索')
  assert.deepEqual(view.markers.map(row => row.id), [72])
  assert.equal(view.searchPlaces[0].kind, 'free')
})

test('三个地图图层独立显隐，现场动态兼容真实图片字段', () => {
  const items = [{ id: 7, kind: 'activity', name: '预制人生', latitude: 31.2, longitude: 121.4, imageUrls: ['post.jpg'] }]
  const teams = [{ teamId: 8, activityId: 7, latitude: 31.21, longitude: 121.41 }]
  const view = entryMap.build(items, teams, { activities: false, teams: true, posts: true }, value => value)
  assert.deepEqual(view.markers.map(row => row.id), [84, 75])
  assert.deepEqual(view.cards[0].dynamicImages, ['post.jpg'])
  assert.deepEqual(entryMap.parseMarker(75), { kind: 'post', id: 7 })
})

test('任一地图图层失败都保持可见错误，不被另一路成功覆盖', () => {
  const errors = { activities: true, teams: false }
  assert.equal(entryMap.hasLayerError(errors), true)
  errors.activities = false
  assert.equal(entryMap.hasLayerError(errors), false)
})


test('结束活动保留地图位置并提示已结束，不再展示招募队伍', (t) => {
  t.mock.method(Date, 'now', () => Date.parse('2026-10-07T04:00:00Z'))
  const item = { id: 5, kind: 'activity', name: '已结束活动', latitude: 31.2, longitude: 121.4,
    startDate: '2026-02-05 08:00:00', endDate: '2026-03-28 12:00:00' }
  const teams = [{ teamId: 8, activityId: 5, latitude: 31.2, longitude: 121.4, viewerHasTicket: true }]
  const card = entryMap.build([item], teams, {}, value => value).cards[0]
  assert.equal(card.ended, true)
  assert.equal(card.registered, true)
  assert.match(card.meta, /已结束/)
  assert.deepEqual(card.teams, [])
  assert.equal(entryMap.activityView({ ...item, viewerHasTicket: true }, teams).registered, true)
  for (const endDate of ['2026-10-07 12:00:00', '2026-10-08 12:00:00', null, 'invalid']) {
    const current = entryMap.activityView({ ...item, endDate }, teams)
    assert.equal(current.ended, false)
    assert.equal(current.teams.length, 1)
  }
  assert.equal(entryMap.activityView({ ...item, kind: 'topic' }, []).ended, false)
})
