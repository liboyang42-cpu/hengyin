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
  // is_active=1 时后端已按有效期过滤:前端不必再判过期
  // 2026-10-20 合并 master(#396)后:后端 isActive=1 同时排除尚未开始(start_date > now)与已到期(end_date <= now)的行;
  // 前端 merchant-subscription-view.js 的 startDate 过滤保留为兜底,与后端口径一致、不冲突。
  assert.match(xml, /isActive != null and isActive == 1"> and \(start_date is null or start_date &lt;= now\(\)\) and \(end_date is null or end_date &gt; now\(\)\)/)
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
