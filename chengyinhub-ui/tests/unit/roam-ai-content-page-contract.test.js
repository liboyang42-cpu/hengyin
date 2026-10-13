const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const repositoryRoot = path.resolve(uiRoot, '..')

const pagePath = path.join(uiRoot, 'src/views/business/roamaicontent/index.vue')
const apiPath = path.join(uiRoot, 'src/api/business/roamAiContent.js')

const source = fs.readFileSync(pagePath, 'utf8')
const apiSource = fs.readFileSync(apiPath, 'utf8')
const routerSource = fs.readFileSync(path.join(uiRoot, 'src/router/index.js'), 'utf8')
const controllerSource = fs.readFileSync(
  path.join(repositoryRoot, 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsRoamAiContentController.java'),
  'utf8'
)
const migrationSource = fs.readFileSync(
  path.join(repositoryRoot, 'chengyinhub-admin/sql/migration_roam_ai_content_menu_20260727.sql'),
  'utf8'
)

// 把 SFC 的 <script> 抠出来当普通模块跑:依赖从外部注入,DS 组件替成空对象。
function loadPage(dependencies) {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(
      /^import \{ listRoamAiContent, getRoamAiContent, addRoamAiContent, updateRoamAiContent, auditRoamAiContent, delRoamAiContent \} from ['"]@\/api\/business\/roamAiContent['"]$/m,
      'const { listRoamAiContent, getRoamAiContent, addRoamAiContent, updateRoamAiContent, auditRoamAiContent, delRoamAiContent } = dependencies;'
    )
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies)
}

const noopApi = () => Promise.resolve({ data: [] })

function stubDependencies(overrides) {
  return Object.assign(
    {
      listRoamAiContent: noopApi,
      getRoamAiContent: noopApi,
      addRoamAiContent: noopApi,
      updateRoamAiContent: noopApi,
      auditRoamAiContent: noopApi,
      delRoamAiContent: noopApi
    },
    overrides
  )
}

test('页面、API 与路由三处真实存在并互相接线,不是悬空文件', () => {
  assert.equal(fs.existsSync(pagePath), true)
  assert.equal(fs.existsSync(apiPath), true)
  assert.ok(source.includes("from '@/api/business/roamAiContent'"), '页面必须真的引用该 API 模块')
  assert.match(
    routerSource,
    /path: '\/roam\/ai-content'[\s\S]*?permissions: \['business:roamAiContent:list'\][\s\S]*?component: \(\) => import\('@\/views\/business\/roamaicontent\/index'\)[\s\S]*?name: 'roamAiContent'/
  )
})

test('菜单注册后 activeMenu 指向正确的 sys_menu fullpath,与侧栏高亮匹配', () => {
  // sys_menu 已注册:roam(M) → ai-content(C),fullpath = /roam/ai-content。
  // activeMenu 必须逐字等于该 fullpath,否则侧栏永不高亮。
  const routeBlock = /path: '\/roam\/ai-content'[\s\S]*?name: 'roamAiContent'[\s\S]{0,200}?\}/.exec(routerSource)
  assert.ok(routeBlock, '解析不到该路由块不算通过')
  assert.match(routeBlock[0], /activeMenu: '\/roam\/ai-content'/)
})

test('前端六个权限串与 Java Controller 的 @PreAuthorize 严格一一对应', () => {
  const controllerPerms = [...controllerSource.matchAll(/hasPermi\('(business:roamAiContent:\w+)'\)/g)]
    .map(match => match[1])
    .sort()
  assert.deepEqual(controllerPerms, [
    'business:roamAiContent:add',
    'business:roamAiContent:audit',
    'business:roamAiContent:edit',
    'business:roamAiContent:list',
    'business:roamAiContent:query',
    'business:roamAiContent:remove'
  ])

  const uiPerms = new Set(
    [...(source + routerSource).matchAll(/'(business:roamAiContent:\w+)'/g)].map(match => match[1])
  )
  assert.deepEqual([...uiPerms].sort(), controllerPerms, '前端出现的权限串必须与后端集合完全一致,不多不少')

  // 每个 perm 都要真的挂在一个入口上,不能只是出现在注释里。
  for (const perm of ['add', 'query', 'edit', 'audit', 'remove']) {
    assert.match(
      source,
      new RegExp(`v-hasPermi="\\['business:roamAiContent:${perm}'\\]"`),
      `${perm} 必须由 v-hasPermi 真实守住一个动作入口`
    )
  }
})

