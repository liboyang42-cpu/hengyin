const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('../helpers/ui-sandbox-vm.js')

// BL-236:主题页与商家信息页的跳转入口连点只跳一次;complete 回调后可再跳;onShow 兜底清锁

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

const TOPIC = 'pages/topic/index/index.js'
const INFO = 'pages/topic/merchantinfo/merchantinfo.js'
const ds = (d) => ({ currentTarget: { dataset: d } })

const CASES = [
  [TOPIC, 'goTemplateDetail', () => ds({ id: 5 }), /^\/pages\/templatedetail\/templatedetail\?id=5&scope=my$/],
  [TOPIC, 'goPoint', () => undefined, /growthcenter/],
  [TOPIC, 'onLineupTap', () => ds({ kind: 'club', id: 9 }), /^\/pages\/club\/detail\/index\?id=9$/],
  [TOPIC, 'goMyOrders', () => undefined, /^\/subpackageMember\/order\/order$/],
  [TOPIC, 'goJoinedPlay', () => undefined, /^\/subpackageMember\/signup\/index\?stype=0$/],
  [INFO, 'goTemplateDetail', () => ds({ id: 6 }), /^\/pages\/templatedetail\/templatedetail\?id=6&scope=my$/],
]

for (const [rel, name, evt, url] of CASES) {
  test(`BL-236:${path.basename(path.dirname(rel))} ${name} 连调两次只跳 1 次,complete 后可再跳`, () => {
    const { page, navs } = loadPage(rel)
    page[name](evt())
    page[name](evt())
    assert.equal(navs.length, 1)
    assert.match(navs[0].url, url)
    navs[0].complete()
    page[name](evt())
    assert.equal(navs.length, 2)
  })
}

test('BL-236:lockedTip 定价中主理人连点只跳一次定价页,仍返回 true 拦住购票', () => {
  const { page, navs } = loadPage(TOPIC)
  page.data.id = 3
  page.data.info = { lifecycle: 2, isOwner: 1 }
  assert.equal(page.lockedTip(), true)
  assert.equal(page.lockedTip(), true)
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, '/pages/topic/pricing/index?topicId=3')
  navs[0].complete()
  page.lockedTip()
  assert.equal(navs.length, 2)
})

test('BL-236:缺参不跳也不上锁', () => {
  const { page, navs } = loadPage(TOPIC)
  page.onLineupTap(ds({ kind: 'club', id: 0 }))
  assert.equal(navs.length, 0)
  page.goMyOrders()
  assert.equal(navs.length, 1)
  const info = loadPage(INFO)
  info.page.goTemplateDetail(ds({}))
  info.page.goTemplateDetail(ds({ id: 1 }))
  assert.equal(info.navs.length, 1)
})

test('BL-236:goSettlement 跳报名页那一步有跳转中保护,两页 onShow 兜底清锁', () => {
  const info = fs.readFileSync(path.join(ROOT, INFO), 'utf8')
  assert.match(info, /if \(this\._navigating\) return; this\._navigating = true; wx\.navigateTo\(\{ url: `\/pages\/topic\/merchantapply\/index\?\$\{params\}`, complete: \(\) => \{ this\._navigating = false; \} \}\);/)
  assert.match(info, /onShow\(\) \{[\s\S]{0,800}?merchantTheme\.merchantPageShow\(\); this\._navigating = false;\s*\},/)
  const topic = fs.readFileSync(path.join(ROOT, TOPIC), 'utf8')
  assert.match(topic, /onShow\(\) \{[\s\S]{0,200}?this\._navigating = false;/)
})
