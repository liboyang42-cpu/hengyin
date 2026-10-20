'use strict'
// BL-106 第八批(BL-100..105)新接的接口合同:路径/方法/鉴权/请求与返回字段,逐条对照 Java 源码。
// 只读后端源码,不改 UI;消费方在 #144(BL-100)/#147(BL-103);BL-101/102/104/105 已 BLOCKED 无调用。
// 纯函数单测随各自 PR 交付:merchant-crm-campaigns.test.js(#144)、coop-partner-credit-bl103.test.js(#147)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const REPO = path.resolve(ROOT, '..')
const API = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/'
const SYS = 'chengyinhub-system/src/main/java/com/chengyinhub/business/'
const readRepo = (file) => fs.readFileSync(path.join(REPO, file), 'utf8')

function assertMapping(controller, base, verb, sub) {
  const src = readRepo(API + controller)
  assert.ok(src.includes(`@RequestMapping("${base}")`), `${controller} 类前缀应为 ${base}`)
  assert.ok(src.includes(`@${verb}Mapping("${sub}")`), `${controller} 应有 @${verb}Mapping("${sub}")`)
  return src
}

function methodBody(src, signature, len) {
  const at = src.indexOf(signature)
  assert.ok(at >= 0, '找不到方法 ' + signature)
  return src.slice(at, at + (len || 1200))
}

function fieldsOf(src) {
  return new Set((src.match(/private\s+[\w<>, .]+\s+(\w+)(?:\s*=[^;]+)?;/g) || []).map((m) => m.match(/(\w+)(?:\s*=[^;]+)?;$/)[1]))
}

function constantsOf(src) {
  return new Set((src.match(/public static final String (\w+) = "(\w+)";/g) || []).map((m) => m.match(/= "(\w+)";/)[1]))
}

test('BL-100 GET /api/merchant/crm/campaigns 与 /{campaignId}:只读,service 内 require(MARKETING_READ)', () => {
  const src = assertMapping('ApiMerchantCrmController.java', '/api/merchant/crm', 'Get', '/campaigns')
  assert.ok(src.includes('@GetMapping("/campaigns/{campaignId}")'))
  assert.match(methodBody(src, 'public AjaxResult campaigns()', 400), /merchantCrmCampaignService\.list\(principal\)/)
  assert.match(methodBody(src, 'public AjaxResult campaignDetail(@PathVariable Long campaignId)', 400),
    /merchantCrmCampaignService\.detail\(principal, campaignId\)/)
  // 写操作是 POST,小程序本批不调
  for (const sub of ['/campaigns', '/campaigns/{campaignId}/dispatch', '/campaigns/{campaignId}/retry', '/campaigns/preview']) {
    assert.ok(src.includes(`@PostMapping("${sub}")`), sub + ' 应仍是 POST 写接口')
  }
  const impl = readRepo(SYS + 'service/impl/MerchantCrmCampaignServiceImpl.java')
  assert.match(methodBody(impl, 'public List<MerchantCrmCampaignVO> list(Long principalMemberId)', 400),
    /accessService\.require\(principalMemberId, MerchantPermission\.MARKETING_READ\)[\s\S]*selectOwnedCampaigns\(access\.getMerchantId\(\), 20\)/)
  assert.match(methodBody(impl, 'public MerchantCrmCampaignVO detail(Long principalMemberId, Long campaignId)', 300),
    /require\(principalMemberId, MerchantPermission\.MARKETING_READ\)/)
})

test('BL-100 MerchantCrmCampaignVO / RecipientReceipt 字段与状态常量覆盖小程序读取项', () => {
  const vo = readRepo(SYS + 'domain/vo/MerchantCrmCampaignVO.java')
  const at = vo.indexOf('public static class RecipientReceipt')
  assert.ok(at > 0)
  const top = fieldsOf(vo.slice(0, at))
  for (const f of ['id', 'channel', 'title', 'status', 'recipientCount', 'deliveredCount', 'noConsentCount',
    'frequencySkippedCount', 'failedCount', 'retryableCount', 'createTime', 'recipients']) {
    assert.ok(top.has(f), 'MerchantCrmCampaignVO 缺字段 ' + f)
  }
  const rec = fieldsOf(vo.slice(at))
  for (const f of ['recipientId', 'customerName', 'status', 'failureMessage', 'deliveredAt']) {
    assert.ok(rec.has(f), 'RecipientReceipt 缺字段 ' + f)
  }
  assert.deepEqual([...constantsOf(readRepo(SYS + 'domain/MerchantCrmCampaign.java'))].sort(),
    ['NO_ELIGIBLE', 'PARTIAL_FAILED', 'PROCESSING', 'READY', 'SUCCESS'])
  const recipient = constantsOf(readRepo(SYS + 'domain/MerchantCrmCampaignRecipient.java'))
  for (const s of ['PENDING', 'DELIVERED', 'SKIPPED_NO_CONSENT', 'SKIPPED_FREQUENCY', 'RETRYABLE_BUSY', 'FAILED']) {
    assert.ok(recipient.has(s), 'MerchantCrmCampaignRecipient 缺状态 ' + s)
  }
  const consent = constantsOf(readRepo(SYS + 'domain/MerchantCrmMarketingConsent.java'))
  assert.ok(consent.has('IN_APP') && consent.has('COUPON'), '渠道常量应含 IN_APP / COUPON')
})

test('BL-103 POST /api/coop/credit:body.memberId 查对方,creditSummary 返回 90 天履约字段', () => {
  const src = assertMapping('ApiCoopController.java', '/api/coop', 'Post', '/credit')
  const body = methodBody(src, 'public AjaxResult credit(@RequestBody(required = false) Map<String, Object> body)', 700)
  assert.match(body, /body\.get\("memberId"\)/)
  assert.match(body, /coopInviteService\.creditSummary\(target\)/)
  const impl = readRepo(SYS + 'service/impl/CoopInviteServiceImpl.java')
  const summary = methodBody(impl, 'public Map<String, Object> creditSummary(Long memberId)', 1600)
  for (const k of ['days', 'fulfil', 'timeout', 'cancelOutside', 'violateInside', 'violationCount', 'fulfillmentRate']) {
    assert.ok(summary.includes(`m.put("${k}"`), 'creditSummary 缺字段 ' + k)
  }
  // 样本为 0 时后端把履约率兜底成 100 —— 小程序据此在样本为 0 时不展示
  assert.match(summary, /int rate = total == 0 \? 100 :/)
  // 对方 memberId 口径:fromId 恒为 memberId;toId 仅 merchant 时为 memberId
  const invite = readRepo(SYS + 'domain/CoopInvite.java')
  assert.match(invite, /发起方ID\(memberId\)/)
  assert.match(invite, /受邀方ID\(merchant=memberId \/ club=clubId\)/)
})