test('API 各方法打到后端真实存在的 URL 与 HTTP 方法上', () => {
  assert.match(apiSource, /url: '\/business\/roamAiContent\/list',\s*method: 'get'/)
  assert.match(apiSource, /url: '\/business\/roamAiContent\/' \+ id,\s*method: 'get'/)
  assert.match(apiSource, /url: '\/business\/roamAiContent',\s*method: 'post'/)
  assert.match(apiSource, /url: '\/business\/roamAiContent',\s*method: 'put'/)
  assert.match(apiSource, /url: '\/business\/roamAiContent\/audit\/' \+ id \+ '\/' \+ status,\s*method: 'put'/)
  assert.match(apiSource, /url: '\/business\/roamAiContent\/' \+ id,\s*method: 'delete'/)
})

test('无分页接口不被伪装成分页列表,读的是裸 response.data', async () => {
  assert.doesNotMatch(source, /<pagination/)

  let received
  const component = loadPage(stubDependencies({
    listRoamAiContent: params => {
      received = params
      return Promise.resolve({ data: [{ id: 1, contentType: 'ROUTE_STORY', auditStatus: 0 }] })
    }
  }))
  const vm = Object.assign(component.data(), { parseTime: () => '2026-07-26 09:00' })
  vm.requestParams = component.methods.requestParams.bind(vm)

  await component.methods.getList.call(vm)

  assert.deepEqual(received, {}, '空筛选不应发出任何多余参数')
  assert.deepEqual(vm.list, [{ id: 1, contentType: 'ROUTE_STORY', auditStatus: 0 }])
  assert.equal(vm.loading, false)
})

test('筛选草稿与已应用条件分离,晚到的旧响应不覆盖最新结果', async () => {
  const pending = []
  const component = loadPage(stubDependencies({
    listRoamAiContent: params => new Promise(resolve => pending.push({ params, resolve }))
  }))
  const vm = Object.assign(component.data(), { parseTime: () => '2026-07-26 09:00' })
  vm.getList = component.methods.getList.bind(vm)
  vm.requestParams = component.methods.requestParams.bind(vm)
  vm.contentTypeText = component.methods.contentTypeText.bind(vm)
  vm.auditInfo = component.methods.auditInfo.bind(vm)

  vm.queryForm.contentType = 'POI_MEANING'
  vm.queryForm.auditStatus = 0
  const first = component.methods.handleQuery.call(vm)
  assert.deepEqual(pending[0].params, { contentType: 'POI_MEANING', auditStatus: 0 })
  assert.deepEqual(component.computed.activeFilters.call(vm), [
    { key: 'contentType', label: '内容类型', value: '地点意义' },
    { key: 'auditStatus', label: '审核状态', value: '待审' }
  ])

  vm.queryForm.contentType = 'ROUTE_STORY'
  // 草稿改了但没提交,已应用筛选不动。
  assert.equal(component.computed.activeFilters.call(vm)[0].value, '地点意义')
  const second = component.methods.handleQuery.call(vm)
  assert.deepEqual(pending[1].params, { contentType: 'ROUTE_STORY', auditStatus: 0 })

  pending[0].resolve({ data: [{ id: 1 }] })
  await first
  assert.deepEqual(vm.list, [], '晚到的首个响应必须被丢弃')

  pending[1].resolve({ data: [{ id: 2 }] })
  await second
  assert.deepEqual(vm.list, [{ id: 2 }])
})

test('非法 poiId 与未知类型不会被塞进查询参数', () => {
  const component = loadPage(stubDependencies({}))
  const vm = Object.assign(component.data(), {})
  vm.getList = () => Promise.resolve()
  vm.requestParams = component.methods.requestParams.bind(vm)

  vm.queryForm.poiId = '0abc'
  vm.queryForm.contentType = 'ROUTE_STORY_TYPO'
  vm.queryForm.auditStatus = 9
  component.methods.handleQuery.call(vm)

  assert.deepEqual(vm.requestParams(), {})
})

