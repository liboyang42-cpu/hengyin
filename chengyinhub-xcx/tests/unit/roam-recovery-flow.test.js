const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const source = '../../pages/roam/index.js'
let config, app, values, calls, sessions, loseBootstrap
const SID = '9007199254740993'
const settle = async () => { for (let i = 0; i < 12; i++) await new Promise(r => setImmediate(r)) }
function mount() {
  delete require.cache[require.resolve(source)]
  require(source)
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) })
  page.setData = patch => Object.entries(patch).forEach(([key, value]) => {
    const parts = key.split('.'); let object = page.data
    parts.slice(0, -1).forEach(p => { object = object[p] || (object[p] = {}) })
    object[parts.at(-1)] = value
  })
  page._loadIntroTopics = () => {}; page._fetchShopBadgeCfg = () => {}
  page._stopClock = () => {}; page._stopReal = () => {}; page._triggerCelebration = () => {}
  page.onLoad({})
  return page
}
beforeEach(() => {
  calls = []; sessions = {}; values = {}; loseBootstrap = true
  global.Page = x => { config = x }
  app = { getUserID: () => '9', globalData: { user_id: '9', features: {} }, isDevEnv: () => false,
    sendRequest(o) {
      calls.push({ url: o.url, data: o.data })
      const key = app.getUserID() + ':' + o.data.clientSessionKey
      if (o.url === '/api/roam/reveal') {
        sessions[key] = sessions[key] || { state: 'ACTIVE', sessionId: SID, clientSessionKey: o.data.clientSessionKey }
        if (loseBootstrap) { loseBootstrap = false; o.successStatusAbnormal({ statusCode: 502 }); return }
        o.success({ code: 200, data: { sessionId: SID } }); return
      }
      if (o.url === '/api/roam/session') { o.success({ code: 200, data: sessions[key] || { state: 'NOT_FOUND' } }); return }
      if (o.url === '/api/roam/finish') { throw new Error('must not finish without an earlier ending intent') }
      o.fail && o.fail({})
    },
  }
  global.getApp = () => app
  global.wx = {
    getStorageSync: k => values[k], setStorageSync: (k, v) => { values[k] = JSON.parse(JSON.stringify(v)) },
    getRandomValues: o => o.success({ randomValues: new Uint8Array(16).fill(17).buffer }),
    getWindowInfo: () => ({ statusBarHeight: 20 }), getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    showToast() {}, hideLoading() {}, showLoading() {},
  }
})

test('漫游商家图层排除城市定向节点，保留普通店铺和自由探索商家', async () => {
  const page = mount();
  page._pois = [];
  page._resolveNearbyReady = () => {}; page._seenSweepHistory = () => {};
  page._syncMarkers = () => {}; page._syncSparkCircles = () => {};
  app.sendRequest = o => o.success({ code: 200, data: [
    { id: 1, topicId: 11, productType: 1, latitude: 31, longitude: 121 },
    { id: 2, topicId: 12, latitude: 31, longitude: 121 },
    { id: 3, latitude: 31, longitude: 121 },
    { id: 4, topicId: 14, productType: '2', latitude: 31, longitude: 121 },
  ] });
  await page._fetchNearbyMerchants({ lat: 31, lng: 121 });
  assert.deepEqual(page._pois.map(p => p.regId), [3, 4]);
});

test('结算完成后主动GO开启独立新会话，不重用已完成关联', async () => {
  loseBootstrap = false
  const page = mount(); page._pendingTiles = ['wtw3sjq']; await page._flushReveal()
  const previousKey = page._clientSessionKey
  page._pois = [{ id: 77, cat: 'merchant', name: '旧轮已探', state: 'done' }]
  page._applyRoamFinishResult({ totalXp: 1 }, true)
  wx.getRandomValues = o => o.success({ randomValues: new Uint8Array(16).fill(18).buffer })
  wx.getLocation = () => {}
  page.data.introCards = []; page.goStart()
  assert.deepEqual(page._pois, [])
  page._pendingTiles = ['wtw3sjr']; await page._flushReveal()
  assert.notEqual(page._clientSessionKey, previousKey)
  assert.equal(Object.keys(sessions).length, 2)
  assert.equal(calls.filter(c => c.url === '/api/roam/reveal').at(-1).data.sessionId, '0')
})

test('首批确认后本机保存失败不得结算，恢复存储后仍用同会话排空再完成', async () => {
  loseBootstrap = false
  const original = app.sendRequest
  let failStorage = true
  const write = wx.setStorageSync
  wx.setStorageSync = (key, value) => {
    if (failStorage && key.endsWith(':recovery') && value && value.sessionId) throw new Error('quota')
    write(key, value)
  }
  app.sendRequest = o => {
    if (o.url !== '/api/roam/finish') return original(o)
    calls.push({ url: o.url, data: o.data }); o.success({ code: 200, data: { totalXp: 1 } })
  }
  const page = mount(); page._pendingTiles = ['wtw3sjq']; page._pois = []
  page.data.screen = 'map'; page._arrive(); await settle()
  assert.equal(calls.filter(c => c.url === '/api/roam/finish').length, 0)
  assert.deepEqual(values['roam_memory_v1:9:recovery'].pendingTiles, ['wtw3sjq'])
  failStorage = false
  const cold = mount(); await settle()
  assert.equal(cold.data.finish.xpAwarded, 1)
  assert.equal(calls.filter(c => c.url === '/api/roam/finish').length, 1)
  assert.equal(Object.keys(sessions).length, 1)
})

test('恢复确认期间重复点击只发送一个查询，未返回前不写', async () => {
  const page = mount(); page._pendingTiles = ['wtw3sjq']; await page._flushReveal()
  let query
  const original = app.sendRequest
  app.sendRequest = o => {
    if (o.url !== '/api/roam/session') return original(o)
    calls.push({ url: o.url, data: o.data }); query = o
  }
  page._recoveryNeedsLookup = true
  const first = page.recoverRoam(); const second = page.recoverRoam()
  assert.equal(first, second)
  assert.equal(calls.filter(c => c.url === '/api/roam/session').length, 1)
  assert.equal(calls.filter(c => c.url === '/api/roam/reveal').length, 1)
  query.success({ code: 200, data: sessions[Object.keys(sessions)[0]] }); await first
  assert.deepEqual(page._pendingTiles, [])
})

