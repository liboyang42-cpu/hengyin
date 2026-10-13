const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const VueRouter = require('vue-router')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function loadClubRun() {
  const source = read('src/views/business/clubrun/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ runList, runManifest \} from "@\/api\/business\/clubRun";$/m, 'const { runList, runManifest } = dependencies;')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({
    runList: () => Promise.resolve({ data: [] }),
    runManifest: () => Promise.resolve({ data: {} })
  })
}

test('带队场次以原列表权限注册唯一的 club/lead-run 入口', () => {
  const router = read('src/router/index.js')

  assert.match(router, /path:\s*['"]\/club\/lead-run['"][\s\S]*?permissions:\s*\[['"]business:clubRun:list['"]\][\s\S]*?name:\s*['"]clubLeadRun['"]/)
  assert.match(router, /component:\s*\(\)\s*=>\s*import\(['"]@\/views\/business\/clubrun\/index['"]\)/)
  assert.match(router, /path:\s*['"]\/business\/clubrun['"][\s\S]*?redirect:\s*to\s*=>\s*\(\{\s*name:\s*['"]clubLeadRun['"]/)
  assert.ok(router.indexOf("path: '/club/lead-run'") < router.indexOf("path: '/business/clubrun'"))
})

test('旧带队场次地址在后台同 path 菜单注册后仍路由级跳到规范入口', () => {
  const router = new VueRouter({ mode: 'abstract', routes: [] })
  router.addRoutes([
    { path: '/club/lead-run', name: 'clubLeadRun' },
    { path: '/business/clubrun', redirect: to => ({ name: 'clubLeadRun', query: Object.assign({}, to.query) }) }
  ])
  router.addRoutes([{ path: '/business/clubrun', name: 'serverLegacyMenu' }])

  const resolved = router.match('/business/clubrun?topicId=7&clubId=3')
  assert.equal(resolved.name, 'clubLeadRun')
  assert.equal(resolved.path, '/club/lead-run')
  assert.deepEqual(resolved.query, { topicId: '7', clubId: '3' })
})

test('旧带队场次入口只兼容转发，不在旧地址读取场次', () => {
  const component = loadClubRun()
  const legacyTargets = []
  let legacyReads = 0
  const legacyVm = Object.assign(component.data(), {
    $route: { path: '/business/clubrun', query: { topicId: '7', clubId: '3' } },
    $router: { replace: target => legacyTargets.push(target) },
    getList: () => { legacyReads += 1 }
  })

  component.methods.ensureCanonicalRoute.call(legacyVm)

  assert.deepEqual(legacyTargets, [{ name: 'clubLeadRun', query: { topicId: '7', clubId: '3' } }])
  assert.equal(legacyReads, 0)
})

test('规范带队场次入口保留查询参数、只读列表和空数据业务语义', () => {
  const component = loadClubRun()
  let applied = 0
  let reads = 0
  const vm = Object.assign(component.data(), {
    $route: { path: '/club/lead-run', query: { topicId: '7' } },
    $router: { replace: () => assert.fail('规范入口不应重定向') },
    applyRouteQuery: () => { applied += 1 },
    getList: () => { reads += 1 }
  })

  component.methods.ensureCanonicalRoute.call(vm)

  assert.equal(applied, 1)
  assert.equal(reads, 1)
  const queryVm = { $route: { query: {} }, query: { topicId: '7', clubId: '3', status: null } }
  component.methods.applyRouteQuery.call(queryVm)
  assert.equal(queryVm.query.topicId, '')
  assert.equal(queryVm.query.clubId, '')
  const filterTargets = []
  let filterReads = 0
  const filterVm = Object.assign(component.data(), {
    $route: { path: '/club/lead-run', query: { topicId: '7', clubId: '3' } },
    $router: { replace: target => filterTargets.push(target) },
    getList: () => { filterReads += 1 }
  })
  filterVm.query.topicId = ''
  filterVm.query.clubId = ''
  component.methods.applyFilters.call(filterVm)
  assert.deepEqual(filterTargets, [{ name: 'clubLeadRun', query: {} }])
  assert.equal(filterReads, 0)
  const source = read('src/views/business/clubrun/index.vue')
  assert.match(source, /created\(\)\s*\{\s*this\.ensureCanonicalRoute\(\)/)
  assert.match(source, /'\$route'\(\)\s*\{\s*this\.ensureCanonicalRoute\(\)/)
  assert.match(source, /@change="applyFilters"/)
  assert.match(source, /applyRouteQuery\(\)[\s\S]*?q\.topicId[\s\S]*?q\.clubId/)
  assert.match(source, /暂无带队场次/)
  assert.doesNotMatch(source, /\b(addClubRun|updateClubRun|delClubRun)\b/)
})
