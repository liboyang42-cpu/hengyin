const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const VueRouter = require('vue-router')

const uiRoot = path.resolve(__dirname, '../..')
const detailSource = fs.readFileSync(path.join(uiRoot, 'src/views/business/topic/detail.vue'), 'utf8')
const apiSource = fs.readFileSync(path.join(uiRoot, 'src/api/business/topic.js'), 'utf8')
const routerSource = fs.readFileSync(path.join(uiRoot, 'src/router/index.js'), 'utf8')

function loadTopicDetail(dependencies) {
  const script = detailSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{[\s\S]*?\} from "@\/api\/business\/topic"$/m,
      'const { getTopic, getTopicChapter, addTopicChapter, updateTopicChapter, delTopicChapter, configureChapterSupplyTerms, getTopicLeadFulfillment, getTopicEditionLedger, markClubPayableBatchPaid } = dependencies')
    .replace(/^import \{ addTicket, updateTicket \} from "@\/api\/business\/ticket"$/m,
      'const { addTicket, updateTicket } = dependencies')
    .replace(/^import \{ addTopicNode, updateTopicNode, delTopicNode \} from "@\/api\/business\/topicnode"$/m,
      'const { addTopicNode, updateTopicNode, delTopicNode } = dependencies')
    .replace(/^import \{ getTopicFunnel \} from "@\/api\/business\/topicFunnel"$/m,
      'const { getTopicFunnel } = dependencies')
    .replace(/^import \{ parseTime \} from "@\/utils\/ruoyi"$/m, 'const parseTime = () => null')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies)
}

function flushPromises() {
  return new Promise(resolve => setImmediate(resolve))
}

test('主题详情路由保持只读可达，章节动作按现有 CRUD 权限分别显示', () => {
  assert.match(routerSource, /path:\s*'\/topic'[\s\S]*permissions:\s*\['business:topic:query'\][\s\S]*name:\s*'topicDetail'/)
  assert.match(detailSource, /business:topicchapter:add/)
  assert.match(detailSource, /business:topicchapter:edit/)
  assert.match(detailSource, /business:topicchapter:remove/)
  assert.match(detailSource, /v-if="canAddChapter"/)
  assert.match(detailSource, /v-if="canEditChapter"/)
  assert.match(detailSource, /v-if="canRemoveChapter"/)
})

test('真实 Vue Router 匹配主题详情并保留主题查询权限', () => {
  const start = routerSource.indexOf("  {\n    path: '/topic',")
  const end = routerSource.indexOf("\n  {\n    path: '/refund',", start)
  const route = new Function('Layout', `return (${routerSource.slice(start + 2, end).replace(/,$/, '')})`)({})
  const router = new VueRouter({ mode: 'abstract', routes: [route] })

  const resolved = router.match('/topic/detail?id=17')

  assert.equal(resolved.name, 'topicDetail')
  assert.equal(resolved.path, '/topic/detail')
  assert.deepEqual(resolved.query, { id: '17' })
  assert.deepEqual(route.permissions, ['business:topic:query'])
})

test('编辑章节同时需要 edit 与 query 权限，其他动作保持独立', () => {
  const component = loadTopicDetail({})
  const vm = permissions => ({ $store: { getters: { permissions } } })

  assert.equal(component.computed.canAddChapter.call(vm(['business:topicchapter:add'])), true)
  assert.equal(component.computed.canEditChapter.call(vm(['business:topicchapter:edit'])), false)
  assert.equal(component.computed.canEditChapter.call(vm([
    'business:topicchapter:edit', 'business:topicchapter:query'
  ])), true)
  assert.equal(component.computed.canRemoveChapter.call(vm(['business:topicchapter:remove'])), true)
  assert.equal(component.computed.canAddChapter.call(vm(['*:*:*'])), true)
})