test('零格子尚无服务端会话时保留本机记录并可主动继续，不虚报结算', async () => {
  const page = mount(); page._pois = []; page.data.screen = 'map'
  page._arrive(); await settle()
  assert.equal(calls.filter(c => c.url === '/api/roam/finish').length, 0)
  assert.equal(page.data.recoveryCanContinue, true)
  assert.equal(page.data.finish.xpAwarded, undefined)
  page.continueUnstartedRoam()
  assert.equal(page._finishRequested, false)
  assert.equal(page.data.screen, 'entry-map', '没有可恢复格子时回到新的真实地图入口，不直接进入旧 intro')
  assert(values['roam_memory_v1:9:recovery'])
  loseBootstrap = false; page._pendingTiles = ['wtw3sjq']; await page._flushReveal()
  assert.equal(Object.keys(sessions).length, 1)
})

test('历史存档写失败后结算事实仍保留恢复记录，修复存储后重建本次存档', async () => {
  loseBootstrap = false
  const write = wx.setStorageSync
  let failArchive = true
  wx.setStorageSync = (key, value) => {
    if (failArchive && key.endsWith(':sessions')) throw new Error('archive unavailable')
    write(key, value)
  }
  const original = app.sendRequest
  app.sendRequest = o => {
    if (o.url !== '/api/roam/finish') return original(o)
    calls.push({ url: o.url, data: o.data })
    sessions[Object.keys(sessions)[0]].state = 'FINISHED'
    sessions[Object.keys(sessions)[0]].result = { totalXp: 1 }
    sessions[Object.keys(sessions)[0]].resultComplete = true
    o.success({ code: 200, data: { totalXp: 1 } })
  }
  const page = mount(); page._pois = []; page._pendingTiles = ['wtw3sjq']; page.data.screen = 'map'
  page._arrive(); await settle()
  assert(values['roam_memory_v1:9:recovery'])
  failArchive = false
  const cold = mount(); cold._pois = []; await settle()
  assert.equal(values['roam_memory_v1:9:sessions'][0].xpAwarded, 1)
  assert.equal(values['roam_memory_v1:9:sessions'][0].sessionId, SID)
  assert.equal(values['roam_memory_v1:9:recovery'], null)
  assert.equal(calls.filter(c => c.url === '/api/roam/finish').length, 1)
})

test('首次reveal已提交但丢回包，冷重进精确找回同sid并确认原格子，不自动结束', async () => {
  const first = mount(); first._pendingTiles = ['wtw3sjq']
  await first._flushReveal(); await settle()
  const second = mount(); await settle()
  assert.equal(second._roamSid, SID)
  assert.deepEqual(second._pendingTiles, [])
  assert.equal(Object.keys(sessions).length, 1)
  assert.equal(calls.filter(c => c.url === '/api/roam/finish').length, 0)
  assert(calls.some(c => c.url === '/api/roam/session'))
})

test('已有结束意图在冷重进后先确认原格子，再只执行一次结算', async () => {
  const original = app.sendRequest
  app.sendRequest = o => {
    if (o.url !== '/api/roam/finish') return original(o)
    calls.push({ url: o.url, data: o.data })
    o.success({ code: 200, data: { totalXp: 7 } })
  }
  const first = mount(); first._pendingTiles = ['wtw3sjq']; first._pois = []; first._dist = 10
  first.data.screen = 'map'; first._arrive(); await settle()
  const second = mount(); await settle()
  assert.equal(calls.filter(c => c.url === '/api/roam/finish').length, 1)
  assert.equal(second.data.finish.xpAwarded, 7)
})

test('finish已提交但timeout，冷重进只刷新原结果，不重复奖励请求', async () => {
  loseBootstrap = false
  const original = app.sendRequest
  app.sendRequest = o => {
    if (o.url !== '/api/roam/finish') return original(o)
    calls.push({ url: o.url, data: o.data })
    const key = Object.keys(sessions)[0]
    sessions[key] = { ...sessions[key], state: 'FINISHED', resultComplete: true, badgeDelivery: [{ badgeCode: 'ROAM_TILES_50', state: 'DELIVERED' }], result: { totalXp: 7, medal: '城市点亮 50 格' } }
    o.fail({ errMsg: 'request:fail timeout' })
  }
  const first = mount(); first._pendingTiles = ['wtw3sjq']; first._pois = []; first._dist = 10
  first.data.screen = 'map'; first._arrive(); await settle()
  assert.equal(first.data.finish.settleErr, 'unknown')
  const second = mount(); await settle()
  assert.equal(second.data.finish.xpAwarded, 7)
  assert.equal(second.data.finish.medal, '城市点亮 50 格')
  assert.equal(calls.filter(c => c.url === '/api/roam/finish').length, 1)
})

test('存储失败时不发首笔写，请求中的失败不覆盖已保存key', async () => {
  wx.setStorageSync = () => { throw new Error('quota') }
  const page = mount(); page._pendingTiles = ['wtw3sjq']
  assert.equal(await page._flushReveal(), false)
  assert.equal(calls.filter(c => c.url === '/api/roam/reveal').length, 0)
  assert.deepEqual(page._pendingTiles, ['wtw3sjq'])
  assert.match(page.data.recoveryError, /保存/)
})

test('确认网络失败不当NOT_FOUND，不重新创建且保留原格子', async () => {
  const first = mount(); first._pendingTiles = ['wtw3sjq']; await first._flushReveal(); await settle()
  const original = app.sendRequest
  app.sendRequest = o => o.url === '/api/roam/session' ? o.fail({}) : original(o)
  const second = mount(); await settle()
  assert.deepEqual(second._pendingTiles, ['wtw3sjq'])
  assert.equal(calls.filter(c => c.url === '/api/roam/reveal').length, 1)
  assert.match(second.data.recoveryError, /确认/)
})

