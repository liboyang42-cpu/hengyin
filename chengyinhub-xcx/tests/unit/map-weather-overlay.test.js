'use strict'

// 第九轮 m-rain:地图天气氛围组件。只桩 wx / getApp / 画布,跑真实组件代码。
const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')

const DIR = path.resolve(__dirname, '../../subpackageRoam/components/map-weather')
const COMPONENT = path.join(DIR, 'index.js')
const CORE = path.join(DIR, 'weather-core.js')
let sandbox

function fakeCanvas() {
  const canvas = {
    frames: [], cancelled: [], width: 0, height: 0, nextId: 1,
    calls: [],
    requestAnimationFrame(fn) { const id = canvas.nextId++; canvas.frames.push({ id, fn }); return id },
    cancelAnimationFrame(id) { canvas.cancelled.push(id); canvas.frames = canvas.frames.filter((f) => f.id !== id) },
    getContext() {
      const rec = (name) => (...args) => { canvas.calls.push(name); return name === 'createLinearGradient' ? { addColorStop() {} } : undefined }
      return new Proxy({}, { get: (t, k) => (k in t ? t[k] : rec(k)), set: (t, k, v) => { t[k] = v; return true } })
    },
  }
  return canvas
}

beforeEach(() => {
  sandbox = { requests: [], canvas: fakeCanvas(), definition: null }
  global.getApp = () => ({ sendRequest(options) { sandbox.requests.push(options) } })
  global.wx = { getWindowInfo: () => ({ pixelRatio: 3 }) }
  global.Component = (definition) => { sandbox.definition = definition }
  delete require.cache[require.resolve(COMPONENT)]
  delete require.cache[require.resolve(CORE)]
  require(COMPONENT)
  require(CORE).resetCache()
})

function mount(props) {
  const def = sandbox.definition
  const vm = Object.assign({}, def.methods)
  vm.data = Object.assign({}, def.data)
  Object.keys(def.properties).forEach((k) => { vm.data[k] = def.properties[k].value })
  vm.setData = function (patch, cb) { Object.assign(this.data, patch); if (cb) cb() }
  vm.createSelectorQuery = () => ({
    select: () => ({ fields: () => ({ exec: (cb) => cb([{ node: sandbox.canvas, width: 390, height: 844 }]) }) }),
  })
  vm.set = function (patch) { Object.assign(this.data, patch); def.observers['lat, lng, active, reducedMotion'].call(this) }
  def.lifetimes.attached.call(vm)
  Object.assign(vm.data, props || {})
  def.lifetimes.ready.call(vm)
  return vm
}

const reply = (i, data) => sandbox.requests[i].success({ code: 200, data })
const tick = (ts) => { const f = sandbox.canvas.frames.shift(); assert.ok(f, '应有下一帧'); f.fn(ts) }

test('reducedMotion:不请求、不建画布、不动画', () => {
  const vm = mount({ lat: 30.2741, lng: 120.1551, active: true, reducedMotion: true })
  assert.equal(sandbox.requests.length, 0)
  assert.equal(vm.data.on, false)
  assert.equal(sandbox.canvas.frames.length, 0)
})

test('下雨:匿名 GET 只发片区中心,画布 DPR 封顶 2,逐帧画雨丝', () => {
  const vm = mount({ lat: 30.2741, lng: 120.1551, active: true })
  assert.equal(sandbox.requests.length, 1)
  const req = sandbox.requests[0]
  assert.equal(req.url, '/api/map/weather')
  assert.equal(req.method, 'GET')
  assert.deepEqual(req.data, { lat: '30.275', lng: '120.175' })
  assert.equal(req.autoErrorToast, false)
  assert.equal(typeof req.fail, 'function')
  assert.equal(typeof req.successStatusAbnormal, 'function')
  reply(0, { kind: 'rain', intensity: 0.6 })
  assert.equal(vm.data.on, true)
  assert.equal(sandbox.canvas.width, 780)
  assert.equal(sandbox.canvas.height, 1688)
  assert.equal(vm._parts.length, 120)
  tick(16); tick(33)
  assert.ok(sandbox.canvas.calls.includes('stroke'))
  assert.equal(sandbox.canvas.frames.length, 1, '持续动画')
})

test('页面隐藏即停,回来复用缓存继续,不再请求', () => {
  const vm = mount({ lat: 30.2741, lng: 120.1551, active: true })
  reply(0, { kind: 'snow', intensity: 0.5 })
  tick(16)
  vm.set({ active: false })
  assert.equal(sandbox.canvas.frames.length, 0)
  assert.equal(vm._raf, 0)
  vm.set({ active: true })
  assert.equal(sandbox.requests.length, 1)
  assert.equal(sandbox.canvas.frames.length, 1)
  tick(50)
  assert.ok(sandbox.canvas.calls.includes('arc'))
})

