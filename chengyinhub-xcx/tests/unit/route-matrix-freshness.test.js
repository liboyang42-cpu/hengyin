'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')

const matrix = require('../../scripts/uiaudit/route-matrix.json')
const {
  build,
  inventoryMatchesCurrent,
  classify,
  usesLoginPageFactory,
  materialize,
} = require('../../scripts/uiaudit/build-route-matrix')

test('route-matrix 生成物必须与当前 app.json 和 scene 扫描逐字一致', () => {
  const current = build()
  // 135 → 134:pages/club/game-director 整页删除、路由注销(十张卡收编进活动详情页)
  // 134 → 129:2026-09-06 孤儿页清理,stopwatch/play、decor/ai-npc、merchant/discover、
  //   club/dissolution-blockers、publish/biaoqian 五页注销。
  /* 2026-09-08 +1:新增 pages/coop/invite-detail(Figma 02d-1~5 协作详情整页)。 */
  /* 2026-09-08 +1:新增 pages/club/checkin-detail(Figma K3 俱乐部端核销详情)。 */
  /* 2026-09-09 −3:会费两页(club/membership/setting、club/membership/status)与
  // 129 → 130:2026-09-05 新增 subpackageRoam/nearby(漫游·附近的局)
  // 130 → 131:2026-09-09 新增 subpackageRoam/citystamp(投一张换一张)。
  //   放在分包而不是主包:信箱那张实物图 37KB,主包资源棘轮只准降。
  /* 2026-09-11 并 github/master:本分支 +2 页(nearby / citystamp),master +2 页(coop/invite-detail、
     club/checkin-detail)−3 页(会费两页与探店日质量证据整页删除)⇒ 129+2+2−3 = 130。
  2026-09-12 rebase github/master(#1062 玩法壳)后 131；本 PR 退役 subpackageTalent/search ⇒ 131−1 = 130。 */
  /* 2026-09-16 −1:孤儿页 pages/coop/candidates 整页删除 ⇒ 130−1 = 129(按脚本重建实测)。 */
  /* 2026-09-17 −1:B-06 孤儿页 pages/topic/pricing/partner 整页删除 ⇒ 129−1 = 128(按脚本重建实测)。 */
  /* 2026-09-19 +1:批复1-a=2 用户裁决 partner 页复活并接进定价页「合作阵容」⇒ 128+1 = 129(按脚本重建实测)。 */
  /* 2026-09-20 +1:预制人生独立游戏分包页,票夹直达。 */
  /* 2026-09-22 +1:Phase 0 物品卡实验室页 subpackageP3/pages/object-card-lab(立体藏品卡闸口页)。 */
  /* 2026-09-22 +1:Phase 3 藏品册页 subpackageP3/pages/object-cards/index(玩家侧卡片墙,个人页入口)。 */
  /* 2026-09-24 −1:Phase 0 实验室页随正式页接进玩法删除 ⇒ 132−1 = 131(按脚本重建实测)。 */
  /* 2026-10-14 +6:漫游上线(我的队伍、subpackageTools 5 个工具页)⇒ 130+6 = 136(按脚本重建实测)。 */
  /* 2026-10-19 Claude 小程序线移植到 master(用户批准冻结数值更新) +9:俱乐部勋章、核销记录、触达记录、主办控制台、积分明细、我的举报、举报进度、我的纠错、纠错进度 ⇒ 136+9 = 145(按脚本重建实测)。 */
  /* 2026-10-20 合并主干官方线路两页，与本轮九页并集，145+2=147。 */
  // 2026-10-20 用户要求删掉「我的纠错 / 纠错进度」两页、消息页改收件箱/发件箱(合并 master 584470ad 后按重建实测) ⇒ 145。
  // 2026-10-23 起名页 templateadd 退役删除 ⇒ 145 → 144。
  // 2026-10-25 +1 官方地点挑战页 pages/play/poi-challenge ⇒ 144 → 145。
  // 2026-10-27 L02 P-55:+1 营销消息与优惠券独立页 pages/shezhi/marketing/index ⇒ 145 → 146。
  // 2026-10-28 G-9:+1 账户收益 · 分析页 subpackageA/pages/assetcenter/analysis/index ⇒ 146 → 147。
  // 2026-10-26 +3:角色卡、定向计分、本人发票独立页 ⇒ 145 → 148。
  // W8-12: 已校验W8-09权益页加入现有分包，148 → 149。
  // 2026-10-28 文档二 L9 集成:登记 L7 我的申诉 + 发票拆出的 4 页，149 → 154。
  // 145 → 146:2026-10-28 新增 pages/templatedetail/topic-preview(主题模板卡堆预览，业主定稿原型 v21)。
  // 2026-10-28 L9 再并 master:#652 topic-preview +1 叠加本线 154 ⇒ 155。
  // 2026-10-28 并 master(#658 后)合流 integrate/devfix-20261027:master 155 叠加本线 marketing / analysis 两页 ⇒ 157(build-route-matrix.js --json 重建)。
  // 2026-10-29 R2 l2-roam:「动态」二级页 pages/square/list 删除(业主:帖文流只剩发现 / 俱乐部详情 / 我的),157 → 156(build-route-matrix.js --json 重建)。
  assert.equal(current.routes.length, 156)
  assert.equal(matrix.counts.routes, 156)
  const bundle = matrix.routes.find(route => route.pagePath === 'subpackageMember/free-explore/index')
  assert.equal(bundle.role, 'player')
  assert.equal(bundle.needsLogin, true)
  assert.ok(bundle.dataKeys.includes('registrationId'))
  // 2026-09-02:+2 俱乐部客户列表与客户详情(club/customers、club/customer-detail)
  assert.equal(inventoryMatchesCurrent(matrix), true)
  // 2026-09-04 导演台整页收编:锚点换成新宿主页 topic-detail。这条要证的是
  //   「俱乐部页必须判 club 角色 + 需登录 + 认得出路由参数」,意图一字未改;
  //   activityId 仍在 dataKeys 里 —— 从场次进来才带,正是导演台那条路。
  const club = matrix.routes.find((route) => route.pagePath === 'pages/club/topic-detail/index')
  const merchant = matrix.routes.find((route) => route.pagePath === 'pages/merchant/game-node/index')
  assert.deepEqual({ role: club.role, needsLogin: club.needsLogin, dataKeys: club.dataKeys }, {
    role: 'club', needsLogin: true, dataKeys: ['topicId', 'clubId', 'activityId'],
  })
  assert.deepEqual({ role: merchant.role, needsLogin: merchant.needsLogin, dataKeys: merchant.dataKeys }, {
    role: 'merchant', needsLogin: true, dataKeys: ['activityId', 'nodeId'],
  })
})