for (const mode of ['timeout', 'missingSid']) test('首次' + mode + '后精确查询再重放同key，不盲建会话', async () => {
  const original = app.sendRequest
  app.sendRequest = o => {
    if (o.url === '/api/roam/reveal' && loseBootstrap) {
      o.successStatusAbnormal = () => mode === 'timeout' ? o.fail({}) : o.success({ code: 200, data: {} })
      return original(o)
    }
    return original(o)
  }
  const first = mount(); first._pendingTiles = ['wtw3sjq']; await first._flushReveal(); await settle()
  assert.equal(first._revealBootstrapUnknown, true)
  const second = mount(); await settle()
  assert.equal(second._roamSid, SID)
  assert.equal(Object.keys(sessions).length, 1)
})

test('常驻页切号后隔离恢复记录，切回原账号还能继续原会话', async () => {
  const page = mount(); page._pendingTiles = ['wtw3sjq']; await page._flushReveal(); await settle()
  page._pois = [{ id: 77, cat: 'merchant', name: '前账号已探', state: 'done' }]
  app.getUserID = () => '10'; page.onShow(); await settle()
  assert.equal(page._roamSid, 0)
  assert.deepEqual(page._pendingTiles, [])
  assert.equal(page._roamMemory.playerId, '10')
  assert.deepEqual(page._pois, [])
  app.getUserID = () => '9'; page.onShow(); await settle()
  assert.equal(page._roamSid, SID)
  assert.deepEqual(page._pendingTiles, [])
})

test('Standards: ACTIVE恢复后新增距离再次冷进必须保留', async () => {
  loseBootstrap = false
  const first = mount(); first._pendingTiles = ['wtw3sjq']; first._dist = 10
  await first._flushReveal()
  const second = mount(); await settle()
  second._dist = 50; second._pendingTiles = ['wtw3sjr']; await second._flushReveal()
  const third = mount(); await settle()
  assert.equal(third._dist, 50)
})
test('Standards: 未知首批后结束且lookup失败仍持久化结束意图', async () => {
  const page = mount(); page._pendingTiles = ['wtw3sjq']; await page._flushReveal()
  const original = app.sendRequest
  app.sendRequest = o => o.url === '/api/roam/session' ? o.fail({errMsg:'timeout'}) : original(o)
  page.data.screen = 'map'; page._pois = []; page._arrive(); await settle()
  assert.equal(values['roam_memory_v1:9:recovery'].finishRequested, true)
  app.sendRequest = o => {
    if (o.url !== '/api/roam/finish') return original(o)
    calls.push({ url: o.url, data: o.data }); o.success({ code: 200, data: { totalXp: 1 } })
  }
  const cold = mount(); await settle()
  assert.equal(cold.data.finish.xpAwarded, 1)
  assert.equal(calls.filter(c => c.url === '/api/roam/finish').length, 1)
  assert.equal(Object.keys(sessions).length, 1)
})
test('Standards: 足迹存档静默不写时不得清除唯一恢复记录', async () => {
  loseBootstrap = false
  const write = wx.setStorageSync
  wx.setStorageSync = (key,value) => { if (!key.endsWith(':sessions')) write(key,value) }
  const original = app.sendRequest
  app.sendRequest = o => {
    if (o.url !== '/api/roam/finish') return original(o)
    calls.push({ url: o.url, data: o.data })
    Object.assign(sessions[Object.keys(sessions)[0]], { state: 'FINISHED', resultComplete: true, result: { totalXp: 1 } })
    o.success({code:200,data:{totalXp:1}})
  }
  const page = mount(); page._pendingTiles = ['wtw3sjq']; page._pois=[]; page.data.screen='map'
  page._arrive(); await settle()
  assert.ok(values['roam_memory_v1:9:recovery'])
  wx.setStorageSync = write
  mount(); await settle()
  assert.equal(values['roam_memory_v1:9:sessions'][0].xpAwarded, 1)
  assert.equal(values['roam_memory_v1:9:sessions'][0].sessionId, SID)
  assert.equal(values['roam_memory_v1:9:recovery'], null)
  assert.equal(calls.filter(c => c.url === '/api/roam/finish').length, 1)
})

test('Spec: ACTIVE恢复后真实GO初始化及异步商家刷新保留本次done，再结束存档不丢', async () => {
  loseBootstrap = false
  const first = mount(); first._pendingTiles = ['wtw3sjq']
  first._pois = [{ id: 77, cat: 'merchant', name: '已探店', nameKnown: true, state: 'done', lat: 31, lng: 121 }]
  await first._flushReveal()
  const page = mount(); await settle()
  const original = app.sendRequest
  app.recordConsent = () => Promise.resolve()
  app.sendRequest = o => {
    if (o.url === '/api/map/nearby') {
      o.success({ code: 200, data: [{ nodeId: 77, id: 1, addressName: '已探店', latitude: 31, longitude: 121 }] }); return
    }
    if (o.url === '/api/roam/finish') { calls.push({ url: o.url, data: o.data }); o.success({ code: 200, data: { totalXp: 1 } }); return }
    return original(o)
  }
  page._disposeFogRenderer = () => {}; page._mapLoadWatch = () => {}
  page._genIcons = () => Promise.resolve(); page._syncMarkers = () => {}; page._syncSparkCircles = () => {}
  page._fetchRoamTiles = () => {}; page._fetchRoamPois = () => {}; page._seenSweepHistory = () => {}
  wx.getLocation = o => o.success({ latitude: 31, longitude: 121 })
  page.data.introCards = []; page.goStart(); clearTimeout(page._roamOpeningTimer); clearTimeout(page._fogOpenTimer); await settle()
  const done = page._pois.filter(p => p.state === 'done')
  assert.equal(done.length, 1)
  assert.equal(done[0].id, 77)
  page._saveRoamRecovery()
  assert.equal(values['roam_memory_v1:9:recovery'].pois.length, 1)
  page.data.screen = 'map'; page._arrive(); await settle()
  assert.equal(page.data.finish.shops, 1)
  assert.equal(values['roam_memory_v1:9:sessions'][0].pois[0].id, 77)
  assert.equal(calls.filter(c => c.url === '/api/roam/finish').length, 1)
})

