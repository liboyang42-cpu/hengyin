'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const { createRequire } = require('node:module')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js') // node:vm + 沙箱里装真 utils/toast.js·loading.js·modal.js

const ROOT = path.resolve(__dirname, '../..')
const JS_PATH = 'subpackageMember/couponInfo/couponInfo.js'
const WXML_PATH = 'subpackageMember/couponInfo/couponInfo.wxml'
const JSON_PATH = 'subpackageMember/couponInfo/couponInfo.json'

function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
}

function setByPath(target, key, value) {
  const parts = key.split('.')
  let cursor = target
  parts.slice(0, -1).forEach((part) => {
    if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {}
    cursor = cursor[part]
  })
  cursor[parts[parts.length - 1]] = value
}

function loadPage(source = read(JS_PATH)) {
  let owner = 1
  const timers = []
  const requests = []
  const toasts = []
  const tips = []
  const modals = []
  let backs = 0
  let definition
  const absolutePath = path.join(ROOT, JS_PATH)
  const app = {
    getUserID: () => owner,
    getRequestErrorMessage: (res, fallback) => (res && (res.msg || res.errMsg)) || fallback,
    sendRequest: (options) => requests.push(options),
    tips: (message) => tips.push(message),
  }
  const wx = {
    navigateBack: () => { backs += 1 },
    showModal: (options) => modals.push(options),
    showToast: (options) => toasts.push(options && options.title),
  }

  vm.runInNewContext(source, {
    console,
    getApp: () => app,
    // 有上一页:全页返回合同(#806)按 getCurrentPages().length 选 navigateBack 还是根栈落点
    getCurrentPages: () => [{}, {}],
    Page: (config) => { definition = config },
    require: name => name.includes('merchant-theme') ? { merchantPageShow() {}, merchantPageRestore() {} } : createRequire(absolutePath)(name),
    setTimeout(callback) { timers.push(callback); return timers.length },
    clearTimeout(id) { timers[id - 1] = null },
    wx,
  }, { filename: absolutePath })

  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) {
      Object.entries(patch).forEach(([key, value]) => setByPath(this.data, key, value))
      if (done) done.call(this)
    },
  })

  return { page, requests, toasts, tips, modals, timers, setOwner: id => { owner = id }, get backs() { return backs } }
}

function fillValidForm(page) {
  page.data.formData = {
    name: '新人礼券',
    startTime: '2026-08-23 09:00:00',
    endTime: '2026-08-24 18:00:00',
    publishCount: 20,
    couponType: 1,
    description: '到店使用',
  }
  page.data.canSubmit = true
}

