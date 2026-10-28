'use strict'
// BL-91 第五批(BL-84..89)新接的接口合同:路径/方法/鉴权/请求与返回字段,逐条对照 Java 源码。
// 只读后端源码,不改 UI;消费方在 #128(BL-85)/#130(BL-87)/#132(BL-89);BL-84/86/88 已 BLOCKED 无调用。
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

function classBlock(src, name) {
  const at = src.indexOf('class ' + name + ' ')
  assert.ok(at >= 0, '找不到类 ' + name)
  const next = src.indexOf('public static class', at + 1)
  return src.slice(at, next < 0 ? undefined : next)
}

test('BL-85 GET /api/merchant/ops/prerequisites:按登录主体计算,返回 PrerequisiteChecklist', () => {
  const src = assertMapping('ApiMerchantOpsController.java', '/api/merchant/ops', 'Get', '/prerequisites')
  const body = methodBody(src, 'public AjaxResult prerequisites()')
  assert.match(body, /prerequisiteService\.calculate\(getAppUserId\(\)\)/, '只按登录主体计算，不信前端参数')
  const vo = readRepo(SYS + 'domain/vo/MerchantOpsVO.java')
  const checklist = fieldsOf(classBlock(vo, 'PrerequisiteChecklist'))
  for (const f of ['merchantId', 'canContinue', 'checkedAt', 'items']) assert.ok(checklist.has(f), 'PrerequisiteChecklist 缺字段 ' + f)
  const item = fieldsOf(classBlock(vo, 'Prerequisite'))
  for (const f of ['code', 'title', 'description', 'status', 'severity', 'actionTarget']) assert.ok(item.has(f), 'Prerequisite 缺字段 ' + f)
  const target = fieldsOf(classBlock(vo, 'ActionTarget'))
  for (const f of ['type', 'path', 'available', 'unavailableReason']) assert.ok(target.has(f), 'ActionTarget 缺字段 ' + f)
  const impl = readRepo(SYS + 'service/impl/MerchantPrerequisiteServiceImpl.java')
  assert.match(impl, /item\.setStatus\(passed \? "PASS" : "BLOCKED"\)/, 'status 只有 PASS/BLOCKED')
  assert.match(impl, /APPLY_PATH = "\/pages\/merchant\/apply\/index"/, '去处之一(前端白名单)')
  assert.match(impl, /DECOR_PATH = "\/pages\/merchant\/decor\/index"/, '去处之二(前端白名单)')
  assert.match(impl, /target\.setAvailable\(path != null\)/)
  assert.match(impl, /setPermissionRequired\("MERCHANT_OWNER"\)/, '前端只给有资料写权限的岗位请求')
})

test('BL-87 GET /api/audit/{taskId}/detail:仅提交人本人,403/404 文案,申请人视图字段', () => {
  const src = assertMapping('ApiAuditController.java', '/api/audit', 'Get', '/{taskId}/detail')
  const body = methodBody(src, 'public AjaxResult detail(@PathVariable Long taskId)', 1400)
  assert.match(body, /HttpStatus\.NOT_FOUND, "审核任务不存在"/)
  assert.match(body, /task\.getSubmitterId\(\)\.equals\(memberId\)/, '仅提交人本人')
  assert.match(body, /HttpStatus\.FORBIDDEN, "无权查看该审批记录"/)
  assert.match(body, /auditTaskService\.detail\(taskId, memberId, false\)/, '小程序走申请人视图')
  const impl = readRepo(SYS + 'service/impl/AuditTaskServiceImpl.java')
  const task = methodBody(impl, 'private AuditTask applicantTask(AuditTask source)', 600)
  for (const f of ['Id', 'BizType', 'BizId', 'Status', 'SubmittedAt']) assert.ok(task.includes('safe.set' + f + '('), 'applicantTask 缺 ' + f)
  const timeline = methodBody(impl, 'private List<AuditRecord> applicantTimeline(List<AuditRecord> source)', 1400)
  for (const a of ['ACTION_SUBMIT', 'ACTION_APPROVE', 'ACTION_REJECT', 'ACTION_REVERT']) assert.ok(timeline.includes(a), '时间线动作 ' + a)
  for (const f of ['Action', 'ReasonText', 'CreateTime']) assert.ok(timeline.includes('item.set' + f + '('), 'applicantTimeline 缺 ' + f)
  const status = readRepo(SYS + 'domain/AuditTask.java')
  for (const [k, v] of [['PENDING', 0], ['APPROVED', 1], ['REJECTED', 2], ['ESCALATED', 3], ['CANCELLED', 4]]) {
    assert.match(status, new RegExp(`STATUS_${k} = ${v};`), '状态值 ' + k)
  }
  // 入口:系统卡片 payload 带 taskId(前端从 card.result.taskId 取)
  // 方法体随守卫申诉文案增长，按方法自身的闭合括号截取，不再用固定字符窗（也不会读到下一个方法）。
  const notifyAt = impl.indexOf('private void notifyReviewResult(AuditTask task, AuditReviewDTO request)')
  assert.ok(notifyAt >= 0, '找不到方法 notifyReviewResult')
  const notify = impl.slice(notifyAt, impl.indexOf('\n    }\n', notifyAt))
  assert.match(notify, /result\.put\("taskId", task\.getId\(\)\)/)
  assert.match(notify, /card\.put\("result", result\)/)
  assert.match(notify, /ImMessage\.MSG_CARD/)
})