test('Standards v2: 切号后的旧POI读取不能把旧账号found带入新账号地图', async () => {
  const page = mount(); let oldRead
  const original = app.sendRequest
  app.sendRequest = o => {
    if (o.url === '/api/roam/pois') { oldRead=o; return }
    return original(o)
  }
  page._genIcons=()=>Promise.resolve(); page._syncMarkers=()=>{}; page._syncSparkCircles=()=>{}; page._syncGoal=()=>{}
  page._fetchRoamPois({lat:31,lng:121}); await settle()
  assert.ok(oldRead)
  app.getUserID=()=> '10'; page.onShow(); await settle()
  assert.equal(page._roamMemory.playerId,'10')
  oldRead.success({code:200,data:[{id:77,type:1,name:'old account found',lat:31,lng:121,found:true}]}); await settle()
  assert.equal(page._pois.some(p=>p.id==='r77'),false)
})

for (const boundary of ['account', 'newSession']) test('等待nearby时' + boundary + '变化不再发旧POI请求，当前会话可正常重试', async () => {
  const page = mount(); let resolveNearby; const pending = []
  page._nearbyReady = new Promise(resolve => { resolveNearby = resolve })
  const original = app.sendRequest
  app.sendRequest = o => { if (o.url === '/api/roam/pois') { pending.push(o); return } return original(o) }
  page._genIcons = () => Promise.resolve(); page._syncMarkers = () => {}; page._syncSparkCircles = () => {}; page._syncGoal = () => {}
  page._fetchRoamPois({ lat: 31, lng: 121 })
  if (boundary === 'account') { app.getUserID = () => '10'; page.onShow() } else page._resetRoamRecoverySession()
  resolveNearby(); await settle(); assert.equal(pending.length, 0)
  page._nearbyReady = Promise.resolve(); page._fetchRoamPois({ lat: 31, lng: 121 }); await settle()
  pending[0].fail({ errMsg: 'timeout' }); await settle(); assert.equal(page.data.poiOffline, undefined, '读取失败不再画离线横幅'); assert.equal(page._pois.length, 0)
  page._fetchRoamPois({ lat: 31, lng: 121 }); await settle()
  pending[1].success({ code: 200, data: [{ id: 88, type: 1, name: '当前地点', lat: 31, lng: 121, found: false }] }); await settle()
  // 2026-09-16 C-09:真 POI 的 marker id 从字符串 'r88' 改成数字段 700000+id(微信 <map> 数字契约)
  assert.equal(page.data.poiOffline, undefined); assert.equal(page._pois[0].id, 700088); assert.equal(page._pois[0]._roamId, 88); assert.equal(page._pois[0].state, 'fog')
})

for (const route of ['/api/map/nearby', '/api/roam/tiles/page']) test('同初始化链旧' + route + '回包不得改新账号地图或本机桶', async () => {
  const page = mount(); let oldRead
  page._pois = []; page._localTiles = {}; page._syncMarkers = () => {}; page._syncSparkCircles = () => {}
  const original = app.sendRequest
  app.sendRequest = o => { if (o.url === route) { oldRead = o; return } return original(o) }
  if (route === '/api/map/nearby') page._fetchNearbyMerchants({ lat: 31, lng: 121 }); else page._fetchRoamTiles()
  await settle(); app.getUserID = () => '10'; page.onShow(); await settle()
  page._localTiles = { newAccountOnly: 1 }
  if (route === '/api/map/nearby') oldRead.success({ code: 200, data: [{ nodeId: 77, latitude: 31, longitude: 121, addressName: '旧店' }] })
  else oldRead.success({ code: 200, data: { tiles: ['wtw3sjq'], nextAfterId: 1, hasMore: false } })
  await settle()
  assert.deepEqual(page._pois, [])
  assert.deepEqual(page._localTiles, { newAccountOnly: 1 })
  assert.equal(JSON.stringify(values).includes('newAccountOnly'), false)
})

test('同账号主动新一轮后旧POI回包不能填入passed或覆盖新轮加载状态', async () => {
  const page = mount(); let oldRead
  const original = app.sendRequest
  app.sendRequest = o => { if (o.url === '/api/roam/pois') { oldRead = o; return } return original(o) }
  page._fetchRoamPois({ lat: 31, lng: 121 }); await settle()
  page._roamCompleted = true; page.data.introCards = []; wx.getLocation = () => {}; page.goStart()
  page.setData({ poiOffline: true })
  oldRead.success({ code: 200, data: [{ id: 77, type: 1, name: '旧轮地点', lat: 31, lng: 121, found: true }] }); await settle()
  assert.deepEqual(page._pois, [])
  assert.equal(page.data.poiOffline, true, '旧回包不能改写新轮的哨兵数据')
})

test('换账号清空入口缓存和弹层，旧回包不能恢复上一账号入口', async () => {
  const page = mount();
  const pending = [];
  app.sendRequest = options => pending.push(options);
  page._entryItems = [{ id: 1, kind: 'activity', registered: true }];
  page._entryTeams = [{ teamId: 2 }];
  page.setData({ entryCards: [{ id: 1 }], entryActivity: { registered: true }, entryTeam: { teamId: 2 }, entrySheet: 'activity' });
  page._loadEntryMap({ lat: 31, lng: 121 });
  app.getUserID = () => '10';
  page._resetRoamRecoverySession();
  pending[0].success({ code: 200, data: { items: [{ id: 1, kind: 'activity', registered: true }] } });
  pending[1].success({ code: 200, data: [{ teamId: 2 }] });
  await settle();
  assert.deepEqual(page._entryItems, []);
  assert.deepEqual(page._entryTeams, []);
  assert.deepEqual(page.data.entryCards, []);
  assert.equal(page.data.entryActivity, null);
  assert.equal(page.data.entryTeam, null);
  assert.equal(page.data.entrySheet, '');
});


test('同一漫游页面重建后保留累计用时，旧快照也不会归零', async () => {
  const { createSessionClock } = require('../../utils/play-ui-contract.js')
  const originalNow = Date.now
  let now = 100000
  Date.now = () => now
  let restored
  try {
    loseBootstrap = false
    const first = mount(); first._pendingTiles = ['wtw3sjq']; await first._flushReveal()
    first._sessionClock = createSessionClock(() => now); first._sessionClock.start()
    now += 66000
    first._stats.time = '01:06'; first.onHide()
    const cold = mount(); await settle()
    cold._syncStats = () => {}; cold._startClock(); restored = cold
    assert.equal(cold._sessionClock.elapsedSeconds(), 66)
    now += 4000
    assert.equal(cold._sessionClock.elapsedSeconds(), 70)
    assert.equal(cold._roamSid, SID)
    assert.equal(Object.keys(sessions).length, 1)
    clearInterval(cold._clock)
    const legacy = values['roam_memory_v1:9:recovery']
    delete legacy.elapsedSeconds; legacy.stats.time = '02:05'
    const old = mount(); await settle()
    old._syncStats = () => {}; old._startClock(); restored = old
    assert.equal(old._sessionClock.elapsedSeconds(), 125)
  } finally {
    if (restored) clearInterval(restored._clock)
    Date.now = originalNow
  }
})

