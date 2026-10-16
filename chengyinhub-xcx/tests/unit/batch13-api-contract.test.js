'use strict'
// BL-146 第十三批(BL-140..142)新接的接口合同:路径/方法/入参/返回,逐条对照 Java 源码。
// 消费方:#183(BL-140 解散阻断回读)、#184(BL-141 营业状态写后回读);BL-142 BLOCKED(#185,与现有列表等价)无新调用。
// 页面处理器单测随各卡交付:club-dissolution-blockers-readback.test.js(#183)、
// merchant-decor-editor-state-contract / merchant-console-identity-gate 新增用例(#184)。
// 本文件只读后端源码,不依赖上述分支的前端改动,可先于它们合并。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const REPO = path.resolve(ROOT, '..')
const API = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/'
const DOMAIN = 'chengyinhub-system/src/main/java/com/chengyinhub/business/domain/'
const readRepo = (file) => fs.readFileSync(path.join(REPO, file), 'utf8')

function block(src, marker, len) {
  const at = src.indexOf(marker)
  assert.ok(at >= 0, '应有 ' + marker)
  const rest = src.slice(at, at + (len || 2400))
  const end = rest.indexOf('\n    }\n')
  return end > 0 ? rest.slice(0, end) : rest
}

test('BL-140 POST /api/club/dissolution-blockers:JSON {id},仅主理人,返回 ClubDissolutionBlockers', () => {
  const src = readRepo(API + 'ApiClubController.java')
  assert.ok(src.includes('@RequestMapping("/api/club")'))
  const body = block(src, '@PostMapping("/dissolution-blockers")')
  assert.match(body, /public AjaxResult dissolutionBlockers\(@RequestBody Club body\)/, '是 JSON body,前端须带 Content-Type: application/json')
  assert.match(body, /body\.getId\(\) == null\) return error\("缺少俱乐部ID"\)/)
  assert.match(body, /AjaxResult\.success\(clubService\.selectDissolutionBlockers\(body\.getId\(\), ownerId\)\)/)
  assert.match(body, /catch \(IllegalArgumentException e\)[\s\S]*return error\(e\.getMessage\(\)\)/, '非主理人报错原话透出')
  const svc = readRepo('chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ClubServiceImpl.java')
  const impl = block(svc, 'public ClubDissolutionBlockers selectDissolutionBlockers(')
  assert.match(impl, /无权查看该俱乐部的解散阻断明细/)
  assert.match(impl, /setDeposits\(clubMapper\.selectPendingDepositsByClub\(clubId\)\)/)
  assert.match(impl, /setSettlements\(clubMapper\.selectUnpaidSettlementsByClub\(clubId\)\)/)
})

test('BL-140 回读字段:只有原始 deposits/settlements,没有 title/statusText/actionText/url 展示字段', () => {
  const blockers = readRepo(DOMAIN + 'ClubDissolutionBlockers.java')
  assert.match(blockers, /private List<ClubPendingDeposit> deposits = Collections\.emptyList\(\)/)
  assert.match(blockers, /private List<ClubPendingSettlement> settlements = Collections\.emptyList\(\)/)
  const deposit = readRepo(DOMAIN + 'ClubPendingDeposit.java')
  for (const f of ['Long id', 'Long topicId', 'Integer depositStatus', 'BigDecimal amount', 'boolean retryable']) {
    assert.ok(deposit.includes('private ' + f + ';'), 'ClubPendingDeposit 应有 ' + f)
  }
  assert.match(deposit, /public boolean isRetryable\(\)/, 'Jackson 序列化为 retryable')
  const settlement = readRepo(DOMAIN + 'ClubPendingSettlement.java')
  for (const f of ['Long id', 'Long topicId', 'BigDecimal amount', 'String direction']) {
    assert.ok(settlement.includes('private ' + f + ';'), 'ClubPendingSettlement 应有 ' + f)
  }
  for (const src of [deposit, settlement, blockers]) {
    assert.doesNotMatch(src, /statusText|actionText|metaText|private String url/, '展示字段只在 /dissolve 报错 actionItems 里')
  }
})

test('BL-140 阻断行 url 形如 dissolution-blockers/index?clubId=&type=deposit|settlement&id=,id 与回读同源', () => {
  const body = block(readRepo(API + 'ApiClubController.java'), '@PostMapping("/dissolve")', 6000)
  assert.match(body, /"\/pages\/club\/dissolution-blockers\/index\?clubId=" \+ body\.getId\(\)\s*\+ "&type=deposit&id=" \+ deposit\.getId\(\)/)
  assert.match(body, /"\/pages\/club\/dissolution-blockers\/index\?clubId=" \+ body\.getId\(\)\s*\+ "&type=settlement&id=" \+ settlement\.getId\(\)/)
  assert.match(body, /if \(historical && !deposit\.isRetryable\(\)\) action\.put\("url", "\/pages\/shezhi\/about\/index"\)/, '历史不可重试行无 type+id,回读时须原样保留')
})

test('BL-141 POST /api/merchant/business-status:无入参,BASIC_READ,返回 businessStatus(0/1)+businessStatusText', () => {
  const src = readRepo(API + 'ApiMerchantController.java')
  assert.ok(src.includes('@RequestMapping("/api/merchant")'))
  const body = block(src, '@PostMapping("/business-status")\n')
  assert.match(body, /public AjaxResult businessStatus\(\)/)
  assert.match(body, /MerchantPermission\.BASIC_READ/)
  assert.match(body, /int bs = m\.getBusinessStatus\(\) == null \? 1 : m\.getBusinessStatus\(\)/, '空值按营业中')
  assert.match(body, /d\.put\("businessStatus", bs\)/)
  assert.match(body, /d\.put\("businessStatusText", bs == 1 \? "营业中" : "已打烊"\)/)
  assert.match(body, /return success\(d\)/)
})

test('BL-141 POST /api/merchant/business-status/update:表单参数 business_status,成功只回文案不回状态(所以要回读)', () => {
  const body = block(readRepo(API + 'ApiMerchantController.java'), '@PostMapping("/business-status/update")')
  assert.match(body, /public AjaxResult businessStatusUpdate\(String business_status\)/, '不是 @RequestBody:data 平铺 business_status')
  assert.match(body, /MerchantPermission\.PROFILE_WRITE/)
  assert.match(body, /if \(bs != 0 && bs != 1\) return error\("营业状态参数错误"\)/)
  assert.match(body, /return success\(bs == 1 \? "已营业" : "已打烊"\)/)
  assert.doesNotMatch(body, /put\("businessStatus"/, '写接口不回读状态')
})

test('BL-142 BLOCKED:POST /api/coop/candidates 三组与现有按主题筛选列表一一对应(无新调用)', () => {
  const src = readRepo(API + 'ApiCoopController.java')
  const body = block(src, '@PostMapping("/candidates")\n', 7000)
  assert.match(body, /public AjaxResult candidates\(@RequestBody CoopInvite query\)/)
  assert.match(body, /仅主题发布者可查看候选池/)
  assert.match(body, /d\.put\("invites", invites\)/)
  assert.match(body, /d\.put\("registrations", registrationRows\)/)
  assert.match(body, /d\.put\("clubApplies", clubApplies\)/)
  const received = block(src, '@PostMapping("/candidates/received")', 4000)
  assert.match(received, /registrationRows\(registrations\)/, '报名行与 /candidates 同一装配')
  assert.match(received, /row\.put\("topicId", topic\.getId\(\)\)/, '每行带 topicId,前端可按主题筛选')
})
