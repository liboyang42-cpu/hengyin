'use strict'
// G-2(业主 2026-10-27 m694):弹层开着时,安卓物理返回 / iOS 侧滑先关最上层弹层,不退出整页。
// 共享弹层在 utils/native-back-guard.js 实现一次(0 尺寸 page-container,与 #589 玩法预览同一做法)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const GUARD = path.join(ROOT, 'utils/native-back-guard.js')

function freshGuard(pages) {
  global.getCurrentPages = () => pages.list
  delete require.cache[GUARD]
  const mod = require(GUARD)
  mod._reset()
  return mod
}

// 按组件定义造一个最小实例:setData 同步落值并回调,observers 手动触发
function mount(def, props) {
  const vm = Object.assign({}, def.methods)
  vm.data = Object.assign({}, def.data)
  Object.keys(def.properties || {}).forEach((key) => { vm.data[key] = def.properties[key].value })
  Object.assign(vm.data, props)
  vm.events = []
  vm.triggerEvent = (name, detail) => vm.events.push({ name, detail })
  vm.setData = (patch, cb) => { Object.assign(vm.data, patch); if (cb) cb() }
  vm.set = (patch) => { Object.assign(vm.data, patch); def.observers['show, backGuard'].call(vm, vm.data.show, vm.data.backGuard) }
  vm.attach = () => def.lifetimes.attached.call(vm)
  vm.detach = () => def.lifetimes.detached.call(vm)
  vm.pageHide = () => def.pageLifetimes.hide.call(vm)
  vm.pageShow = () => def.pageLifetimes.show.call(vm)
  return vm
}

const tick = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function sheetDef(guard, onBack) {
  return guard.withNativeBackGuard({ properties: { show: { type: Boolean, value: false } }, data: {} }, onBack)
}

test('只有最上层弹层挂守卫;关掉上层后下层重新挂上', () => {
  const pages = { list: [{}] }
  const guard = freshGuard(pages)
  const def = sheetDef(guard, () => {})
  const a = mount(def); a.attach()
  const b = mount(def); b.attach()
  a.set({ show: true })
  assert.equal(a.data._backGuard, true)
  b.set({ show: true })
  assert.equal(a.data._backGuard, false, '叠层时下层让位(一页只能有一个 page-container)')
  assert.equal(b.data._backGuard, true)
  b.set({ show: false })
  assert.equal(b.data._backGuard, false)
  assert.equal(a.data._backGuard, true, '上层关掉后下层重新接管返回键')
  a.set({ backGuard: false })
  assert.equal(a.data._backGuard, false, 'back-guard=false 让位给页面自己的守卫')
})

test('物理返回落到弹层出口;宿主没关(脏数据确认)就重新挂上', async () => {
  const pages = { list: [{}, {}] }
  const guard = freshGuard(pages)
  let backs = 0
  const def = sheetDef(guard, (vm) => { backs += 1; if (vm.data.closeOnBack) vm.set({ show: false }) })
  const vm = mount(def, { closeOnBack: true }); vm.attach()
  vm.set({ show: true })
  vm._onBackGuardLeave()
  assert.equal(backs, 0, '等一拍确认不是离页')
  await tick(guard.LEAVE_SETTLE_MS + 20)
  assert.equal(backs, 1)
  assert.equal(vm.data.show, false)
  assert.equal(vm.data._backGuard, false)

  const dirty = mount(def, { closeOnBack: false }); dirty.attach()
  dirty.set({ show: true })
  dirty._onBackGuardLeave()
  await tick(guard.LEAVE_SETTLE_MS + 20)
  assert.equal(backs, 2)
  assert.equal(dirty.data._backGuard, true, '弹层还开着 ⇒ 下一次返回仍先落在它身上')
})

test('程序收起触发的 afterleave 不当成返回键', async () => {
  const pages = { list: [{}] }
  const guard = freshGuard(pages)
  let backs = 0
  const def = sheetDef(guard, () => { backs += 1 })
  const a = mount(def); a.attach()
  const b = mount(def); b.attach()
  a.set({ show: true })
  b.set({ show: true })      // a 被换下,page-container 随之 leave
  a._onBackGuardLeave()
  b.set({ show: false })     // ✕ 关闭,守卫随之 leave
  b._onBackGuardLeave()
  await tick(guard.LEAVE_SETTLE_MS + 20)
  assert.equal(backs, 0)
})

test('离页(navigateTo / 选图)让守卫 leave 时不关弹层,回到页面再挂', async () => {
  const pages = { list: [{}] }
  const guard = freshGuard(pages)
  let backs = 0
  const def = sheetDef(guard, () => { backs += 1 })
  const vm = mount(def); vm.attach()
  vm.set({ show: true })
  vm._onBackGuardLeave()
  pages.list = [{}, {}]
  vm.pageHide()
  await tick(guard.LEAVE_SETTLE_MS + 20)
  assert.equal(backs, 0, '离页不是返回键')
  assert.equal(vm.data._backGuard, false)
  pages.list = [{}]
  vm.pageShow()
  assert.equal(vm.data._backGuard, true, '回到页面重新拦返回键')
})

