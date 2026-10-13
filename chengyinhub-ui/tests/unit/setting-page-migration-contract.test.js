const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const legacySettingPage = fs.readFileSync(path.join(uiRoot, 'src/views/business/setting/index.vue'), 'utf8')
const settlementRulePage = fs.readFileSync(path.join(uiRoot, 'src/views/business/settlementrule/index.vue'), 'utf8')
const settlementRuleApi = fs.readFileSync(path.join(uiRoot, 'src/api/business/settlementRule.js'), 'utf8')
const p3SettlementMenuMigration = fs.readFileSync(path.resolve(uiRoot, '../chengyinhub-admin/sql/migration_p3_phase_c_web_rules_refund.sql'), 'utf8')

test('旧设置页只兼容跳到结算规则，不再暴露死佣金或未接通的配额字段', () => {
  // [P2-4 Q5] 2188『结算规则』由 2155 迁入 2214,/p3-platform/settlementrule 不再存在。
  // 壳改为按 route name 跳转(RuoYi getRouteName = capitalize(path),与 parent 无关)。
  // ★断言必须落在 $router.replace 的实参上,不能只 match 源文件里出现过这串字符 ——
  //   否则一句注释就能把它变绿(2026-07-26 实测踩到)。
  assert.match(legacySettingPage, /\$router\.replace\(\{\s*\n?\s*name: 'Settlementrule'/)
  assert.doesNotMatch(legacySettingPage, /path:\s*['"]\/p3-platform\/settlementrule['"]/)
  assert.match(p3SettlementMenuMigration, /'p3-platform'/)
  assert.match(p3SettlementMenuMigration, /'settlementrule'/)
  assert.doesNotMatch(legacySettingPage, /getSetting|updateSetting|activityPlatformCommission|activityMerchantCommission|topicPlatformCommission|topicMerchantCommission|topicPublisherCommission|topicInviteCommission|user1TopicNum|user2TopicNum|user3TopicNum|merchantTopicNum|merchantTemplateNum|merchantCouponNum/)
})

test('结算规则承接平台活动分润收款账户，并区分未配置、不可用与读取失败', () => {
  assert.match(settlementRuleApi, /platform-account/)
  assert.match(settlementRuleApi, /getPlatformSettlementAccount/)
  assert.match(settlementRuleApi, /updatePlatformSettlementAccount/)
  assert.match(settlementRulePage, /getPlatformSettlementAccount/)
  assert.match(settlementRulePage, /updatePlatformSettlementAccount/)
  assert.match(settlementRulePage, /当前收款账户不可用/)
  assert.match(settlementRulePage, /活动结算将被阻断/)
  assert.match(settlementRulePage, /读取平台收款账户失败/)
  assert.match(settlementRulePage, /平台收款账户已更新，但状态回读失败/)
  assert.match(settlementRulePage, /\.then\(reloaded => \{[\s\S]*reloaded === false/)
  assert.match(settlementRulePage, /平台活动分润/)
  assert.doesNotMatch(settlementRulePage, /topicPlatformCommission|topicMerchantCommission|topicPublisherCommission|topicInviteCommission/)
})
