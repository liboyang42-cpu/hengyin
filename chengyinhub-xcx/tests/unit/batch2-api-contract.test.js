'use strict'
// BL-57 第二批(BL-31..56)新接的接口合同:路径/方法/请求体绑定方式/返回字段,逐条对照 Java 源码。
// 只读后端源码,不改 UI;某条合同变了,先改这里再改消费方。
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

function assertMapping(controller, base, verb, sub) {
  const src = readRepo(API + controller)
  assert.ok(src.includes(`@RequestMapping("${base}")`), `${controller} 类前缀应为 ${base}`)
  assert.ok(src.includes(`@${verb}Mapping("${sub}")`), `${controller} 应有 @${verb}Mapping("${sub}")`)
  return src
}

function methodBody(src, signature) {
  const at = src.indexOf(signature)
  assert.ok(at >= 0, '找不到方法 ' + signature)
  return src.slice(at, at + 1200)
}

function fieldsOf(javaFile) {
  return new Set((readRepo(javaFile).match(/private\s+[\w<>, ]+\s+(\w+);/g) || []).map((m) => m.match(/(\w+);$/)[1]))
}

test('BL-42 GET /api/city/nodes/favorites → CityNodeVO[](含 poiId/name/coverImg/merchantName/merchantAddress/completed)', () => {
  const src = assertMapping('ApiCityNodeController.java', '/api/city', 'Get', '/nodes/favorites')
  assert.match(methodBody(src, 'public AjaxResult favorites()'), /selectFavoriteMerchantNodes\(memberId\)[\s\S]*markUserState\(list\)/)
  const fields = fieldsOf(SYS + 'domain/vo/CityNodeVO.java')
  for (const f of ['poiId', 'name', 'coverImg', 'merchantName', 'merchantAddress', 'completed', 'favorited']) {
    assert.ok(fields.has(f), 'CityNodeVO 缺字段 ' + f)
  }
})

test('BL-32/51 GET /api/community-trust/reports/taxonomy → policyVersion + reasons[code,label,priority,firstResponseMinutes,emergency]', () => {
  assertMapping('ApiCommunityTrustController.java', '/api/community-trust', 'Get', '/reports/taxonomy')
  const impl = readRepo(SYS + 'service/impl/CommunityTrustServiceImpl.java')
  const tax = methodBody(impl, 'public Map<String, Object> getReportTaxonomy()')
  assert.match(tax, /put\("policyVersion"/)
  assert.match(tax, /put\("reasons", CommunityReportPolicy\.publicReasons\(\)\)/)
  const policy = readRepo(SYS + 'service/support/CommunityReportPolicy.java')
  for (const k of ['code', 'label', 'priority', 'firstResponseMinutes', 'emergency']) {
    assert.ok(policy.includes(`item.put("${k}"`), 'publicReasons 缺 ' + k)
  }
  // 逐条原因没有「说明」字段:前端不得编造原因说明文案
  assert.ok(!/item\.put\("(description|desc|hint)"/.test(policy))
})

test('BL-32..36 POST /api/community-trust/reports/submit 为 JSON 体(CommunityReportSubmitRequest)', () => {
  const src = assertMapping('ApiCommunityTrustController.java', '/api/community-trust', 'Post', '/reports/submit')
  assert.match(src, /submitReport\(@RequestBody\(required = false\) CommunityReportSubmitRequest request\)/)
  const fields = fieldsOf(SYS + 'domain/dto/CommunityReportSubmitRequest.java')
  for (const f of ['targetType', 'targetId', 'reasonCode', 'policyVersion', 'description', 'requestId', 'evidenceAssetIds']) {
    assert.ok(fields.has(f), 'CommunityReportSubmitRequest 缺字段 ' + f)
  }
})

test('BL-31 POST /api/v1/community/notifications/{id}/read,可选 JSON 体 {requestId}', () => {
  const src = assertMapping('ApiCommunityPostController.java', '/api/v1/community', 'Post', '/notifications/{id}/read')
  assert.match(src, /readNotification\(@PathVariable Long id,\s*@RequestBody\(required = false\) CommunityPostRequests\.RequestId request\)/)
})

test('BL-43 POST /api/merchant/npc/voice/reset 无参,需商家 PROFILE_WRITE', () => {
  const src = assertMapping('ApiMerchantController.java', '/api/merchant', 'Post', '/npc/voice/reset')
  assert.match(methodBody(src, 'public AjaxResult npcVoiceReset()'), /MerchantPermission\.PROFILE_WRITE/)
})

test('BL-45 POST /api/club/badge/list 为 JSON 体 {id: clubId},登录即可读', () => {
  const src = assertMapping('ApiClubController.java', '/api/club', 'Post', '/badge/list')
  const body = methodBody(src, 'public AjaxResult badgeList(@RequestBody Club body)')
  assert.match(body, /body\.getId\(\) == null/)
  assert.doesNotMatch(body.slice(0, body.indexOf('@Operation') > 0 ? body.indexOf('@Operation') : body.length), /notOwner/)
})

test('BL-41 GET /api/official/active-for-roam', () => {
  assertMapping('ApiOfficialEventController.java', '/api/official', 'Get', '/active-for-roam')
})

test('BL-49 POST /api/roam/stamp/received 用普通参数绑定 → 前端不得改成 JSON 头', () => {
  const src = assertMapping('ApiRoamStampController.java', '/api/roam/stamp', 'Post', '/received')
  const body = methodBody(src, 'public AjaxResult received(Integer pageNum, Integer pageSize)')
  for (const k of ['list', 'total', 'pageNum', 'pageSize']) assert.ok(body.includes(`data.put("${k}"`), '缺 ' + k)
  // request-client:POST 且不带 header → urlencoded,Spring 才能绑定 pageNum/pageSize
  assert.match(read('utils/transport/request-client.js'), /POST 且无 param\.header → urlencoded/)
  const album = read('components/cy/scene-roam-stamp-album/index.js')
  const call = album.slice(album.indexOf("'/api/roam/stamp/received'"), album.indexOf('success:', album.indexOf("'/api/roam/stamp/received'")))
  assert.doesNotMatch(call, /header:/)
  assert.match(call, /data: \{ pageNum: this\.data\.pageNum, pageSize: 50 \}/)
})
