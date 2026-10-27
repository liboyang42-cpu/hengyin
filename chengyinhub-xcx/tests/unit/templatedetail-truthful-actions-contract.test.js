'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const MODULE = path.join(ROOT, 'pages/templatedetail/templatedetail.js')
const read = (relativePath) => fs.readFileSync(path.join(ROOT, relativePath), 'utf8')

function loadPage(options = {}) {
  const requests = []
  const tips = []
  const identity = { member: '11', role: 'user', type: 'user' }
  const navigations = []
  const measurements = []
  const nextTicks = []
  let definition
  global.getApp = () => ({
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getRequestErrorMessage: (response, fallback) => (response && (response.msg || response.errMsg)) || fallback,
    getUserID: () => identity.member,
    getUserRole: () => identity.role,
    getUserType: () => identity.type,
    sendRequest: (options) => requests.push(options),
    tips: (message) => tips.push(message),
  })
  global.Page = (config) => { definition = config }
  global.wx = {
    setNavigationBarColor() {},
    setBackgroundColor() {},
    navigateTo(navigation) { navigations.push(navigation); if (!options.deferNavigation && navigation.success) navigation.success() },
    switchTab(navigation) { navigations.push(navigation) },
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    nextTick: (done) => { if (options.deferTicks) nextTicks.push(done); else done() },
    createSelectorQuery: () => ({
      in() { return this },
      select() { return this },
      boundingClientRect() { return this },
      exec(done) { if (options.deferMeasurements) measurements.push(done); else done([]) },
    }),
  }
  delete require.cache[require.resolve(MODULE)]
  require(MODULE)
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) {
      Object.assign(this.data, patch)
      if (done) done.call(this)
    },
  })
  return { page, requests, tips, identity, navigations, measurements, nextTicks }
}

function reply(request, title) {
  request.success({ code: 200, data: { id: request.data.id, title, canEdit: request.url === '/api/template/myinfo' } })
  request.complete()
}

test('older template response cannot overwrite newer detail or finish its loading', () => {
  const h = loadPage()
  h.page.data.id = 8
  h.page.getData()
  h.page.getData()
  h.requests[0].fail({ msg: 'old network error' })
  h.requests[0].complete()
  assert.equal(h.page.data.loading, true)
  assert.equal(h.page.data.loadError, false)
  reply(h.requests[1], 'new detail')
  const current = JSON.stringify(h.page.data)
  reply(h.requests[0], 'old detail')
  assert.equal(JSON.stringify(h.page.data), current)
})

test('unloaded template ignores all late callbacks and does not issue new requests', () => {
  const h = loadPage()
  h.page.data.id = 8
  h.page.getData()
  h.page.onUnload()
  const current = JSON.stringify(h.page.data)
  reply(h.requests[0], 'disposed detail')
  h.requests[0].fail({ msg: 'disposed error' })
  h.page.getData()
  assert.equal(JSON.stringify(h.page.data), current)
  assert.equal(h.requests.length, 1)
})

for (const [name, change] of [
  ['member', h => { h.identity.member = '22' }],
  ['role', h => { h.identity.role = 'merchant' }],
  ['user type', h => { h.identity.type = 'merchant' }],
  ['template id', h => { h.page.data.id = 9 }],
  ['source scope', h => { h.page.data.scope = 'my' }],
  ['operation scope', h => { h.page.data.operationScope = 'MERCHANT' }],
]) {
  test('changed ' + name + ' rejects the earlier template callback', () => {
    const h = loadPage()
    h.page.data.id = 8
    h.page.getData()
    change(h)
    const current = JSON.stringify(h.page.data)
    reply(h.requests[0], 'previous context detail')
    h.requests[0].fail({ msg: 'previous context error' })
    assert.equal(JSON.stringify(h.page.data), current)
  })
}

test('show after identity change clears previous detail and preview then reloads once', () => {
  const h = loadPage()
  h.page.data.id = 8
  h.page.data.scope = 'my'
  h.page.getData()
  reply(h.requests[0], 'private old detail')
  h.page.data.popDavid = true
  h.page.data.inputAnswer = 'previous answer'
  h.identity.member = '22'
  h.page.onShow()
  assert.notEqual(h.page.data.info.title, 'private old detail')
  assert.equal(h.page.data.popDavid, false)
  assert.equal(h.page.data.inputAnswer, '')
  assert.equal(h.requests.length, 2)
  assert.equal(h.requests[1].url, '/api/template/myinfo')
  h.page.onShow()
  assert.equal(h.requests.length, 2)
})