test('编辑请求体只带发生变化的 contentText/enabled,绝不借编辑入口改写审核态、类型、key 或 POI', () => {
  const component = loadPage(stubDependencies({}))
  const vm = Object.assign(component.data(), {})
  vm.hasValue = component.methods.hasValue.bind(vm)

  component.methods.openEdit.call(vm, {
    id: 42,
    contentType: 'POI_MEANING',
    contentKey: 'poi-7',
    poiId: 7,
    contentText: '这里曾是老码头',
    enabled: 1,
    auditStatus: 1
  })

  const unchangedPayload = component.methods.editPayload.call(vm)
  assert.deepEqual(Object.keys(unchangedPayload).sort(), ['enabled', 'id'])
  assert.equal('contentText' in unchangedPayload, false, '只改启用状态不得重复提交原文案并触发重审')

  vm.form.contentText = '这里曾是新码头'
  const payload = component.methods.editPayload.call(vm)
  assert.deepEqual(Object.keys(payload).sort(), ['contentText', 'enabled', 'id'])
  assert.equal(payload.id, 42)
  assert.equal(payload.contentText, '这里曾是新码头')
  assert.equal(payload.enabled, 1)
  for (const forbidden of ['auditStatus', 'contentType', 'contentKey', 'poiId']) {
    assert.equal(forbidden in payload, false, `编辑请求体不得包含 ${forbidden}`)
  }

  // 即便有人在表单态上硬塞这些字段,payload 仍不得把它们带出去。
  Object.assign(vm.form, { auditStatus: 1, contentType: 'ROUTE_STORY', contentKey: '篡改', poiId: 99 })
  assert.deepEqual(Object.keys(component.methods.editPayload.call(vm)).sort(), ['contentText', 'enabled', 'id'])
})

test('审核态只经 audit 接口写入,编辑接口从不承担改状态', async () => {
  const calls = []
  const component = loadPage(stubDependencies({
    auditRoamAiContent: (id, status) => { calls.push(`audit:${id}:${status}`); return Promise.resolve() },
    updateRoamAiContent: payload => { calls.push(`update:${JSON.stringify(payload)}`); return Promise.resolve() }
  }))
  const vm = Object.assign(component.data(), {
    $modal: { confirm: () => Promise.resolve(), msgSuccess: () => {}, msgError: () => { throw new Error('不应进入失败态') } },
    getList: () => Promise.resolve()
  })
  vm.canAudit = component.methods.canAudit.bind(vm)
  vm.changeAudit = component.methods.changeAudit.bind(vm)

  await component.methods.onPass.call(vm, { id: 5, auditStatus: 0 })
  await component.methods.onReject.call(vm, { id: 6, auditStatus: 1 })
  // 已是目标态 / 状态未知的条目不提供动作。
  await component.methods.onPass.call(vm, { id: 7, auditStatus: 1 })
  await component.methods.onPass.call(vm, { id: 8, auditStatus: null })
  await component.methods.onPass.call(vm, { id: 9, auditStatus: 9 })

  assert.deepEqual(calls, ['audit:5:1', 'audit:6:2'])
  assert.equal(component.methods.auditInfo(null).key, 'unknown')
  assert.equal(component.methods.auditInfo(9).key, 'unknown')
  assert.equal(component.methods.canAudit.call(vm, { auditStatus: null }, 1), false)
})

