const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const memberTemplateSource = fs.readFileSync(path.join(uiRoot, 'src/views/business/membertemplate/index.vue'), 'utf8')
const canonicalDetailSource = fs.readFileSync(path.join(uiRoot, 'src/views/business/membertemplate/detail.vue'), 'utf8')
const legacyLibrarySource = fs.readFileSync(path.join(uiRoot, 'src/views/business/templatelibrary/index.vue'), 'utf8')
const legacyDetailSource = fs.readFileSync(path.join(uiRoot, 'src/views/business/templatelibrary/detail.vue'), 'utf8')
const routerSource = fs.readFileSync(path.join(uiRoot, 'src/router/index.js'), 'utf8')

function loadTemplateCenter(dependencies) {
  const script = memberTemplateSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ delMembertemplate, getMembertemplate, listMembertemplate, submitMembertemplateReview, updateMembertemplate \} from '@\/api\/business\/membertemplate'$/m, 'const { delMembertemplate, getMembertemplate, listMembertemplate, submitMembertemplateReview, updateMembertemplate } = dependencies')
    .replace(/^import \{ listCategory \} from '@\/api\/system\/category'$/m, 'const { listCategory } = dependencies')
    .replace(/^import \{ parseTime \} from '@\/utils\/ruoyi'$/m, 'const { parseTime } = dependencies')
    .replace(/^import \{ checkPermi \} from '@\/utils\/permission'$/m, 'const { checkPermi } = dependencies')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {}')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies)
}

function loadLegacyList() {
  const script = legacyLibrarySource.match(/<script>([\s\S]*?)<\/script>/)[1]
  return new Function(script.replace(/export\s+default\s*/, 'return '))()
}

function loadLegacyDetail(getTemplatelibrary) {
  const script = legacyDetailSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ getTemplatelibrary \} from '@\/api\/business\/templatelibrary'$/m, 'const { getTemplatelibrary } = dependencies')
    .replace(/^import CyResourceState from .+$/m, 'const CyResourceState = {}')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({ getTemplatelibrary })
}

function loadCanonicalDetail(dependencies) {
  const script = canonicalDetailSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ getMembertemplate \} from '@\/api\/business\/membertemplate'$/m, 'const { getMembertemplate } = dependencies')
    .replace(/^import \{ getCouponInfo \} from '@\/api\/business\/coupon'$/m, 'const { getCouponInfo } = dependencies')
    .replace(/^import \{ getCategoryList \} from '@\/api\/system\/category'$/m, 'const { getCategoryList } = dependencies')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {}')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies)
}

test('模板中心只从 membertemplate 真源读取和发起审核写入', () => {
  assert.match(memberTemplateSource, /CyPageHeader/)
  assert.match(memberTemplateSource, /CyFilterBar/)
  assert.match(memberTemplateSource, /CyDataTable/)
  assert.match(memberTemplateSource, /listMembertemplate/)
  assert.match(memberTemplateSource, /submitMembertemplateReview/)
  assert.match(memberTemplateSource, /v-if="canViewDetail"/)
  assert.doesNotMatch(memberTemplateSource, /@\/api\/business\/templatelibrary/)
  assert.doesNotMatch(memberTemplateSource, /business:templatelibrary/)
  assert.doesNotMatch(memberTemplateSource, /templatelibraryList/)
})

test('旧查阅状态不会被误作模板审核状态', () => {
  const component = loadTemplateCenter({
    delMembertemplate: () => Promise.resolve(),
    getMembertemplate: () => Promise.resolve({ data: {} }),
    listMembertemplate: () => Promise.resolve({ rows: [], total: 0 }),
    submitMembertemplateReview: () => Promise.resolve(),
    updateMembertemplate: () => Promise.resolve(),
    listCategory: () => Promise.resolve({ rows: [] }),
    parseTime: () => '',
    checkPermi: () => true
  })
  const vm = component.data()

  component.methods.hydrateFromRoute.call(vm, { check_status: '2' })
  assert.equal(vm.queryForm.status, null)
  assert.equal(vm.queryParams.status, null)

  component.methods.hydrateFromRoute.call(vm, { status: '2' })
  assert.equal(vm.queryForm.status, 2)
  assert.equal(vm.queryParams.status, 2)
})