test('hide with unchanged context keeps its valid response and show does not refetch', () => {
  const h = loadPage()
  h.page.data.id = 8
  h.page.getData()
  h.page.onHide()
  reply(h.requests[0], 'valid hidden response')
  h.page.onShow()
  assert.equal(h.page.data.info.title, 'valid hidden response')
  assert.equal(h.requests.length, 1)
})

test('return from own editor refreshes the same personal template once', () => {
  const h = loadPage()
  h.page.data.id = 8
  h.page.data.scope = 'my'
  h.page.getData()
  reply(h.requests[0], 'before edit')
  h.page.goPrimary()
  assert.equal(h.navigations[0].url, '/pages/publish/temp/index?id=8&scope=my')
  h.page.onHide()
  h.page.onShow()
  assert.equal(h.requests.length, 2)
  assert.equal(h.requests[1].data.id, 8)
  assert.equal(h.requests[1].url, '/api/template/myinfo')
  reply(h.requests[1], 'after edit')
  h.page.onShow()
  assert.equal(h.requests.length, 2)
  assert.equal(h.page.data.info.title, 'after edit')
})

test('merchant own editor keeps its existing route and reload scope', () => {
  const h = loadPage()
  h.page.onLoad({ id: '8', scope: 'my', operationScope: 'MERCHANT' })
  reply(h.requests[0], 'owned merchant template')
  h.page.goPrimary()
  assert.equal(h.navigations[0].url, '/pages/publish/temp/index?id=8&scope=MERCHANT&templateScope=my')
  h.page.onShow()
  assert.equal(h.requests.length, 2)
  assert.deepEqual(h.requests[1].data, { id: '8', scope: 'MERCHANT' })
})

test('failed or stale editor navigation does not arm a later unrelated refresh', () => {
  const h = loadPage({ deferNavigation: true })
  h.page.onLoad({ id: '8', scope: 'my' })
  reply(h.requests[0], 'owned template')
  h.page.goPrimary()
  h.page.onShow()
  assert.equal(h.requests.length, 1)
  h.identity.member = '22'
  h.navigations[0].success()
  h.page.onShow()
  assert.equal(h.requests.length, 2)
  h.page.onShow()
  assert.equal(h.requests.length, 2)
  reply(h.requests[1], 'new account owned template')
  h.page.goPrimary()
  h.page.onUnload()
  h.navigations[1].success()
  assert.equal(h.page._editorContext, null)
})

test('disposed page does not begin a deferred text query', () => {
  const h = loadPage({ deferTicks: true, deferMeasurements: true })
  h.page.onLoad({ id: '8' })
  reply(h.requests[0], 'loaded detail')
  h.page.onUnload()
  h.nextTicks[0]()
  assert.equal(h.measurements.length, 0)
})

test('return from library application never treats its public id as a personal template', () => {
  const h = loadPage()
  h.page.data.id = 8
  h.page.getData()
  h.page.goPrimary()
  h.page.onHide()
  h.page.onShow()
  assert.equal(h.requests.length, 1)
  assert.equal(h.navigations[0].url, '/pages/publish/temp/index?id=8')
})

test('missing id invalidates the earlier detail instead of allowing its result back in', () => {
  const h = loadPage()
  h.page.data.id = 8
  h.page.getData()
  h.page.data.id = 0
  h.page.getData()
  const current = JSON.stringify(h.page.data)
  reply(h.requests[0], 'old id')
  assert.equal(JSON.stringify(h.page.data), current)
  assert.equal(h.requests.length, 1)
})

test('deferred old text measurement cannot change new detail or a disposed page', () => {
  const h = loadPage({ deferMeasurements: true })
  h.page.data.id = 8
  h.page.getData()
  reply(h.requests[0], 'first detail')
  h.page.getData()
  reply(h.requests[1], 'second detail')
  assert.equal(h.measurements.length, 2)
  const current = JSON.stringify(h.page.data)
  h.measurements[0]([{ height: 300 }, { height: 300 }])
  assert.equal(JSON.stringify(h.page.data), current)
  h.page.onUnload()
  h.measurements[1]([{ height: 300 }, { height: 300 }])
  assert.equal(JSON.stringify(h.page.data), current)
})

