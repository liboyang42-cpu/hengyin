// R1 城市集市后台:三个页签都接到真实后端路由,线索页不出任何玩家信息,推广位页只读。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')
const readRepo = (p) => fs.readFileSync(path.join(__dirname, '../../..', p), 'utf8')

test('api 文件只用后端已有的 /business/marketplace 路由', () => {
  const api = read('src/api/business/marketplace.js')
  const controller = readRepo('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/MarketplaceAdminController.java')
  assert.match(controller, /@RequestMapping\("\/business\/marketplace"\)/)
  for (const route of ['/deals', '/deals/{couponId}', '/promotion-slot/purchases', '/leads']) {
    assert.ok(controller.includes('"' + route + '"'), 'controller missing ' + route)
  }
  for (const url of ["'/business/marketplace/deals'", "'/business/marketplace/deals/' + couponId",
    "'/business/marketplace/promotion-slot/purchases'", "'/business/marketplace/leads'"]) {
    assert.ok(api.includes(url), 'api missing ' + url)
  }
})

test('页面:线索不展示玩家字段;推广位订单没有任何写操作按钮', () => {
  const vue = read('src/views/business/marketplace/index.vue')
  const leads = vue.slice(vue.indexOf('name="leads"'), vue.indexOf('</el-tabs>'))
  assert.doesNotMatch(leads, /member|nickname|phone|手机号|昵称/i)
  const promo = vue.slice(vue.indexOf('name="promotion"'), vue.indexOf('name="leads"'))
  assert.doesNotMatch(promo, /<el-button/)
  assert.match(vue, /v-hasPermi="\['business:marketplace:edit'\]"/)
})

test('菜单迁移注册了页面组件与两项权限', () => {
  const sql = readRepo('chengyinhub-admin/sql/migration_20261115_marketplace.sql')
  assert.match(sql, /'business\/marketplace\/index'/)
  assert.match(sql, /business:marketplace:list/)
  assert.match(sql, /business:marketplace:edit/)
  assert.match(sql, /^-- Rollback/m)
})