test('route-matrix 少一页或角色被改写必须判为过期', () => {
  const missingRoute = JSON.parse(JSON.stringify(matrix))
  missingRoute.routes.pop()
  missingRoute.counts.routes -= 1
  assert.equal(inventoryMatchesCurrent(missingRoute), false)

  const wrongRole = JSON.parse(JSON.stringify(matrix))
  wrongRole.routes.find((route) => route.pagePath === 'pages/club/checkin-detail/index').role = 'player'
  assert.equal(inventoryMatchesCurrent(wrongRole), false)
})

test('已有identity page factory的五个工具路由都必须标记需登录，公开页仍公开', () => {
  const current = build()
  const tools = ['capsule', 'monthly-recap', 'exploration-goals', 'anniversary', 'exploration-export']
  for (const name of tools) {
    const pagePath = 'subpackageTools/' + name + '/index'
    assert.equal(current.routes.find(route => route.pagePath === pagePath).needsLogin, true, pagePath)
    assert.equal(matrix.routes.find(route => route.pagePath === pagePath).needsLogin, true, pagePath)
  }
  assert.equal(classify({ pagePath: 'pages/crop/index' }).needsLogin, false)
  assert.equal(classify({ pagePath: 'pages/agreement/index' }).needsLogin, false)
  assert.deepEqual(materialize(build()), materialize(build()), '生成器重复运行必须一致')
  const wrongLogin = JSON.parse(JSON.stringify(matrix))
  wrongLogin.routes.find(route => route.pagePath === 'subpackageTools/monthly-recap/index').needsLogin = false
  assert.equal(inventoryMatchesCurrent(wrongLogin), false)
})

test('只认实际用于Page的已核实factory依赖，允许别名和等价相对路径', () => {
  const path = require('node:path')
  const { ROOT } = require('../../scripts/lib/xcx-scan')
  const file = path.join(ROOT, 'subpackageTools/example/index.js')
  for (const source of [
    "const { page } = require('../cloud-ten-runtime'); Page(page({}));",
    "const { page: make$page } = require('../cloud-ten-runtime'); Page(make$page({}));",
    "const { page: $page } = require('../cloud-ten-runtime'); Page($page({}));",
    "const { page: makePage } = require('../cloud-ten-runtime.js'); Page(makePage({}));",
    "const { runtime } = require('../lib/runtime'); Page(runtime('goals', {}));",
    "const { runtime: make$runtime } = require('../lib/runtime'); Page(make$runtime('goals', {}));",
    "const { runtime: $runtime } = require('../lib/runtime'); Page($runtime('goals', {}));",
    "const { runtime: makePage } = require('../lib/../lib/runtime.js'); Page(makePage('goals', {}));",
  ]) assert.equal(usesLoginPageFactory(source, file), true, source)
})

test('合法guest page/factory、未使用导入、同名其它模块及注释字符串不能误判登录', () => {
  const path = require('node:path')
  const { ROOT } = require('../../scripts/lib/xcx-scan')
  const file = path.join(ROOT, 'subpackageTools/example/index.js')
  for (const source of [
    "const { page: $page } = require('../guest-runtime'); Page($page({}));",
    "const { runtime: make$runtime } = require('../lib/guest-runtime'); Page(make$runtime('public', {}));",
    "const { storage: $page } = require('../cloud-ten-runtime'); Page($page({}));",
    "const { page: $page } = require('../cloud-ten-runtime'); Page({});",
    'Page({ data: { title: "公开说明" } });',
    "const { page } = require('../guest-runtime'); Page(page({}));",
    "const { runtime } = require('../lib/guest-runtime'); Page(runtime('public', {}));",
    "const { page } = require('../cloud-ten-runtime'); Page({});",
    "const { storage } = require('../cloud-ten-runtime'); Page(storage({}));",
    "const { page } = require('../lib/runtime'); Page(page({}));",
    "// const { page } = require('../cloud-ten-runtime');\nPage(page({}));",
    "const sample = \"const { page } = require('../cloud-ten-runtime');\"; Page(page({}));",
    "const { page } = require('../cloud-ten-runtime'); /* Page(page({})); */ Page({});",
    "const { page } = require('../cloud-ten-runtime'); const sample = 'Page(page({}));'; Page({});",
  ]) assert.equal(usesLoginPageFactory(source, file), false, source)
})