test('新增表单只挡住前端可确定的组合,保留后端唯一业务规则且不发明字段', () => {
  const component = loadPage(stubDependencies({}))
  const vm = Object.assign(component.data(), {})
  vm.validateForm = component.methods.validateForm.bind(vm)
  vm.createPayload = component.methods.createPayload.bind(vm)
  vm.dialogMode = 'create'

  // 未选类型
  Object.assign(vm.form, { contentType: null, contentText: '一段文案', enabled: 1 })
  assert.match(vm.validateForm(), /内容类型/)

  // 路线指标白名单属于后端唯一真源,前端不复制一份会漂移的规则。
  Object.assign(vm.form, { contentType: 'ROUTE_STORY', contentKey: 'story-1', contentText: '你走了 {{steps}} 步' })
  assert.equal(vm.validateForm(), null)

  // 路线故事缺 key
  Object.assign(vm.form, { contentText: '走过 {{tiles}} 格、{{pois}} 个据点', contentKey: '  ' })
  assert.match(vm.validateForm(), /key/)

  // 合法路线故事:请求体不带 poiId
  vm.form.contentKey = 'story-1'
  assert.equal(vm.validateForm(), null)
  const storyPayload = vm.createPayload()
  assert.deepEqual(Object.keys(storyPayload).sort(), ['contentKey', 'contentText', 'contentType', 'enabled'])
  assert.equal('poiId' in storyPayload, false, '路线故事不得绑定 POI')

  // 超长文案
  Object.assign(vm.form, { contentText: '文'.repeat(81) })
  assert.match(vm.validateForm(), /1-80 字/)

  // 地点意义必须带有效 poiId,且 key 交给后端生成
  Object.assign(vm.form, { contentType: 'POI_MEANING', contentText: '这里曾是老码头', poiId: '0' })
  assert.match(vm.validateForm(), /POI/)
  vm.form.poiId = '7'
  assert.equal(vm.validateForm(), null)
  const meaningPayload = vm.createPayload()
  assert.deepEqual(Object.keys(meaningPayload).sort(), ['contentText', 'contentType', 'enabled', 'poiId'])
  assert.equal(meaningPayload.poiId, 7)
  assert.equal('contentKey' in meaningPayload, false, 'contentKey 由后端按 poi-<id> 生成,前端不抢')
})

test('详情走 query 接口回读,失败时不用列表旧值伪装成详情', async () => {
  let requestedId
  const component = loadPage(stubDependencies({
    getRoamAiContent: id => { requestedId = id; return Promise.reject(new Error('403')) }
  }))
  const vm = Object.assign(component.data(), {})

  await component.methods.openDetail.call(vm, { id: 11 })

  assert.equal(requestedId, 11)
  assert.equal(vm.detail, null)
  assert.equal(vm.detailVisible, true)
  assert.equal(vm.detailLoading, false)
})

test('详情晚到响应不得覆盖后来打开的对象', async () => {
  const pending = []
  const component = loadPage(stubDependencies({
    getRoamAiContent: id => new Promise(resolve => pending.push({ id, resolve }))
  }))
  const vm = Object.assign(component.data(), {})

  const first = component.methods.openDetail.call(vm, { id: 1 })
  const second = component.methods.openDetail.call(vm, { id: 2 })
  pending[0].resolve({ data: { id: 1 } })
  await first
  assert.equal(vm.detail, null, '较早详情不得覆盖当前对象')

  pending[1].resolve({ data: { id: 2 } })
  await second
  assert.deepEqual(vm.detail, { id: 2 })
  assert.equal(vm.detailLoading, false)
})

test('删除必须先确认,取消即不发请求', async () => {
  const calls = []
  const component = loadPage(stubDependencies({
    delRoamAiContent: id => { calls.push(id); return Promise.resolve() }
  }))
  const vm = Object.assign(component.data(), {
    $modal: { confirm: () => Promise.reject(new Error('cancel')), msgSuccess: () => {}, msgError: () => {} },
    getList: () => Promise.resolve()
  })

  await component.methods.onRemove.call(vm, { id: 3 })
  assert.deepEqual(calls, [], '用户取消时不得发删除请求')

  vm.$modal.confirm = () => Promise.resolve()
  vm.pendingActionId = null
  await component.methods.onRemove.call(vm, { id: 3 })
  assert.deepEqual(calls, [3])
})

