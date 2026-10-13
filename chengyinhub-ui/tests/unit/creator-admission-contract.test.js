const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

const legacySource = read('src/views/business/creator/index.vue')
const admissionSource = read('src/views/business/risk/creator-admission/index.vue')
const routerSource = read('src/router/index.js')

function loadAdmission(dependencies) {
  const script = admissionSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listCreators, reviewCreator \} from '@\/api\/business\/creator'$/m, 'const { listCreators, reviewCreator } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies)
}

function createVm(component) {
  const vm = Object.assign(component.data(), {
    $modal: { confirm: () => Promise.resolve(), msgSuccess: () => {} }
  })
  for (const name of ['getList', 'handleQuery', 'handlePagination', 'handleApprove', 'openReject', 'submitReject']) {
    vm[name] = component.methods[name].bind(vm)
  }
  return vm
}

test('创作者准入使用受权限保护的规范路由，旧入口只做兼容跳转', () => {
  assert.match(routerSource, /path:\s*'\/risk\/creator-admission'/)
  assert.match(routerSource, /permissions:\s*\['business:creator:list'\]/)
  assert.match(routerSource, /name:\s*'creatorAdmission'/)
  assert.match(legacySource, /\$router\.replace\(/)
  assert.match(legacySource, /path:\s*'\/risk\/creator-admission'/)
  assert.doesNotMatch(legacySource, /listCreators|reviewCreator|<el-table\b/)
})

test('准入页分页并阻断旧列表请求覆盖新筛选结果', async () => {
  const pending = []
  const component = loadAdmission({
    listCreators: query => new Promise(resolve => pending.push({ query, resolve })),
    reviewCreator: () => Promise.resolve()
  })
  const vm = createVm(component)

  const first = vm.getList()
  vm.queryParams.applyStatus = 1
  const second = vm.getList()

  assert.deepEqual(pending[0].query, {
    pageNum: 1,
    pageSize: 10,
    memberId: null,
    creatorName: null,
    applyStatus: null
  })
  assert.equal(pending[1].query.applyStatus, 1)

  pending[0].resolve({ rows: [{ id: 1, applyStatus: 0 }], total: 1 })
  await first
  assert.deepEqual(vm.creatorList, [])
  assert.equal(vm.loading, true)

  pending[1].resolve({ rows: [{ id: 2, applyStatus: 1 }], total: 1 })
  await second
  assert.deepEqual(vm.creatorList, [{ id: 2, applyStatus: 1 }])
  assert.equal(vm.total, 1)
  assert.equal(vm.loading, false)
})

test('准入动作只处理待审申请，拒绝必须经带原因的确认组件提交', () => {
  assert.ok(admissionSource.includes("import CyConfirmAction from '@/components/CyConfirmAction'"))
  assert.ok(admissionSource.includes("import CyDataTable from '@/components/CyDataTable'"))
  assert.match(admissionSource, /:total="total"/)
  assert.match(admissionSource, /@pagination="handlePagination"/)
  assert.match(admissionSource, /canReview\(row\)\s*\{[\s\S]*?row\.applyStatus === STATUS_PENDING/)
  assert.match(admissionSource, /@confirm="submitReject"/)
  assert.match(admissionSource, /reviewReason:\s*reason/)
  assert.match(admissionSource, /\$modal\.confirm\(/)
  assert.doesNotMatch(admissionSource, /<el-table\b/)
})
