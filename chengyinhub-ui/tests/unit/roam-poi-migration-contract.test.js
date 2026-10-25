const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/roampoi/index.vue'), 'utf8')
const routerSource = fs.readFileSync(path.resolve(__dirname, '../../src/router/index.js'), 'utf8')

test('审核区只展示 roam_poi 字段，不再声称复用 merchantapply 账本', () => {
  assert.doesNotMatch(source, /merchantapply|商家报名账本/)
  assert.match(source, /s\.row\.applicantMerchantId/)
  assert.match(source, /s\.row\.auditReason/)
  assert.match(source, /s\.row\.address/)
})

function loadRoamPoi(dependencies) {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ adminPoiList, poiOnline, poiOffline, roamPoiApplications, approveRoamPoiApplication, rejectRoamPoiApplication \} from ['"]@\/api\/business\/roamPoi['"]$/m,
      'const { adminPoiList, poiOnline, poiOffline, roamPoiApplications, approveRoamPoiApplication, rejectRoamPoiApplication } = dependencies;')
    .replace(/^import \{ approveOfficialPoi, rejectOfficialPoi \} from .+$/m, 'const { approveOfficialPoi, rejectOfficialPoi } = dependencies;')
    .replace(/^import (Cy\w+|OfficialEditor) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies)
}

test('漫游 POI 治理页有受原列表权限保护的规范入口，并保留旧菜单高亮', () => {
  assert.match(routerSource, /path: '\/club\/roam-poi'[\s\S]*?permissions: \['business:roamPoi:list'\][\s\S]*?path: ''[\s\S]*?component: \(\) => import\('@\/views\/business\/roampoi\/index'\)[\s\S]*?name: 'clubRoamPoi'[\s\S]*?activeMenu: '\/roam\/roampoi'/)  // [P2-4] 2224 迁入新建「漫游」域
})

test('治理页复用后台标准页头、筛选和错误态，不把无分页接口伪装成分页列表', () => {
  assert.ok(source.includes("import CyPageHeader from '@/components/CyPageHeader'"))
  assert.ok(source.includes("import CyFilterBar from '@/components/CyFilterBar'"))
  assert.ok(source.includes("import CyResourceState from '@/components/CyResourceState'"))
  assert.match(source, /<cy-page-header[\s\S]*?title="漫游 POI 治理"/)
  assert.match(source, /<cy-filter-bar[\s\S]*?:active-filters="activeFilters"[\s\S]*?@submit="handleQuery"[\s\S]*?@remove-filter="clearFilter"/)
  assert.match(source, /<cy-resource-state[\s\S]*?title="漫游 POI 列表暂时无法读取"/)
  assert.doesNotMatch(source, /<pagination/)
  assert.doesNotMatch(source, /<cy-data-table/)
})

test('旧入口只携带白名单筛选跳转到规范入口，不提前拉取旧路径数据', () => {
  const component = loadRoamPoi({ adminPoiList: () => Promise.resolve({ data: [] }) })
  let replacement
  let listCalls = 0
  const vm = Object.assign(component.data(), {
    $route: {
      path: '/business/roampoi',
      query: { status: '1', cityCode: 'HZ ', merchantId: '12', name: '不应透传', unexpected: 'x' }
    },
    $router: { replace: location => { replacement = location } },
    getList: () => { listCalls += 1 }
  })

  component.methods.initializePage.call(vm)

  assert.deepEqual(replacement, {
    name: 'clubRoamPoi',
    query: { status: '1', cityCode: 'HZ', merchantId: '12' }
  })
  assert.equal(listCalls, 0)
})