test('detached 停动画;之后迟到的回包不再画', () => {
  const vm = mount({ lat: 30.2741, lng: 120.1551, active: true })
  sandbox.definition.lifetimes.detached.call(vm)
  reply(0, { kind: 'rain', intensity: 1 })
  assert.equal(vm.data.on, false)
  assert.equal(sandbox.canvas.frames.length, 0)
})

test('运行中转 reducedMotion:停下并撤掉画布', () => {
  const vm = mount({ lat: 30.2741, lng: 120.1551, active: true })
  reply(0, { kind: 'rain', intensity: 0.4 })
  vm.set({ reducedMotion: true })
  assert.equal(vm.data.on, false)
  assert.equal(sandbox.canvas.frames.length, 0)
})

test('clear、失败、异常结构:什么都不画,也不提示', () => {
  const cases = [
    (r) => r.success({ code: 200, data: { kind: 'clear', intensity: 0 } }),
    (r) => r.success({ code: 500, msg: '经纬度格式不正确' }),
    (r) => r.fail(),
    (r) => r.successStatusAbnormal(),
    (r) => r.success({ code: 200, data: { kind: 'hail' } }),
  ]
  cases.forEach((answer, i) => {
    const vm = mount({ lat: 10 + i, lng: 120, active: true })
    answer(sandbox.requests[sandbox.requests.length - 1])
    assert.equal(vm.data.on, false, 'case ' + i)
  })
  assert.equal(sandbox.canvas.frames.length, 0)
  const src = fs.readFileSync(COMPONENT, 'utf8')
  assert.doesNotMatch(src, /showToast|showModal|triggerEvent/)
})

test('起雾:画一次静止纱层,不跑动画', () => {
  const vm = mount({ lat: 30.2741, lng: 120.1551, active: true })
  reply(0, { kind: 'fog', intensity: 0.8 })
  assert.equal(vm.data.on, true)
  assert.ok(sandbox.canvas.calls.includes('fillRect'))
  assert.equal(sandbox.canvas.frames.length, 0)
})

test('GPS 每跳都变但仍在同一片区:十分钟内不重复请求;换片区才再问', () => {
  const vm = mount({ lat: 30.2741, lng: 120.1551, active: true })
  vm.set({ lat: 30.2752 }); vm.set({ lat: 30.2763, lng: 120.1560 }) // 在途时也不重复
  assert.equal(sandbox.requests.length, 1)
  reply(0, { kind: 'rain', intensity: 0.5 })
  vm.set({ lat: 30.2799 })
  assert.equal(sandbox.requests.length, 1)
  vm.set({ lat: 30.40 })
  assert.equal(sandbox.requests.length, 2)
})

test('片区取整与缓存过期', () => {
  const core = require(CORE)
  assert.equal(core.cellOf(30.2741, 120.1551), '605:2403')
  assert.equal(core.cellOf(30.2799, 120.1599), '605:2403')
  assert.equal(core.cellOf(-33.86, 151.21), '-678:3024')
  assert.equal(core.cellOf(0, 0), null)
  assert.equal(core.cellOf(91, 0), null)
  assert.equal(core.cellOf('x', 1), null)
  core.remember('1:1', { kind: 'rain', intensity: 0.5 }, 1000)
  assert.deepEqual(core.cached('1:1', 1000 + core.TTL_MS - 1), { kind: 'rain', intensity: 0.5 })
  assert.equal(core.cached('1:1', 1000 + core.TTL_MS), undefined)
  core.remember('2:2', null, 0)
  assert.equal(core.cached('2:2', 5), null)
  assert.ok(core.TTL_MS >= 10 * 60 * 1000)
  assert.equal(core.dprOf(3), 2)
  assert.equal(core.dprOf(undefined), 1)
  assert.ok(core.particleCount({ kind: 'rain', intensity: 1 }) <= 160)
  assert.ok(core.particleCount({ kind: 'snow', intensity: 1 }) <= 110)
})

test('只住在 subpackageRoam,漫游页经 componentPlaceholder 异步引用,画布不接手势', () => {
  const json = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../pages/roam/index.json'), 'utf8'))
  assert.equal(json.usingComponents['cy-map-weather'], '/subpackageRoam/components/map-weather/index')
  assert.equal(json.componentPlaceholder['cy-map-weather'], 'view')
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/roam/index.wxml'), 'utf8')
  assert.match(wxml, /<cy-map-weather lat="\{\{center\.lat\}\}" lng="\{\{center\.lng\}\}" active="\{\{roamPageShown\}\}" reduced-motion="\{\{reducedMotion\}\}" \/>/)
  assert.match(fs.readFileSync(path.join(DIR, 'index.wxss'), 'utf8'), /pointer-events:\s*none/)
  assert.match(fs.readFileSync(path.join(DIR, 'index.wxml'), 'utf8'), /type="2d"/)
})
