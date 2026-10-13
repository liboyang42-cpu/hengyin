const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function loadAiLog() {
  const source = read('src/views/business/ailog/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listAiLog, getAiLogSnapshot \} from "@\/api\/business\/ailog"$/m, 'const { listAiLog, getAiLogSnapshot } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({
    listAiLog: () => Promise.resolve({ rows: [], total: 0 }),
    getAiLogSnapshot: () => Promise.resolve({ data: null })
  })
}

test('AI 调用审计的规范入口是受原列表权限保护的 risk/ai-log', () => {
  const router = read('src/router/index.js')

  assert.match(router, /path:\s*['"]\/risk\/ai-log['"][\s\S]*?permissions:\s*\[['"]business:ailog:list['"]\][\s\S]*?name:\s*['"]riskAiLog['"]/);
  assert.match(router, /component:\s*\(\)\s*=>\s*import\(['"]@\/views\/business\/ailog\/index['"]\)/)
})

test('旧 AI 审计入口仅兼容转发到规范路由，不在旧地址读取列表', () => {
  const component = loadAiLog()
  const legacyTargets = []
  let legacyReads = 0
  const legacyVm = Object.assign(component.data(), {
    $route: { path: '/business/ailog', query: { traceId: 'trace-1', pageNum: '3' } },
    $router: { replace: target => legacyTargets.push(target) },
    getList: () => { legacyReads += 1 }
  })

  component.methods.ensureCanonicalRoute.call(legacyVm)

  assert.deepEqual(legacyTargets, [{
    name: 'riskAiLog',
    query: { traceId: 'trace-1', pageNum: '3' }
  }])
  assert.equal(legacyReads, 0)
})

test('规范 AI 审计入口保留现有只读工作台读取，不新增日志写操作', () => {
  const component = loadAiLog()
  let reads = 0
  const vm = Object.assign(component.data(), {
    $route: { path: '/risk/ai-log', query: {} },
    $router: { replace: () => assert.fail('规范入口不应重定向') },
    getList: () => { reads += 1 }
  })

  component.methods.ensureCanonicalRoute.call(vm)

  assert.equal(reads, 1)
  const source = read('src/views/business/ailog/index.vue')
  assert.match(source, /created\(\)\s*\{\s*this\.ensureCanonicalRoute\(\)/)
  assert.match(source, /'\$route'\(\)\s*\{\s*this\.ensureCanonicalRoute\(\)/)
  assert.doesNotMatch(source, /\b(addAiLog|updateAiLog|delAiLog)\b/)
})
