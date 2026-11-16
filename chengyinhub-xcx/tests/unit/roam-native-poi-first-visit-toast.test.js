const { test } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

// 3-06:首亮原生 POI 的「+N 探索值 · 点亮xx」刚弹出就被兜底文案盖掉(分支末尾没 return)。
// CITY-5:点到店只用真实定位(_realAnchor)并带上精度 hAcc;没有真实定位时不发请求,也不拿地图中心或地点坐标冒充。
async function tapPoi(response, fix = { anchor: { lat: 31.2, lng: 121.4 }, accuracy: 12 }, sent = []) {
  let def
  const toasts = []
  const toastPath = require.resolve(path.join(__dirname, '../../utils/toast.js'))
  const savedToast = require.cache[toastPath]
  require.cache[toastPath] = { id: toastPath, filename: toastPath, loaded: true, exports: Object.assign((t) => toasts.push(t), { success() {}, hide() {} }) }
  const prev = { Page: global.Page, getApp: global.getApp, getCurrentPages: global.getCurrentPages, wx: global.wx }
  global.Page = c => { def = c }
  global.getApp = () => ({ globalData: { features: {} }, getUserID: () => '1', sendRequest: o => { sent.push(o); o.success(response) } })
  global.getCurrentPages = () => []
  global.wx = { getStorageSync: () => undefined }
  try {
    delete require.cache[require.resolve('../../pages/roam/index.js')]
    require('../../pages/roam/index.js')
    const page = Object.assign({}, def, { data: { center: { lat: 31.9, lng: 121.9 }, fogScreenGuard: false },
      _realAnchor: fix && fix.anchor, _realAccuracy: fix ? fix.accuracy : null })
    page.onPoiTap({ detail: { name: '外滩', latitude: 31.2, longitude: 121.4 } })
    await new Promise(r => setImmediate(r))
  } finally {
    Object.assign(global, prev)
    if (savedToast) require.cache[toastPath] = savedToast; else delete require.cache[toastPath]
  }
  return toasts
}

test('首亮:只弹「+N 探索值 · 点亮xx」,不被兜底文案覆盖', async () => {
  assert.deepEqual(await tapPoi({ code: 200, data: { participating: false, firstVisit: true, xp: 20 } }), ['+20 探索值 · 点亮外滩'])
})

test('已点亮过:只弹「已点亮过」', async () => {
  assert.deepEqual(await tapPoi({ code: 200, data: { participating: false, firstVisit: false } }), ['已点亮过'])
})

test('无记录/报错:仍给兜底文案', async () => {
  assert.deepEqual(await tapPoi({ code: 500, msg: 'x' }), ['这个地点暂时没有可记录的信息'])
})

test('CITY-5:带上真实定位与精度,不用地图中心', async () => {
  const sent = []
  await tapPoi({ code: 200, data: { participating: false, firstVisit: false } }, undefined, sent)
  assert.equal(sent.length, 1)
  const body = sent[0].data || {}
  assert.equal(body.curLat, '31.2')
  assert.equal(body.curLng, '121.4')
  assert.equal(body.hAcc, '12')
})

test('CITY-5:没有真实定位时不发请求', async () => {
  const sent = []
  const toasts = await tapPoi({ code: 200, data: {} }, null, sent)
  assert.equal(sent.length, 0)
  assert.deepEqual(toasts, ['先开启定位再点亮'])
})
