const { test } = require('node:test')
const assert = require('node:assert/strict')
const entryMap = require('../../utils/roam-entry-map.js')

test('活动与队伍同图，活动卡关联对应招募队伍', () => {
  const items = [{ id: 7, kind: 'activity', topicId: 3, name: '预制人生', picUrl: '/cover.jpg', latitude: 31.2, longitude: 121.4 }]
  const teams = [{ teamId: 8, topicId: 3, activityId: 7, title: '新手慢玩队', latitude: 31.21, longitude: 121.41, joinedCount: 2, maxMembers: 4, viewerHasTicket: true, memberAvatars: ['a.png', '', 'b.png'] }]
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
  const items = [{ id: 7, kind: 'topic', productType: 2, name: '自由探索主题', latitude: 31.2, longitude: 121.4 }]
  const view = entryMap.build(items, [], { activities: true, teams: true, posts: true }, value => value)
  assert.equal(view.markers.length, 1)
  assert.equal(view.cards.length, 1)
  assert.deepEqual(entryMap.parseMarker(73), { kind: 'topic', id: 7 })
})

test('漫游入口地图的点位、卡片、搜索结果都排除城市定向', () => {
  const point = { kind: 'topic', latitude: 31.2, longitude: 121.4, postImages: ['scene.jpg'] };
  const view = entryMap.build([
    { ...point, id: 1, productType: 1 },
    { ...point, id: 2 },
    { ...point, id: 3, productType: 2 },
  ], []);
  assert.deepEqual(view.cards.map(row => row.id), [3]);
  assert.deepEqual(view.searchPlaces.map(row => row.id), ['topic-3']);
  assert.deepEqual(view.markers.map(row => row.id), [33, 35]);
});

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

// 第十一轮 g2,j4 F6:场次卡只数本场次的队伍(队伍要本场票);主题卡才汇总各场次。
test('j4 F6: a session card counts only its own teams; the topic card counts all of them', () => {
  const teams = [
    { teamId: 1, topicId: 14, activityId: 9, latitude: 31.2, longitude: 121.4, viewerHasTicket: true },
    { teamId: 2, topicId: 14, activityId: 9, latitude: 31.2, longitude: 121.4 },
  ]
  const s9 = entryMap.activityView({ id: 9, kind: 'activity', topicId: 14, productType: 2, name: '场次9' }, teams)
  const s12 = entryMap.activityView({ id: 12, kind: 'activity', topicId: 14, productType: 2, name: '场次12' }, teams)
  const topic = entryMap.activityView({ id: 14, kind: 'topic', productType: 2, name: '主题' }, teams)
  assert.match(s9.meta, /2 支队伍招募/)
  assert.doesNotMatch(s12.meta, /队伍招募/)
  assert.equal(s12.registered, false, '别的场次的票不算本场已报名')
  assert.match(topic.meta, /2 支队伍招募/)
})

test('j4 N5:同主题的场次卡显示各自的真实开场时间', () => {
  const base = { kind: 'activity', name: '同一主题', topicId: 3 };
  const first = entryMap.activityView({ ...base, startDate: '2026-12-01 09:30:00' }, []);
  const second = entryMap.activityView({ ...base, startDate: '2026-12-02 14:00:00' }, []);
  assert.match(first.meta, /2026.12.01 09:30/);
  assert.match(second.meta, /2026.12.02 14:00/);
  assert.doesNotMatch(entryMap.activityView(base, []).meta, /NaN|1970/);
});