test('游客附近入口可点登录，不发送两条私有读取或制造附近无活动', async () => {
  app.getUserID = () => '';
  const page = mount(); await settle();
  assert.equal(calls.filter(c => ['/api/roam/entry/nearby', '/api/team/nearby'].includes(c.url)).length, 0);
  assert.equal(page.data.entryReadAccess, 'login');
  app.firstLogin = () => { app.getUserID = () => '9'; return Promise.resolve({ ok: true }); };
  await page.loginForEntryRead(); await settle();
  assert.equal(calls.filter(c => ['/api/roam/entry/nearby', '/api/team/nearby'].includes(c.url)).length, 2);
});
test('附近读取401/403明确区分；普通500不伪装无权限', async () => {
  for (const code of [401, 403, 500]) {
    const page = mount(); await settle();
    app.sendRequest = o => o.success({ code });
    page._loadEntryMap({ lat: 31, lng: 121 }); await settle();
    assert.equal(page.data.entryReadAccess, code === 401 ? 'login' : code === 403 ? 'denied' : '');
    assert.equal(page.data.entryLoadError, code === 500);
  }
});
test('旧账号附近权限回包不覆盖新读取；登录在页面退出后完成不继续读', async () => {
  const page = mount(); await settle(); const pending=[];
  app.sendRequest=o=>pending.push(o); page._loadEntryMap({lat:31,lng:121});
  app.getUserID=()=> '10'; page._loadEntryMap({lat:31,lng:121});
  pending[0].success({code:401});pending[1].success({code:403});await settle();
  assert.equal(page.data.entryReadAccess,'');
  pending[2].success({code:200,data:{items:[]}});pending[3].success({code:200,data:[]});await settle();
  assert.equal(page.data.entryLoadError,false);
  // 2026-10-28 p74:附近为空时会再读一次 20km 兜底(第 5 条);这里只锁「页面退出后登录不再读」
  const before=pending.length;
  let resolve;app.getUserID=()=>'';app.firstLogin=()=>new Promise(r=>{resolve=r;});
  const login=page.loginForEntryRead();page._unloaded=true;app.getUserID=()=> '10';resolve({ok:true});
  await login;assert.equal(pending.length,before);
});


test('入口保留真实HTTP401/403与续期失败状态，不将网关500或断网误判权限', async () => {
  for (const callback of ['successStatusAbnormal','fail']) for (const code of [401,403,500]) {
    const page=mount();await settle();
    app.sendRequest=o=>o[callback]({code:500},code);
    page._loadEntryMap({lat:31,lng:121});await settle();
    assert.equal(page.data.entryReadAccess,code===401?'login':code===403?'denied':'');
    assert.equal(page.data.entryLoadError,code===500);
  }
});

test('C3 tiles落盘失败保dirty，重试成功只清相同revision', () => {
  const page = mount(); page._localTiles = { wtw3sjq: 1 }; page._tilesDirty = 1; page._tilesRevision = 1
  const write = wx.setStorageSync
  wx.setStorageSync = () => { throw new Error('quota') }
  assert.equal(page._persistTiles(), false); assert.equal(page._tilesDirty, 1)
  wx.setStorageSync = (key, value) => {
    write(key, value)
    if (key.endsWith(':tiles')) { page._tilesRevision = 2; page._tilesDirty = 2; page._localTiles.wtw3sjr = 1 }
  }
  assert.equal(page._persistTiles(), true); assert.equal(page._tilesDirty, 2)
  wx.setStorageSync = write
  assert.equal(page._persistTiles(), true); assert.equal(page._tilesDirty, 0)
  assert.deepEqual(values['roam_memory_v1:9:tiles'].tiles, { wtw3sjq: 1, wtw3sjr: 1 })
})

test('C3静默落盘不算成功，dirty保留', () => {
  const page = mount(); page._localTiles = { wtw3sjq: 1 }; page._tilesDirty = 1
  wx.setStorageSync = () => {}
  assert.equal(page._persistTiles(), false); assert.equal(page._tilesDirty, 1)
})

for (const route of ['/api/roam/reveal', '/api/roam/session', '/api/roam/tiles/page', '/api/roam/finish']) {
  test('C3清缓存使旧' + route + '迟到回包失效，hide不重建恢复', async () => {
    loseBootstrap = false
    const page = mount(); page._pendingTiles = ['wtw3sjq']; await page._flushReveal()
    const oldSid = page._roamSid
    let inFlight
    const original = app.sendRequest
    app.sendRequest = o => { if (o.url === route) { inFlight = o; return } return original(o) }
    let pending
    if (route === '/api/roam/reveal') { page._pendingTiles = ['wtw3sjr']; pending = page._flushReveal() }
    if (route === '/api/roam/session') { page._recoveryNeedsLookup = true; pending = page.recoverRoam() }
    if (route === '/api/roam/tiles/page') pending = page._fetchRoamTiles()
    if (route === '/api/roam/finish') pending = page._postRoamFinish(20)
    await settle(); assert.ok(inFlight)
    assert.equal(page._roamMemory.clearLocal(), true)
    assert.deepEqual(page._pendingTiles, [])
    inFlight.success({ code: 200, data: route === '/api/roam/session'
      ? { state: 'FINISHED', sessionId: SID, clientSessionKey: page._clientSessionKey, result: { totalXp: 100 }, resultComplete: true }
      : route === '/api/roam/tiles/page' ? { tiles: ['wtw3sjr'], nextAfterId: 1, hasMore: false }
      : route === '/api/roam/finish' ? { totalXp: 100 } : { sessionId: SID } })
    await pending; page._sessionClock = { elapsedSeconds: () => 1, isPaused: () => true }; page.onHide(); await settle()
    assert.deepEqual(values['roam_memory_v1:9:tiles'].tiles, {})
    assert.equal(values['roam_memory_v1:9:recovery'], null)
    assert.deepEqual(values['roam_memory_v1:9:sessions'], [])
    assert.deepEqual(page._pendingTiles, [])
    assert.equal(page._roamSid, oldSid, 'known active original SID is retained for fresh collection')
    const cold = mount(); await settle(); assert.deepEqual(cold._pendingTiles, []); assert.equal(cold._roamSid, 0)
    assert.equal(Object.keys(sessions).length, 1, 'server history retained')
  })
}

