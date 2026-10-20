'use strict'
// BL-74 第三批(BL-61..72)新接的接口合同:路径/方法/参数绑定方式/返回字段,逐条对照 Java 源码。
// 只读后端源码,不改 UI;某条合同变了,先改这里再改消费方(消费方在 #104/#109/#110/#112/#113/#114)。
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

function fieldsOf(javaFile) {
  return new Set((readRepo(javaFile).match(/private\s+[\w<>, ]+\s+(\w+)(?:\s*=[^;]+)?;/g) || []).map((m) => m.match(/(\w+)(?:\s*=[^;]+)?;$/)[1]))
}

test('BL-61 POST /api/im/unblock 为表单参数 target_member_id;会话回读字段 blockedByMe', () => {
  const src = assertMapping('ApiImController.java', '/api/im', 'Post', '/unblock')
  assert.match(src, /public AjaxResult unblock\(String target_member_id\)/)
  assert.match(methodBody(src, 'public AjaxResult unblock('), /imService\.unblock\(getAppUserId\(\), Convert\.toLong\(target_member_id, 0L\)\)/)
  assert.match(readRepo(SYS + 'service/impl/ImServiceImpl.java'), /res\.put\("blockedByMe",/)
})

test('BL-66 GET /api/community-trust/safety/merchants/{merchantId} → MerchantSafetySummaryVO;样本态与等级取值', () => {
  const src = assertMapping('ApiCommunityTrustController.java', '/api/community-trust', 'Get', '/safety/merchants/{merchantId}')
  assert.match(src, /safetySummary\(@PathVariable Long merchantId\)/)
  const fields = fieldsOf(SYS + 'domain/vo/MerchantSafetySummaryVO.java')
  for (const f of ['merchantId', 'sampleStatus', 'sampleCount', 'minimumSampleCount', 'windowDays', 'level', 'disclaimerCode']) {
    assert.ok(fields.has(f), 'MerchantSafetySummaryVO 缺字段 ' + f)
  }
  const impl = methodBody(readRepo(SYS + 'service/impl/CommunityTrustServiceImpl.java'), 'public MerchantSafetySummaryVO getSafetySummary(Long merchantId)', 2400)
  for (const s of ['POLICY_PENDING', 'INSUFFICIENT', 'SUFFICIENT']) assert.ok(impl.includes(`setSampleStatus("${s}")`), '缺样本态 ' + s)
  // 商家编号是 mms_merchant.id(与 public-home 的 id 同源),不是 memberId
  assert.match(impl, /mmsMerchantMapper\.selectMmsMerchantById\(merchantId\)/)
  const rating = readRepo(SYS + 'domain/MerchantSafetyRating.java')
  for (const l of ['SAFE', 'NEUTRAL', 'UNSAFE']) assert.ok(rating.includes(`LEVEL_${l} = "${l}"`), '缺等级 ' + l)
})

test('BL-67 GET /contributions/taxonomy 的 issueTypes;POST /contributions/submit 为 JSON 体', () => {
  const src = assertMapping('ApiCommunityTrustController.java', '/api/community-trust', 'Get', '/contributions/taxonomy')
  assert.ok(src.includes('@PostMapping("/contributions/submit")'))
  assert.match(src, /@RequestBody\(required = false\) MerchantContributionSubmitRequest request/)
  const tax = methodBody(readRepo(SYS + 'service/impl/CommunityTrustServiceImpl.java'), 'public Map<String, Object> getContributionTaxonomy()')
  assert.match(tax, /put\("contributionTypes", Arrays\.asList\("INFO_UPDATE", "ISSUE_REPORT"\)\)/)
  assert.match(tax, /put\("issueTypes", Arrays\.asList\("CLOSED", "ADDRESS", "BUSINESS_TIME", "CONTENT", "OTHER"\)\)/)
  const fields = fieldsOf(SYS + 'domain/dto/MerchantContributionSubmitRequest.java')
  for (const f of ['merchantId', 'contributionType', 'fieldChanges', 'issueType', 'description', 'evidenceAssetIds', 'idempotencyKey']) {
    assert.ok(fields.has(f), 'MerchantContributionSubmitRequest 缺字段 ' + f)
  }
})

test('BL-69 GET /api/city/nodes/merchant/{merchantId} 无节点回空;POST /nodes/{id}/favorite 回 favorited 布尔', () => {
  const src = assertMapping('ApiCityNodeController.java', '/api/city', 'Get', '/nodes/merchant/{merchantId}')
  const body = methodBody(src, 'public AjaxResult merchantNode(')
  assert.match(body, /merchantNodeDiscoveryService\.findActiveNode\(merchantId\)/)
  assert.match(body, /if \(node == null\) return success\(\);/)
  assert.match(body, /markUserState\(Collections\.singletonList\(node\)\)/)
  assert.ok(src.includes('@PostMapping("/nodes/{id}/favorite")'))
  const fav = methodBody(src, 'public AjaxResult favorite(', 1600)
  assert.match(fav, /if \(memberId <= 0\) return error\("请先登录"\)/)
  assert.match(fav, /data\.put\("favorited", nowFav\)/)
  const fields = fieldsOf(SYS + 'domain/vo/CityNodeVO.java')
  for (const f of ['poiId', 'name', 'merchantId', 'favorited']) assert.ok(fields.has(f), 'CityNodeVO 缺字段 ' + f)
})

test('BL-70 POST /api/roam/shop/visitors 为表单参数 sourceType/sourceIds;每行 sourceId/avatars/total;上限 20/5', () => {
  const src = assertMapping('ApiRoamController.java', '/api/roam', 'Post', '/shop/visitors')
  assert.match(src, /public AjaxResult shopVisitors\(Integer sourceType, String sourceIds\)/)
  assert.match(src, /VISITORS_SOURCE_MAX = 20;/)
  assert.match(src, /VISITORS_AVATAR_MAX = 5;/)
  const body = methodBody(src, 'public AjaxResult shopVisitors(', 2600)
  assert.match(body, /sourceType != 1 && sourceType != 2/)
  for (const k of ['sourceId', 'avatars', 'total']) assert.ok(body.includes(`row.put("${k}"`), '行缺 ' + k)
  // sourceType 口径:1=roam_poi 据点,2=cms_registration_merchant 报名商家(同 /shop/visit)
  assert.match(src, /if \(sourceType == 1\) \{\s*\/\/ roam_poi 据点/)
})

test('BL-71 POST /api/user/player-code 需登录,回 { qr }', () => {
  const src = assertMapping('ApiUmsMemberController.java', '/api/user', 'Post', '/player-code')
  const body = methodBody(src, 'public AjaxResult getPlayerWxacode()')
  assert.match(body, /return error\("请先登录"\)/)
  assert.match(body, /singletonMap\(\s*"qr", playerWxacodeService\.getOrCreate\(memberId\)\)/)
  assert.match(body, /return error\("个人码生成服务繁忙，请稍后重试"\)/)
})

test('BL-72(BLOCKED 依据) GET /api/official/invites 直接返回原始 coop_invite 行,不填 topicName/partner', () => {
  const src = assertMapping('ApiOfficialEventController.java', '/api/official', 'Get', '/invites')
  const body = methodBody(src, 'public AjaxResult officialInvites(')
  assert.match(body, /return success\(coopInviteService\.selectCoopInviteList\(query\)\);/)
  const impl = methodBody(readRepo(SYS + 'service/impl/CoopInviteServiceImpl.java'), 'public List<CoopInvite> selectCoopInviteList(CoopInvite coopInvite)', 400)
  assert.doesNotMatch(impl, /setTopicName|setPartner/)
})
