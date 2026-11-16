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

// W7 MERCHANT-15:打开资金区域两人完成;赞助任务每次写都要原因。
test('资金开关:页面只提交打开申请,批准用单独权限,路由与控制器一致', () => {
  const vue = read('src/views/business/moneyswitch/index.vue')
  const api = read('src/api/business/moneyswitch.js')
  const controller = readRepo('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/MoneySwitchAdminController.java')
  const migration = readRepo('chengyinhub-admin/sql/migration_20261130_money_switch_two_person.sql')
  assert.doesNotMatch(vue, /change\(scope\.row, true\)/)
  assert.match(vue, /askOpen\(scope\.row\)/)
  assert.ok(vue.includes("v-hasPermi=\"['business:moneyswitch:approve']\""))
  assert.ok(controller.includes("hasPermi('business:moneyswitch:approve')"))
  assert.ok(migration.includes("'business:moneyswitch:approve'"))
  assert.doesNotMatch(migration, /INSERT INTO sys_role_menu|INSERT INTO cy_money_switch\b/i)
  for (const route of ['"/requests"', '"/requests/{id}/approve"', '"/requests/{id}/reject"']) assert.ok(controller.includes(route), route)
  for (const url of ["'/business/money-switch/requests'", "'/approve'", "'/reject'"]) assert.ok(api.includes(url), url)
})

test('赞助任务:保存与上下线都先填写原因', () => {
  const vue = read('src/views/business/sponsoredquest/index.vue')
  assert.match(vue, /body\.reason = reason/)
  assert.match(vue, /setSponsoredQuestStatus\(row\.id, status, reason\)/)
})