test('C3清缓存后活动行程只保存新采集，不找回旧pending', async () => {
  loseBootstrap = false
  const page = mount(); page._pendingTiles = ['wtw3sjq']; await page._flushReveal()
  const sid = page._roamSid; page._pendingTiles = ['wtw3sjr']
  page._roamMemory.clearLocal()
  page._accumulateTile(31.2, 121.5); clearTimeout(page._revealTimer); clearTimeout(page._tilePersistTimer); await settle()
  assert.equal(page._roamSid, sid)
  assert.equal(page._pendingTiles.includes('wtw3sjr'), false)
  assert.deepEqual(values['roam_memory_v1:9:recovery'].pendingTiles, page._pendingTiles)
})

test('C3原key响应另一个SID不得更换原SID或补传', async () => {
  loseBootstrap = false
  const page = mount(); page._pendingTiles = ['wtw3sjq']; await page._flushReveal()
  sessions[Object.keys(sessions)[0]].sessionId = '9007199254740994'
  page._recoveryNeedsLookup = true; assert.equal(await page.recoverRoam(), false)
  assert.equal(page._roamSid, SID); assert.match(page.data.recoveryError, /关联/)
})

test('C7同意回执在停止后迟到不得开启定位', async () => {
  const page = mount(); let resolveConsent; let starts = 0
  app.recordConsent = () => new Promise(resolve => { resolveConsent = resolve })
  const modal = require('../../utils/modal.js')
  const originalShow = modal.show
  modal.show = options => options.success({ confirm: true })
  wx.startLocationUpdate = () => { starts++ }
  try {
    page._startReal(); config._stopReal.call(page)
    resolveConsent(); await settle()
    assert.equal(starts, 0); assert.equal(page._realOn, false); assert.equal(page._realStarting, false)
  } finally { modal.show = originalShow }
})

test('C7异常跳点不改player，停止后的已排队定位回调不再记轨迹', () => {
  const page = mount(); page.data.screen = 'map'; page._realOn = true
  page._realAnchor = { lat: 31.2, lng: 121.5, ts: Date.now() - 1000 }; page._player = { lat: 31.2, lng: 121.5 }
  page._realTick({ latitude: NaN, longitude: 121.5 })
  page._realTick({ latitude: 40, longitude: 121.5 })
  assert.deepEqual(page._player, { lat: 31.2, lng: 121.5 }); assert.deepEqual(page._track, [])
  config._stopReal.call(page)
  page._realTick({ latitude: 31.2001, longitude: 121.5 })
  assert.deepEqual(page._track, [])
})

test('C3结束补传使用原SID的独立记录接口，重复/丢响应不结算发奖励', async () => {
  loseBootstrap = false
  const page = mount(); page._pendingTiles = ['wtw3sjq']; await page._flushReveal()
  page._pendingTiles = ['wtw3sjr']; page._pendingCollectedAt = { wtw3sjr: 1234 }; page._saveRoamRecovery()
  Object.assign(sessions[Object.keys(sessions)[0]], { state: 'FINISHED', resultComplete: true, result: { totalXp: 7 } })
  const original = app.sendRequest; let lose = true; const archive = new Set()
  app.sendRequest = o => {
    if (o.url !== '/api/roam/session/records') return original(o)
    calls.push({ url: o.url, data: o.data }); assert.equal(o.data.sessionId, SID)
    const records = JSON.parse(o.data.records); assert.deepEqual(records, [{ tile: 'wtw3sjr', collectedAt: 1234 }])
    records.forEach(r => archive.add(r.tile))
    if (lose) { lose = false; o.fail({}); return }
    o.success({ code: 200, data: { sessionId: SID, acceptedRecords: 1, evidenceType: 'CLIENT_REPORTED' } })
  }
  const cold = mount(); await settle(); assert.deepEqual(cold._pendingTiles, ['wtw3sjr'])
  assert.equal(values['roam_memory_v1:9:recovery'].sessionId, SID)
  await cold.recoverRoam(); assert.equal(cold.data.finish.xpAwarded, 7); assert.equal(archive.size, 1)
  assert.equal(values['roam_memory_v1:9:recovery'], null)
  assert.equal(calls.filter(c => c.url === '/api/roam/finish').length, 0)
  assert.equal(calls.filter(c => c.url === '/api/roam/reveal').length, 1)
})


test('C3旧记录缺采集时间不伪造时间，保原pending供确认', async () => {
  loseBootstrap = false
  const page = mount(); page._pendingTiles = ['wtw3sjq']; await page._flushReveal()
  page._pendingTiles = ['wtw3sjr']; page._saveRoamRecovery()
  Object.assign(sessions[Object.keys(sessions)[0]], { state: 'FINISHED', resultComplete: true, result: { totalXp: 7 } })
  const cold = mount(); await settle()
  assert.deepEqual(cold._pendingTiles, ['wtw3sjr']); assert.match(cold.data.recoveryError, /采集时间缺失/)
  assert.equal(calls.filter(c => c.url === '/api/roam/session/records').length, 0)
})


test('M0116已成功结算的待交付徽章不伪报到手，独立刷新DELIVERED后显示原徽章', () => {
  const page = mount(); page._roamSid = SID
  const result = { totalXp: 7, medal: '城市点亮 50 格', shopMedal: { code: 'ROAM_SHOP_STREAK', name: '原三店徽章' },
    badgeDelivery: [{ badgeCode: 'ROAM_TILES_50', state: 'DUE' }, { badgeCode: 'ROAM_SHOP_STREAK', state: 'DUE' }] }
  assert.equal(page._applyRoamFinishResult(result, true), true)
  assert.equal(page.data.finish.xpAwarded, 7); assert.equal(page.data.finish.medal, ''); assert.equal(page.data.finish.shopMedal, null)
  assert.equal(page._applyRoamFinishResult(result, true, result.badgeDelivery.map(r => ({ ...r, state: 'DELIVERED' }))), true)
  assert.equal(page.data.finish.medal, '城市点亮 50 格'); assert.equal(page.data.finish.shopMedal.name, '原三店徽章')
  assert.equal(calls.filter(c => c.url === '/api/roam/finish').length, 0)
})