test('旧模板库列表只把筛选条件交给模板中心，不再承载 CRUD', () => {
  // [P2-4 Q5] 2086 由 4 迁入 2209 并改名「模板中心」,/business/membertemplate 不再存在。
  // 壳改为按 route name 跳转(RuoYi getRouteName = capitalize(path),与 parent 无关)。
  assert.match(legacyLibrarySource, /name:\s*['\"]Membertemplate['\"]/)
  assert.doesNotMatch(legacyLibrarySource, /path:\s*['\"]\/business\/membertemplate['\"]/)
  assert.match(legacyLibrarySource, /query:\s*this\.\$route\.query/)
  assert.doesNotMatch(legacyLibrarySource, /listTemplatelibrary|addTemplatelibrary|updateTemplatelibrary|delTemplatelibrary/)
  assert.doesNotMatch(legacyLibrarySource, /handleAdd|handleUpdate|handleDelete|submitForm/)
})

test('旧模板库详情先用 projection 的 memberTemplateId 定位唯一模板详情', () => {
  assert.match(legacyDetailSource, /getTemplatelibrary/)
  assert.match(legacyDetailSource, /memberTemplateId/)
  assert.match(legacyDetailSource, /name:\s*['\"]membertemplateDetail['\"]|path:\s*['\"]\/membertemplate\/detail['\"]/)
  assert.doesNotMatch(legacyDetailSource, /getCouponInfo|getCategoryList/)
  assert.match(routerSource, /name:\s*['\"]membertemplateDetail['\"]/)
})

test('模板中心详情只读展示用户模板真源，并使用统一对象详情组件', () => {
  assert.match(canonicalDetailSource, /CyPageHeader/)
  assert.match(canonicalDetailSource, /CyEntityHeader/)
  assert.match(canonicalDetailSource, /CyResourceState/)
  assert.match(canonicalDetailSource, /getMembertemplate/)
  assert.doesNotMatch(canonicalDetailSource, /getTemplatelibrary/)
  assert.doesNotMatch(canonicalDetailSource, /submitMembertemplateReview|updateMembertemplate|delMembertemplate/)
})

test('模板中心详情不接受较早的真源详情回包', async () => {
  const pending = []
  const component = loadCanonicalDetail({
    getMembertemplate: id => new Promise(resolve => pending.push({ id, resolve })),
    getCouponInfo: () => Promise.resolve({ data: {} }),
    getCategoryList: () => Promise.resolve({ data: [] })
  })
  const vm = Object.assign(component.data(), {
    $route: { query: {} },
    $router: { push: () => {} }
  })
  for (const name of ['loadTemplateDetail', 'loadCoupon', 'loadCategories']) {
    vm[name] = component.methods[name].bind(vm)
  }

  const first = vm.loadTemplateDetail('11')
  const second = vm.loadTemplateDetail('12')
  assert.deepEqual(pending.map(request => request.id), ['11', '12'])
  pending[1].resolve({ data: { id: 12, title: '新模板' } })
  await second
  pending[0].resolve({ data: { id: 11, title: '旧模板' } })
  await first

  assert.equal(vm.templateId, '12')
  assert.deepEqual(vm.templateDetail, { id: 12, title: '新模板' })
  assert.equal(vm.detailError, false)
  assert.equal(vm.loading, false)
})

test('模板中心不接受较早列表回包覆盖新的筛选结果', async () => {
  const pending = []
  const component = loadTemplateCenter({
    delMembertemplate: () => Promise.resolve(),
    getMembertemplate: () => Promise.resolve({ data: {} }),
    listMembertemplate: query => new Promise(resolve => pending.push({ query, resolve })),
    submitMembertemplateReview: () => Promise.resolve(),
    updateMembertemplate: () => Promise.resolve(),
    listCategory: () => Promise.resolve({ rows: [] }),
    parseTime: () => '2026-07-22 12:00',
    checkPermi: () => true
  })
  const vm = Object.assign(component.data(), {
    $route: { query: {} },
    $router: { push: () => {}, replace: () => {} },
    $modal: { confirm: () => Promise.resolve(), msgError: () => {}, msgSuccess: () => {} }
  })
  vm.getList = component.methods.getList.bind(vm)

  const first = vm.getList()
  vm.queryParams.title = '夜游'
  const second = vm.getList()
  assert.equal(pending[1].query.title, '夜游')

  pending[1].resolve({ rows: [{ id: 2, title: '夜游模板' }], total: 1 })
  await second
  pending[0].resolve({ rows: [{ id: 1, title: '旧模板' }], total: 1 })
  await first

  assert.deepEqual(vm.templateList, [{ id: 2, title: '夜游模板' }])
  assert.equal(vm.total, 1)
  assert.equal(vm.listError, false)
})

test('旧模板库列表保留 query 跳转，详情用 projection 关联的真源 ID 跳转', async () => {
  const listRedirects = []
  const legacyList = loadLegacyList()
  legacyList.created.call({
    $route: { query: { title: '夜游', status: '0' } },
    $router: { replace: target => listRedirects.push(target) }
  })
  assert.deepEqual(listRedirects, [{
    name: 'Membertemplate',
    query: { title: '夜游', status: '0' }
  }])

  const detailRedirects = []
  const legacyDetail = loadLegacyDetail(id => Promise.resolve({ data: { id, memberTemplateId: 91 } }))
  const vm = Object.assign(legacyDetail.data(), {
    $route: { query: { id: '41', source: 'legacy-library' } },
    $router: { replace: target => detailRedirects.push(target) }
  })
  vm.redirectToCanonical = legacyDetail.methods.redirectToCanonical.bind(vm)
  await vm.redirectToCanonical()
  assert.deepEqual(detailRedirects, [{
    name: 'membertemplateDetail',
    query: { id: 91, source: 'legacy-library' }
  }])
})

test('旧模板库详情缺少真源关联时不把 projection ID 误导向模板详情', async () => {
  const redirects = []
  const legacyDetail = loadLegacyDetail(() => Promise.resolve({ data: { id: 41, memberTemplateId: null } }))
  const vm = Object.assign(legacyDetail.data(), {
    $route: { query: { id: '41' } },
    $router: { replace: target => redirects.push(target) }
  })
  vm.redirectToCanonical = legacyDetail.methods.redirectToCanonical.bind(vm)
  await vm.redirectToCanonical()
  assert.deepEqual(redirects, [])
  assert.equal(vm.redirectError, true)
  assert.match(vm.redirectMessage, /不能把投影编号误当成模板编号/)
})

test('旧模板库详情不接受较早投影回包的重定向', async () => {
  const pending = []
  const redirects = []
  const legacyDetail = loadLegacyDetail(id => new Promise(resolve => pending.push({ id, resolve })))
  const vm = Object.assign(legacyDetail.data(), {
    $route: { query: {} },
    $router: { replace: target => redirects.push(target) }
  })
  vm.redirectToCanonical = legacyDetail.methods.redirectToCanonical.bind(vm)

  const first = vm.redirectToCanonical({ id: '41' })
  const second = vm.redirectToCanonical({ id: '42' })
  assert.deepEqual(pending.map(request => request.id), ['41', '42'])
  pending[1].resolve({ data: { id: 42, memberTemplateId: 92 } })
  await second
  pending[0].resolve({ data: { id: 41, memberTemplateId: 91 } })
  await first

  assert.deepEqual(redirects, [{
    name: 'membertemplateDetail',
    query: { id: 92 }
  }])
  assert.equal(vm.loading, false)
})


function editorHarness(overrides = {}) {
  const writes = [], messages = [], routes = []
  const component = loadTemplateCenter(Object.assign({
    getMembertemplate: id => Promise.resolve({ data: { id, title: '夜游', validationMethod: 3,
      questionName: '下一站？', questionA: '钟楼', questionB: '码头', correctAnswer: 'B',
      questionOptionMediaJson: '{"B":{"img":"existing-media"}}', merchantGuide: '仅店员可见', activityCategoryids: '8,9' } }),
    updateMembertemplate: payload => { writes.push(payload); return Promise.resolve() },
    listCategory: () => Promise.resolve({ rows: [] }),
    listMembertemplate: () => Promise.resolve({ rows: [], total: 0 }),
    parseTime: () => '', checkPermi: () => true
  }, overrides))
  const vm = Object.assign(component.data(), {
    $refs: { editForm: { validate: callback => callback(true) } },
    $router: { push: route => routes.push(route) },
    $modal: { msgError: value => messages.push(value), msgSuccess: value => messages.push(value) }
  })
  for (const [name, method] of Object.entries(component.methods)) vm[name] = method.bind(vm)
  return { component, vm, writes, messages, routes }
}

test('选择题可从现有模板编辑后保存并进入原统一审核，不重复提交审核', async () => {
  const { vm, writes, routes } = editorHarness({ submitMembertemplateReview: () => assert.fail('save already queues review') })
  await vm.handleEdit({ id: 18 })
  vm.editForm.questionB = '书店'
  await vm.submitEdit(true)
  assert.equal(writes.length, 1)
  assert.equal(writes[0].questionB, '书店')
  assert.equal(writes[0].correctAnswer, 'B')
  assert.equal(writes[0].questionOptionMediaJson, '{"B":{"img":"existing-media"}}')
  assert.equal(writes[0].merchantGuide, '仅店员可见')
  assert.equal(writes[0].activityCategoryids, '8,9')
  assert.deepEqual(routes, [{ name: 'Audit', query: { bizType: 'TEMPLATE', bizId: 18 } }])
  assert.equal(vm.editOpen, false)
})

test('选择题拒绝空问题、少于两个选项及指向空选项的答案', async () => {
  for (const invalid of [{ questionName: ' ' }, { questionA: '' }, { questionB: ' ' }, { correctAnswer: 'C' }, { correctAnswer: 'E' }, { correctAnswer: '' }]) {
    const { vm, writes, messages } = editorHarness()
    await vm.handleEdit({ id: 18 })
    Object.assign(vm.editForm, invalid)
    await vm.submitEdit()
    assert.equal(writes.length, 0)
    assert.equal(messages.length, 1)
    assert.equal(vm.editOpen, true)
    assert.equal(vm.editSaving, false)
  }
})

test('选填选项可作为正确选项，切换验证方式不清除既有题目与媒体', async () => {
  const { vm, writes } = editorHarness()
  await vm.handleEdit({ id: 18 })
  vm.editForm.questionD = '广场'
  vm.editForm.correctAnswer = 'D'
  assert.equal(vm.choiceValidationMessage(), '')
  vm.editForm.validationMethod = 1
  vm.editForm.questionAnswer = '文字答案'
  await vm.submitEdit()
  assert.equal(writes[0].questionD, '广场')
  assert.equal(writes[0].questionAnswer, '文字答案')
  assert.equal(writes[0].correctAnswer, 'D')
})

test('连续点击保存只发一次现有更新请求，完成前不能切到另一份模板', async () => {
  let finish, count = 0
  const { vm } = editorHarness({ updateMembertemplate: () => { count++; return new Promise(resolve => { finish = resolve }) } })
  await vm.handleEdit({ id: 18 })
  const saving = vm.submitEdit()
  await vm.submitEdit(true)
  await vm.handleEdit({ id: 19 })
  assert.equal(count, 1)
  assert.equal(vm.editForm.id, 18)
  assert.equal(vm.editSaving, true)
  finish()
  await saving
  assert.equal(vm.editSaving, false)
})

test('保存失败保留全部编辑内容，不跳转审核，并可重新核对后重试', async () => {
  let count = 0
  const { vm, routes, messages } = editorHarness({ updateMembertemplate: () => { count++; return Promise.reject(new Error('network')) } })
  await vm.handleEdit({ id: 18 })
  vm.editForm.questionA = '尚未保存的选择'
  await vm.submitEdit(true)
  assert.equal(vm.editOpen, true)
  assert.equal(vm.editSaving, false)
  assert.equal(vm.editForm.questionA, '尚未保存的选择')
  assert.deepEqual(routes, [])
  assert.match(messages[0], /保存未确认/)
  await vm.submitEdit()
  assert.equal(count, 2)
})

test('取消或离开后的旧回包不能重新打开编辑器', async () => {
  let finish
  const { vm } = editorHarness({ getMembertemplate: () => new Promise(resolve => { finish = resolve }) })
  const opening = vm.handleEdit({ id: 18 })
  vm.invalidateEdit()
  finish({ data: { id: 18 } })
  await opening
  assert.equal(vm.editOpen, false)
  assert.equal(vm.editForm.id, null)
})

test('较新的模板编辑读取优先，空或错配对象不能编辑', async () => {
  const pending = []
  const { vm } = editorHarness({ getMembertemplate: id => new Promise(resolve => pending.push({ id, resolve })) })
  const old = vm.handleEdit({ id: 18 }), recent = vm.handleEdit({ id: 19 })
  pending[1].resolve({ data: { id: 19 } })
  await recent
  pending[0].resolve({ data: { id: 18 } })
  await old
  assert.equal(vm.editForm.id, 19)
  for (const data of [null, { id: 99 }]) {
    const h = editorHarness({ getMembertemplate: () => Promise.resolve({ data }) })
    await h.vm.handleEdit({ id: 18 })
    assert.equal(h.vm.editOpen, false)
    assert.equal(h.messages.length, 1)
  }
})

test('离开路由后的保存回包不能关闭新编辑器或跳转旧审核', async () => {
  let finish
  const { vm, routes, messages } = editorHarness({ updateMembertemplate: () => new Promise(resolve => { finish = resolve }) })
  await vm.handleEdit({ id: 18 })
  const saving = vm.submitEdit(true)
  vm.invalidateEdit()
  await vm.handleEdit({ id: 19 })
  finish()
  await saving
  assert.equal(vm.editOpen, true)
  assert.equal(vm.editForm.id, 19)
  assert.deepEqual(routes, [])
  assert.deepEqual(messages, [])
})

test('异步表单校验完成前取消不会保存，普通校验失败会释放保存锁', async () => {
  const { vm, writes } = editorHarness()
  await vm.handleEdit({ id: 18 })
  let validated
  vm.$refs.editForm.validate = callback => { validated = callback }
  const pending = vm.submitEdit()
  vm.invalidateEdit()
  validated(true)
  await pending
  assert.equal(writes.length, 0)
  vm.$refs.editForm.validate = callback => callback(false)
  await vm.submitEdit()
  assert.equal(vm.editSaving, false)
  assert.equal(writes.length, 0)
})

test('选择题入口、答案与审核跳转按钮接到现有表单，提交时禁用关闭与再次提交', () => {
  assert.match(memberTemplateSource, /v-model="editForm\[`question\$\{letter\}`\]"/)
  assert.match(memberTemplateSource, /v-model="editForm.correctAnswer"/)
  assert.match(memberTemplateSource, /:disabled="editSaving"/)
  assert.match(memberTemplateSource, /:show-close="!editSaving"/)
  assert.match(memberTemplateSource, /保存并查看审核/)
  assert.match(memberTemplateSource, /business:audit:list/)
  assert.doesNotMatch(memberTemplateSource, /localStorage|sessionStorage/)
})


test('离开路由和 keep-alive 停用均阻止旧保存跳转与旧读取重开', async () => {
  for (const hook of ['beforeRouteLeave', 'deactivated', 'beforeDestroy']) {
    let finish
    const h = editorHarness({ updateMembertemplate: () => new Promise(resolve => { finish = resolve }) })
    await h.vm.handleEdit({ id: 18 })
    const saving = h.vm.submitEdit(true)
    let nextCalls = 0
    if (hook === 'beforeRouteLeave') h.component[hook].call(h.vm, {}, {}, () => nextCalls++)
    else h.component[hook].call(h.vm)
    finish()
    await saving
    assert.deepEqual(h.routes, [])
    assert.deepEqual(h.messages, [])
    assert.equal(h.vm.editSaving, false)
    if (hook !== 'beforeDestroy') assert.equal(h.vm.editOpen, false)
    if (hook === 'beforeRouteLeave') assert.equal(nextCalls, 1)

    const r = editorHarness({ getMembertemplate: () => new Promise(resolve => { finish = resolve }) })
    const opening = r.vm.handleEdit({ id: 18 })
    if (hook === 'beforeRouteLeave') r.component[hook].call(r.vm, {}, {}, () => {})
    else r.component[hook].call(r.vm)
    finish({ data: { id: 18 } })
    await opening
    assert.equal(r.vm.editOpen, false)
  }
})


test('至少两个选项不限定必须 A、B，兼容既有非连续选项', async () => {
  const { vm, writes } = editorHarness()
  await vm.handleEdit({ id: 18 })
  vm.editForm.questionA = ''
  vm.editForm.questionD = '广场'
  vm.editForm.correctAnswer = 'D'
  await vm.submitEdit()
  assert.equal(writes.length, 1)
  assert.equal(writes[0].correctAnswer, 'D')
})
