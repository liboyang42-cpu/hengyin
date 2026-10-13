const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { compile } = require('vue-template-compiler')

const uiRoot = path.resolve(__dirname, '../..')
const ticketSource = fs.readFileSync(path.join(uiRoot, 'src/views/business/ticket/index.vue'), 'utf8')
const topicDetailSource = fs.readFileSync(path.join(uiRoot, 'src/views/business/topic/detail.vue'), 'utf8')
const activityDetailSource = fs.readFileSync(path.join(uiRoot, 'src/views/business/activity/detail.vue'), 'utf8')

function findNodes(node, tag) {
  if (!node || typeof node !== 'object') return []
  const matches = node.tag === tag ? [node] : []
  for (const child of node.children || []) matches.push(...findNodes(child, tag))
  return matches
}

function loadTicketLocator(listTicket) {
  const script = ticketSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listTicket \} from '@\/api\/business\/ticket'$/m, 'const { listTicket } = dependencies')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {}')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({ listTicket })
}

function createVm(component) {
  const vm = Object.assign(component.data(), {
    $route: { query: {} },
    $router: { push: () => {} },
    $store: { getters: { permissions: ['business:topic:query', 'business:activity:query'] } },
    $modal: { msgWarning: () => {} }
  })
  for (const name of ['hydrateFromRoute', 'getList', 'handleQuery', 'resetQuery', 'clearFilter', 'hasKnownOwner', 'canOpenOwner', 'ownerTypeLabel', 'ownerEntityType', 'ownerLabel', 'openOwner', 'handlePagination']) {
    vm[name] = component.methods[name].bind(vm)
  }
  return vm
}

function loadActivityDetail(getActivity, addTicket, updateTicket) {
  const script = activityDetailSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ getActivity \} from "@\/api\/business\/activity"$/m, 'const { getActivity } = dependencies')
    .replace(/^import \{ addTicket, updateTicket \} from "@\/api\/business\/ticket"$/m, 'const { addTicket, updateTicket } = dependencies')
    .replace(/^import \{ addTopicNode, updateTopicNode, delTopicNode \} from "@\/api\/business\/topicnode"$/m,
      'const { addTopicNode, updateTopicNode, delTopicNode } = dependencies')
    .replace(/^import \{ getTopicFunnel \} from "@\/api\/business\/topicFunnel"$/m,
      'const { getTopicFunnel } = dependencies')
    .replace(/^import \{ parseTime \} from "@\/utils\/ruoyi"$/m, 'const parseTime = () => null')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({ getActivity, addTicket, updateTicket })
}

function loadTopicDetail(getTopic, addTicket, updateTicket) {
  const script = topicDetailSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{[\s\S]*?\} from "@\/api\/business\/topic"$/m,
      'const { getTopic, getTopicChapter, addTopicChapter, updateTopicChapter, delTopicChapter, configureChapterSupplyTerms, getTopicLeadFulfillment } = dependencies')
    .replace(/^import \{ addTicket, updateTicket \} from "@\/api\/business\/ticket"$/m,
      'const { addTicket, updateTicket } = dependencies')
    .replace(/^import \{ addTopicNode, updateTopicNode, delTopicNode \} from "@\/api\/business\/topicnode"$/m,
      'const { addTopicNode, updateTopicNode, delTopicNode } = dependencies')
    .replace(/^import \{ getTopicFunnel \} from "@\/api\/business\/topicFunnel"$/m,
      'const { getTopicFunnel } = dependencies')
    .replace(/^import \{ parseTime \} from "@\/utils\/ruoyi"$/m, 'const parseTime = () => null')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({
    getTopic,
    configureChapterSupplyTerms: () => Promise.resolve(),
    getTopicLeadFulfillment: () => Promise.resolve({ code: 200, data: [] }),
    addTicket,
    updateTicket
  })
}

function createActivityVm(component) {
  const vm = Object.assign(component.data(), {
    activityDetail: { id: 23, memberId: 77 },
    ticketList: [{ id: 9, name: '夜游票', price: 88, totalInventory: 12, startTime: '2026-07-22 10:00:00', endTime: '2026-07-23 10:00:00' }],
    canAddTicket: true,
    canEditTicket: true,
    $refs: { ticketForm: { validate: callback => callback(true), clearValidate: () => {} } },
    $modal: { msgSuccess: () => {}, msgError: () => {} },
    $nextTick: callback => callback(),
    getDetail: () => Promise.resolve()
  })
  vm.findTicketOnCurrentActivity = component.methods.findTicketOnCurrentActivity.bind(vm)
  vm.submitTicketForm = component.methods.submitTicketForm.bind(vm)
  vm.openAddTicket = component.methods.openAddTicket.bind(vm)
  vm.openEditTicket = component.methods.openEditTicket.bind(vm)
  return vm
}