test('筛选草稿与已应用条件分离，晚到的旧响应不会覆盖最新结果', async () => {
  const pending = []
  const component = loadRoamPoi({
    adminPoiList: params => new Promise(resolve => pending.push({ params, resolve })),
    poiOnline: () => Promise.resolve(),
    poiOffline: () => Promise.resolve()
  })
  const vm = Object.assign(component.data(), {
    $modal: { msgError: () => {} },
    parseTime: () => '2026-07-23 09:00'
  })
  vm.getList = component.methods.getList.bind(vm)
  vm.requestParams = component.methods.requestParams.bind(vm)

  vm.queryForm.cityCode = 'HZ'
  const firstRequest = component.methods.handleQuery.call(vm)
  assert.deepEqual(pending[0].params, { cityCode: 'HZ' })
  assert.deepEqual(component.computed.activeFilters.call(vm), [{ key: 'cityCode', label: '城市码', value: 'HZ' }])

  vm.queryForm.cityCode = 'SH'
  assert.deepEqual(component.computed.activeFilters.call(vm), [{ key: 'cityCode', label: '城市码', value: 'HZ' }])
  const secondRequest = component.methods.handleQuery.call(vm)
  assert.deepEqual(pending[1].params, { cityCode: 'SH' })

  pending[0].resolve({ data: [{ id: 1 }] })
  await firstRequest
  assert.deepEqual(vm.list, [])
  assert.equal(vm.loading, true)

  pending[1].resolve({ data: [{ id: 2 }] })
  await secondRequest
  assert.deepEqual(vm.list, [{ id: 2 }])
  assert.equal(vm.loading, false)
})

test('可见性治理只允许已知上下架状态的可逆转换，缺失奖励不被误写为零', async () => {
  const calls = []
  const messages = []
  const component = loadRoamPoi({
    adminPoiList: () => Promise.resolve({ data: [] }),
    poiOnline: id => { calls.push(`online:${id}`); return Promise.resolve() },
    poiOffline: id => { calls.push(`offline:${id}`); return Promise.resolve() }
  })
  const vm = Object.assign(component.data(), {
    $modal: {
      confirm: () => Promise.resolve(),
      msgSuccess: message => messages.push(message),
      msgError: () => { throw new Error('不应进入失败态') }
    },
    getList: () => Promise.resolve()
  })
  vm.canTransition = component.methods.canTransition.bind(vm)
  vm.hasValue = component.methods.hasValue.bind(vm)
  vm.changeVisibility = component.methods.changeVisibility.bind(vm)

  await component.methods.onOffline.call(vm, { id: 7, name: '西湖边', status: 1 })
  await component.methods.onOnline.call(vm, { id: 8, name: '未知据点', status: 9 })
  await component.methods.onOnline.call(vm, { id: 9, name: '空状态据点', status: null })
  await component.methods.onOnline.call(vm, { id: 10, name: '空字符串状态据点', status: '' })
  await component.methods.onOnline.call(vm, { id: 11, name: '待审投放', status: 0, auditStatus: 0 })
  await component.methods.onOnline.call(vm, { id: 12, name: '驳回投放', status: 0, auditStatus: 2 })

  assert.deepEqual(calls, ['offline:7'])
  assert.deepEqual(messages, ['已下架，玩家侧将不可见。'])
  assert.equal(component.methods.statusInfo(null).key, 'unknown')
  assert.equal(component.methods.statusInfo('').key, 'unknown')
  assert.equal(component.methods.formatXp(null), '未配置')
  assert.equal(component.methods.formatXp(0), 'XP 0')
})

// 2026-08-10:审核材料从「模板 #ID」升级成真实内容。
// 审核要判的是「这个玩法跑不跑得起来、题目合不合规」——只给 id 判不了,
// 审核员只能自己去数据库翻,那等于没有审核面。
test('据点投放审核材料展示商家资料与玩法内容,而不是一个模板 id', () => {
  assert.match(source, /reviewMaterial\.merchant\.name/)
  assert.match(source, /reviewMaterial\.merchant\.phone/)
  assert.match(source, /reviewMaterial\.template\.title/)
  assert.match(source, /reviewMaterial\.template\.questionName/)
  assert.match(source, /reviewMaterial\.template\.correctAnswer/)
  assert.match(source, /interactionTypeText\(/)
  assert.match(source, /validationMethodText\(/)
  // 读不到模板内容时必须明说,不能静默渲染成一张空白材料让人闭眼通过
  assert.match(source, /读不到内容,先别通过/)
})

test('未配的档位显示成「未配」而不是留空', () => {
  // 空白会被审核员当成「这项不适用」,而它其实是「商家没配,玩家会卡死在这一关」
  assert.match(source, /未配玩法形态/)
  assert.match(source, /未配完成方式/)
  assert.match(source, /玩家永远答不对/)
})

test('★负控:材料退回只显示模板 id 必须判红', () => {
  const broken = source.replace(/reviewMaterial\.template\.correctAnswer/g, 'templateId')
  assert.notEqual(broken, source, '负控锚点失效')
  assert.throws(() => assert.match(broken, /reviewMaterial\.template\.correctAnswer/))
})