test('登记付款消费双人分离状态：降级时禁用并在调用侧回显原因与部署动作', async () => {
  const paidCalls = []
  const errors = []
  const prompts = []
  const component = loadTopicDetail({
    getTopicEditionLedger: () => Promise.resolve({ data: {
      batches: [{ id: 91 }],
      separation: {
        degraded: true,
        reason: '确认与付款权限都落在同一个账号',
        deploymentAction: '创建两个非超管账号并分别授权'
      }
    } }),
    markClubPayableBatchPaid: (...args) => { paidCalls.push(args); return Promise.resolve() }
  })
  const vm = Object.assign(component.data(), {
    topicDetail: { id: 17 },
    askBatchId: () => { prompts.push('batch'); return Promise.resolve(91) },
    $prompt: () => { prompts.push('reference'); return Promise.resolve({ value: 'PAY-91' }) },
    $modal: { msgError: message => errors.push(message), msgSuccess: () => {} }
  })
  for (const name of [
    'paymentSeparation', 'paymentRegistrationDisabled',
    'paymentSeparationReason', 'paymentSeparationAction'
  ]) {
    Object.defineProperty(vm, name, { get: () => component.computed[name].call(vm) })
  }

  await component.methods.loadEditionLedger.call(vm)
  assert.equal(vm.paymentRegistrationDisabled, true)
  assert.equal(vm.paymentSeparationReason, '确认与付款权限都落在同一个账号')
  assert.equal(vm.paymentSeparationAction, '创建两个非超管账号并分别授权')

  await component.methods.markBatchPaid.call(vm)
  assert.deepEqual(prompts, [], '降级时连批次号都不得询问，按钮禁用不是唯一防线')
  assert.deepEqual(paidCalls, [], '降级时不得把登记付款请求发到后端再撞 CHECK/CAS')
  assert.deepEqual(errors, ['确认与付款权限都落在同一个账号；创建两个非超管账号并分别授权'])

  vm.editionLedger.separation = { degraded: false, reason: '', deploymentAction: '' }
  await component.methods.markBatchPaid.call(vm)
  assert.deepEqual(prompts, ['batch', 'reference'])
  assert.deepEqual(paidCalls, [[17, 91, { paymentReference: 'PAY-91' }]])

  assert.match(detailSource, /:disabled="paymentRegistrationDisabled"[^>]*>登记付款<\/el-button>/,
    '登记付款按钮必须消费行为合同验证过的 fail-closed 状态')
  assert.match(detailSource, /:title="paymentSeparationReason"/)
  assert.match(detailSource, /:description="paymentSeparationAction"/)
})