test('releaseNativeBackGuards 先撤守卫再 navigateBack,卸载后不再登记', () => {
  const pages = { list: [{}] }
  const guard = freshGuard(pages)
  const def = sheetDef(guard, () => {})
  const vm = mount(def); vm.attach()
  vm.set({ show: true })
  let navigated = null
  guard.releaseNativeBackGuards(() => { navigated = vm.data._backGuard })
  assert.equal(navigated, false, 'navigateBack 执行时守卫已撤,否则会被 page-container 吃掉')
  vm.detach()
  assert.equal(guard._stack.length, 0)
})

function loadComponent(rel) {
  let def
  global.Behavior = (o) => o
  global.Component = (d) => { def = d }
  global.getApp = () => ({ globalData: {} })
  global.wx = { getWindowInfo: () => ({ windowWidth: 375, windowHeight: 812 }) }
  const file = path.join(ROOT, rel)
  delete require.cache[file]
  require(file)
  return def
}

test('cy-scene-sheet:返回键有上一步退一步,否则走 ✕ 出口;closable=false 只吞掉', async () => {
  freshGuard({ list: [{}] })
  const def = loadComponent('components/cy/scene-sheet/index.js')
  const vm = mount(def, { show: true })
  vm._onNativeBack()
  assert.deepEqual(vm.events.map((e) => e.name), ['requestclose', 'close'])
  assert.equal(vm.events[0].detail.reason, 'back')
  const child = mount(def, { show: true, canBack: true })
  child._onNativeBack()
  assert.deepEqual(child.events.map((e) => e.name), ['back'])
  const locked = mount(def, { show: true, closable: false })
  locked._onNativeBack()
  assert.deepEqual(locked.events, [])
})

test('cy-sheet / cy-modal / cy-date-sheet:返回键与用户能点到的出口一致', () => {
  freshGuard({ list: [{}] })
  const sheet = loadComponent('components/cy/sheet/index.js')
  const open = mount(sheet, { show: true })
  open._onNativeBack()
  assert.deepEqual(open.events.map((e) => e.name), ['requestclose', 'close'])
  const dirty = mount(sheet, { show: true, dirty: true })
  dirty._onNativeBack()
  assert.deepEqual(dirty.events.map((e) => e.name), ['requestclose'], '脏输入只上报,不直接关')
  const noExit = mount(sheet, { show: true, maskClosable: false })
  noExit._onNativeBack()
  assert.deepEqual(noExit.events, [], '没 ✕ 也不许点遮罩关的弹层,返回键只吞掉')

  const modal = loadComponent('components/cy/modal/index.js')
  const confirm = mount(modal, { show: true })
  confirm._onNativeBack()
  assert.deepEqual(confirm.events.map((e) => e.name), ['cancel'], '返回 = 取消,永远不等于确认')
  const busy = mount(modal, { show: true, loading: true })
  busy._onNativeBack()
  assert.deepEqual(busy.events, [])

  const date = loadComponent('components/cy/date-sheet/index.js')
  const picker = mount(date, { show: true })
  picker._onNativeBack()
  assert.deepEqual(picker.events.map((e) => e.name), ['cancel'])
})

test('共享弹层统一挂 0 尺寸 page-container 守卫', () => {
  const GUARD_TAG = /<page-container show="\{\{_backGuard\}\}" duration="\{\{0\}\}" overlay="\{\{false\}\}" custom-style="width:0;height:0;background:transparent;pointer-events:none" bind:afterleave="_onBackGuardLeave" \/>/
  for (const name of ['sheet', 'scene-sheet', 'modal', 'date-sheet', 'publish-sheet', 'back-guard']) {
    assert.match(read(`components/cy/${name}/index.wxml`), GUARD_TAG, name)
    assert.match(read(`components/cy/${name}/index.js`), /Component\(withNativeBackGuard\(/, name)
  }
  // 包了一层共享弹层的组件把开关透传下去
  for (const name of ['option-sheet', 'category-sheet', 'result-sheet', 'datetime-range']) {
    assert.match(read(`components/cy/${name}/index.wxml`), /back-guard="\{\{backGuard\}\}"/, name)
  }
})

test('手写 psheet 宿主用 cy-back-guard 一行接入;深链壳退页前先撤守卫', () => {
  assert.match(read('pages/roam/index.wxml'), /<cy-back-guard show="\{\{!!sceneCurrent\}\}" bind:back="onProtoSheetClose" \/>/)
  assert.match(read('pages/play/index.wxml'), /<cy-back-guard show="\{\{!!sceneCurrent\}\}" bind:back="onProtoSheetClose" \/>/)
  assert.match(read('subpackageRoam/nearby/index.wxml'), /<cy-back-guard show="\{\{!!sheet\}\}" bind:back="closeSheet" \/>/)
  const deepLink = read('components/cy/scene-deep-link/index.js')
  assert.match(deepLink, /releaseNativeBackGuards\(\(\) => wx\.navigateBack\(/)
  assert.match(read('components/cy/scene-deep-link/index.wxml'), /back-guard="\{\{sceneStack\.length > 1 \|\| sceneCurrent\.dirty\}\}"/)
})