function createTopicVm(component) {
  const vm = Object.assign(component.data(), {
    topicDetail: { id: 17, memberId: 77 },
    ticketList: [{ id: 9, remainingInventory: 3, totalInventory: 12 }],
    $refs: { ticketForm: { validate: callback => callback(true), clearValidate: () => {} } },
    $modal: { msgSuccess: () => {}, msgError: () => {} },
    $nextTick: callback => callback(),
    getDetail: () => Promise.resolve()
  })
  vm.submitTicketForm = component.methods.submitTicketForm.bind(vm)
  return vm
}

test('顶级 ticket 页是只读定位器，不再保留票务写入口或导出入口', () => {
  assert.match(ticketSource, /import \{ listTicket \} from '@\/api\/business\/ticket'/)
  assert.doesNotMatch(ticketSource, /getTicket|addTicket|updateTicket|delTicket|handleAdd|handleUpdate|handleDelete|submitForm|handleExport|business:ticket:(add|edit|remove|export)/)
  assert.match(ticketSource, /票务已归属主题或活动详情/)
  assert.match(ticketSource, /openOwner\(row\)/)
})

test('定位器的筛选项必须处于 el-form 上下文，避免 Element UI 首次渲染崩溃', () => {
  const template = ticketSource.match(/<template>([\s\S]*?)<\/template>/)[1]
  const form = findNodes(compile(template).ast, 'el-form')[0]

  assert.ok(form, 'CyFilterBar 中的 el-form-item 必须由 el-form 包裹')
  const filterBar = findNodes(form, 'cy-filter-bar')[0]
  assert.ok(filterBar, '筛选表单必须承载 CyFilterBar')
  assert.ok(findNodes(filterBar, 'el-form-item').length > 0,
    '筛选项必须是该 el-form 内 CyFilterBar 的后代')
})

test('定位器按 ownerType 和 ownerId 跳回主题或活动详情', () => {
  const pushes = []
  const warnings = []
  const component = loadTicketLocator(() => Promise.resolve({ rows: [], total: 0 }))
  const vm = createVm(component)
  vm.$router.push = target => pushes.push(target)
  vm.$modal.msgWarning = message => warnings.push(message)

  vm.openOwner({ ownerType: 1, ownerId: 17 })
  vm.openOwner({ ownerType: 2, ownerId: 23 })
  vm.openOwner({ ownerType: 9, ownerId: 31 })

  assert.deepEqual(pushes, [
    { name: 'topicDetail', query: { id: 17 } },
    { name: 'activityDetail', query: { id: 23 } }
  ])
  assert.deepEqual(warnings, ['该票缺少可定位的主题或活动归属'])
})

test('定位器不为缺少所属详情读取权限的角色暴露死链接', () => {
  const pushes = []
  const warnings = []
  const component = loadTicketLocator(() => Promise.resolve({ rows: [], total: 0 }))
  const vm = createVm(component)
  vm.$store.getters.permissions = ['business:topic:query']
  vm.$router.push = target => pushes.push(target)
  vm.$modal.msgWarning = message => warnings.push(message)

  assert.equal(vm.canOpenOwner({ ownerType: 1, ownerId: 17 }), true)
  assert.equal(vm.canOpenOwner({ ownerType: 2, ownerId: 23 }), false)
  vm.openOwner({ ownerType: 2, ownerId: 23 })

  assert.deepEqual(pushes, [])
  assert.deepEqual(warnings, ['没有查看活动详情的权限'])
})