test('章节 API 复用 backend CRUD 路由，不新造平行资源', () => {
  assert.doesNotMatch(apiSource, /export function listTopicChapter\(/)
  assert.match(apiSource, /export function getTopicChapter\(id\)[\s\S]*url:\s*'\/business\/topicchapter\/' \+ id[\s\S]*method:\s*'get'/)
  assert.match(apiSource, /export function addTopicChapter\(data\)[\s\S]*url:\s*'\/business\/topicchapter'[\s\S]*method:\s*'post'/)
  assert.match(apiSource, /export function updateTopicChapter\(data\)[\s\S]*url:\s*'\/business\/topicchapter'[\s\S]*method:\s*'put'/)
  assert.match(apiSource, /export function delTopicChapter\(id\)[\s\S]*url:\s*'\/business\/topicchapter\/' \+ id[\s\S]*method:\s*'delete'/)
})

test('快速切换编辑章节时，迟到的旧详情响应不能覆盖当前表单', async () => {
  const pending = new Map()
  const component = loadTopicDetail({
    getTopicChapter: id => new Promise(resolve => pending.set(id, resolve))
  })
  const vm = Object.assign(component.data(), {
    $nextTick: callback => callback(),
    $refs: { chapterForm: { clearValidate: () => {} } }
  })
  vm.handleEditChapter = component.methods.handleEditChapter.bind(vm)

  const first = vm.handleEditChapter({ id: 4 })
  const second = vm.handleEditChapter({ id: 8 })
  pending.get(8)({ code: 200, data: { id: 8, topicId: 17, name: '第二店', sortId: 2, required: 1 } })
  await second
  pending.get(4)({ code: 200, data: { id: 4, topicId: 17, name: '第一店', sortId: 1, required: 1 } })
  await first

  assert.equal(vm.chapterForm.id, 8)
  assert.equal(vm.chapterForm.name, '第二店')
  assert.equal(vm.chapterSaving, false)
})

test('空章节仍显示新增入口，首期四章只是建议而不是数量上限', () => {
  assert.match(detailSource, /<el-card class="mt10"[^>]*>[\s\S]*章节信息/)
  assert.match(detailSource, /<el-empty[^>]+description="暂无章节，先创建第一个店位章节"/)
  assert.match(detailSource, /首期可先建四个店位章节/)
  assert.doesNotMatch(detailSource, /chapterList\.length\s*(?:>=|===|==)\s*4/)
  assert.doesNotMatch(detailSource, /:max="4"/)
})

test('编辑从章节详情 API 回显，新增和编辑都在成功后重新读取主题详情', async () => {
  const writes = []
  const getTopicChapter = id => Promise.resolve({ code: 200, data: {
    id, topicId: 17, name: '咖啡店', description: '手冲体验', sortId: 3, required: 1
  } })
  const component = loadTopicDetail({
    getTopic: () => Promise.resolve({ code: 200, data: {} }),
    getTopicChapter,
    addTopicChapter: data => { writes.push(['add', data]); return Promise.resolve({ code: 200 }) },
    updateTopicChapter: data => { writes.push(['edit', data]); return Promise.resolve({ code: 200 }) },
    delTopicChapter: () => Promise.resolve({ code: 200 }),
    configureChapterSupplyTerms: () => Promise.resolve({ code: 200 }),
    getTopicLeadFulfillment: () => Promise.resolve({ code: 200, data: [] }),
    addTicket: () => Promise.resolve({ code: 200 }),
    updateTicket: () => Promise.resolve({ code: 200 })
  })
  let readbacks = 0
  const vm = Object.assign(component.data(), {
    topicDetail: { id: 17, memberId: 9 },
    chapterList: [{ id: 4, sortId: 1 }, { id: 8, sortId: 2 }],
    $refs: { chapterForm: { validate: callback => callback(true), clearValidate: () => {} } },
    $nextTick: callback => callback(),
    $modal: { msgSuccess: () => {} },
    getDetail: () => { readbacks += 1; return Promise.resolve() }
  })
  vm.handleEditChapter = component.methods.handleEditChapter.bind(vm)
  vm.submitChapterForm = component.methods.submitChapterForm.bind(vm)

  await vm.handleEditChapter({ id: 8 })
  assert.deepEqual(vm.chapterForm, {
    id: 8, topicId: 17, name: '咖啡店', description: '手冲体验', sortId: 3, required: 1,
    recruitEnabled: 0, categoryId: null
  })

  vm.submitChapterForm()
  await flushPromises()
  assert.deepEqual(writes[0], ['edit', {
    id: 8, name: '咖啡店', description: '手冲体验', sortId: 3, required: 1,
    recruitEnabled: 0, categoryId: null
  }])

  vm.chapterForm = { id: null, topicId: 17, name: '甜品店', description: '', sortId: 4, required: 1 }
  vm.submitChapterForm()
  await flushPromises()
  assert.deepEqual(writes[1], ['add', {
    topicId: 17, name: '甜品店', description: '', sortId: 4, required: 1,
    termsMode: 'PERK', maxMerchant: 1, recruitEnabled: 0, categoryId: null
  }])
  assert.equal(readbacks, 2)
})

test('删除章节经确认调用真实 API；排序由可编辑 sortId 写回而不是前端假排序', async () => {
  const deleted = []
  const component = loadTopicDetail({
    getTopic: () => Promise.resolve({ code: 200, data: {} }),
    getTopicChapter: () => Promise.resolve({ code: 200, data: {} }),
    addTopicChapter: () => Promise.resolve({ code: 200 }),
    updateTopicChapter: () => Promise.resolve({ code: 200 }),
    delTopicChapter: id => { deleted.push(id); return Promise.resolve({ code: 200 }) },
    configureChapterSupplyTerms: () => Promise.resolve({ code: 200 }),
    getTopicLeadFulfillment: () => Promise.resolve({ code: 200, data: [] }),
    addTicket: () => Promise.resolve({ code: 200 }),
    updateTicket: () => Promise.resolve({ code: 200 })
  })
  let readbacks = 0
  const vm = Object.assign(component.data(), {
    topicDetail: { id: 17 },
    $modal: {
      confirm: () => Promise.resolve(),
      msgSuccess: () => {}
    },
    getDetail: () => { readbacks += 1; return Promise.resolve() }
  })
  vm.handleDeleteChapter = component.methods.handleDeleteChapter.bind(vm)

  await vm.handleDeleteChapter({ id: 8, name: '甜品店' })
  assert.deepEqual(deleted, [8])
  assert.equal(readbacks, 1)
  assert.match(detailSource, /v-model="chapterForm\.sortId"/)
  assert.match(detailSource, /数字越小越靠前/)
  assert.match(detailSource, /sortId:\s*Number\(this\.chapterForm\.sortId\)/)
})
