const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const VueRouter = require('vue-router')

const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/officialevent/index.vue'), 'utf8')
const routerSource = fs.readFileSync(path.resolve(__dirname, '../../src/router/index.js'), 'utf8')

// [P2-4 Q14=B] 本用例原先把 /supply/official-event 【本身】断言为 canonical 入口
// (component 路由 + name 'supplyOfficialEvent')。P2-4 方案 §6.3 推翻了该结论:
// 菜单 2192(path='officialevent',父 2209 path='supply')生成的 /supply/officialevent
// 才是 canonical,带连字符的那条是拼写分叉,应降级为同权限 redirect。用户已裁决 = B。
//
// ★断言写法约束(2026-07-26 踩过一次):不许只 assert.match 源文件里出现过某串字符 ——
//   一句解释性注释就能把它变绿。下面全部落在【真实行为】上:把 router 源码里那条
//   redirect 函数抽出来真的执行,并用 vue-router 真的解析一次路径。
function loadOfficialEventRedirect () {
  const entry = routerSource.match(/\{\s*(?:\/\/[^\n]*\n\s*)*path: '\/supply\/official-event',[\s\S]*?\n  \}/)
  assert.ok(entry, '/supply/official-event 路由条目没找到')
  const block = entry[0]
  const fn = block.match(/redirect: (to => \([\s\S]*?\))\s*\n/)
  assert.ok(fn, '/supply/official-event 必须是 redirect 条目(Q14=B),不再是 component 入口')
  // 求值前先把形状钉死:只允许「to => ({ name: 'X', query: Object.assign({}, to.query) })」这一种。
  // 输入来自本仓自己的源文件(测试期读盘,非外部输入),但仍不给 new Function 任何自由度 ——
  // 形状不符就直接失败,而不是把一段任意代码喂进去执行。
  assert.match(
    fn[1],
    /^to => \(\{ name: '[A-Za-z][\w-]*', query: Object\.assign\(\{\}, to\.query\) \}\)$/,
    'redirect 形状不在白名单内,拒绝求值'
  )
  return { block, redirect: new Function(`return (${fn[1]})`)() }
}

test('/supply/official-event 是受原列表权限保护的 redirect，而不是第二个 canonical 入口', () => {
  const { block, redirect } = loadOfficialEventRedirect()

  // 权限闸必须还在:无权账号不能借 redirect 绕过 filterDynamicRoutes
  assert.match(block, /permissions: \['business:officialevent:list'\]/)
  assert.match(block, /hidden: true/)

  // 行为断言:redirect 真的指向菜单 canonical 的 route name,并原样带走 query
  assert.deepEqual(
    redirect({ query: { status: '1', keyword: '夜游' } }),
    { name: 'Officialevent', query: { status: '1', keyword: '夜游' } }
  )

  // 该条目不得再自带组件,否则又变回两个并列的 canonical 入口
  assert.doesNotMatch(block, /component: \(\) => import\(/)
  assert.doesNotMatch(block, /name: 'supplyOfficialEvent'/)
})

test('旧地址 /supply/official-event 在路由层真的落到 /supply/officialevent 并保留 query', () => {
  const { redirect } = loadOfficialEventRedirect()
  const router = new VueRouter({ mode: 'abstract', routes: [] })
  router.addRoutes([
    // 菜单 2192 生成的动态路由:RuoYi getRouteName = capitalize(route_name || path)
    { path: '/supply/officialevent', name: 'Officialevent' },
    { path: '/supply/official-event', redirect }
  ])

  const resolved = router.match('/supply/official-event?status=1&keyword=%E5%A4%9C%E6%B8%B8')
  assert.equal(resolved.name, 'Officialevent')
  assert.equal(resolved.path, '/supply/officialevent')
  assert.deepEqual(resolved.query, { status: '1', keyword: '夜游' })
})

test('官方活动菜单高亮指向 2192 的真实 fullpath，不再是断链的 /business/officialevent', () => {
  // 旧值 /business/officialevent 是方案 §6.1 那 19 条 activeMenu 断链之一 ——
  // sys_menu 里从来没有过这个路径,点进去侧栏不高亮、面包屑丢失。
  assert.doesNotMatch(routerSource, /activeMenu: '\/business\/officialevent'/)
})

test('官方活动治理页使用 V1/V2 混合视图，包含生命周期状态栏和主办授权入口', () => {
  assert.ok(source.includes('view === \'list\''))
  assert.ok(source.includes('v-hasPermi="[\'business:officialevent:authorize\']"'))
  assert.ok(source.includes('v-hasPermi="[\'business:officialevent:settle\']"'))
  assert.ok(source.includes('class="status-rail"'))
  assert.match(source, /STATUS\s*=\s*\{[\s\S]*0:\s*'草稿'[\s\S]*2:\s*'报名中'[\s\S]*4:\s*'待结算'[\s\S]*9:\s*'已下线'/)
})

test('官方活动结算与通知入口与列表页权限指令对齐', () => {
  assert.equal(
    (source.match(/v-hasPermi="\['business:officialevent:settle'\]"/g) || []).length,
    2,
    '列表结算与驾驶舱重试结算都必须挂 settle 权限'
  )
  assert.match(source, /v-if="activity360\.status === 4"[^>]*v-hasPermi="\['business:officialevent:settle'\]"[^>]*@click="settle\(activity360\)"/)
  assert.match(source, /v-hasPermi="\['business:officialevent:settle'\]"[^>]*@click="settle\(scope\.row\)"/)
  assert.match(source, /v-hasPermi="\['business:officialevent:notify'\]"[^>]*@click="openNotify\(null\)"/)
  assert.match(source, /v-hasPermi="\['business:officialevent:notify'\]"[^>]*@click="openNotify\(scope\.row\)"/)
  assert.match(source, /v-hasPermi="\['business:officialevent:notify'\]"[^>]*@click="submitNotify"/)
  assert.match(source, /hasPermi\('business:officialevent:settle'\)/)
  assert.match(source, /hasPermi\('business:officialevent:notify'\)/)
})

test('V2 操作人身份校验不可绕过——未绑定官方会员时写入操作不可用', () => {
  assert.ok(source.includes('canWriteV2'))
  assert.ok(source.includes(':disabled="!canWriteV2"'))
  assert.ok(source.includes('operatorIdentity'))
  assert.match(source, /v-if="!canWriteV2".*当前后台账号未绑定有效官方会员/)
})

test('路由入口只读投影聚合 V2 合同，V1 历史详情与 V2 合同查看分支明确', () => {
  assert.ok(source.includes('contractVersion'))
  assert.ok(source.includes('createV2'))
  assert.ok(source.includes('isV2'))
  assert.ok(source.includes('V1 历史') || source.includes('历史详情'))
  assert.ok(source.includes('V2 证据') || source.includes('合同查看') || source.includes('V2 DRAFT'))
})

test('官方活动页面不混入 F 域自助模板/主题页面', () => {
  const scriptSection = source.match(/<script>([\s\S]*?)<\/script>/)
  const scriptContent = scriptSection ? scriptSection[1] : ''
  const templateSection = source.replace(/<template>/g, '').replace(/<\/template>/g, '')
  assert.doesNotMatch(templateSection, /publish\/simple|publish\/fabu|templateadd|CmsTopic/i)
  assert.doesNotMatch(scriptContent, /publish\/simple|publish\/fabu|templateadd|CmsTopic/i)
})
