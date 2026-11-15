// S5 店铺小游戏后台:接到真实后端路由,权限与迁移、控制器一致,每次修改都要原因,正文不露内部名。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')
const readRepo = (p) => fs.readFileSync(path.join(__dirname, '../../..', p), 'utf8')

const api = read('src/api/business/shopGames.js')
const vue = read('src/views/business/shopgames/index.vue')
const controller = readRepo('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/ShopGamesAdminController.java')
const sql = readRepo('chengyinhub-admin/sql/migration_20261115_shop_games.sql')

test('api 只用后端已有的 /business/shop-games 路由', () => {
  assert.match(controller, /@RequestMapping\("\/business\/shop-games"\)/)
  for (const route of ['/catalog', '/profiles', '/profiles/{merchantId}', '/games/{key}']) {
    assert.ok(controller.includes('"' + route + '"'), 'controller missing ' + route)
  }
  for (const url of ["'/business/shop-games/catalog'", "'/business/shop-games/profiles'",
    "'/business/shop-games/profiles/' + merchantId", "'/business/shop-games/games/' + key"]) {
    assert.ok(api.includes(url), 'api missing ' + url)
  }
})

test('按钮权限与迁移、控制器一致', () => {
  for (const perm of ['list', 'edit']) {
    const key = 'business:shopgames:' + perm
    assert.ok(sql.includes("'" + key + "'"), 'migration missing ' + key)
    assert.ok(controller.includes("hasPermi('" + key + "')"), 'controller missing ' + key)
  }
  assert.ok(vue.includes("v-hasPermi=\"['business:shopgames:edit']\""))
  assert.match(sql, /'business\/shopgames\/index'/)
})

test('每次修改都要原因', () => {
  assert.match(vue, /askReason\(/)
  assert.match(vue, /:disabled="!shopForm.reason/)
  assert.match(controller, /str\(body.get\("reason"\)\)/)
})

test('正文不露内部名', () => {
  const template = vue.split('<script>')[0]
  for (const word of ['merchantId</', 'templateId', 'qaPick', 'OPERATOR', 'MERCHANT', 'DEFAULT', 'cy_shop_game']) {
    assert.ok(!template.includes(word), 'template shows ' + word)
  }
})