test('M0116阈值和DUE不能弹获得徽章，只有原DELIVERED收据可弹', () => {
  const page = mount(); page._shopBadgeCfg = { name: 'pending badge' }
  assert.equal(page._popMedal(), false); assert.equal(page.data.medal.show, false)
  page._shopMedalFromServer = true; page.data.finish.shopMedal = { name: '原已交付徽章', statement: '原文案' }
  assert.equal(page._popMedal(), true); assert.equal(page.data.medal.name, '原已交付徽章')
})


test('M0116缺原徽章交付状态是未核，不当已获得', () => {
  const page = mount()
  assert.equal(page._applyRoamFinishResult({ totalXp: 7, medal: '未核徽章', shopMedal: { code: 'ROAM_SHOP_STREAK', name: '未核店章' } }, true), true)
  assert.equal(page.data.finish.medal, ''); assert.equal(page.data.finish.shopMedal, null); assert.equal(page._shopMedalFromServer, false)
  assert.equal(page._popMedal(), false)
})

test('C3补传回执在清缓存后迟到不得重建恢复或结算展示', async () => {
  const page = mount(); page._roamSid = SID; page._clientSessionKey = '1'.repeat(32)
  page._pendingTiles = ['wtw3sjr']; page._pendingCollectedAt = { wtw3sjr: 1234 }; page._saveRoamRecovery()
  let archive
  app.sendRequest = o => {
    calls.push({ url: o.url, data: o.data })
    if (o.url === '/api/roam/session') o.success({ code: 200, data: { state: 'FINISHED', sessionId: SID,
      clientSessionKey: page._clientSessionKey, resultComplete: true, result: { totalXp: 7 } } })
    else if (o.url === '/api/roam/session/records') archive = o
  }
  const pending = page.recoverRoam(); await settle(); assert(archive)
  require('../../utils/roam-player-memory').createPlayerRoamMemory('9', wx).clearLocal()
  archive.success({ code: 200, data: { sessionId: SID, acceptedRecords: 1, evidenceType: 'CLIENT_REPORTED' } })
  assert.equal(await pending, false)
  assert.equal(values['roam_memory_v1:9:recovery'], null)
  assert.equal(page.data.finish.xpAwarded, undefined)
  assert.deepEqual(page._pendingTiles, [])
})

test('C3结束恢复保留过期位置清理，徽章待交付刷新也不得还原几何', async () => {
  const page = mount(); page._roamSid = SID; page._clientSessionKey = '1'.repeat(32)
  page._track = [{ lat: 31, lng: 121 }]; page._sessionTiles = { wtw3sjq: 1 }; page._pendingTiles = []
  page._recoveryArchiveTs = 1234
  values['roam_memory_v1:9:sessions'] = [{ ts: 1234, track: page._track, tiles: ['wtw3sjq'] }]
  app.sendRequest = o => {
    calls.push({ url: o.url, data: o.data })
    assert.equal(o.url, '/api/roam/session')
    o.success({ code: 200, data: { state: 'FINISHED', sessionId: SID, clientSessionKey: page._clientSessionKey,
      locationExpired: true, finishedAt: 1234, resultComplete: true, result: { totalXp: 7, medal: '待交付' },
      badgeDelivery: [{ badgeCode: 'ROAM_TILES_50', state: 'DUE' }] } })
  }
  assert.equal(await page.recoverRoam(), true)
  assert.deepEqual(page._track, []); assert.deepEqual(page._sessionTiles, {})
  assert.deepEqual(values['roam_memory_v1:9:recovery'].track, [])
  assert.equal(values['roam_memory_v1:9:sessions'][0].locationExpired, true)
  assert.equal(page.data.finish.medal, '')
})

for (const hasTime of [true, false]) test(`C3过期行程有pending且采集时间${hasTime ? '存在' : '缺失'}：先清几何不补传，仍读回徽章`, async () => {
  const page = mount(); page._roamSid = SID; page._clientSessionKey = '1'.repeat(32)
  page._pendingTiles = ['wtw3sjr']; page._pendingCollectedAt = hasTime ? { wtw3sjr: 1234 } : {}
  page._track = [{ lat: 31, lng: 121 }]; page._sessionTiles = { wtw3sjr: 1 }
  page._recoveryArchiveTs = 1234; page._saveRoamRecovery()
  values['roam_memory_v1:9:sessions'] = [{ ts: 1234, track: page._track }]
  let state = 'DUE'
  calls = [] // Only requests made by the recovery under test.
  app.sendRequest = o => {
    calls.push({ url: o.url, data: o.data })
    if (o.url !== '/api/roam/session') { o.fail({}); return }
    o.success({ code: 200, data: { state: 'FINISHED', sessionId: SID, clientSessionKey: page._clientSessionKey,
      locationExpired: true, finishedAt: 1234, resultComplete: true, result: { totalXp: 7, medal: '城市点亮 50 格' },
      badgeDelivery: [{ badgeCode: 'ROAM_TILES_50', state }] } })
  }
  assert.equal(await page.recoverRoam(), true)
  assert.equal(calls.filter(c => c.url === '/api/roam/session/records').length, 0)
  const record = values['roam_memory_v1:9:recovery']
  assert.deepEqual(record.pendingTiles, []); assert.deepEqual(record.collectedAt, {}); assert.deepEqual(record.track, [])
  assert.equal(values['roam_memory_v1:9:sessions'][0].locationExpired, true)
  assert.equal(page.data.finish.medal, '')
  state = 'DELIVERED'; assert.equal(await page.recoverRoam(), true)
  assert.equal(page.data.finish.medal, '城市点亮 50 格')
  assert.equal(calls.filter(c => c.url !== '/api/roam/session').length, 0)
})

