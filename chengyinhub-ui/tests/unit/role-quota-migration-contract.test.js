const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const legacyPage = fs.readFileSync(path.join(uiRoot, 'src/views/business/userpermission/index.vue'), 'utf8')
const canonicalPage = fs.readFileSync(path.join(uiRoot, 'src/views/business/config/role-quota/index.vue'), 'utf8')
const routerSource = fs.readFileSync(path.join(uiRoot, 'src/router/index.js'), 'utf8')

function loadDynamicRouteFilter(loadView) {
  const source = fs.readFileSync(path.join(uiRoot, 'src/store/modules/permission.js'), 'utf8')
  const body = source.slice(source.indexOf('function filterAsyncRouter('),
    source.indexOf('// 动态路由遍历，验证是否具备权限'))
  return new Function('Layout', 'ParentView', 'InnerLink', 'loadView',
    body + '\nreturn filterAsyncRouter;')({}, {}, {}, loadView)
}

function loadTagsModule() {
  const utilsSource = fs.readFileSync(path.join(uiRoot, 'src/utils/tagsView.js'), 'utf8')
    .replace(/export\s+const\s+/g, 'const ')
  const utils = new Function(utilsSource + '\nreturn { MAX_RECENT_VIEWS, hasEntityId, getViewKey };')()
  const source = fs.readFileSync(path.join(uiRoot, 'src/store/modules/tagsView.js'), 'utf8')
    .replace("import { MAX_RECENT_VIEWS, hasEntityId, getViewKey } from '@/utils/tagsView'",
      'const { MAX_RECENT_VIEWS, hasEntityId, getViewKey } = utils')
    .replace(/export\s+default\s*/, 'return ')
  return new Function('utils', source)(utils)
}

test('动态角色配额旧叶路由在进入组件前跳转，真实路由与标签状态只保留规范页', async () => {
  const Vue = require('vue')
  const VueRouter = require('vue-router')
  const Vuex = require('vuex')
  Vue.use(VueRouter)
  Vue.use(Vuex)
  const loaded = []
  const filter = loadDynamicRouteFilter(view => {
    loaded.push(view)
    return { render: h => h('div') }
  })
  const meta = { title: '角色配额', icon: 'quota', noCache: true }
  const permissions = ['business:userpermission:list']
  const [legacy] = filter([{
    path: '/rules/userpermission', name: 'Userpermission',
    component: 'business/userpermission/index', redirect: 'noRedirect',
    children: [], meta, permissions
  }])
  assert.deepEqual(loaded, [])
  assert.equal(legacy.component, undefined)
  assert.equal(legacy.meta, meta)
  assert.equal(legacy.permissions, permissions)
  assert.equal(legacy.path, '/rules/userpermission')

  const store = new Vuex.Store({ modules: { tagsView: loadTagsModule() } })
  const router = new VueRouter({ mode: 'abstract', routes: [
    { path: '/config/role-quota', name: 'roleQuotaConfig',
      component: { render: h => h('div') }, meta: { title: '角色配额' } },
    legacy
  ] })
  router.afterEach(to => store.dispatch('tagsView/addView', to))
  await router.push({ path: '/rules/userpermission', query: { probe: 'kept', id: '7' } })
  assert.equal(router.currentRoute.path, '/config/role-quota')
  assert.deepEqual(router.currentRoute.query, { probe: 'kept', id: '7' })
  assert.deepEqual(store.state.tagsView.visitedViews.map(v => v.path), ['/config/role-quota'])
  assert.deepEqual(store.state.tagsView.visitedViews.map(v => v.tagKey), ['/config/role-quota?id=7'])
})

test('动态解析只处理精确旧叶入口，普通叶加载和redirect清理及非叶分支保持原样', () => {
  const loaded = []
  const filter = loadDynamicRouteFilter(view => {
    loaded.push(view)
    return { sourceView: view }
  })
  const routes = filter([
    { path: '/ordinary', component: 'business/topic/index', redirect: 'noRedirect', children: [] },
    { path: '/quota-parent', component: 'business/userpermission/index',
      children: [{ path: 'child', component: 'business/topic/index' }] }
  ])
  assert.deepEqual(loaded, ['business/topic/index', 'business/userpermission/index', 'business/topic/index'])
  assert.equal(routes[0].component.sourceView, 'business/topic/index')
  assert.equal(routes[0].redirect, undefined)
  assert.equal(routes[0].children, undefined)
  assert.equal(routes[1].component.sourceView, 'business/userpermission/index')
  assert.equal(routes[1].redirect, undefined)
  assert.equal(routes[1].children[0].component.sourceView, 'business/topic/index')
})

test('旧角色配额入口只兼容跳到规范配置页', () => {
  assert.match(legacyPage, /path:\s*'\/config\/role-quota'/)
  assert.match(legacyPage, /query:\s*this\.\$route\.query/)
  assert.doesNotMatch(legacyPage, /listPermission|updatePermission/)
})

test('规范角色配额路由保持原读取权限并注册在配置域', () => {
  assert.match(routerSource, /path:\s*'\/config\/role-quota'/)
  assert.match(routerSource, /business:userpermission:list/)
  assert.match(routerSource, /roleQuotaConfig/)
})

test('角色配额页只使用既有角色级读写合同，并保留失败态', () => {
  assert.match(canonicalPage, /import \{ listPermission, updatePermission \} from '@\/api\/business\/userpermission'/)
  assert.match(canonicalPage, /<cy-resource-state/)
  assert.match(canonicalPage, /loadError/)
  assert.match(canonicalPage, /updatePermission\(this\.toUpdatePayload\(this\.editForm\)\)/)
  assert.match(canonicalPage, /v-hasPermi="\['business:userpermission:edit'\]"/)
  assert.doesNotMatch(canonicalPage, /updatePermission\(row\)/)
})

test('页面明确角色级配置边界，不伪造当前使用量', () => {
  assert.match(canonicalPage, /当前接口只返回角色级配置，不包含当前使用量/)
  assert.match(canonicalPage, /toUpdatePayload\(form\)/)
  assert.match(canonicalPage, /id:\s*form\.id/)
  assert.match(canonicalPage, /maxThemes:\s*form\.maxThemes/)
  assert.match(canonicalPage, /canPublishCoupon:\s*form\.canPublishCoupon/)
  assert.doesNotMatch(canonicalPage, /role:\s*form\.role/)
})

test('列表归一化保留组件上下文，空配额不会被改写成零', () => {
  assert.match(canonicalPage, /\.map\(row => this\.normalizeRow\(row\)\)/)
  assert.match(canonicalPage, /quotaFormValue\(value\)/)
  assert.match(canonicalPage, /value == null \|\| value === '' \? null : Number\(value\)/)
  assert.doesNotMatch(canonicalPage, /Number\(row\.maxThemes \|\| 0\)/)
  assert.doesNotMatch(canonicalPage, /Number\(row\.maxEvents \|\| 0\)/)
  assert.doesNotMatch(canonicalPage, /Number\(row\.maxCoupons \|\| 0\)/)
})

test('空配额按服务端语义明确展示为不限', () => {
  assert.match(canonicalPage, /return value == null \? '不限' : `\$\{value\} 个`/)
  assert.doesNotMatch(canonicalPage, /return value == null \? '未配置'/)
})
