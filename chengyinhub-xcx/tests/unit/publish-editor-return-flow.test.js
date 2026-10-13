const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')

function loadPage(relativePath, wx, transform = source => source, runtime = {}) {
  const filename = path.join(ROOT, relativePath)
  let definition = null
  const source = transform(fs.readFileSync(filename, 'utf8'))
  const sandbox = {
    Page(config) { definition = config },
    getApp() {
      return runtime.app || {
        globalData: { statusBarHeight: 20, navBarHeight: 44 },
        sendRequest() {},
      }
    },
    wx,
    setTimeout: runtime.setTimeout,
    require(request) {
      if (request.endsWith('/toast.js')) return Object.assign(() => {}, { success() {} })
      if (request.includes('motion-preference')) return { readReducedMotion: () => false }
      if (request.includes('play-visual-tokens')) return { TOPIC_EDITOR_SWITCH_COLOR: '#000000' }
      if (request.includes('advanced-game-config')) return { defaultConfig: () => ({}) }
      if (request.includes('response-shape')) return { isRecordList: Array.isArray }
      if (request.includes('safe-user-message')) return { safeUserMessage: () => '' }
      if (request.includes('merchant-access-policy')) return require('../../utils/merchant-access-policy.js')
      return {}
    },
    console,
    encodeURIComponent,
  }
  vm.runInNewContext(source, sandbox, { filename })
  assert.ok(definition, `${relativePath} 没有注册 Page`)
  const page = Object.assign({}, definition)
  page.data = JSON.parse(JSON.stringify(definition.data || {}))
  page.setData = function (patch) { Object.assign(this.data, patch) }
  return page
}

function runCreateFlow(transform = source => source) {
  const stack = ['/pages/template/index', '/pages/publish/template-intro/index']
  const wx = {
    navigateTo({ url, success }) {
      stack.push(url)
      if (success) success()
    },
    redirectTo({ url, success }) {
      stack[stack.length - 1] = url
      if (success) success()
    },
    showToast() {},
  }

  const intro = loadPage('pages/publish/template-intro/index.js', wx, transform)
  intro.goCreate()

  const naming = loadPage('pages/publish/templateadd/templateadd.js', wx, transform)
  naming.data.templateName = '梧桐夜行'
  naming.onCreateTemplate()

  return stack
}

test('创建玩法保留引导与命名层级，编辑器连续两次返回不会直接掉回发布首页', () => {
  const stack = runCreateFlow()
  assert.deepEqual(stack.map(url => url.split('?')[0]), [
    '/pages/template/index',
    '/pages/publish/template-intro/index',
    '/pages/publish/templateadd/templateadd',
    '/pages/publish/temp/index',
  ])

  stack.pop()
  assert.equal(stack.at(-1), '/pages/publish/templateadd/templateadd', '第一次返回必须回到命名')
  stack.pop()
  assert.equal(stack.at(-1), '/pages/publish/template-intro/index', '第二次返回必须回到引导')
})

test('负控：把两次 navigateTo 变回 redirectTo 时，契约必须识别出返回层级丢失', () => {
  const stack = runCreateFlow(source => source.replace(/wx\.navigateTo/g, 'wx.redirectTo'))
  assert.notDeepEqual(stack.map(url => url.split('?')[0]), [
    '/pages/template/index',
    '/pages/publish/template-intro/index',
    '/pages/publish/templateadd/templateadd',
    '/pages/publish/temp/index',
  ])
  stack.pop()
  assert.equal(stack.at(-1), '/pages/template/index', '负控必须真实复现“一次返回直接回首页”')
})