function assertRecoveryContract(source) {
  const initialData = source.slice(source.indexOf('data:'), source.indexOf('onLoad('))
  const submit = source.slice(source.indexOf('onSubmit() {'), source.lastIndexOf('\n})'))
  assert.match(initialData, /submitError:\s*''/)
  assert.match(initialData, /submitErrorKind:\s*'data'/)
  assert.match(submit, /submitError:\s*message/)
  assert.match(submit, /submitErrorKind:\s*reason\s*===\s*'network'\s*\?\s*'network'\s*:\s*'data'/)
  assert.doesNotMatch(submit, /app\.tips\(|wx\.showToast\(\{\s*title:\s*message/)
}

test('发布失败原位可见、表单保留且重试不重复提交', () => {
  const h = loadPage()
  fillValidForm(h.page)

  h.page.onSubmit()
  h.page.onSubmit()
  assert.equal(h.requests.length, 1, '同步连点只能发一次请求')
  assert.equal(h.page.data.isSubmitting, true)
  assert.equal(h.requests[0].hideLoading, true, '进度应留在表单主按钮')
  assert.equal(h.requests[0].silentError, true, '页面自己承接错误，不能叠全局 toast')

  h.requests[0].success({ code: 500, msg: '投放数需要重新核对' })
  h.requests[0].complete()
  assert.equal(h.page.data.isSubmitting, false)
  assert.equal(h.page.data.submitErrorKind, 'data')
  assert.equal(h.page.data.submitError, '投放数需要重新核对')
  assert.equal(h.page.data.formData.name, '新人礼券')
  assert.deepEqual(h.toasts, [])
  assert.deepEqual(h.tips, [])

  h.page.onSubmit()
  assert.equal(h.requests.length, 2)
  assert.equal(h.page.data.submitError, '', '重试在途要清掉过期错误')
  h.requests[1].fail({ errMsg: 'request:fail timeout' })
  h.requests[1].complete()
  assert.equal(h.page.data.isSubmitting, false)
  assert.equal(h.page.data.submitErrorKind, 'network')
  assert.match(h.page.data.submitError, /网络|重试/)
  assert.equal(h.page.data.formData.description, '到店使用')
})

test('已填表单离开前二次确认，空表单直接返回', () => {
  const clean = loadPage()
  clean.page.onCancel()
  assert.equal(clean.backs, 1)
  assert.equal(clean.modals.length, 0)

  const dirty = loadPage()
  dirty.page.onNameInput({ detail: { value: '已填内容' } })
  dirty.page.onCancel()
  assert.equal(dirty.backs, 0)
  assert.equal(dirty.modals.length, 1)
  dirty.modals[0].success({ confirm: false })
  assert.equal(dirty.backs, 0, '未确认放弃时不能丢表单')
  dirty.modals[0].success({ confirm: true })
  assert.equal(dirty.backs, 1)
})

test('修改字段清掉旧错误，页面用内联恢复态与可访问按钮', () => {
  const h = loadPage()
  h.page.data.submitError = '旧错误'
  h.page.onDescriptionInput({ detail: { value: '新说明' } })
  assert.equal(h.page.data.submitError, '')

  const wxml = read(WXML_PATH).replace(/<!--[\s\S]*?-->/g, '')
  const json = JSON.parse(read(JSON_PATH))
  assert.equal(json.usingComponents['cy-inline-error'], '/components/cy/inline-error/index')
  assert.match(wxml, /<cy-nav-bar\b[^>]*custom-back[^>]*bind:back="onCancel"/s)
  assert.match(wxml, /<cy-inline-error\b[^>]*wx:if="\{\{submitError\}\}"[^>]*kind="\{\{submitErrorKind\}\}"[^>]*action="\{\{submitErrorRetryable \? '重试' : ''\}\}"[^>]*bind:action="onSubmit"/s)
  assert.match(wxml, /<cy-btn\b[^>]*loading="\{\{isSubmitting\}\}"[^>]*disabled="\{\{!canSubmit \|\| isSubmitting\}\}"[^>]*aria-disabled="\{\{!canSubmit \|\| isSubmitting\}\}"[^>]*bindtap="onSubmit"/s)
})

test('负控：摘掉持久错误写入后恢复契约必须变红', () => {
  const source = read(JS_PATH)
  assertRecoveryContract(source)
  const broken = source.replace(/\n\s*submitError:\s*message,/, '')
  assert.notEqual(broken, source, '负控锚点失效：未找到 submitError 写入')
  assert.throws(() => assertRecoveryContract(broken), assert.AssertionError)
})

for (const lifecycle of ['onHide', 'onUnload']) {
  test(`successful publish followed by ${lifecycle} cannot navigate another page`, () => {
    const h=loadPage(); fillValidForm(h.page); h.page.onSubmit()
    h.requests[0].success({code:200,data:{id:1}}); h.page[lifecycle]()
    h.timers.forEach(callback=>{if(callback)callback()})
    assert.equal(h.backs,0)
    assert.equal(h.page.data.formData.name,'新人礼券')
  })
  test(`publish response after ${lifecycle} cannot toast or schedule navigation`, () => {
    const h=loadPage(); fillValidForm(h.page); h.page.onSubmit(); h.page[lifecycle]()
    const before=JSON.stringify(h.page.data); h.requests[0].success({code:200,data:{id:1}})
    h.requests[0].complete(); h.timers.forEach(callback=>{if(callback)callback()})
    assert.equal(h.backs,0); assert.deepEqual(h.toasts,[])
    if (lifecycle === 'onUnload') assert.equal(JSON.stringify(h.page.data),before)
    else {
      assert.equal(h.page.data.isSubmitting,false, 'hidden request must settle without navigation')
      assert.equal(h.page.data.formData.name,'新人礼券')
    }
  })
}
test('publish response for a previous actor cannot mutate the current form', () => {
  const h=loadPage(); fillValidForm(h.page); h.page.onSubmit(); h.setOwner(2)
  const before=JSON.stringify(h.page.data); h.requests[0].success({code:200,data:{id:1}})
  h.requests[0].complete(); h.timers.forEach(callback=>{if(callback)callback()})
  assert.equal(JSON.stringify(h.page.data),before); assert.equal(h.backs,0); assert.deepEqual(h.toasts,[])
})
test('normal successful publish returns once and the timer checks current actor', () => {
  const h=loadPage(); fillValidForm(h.page); h.page.onSubmit()
  h.requests[0].success({code:200,data:{id:1}}); h.requests[0].complete()
  assert.equal(h.page.data.isSubmitting,false)
  h.timers.filter(Boolean)[0](); assert.equal(h.backs,1)
  const changed=loadPage(); fillValidForm(changed.page); changed.page.onSubmit()
  changed.requests[0].success({code:200,data:{id:1}}); changed.setOwner(2)
  changed.timers.forEach(callback=>{if(callback)callback()}); assert.equal(changed.backs,0)
})

test('hide keeps a publish in flight locked, completion settles without resending on show', () => {
  const h=loadPage(); fillValidForm(h.page); h.page.onSubmit(); h.page.onHide()
  h.page.onSubmit(); assert.equal(h.requests.length,1); assert.equal(h.page.data.isSubmitting,true)
  h.requests[0].success({code:200,data:{id:1}}); h.requests[0].complete()
  assert.equal(h.page.data.isSubmitting,false); assert.deepEqual(h.toasts,[])
  h.page.onShow(); assert.equal(h.requests.length,1); assert.equal(h.page.data.isSubmitting,false)
  assert.equal(h.backs,0); assert.equal(h.timers.filter(Boolean).length,0)
})

test('publish accepts existing high string owner but empty owner cannot publish', () => {
  const h=loadPage(); fillValidForm(h.page); h.setOwner('9223372036854775807'); h.page.onSubmit()
  assert.equal(h.requests.length,1)
  const empty=loadPage(); fillValidForm(empty.page); empty.setOwner(''); empty.page.onSubmit()
  assert.equal(empty.requests.length,0)
})
