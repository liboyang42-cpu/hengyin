/**
 * R9-20 v2 回归: 同页只换 query 时, 11 个规范入口都必须重新加载。
 *
 * 真实 vue-router 在同页只换 query 时 $route.path 不变。若 watcher 只监听
 * '$route.path', 该 watcher 不会运行 → 从旧入口携新 query / 浏览器前进后退 /
 * 同页改筛选都会继续显示上一批数据。这里用真实组件脚本 + 真实 watcher 键断言行为。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

const ROUTES = [
  ['club', '/club/profile', 'clubProfile', ['getList']],
  ['cashwithdraw', '/finance/withdraw/wechat', 'financeWechatWithdraw', ['getList']],
  ['member', '/customer/member', 'customerMember', ['getList']],
  ['coupon', '/supply/coupon', 'supplyCoupon', ['getList']],
  ['ailog', '/risk/ai-log', 'riskAiLog', ['getList']],
  ['chapteroffer', '/merchant/chapter-offer', 'merchantChapterOffer', ['getQueue']],
  ['memberwithdrawal', '/finance/withdraw/bank', 'financeBankWithdraw', ['getList']],
  ['im', '/customer/im', 'customerIm', ['loadConvs']],
  ['coopcomplaint', '/customer/case', 'customerCase', ['getList']],
  ['clubrun', '/club/lead-run', 'clubLeadRun', ['getList']],
  ['roampoi', '/club/roam-poi', 'clubRoamPoi', ['getList', 'getApplications']]
]

function load(view) {
  const source = fs.readFileSync(path.join(uiRoot, `src/views/business/${view}/index.vue`), 'utf8')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s+\{([^}]*)\}\s+from\s+['"][^'"]+['"];?\s*$/gm, (_, names) => {
      const declarations = names.split(',').map(x => x.trim()).filter(Boolean).map(x => {
        const [original, alias] = x.split(/\s+as\s+/)
        return `${alias || original}: ${alias || original}`
      })
      return `const { ${declarations.join(', ')} } = __deps`
    })
    .replace(/^import\s+(\w+)\s+from\s+['"][^'"]+['"];?\s*$/gm, 'const $1 = __deps.$1')
    .replace(/export\s+default\s*/, 'return ')
  const callable = new Proxy({}, {
    get: (_, name) => (...args) => {
      if (name === 'checkPermi' || name === 'checkRole') return true
      return Promise.resolve({ rows: [], total: 0, data: [] })
    }
  })
  return new Function('__deps', script)(callable)
}

function buildVm(component, loaders, route) {
  const calls = []
  const redirects = []
  const vm = Object.assign({}, component.data ? component.data() : {})
  for (const [name, fn] of Object.entries(component.methods || {})) vm[name] = fn.bind(vm)
  for (const name of loaders) vm[name] = () => { calls.push(name); return Promise.resolve() }
  vm.$router = { replace: target => { redirects.push(target); return Promise.resolve() } }
  vm.$route = route
  return { vm, calls, redirects }
}

function watcherOf(component) {
  const keys = Object.keys(component.watch || {})
  const key = keys.find(k => k === '$route' || k === '$route.path' || k === '$route.fullPath')
  const raw = key ? component.watch[key] : null
  return { key, run: typeof raw === 'function' ? raw : (raw && raw.handler) }
}

test('R9-20 11 页规范入口都监听完整路由(query 同页变化必须重载)', () => {
  for (const [view, canonical, , loaders] of ROUTES) {
    const component = load(view)
    const w = watcherOf(component)
    assert.ok(w.key, `${view}: 必须声明路由 watcher`)
    assert.ok(
      w.key === '$route' || w.key === '$route.fullPath',
      `${view}: 不能只监听 ${w.key}, 否则同页换 query 不重载`
    )
  }
})

test('R9-20 11 页: 尾斜杠进入不重定向并加载, query 同页变化再次加载, 旧入口只重定向', () => {
  for (const [view, canonical, expectedName, loaders] of ROUTES) {
    const component = load(view)

    const initial = buildVm(component, loaders, { path: canonical + '/', fullPath: canonical + '/', query: {} })
    component.created.call(initial.vm)
    assert.equal(initial.redirects.length, 0, `${view}: 规范入口尾斜杠不得重定向`)
    assert.deepEqual(initial.calls, loaders, `${view}: 规范入口必须调用全部加载器`)

    const w = watcherOf(component)
    const before = initial.calls.length
    initial.vm.$route = { path: canonical + '/', fullPath: canonical + '/?probe=2', query: { probe: '2' } }
    if (w.key === '$route' || w.key === '$route.fullPath') w.run.call(initial.vm, initial.vm.$route)
    assert.ok(initial.calls.length > before, `${view}: 同页 query 变化必须重新加载`)

    const legacy = buildVm(component, loaders, { path: `/business/${view}`, fullPath: `/business/${view}`, query: {} })
    component.created.call(legacy.vm)
    assert.equal(legacy.calls.length, 0, `${view}: 旧入口不得先读数据`)
    assert.equal(legacy.redirects.length, 1, `${view}: 旧入口必须重定向一次`)
    assert.equal(legacy.redirects[0].name, expectedName, `${view}: 旧入口目标为规范命名路由`)
  }
})
