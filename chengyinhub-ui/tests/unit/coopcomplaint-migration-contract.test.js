const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const VueRouter = require('vue-router')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function loadCoopComplaint(overrides = {}) {
  const source = read('src/views/business/coopcomplaint/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listCoopComplaint, getCoopComplaint, advanceCoopComplaint, closeCoopComplaint \} from "@\/api\/business\/coopcomplaint"$/m, 'const { listCoopComplaint, getCoopComplaint, advanceCoopComplaint, closeCoopComplaint } = dependencies;')
    .replace(/^import \{ checkPermi \} from '@\/utils\/permission'$/m, 'const { checkPermi } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(Object.assign({
    listCoopComplaint: () => Promise.resolve({ rows: [], total: 0 }),
    getCoopComplaint: () => Promise.resolve({ data: {} }),
    advanceCoopComplaint: () => Promise.resolve({}),
    closeCoopComplaint: () => Promise.resolve({}),
    checkPermi: () => true
  }, overrides))
}

test('合作客诉以原列表权限注册唯一的 customer/case 入口', () => {
  const router = read('src/router/index.js')

  assert.match(router, /path:\s*['"]\/customer\/case['"][\s\S]*?permissions:\s*\[['"]business:coopcomplaint:list['"]\][\s\S]*?name:\s*['"]customerCase['"]/)
  assert.match(router, /component:\s*\(\)\s*=>\s*import\(['"]@\/views\/business\/coopcomplaint\/index['"]\)/)
  assert.match(router, /path:\s*['"]\/business\/coopcomplaint['"][\s\S]*?redirect:\s*to\s*=>\s*\(\{\s*name:\s*['"]customerCase['"]/)
  assert.ok(router.indexOf("path: '/customer/case'") < router.indexOf("path: '/business/coopcomplaint'"))
})

test('旧客诉地址在后台同 path 菜单注册后仍路由级跳到规范入口', () => {
  const router = new VueRouter({ mode: 'abstract', routes: [] })
  router.addRoutes([
    { path: '/customer/case', name: 'customerCase' },
    { path: '/business/coopcomplaint', redirect: to => ({ name: 'customerCase', query: Object.assign({}, to.query) }) }
  ])
  router.addRoutes([{ path: '/business/coopcomplaint', name: 'serverLegacyMenu' }])

  const resolved = router.match('/business/coopcomplaint?topicId=7&status=0&pageNum=2')
  assert.equal(resolved.name, 'customerCase')
  assert.equal(resolved.path, '/customer/case')
  assert.deepEqual(resolved.query, { topicId: '7', status: '0', pageNum: '2' })
})

test('旧客诉入口只兼容转发，不在旧地址读取案件', () => {
  const component = loadCoopComplaint()
  const targets = []
  let reads = 0
  const vm = Object.assign(component.data(), {
    $route: { path: '/business/coopcomplaint', query: { topicId: '7', status: '0' } },
    $router: { replace: target => targets.push(target) },
    getList: () => { reads += 1 }
  })

  component.methods.ensureCanonicalRoute.call(vm)

  assert.deepEqual(targets, [{ name: 'customerCase', query: { topicId: '7', status: '0' } }])
  assert.equal(reads, 0)
})

test('规范案件入口恢复并清空筛选，保持既有资金动作边界', () => {
  const component = loadCoopComplaint()
  let reads = 0
  const vm = Object.assign(component.data(), {
    $route: { path: '/customer/case', query: { topicId: '7', status: '0', pageNum: '2', pageSize: '20' } },
    $router: { replace: () => assert.fail('规范入口不应重定向') },
    applyRouteQuery() { component.methods.applyRouteQuery.call(this) },
    getList: () => { reads += 1 }
  })

  component.methods.ensureCanonicalRoute.call(vm)

  assert.deepEqual(vm.query, { topicId: '7', status: 0, pageNum: 2, pageSize: 20 })
  assert.equal(reads, 1)

  const targets = []
  const filterVm = Object.assign(component.data(), {
    $route: { path: '/customer/case', query: { topicId: '7', status: '0', pageNum: '2' } },
    $router: { replace: target => targets.push(target) },
    getList: () => assert.fail('筛选变更应先更新路由')
  })
  filterVm.query = { topicId: null, status: null, pageNum: 1, pageSize: 10 }

  component.methods.syncRoute.call(filterVm)

  assert.deepEqual(targets, [{ name: 'customerCase', query: {} }])
  const source = read('src/views/business/coopcomplaint/index.vue')
  assert.match(source, /getCoopComplaint\(row\.id\)/)
  assert.match(source, /v-if="row\.status === 0"[\s\S]*?先行垫付/)
  assert.match(source, /v-if="row\.status < 3"[\s\S]*?关闭案件/)
  assert.match(source, /v-hasPermi="\['business:coopcomplaint:query'\]"/)
  assert.match(source, /v-hasPermi="\['business:coopcomplaint:handle'\]"/)
  assert.match(source, /advanceCoopComplaint\(\{ id: this\.advanceForm\.id, amount: this\.advanceForm\.amount \}\)/)
  assert.match(source, /服务端将根据当前未结案件状态裁定是否恢复结算/)
  assert.match(source, />确认关闭案件<\/el-button>/)
  assert.doesNotMatch(source, /解除该主题的结算冻结|案件已关闭并解除结算冻结|确认关闭并解冻/)
  assert.doesNotMatch(source, /\b(addCoopComplaint|updateCoopComplaint|delCoopComplaint)\b/)
})

test('客诉列表读取失败不会伪装成空案件，且可重新读取', async () => {
  const component = loadCoopComplaint({ listCoopComplaint: () => Promise.reject(new Error('offline')) })
  const vm = Object.assign(component.data(), {
    list: [{ id: 7 }],
    total: 1,
    query: { topicId: null, status: null, pageNum: 1, pageSize: 10 }
  })

  await component.methods.getList.call(vm)

  assert.deepEqual(vm.list, [])
  assert.equal(vm.total, 0)
  assert.equal(vm.listError, true)
  assert.equal(vm.loading, false)
  const source = read('src/views/business/coopcomplaint/index.vue')
  assert.match(source, /<cy-resource-state[\s\S]*?v-if="listError"[\s\S]*?@retry="getList"/)
})
