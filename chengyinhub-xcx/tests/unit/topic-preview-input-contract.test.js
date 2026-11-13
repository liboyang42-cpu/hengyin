const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

const PAGE = path.resolve(__dirname, '../../pages/templatedetail/topic-preview/index.js')

function loadPage() {
  let definition
  const requests = []
  const navigationColors = []
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    sendRequest(request) { requests.push(request) },
    getRequestErrorMessage(response, fallback) { return response && response.msg || fallback },
  }
  vm.runInNewContext(fs.readFileSync(PAGE, 'utf8'), {
    getApp: () => app,
    Page(value) { definition = value },
    require(request) {
      if (request === '../shelf/art.js') return { topicArt: () => null }
      if (request === './intro.js') return require('../../pages/templatedetail/topic-preview/intro.js')
      if (request === '../../../utils/motion-preference.js') return { readReducedMotion: () => true }
      throw new Error('Unexpected dependency: ' + request)
    },
    wx: { setNavigationBarColor(value) { navigationColors.push(value) } },
    setTimeout, clearTimeout,
  }, { filename: PAGE })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch) },
  })
  return { page, requests, navigationColors }
}

test('preview without an ID keeps the existing refusal state and never requests info, including retry', () => {
  for (const options of [undefined, {}, { id: '' }, { scope: 'MERCHANT' }]) {
    const { page, requests } = loadPage()
    page.onLoad(options)
    assert.equal(requests.length, 0)
    assert.equal(page.data.loading, false)
    assert.equal(page.data.errorRefused, true)
    assert.equal(page.data.errorMsg, '缺少模板ID')
    assert.deepEqual(Array.from(page.data.cards), [])
    assert.equal(page.data.name, '')
    page.load()
    assert.equal(requests.length, 0)
    assert.equal(page.data.errorMsg, '缺少模板ID')
  }
})

test('valid IDs retain the same POST read, scope state and successful detail projection', () => {
  for (const id of ['42', '9007199254740993']) {
    const { page, requests, navigationColors } = loadPage()
    page.onLoad({ id, scope: 'MERCHANT', readonly: '1' })
    assert.equal(page._id, id)
    assert.equal(page._readonly, true)
    assert.equal(page.data.light, true)
    assert.equal(navigationColors.length, 1)
    assert.equal(requests.length, 1)
    const request = requests[0]
    assert.equal(request.url, '/api/template/topic-template/info')
    assert.equal(request.method, 'POST')
    assert.equal(request.data.id, id)
    assert.equal(request.hideLoading, true)
    assert.equal(request.silentError, true)
    assert.equal(page.data.loading, true)
    request.success({ code: 200, data: { id, name: '主题', imgUrl: '/cover.png', games: [], chapters: [] } })
    assert.equal(page.data.loading, false)
    assert.equal(page.data.errorRefused, false)
    assert.equal(page.data.errorMsg, '')
    assert.equal(page.data.name, '主题')
    assert.equal(page.data.cover, '/cover.png')
    assert.equal(page.data.cards.length, 1)
    assert.equal(page.data.entered, true)
  }
})

test('valid-ID hidden template refusals keep the exact server message without artwork substitution', () => {
  const { page, requests } = loadPage()
  page.onLoad({ id: '42' })
  requests[0].success({ code: 404, msg: '模板不存在' })
  assert.equal(page.data.loading, false)
  assert.equal(page.data.errorRefused, true)
  assert.equal(page.data.errorMsg, '模板不存在')
  assert.equal(page.data.cover, '')
  assert.equal(page.data.cards.length, 0)
})

test('a missing-ID retry invalidates any earlier detail response', () => {
  const { page, requests } = loadPage()
  page.onLoad({ id: '42' })
  page._id = undefined
  page.load()
  requests[0].success({ code: 200, data: { name: '旧主题', games: [] } })
  assert.equal(requests.length, 1)
  assert.equal(page.data.errorMsg, '缺少模板ID')
  assert.equal(page.data.errorRefused, true)
  assert.equal(page.data.loading, false)
  assert.equal(page.data.name, '')
})
