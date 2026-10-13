const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const legacySource = fs.readFileSync(path.join(uiRoot, 'src/views/business/registrationmerchant/index.vue'), 'utf8')
const dispatchSource = fs.readFileSync(path.join(uiRoot, 'src/views/business/registrationmerchant/dispatch.vue'), 'utf8')
const apiSource = fs.readFileSync(path.join(uiRoot, 'src/api/business/registrationmerchant.js'), 'utf8')

function loadDispatch(dependencies) {
  const script = dispatchSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ dispatchTree, selectDispatch, rejectDispatch \} from ['"]@\/api\/business\/registrationmerchant['"]$/m,
      'const { dispatchTree, selectDispatch, rejectDispatch } = dependencies')
    .replace(/^import (Cy\w+) from ['"].+['"]$/gm, 'const $1 = {}')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies)
}

function createVm(component) {
  const vm = Object.assign(component.data(), {
    $route: { query: {} },
    $modal: {
      confirm: () => Promise.resolve(),
      msgError: () => {},
      msgSuccess: () => {}
    }
  })
  for (const name of [
    'getTree', 'handleQuery', 'resetQuery', 'clearFilter',
    'selectRegistration', 'openReject', 'confirmReject', 'runAction',
    'releaseAction', 'isActioning', 'isPending'
  ]) {
    vm[name] = component.methods[name].bind(vm)
  }
  return vm
}

test('旧商家报名入口只承载调配页，不再暴露通用编辑、分润或删除写入口', () => {
  assert.match(legacySource, /<registration-merchant-dispatch\s*\/?\s*>/)
  assert.match(legacySource, /RegistrationMerchantDispatch/)
  assert.doesNotMatch(legacySource, /listRegistrationmerchant|listRegistrationmerchantJoin|getRegistrationmerchant/)
  assert.doesNotMatch(legacySource, /updateRegistrationmerchant|updateRegistrationmerchantSharingRates|delRegistrationmerchant/)
  assert.doesNotMatch(legacySource, /sharingRate|分润比例设置|<el-dialog/)
  assert.doesNotMatch(legacySource, /\u0000/)
  assert.doesNotMatch(apiSource, /listRegistrationmerchant|listRegistrationmerchantJoin|getRegistrationmerchant/)
  assert.doesNotMatch(apiSource, /addRegistrationmerchant|updateRegistrationmerchant|delRegistrationmerchant|sharingRates/)
})

test('调配页以主题名称为常用筛选，以主题编号为精确定位，并显式提供加载失败和空态', () => {
  assert.match(dispatchSource, /CyPageHeader/)
  assert.match(dispatchSource, /CyFilterBar/)
  assert.match(dispatchSource, /CyEmptyState/)
  assert.match(dispatchSource, /CyResourceState/)
  assert.match(dispatchSource, /主题名称/)
  assert.match(dispatchSource, /主题编号/)
  assert.match(dispatchSource, /queryParams\.topicId\s*\|\|\s*undefined/)
  assert.doesNotMatch(dispatchSource, /scope\.row\.email|maskEmail/)
})

test('驳回必须填写并展示原因，且只将原因交给调配状态机', async () => {
  const rejected = []
  const component = loadDispatch({
    dispatchTree: () => Promise.resolve({ data: [] }),
    selectDispatch: () => Promise.resolve(),
    rejectDispatch: (id, reason) => { rejected.push({ id, reason }); return Promise.resolve({ msg: '已驳回报名' }) }
  })
  const vm = createVm(component)
  vm.getTree = () => Promise.resolve()

  assert.match(dispatchSource, /CyConfirmAction/)
  assert.match(dispatchSource, /scope\.row\.reason/)
  assert.match(dispatchSource, /@confirm="confirmReject"/)
  assert.match(dispatchSource, /v-hasPermi="\['business:registrationmerchant:edit'\]"/)

  vm.openReject({ id: 10, auditStatus: 0 })
  await vm.confirmReject('地点容量已满')

  assert.deepEqual(rejected, [{ id: 10, reason: '地点容量已满' }])
})

test('调配只读取当前主题候选，较早回包不得覆盖最新主题', async () => {
  const pending = []
  const component = loadDispatch({
    dispatchTree: topicId => new Promise(resolve => pending.push({ topicId, resolve })),
    selectDispatch: () => Promise.resolve(),
    rejectDispatch: () => Promise.resolve()
  })
  const vm = createVm(component)
  vm.queryParams.topicId = 21

  const first = vm.getTree()
  vm.queryParams.topicId = 22
  const second = vm.getTree()
  assert.deepEqual(pending.map(request => request.topicId), [21, 22])

  pending[1].resolve({ data: [{ topicId: 22, topicName: '新主题', registrations: [] }] })
  await second
  pending[0].resolve({ data: [{ topicId: 21, topicName: '旧主题', registrations: [] }] })
  await first

  assert.deepEqual(vm.tree, [{ topicId: 22, topicName: '新主题', registrations: [] }])
  assert.equal(vm.loading, false)
  assert.equal(vm.loadError, false)
})

test('选定只调用调配状态机，不调用通用报名编辑或分润写接口', async () => {
  const selected = []
  const successes = []
  const component = loadDispatch({
    dispatchTree: () => Promise.resolve({ data: [] }),
    selectDispatch: id => { selected.push(id); return Promise.resolve({ msg: '已选定中标商家' }) },
    rejectDispatch: () => Promise.resolve()
  })
  const vm = createVm(component)
  vm.$modal.msgSuccess = message => successes.push(message)
  let refreshes = 0
  vm.getTree = () => { refreshes += 1; return Promise.resolve() }

  await vm.selectRegistration({ id: 9 })

  assert.deepEqual(selected, [9])
  assert.deepEqual(successes, ['已选定中标商家'])
  assert.equal(refreshes, 1)
})

test('同一时刻只允许一条调配命令，慢回包不能提前解锁其他报名', async () => {
  const selected = []
  const rejected = []
  let resolveSelection
  const component = loadDispatch({
    dispatchTree: () => Promise.resolve({ data: [] }),
    selectDispatch: id => {
      selected.push(id)
      return new Promise(resolve => { resolveSelection = () => resolve({ msg: '已选定中标商家' }) })
    },
    rejectDispatch: id => { rejected.push(id); return Promise.resolve({ msg: '已驳回报名' }) }
  })
  const vm = createVm(component)
  vm.getTree = () => Promise.resolve()

  const selecting = vm.selectRegistration({ id: 9, auditStatus: 0 })
  await Promise.resolve()
  assert.equal(vm.actioning, true)

  vm.openReject({ id: 10, auditStatus: 0 })
  await vm.confirmReject('重复报名')
  assert.deepEqual(rejected, [])

  resolveSelection()
  await selecting
  assert.deepEqual(selected, [9])
  assert.equal(vm.actioning, false)
})

test('调配已成功但候选刷新失败时，错误态不得否定已完成的状态变更', async () => {
  const errors = []
  const component = loadDispatch({
    dispatchTree: () => Promise.reject(new Error('network')),
    selectDispatch: () => Promise.resolve({ msg: '已选定中标商家' }),
    rejectDispatch: () => Promise.resolve()
  })
  const vm = createVm(component)
  vm.$modal.msgError = message => errors.push(message)

  await vm.selectRegistration({ id: 9, auditStatus: 0 })

  assert.equal(vm.loadError, true)
  assert.equal(vm.loadErrorAfterAction, true)
  assert.deepEqual(errors, [])
  assert.match(dispatchSource, /loadErrorAfterAction \? '报名状态已更新/)
})
