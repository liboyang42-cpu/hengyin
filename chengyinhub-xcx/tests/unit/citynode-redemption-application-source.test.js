'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const repo = path.resolve(__dirname, '../../..');
const read = relative => fs.readFileSync(path.join(repo, relative), 'utf8');
const controllerPath = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiVerifyController.java';
const ownerPath = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/service/verify/CityNodeRedemptionApplication.java';
function body(source, signature) {
  const start = source.indexOf(signature), end = source.indexOf('\n    }', start);
  assert.ok(start >= 0 && end > start, signature); return source.slice(start, end);
}
function boundary(controller, owner) {
  assert.doesNotMatch(controller, /@Transactional/);
  assert.match(body(controller, 'public AjaxResult redeemCityNodeCode(String code)'), /\{\s*return cityNodeRedemptionApplication.redeemCityNodeCode\(appUid\(\), code\);\s*$/);
  assert.match(owner, /@Transactional\(rollbackFor = Exception.class\)\s+public AjaxResult redeemCityNodeCode\(long principalMemberId, String code\)/);
  assert.doesNotMatch(owner, /readOnly\s*=\s*true|REQUIRES_NEW|@Lazy|ApiVerifyController|extends BaseController|getAppUserId|Supplier|Function</);
}
function sequence(owner) {
  const command = body(owner, 'public AjaxResult redeemCityNodeCode(long principalMemberId, String code)');
  let previous = -1;
  for (const step of ['if (principalMemberId <= 0)', 'merchantAccessService.require(', 'VerifyDynCodeUtil.verify(',
    'roamMapper.lockClaimOwner(playerId)', 'roamMapper.selectMerchantNodeDetail(poiId)', 'poi.getMerchantId().equals(access.getMerchantId())',
    'roamMapper.selectPendingCityNodeCouponId(poiId, playerId)', 'roamMapper.redeemCityNodeReward(poiId, playerId)',
    'couponHistoryService.isCouponNotIssuable(couponId)', 'couponHistoryService.grantCouponById(playerId, couponId)',
    'businessAuditEventMapper.insertBusinessAuditEvent(audit)', 'return AjaxResult.success(']) {
    const next = command.indexOf(step); assert.ok(next > previous, step); previous = next;
  }
  assert.match(command, /throw new ServiceException\("券库存不足,核销失败"\)/);
  assert.match(command, /throw new IllegalStateException\("据点核销审计写入失败"\)/);
}
// Source wiring checks only; Java/Spring/MySQL execution is intentionally left to controlled CI.
test('city-node command has one external application proxy transaction entry and a thin actor adapter', () => boundary(read(controllerPath), read(ownerPath)));
test('current permission, signed scope, original lock/CAS/coupon/audit sequence is retained', () => sequence(read(ownerPath)));
test('original identifiers, coupon snapshot and response schema stay unchanged without a second intent system', () => {
  const owner = read(ownerPath);
  for (const text of ['audit.setOwnerMemberId(access.getOwnerMemberId())', 'audit.setActorMemberId(principalMemberId)',
    'audit.setActorRoleCode(access.getRoleCode())', '"citynode-redeem-" + poiId + "-" + playerId',
    'data.put("memberId", playerId)', 'data.put("poiId", poiId)', 'data.put("couponId", couponId)', 'data.put("couponGranted", !couponSkipped)']) assert.ok(owner.includes(text), text);
  assert.doesNotMatch(body(owner, 'public AjaxResult redeemCityNodeCode(long principalMemberId, String code)'), /verifyForReceipt/);
  assert.doesNotMatch(owner, /new AjaxResult\(200|UUID|intentNotExecuted|selectCurrentTemplate/);
});
test('both real MySQL fixtures put TransactionInterceptor on the application and inject it into the plain controller', () => {
  const equity = read('chengyinhub-admin/src/test/java/com/chengyinhub/business/service/RegistrationEquityMybatisIntegrationTest.java');
  assert.match(equity, /ProxyFactory factory = new ProxyFactory\(application\)/);
  assert.match(equity, /setField\(target, "cityNodeRedemptionApplication", \(CityNodeRedemptionApplication\) factory.getProxy\(\)\)/);
  assert.match(equity, /ApiVerifyController controller = target/);
  const roam = body(read('chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/RoamLocationRetentionMySqlIT.java'), 'private ApiVerifyController verifyController(RoamMapper mapper,boolean failAudit)');
  assert.match(roam, /ProxyFactory proxy=new ProxyFactory\(application\)/);
  assert.match(roam, /new TransactionInterceptor\(new DataSourceTransactionManager\(source\),new AnnotationTransactionAttributeSource\(\)\)\)/);
  assert.match(roam, /setField\(target,"cityNodeRedemptionApplication",\(CityNodeRedemptionApplication\)proxy.getProxy\(\)\)/);
  assert.match(roam, /return target;/);
});
test('negative controls: removing the transactional owner or original permission is detected', () => {
  const controller = read(controllerPath), owner = read(ownerPath);
  const noTransaction = owner.replace('@Transactional(rollbackFor = Exception.class)', '');
  assert.notEqual(noTransaction, owner); assert.throws(() => boundary(controller, noTransaction), assert.AssertionError);
  const noPermission = owner.replace('merchantAccessService.require(', 'unapprovedContext(');
  assert.notEqual(noPermission, owner); assert.throws(() => sequence(noPermission), assert.AssertionError);
});

test('issuance shares CityNode ownership and signing configuration without an issuance transaction', () => {
  const controller = read(controllerPath), owner = read(ownerPath);
  assert.match(body(controller, 'public AjaxResult issueCityNodeCode(Long poiId)'), /return cityNodeRedemptionApplication.issueCityNodeCode\(appUid\(\), poiId\);/);
  assert.doesNotMatch(controller, /private (?:RoamMapper|String dynSecret)|qrcodeService|VerifyDynCodeUtil/);
  assert.doesNotMatch(owner, /@Transactional[^;{}]*public AjaxResult issueCityNodeCode/);
  const issuance = body(owner, 'public AjaxResult issueCityNodeCode(long memberId, Long poiId)');
  let previous = -1;
  for (const step of ['memberId <= 0', 'poiId == null || poiId <= 0', 'selectMerchantNodeDetail(poiId)', 'selectPlayerMerchantNodeDetail(poiId, 0L)',
    'selectPendingCityNodeCouponId(poiId, memberId)', 'VerifyDynCodeUtil.issue(poiId, "citynode_" + memberId, now, DYN_TTL_MS, nonce, dynSecret)', 'qrcodeService.generateInlineQrcode(code, 300, 300)', 'AjaxResult.success(data)']) {
    const next = issuance.indexOf(step); assert.ok(next > previous, step); previous = next;
  }
});

function receiptBoundary(owner) {
  const receipt = body(owner, 'public AjaxResult cityNodeReceipt(long principalMemberId, String code)');
  let previous = -1;
  for (const step of ['principalMemberId <= 0', 'merchantAccessService.require(principalMemberId, MerchantPermission.VERIFY)',
    'VerifyDynCodeUtil.verifyForReceipt(code, dynSecret)', 'roamMapper.lockClaimOwner(playerId)', 'roamMapper.selectMerchantNodeDetail(signed.regId)',
    'poi.getMerchantId().equals(access.getMerchantId())', 'businessAuditEventMapper.selectByDomainActionRequestForUpdate(', 'JSON.parseObject(original.getAfterJson())']) {
    const next = receipt.indexOf(step); assert.ok(next > previous, step); previous = next;
  }
  assert.doesNotMatch(receipt, /redeemCityNodeReward|grantCoupon|insertBusinessAudit|isCouponNotIssuable|selectPendingCityNodeCoupon/);
  for (const fact of ['getOwnerMemberId()', 'original.getRelationId()', 'receiptVersion', 'integralId(result.get("memberId"), playerId)',
    'integralId(result.get("poiId"), signed.regId)', 'instanceof Boolean', 'message.equals(frozen.get("message"))']) assert.ok(receipt.includes(fact), fact);
  assert.match(owner, /audit.setAfterJson\(JSON.toJSONString\(Map.of\("status", 1, "receiptVersion", 1, "data", data, "message", message\)\)\)/);
}
test('original receipt read authorizes and locks the signed scope before projecting the atomic saved result', () => {
  receiptBoundary(read(ownerPath));
  assert.match(body(read(controllerPath), 'public AjaxResult cityNodeReceipt(String code)'), /return cityNodeRedemptionApplication.cityNodeReceipt\(appUid\(\), code\);/);
});
test('negative controls: dropping receipt scope or using current coupon state cannot satisfy the recovery contract', () => {
  const owner = read(ownerPath);
  const noScope = owner.replace('poi.getMerchantId().equals(access.getMerchantId())', 'true').replace('poi.getMerchantId().equals(access.getMerchantId())', 'true');
  assert.notEqual(noScope, owner); assert.throws(() => receiptBoundary(noScope), assert.AssertionError);
  const guessed = owner.replace('JSON.parseObject(original.getAfterJson())', 'currentCouponState()');
  assert.throws(() => receiptBoundary(guessed), assert.AssertionError);
});
