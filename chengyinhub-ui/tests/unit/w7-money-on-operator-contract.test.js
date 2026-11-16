// W7 MERCHANT-12 资金开启前的运营闭环:套餐确认收款要流水号与实收金额;无订单开通付费套餐要单独权限;
// 会费退款可以在「商品与会费」里通过(原路退款)或驳回,权限与迁移、控制器一致,迁移不授予任何角色。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')
const readRepo = (p) => fs.readFileSync(path.join(__dirname, '../../..', p), 'utf8')

const plansVue = read('src/views/business/merchantplans/index.vue')
const commerceVue = read('src/views/business/commerceconsole/index.vue')
const commerceApi = read('src/api/business/commerceConsole.js')
const commerceController = readRepo('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CommerceConsoleController.java')
const plansController = readRepo('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/MerchantPlansAdminController.java')
const sql = readRepo('chengyinhub-admin/sql/migration_20261130_money_on_operator.sql')

test('确认收款提交流水号与实收金额(分),与控制器字段一致', () => {
  assert.match(plansVue, /paymentReference: reference, amountReceivedFen: receivedFen/)
  assert.match(plansController, /body\.get\("paymentReference"\)/)
  assert.match(plansController, /body\.get\("amountReceivedFen"\)/)
  assert.match(plansVue, /实收金额与应收不一致/)
})

test('无订单开通付费套餐要单独权限,迁移登记且不授予角色', () => {
  assert.match(plansVue, /hasPermi\('business:merchantplans:grantPaid'\)/)
  assert.match(plansController, /hasPermi\("business:merchantplans:grantPaid"\)/)
  assert.ok(sql.includes("'business:merchantplans:grantPaid'"))
  assert.doesNotMatch(sql, /INSERT INTO sys_role_menu/i)
})

test('会费退款:路由、权限、页面一致,每次处理都要原因', () => {
  for (const route of ['"/dues-refunds"', '"/dues-refunds/{reviewId}/approve"', '"/dues-refunds/{reviewId}/reject"']) {
    assert.ok(commerceController.includes(route), 'controller missing ' + route)
  }
  for (const url of ["'/business/commerceConsole/dues-refunds'", "'/approve'", "'/reject'"]) {
    assert.ok(commerceApi.includes(url), 'api missing ' + url)
  }
  assert.ok(commerceController.includes("hasPermi('business:commerceConsole:refund')"))
  assert.ok(commerceVue.includes("v-hasPermi=\"['business:commerceConsole:refund']\""))
  assert.ok(sql.includes("'business:commerceConsole:refund'"))
  assert.match(commerceVue, /请填写原因/)
})
