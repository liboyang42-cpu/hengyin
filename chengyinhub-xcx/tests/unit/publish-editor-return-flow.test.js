const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')

function loadPage(relativePath, wx, transform = source => source) {
  const filename = path.join(ROOT, relativePath)
  let definition = null
  const source = transform(fs.readFileSync(filename, 'utf8'))
  const sandbox = {
    Page(config) { definition = config },
    getApp() {
      return {
        globalData: { statusBarHeight: 20, navBarHeight: 44 },
        sendRequest() {},
      }
    },
    wx,
    require(request) {
      if (request.endsWith('/theme.js')) return { applyTheme: theme => { if (wx.onTheme) wx.onTheme(theme) } }
      if (request.includes('motion-preference')) return { readReducedMotion: () => false }
      if (request.includes('play-visual-tokens')) return { TOPIC_EDITOR_SWITCH_COLOR: '#000000' }
      if (request.includes('advanced-game-config')) return { defaultConfig: () => ({}) }
      if (request.includes('node-game-catalog')) return require('../../pages/publish/utils/publish/node-game-catalog.js')
      if (request.includes('template-tools')) return require('../../pages/publish/utils/publish/template-tools.js')
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

  // 2026-10-22 owner:去掉「起名字」一步,引导页直接进编辑器新建
  const intro = loadPage('pages/publish/template-intro/index.js', wx, transform)
  intro.goCreate()

  return stack
}

test('创建玩法保留引导层级，编辑器返回回到引导而不是掉回发布首页', () => {
  const stack = runCreateFlow()
  assert.deepEqual(stack.map(url => url.split('?')[0]), [
    '/pages/template/index',
    '/pages/publish/template-intro/index',
    '/pages/publish/temp/index',
  ])
  assert.equal(stack.at(-1), '/pages/publish/temp/index', '新建直接进编辑器,不带 id(不经过起名页)')

  stack.pop()
  assert.equal(stack.at(-1), '/pages/publish/template-intro/index', '返回必须回到引导')
})

test('负控：把 navigateTo 变回 redirectTo 时，契约必须识别出返回层级丢失', () => {
  const stack = runCreateFlow(source => source.replace(/wx\.navigateTo/g, 'wx.redirectTo'))
  assert.notDeepEqual(stack.map(url => url.split('?')[0]), [
    '/pages/template/index',
    '/pages/publish/template-intro/index',
    '/pages/publish/temp/index',
  ])
  stack.pop()
  assert.equal(stack.at(-1), '/pages/template/index', '负控必须真实复现“一次返回直接回首页”')
})

/* 2026-09-06:本页现在有**两条**去模板库的离场路径 ——
 *   ① creation-success 的「查看模板库」(flow[key] === 'templates');
 *   ② 结果面板收掉后的 onResultSheetClose(「模板草稿已保存 / 已发布」那一支改走面板)。
 * 两条都必须 reLaunch:清栈才不会在模板库上面压着一个已经保存完的编辑器。
 * ⚠️ 只钉第一条是不够的 —— 加第二条的时候我就把原来的负控变成了空负控:
 * 它用 String.replace 只换第一处,新加的那处顶在前面,变异后另一处仍是 reLaunch,
 * 契约照样绿。所以这里改成「逐条都要满足」+「全仓不许出现指向模板库的 redirectTo」。 */
function hasCleanTemplateLibraryExit(source) {
  const viaCreationSuccess = /flow\[key\]\s*===\s*'templates'\)\s*wx\.reLaunch\(\{\s*url:\s*'\/subpackageMember\/mytemplate\/mytemplate'/.test(source)
  const viaResultSheet = /onResultSheetClose\(\)\s*\{[\s\S]{0,240}?wx\.reLaunch\(\{\s*url:\s*'\/subpackageMember\/mytemplate\/mytemplate'/.test(source)
  const noDirtyExit = !/wx\.(redirectTo|navigateTo)\(\{\s*url:\s*'\/subpackageMember\/mytemplate\/mytemplate'/.test(source)
  return viaCreationSuccess && viaResultSheet && noDirtyExit
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
  const source = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.js'), 'utf8')
  assert.equal(hasCleanTemplateLibraryExit(source), true)
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

test('预览层内保存成功的“返回上一页”必须离开编辑器，不能只关预览', () => {
  const result = runEditorSuccessBack()
  assert.equal(result.events[0].type, 'navigateBack')
  assert.equal(result.events[0].options.delta, 1)
  const source = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.js'), 'utf8')
  assert.match(source, /setTimeout\(function \(\) \{ if \(current\(\)\) that\.exitPage\(\); \}, 600\)/)
})

test('负控：成功离场误复用 goBack 时，previewVisible 会把用户留在编辑器', () => {
  const result = runEditorSuccessBack(source => source
    .replace("if (flow[key] === 'back') this.exitPage();", "if (flow[key] === 'back') this.goBack();")
    .replace('setTimeout(function () { if (current()) that.exitPage(); }, 600);', 'setTimeout(function () { if (current()) that.goBack(); }, 600);'))
  assert.equal(result.events.length, 0)
  assert.equal(result.page.data.previewVisible, false)
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

test('负控：成功离场退回 redirectTo 时契约必须判红', () => {
  const source = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.js'), 'utf8')
  // ⚠️ 必须 replaceAll:本页有两条去模板库的离场路径,只换第一处会留下另一处仍是
  // reLaunch —— 那样这条负控就永远红不了(2026-09-06 实际踩到)。
  const mutated = source.replaceAll("wx.reLaunch({ url: '/subpackageMember/mytemplate/mytemplate'",
    "wx.redirectTo({ url: '/subpackageMember/mytemplate/mytemplate'")
  assert.notEqual(mutated, source, '负控必须真实改动成功离场 API')
  assert.equal(hasCleanTemplateLibraryExit(mutated), false)
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

test('命名页进入编辑器后返回可再次确认，在途连点仍只导航一次', () => {
  const navigations = []
  const page = loadPage('pages/publish/templateadd/templateadd.js', {
    navigateTo(options) { navigations.push(options) },
  })
  page.onInputChange({ detail: { value: '返回后继续创建' } })
  page.onCreateTemplate()
  page.onCreateTemplate()
  assert.equal(navigations.length, 1)
  assert.equal(page.data.navigating, true)
  navigations[0].success()
  page.onShow()
  assert.equal(page.data.navigating, false, '返回命名页不能一直转圈')
  assert.equal(page.data.templateName, '返回后继续创建')
  page.onCreateTemplate()
  assert.equal(navigations.length, 2)
})


test('创建流程原生状态栏跟随浅色编辑与深色玩法预览，关闭后恢复', () => {
  const themes = []
  const wx = { onTheme(theme) { themes.push(theme) } }
  // 2026-10-22 owner 定稿:引导页改深底(漂浮圆圈),命名页仍浅色
  loadPage('pages/publish/template-intro/index.js', wx).onShow()
  assert.equal(themes.at(-1), 'dark')
  loadPage('pages/publish/templateadd/templateadd.js', wx).onShow()
  assert.equal(themes.at(-1), 'light')
  const page = loadPage('pages/publish/temp/index.js', wx)
  page.onShow()
  assert.equal(themes.at(-1), 'light')
  page.data.previewVisible = true
  page.data.pvKit = { mode: 'CARD' }
  page.data.pvStepKey = 'challenge'
  page.onShow()
  assert.equal(themes.at(-1), 'dark')
  page.destroyPreviewAudio = () => {}
  page.goBack()
  assert.equal(page.data.previewVisible, false)
  assert.equal(themes.at(-1), 'light')
})

test('命名成功dirty只更新内部状态，失败仍保留未保存输入', () => {
  const nav = []
  const page = loadPage('pages/publish/templateadd/templateadd.js', {navigateTo(o){nav.push(o)}})
  const patches = [], original = page.setData
  page.setData = function(p) { patches.push(p); return original.call(this,p) }
  page.onInputChange({detail:{value:'新玩法'}})
  page.onCreateTemplate()
  nav[0].fail()
  assert.equal(page.data.dirty,true)
  page.onCreateTemplate()
  nav[1].success()
  assert.equal(page.data.dirty,false)
  assert.equal(page.data.navigating,false)
  assert.equal(patches.some(p => Object.hasOwn(p,'dirty')),false)
})
