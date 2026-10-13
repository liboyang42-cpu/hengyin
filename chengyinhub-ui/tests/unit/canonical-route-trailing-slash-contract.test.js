/**
 * R9-20 回归: 后台规范入口必须能承受命名路由产生的「末尾斜杠」路径, 且 query 切换时继续加载。
 *
 * 背景: 页面用不带斜杠常量严格比较 $route.path; 而命名路由的空子路径会解析出带斜杠地址
 * (/club/profile/), 初始加载因此被守卫判定为「非规范入口」并跳过, 页面显示假空态。
 * 这里同时做三件事:
 *   1. 用真实 vue-router 证明命名路由确实解析出末尾斜杠 (环境事实);
 *   2. 真实加载俱乐部组件, 用末尾斜杠路径调用守卫, 断言会真正加载列表而不是重定向;
 *   3. 断言所有同族守卫都对路径做了去尾斜杠归一, 不再各自写死严格比较。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

const CANONICAL_ROUTES = [
  { view: 'src/views/business/club/index.vue', canonicalPath: '/club/profile', name: 'clubProfile' },
  { view: 'src/views/business/cashwithdraw/index.vue', canonicalPath: '/finance/withdraw/wechat', name: 'financeWechatWithdraw' },
  { view: 'src/views/business/member/index.vue', canonicalPath: '/customer/member', name: 'customerMember' },
  { view: 'src/views/business/coupon/index.vue', canonicalPath: '/supply/coupon', name: 'supplyCoupon' },
  { view: 'src/views/business/ailog/index.vue', canonicalPath: '/risk/ai-log', name: 'riskAiLog' },
  { view: 'src/views/business/chapteroffer/index.vue', canonicalPath: '/merchant/chapter-offer', name: 'merchantChapterOffer' },
  { view: 'src/views/business/memberwithdrawal/index.vue', canonicalPath: '/finance/withdraw/bank', name: 'financeBankWithdraw' },
  { view: 'src/views/business/im/index.vue', canonicalPath: '/customer/im', name: 'customerIm' },
  { view: 'src/views/business/coopcomplaint/index.vue', canonicalPath: '/customer/case', name: 'customerCase' },
  { view: 'src/views/business/clubrun/index.vue', canonicalPath: '/club/lead-run', name: 'clubLeadRun' },
  { view: 'src/views/business/roampoi/index.vue', canonicalPath: '/club/roam-poi', name: 'clubRoamPoi' }
]

const NORMALIZE = /String\(this\.\$route\.path \|\| ''\)\.replace\(\/\\\/\+\$\/, ''\)/

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function loadScript(relativePath) {
  const source = read(relativePath)
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s+\{([^}]*)\}\s+from\s+['"][^'"]+['"];?\s*$/gm, (match, names) =>
      'const ' + names.split(',').map(name => name.trim()).filter(Boolean).map(name => `${name} = {}`).join(', ') + ';')
    .replace(/^import\s+(\w+)\s+from\s+['"][^'"]+['"];?\s*$/gm, 'const $1 = {};')
  return new Function(script.replace(/export\s+default\s*/, 'return '))()
}

test('R9-20 真实 vue-router: 空子路径命名路由解析出末尾斜杠地址', () => {
  const Vue = require(path.join(uiRoot, 'node_modules/vue'))
  const VueRouter = require(path.join(uiRoot, 'node_modules/vue-router'))
  Vue.use(VueRouter)

  const Dummy = { render: h => h('div') }
  const routes = CANONICAL_ROUTES.map(route => ({
    path: route.canonicalPath,
    component: Dummy,
    children: [{ path: '', name: route.name, component: Dummy }]
  }))
  const router = new VueRouter({ mode: 'abstract', routes })

  for (const route of CANONICAL_ROUTES) {
    const resolved = router.resolve({ name: route.name })
    assert.equal(
      resolved.href,
      route.canonicalPath + '/',
      `${route.name} 的命名路由解析结果应带末尾斜杠 (这是守卫必须容忍的真实路径)`
    )
  }
})

test('R9-20 真实俱乐部组件: 末尾斜杠路径必须加载列表, 不能重定向成假空态', () => {
  const component = loadScript('src/views/business/club/index.vue')

  const redirects = []
  const vm = Object.assign(component.data(), {
    $route: { path: '/club/profile/', query: { pageNum: '2' } },
    $router: { replace: target => redirects.push(target) }
  })
  let listCalls = 0
  vm.getList = () => { listCalls += 1 }

  component.methods.ensureCanonicalRoute.call(vm)

  assert.deepEqual(redirects, [], '带末尾斜杠的规范入口不应再触发重定向')
  assert.equal(listCalls, 1, '带末尾斜杠进入必须真实加载列表')

  // query 切换 (同 path, 换 query) 仍要重新加载
  vm.$route = { path: '/club/profile/', query: { pageNum: '3' } }
  component.methods.ensureCanonicalRoute.call(vm)
  assert.equal(listCalls, 2, 'query 切换必须重新加载')

  // 旧入口仍要转发, 不能被归一误吞
  component.methods.ensureCanonicalRoute.call(Object.assign(component.data(), {
    $route: { path: '/business/club', query: {} },
    $router: { replace: target => redirects.push(target) },
    getList: () => assert.fail('旧入口不应直接读数据')
  }))
  assert.deepEqual(redirects, [{ name: 'clubProfile', query: {} }], '旧入口仍必须重定向到规范入口')
})

test('R9-20 同族 ensureCanonicalRoute 都对路径做去尾斜杠归一', () => {
  for (const route of CANONICAL_ROUTES) {
    const source = read(route.view)
    assert.match(
      source,
      NORMALIZE,
      `${route.view}: 规范入口判断必须对 $route.path 去尾斜杠, 才能容忍命名路由的末尾斜杠`
    )
    assert.doesNotMatch(
      source,
      /\$route\.path\s*[!=]==?\s*['"]/,
      `${route.view}: 不得再对 $route.path 做写死字符串的严格比较`
    )
  }
})