test('列表读取失败时不保留旧结果冒充当前状态', async () => {
  const component = loadPage(stubDependencies({
    listRoamAiContent: () => Promise.reject(new Error('500'))
  }))
  const vm = Object.assign(component.data(), { parseTime: () => '2026-07-26 09:00' })
  vm.requestParams = component.methods.requestParams.bind(vm)
  vm.list = [{ id: 1 }]

  await component.methods.getList.call(vm)

  assert.deepEqual(vm.list, [])
  assert.equal(vm.listError, true)
  assert.equal(vm.updatedAt, '')
  assert.ok(source.includes("import CyResourceState from '@/components/CyResourceState'"))
})

// ======================= 源码负控:migration guard 必须存在,删掉即红 =======================
// 以下断言读 migration SQL 源码,不连数据库。如果 guard 被删除/改弱,测试直接红。

test('@menu 解析必须严格限定 parent_id/menu_type/path/component + @menu_unique,不能仅按 perms LIMIT 1', () => {
  const menuBlock = migrationSource.match(/SET @menu := \(SELECT menu_id FROM sys_menu[\s\S]*?LIMIT 1\);/)
  assert.ok(menuBlock, '解析不到 @menu SET 语句')
  // 六项缺一不可:限定哪个 C 菜单,不靠碰巧
  assert.match(menuBlock[0], /parent_id\s*=\s*@parent/, '必须限定 parent_id=@parent —— 否则同 perms 错误父级 C 会被错选')
  assert.match(menuBlock[0], /menu_type\s*=\s*'C'/, '必须限定 menu_type=C —— 否则 F 行类型会被错选')
  assert.match(menuBlock[0], /path\s*=\s*'ai-content'/, '必须限定 path=ai-content')
  assert.match(menuBlock[0], /component\s*=\s*'business\/roamaicontent\/index'/, '必须限定 component')
  // 父级 guard + C 唯一性 guard
  assert.match(menuBlock[0], /@parent IS NOT NULL/, '必须带 @parent IS NOT NULL guard')
  assert.match(menuBlock[0], /@parent_unique\s*=\s*1/, '必须带 @parent_unique=1 guard')
  assert.match(menuBlock[0], /@menu_unique\s*=\s*1/, '必须带 @menu_unique=1 guard(删掉则重复 C 被 LIMIT 1 任选)')
})

test('五个 F INSERT 的 WHERE 必须显式带父级 guard + @menu guard,不能仅靠 @menu 隐式', () => {
  const fInserts = migrationSource.match(/INSERT INTO sys_menu[\s\S]*?NOT EXISTS[\s\S]*?\);/g)
  assert.ok(fInserts, '解析不到 F INSERT 语句')
  // 只统计 F 类型 INSERT(SELECT 值含 'F')
  const fStmts = fInserts.filter(s => s.includes("'F'"))
  assert.equal(fStmts.length, 5, '必须有恰好 5 条 F INSERT(匹配 6 条 perms 中去掉 list)')

  for (let i = 0; i < fStmts.length; i++) {
    const wherePart = fStmts[i].match(/WHERE[\s\S]*?\);/)
    assert.ok(wherePart, `F#${i + 1} 找不到 WHERE 子句`)
    assert.match(wherePart[0], /@parent IS NOT NULL/, `F#${i + 1} WHERE 必须显式带 @parent IS NOT NULL(删掉即红)`)
    assert.match(wherePart[0], /@parent_unique\s*=\s*1/, `F#${i + 1} WHERE 必须显式带 @parent_unique=1(删掉即红)`)
    assert.match(wherePart[0], /@menu_unique\s*=\s*1/, `F#${i + 1} WHERE 必须显式带 @menu_unique=1(删掉即红)`)
    assert.match(wherePart[0], /@menu IS NOT NULL/, `F#${i + 1} WHERE 必须显式带 @menu IS NOT NULL`)
  }
})

