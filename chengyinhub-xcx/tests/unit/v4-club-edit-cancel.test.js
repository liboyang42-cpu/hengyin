'use strict'
// SC-CL-EDIT-CANCEL:pages/club/edit(深链壳)+ cy-scene-club-edit。
// 编辑 → 返回 →「继续编辑」保留草稿;「确认放弃」不发任何写请求。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const WRITE_URLS = ['/api/club/update-mine', '/api/club/dissolve']

function mount(options) {
  let pageDef, compDef, editor
  const requests = []
  const backs = []
  global.getApp = () => ({ globalData: {}, sendRequest(r) { requests.push(r) }, getAuthorization: () => 'Bearer t', getUserID: () => '9' })
  global.Page = (d) => { pageDef = d }
  global.Component = (d) => { compDef = d }
  const localDrafts = {}
  global.wx = { getStorageSync: key => localDrafts[key], setStorageSync: (key, value) => { localDrafts[key] = value }, removeStorageSync: key => { delete localDrafts[key] }, navigateBack(o) { backs.push(o || {}) }, switchTab() {}, showToast() {}, navigateTo() {} }
  for (const file of ['pages/club/edit/index.js', 'components/cy/scene-club-edit/index.js']) {
    const abs = path.join(ROOT, file)
    delete require.cache[require.resolve(abs)]
    require(abs)
  }
  // 按 wxml 上的 bind:xxx 把组件事件接到页面处理函数
  const tag = fs.readFileSync(path.join(ROOT, 'pages/club/edit/index.wxml'), 'utf8').match(/<cy-scene-club-edit\b[^>]*>/)[0]
  const bindings = {}
  tag.replace(/bind:(\w+)="(\w+)"/g, (_, ev, handler) => { bindings[ev] = handler })
  const page = Object.assign({}, pageDef, {
    data: structuredClone(pageDef.data),
    setData(patch) { Object.assign(this.data, patch); if (this.data.routeReady && !editor) attach() },
    selectComponent() { return editor },
  })
  function attach() {
    editor = Object.assign({}, compDef.methods, {
      data: Object.assign(structuredClone(compDef.data), { clubId: page.data.clubId }),
      setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb.call(this) },
      triggerEvent(name, detail) { const h = bindings[name]; if (h) page[h]({ detail: detail || {} }) },
    })
    compDef.lifetimes.attached.call(editor)
  }
  page.onLoad(options)
  return { page, get editor() { return editor }, requests, backs }
}

function loadReady(h) {
  h.requests[0].success({ code: 200, data: { id: 7001, name: '原名', city: '上海', isOwner: true } })
  assert.equal(h.editor.data.loadState, 'ready')
}

test('编辑后返回 →「继续编辑」:不离页、草稿原样保留、不发写请求', () => {
  const h = mount({ id: '7001' })
  loadReady(h)
  h.editor.onInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: '新名字' } })
  assert.equal(h.page.data.dirty, true, '输入必须把 dirty 传给宿主')

  h.editor.onBack() // 组件内返回 → bind:back → requestBack
  assert.equal(h.page.data.confirm.show, true, '有未保存修改时返回必须先确认')
  assert.equal(h.backs.length, 0)

  h.page.cancelBack()
  assert.equal(h.page.data.confirm.show, false)
  assert.equal(h.page.data.dirty, true)
  assert.equal(h.editor.data.name, '新名字', '继续编辑必须保留草稿')
  assert.equal(h.backs.length, 0)
  assert.equal(h.requests.filter((r) => WRITE_URLS.includes(r.url)).length, 0)

  // 继续编辑后仍是同一个草稿,可以正常保存
  h.editor.save()
  const check = h.requests[h.requests.length - 1]
  assert.equal(check.url, '/api/club/detail')
  check.success({ code: 200, data: { id: 7001, name: '原名', city: '上海', isOwner: true } })
  const save = h.requests.find((r) => r.url === '/api/club/update-mine')
  assert.equal(JSON.parse(save.data).name, '新名字')
})

test('编辑后返回 →「确认放弃」:离页且全程零写请求', () => {
  const h = mount({ id: '7001' })
  loadReady(h)
  h.editor.onInput({ currentTarget: { dataset: { field: 'city' } }, detail: { value: '杭州' } })
  h.page.requestBack()
  assert.equal(h.page.data.confirm.show, true)
  h.page.confirmBack()
  assert.equal(h.backs.length, 1, '放弃后必须真的返回')
  assert.equal(h.page.data.dirty, false)
  assert.deepEqual(h.requests.map((r) => r.url), ['/api/club/detail'], '放弃不得发任何写请求')
})

test('未改动时返回直接离开,不弹放弃确认', () => {
  const h = mount({ id: '7001' })
  loadReady(h)
  h.page.requestBack()
  assert.equal(h.page.data.confirm.show, false)
  assert.equal(h.backs.length, 1)
})

test('站内入口(俱乐部详情里的编辑 scene):dirty 关闭先确认,继续保留、放弃只关层不写', () => {
  let def
  const vm = require('node:vm')
  const src = fs.readFileSync(path.join(ROOT, 'pages/club/detail/index.js'), 'utf8')
  // 只取 scene 相关方法:不跑整页依赖,直接从源码切出这几个方法体求值
  const pick = (name) => src.match(new RegExp('\\n  ' + name + '\\(\\) \\{[\\s\\S]*?\\n  \\},'))[0]
  const methods = ['closeScene', 'requestSceneClose', 'confirmSceneDiscard', 'cancelSceneDiscard'].map(pick).join('\n')
  vm.runInNewContext('Page({' + methods + '\n onSceneDirtyChange(e) { this.setData({ sceneDirty: !!(e.detail && e.detail.dirty) }); } })',
    { Page(d) { def = d } })
  const page = Object.assign({}, def, {
    data: { sceneStack: [{ key: 'club-edit' }], sceneCurrent: { key: 'club-edit' }, sceneDirty: false, sceneConfirm: false },
    setData(p) { Object.assign(this.data, p) },
  })
  page.onSceneDirtyChange({ detail: { dirty: true } })
  page.requestSceneClose()
  assert.equal(page.data.sceneConfirm, true)
  assert.ok(page.data.sceneCurrent, '确认前编辑层不得关闭')
  page.cancelSceneDiscard()
  assert.equal(page.data.sceneConfirm, false)
  assert.equal(page.data.sceneDirty, true, '继续编辑保留 dirty')
  assert.ok(page.data.sceneCurrent)
  page.requestSceneClose()
  page.confirmSceneDiscard()
  assert.equal(page.data.sceneCurrent, null)
  assert.equal(page.data.sceneDirty, false)
})