// 2026-10-27 P-42:业主删掉 ⋯ 和它的「分享玩法 / 举报」弹层,分享直接放在主动作旁(open-type="share")
test('「分享玩法」是页面上的真实小程序分享按钮，不再藏在 ⋯ 弹层里', () => {
  const wxml = read('pages/templatedetail/templatedetail.wxml').replace(/<!--[\s\S]*?-->/g, '')
  assert.match(wxml, /<button\b[^>]*class="xb-share"[^>]*open-type="share"[^>]*aria-label="分享玩法"[^>]*>/s)
  assert.doesNotMatch(wxml, /openMore|moreShow|xb-sheet-item/, '⋯ 弹层不许回潮')
  assert.match(read('pages/templatedetail/templatedetail.js'), /onShareAppMessage\(\)/)
})

test('节点预览不判对错，完成后关闭并清理上次输入', () => {
  const h = loadPage()
  h.page.data.popDavid = true
  h.page.onSelectOption({ currentTarget: { dataset: { value: '选项内容', option: 'A' } } })
  assert.equal(h.page.data.canSubmitPreview, true)
  h.page.completePreview()
  assert.equal(h.page.data.popDavid, false)
  assert.equal(h.page.data.canSubmitPreview, false)
  assert.equal(h.page.data.selectedOptionKey, '')
  assert.equal(h.page.data.inputAnswer, '')
  assert.deepEqual(h.tips, [], '预览不应假装校验后再用 tips 拒绝')

  const wxml = read('pages/templatedetail/templatedetail.wxml').replace(/<!--[\s\S]*?-->/g, '')
  assert.doesNotMatch(wxml, /只展示题面与作答方式，不判断对错/)
  assert.equal((wxml.match(/bindtap="completePreview"/g) || []).length, 2)
  assert.equal((wxml.match(/>完成预览<\/cy-btn>/g) || []).length, 2)
  assert.doesNotMatch(wxml, /试玩验证\s*›|bind:disabledtap="validateAnswer"/)
  assert.doesNotMatch(read('pages/templatedetail/templatedetail.js'), /validateAnswer\(\)[\s\S]*请先报名参与活动/)
})

test('详情请求静默失败并在页内保留真实错误文案', () => {
  const h = loadPage()
  h.page.data.id = 8
  h.page.getData()
  assert.equal(h.requests.length, 1)
  assert.equal(h.requests[0].hideLoading, true)
  assert.equal(h.requests[0].silentError, true)
  h.requests[0].fail({ msg: '玩法详情暂时不可用' })
  h.requests[0].complete()
  assert.equal(h.page.data.loading, false)
  assert.equal(h.page.data.loadError, true)
  assert.equal(h.page.data.loadErrorMsg, '玩法详情暂时不可用')
  assert.deepEqual(h.tips, [])

  const wxml = read('pages/templatedetail/templatedetail.wxml')
  assert.match(wxml, /<cy-error\b[^>]*sub="\{\{loadErrorMsg\}\}"[^>]*bind:retry="onRetry"/s)
})

test('更多操作与预览链接使用图标库，不用文字符号伪造图标', () => {
  const wxml = read('pages/templatedetail/templatedetail.wxml')
  const json = JSON.parse(read('pages/templatedetail/templatedetail.json'))
  assert.equal(json.usingComponents['cy-icon'], '/components/cy/icon/index')
  // 2026-10-27 P-42:⋯ 已删,右侧圆钮换成分享图标
  assert.match(wxml, /class="xb-share"[^>]*>[\s\S]*<cy-icon name="share"/)
  // 2026-08-26 能力 tab 压成标签行:「查看预览」行变成动作 chip,文案挪进 js 的 chip 数据,
  // 所以锚点两边各钉一半 —— 意图不变:预览入口必须用图标库,不许拿 › 文字冒充箭头。
  const js = fs.readFileSync(path.join(__dirname, '../../pages/templatedetail/templatedetail.js'), 'utf8')
  assert.match(wxml, /class="xb-chip xb-chip--act"[\s\S]*<cy-icon name="arrow-right"/)
  assert.match(js, /text:\s*'节点预览',\s*action:\s*'preview'/)
  assert.doesNotMatch(wxml, />···<|>试玩验证 ›</)
})

test('负控：分享退回普通 view 必须判红', () => {
  const source = read('pages/templatedetail/templatedetail.wxml')
  assert.match(source, /open-type="share"/)
  // 2026-10-27 P-42:分享改成主动作旁的图标按钮(aria-label 分享玩法),锚点随之换
  const broken = source.replace(/<button([^>]*)open-type="share"([^>]*)>([\s\S]*?)<\/button>/, '<view$1$2>$3</view>')
  assert.notEqual(broken, source, '负控锚点失效')
  assert.doesNotMatch(broken, /open-type="share"/)
})