test('定位器只发送读取查询，路由筛选和旧回包不会污染最新结果', async () => {
  const pending = []
  const component = loadTicketLocator(query => new Promise(resolve => pending.push({ query, resolve })))
  const vm = createVm(component)

  vm.hydrateFromRoute({ ownerType: '1', ownerId: '17', name: '夜游' })
  const first = vm.getList()
  vm.hydrateFromRoute({ ownerType: '2', ownerId: '23', name: '市集' })
  const second = vm.getList()

  assert.deepEqual(pending[0].query, { pageNum: 1, pageSize: 10, ownerType: 1, ownerId: '17', name: '夜游' })
  assert.deepEqual(pending[1].query, { pageNum: 1, pageSize: 10, ownerType: 2, ownerId: '23', name: '市集' })
  pending[1].resolve({ rows: [{ id: 2, ownerId: 23 }], total: 1 })
  await second
  pending[0].resolve({ rows: [{ id: 1, ownerId: 17 }], total: 1 })
  await first

  assert.deepEqual(vm.ticketList, [{ id: 2, ownerId: 23 }])
  assert.equal(vm.total, 1)
})

test('活动详情承接活动票新增和编辑，且不会暴露物理删除；主题详情也移除失效删除动作', () => {
  assert.match(activityDetailSource, /import \{ addTicket, updateTicket \} from "@\/api\/business\/ticket"/)
  assert.match(activityDetailSource, /ownerType:\s*2/)
  assert.match(activityDetailSource, /ownerId:\s*activityId/)
  assert.match(activityDetailSource, /findTicketOnCurrentActivity/)
  assert.match(activityDetailSource, /business:ticket:add/)
  assert.match(activityDetailSource, /business:ticket:edit/)
  assert.doesNotMatch(activityDetailSource, /delTicket|handleDeleteTicket|business:ticket:remove/)
  assert.doesNotMatch(topicDetailSource, /delTicket|handleDeleteTicket|business:ticket:remove/)
  assert.match(topicDetailSource, /business:ticket:add/)
  assert.match(topicDetailSource, /business:ticket:edit/)
})

test('活动详情票务按钮声明 RuoYi v-hasPermi 指令，键沿用既有票务动作权限', () => {
  assert.match(activityDetailSource,
    /<el-button[^>]*v-hasPermi="\['business:ticket:add'\]"[^>]*>\s*添加票务<\/el-button>/)
  assert.match(activityDetailSource,
    /<el-button[^>]*v-hasPermi="\['business:ticket:edit'\]"[^>]*>\s*编辑<\/el-button>/)
})

test('票务写入口同时要求票务动作权限和父主题或活动编辑权限', () => {
  const activity = loadActivityDetail(() => Promise.resolve({ data: {} }), () => Promise.resolve(), () => Promise.resolve())
  const topic = loadTopicDetail(() => Promise.resolve({ data: {} }), () => Promise.resolve(), () => Promise.resolve())
  const vm = permissions => ({ $store: { getters: { permissions } } })

  assert.equal(activity.computed.canAddTicket.call(vm(['business:ticket:add'])), false)
  assert.equal(activity.computed.canAddTicket.call(vm(['business:activity:edit'])), false)
  assert.equal(activity.computed.canAddTicket.call(vm(['business:ticket:add', 'business:activity:edit'])), true)
  assert.equal(activity.computed.canEditTicket.call(vm(['business:ticket:edit', 'business:activity:edit'])), true)
  assert.equal(topic.computed.canAddTicket.call(vm(['business:ticket:add', 'business:topic:edit'])), true)
  assert.equal(topic.computed.canEditTicket.call(vm(['business:ticket:edit'])), false)
  assert.equal(topic.computed.canEditTicket.call(vm(['*:*:*'])), true)
})

test('活动票新增固定写入当前活动归属，编辑绑定当前活动且不携带库存计数器', async () => {
  const requests = []
  const component = loadActivityDetail(
    () => Promise.resolve({ data: {} }),
    payload => { requests.push({ type: 'add', payload }); return Promise.resolve() },
    payload => { requests.push({ type: 'edit', payload }); return Promise.resolve() }
  )
  const vm = createActivityVm(component)

  vm.ticketForm = {
    id: null,
    name: '夜游票',
    price: 88,
    totalInventory: 12,
    startTime: '2026-07-22 10:00:00',
    endTime: '2026-07-23 10:00:00'
  }
  vm.submitTicketForm()
  await Promise.resolve()

  assert.deepEqual(requests[0], {
    type: 'add',
    payload: {
      id: null,
      ownerType: 2,
      ownerId: 23,
      name: '夜游票',
      price: 88,
      totalInventory: 12,
      startTime: '2026-07-22 10:00:00',
      endTime: '2026-07-23 10:00:00',
      memberId: 77,
      remainingInventory: 12
    }
  })

  vm.ticketSaving = false
  vm.ticketForm = Object.assign({}, vm.ticketForm, { id: 9, totalInventory: 20 })
  vm.submitTicketForm()
  await Promise.resolve()

  assert.deepEqual(requests[1], {
    type: 'edit',
    payload: {
      id: 9,
      ownerType: 2,
      ownerId: 23,
      name: '夜游票',
      price: 88,
      totalInventory: 20,
      startTime: '2026-07-22 10:00:00',
      endTime: '2026-07-23 10:00:00'
    }
  })
})

