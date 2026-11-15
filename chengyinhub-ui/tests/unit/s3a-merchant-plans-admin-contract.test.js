// S3a 商家套餐与抽成后台:接到真实后端路由,权限与迁移一致,收费按钮随资金开关禁用,关闭时显示「即将开放」。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')
const readRepo = (p) => fs.readFileSync(path.join(__dirname, '../../..', p), 'utf8')

const api = read('src/api/business/merchantPlans.js')
const vue = read('src/views/business/merchantplans/index.vue')
const controller = readRepo('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/MerchantPlansAdminController.java')
const sql = readRepo('chengyinhub-admin/sql/migration_20261115_merchant_plans.sql')

test('api 只用后端已有的 /business/merchant-plans 路由', () => {
  assert.match(controller, /@RequestMapping\("\/business\/merchant-plans"\)/)
  for (const route of ['/take-rates', '/{code}', '/orders', '/subscriptions', '/orders/{orderNo}/confirm-paid', '/grant',
    '/visit-charges', '/visit-invoices', '/visit-charges/accrue', '/visit-invoices/issue']) {
    assert.ok(controller.includes('"' + route + '"'), 'controller missing ' + route)
  }
  for (const url of ["'/business/merchant-plans/take-rates'", "'/business/merchant-plans'", "'/business/merchant-plans/' + code",
    "'/business/merchant-plans/orders'", "'/business/merchant-plans/subscriptions'", "'/confirm-paid'",
    "'/business/merchant-plans/grant'", "'/business/merchant-plans/visit-charges'", "'/business/merchant-plans/visit-invoices'",
    "'/business/merchant-plans/visit-charges/accrue'", "'/business/merchant-plans/visit-invoices/issue'"]) {
    assert.ok(api.includes(url), 'api missing ' + url)
  }
})

test('按钮权限与迁移、控制器一致', () => {
  for (const perm of ['take', 'edit', 'sell', 'billing']) {
    const key = 'business:merchantplans:' + perm
    assert.ok(vue.includes("v-hasPermi=\"['" + key + "']\""), 'page missing ' + key)
    assert.ok(sql.includes("'" + key + "'"), 'migration missing ' + key)
    assert.ok(controller.includes("hasPermi('" + key + "')"), 'controller missing ' + key)
  }
  assert.match(sql, /'business\/merchantplans\/index'/)
})

test('收费操作随资金开关禁用,关闭时提示即将开放', () => {
  assert.match(vue, /即将开放/)
  assert.match(vue, /:disabled="!salesOpen" @click="confirmPaid/)
  assert.match(vue, /:disabled="!salesOpen" @click="accrue"/)
  assert.match(vue, /:disabled="!salesOpen" @click="issue"/)
  assert.match(vue, /:disabled="p.code !== 'FREE' && !salesOpen"/)
})

test('每项修改都要求填写原因;到店记录不展示玩家', () => {
  assert.match(vue, /请填写原因/)
  const billing = vue.slice(vue.indexOf('name="billing"'), vue.indexOf('</el-tabs>'))
  assert.doesNotMatch(billing, /member|nickname|phone|手机号|昵称|玩家编号/i)
})