// 两个真实成功动作都必须清栈，且保留本人/商家模板库口径。
function assertCleanTemplateLibraryExit(transform = source => source) {
  for (const scope of ['PERSONAL', 'MERCHANT']) {
    for (const action of ['result', 'creation']) {
      const events = []
      const page = loadPage('pages/publish/temp/index.js', {
        reLaunch({ url }) { events.push({ type: 'reLaunch', url }) },
        redirectTo({ url }) { events.push({ type: 'redirectTo', url }) },
        navigateTo({ url }) { events.push({ type: 'navigateTo', url }) },
      }, transform)
      page.data.operationScope = scope
      page.data.dirty = false
      page.data.creationSuccess = { primaryAction: 'templates', show: true }
      if (action === 'result') page.onResultSheetClose()
      else page._leaveCreationSuccess('primaryAction')
      assert.deepEqual(events, [{
        type: 'reLaunch',
        url: '/subpackageMember/mytemplate/mytemplate' + (scope === 'MERCHANT' ? '?scope=MERCHANT' : ''),
      }], action + ':' + scope)
    }
  }
}

// 把变异限定到指定真实方法，并确认替换发生，避免源码演进后负控悄悄失效。
function mutateMethod(source, start, end, original, replacement) {
  const from = source.indexOf(start)
  const to = source.indexOf(end, from + start.length)
  assert.ok(from >= 0 && to > from, '负控必须定位真实方法')
  const method = source.slice(from, to)
  assert.equal(method.split(original).length - 1, 1, '负控必须命中唯一真实分支')
  return source.slice(0, from) + method.replace(original, replacement) + source.slice(to)
}

function loadMyTemplatePage(stackLength, events, transform = source => source) {
  const filename = path.join(ROOT, 'subpackageMember/mytemplate/mytemplate.js')
  let definition = null
  const sandbox = {
    Page(config) { definition = config },
    getApp() { return { globalData: {}, sendRequest() {} } },
    getCurrentPages() { return Array.from({ length: stackLength }, () => ({})) },
    require(request) {
      if (request.includes('datetime')) return { toTimestamp: () => 0 }
      if (request.includes('mockData')) return { getTemplates: () => [] }
      if (request.includes('response-shape')) return { isRecordList: Array.isArray }
      return {}
    },
    wx: {
      navigateBack(options) { events.push({ type: 'navigateBack', options }) },
      switchTab(options) { events.push({ type: 'switchTab', options }) },
    },
    console,
  }
  vm.runInNewContext(transform(fs.readFileSync(filename, 'utf8')), sandbox, { filename })
  assert.ok(definition, '我的节点玩法页没有注册 Page')
  return definition
}

test('保存完成后进入模板库会清理创建栈，不把旧命名页留在返回路径里', () => {
  assertCleanTemplateLibraryExit()
})

function runEditorSuccessBack(transform = source => source) {
  const events = []
  const page = loadPage('pages/publish/temp/index.js', {
    navigateBack(options) { events.push({ type: 'navigateBack', options }) },
    switchTab(options) { events.push({ type: 'switchTab', options }) },
  }, transform)
  page.destroyPreviewAudio = function () {}
  page.data.previewVisible = true
  page.data.creationSuccess = { secondaryAction: 'back', show: true }
  page._leaveCreationSuccess('secondaryAction')
  return { events, page }
}

function runCityNodeSuccessBack(transform = source => source, beforeTimer = () => {}) {
  const events = [], requests = [], timers = []
  const app = {
    globalData: {},
    getUserID: () => 1,
    getAuthorization: () => '',
    sendRequest(request) { requests.push(request) },
  }
  const page = loadPage('pages/publish/temp/index.js', {
    navigateBack(options) { events.push({ type: 'navigateBack', options }) },
    switchTab(options) { events.push({ type: 'switchTab', options }) },
  }, transform, { app, setTimeout(callback, delay) { timers.push({ callback, delay }) } })
  page.destroyPreviewAudio = function () {}
  page.prepareFormData = () => ({ title: '保存的玩法' })
  page._editorMemberId = '1'
  page.data.from = 'citynode'
  page.data.previewVisible = true
  page.data.merchantAccess = { canManageProjects: true }
  page.sendData({ title: '保存的玩法', status: 1 }, '/api/merchant/city-node/template/submit', 'publish')
  assert.equal(requests.length, 1)
  requests[0].success({ code: 200, data: 101 })
  assert.equal(timers.length, 1, '成功回调必须安排返回')
  assert.equal(timers[0].delay, 600)
  beforeTimer(page, app)
  timers[0].callback()
  return { events, page }
}