test('负控 NC-0~NC-4 必须全部存在于 migration SQL 中,每项含 verdict 判决列', () => {
  const ncLabels = migrationSource.match(/NC-\d[-a-z_]+/g)
  assert.ok(ncLabels, '找不到任何 NC-* 负控标号')
  const expected = ['NC-0-roam_parent_unique', 'NC-1-c_parent_is_roam', 'NC-2-f_parent_is_c', 'NC-3-same_perms_wrong_parent', 'NC-4-c_menu_unique']
  for (const label of expected) {
    assert.ok(migrationSource.includes(label), `负控 ${label} 缺失 —— 删掉即无验证`)
  }
  // 每个负控要么 PASS/SKIP,要么 FAIL —— 有判决列才过得去
  const verdictCount = (migrationSource.match(/\bverdict\b/g) || []).length
  assert.ok(verdictCount >= 5, `verdict 判决列至少 5 处(当前 ${verdictCount}),判决列不足则负控形同虚设`)
})

test('roam 父级缺失场景:guard 存在时 @menu 和 F INSERT 都会零写入', () => {
  // 场景:若 @parent_unique=0(roam M 缺失),@parent=NULL,则:
  //   · C INSERT WHERE 带 @parent IS NOT NULL AND @parent_unique=1 → 0 行
  //   · @menu_unique 带 @parent IS NOT NULL AND @parent_unique=1 → 0(因为 @parent=NULL)
  //   · @menu SELECT 带 @parent IS NOT NULL AND @parent_unique=1 → NULL
  //   · 5 条 F INSERT 各带四层 guard → 0 行
  assert.ok(migrationSource.includes(
    "WHERE @parent IS NOT NULL AND @parent_unique = 1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:roamAiContent:list')"
  ), 'C INSERT WHERE 必须带 @parent IS NOT NULL AND @parent_unique=1(删掉则父级缺失时零写入不保证)')
})

test('C 菜单重复场景:同一 parent 下两行同 perms C 时,@menu_unique>1 ⇒ @menu=NULL ⇒ F 零写入 + 首跑原子性', () => {
  // 场景:若 @menu_unique>1(同 parent 下完全相同的 C 重复),则:
  //   · @menu SELECT 带 @menu_unique=1 ⇒ NULL
  //   · 5 条 F INSERT 各带 @menu_unique=1 guard ⇒ 0 行
  //   · NC-4 会报 FAIL_duplicate
  // ★首跑原子性:@menu_unique 必须在 C INSERT**之后**重算,否则首跑 @menu_unique=0
  //   会错误挡掉 @menu 解析 ⇒ 5 F 全被挡 ⇒ 只落 1 C,违背整份原子。
  assert.ok(migrationSource.includes('@menu_unique := (SELECT COUNT(*) FROM sys_menu'),
    '必须存在 @menu_unique COUNT(*) 定义(删掉则重复 C 无法 fail-closed)')
  assert.match(migrationSource, /@menu_unique\s*=\s*1/,
    '@menu 解析和 F INSERT 必须带 @menu_unique=1 guard(删掉则重复 C 被 LIMIT 1 任选)')

  // ★位置断言:@menu_unique SET 必须在 C INSERT 之后、@menu SET 之前。
  //   用字符串偏移量验证:如果重算跑到 C INSERT 前面,首跑 stale=0 会挡死。
  const cInsertPos = migrationSource.indexOf("WHERE @parent IS NOT NULL AND @parent_unique = 1 AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:roamAiContent:list')")
  const menuUniquePos = migrationSource.indexOf('@menu_unique := (SELECT COUNT(*) FROM sys_menu')
  const menuSetPos = migrationSource.indexOf('SET @menu := (SELECT menu_id FROM sys_menu')
  assert.ok(cInsertPos > 0, '找不到 C INSERT WHERE 行')
  assert.ok(menuUniquePos > 0, '找不到 @menu_unique SET')
  assert.ok(menuSetPos > 0, '找不到 @menu SET')
  assert.ok(cInsertPos < menuUniquePos, '@menu_unique 重算必须在 C INSERT 之后(位置错=首跑 stale=0)')
  assert.ok(menuUniquePos < menuSetPos, '@menu_unique 重算必须在 @menu 解析之前(位置错=读到旧值)')

  // 确认 NC-4 判决列为 FAIL_duplicate
  assert.ok(migrationSource.includes('FAIL_duplicate'),
    'NC-4 必须含 FAIL_duplicate 判决')
})