test('BL-89 安全感受评价:taxonomy 档位 + POST /safety/ratings 请求体/资格/回执', () => {
  const src = assertMapping('ApiCommunityTrustController.java', '/api/community-trust', 'Post', '/safety/ratings')
  assert.ok(src.includes('@GetMapping("/safety/taxonomy")'))
  const body = methodBody(src, 'public AjaxResult submitSafetyRating(', 700)
  assert.match(body, /@RequestBody\(required = false\) MerchantSafetyRatingRequest request/, 'JSON body')
  assert.match(body, /MerchantSafetyRatingReceiptVO\.from\(row\)/)
  const req = fieldsOf(readRepo(SYS + 'domain/dto/MerchantSafetyRatingRequest.java'))
  for (const f of ['merchantId', 'overallLevel', 'reason', 'idempotencyKey']) assert.ok(req.has(f), '请求体缺字段 ' + f)
  const receipt = fieldsOf(readRepo(SYS + 'domain/vo/MerchantSafetyRatingReceiptVO.java'))
  for (const f of ['ratingId', 'merchantId', 'overallLevel', 'ratingPeriod', 'acceptedAt']) assert.ok(receipt.has(f), '回执缺字段 ' + f)
  const impl = readRepo(SYS + 'service/impl/CommunityTrustServiceImpl.java')
  const submit = methodBody(impl, 'public MerchantSafetyRating submitSafetyRating(', 1800)
  assert.match(submit, /selectMmsMerchantById\(request\.getMerchantId\(\)\)/, 'merchantId 是 mms_merchant.id(前端需经 public-home 换取)')
  assert.match(submit, /forbidden\("仅真实核销到店记录可提交安全感评分"\)/, '资格由后端判定')
  assert.match(submit, /requireText\(request\.getReason\(\), 1000, "安全评分理由不能为空"\)/, '原因必填且 ≤1000')
  const taxonomy = methodBody(impl, 'public Map<String, Object> getSafetyTaxonomy()', 900)
  assert.match(taxonomy, /result\.put\("levels", Arrays\.asList\(MerchantSafetyRating\.LEVEL_UNSAFE,\s*MerchantSafetyRating\.LEVEL_NEUTRAL, MerchantSafetyRating\.LEVEL_SAFE\)\)/)
  const levels = readRepo(SYS + 'domain/MerchantSafetyRating.java')
  for (const v of ['SAFE', 'NEUTRAL', 'UNSAFE']) assert.match(levels, new RegExp(`LEVEL_${v} = "${v}"`))
  // memberId -> mms_merchant.id 的换取路径
  const home = methodBody(readRepo(API + 'ApiMerchantController.java'), 'public AjaxResult publicHome(', 800)
  assert.match(home, /selectMmsMerchantByMemberId\(memberId\)/)
  assert.ok(fieldsOf(readRepo(SYS + 'domain/vo/PublicMerchantVO.java')).has('id'), 'PublicMerchantVO 需带 id')
})