for (const kind of ['reveal', 'archive']) test(`C3${kind}确认只保留pending时间，落盘失败同时回滚队列与时间`, async () => {
  const page = mount(); page._roamSid = SID; page._clientSessionKey = '1'.repeat(32)
  page._pendingTiles = ['wtw3sjq']; page._pendingCollectedAt = { wtw3sjq: 1234, staleKey: 123 }
  let reply
  app.sendRequest = o => { reply = o }
  const run = () => kind === 'reveal' ? page._flushReveal() : page._archiveFinishedRecords()
  let pending = run(); await settle()
  page._pendingTiles.push('wtw3sjr'); page._pendingCollectedAt.wtw3sjr = 2345
  const originalSave = page._saveRoamRecovery; page._saveRoamRecovery = () => false
  reply.success({ code: 200, data: { sessionId: SID, acceptedRecords: 1, evidenceType: 'CLIENT_REPORTED' } })
  assert.equal(await pending, false)
  assert.deepEqual(page._pendingTiles, ['wtw3sjq', 'wtw3sjr']); assert.equal(page._pendingCollectedAt.wtw3sjq, 1234)
  page._saveRoamRecovery = originalSave
  pending = run(); await settle()
  reply.success({ code: 200, data: { sessionId: SID, acceptedRecords: 2, evidenceType: 'CLIENT_REPORTED' } })
  // Archive drains all batches; both paths now acknowledge every pending tile.
  assert.equal(await pending, true)
  assert.deepEqual(page._pendingCollectedAt, {})
  assert.deepEqual(values['roam_memory_v1:9:recovery'].collectedAt, {})
})

test('M0116低档DELIVERED不证明200格徽章已得，原200档回执确认后才显示', () => {
  const page = mount(); page._roamSid = SID
  const result = { totalXp: 7, medal: '城市点亮 200 格' }
  const rows = [{ badgeCode: 'ROAM_TILES_50', sourceId: 50, state: 'DELIVERED' }, { badgeCode: 'ROAM_TILES_200', sourceId: 200, state: 'DUE' }]
  assert.equal(page._applyRoamFinishResult(result, true, rows), true)
  assert.equal(page.data.finish.medal, '')
  assert.equal(values['roam_memory_v1:9:recovery'].sessionId, SID)
  rows[1].state = 'DELIVERED'
  assert.equal(page._applyRoamFinishResult(result, true, rows), true)
  assert.equal(page.data.finish.medal, result.medal)
})

test('M0116空交付收据安全保原SID，查询promise释放后可以重试', async () => {
  const page = mount(); page._roamSid = SID; page._clientSessionKey = '1'.repeat(32); page._saveRoamRecovery()
  let rows = [null]
  app.sendRequest = o => o.success({ code: 200, data: { state: 'FINISHED', sessionId: SID,
    clientSessionKey: page._clientSessionKey, resultComplete: true, result: { totalXp: 7, medal: '城市点亮 200 格' }, badgeDelivery: rows } })
  assert.equal(await page.recoverRoam(), true); await settle()
  assert.equal(page._recoveryPromise, null); assert.equal(page.data.finish.medal, '')
  assert.equal(values['roam_memory_v1:9:recovery'].sessionId, SID)
  rows = [{ badgeCode: 'ROAM_TILES_200', state: 'DELIVERED' }]
  assert.equal(await page.recoverRoam(), true); assert.equal(page.data.finish.medal, '城市点亮 200 格')
})

test('M0116恢复处理异常也释放本轮promise，保留SID重查', async () => {
  const page = mount(); page._roamSid = SID; page._clientSessionKey = '1'.repeat(32); page._saveRoamRecovery()
  app.sendRequest = o => o.success({ code: 200, data: { state: 'FINISHED', sessionId: SID,
    clientSessionKey: page._clientSessionKey, resultComplete: true, result: { totalXp: 7 } } })
  const apply = page._applyRoamFinishResult; page._applyRoamFinishResult = () => { throw new Error('read failure') }
  assert.equal(await page.recoverRoam(), false); await settle(); assert.equal(page._recoveryPromise, null)
  assert.equal(values['roam_memory_v1:9:recovery'].sessionId, SID)
  page._applyRoamFinishResult = apply; assert.equal(await page.recoverRoam(), true)
})

test('P0-102 同会话内连续重拉POI只认最后一次请求，旧回包晚到不得覆盖新结果', async () => {
  const page = mount(); const pending = []
  const original = app.sendRequest
  app.sendRequest = o => { if (o.url === '/api/roam/pois') { pending.push(o); return } return original(o) }
  page._genIcons = () => Promise.resolve(); page._syncMarkers = () => {}; page._syncSparkCircles = () => {}; page._syncGoal = () => {}
  page._nearbyReady = Promise.resolve()
  page._fetchRoamPois({ lat: 31, lng: 121 }); await settle()
  page._fetchRoamPois({ lat: 31.01, lng: 121.01 }); await settle()
  assert.equal(pending.length, 2)
  pending[1].success({ code: 200, data: [{ id: 2, type: 1, name: '新范围地点', lat: 31.01, lng: 121.01, found: false }] }); await settle()
  pending[0].success({ code: 200, data: [{ id: 1, type: 1, name: '旧范围地点', lat: 31, lng: 121, found: false }] }); await settle()
  assert.deepEqual(page._pois.filter(p => p._roamId != null).map(p => p._roamId), [2])
})

for (const failure of ['throw', 'silent']) test('轨迹落盘' + failure + '失败不标记存档完成，恢复存储后可重试', () => {
  const page = mount(); page._pois = []; page._reveals = [{ lat: 31, lng: 121, r: 10 }]
  const write = wx.setStorageSync
  wx.setStorageSync = (key, value) => {
    if (key.endsWith(':reveals')) { if (failure === 'throw') throw new Error('disk full'); return }
    write(key, value)
  }
  assert.equal(page._saveSession({ distance: 1, photos: [] }), false)
  assert.notEqual(page._revealsSaved, true)
  wx.setStorageSync = write
  assert.equal(page._saveSession({ distance: 1, photos: [] }), true)
  assert.equal(page._revealsSaved, true)
  assert.equal(values['roam_memory_v1:9:sessions'].length, 1)
  assert.equal(values['roam_memory_v1:9:reveals'].length, 1)
})
