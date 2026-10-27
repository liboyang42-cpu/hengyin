'use strict'
// BL-82 第四批(BL-76..80)新接的接口合同:路径/方法/参数绑定方式/鉴权/返回字段,逐条对照 Java 源码。
// 只读后端源码,不改 UI;某条合同变了,先改这里再改消费方(消费方在 #119/#120/#122/#123;BL-78 #121 已 BLOCKED)。
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
  return new Set((readRepo(javaFile).match(/private\s+[\w<>, .]+\s+(\w+)(?:\s*=[^;]+)?;/g) || []).map((m) => m.match(/(\w+)(?:\s*=[^;]+)?;$/)[1]))
}

test('BL-76 POST /api/merchant/verification-records:无参,VERIFY_RECORD_READ,按 owner 取 CmsRegistrationScan 倒序全量', () => {
  const src = assertMapping('ApiMerchantController.java', '/api/merchant', 'Post', '/verification-records')
  const body = methodBody(src, 'public AjaxResult verificationRecords()')
  assert.match(body, /merchantAccessService\.require\(\s*principalMemberId, MerchantPermission\.VERIFY_RECORD_READ\)/)
  assert.match(body, /sq\.setMemberId\(access\.getOwnerMemberId\(\)\)/, '必须按店主归属取数，不能信前端参数')
  assert.match(body, /return Long\.compare\(tb, ta\)/, '按 createTime 倒序')
  assert.match(body, /return success\(list\)/, '返回数组，不分页')
  const fields = fieldsOf(SYS + 'domain/CmsRegistrationScan.java')
  for (const f of ['id', 'registrationId', 'validationMethod', 'status', 'name']) assert.ok(fields.has(f), 'CmsRegistrationScan 缺字段 ' + f)
  const entity = readRepo(SYS + 'domain/CmsRegistrationScan.java')
  assert.match(entity, /状态 0未验证 1已验证/, 'status 取值语义')
  assert.match(entity, /验证方式:1、文字验证 2、图片验证 {2}3、选项验证/, 'validationMethod 取值语义')
  assert.match(entity, /extends BaseEntity/, 'createTime 来自 BaseEntity')
  assert.match(readRepo('chengyinhub-common/src/main/java/com/chengyinhub/common/core/domain/BaseEntity.java'),
    /@JsonFormat\(pattern = "yyyy-MM-dd HH:mm:ss"\)\s*private Date createTime;/, 'createTime 序列化格式(页面按 slice 取日期/时分)')
  // 小程序权限名与后端一致:页面靠 canReadVerifyRecords 判权
  assert.match(readRepo(SYS + 'access/merchant/MerchantPermission.java'), /VERIFY_RECORD_READ = "merchant:verify:record:read"/)
  assert.match(fs.readFileSync(path.join(ROOT, 'utils/merchant-access-policy.js'), 'utf8'), /VERIFY_RECORD_READ: 'merchant:verify:record:read'/)
})

test('BL-77 POST /api/merchant/coupon-summary:COUPON_MANAGE,返回 couponCount/couponTotal/received/verified', () => {
  const src = assertMapping('ApiMerchantController.java', '/api/merchant', 'Post', '/coupon-summary')
  const body = methodBody(src, 'public AjaxResult couponSummary()', 2000)
  assert.match(body, /MerchantPermission\.COUPON_MANAGE/)
  for (const k of ['couponCount', 'couponTotal', 'received', 'verified']) assert.ok(body.includes(`data.put("${k}",`), '缺返回键 ' + k)
  assert.match(body, /received \+= c\.getReceiveCount\(\)/)
  assert.match(body, /verified \+= c\.getUseCount\(\)/)
})

test('BL-79 POST /api/object-card/rename:表单参数 id/title(非 JSON 体),上限 64,返回 PlayerObjectCardVO.title', () => {
  const src = assertMapping('ApiObjectCardController.java', '/api/object-card', 'Post', '/rename')
  assert.match(src, /public AjaxResult rename\(Long id, String title\)/, '无 @RequestBody:小程序 POST 默认 urlencoded 正好对上')
  assert.match(src, /static final int TITLE_MAX = 64;/)
  const body = methodBody(src, 'public AjaxResult rename(')
  assert.match(body, /return error\("给这张卡起个名字"\)/)
  assert.match(body, /objectCardService\.rename\(memberId, id, name\)/, '按当前用户归属改名')
  assert.match(body, /return success\(PlayerObjectCardVO\.from\(renamed[,)]/)
  assert.ok(fieldsOf(SYS + 'domain/vo/PlayerObjectCardVO.java').has('title'))
})

test('BL-80 GET /api/official/broadcast/{id}/stats:白名单+发布者本人,返回 reached/clicked', () => {
  const src = assertMapping('ApiOfficialEventController.java', '/api/official', 'Get', '/broadcast/{id}/stats')
  const body = methodBody(src, 'public AjaxResult broadcastStats(@PathVariable("id") Long id)', 1400)
  assert.match(body, /if \(!canPublish\(me\)\) return error\("无官方发布权限"\)/)
  assert.match(body, /officialBroadcastService\.getById\(id, me\)/)
  assert.match(body, /bc\.getSenderAccount\(\)\.equals\(me\)/, 'V1 通知只给发布者本人')
  assert.match(body, /officialEventV2Service\.broadcastStatsForOperator\(id, me\)/)
  // W8 起触达统计迁入 OfficialBroadcastDeliveryService.summary：reachStats 先按发布者归属取单条，再委托 summary。
  const stats = methodBody(readRepo(SYS + 'service/impl/OfficialBroadcastServiceImpl.java'), 'public java.util.Map<String, Object> reachStats(Long broadcastId, Long actorMemberId)', 200)
  assert.match(stats, /getById\(broadcastId, actorMemberId\);\s*return delivery\.summary\(broadcastId\);/)
  const summary = methodBody(readRepo(SYS + 'service/impl/OfficialBroadcastDeliveryService.java'), 'public Map<String,Object> summary(Long id)', 900)
  assert.match(summary, /out\.put\("reached",\s*counts\.get\("INAPP_STORED"\)\)/)
  assert.match(summary, /out\.put\("clicked",\s*logs\.countClicked\(id\)\)/)
  // 入口挂在「我发布的」列表,其通知来源仍是 my-published.broadcasts
  assert.match(src, /data\.put\("broadcasts", officialBroadcastService\.listBySender\(memberId, memberId\)\)/)
})