test('成功后的延迟返回保留新输入、卸载和换号保护', () => {
  for (const change of [
    page => { page.data.dirty = true },
    page => { page._disposed = true },
    (page, app) => { app.getUserID = () => 2 },
  ]) {
    const result = runCityNodeSuccessBack(source => source, change)
    assert.equal(result.events.length, 0)
    assert.equal(result.page.data.previewVisible, true)
  }
})

test('结果面板迟到关闭不得带走尚未保存的输入', () => {
  const events = []
  const page = loadPage('pages/publish/temp/index.js', {
    reLaunch(options) { events.push(options) },
  })
  page.data.dirty = true
  page.onResultSheetClose()
  assert.deepEqual(events, [])
})

test('预览层内保存成功的“返回上一页”必须离开编辑器，不能只关预览', () => {
  const result = runEditorSuccessBack()
  assert.equal(result.events[0].type, 'navigateBack')
  assert.equal(result.events[0].options.delta, 1)
  const delayed = runCityNodeSuccessBack()
  assert.equal(delayed.events[0].type, 'navigateBack')
  assert.equal(delayed.events[0].options.delta, 1)
})

test('负控：成功离场误复用 goBack 时，previewVisible 会把用户留在编辑器', () => {
  const result = runEditorSuccessBack(source => mutateMethod(source,
    '_leaveCreationSuccess(key) {', '// ===== 回填 =====',
    'else this.exitPage();', 'else this.goBack();'))
  assert.equal(result.events.length, 0)
  assert.equal(result.page.data.previewVisible, false)
  const delayed = runCityNodeSuccessBack(source => mutateMethod(source,
    'sendData: function (data, url, action) {', 'onResultSheetClose() {',
    'if (current() && !that.data.dirty) that.exitPage();',
    'if (current() && !that.data.dirty) that.goBack();'))
  assert.equal(delayed.events.length, 0)
  assert.equal(delayed.page.data.previewVisible, false)
})

test('创建栈清理后，我的节点玩法页返回仍能回到节点玩法主页', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'subpackageMember/mytemplate/mytemplate.wxml'), 'utf8')
  assert.match(wxml, /<cy-nav-bar\s+custom-back\s+bind:back="onBack"/)

  const rootEvents = []
  loadMyTemplatePage(1, rootEvents).onBack()
  assert.equal(rootEvents.length, 1)
  assert.equal(rootEvents[0].type, 'switchTab')
  assert.equal(rootEvents[0].options.url, '/pages/template/index')

  const stackedEvents = []
  loadMyTemplatePage(2, stackedEvents).onBack()
  assert.equal(stackedEvents.length, 1)
  assert.equal(stackedEvents[0].type, 'navigateBack')
  assert.equal(stackedEvents[0].options.delta, 1)
})

test('负控：任一成功离场退回 redirectTo 时契约必须判红', () => {
  for (const [start, end] of [
    ['onResultSheetClose() {', 'onCreationSuccessAction(e) {'],
    ['_leaveCreationSuccess(key) {', '// ===== 回填 ====='],
  ]) {
    const source = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.js'), 'utf8')
    const mutated = mutateMethod(source, start, end, 'wx.reLaunch(', 'wx.redirectTo(')
    assert.throws(() => assertCleanTemplateLibraryExit(() => mutated), { name: 'AssertionError' })
  }
})

test('负控：清栈页丢失主页兜底时必须判红', () => {
  const events = []
  const page = loadMyTemplatePage(1, events, source => source.replace(
    "wx.switchTab({ url: '/pages/template/index' });",
    "wx.navigateBack({ delta: 1 });"
  ))
  page.onBack()
  assert.notDeepEqual(events, [{ type: 'switchTab', options: { url: '/pages/template/index' } }])
})
