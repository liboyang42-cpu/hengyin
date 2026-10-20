'use strict'
// BL-114 第九批(BL-108..113)新接的接口合同:路径/方法/鉴权/返回字段,逐条对照 Java 源码。
// 只读后端源码,不改 UI。消费方只有 #152(BL-108);BL-109..113 已 BLOCKED(#153..#157)无新调用。
// 纯函数单测随 BL-108 交付:merchant-subscription-view.test.js。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const REPO = path.resolve(ROOT, '..')
const API = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/'
const SYS = 'chengyinhub-system/src/main/java/com/chengyinhub/business/'
const readRepo = (file) => fs.readFileSync(path.join(REPO, file), 'utf8')
const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8')

function fieldsOf(src) {
  return new Set((src.match(/private\s+[\w<>, .]+\s+(\w+)(?:\s*=[^;]+)?;/g) || []).map((m) => m.match(/(\w+)(?:\s*=[^;]+)?;$/)[1]))
}

test('BL-108 POST /api/merchant/subscription:BASIC_READ,只查生效行,无写参数', () => {
  const src = readRepo(API + 'ApiMerchantController.java')
  assert.ok(src.includes('@RequestMapping("/api/merchant")'))
  const at = src.indexOf('@PostMapping("/subscription")')
  assert.ok(at >= 0, '应有 @PostMapping("/subscription")')
  const body = src.slice(at, at + 700)
  assert.match(body, /public AjaxResult mySubscription\(\)/)
  assert.match(body, /MerchantPermission\.BASIC_READ/)
  assert.match(body, /q\.setIsActive\(1\)/)
  assert.match(body, /selectMerchantSubscriptionList\(q\)/)
  const xml = readRepo('chengyinhub-system/src/main/resources/mapper/business/MerchantSubscriptionMapper.xml')
  // isActive=1 仅返回已生效权益:未来 start_date 排除,开始时刻包含,到期时刻排除。
  // 未设开始或结束日期仍允许;完整匹配同一条件块,避免丢失任一边界后假绿。
  assert.match(xml, /<if test="isActive != null and isActive == 1">\s*and \(start_date is null or start_date &lt;= now\(\)\)\s+and \(end_date is null or end_date &gt; now\(\)\)\s*<\/if>/)
})

test('BL-108 前端只读用到的字段都在 MerchantSubscription 实体里,且不含价格字段', () => {
  const fields = fieldsOf(readRepo(SYS + 'domain/MerchantSubscription.java'))
  for (const f of ['id', 'subscriptionType', 'endDate', 'maxUsage', 'usedCount', 'isActive']) {
    assert.ok(fields.has(f), 'MerchantSubscription 缺字段 ' + f)
  }
  const util = read('pages/merchant/utils/merchant-subscription-view.js')
  const used = new Set((util.match(/\br\.(\w+)/g) || []).map((m) => m.slice(2)))
  for (const f of used) assert.ok(fields.has(f), '前端读了实体没有的字段 ' + f)
  assert.doesNotMatch(util, /price|amount|fee/i)
})

test('BL-108 权益名映射只用装修页已有 tiers 的 key', () => {
  const js = read('pages/merchant/decor/index.js')
  const keys = (js.match(/\{ key: '(\w+)', name: '/g) || []).map((m) => m.match(/'(\w+)'/)[1])
  assert.deepEqual(keys, ['premium_template', 'promotion_slot', 'brand_home', 'custom_event'])
  const entity = readRepo(SYS + 'domain/MerchantSubscription.java')
  for (const k of keys) assert.ok(entity.includes(k), '实体注释的权益类型里没有 ' + k)
})
