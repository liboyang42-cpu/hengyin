const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8')

function assertScanPathContract(js) {
  // mode2 的 checkin 成功分支必须在 onComplete 之前被拦下:onComplete 会本地置 done+庆祝,
  // 把「进店」演成「已核销」(2026-08-15 F6)。
  assert.match(js, /that\.data\.mode === 2 && !isGame\) \{ that\.onShopDayArrived\(litId, r\.data\); return; \}/)
  assert.match(js, /onShopDayArrived\(nodeId, data\) \{/)
  const guard = js.indexOf('that.onShopDayArrived(litId')
  const complete = js.indexOf('that.onComplete(litId')
  assert.ok(guard !== -1 && complete !== -1 && guard < complete, 'mode2 拦截必须先于 onComplete')
  assert.match(js, /onShopDayArrived[\s\S]{0,400}\/pages\/play\/merchant\/index\?nodeId=/)
}

function assertCoordinateArrivalContract(js) {
  const arrive = js.slice(js.indexOf('  arrive(nodeId, via) {'), js.indexOf('  checkin(nodeId, code, source) {'))
  assert.match(
    arrive,
    /that\.data\.mode === 2 && !completedReceipt\) \{ that\.onShopDayArrived\(nodeId, r\.data\); return; \}/,
    'mode2 纯到店回执只能进入到店态；完整游戏结算回执复用 onComplete',
  )
  const guard = arrive.indexOf('that.onShopDayArrived(nodeId')
  const complete = arrive.indexOf('that.onComplete(nodeId')
  assert.ok(guard !== -1 && complete !== -1 && guard < complete, '到店态拦截必须先于 onComplete')
}

test('探店日扫码进店不得走 onComplete,必须转商家页三步条', () => {
  assertScanPathContract(read('pages/play/index.js'))
})

test('探店日无坐标站点手动到店不得走 onComplete,必须转商家页三步条', () => {
  assertCoordinateArrivalContract(read('pages/play/index.js'))
})

test('mutation 负控:删掉 mode2 拦截时契约确实变红', () => {
  const js = read('pages/play/index.js')
    .replace('if (that.data.mode === 2 && !isGame) { that.onShopDayArrived(litId, r.data); return; }', '')
  assert.throws(() => assertScanPathContract(js), assert.AssertionError)
})

test('mutation 负控:删掉无坐标到店拦截时契约确实变红', () => {
  const js = read('pages/play/index.js')
    .replace('if (that.data.mode === 2 && !completedReceipt) { that.onShopDayArrived(nodeId, r.data); return; }', '')
  assert.throws(() => assertCoordinateArrivalContract(js), assert.AssertionError)
})

test('FE-STORY 到访:扫码走 /api/play/checkin、GPS 走 /api/play/arrive,解锁后留在卡包打开本章,不跳商家页', () => {
  const gate = read('pages/play/utils/fe-story-gate.js')
  assert.match(gate, /this\._sendVisit\(node, '\/api\/play\/checkin', \{ nodeId: node\.nodeId, code: r\.result/)
  assert.match(gate, /this\._sendVisit\(node, '\/api\/play\/arrive', \{ nodeId: node\.nodeId, longitude: loc\.longitude, latitude: loc\.latitude/)
  assert.doesNotMatch(gate, /merchant\/index|onShopDayArrived|onComplete\(/, '到访只解锁剧情,不走进店三步条也不本地置完成')
})
