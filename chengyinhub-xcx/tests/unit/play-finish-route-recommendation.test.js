const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const PAGE_MODULE = '../../pages/play/index.js'

function setByPath(target, key, value) {
  const parts = key.split('.')
  let cursor = target
  parts.slice(0, -1).forEach((part) => {
    cursor[part] = cursor[part] || {}
    cursor = cursor[part]
  })
  cursor[parts.at(-1)] = value
}

function loadPlayPage() {
  let definition
  const requests = []
  const app = {
    globalData: { features: { finishNearbyRoute: true }, statusBarHeight: 20, navBarHeight: 44 },
    sendRequest(options) { requests.push(options) }
  }
  const previous = {
    Page: global.Page,
    getApp: global.getApp,
    wx: global.wx,
    getCurrentPages: global.getCurrentPages
  }
  try {
    global.Page = config => { definition = config }
    global.getApp = () => app
    global.getCurrentPages = () => []
    global.wx = {}
    delete require.cache[require.resolve(PAGE_MODULE)]
    require(PAGE_MODULE)
  } finally {
    global.Page = previous.Page
    global.getApp = previous.getApp
    global.wx = previous.wx
    global.getCurrentPages = previous.getCurrentPages
  }
  assert.ok(definition, '游玩页必须注册 Page')
  const page = Object.assign({}, definition, {
    data: Object.assign({}, definition.data, {
      showFinish: true,
      isPreview: false,
      isMock: false,
      activityId: 10,
      topicId: 100,
      mode: 2,
      nodes: []
    }),
    _loc: { latitude: 31.2, longitude: 121.4 },
    setData(patch, callback) {
      Object.entries(patch).forEach(([key, value]) => setByPath(this.data, key, value))
      if (callback) callback()
    }
  })
  return { page, requests }
}

test('完赛推荐灰度闸严格默认关闭，只有服务端明确 true 才开启', () => {
  const { isEnabled } = require('../../pages/play/finish-route-recommendation.js')
  assert.equal(isEnabled(), false)
  assert.equal(isEnabled({}), false)
  assert.equal(isEnabled({ finishNearbyRoute: 'true' }), false)
  assert.equal(isEnabled({ finishNearbyRoute: true }), true)
})

test('附近候选排除当前活动与同主题路线，并保留服务端距离和真实票价', () => {
  const { pickCandidate } = require('../../pages/play/finish-route-recommendation.js')
  const rows = [
    { id: 10, topicId: 100, name: '当前场次' },
    { id: 11, topicId: 100, name: '同路线另一场' },
    { id: 12, topicId: 101, name: '下一条路线', distance: '1280', minAmout: 39.9, addressName: '附近街区' },
  ]
  const candidate = pickCandidate(rows, 10, 100)
  assert.equal(candidate.id, 12)
  assert.equal(candidate.title, '下一条路线')
  assert.match(candidate.meta, /1\.3 公里/)
  assert.equal(candidate.priceText, '展示价 ¥39.9 起')
})

test('当前会话缺少可排除 ID 时仍可选择真实附近候选', () => {
  const { pickCandidate } = require('../../pages/play/finish-route-recommendation.js')
  const candidate = pickCandidate([{ id: 21, name: '附近可购路线' }], '', '')
  assert.equal(candidate.id, 21)
})

test('结算页关闭后到达的附近请求不会落卡或记录伪曝光', async () => {
  const analytics = require('../../utils/analytics.js')
  const originalTrack = analytics.track
  const events = []
  analytics.track = (name, payload) => events.push({ name, payload })
  try {
    const { page, requests } = loadPlayPage()
    const loading = page.loadFinishRouteRecommendation()
    assert.equal(requests.length, 1)
    page.closeFinish()
    requests[0].success({
      code: 200,
      data: { rows: [{ id: 12, topicId: 101, name: '下一条路线', minAmout: 39.9 }] }
    })
    const result = await loading

    assert.equal(result, null)
    assert.equal(page.data.finishRouteRecommendation, null)
    assert.equal(events.length, 0)
  } finally {
    analytics.track = originalTrack
  }
})

test('可见结算页收到候选后只记录一次真实曝光', async () => {
  const analytics = require('../../utils/analytics.js')
  const originalTrack = analytics.track
  const events = []
  analytics.track = (name, payload) => events.push({ name, payload })
  try {
    const { page, requests } = loadPlayPage()
    const loading = page.loadFinishRouteRecommendation()
    requests[0].success({
      code: 200,
      data: { rows: [{ id: 12, topicId: 101, name: '下一条路线', minAmout: 39.9 }] }
    })
    const result = await loading

    assert.equal(result.id, 12)
    assert.equal(page.data.finishRouteRecommendation.id, 12)
    assert.equal(events.length, 1)
    assert.equal(events[0].name, 'finish_route_recommendation_shown')
    assert.equal(events[0].payload.bizId, 12)
  } finally {
    analytics.track = originalTrack
  }
})

test('点击推荐卡只埋一次点击，并携候选 ID 打开活动详情场景', () => {
  const analytics = require('../../utils/analytics.js')
  const originalTrack = analytics.track
  const events = []
  const scenes = []
  analytics.track = (name, payload) => events.push({ name, payload })
  try {
    const { page } = loadPlayPage()
    page.data.finishRouteRecommendation = { id: 37, title: '可购路线' }
    page.openScene = (id, params) => scenes.push({ id, params })
    page.goFinishRouteRecommendation()

    assert.equal(events.length, 1)
    assert.equal(events[0].name, 'finish_route_recommendation_click')
    assert.equal(events[0].payload.bizId, 37)
    assert.deepEqual(scenes, [{ id: 'play-activity-detail', params: { id: 37 } }])
  } finally {
    analytics.track = originalTrack
  }
})

 test('未知距离和非法价格不伪装零距离或免费', () => {
  const { pickCandidate } = require('../../pages/play/finish-route-recommendation.js');
  for (const value of [null, '', 'bad', -1]) {
    const card = pickCandidate([{ id: 1, distance: value, minAmout: value }]);
    assert.equal(card.meta, '距离待确认');
    assert.equal(card.priceText, '价格待确认');
  }
});

test('完赛候选请求失败与真实空结果分开呈现', async () => {
  for (const [response, state] of [[{ code: 500 }, 'error'], [{ code: 200, data: { rows: [] } }, 'empty'], [{ code: 200, data: {} }, 'error']]) {
    const { page, requests } = loadPlayPage();
    const promise = page.loadFinishRouteRecommendation();
    assert.equal(page.data.finishRecommendationState, 'loading');
    requests[0].success(response);
    await promise;
    assert.equal(page.data.finishRecommendationState, state);
    assert.equal(page.data.finishRouteRecommendation, null);
  }
});

test('缺失或越界位置不变成零点附近，零经纬度数值仍合法', () => {
  const { resolveOrigin } = require('../../pages/play/finish-route-recommendation.js');
  for (const location of [{ latitude: null, longitude: null }, { latitude: 91, longitude: 121 }, { latitude: '', longitude: 121 }]) {
    assert.equal(resolveOrigin(location, []), null);
  }
  assert.deepEqual(resolveOrigin({ latitude: 0, longitude: 0 }, []), { latitude: 0, longitude: 0 });
});
