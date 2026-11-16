const { test } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

// 3-06:首亮原生 POI 的「+N 探索值 · 点亮xx」刚弹出就被兜底文案盖掉(分支末尾没 return)。
// GAME-4:点亮只用设备真实定位;夹具给一个 wx.getLocation(默认成功),失败用例传 locationFails。
async function tapPoi(response, { locationFails = false } = {}) {
  let def
  const toasts = []
  const toastPath = require.resolve(path.join(__dirname, '../../utils/toast.js'))
  const savedToast = require.cache[toastPath]
  require.cache[toastPath] = { id: toastPath, filename: toastPath, loaded: true, exports: Object.assign((t) => toasts.push(t), { success() {}, hide() {} }) }
  const prev = { Page: global.Page, getApp: global.getApp, getCurrentPages: global.getCurrentPages, wx: global.wx }
  global.Page = c => { def = c }
  global.getCurrentPages = () => []
  const sent = []
  global.getApp = () => ({ globalData: { features: {} }, getUserID: () => '1', sendRequest: o => { sent.push(o.data); o.success(response) } })
  global.wx = {
    getStorageSync: () => undefined,
    getLocation: (o) => locationFails ? o.fail({ errMsg: 'getLocation:fail auth deny' }) : o.success({ latitude: 31.2001, longitude: 121.4001, accuracy: 12 }),
  }
  try {
    delete require.cache[require.resolve('../../pages/roam/index.js')]
    require('../../pages/roam/index.js')
    const page = Object.assign({}, def, { data: { center: { lat: 31.2, lng: 121.4 }, fogScreenGuard: false } })
    page.onPoiTap({ detail: { name: '外滩', latitude: 31.2, longitude: 121.4 } })
    for (let i = 0; i < 3; i += 1) await new Promise(r => setImmediate(r))
  } finally {
    Object.assign(global, prev)
    if (savedToast) require.cache[toastPath] = savedToast; else delete require.cache[toastPath]
  }
  tapPoi.lastSent = sent
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

test('GAME-4:发出去的是设备定位,不是地图中心', async () => {
  await tapPoi({ code: 200, data: { participating: false, firstVisit: false } })
  const body = tapPoi.lastSent[0]
  assert.equal(body.curLat, '31.2001')
  assert.equal(body.curLng, '121.4001')
  assert.equal(body.locAccuracy, '12')
  assert.match(body.locTimestamp, /^\d+$/)
})

test('GAME-4:拿不到定位就不点亮,也不发请求', async () => {
  assert.deepEqual(await tapPoi({ code: 200, data: {} }, { locationFails: true }), ['需要打开定位才能点亮这里'])
  assert.equal(tapPoi.lastSent.length, 0)
})

test('GAME-4:今天探索值已满时照常点亮并说明', async () => {
  assert.deepEqual(await tapPoi({ code: 200, data: { participating: false, firstVisit: true, xp: 0, capped: true } }), ['点亮外滩 · 今天探索值已满'])
})

test('GAME-4:服务端说没记上时如实说', async () => {
  assert.deepEqual(await tapPoi({ code: 200, data: { uncredited: true, trustCode: 'SPEED_IMPOSSIBLE' } }), ['这次没有记上，到了再试'])
})
