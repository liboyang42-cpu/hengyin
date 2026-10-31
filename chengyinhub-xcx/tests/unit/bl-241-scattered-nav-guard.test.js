const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-241:零散单点跳转连点只跳一次;complete 回调或 onShow(组件 pageLifetimes.show)后可再跳

const ROOT = path.resolve(__dirname, '../..')

function load(file) {
  let definition
  const navs = []
  const base = { navigateTo: (o) => navs.push(o), getStorageSync: () => '', getSystemInfoSync: () => ({ statusBarHeight: 20, windowWidth: 375 }) }
  const wx = new Proxy(base, { get(t, k) { return k in t ? t[k] : () => {} } })
  const app = new Proxy({ globalData: {}, sendRequest() { return { abort() {} } } }, {
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
  return { page, navs }
}

const CASES = [
  {
    name: '邀约详情「打开项目页」', file: 'pages/coop/invite-detail/index.js', show: 'onShow',
    url: '/pages/topic/merchantinfo/merchantinfo?topicId=9&scope=MERCHANT',
    prep(page) { Object.assign(page.data, { box: 'received', invite: { inviteId: 5, status: 1, inviteType: 0, topicId: '9', legacyReadonly: false } }) },
    tap(page) { page.openAcceptedProject() },
  },
  {
    name: '我发布的活动卡片', file: 'pages/activity/official-mine/index.js', show: 'onShow',
    url: '/pages/activity/official-detail/index?id=7',
    prep(page) { page.syncViewerTheme = () => {}; page.fetch = () => {} },
    tap(page) { page.openEvent({ currentTarget: { dataset: { id: 7 } } }) },
  },
  {
    name: '客户列表行', file: 'pages/merchant/customer/index.js', show: 'onShow',
    url: '/pages/merchant/customer/detail/index?customerMemberId=41',
    tap(page) { page.openCustomer({ currentTarget: { dataset: { memberid: '41' } } }) },
  },
  {
    name: '模板详情主按钮', file: 'pages/templatedetail/templatedetail.js', show: 'onShow',
    url: '/pages/publish/temp/index?id=12',
    prep(page) { Object.assign(page.data, { id: 12, scope: 'library' }) },
    tap(page) { page.goPrimary() },
  },
  {
    name: '解散阻断项', file: 'components/cy/scene-club-edit/index.js', show: 'pageShow',
    url: '/pages/club/customers/index?clubId=7',
    prep(page) { page.data.dissolutionBlockerItems = [{ url: '/pages/club/customers/index?clubId=7' }] },
    tap(page) { page.openDissolutionBlockerItem({ currentTarget: { dataset: { index: 0 } } }) },
  },
]

for (const c of CASES) {
  const mount = () => { const r = load(c.file); if (c.prep) c.prep(r.page); return r }

  test(`BL-241:${c.name} complete 未回调时连点两次只跳 1 次`, () => {
    const { page, navs } = mount()
    c.tap(page)
    c.tap(page)
    assert.equal(navs.length, 1)
    assert.equal(navs[0].url, c.url)
  })

  test(`BL-241:${c.name} complete 回调后可再跳`, () => {
    const { page, navs } = mount()
    c.tap(page)
    navs[0].complete()
    c.tap(page)
    assert.equal(navs.length, 2)
  })

  test(`BL-241:${c.name} ${c.show} 兜底清锁后可再跳`, () => {
    const { page, navs } = mount()
    c.tap(page)
    try { page[c.show]() } catch (_) { /* 兜底清锁写在 onShow 首行,后续主题/刷新与本卡无关 */ }
    c.tap(page)
    assert.equal(navs.length, 2)
  })
}
