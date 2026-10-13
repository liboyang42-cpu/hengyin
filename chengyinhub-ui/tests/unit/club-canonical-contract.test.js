const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function loadClub() {
  const source = read('src/views/business/club/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s*\{[\s\S]*?\}\s*from\s*'@\/api\/business\/club'\s*;?\s*$/m, 'const { auditClub, delClub, getClub, getClubFulfillment, getClubGovernance, getClubWorkspace, leaderAudit, listClub, removeClubMember, submitClubPostForReview, updateClub, updateClubMemberRole } = dependencies;')
    .replace(/^import\s*\{ listTopic \}\s*from\s*'@\/api\/business\/topic'\s*;?\s*$/m, 'const { listTopic } = dependencies;')
    .replace(/^import\s*\{ checkPermi \}\s*from\s*'@\/utils\/permission'\s*;?\s*$/m, 'const { checkPermi } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({
    auditClub: () => Promise.resolve({}),
    delClub: () => Promise.resolve({}),
    getClub: () => Promise.resolve({ data: {} }),
    getClubFulfillment: () => Promise.resolve({ data: {} }),
    getClubGovernance: () => Promise.resolve({ data: {} }),
    getClubWorkspace: () => Promise.resolve({ data: {} }),
    leaderAudit: () => Promise.resolve({}),
    listClub: () => Promise.resolve({ rows: [], total: 0 }),
    listTopic: () => Promise.resolve({ rows: [], total: 0 }),
    removeClubMember: () => Promise.resolve({}),
    submitClubPostForReview: () => Promise.resolve({ data: {} }),
    updateClub: () => Promise.resolve({}),
    updateClubMemberRole: () => Promise.resolve({}),
    checkPermi: () => false
  })
}

test('俱乐部以原列表权限注册唯一的 club/profile 入口', () => {
  const router = read('src/router/index.js')

  assert.match(router, /path:\s*['"]\/club\/profile['"][\s\S]*?permissions:\s*\[['"]business:club:list['"]\][\s\S]*?name:\s*['"]clubProfile['"]/)
  assert.match(router, /component:\s*\(\)\s*=>\s*import\(['"]@\/views\/business\/club\/index['"]\)/)
})

test('俱乐部旧入口重定向到规范路径', () => {
  const router = read('src/router/index.js')

  assert.match(router, /path:\s*['"]\/business\/club['"][\s\S]*?permissions:\s*\[['"]business:club:list['"]\][\s\S]*?name:\s*['"]clubProfile['"]/)
  assert.ok(router.indexOf("path: '/club/profile'") < router.indexOf("path: '/business/club'"),
    'canonical route 应定义在 redirect route 之前')
})

test('规范入口调用 ensureCanonicalRoute 并在 created 阶段拉列表', () => {
  const component = loadClub()
  const source = read('src/views/business/club/index.vue')

  assert.match(source, /this\.ensureCanonicalRoute\(\)/)
  assert.match(source, /'\$route'\(\)\s*\{\s*this\.ensureCanonicalRoute\(\)/)
  assert.match(source, /created\(\)\s*\{\s*this\.ensureCanonicalRoute\(\)/)

  const vm = Object.assign(component.data(), {
    $route: { path: '/club/profile', query: {} },
    $router: { replace: () => assert.fail('规范入口不应重定向') }
  })
  let listCalls = 0
  vm.getList = () => { listCalls += 1 }

  component.methods.ensureCanonicalRoute.call(vm)
  assert.equal(listCalls, 1)
})

test('旧俱乐部入口只转发不读数据', () => {
  const component = loadClub()
  const legacyTargets = []
  let legacyReads = 0
  const legacyVm = Object.assign(component.data(), {
    $route: { path: '/business/club', query: {} },
    $router: { replace: target => legacyTargets.push(target) },
    getList: () => { legacyReads += 1 }
  })

  component.methods.ensureCanonicalRoute.call(legacyVm)

  assert.deepEqual(legacyTargets, [{ name: 'clubProfile', query: {} }])
  assert.equal(legacyReads, 0)
})
