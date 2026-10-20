const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-249:商家信息页选点半屏「去完善品类」「去创建玩法」连点只跳一次;与 BL-236 共用 this._navigating;complete/onShow 后可再跳

const ROOT = path.resolve(__dirname, '../..')

function loadPage(rel) {
  let definition
  const navs = []
  const base = { navigateTo: (o) => navs.push(o), getStorageSync: () => '', getSystemInfoSync: () => ({}), getWindowInfo: () => ({}) }
  const wx = new Proxy(base, { get(t, k) { return k in t ? t[k] : () => {} } })
  const app = new Proxy({ globalData: {}, sendRequest() { return { abort() {} } } }, {
    get(t, k) { return k in t ? t[k] : () => '' },
  })
  const pageDir = path.dirname(path.join(ROOT, rel))
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, rel), 'utf8'), {
    getApp: () => app,
    Page(page) { definition = page },
    Component() {},
    require(request) { return require(path.resolve(pageDir, request)) },
    Set, Map, Date, JSON, Promise, wx, setTimeout: () => 0, clearTimeout() {}, console,
  }, { filename: path.join(ROOT, rel) })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb() },
  })
  return { page, navs }
}

const INFO = 'pages/topic/merchantinfo/merchantinfo.js'

const CASES = [
  ['goMerchantCategory', '/pages/merchant/decor/index'],
  ['goCreateTemplate', '/pages/publish/temp/index?scope=MERCHANT'],
]

for (const [name, url] of CASES) {
  test(`BL-249:merchantinfo ${name} complete 未回调时连调两次只跳 1 次,complete 后可再跳`, () => {
    const { page, navs } = loadPage(INFO)
    page.data.topicShow = true
    page[name]()
    page[name]()
    assert.equal(navs.length, 1)
    assert.equal(navs[0].url, url)
    assert.equal(page.data.topicShow, false)
    navs[0].complete()
    page[name]()
    assert.equal(navs.length, 2)
  })
}

test('BL-249:两个入口与 goTemplateDetail 共用一把锁;锁住时不收半屏;onShow 兜底清锁', () => {
  const { page, navs } = loadPage(INFO)
  page.goMerchantCategory()
  page.data.topicShow = true
  page.goCreateTemplate()
  page.goTemplateDetail({ currentTarget: { dataset: { id: 6 } } })
  assert.equal(navs.length, 1)
  assert.equal(page.data.topicShow, true)
  page._navigating = false // onShow 末尾已有 this._navigating = false(BL-236),onShow 依赖全局 getApp 主题,这里只验清锁后的行为
  page.goCreateTemplate()
  assert.equal(navs.length, 2)
  assert.equal(navs[1].url, '/pages/publish/temp/index?scope=MERCHANT')
})

test('BL-249:两个入口先判锁再收半屏,跳转带 complete 清锁;onShow 兜底清锁', () => {
  const source = fs.readFileSync(path.join(ROOT, INFO), 'utf8')
  for (const name of ['goMerchantCategory', 'goCreateTemplate']) {
    const body = source.slice(source.indexOf(`  ${name}() {`), source.indexOf('\n  },', source.indexOf(`  ${name}() {`)))
    assert.match(body, /if \(this\._navigating\) return; this\.setData\(\{ topicShow: false \}\);/)
    assert.match(body, /this\._navigating = true; wx\.navigateTo\(\{[^\n]*complete: \(\) => \{ this\._navigating = false; \}/)
  }
  assert.match(source, /onShow\(\) \{[\s\S]{0,300}this\._navigating = false; \},/)
})
