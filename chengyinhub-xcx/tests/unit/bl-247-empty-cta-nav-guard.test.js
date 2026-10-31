const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-247:收件箱空态「去发现找人」(2026-10-29 改 switchTab 去发现页帖文)与客户页空态「去合作中心」连点只跳一次;complete 或 onShow 后可再跳;
// 非 dm 标签/非 go-coop 时不跳也不上锁

const ROOT = path.resolve(__dirname, '../..')

function load(file) {
  let definition
  const navs = []
  const base = { navigateTo: (o) => navs.push(o), switchTab: (o) => navs.push(o), getStorageSync: () => '', getSystemInfoSync: () => ({ statusBarHeight: 20, windowWidth: 375 }) }
  const wx = new Proxy(base, { get(t, k) { return k in t ? t[k] : () => {} } })
  const app = new Proxy({ globalData: {}, sendRequest() { return { abort() {} } }, canUseCommunity: () => true }, {
    get(t, k) { return k in t ? t[k] : () => '' },
  })
  global.getApp = () => app
  global.wx = wx
  const dir = path.dirname(path.join(ROOT, file))
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, file), 'utf8'), {
    getApp: () => app,
    getCurrentPages: () => [{}, {}],
    Page(page) { definition = page },
    Component(c) { definition = Object.assign({}, c.methods, { data: c.data || {}, pageShow: c.pageLifetimes.show }) },
    Behavior: (o) => o,
    require(request) { return require(path.resolve(dir, request)) },
    Set, Map, Date, JSON, Math, Number, String, Object, Array, Promise, wx, setTimeout, clearTimeout, console,
  }, { filename: path.join(ROOT, file) })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data || {})),
    setData(patch) { Object.assign(this.data, patch) },
  })
  return { page, navs, app }
}

const CASES = [
  {
    name: '收件箱空态「去发现找人」', file: 'subpackageB/pages/im/list/index.js',
    url: '/pages/talent/list/index',
    discover: true,
    prep(page) { page.data.activeTab = 'in' },
    tap(page) { page.onEmptyCta() },
  },
  {
    name: '客户页空态「去合作中心」', file: 'pages/merchant/customer/index.js',
    url: '/pages/merchant/coop-center/index',
    prep(page) { page.data.emptyAction = 'go-coop'; page.load = () => {} },
    tap(page) { page.onEmptyAction() },
  },
]

for (const c of CASES) {
  const mount = () => { const r = load(c.file); c.prep(r.page); return r }

  test(`BL-247:${c.name} complete 未回调时连点两次只跳 1 次,complete 后可再跳`, () => {
    const { page, navs, app } = mount()
    c.tap(page)
    c.tap(page)
    assert.equal(navs.length, 1)
    assert.equal(navs[0].url, c.url)
    if (c.discover) assert.equal(app.globalData.discoverFeedTab, true, '发现页凭一次性标记切到帖文 tab')
    navs[0].complete()
    c.tap(page)
    assert.equal(navs.length, 2)
  })

  test(`BL-247:${c.name} onShow 兜底清锁后可再跳`, () => {
    const { page, navs } = mount()
    c.tap(page)
    try { page.onShow() } catch (_) { /* 兜底清锁写在 onShow 首行,后续刷新与本卡无关 */ }
    c.tap(page)
    assert.equal(navs.length, 2)
  })
}

test('BL-247:发件箱点空态按钮不跳也不上锁', () => {
  const { page, navs } = load('subpackageB/pages/im/list/index.js')
  page.data.activeTab = 'out'
  page.onEmptyCta()
  assert.equal(navs.length, 0)
  assert.ok(!page._navigating)
})

test('BL-247:客户页非 go-coop 空态按钮走重载/清筛选,不跳也不上锁', () => {
  const { page, navs } = load('pages/merchant/customer/index.js')
  let loads = 0
  page.load = () => { loads += 1 }
  page.data.emptyAction = ''
  page.onEmptyAction()
  page.data.emptyAction = 'clear-advanced'
  page.onEmptyAction()
  assert.equal(navs.length, 0)
  assert.equal(loads, 2)
  assert.ok(!page._navigating)
})
