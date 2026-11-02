'use strict'
// C-14:离开名称框 / 第 3 步点「下一步」时查名,被占就在名称下方显示红 * 与原因并停在这一步;创建时的终检保留。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const JS_PATH = 'pages/club/create/index.js'
const WXML = fs.readFileSync(path.join(ROOT, 'pages/club/create/index.wxml'), 'utf8')

function loadPage(mutate) {
  let source = fs.readFileSync(path.join(ROOT, JS_PATH), 'utf8')
  if (mutate) {
    const changed = mutate(source)
    assert.notEqual(changed, source, '负控锚点失效')
    source = changed
  }
  const requests = []
  const roleCallbacks = []
  const redirects = []
  const backs = []
  let definition
  let snapshotReady = false
  let clubLeader = false
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    sendRequest: (options) => requests.push(options),
    chooseImage() {},
  }
  const roleGuard = {
    load: (callback) => roleCallbacks.push(callback),
    hasSnapshot: () => snapshotReady,
    isClubLeader: () => clubLeader,
  }
  const sandbox = {
    getApp: () => app,
    // 有上一页:全页返回合同(#806)按 getCurrentPages().length 选 navigateBack 还是根栈落点
    getCurrentPages: () => [{}, {}],
    Page: (value) => { definition = value },
    require: (id) => id.includes('roleGuard')
      ? roleGuard
      : require(path.resolve(path.dirname(path.join(ROOT, JS_PATH)), id)),
    wx: {
      redirectTo: (options) => redirects.push(options),
      navigateBack: (options) => backs.push(options || {}), showToast() {}, nextTick: (fn) => fn(),
    },
  }
  vm.runInNewContext(source, sandbox, { filename: JS_PATH })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done.call(this) },
  })
  return {
    page, requests, roleCallbacks, redirects, backs,
    setSnapshot(value) { snapshotReady = value },
    setClubLeader(value) { clubLeader = value },
  }
}

function atNameStep(h, name) {
  Object.assign(h.page.data, { bootstrapState: 'ready', introMode: false, step: 3, clubType: '兴趣社群', name })
  h.page.validate()
}
const TAKEN = '俱乐部名称已被使用，请换一个'

test('离开名称框就查名;被占时名称下方显示原因且不能下一步', () => {
  const h = loadPage()
  atNameStep(h, ' 夜跑团 ')
  h.page.onNameBlur()
  const req = h.requests.at(-1)
  assert.equal(req.url, '/api/club/name-check')
  assert.deepEqual(JSON.parse(req.data), { name: '夜跑团' })
  req.success({ code: 200, data: { available: false, reason: TAKEN } })
  assert.equal(h.page.data.nameError, TAKEN)
  assert.equal(h.page.data.canNext, false)
})

test('第 3 步点下一步先查名:可用才进第 4 步,被占停在第 3 步', () => {
  const h = loadPage()
  atNameStep(h, '晨跑团')
  h.page.onNext()
  assert.equal(h.page.data.step, 3)
  h.requests.at(-1).success({ code: 200, data: { available: true, reason: '' } })
  assert.equal(h.page.data.step, 4)

  const h2 = loadPage()
  atNameStep(h2, '夜跑团')
  h2.page.onNext()
  h2.requests.at(-1).success({ code: 200, data: { available: false, reason: TAKEN } })
  assert.equal(h2.page.data.step, 3)
  assert.equal(h2.page.data.nameError, TAKEN)
})

test('改名清掉旧原因,过期的查名结果不回写', () => {
  const h = loadPage()
  atNameStep(h, '夜跑团')
  h.page.onNameBlur()
  const stale = h.requests.at(-1)
  h.page.onInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: '夜跑团2' } })
  stale.success({ code: 200, data: { available: false, reason: TAKEN } })
  assert.equal(h.page.data.nameError, '')
  assert.equal(h.page.data.canNext, true)
})

test('查名失败不拦人,交给创建时的终检', () => {
  const h = loadPage()
  atNameStep(h, '晨跑团')
  h.page.onNext()
  h.requests.at(-1).fail({ errMsg: 'request:fail' })
  assert.equal(h.page.data.step, 4)
  assert.equal(h.page.data.nameError, '')
})

test('名称框绑定离开事件,原因显示在名称框下方,保留原 aria-label', () => {
  assert.match(WXML, /aria-label="俱乐部名称，必填"[^>]*bindblur="onNameBlur"/)
  assert.match(WXML, /class="cc-field-err" wx:if="\{\{nameError\}\}"[^>]*><text class="cc-req"[^>]*>\*<\/text><text>\{\{nameError\}\}<\/text>/)
})
