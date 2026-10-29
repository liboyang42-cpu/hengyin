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

function loadTemplateContentModel() {
  const source = fs.readFileSync(path.join(uiRoot, 'src/views/business/membertemplate/template-content.js'), 'utf8')
  return new Function(source.replace(/^export /gm, '') + '\nreturn { validationMethods, validationLabel, contentSummary, buildTemplatePayload }')()
}

function loadTemplateCenter(dependencies) {
  const script = memberTemplateSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ delMembertemplate, getMembertemplate, listMembertemplate, submitMembertemplateReview, updateMembertemplate \} from '@\/api\/business\/membertemplate'$/m, 'const { delMembertemplate, getMembertemplate, listMembertemplate, submitMembertemplateReview, updateMembertemplate } = dependencies')
    .replace(/^import \{ listCategory \} from '@\/api\/system\/category'$/m, 'const { listCategory } = dependencies')
    .replace(/^import \{ parseTime \} from '@\/utils\/ruoyi'$/m, 'const { parseTime } = dependencies')
    .replace(/^import \{ checkPermi \} from '@\/utils\/permission'$/m, 'const { checkPermi } = dependencies')
    .replace(/^import TemplateContentFields from .+$/m, 'const TemplateContentFields = {}')
    .replace(/^import \{ validationMethods, validationLabel, contentSummary, buildTemplatePayload \} from '\.\/template-content'$/m, 'const { validationMethods, validationLabel, contentSummary, buildTemplatePayload } = contentModel')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {}')
  return new Function('dependencies', 'contentModel', script.replace(/export\s+default\s*/, 'return '))(dependencies, loadTemplateContentModel())
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
    .replace(/^import TemplateContentFields from .+$/m, 'const TemplateContentFields = {}')
    .replace(/^import \{ validationLabel \} from '\.\/template-content'$/m, 'const { validationLabel } = contentModel')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {}')
  return new Function('dependencies', 'contentModel', script.replace(/export\s+default\s*/, 'return '))(dependencies, loadTemplateContentModel())
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