test('活动票编辑拒绝表单里不属于当前活动的 ticket id，也不在缺权限时发写请求', async () => {
  const requests = []
  const errors = []
  const component = loadActivityDetail(
    () => Promise.resolve({ data: {} }),
    payload => { requests.push({ type: 'add', payload }); return Promise.resolve() },
    payload => { requests.push({ type: 'edit', payload }); return Promise.resolve() }
  )
  const vm = createActivityVm(component)
  vm.$modal.msgError = message => errors.push(message)
  vm.ticketForm = {
    id: 99,
    name: '夜游票',
    price: 88,
    totalInventory: 12,
    startTime: '2026-07-22 10:00:00',
    endTime: '2026-07-23 10:00:00'
  }

  vm.submitTicketForm()
  await Promise.resolve()

  assert.equal(requests.length, 0)
  assert.deepEqual(errors, ['只能编辑当前活动下的票务'])

  errors.length = 0
  vm.canEditTicket = false
  vm.ticketForm.id = 9
  vm.submitTicketForm()
  await Promise.resolve()

  assert.equal(requests.length, 0)
  assert.deepEqual(errors, ['没有编辑该活动票务的权限'])

  errors.length = 0
  vm.canAddTicket = false
  vm.ticketForm.id = null
  vm.submitTicketForm()
  await Promise.resolve()

  assert.equal(requests.length, 0)
  assert.deepEqual(errors, ['没有添加该活动票务的权限'])
})

test('活动票拒绝结束早于开始的时间窗，不发写请求', () => {
  let requests = 0
  const component = loadActivityDetail(
    () => Promise.resolve({ data: {} }),
    () => { requests += 1; return Promise.resolve() },
    () => { requests += 1; return Promise.resolve() }
  )
  const vm = createActivityVm(component)
  const errors = []
  vm.$modal.msgError = message => errors.push(message)
  vm.ticketForm = {
    id: null,
    name: '倒置时间票',
    price: 88,
    totalInventory: 12,
    startTime: '2026-07-23 10:00:00',
    endTime: '2026-07-22 10:00:00'
  }

  vm.submitTicketForm()

  assert.equal(requests, 0)
  assert.deepEqual(errors, ['结束时间必须晚于开始时间'])
})

test('主题票编辑必须声明 ownerType/ownerId 归属，不重置真实剩余库存，并在保存后回读详情', async () => {
  const requests = []
  const component = loadTopicDetail(
    () => Promise.resolve({ data: {} }),
    payload => { requests.push({ type: 'add', payload }); return Promise.resolve({ code: 200 }) },
    payload => { requests.push({ type: 'edit', payload }); return Promise.resolve({ code: 200 }) }
  )
  const vm = createTopicVm(component)
  let detailReads = 0
  vm.getDetail = () => { detailReads += 1; return Promise.resolve() }
  vm.editingTicketIndex = 0
  vm.ticketForm = {
    id: 9,
    ownerId: 17,
    ownerType: 1,
    memberId: 77,
    name: '主题票',
    price: 88,
    totalInventory: 20,
    remainingInventory: 3,
    startTime: '2026-07-22 10:00:00',
    endTime: '2026-07-23 10:00:00'
  }

  vm.submitTicketForm()
  await Promise.resolve()
  await Promise.resolve()

  assert.deepEqual(requests, [{
    type: 'edit',
    payload: {
      id: 9,
      ownerType: 1,
      ownerId: 17,
      name: '主题票',
      price: 88,
      totalInventory: 20,
      startTime: '2026-07-22 10:00:00',
      endTime: '2026-07-23 10:00:00'
    }
  }])
  assert.equal(vm.ticketForm.remainingInventory, 3)
  assert.equal(detailReads, 1)
})
