'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const repo = path.resolve(__dirname, '../../..');
const read = relative => fs.readFileSync(path.join(repo, relative), 'utf8');
const controllerPath = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java';
const applicationPath = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/service/registration/RegistrationRedemptionApplication.java';
function method(source, signature) {
  const start = source.indexOf(signature), end = source.indexOf('\n    }', start);
  assert.ok(start >= 0 && end > start, signature); return source.slice(start, end);
}
function fallbackContract(source) {
  for (const signature of ['public AjaxResult scanDynamicCode(Actor actorContext, String code)', 'public AjaxResult scanGroupMemberTicket(Actor actorContext, String code, Long activityId)']) {
    assert.match(method(source, signature), /if \(!result.valid\) \{\s*AjaxResult original = replayOriginalSeat\([\s\S]*?if \(original != null\) return original;\s*return AjaxResult.error\(result.reason/, signature);
  }
  const scan = method(source, 'public AjaxResult scanQrCode(Actor actorContext, String type, String code, Long activityId)');
  assert.match(scan, /if \(ctx.error != null\) \{\s*AjaxResult original = replayOriginalSeat\([\s\S]*?if \(original != null\) return original;\s*return ctx.error;/);
  assert.match(scan, /if \(tooEarly != null\) \{\s*AjaxResult original = replayOriginalSeat\([\s\S]*?if \(original != null\) return original;\s*return tooEarly;/);
  assert.match(scan, /catch \(ServiceException e\) \{\s*AjaxResult original = replayOriginalSeat\([\s\S]*?if \(original != null\) return original;\s*return new AjaxResult\(e.getCode\(\) == null \? com\.chengyinhub\.common\.constant\.HttpStatus\.ERROR : e.getCode\(\), e.getMessage\(\)\)/);
}
// Java execution belongs to controlled CI. These checks prove source wiring only, not HTTP/SQL authorization.
test('seat terminal read is reachable before transport/payment/window refusal is returned', () => fallbackContract(read(applicationPath)));

test('terminal fallback obtains current actor and verifier-only typed read, never a buyer self-read or economic command', () => {
  const body = method(read(applicationPath), 'private AjaxResult replayOriginalSeat(');
  assert.ok(body.includes('actor = actorContext.currentMemberId();'));
  assert.ok(body.indexOf('actor = actorContext.currentMemberId();') < body.indexOf('registrationSeatService.verifierSeatReceipt('));
  assert.match(body, /if \(actor == null \|\| actor <= 0\) return AjaxResult.error\("请先登录"\)/);
  assert.match(body, /if \(type != null && !type.equals\(activityTicket \? "activity" : "topic"\)\) return null/);
  assert.match(body, /if \(\(requireActivity \|\| !activityTicket\) && \(activityId == null \|\| activityId <= 0\)\) return null/);
  assert.match(body, /verifierSeatReceipt\(identity.regId, seatNo, activityId, actor, activityTicket\)/);
  assert.match(body, /receipt == null \? null : AjaxResult.success/);
  assert.match(body, /new AjaxResult\(520,/);
  assert.doesNotMatch(body, /\.seatReceipt\(|\.redeemActivitySeat\(|\.redeemGroupSeat\(|applyOriginalSeatRewards\(|\.award\(/);
});
test('original default verifier enforces actual expiry; signature-only path is explicit and only used for receipt/routing', () => {
  const util = read('chengyinhub-common/src/main/java/com/chengyinhub/common/utils/VerifyDynCodeUtil.java');
  assert.match(method(util, 'public static Result verify(String code, long nowMs, String secret)'), /return verify\(code, nowMs, secret, true\)/);
  assert.match(method(util, 'public static Result verifyForReceipt(String code, String secret)'), /return verify\(code, 0L, secret, false\)/);
  assert.match(util, /if \(enforceExpiry && nowMs > exp\)/);
  assert.match(util, /constEquals\(hmac\(payload, secret\), p\[5\]\)/);
});
test('verifier terminal read uses original writable locking transaction and the same authorization algorithm without execute', () => {
  const service = read('chengyinhub-system/src/main/java/com/chengyinhub/business/service/RegistrationSeatService.java');
  const signature = 'public Receipt verifierSeatReceipt(';
  assert.match(method(service, signature), /return redeemSeat\(registrationId, seatNo, activityId, actor, activityTicket, false\)/);
  assert.match(service.slice(service.indexOf(signature) - 100, service.indexOf(signature)), /@Transactional\(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class\)/);
  const implementation = method(service, 'private Receipt redeemSeat(');
  assert.ok(implementation.indexOf('merchants.require(') < implementation.indexOf('if (verified(seat))'));
  assert.ok(implementation.indexOf('lockClubOwner(') < implementation.indexOf('if (verified(seat))'));
  assert.ok(implementation.indexOf('if (!execute) return null;') < implementation.indexOf('requirePaid(current)'));
  assert.match(implementation, /activityTicket && requestedActivityId != null && !requestedActivityId.equals\(activityId\)/);
});
test('explicit topic seats route to existing group entry, preserving its normal TTL and avoiding legacy whole-order execution', () => {
  const source = read(applicationPath), scan = method(source, 'public AjaxResult scanQrCode(Actor actorContext, String type, String code, Long activityId)');
  const topic = scan.slice(scan.indexOf('} else if (type.equals("topic"))'));
  assert.ok(topic.indexOf('return scanGroupMemberTicket(actorContext, code, activityId);') < topic.indexOf('CmsRegistration cmsRegistration = resolveRegistrationByScanCode(code);'));
  assert.match(topic, /seatIdentity.valid && seatIdentity.type.startsWith\("topic_seat_"\)/);
  assert.match(method(source, 'public AjaxResult scanGroupMemberTicket(Actor actorContext, String code, Long activityId)'), /VerifyDynCodeUtil.verify\(code, System.currentTimeMillis\(\), dynSecret\)/);
});
test('negative control: deleting any refused-state fallback is detected without weakening original refusal', () => {
  const original = read(applicationPath);
  const calls = [...original.matchAll(/AjaxResult original = replayOriginalSeat\([^\n]*\);/g)];
  assert.equal(calls.length, 5, 'all five refusal-return sites must remain enumerated');
  for (const call of calls) {
    const changed = original.slice(0, call.index) + 'AjaxResult original = null;' + original.slice(call.index + call[0].length);
    assert.notEqual(changed, original); assert.throws(() => fallbackContract(changed), assert.AssertionError);
  }
});

function actorAdapters(source) {
  const routes = {
    scanQrCodeChapter: ['String code, Long chapterId', 'code, chapterId'],
    scanQrCodeStation: ['String code, Long registrationMerchantId', 'code, registrationMerchantId'],
    scanDynamicCode: ['String code', 'code'],
    scanGroupMemberTicket: ['String code, Long activityId', 'code, activityId'],
    scanQrCode: ['String type, String code, Long activityId', 'type, code, activityId'],
  };
  for (const [name, [parameters, args]] of Object.entries(routes)) {
    const body = method(source, 'public AjaxResult ' + name + '(' + parameters + ')');
    assert.ok(body.includes('return registrationRedemptionApplication.' + name + '(redemptionActor(), ' + args + ');'), name);
    assert.doesNotMatch(body, /selectCms|verifyAndSettle|redeemActivitySeat|redeemGroupSeat|\.award\(/);
  }
  const capture = method(source, 'Actor redemptionActor()');
  assert.ok(capture.includes('RegistrationRedemptionApplication.Actor(getAppUserId(), false)'));
  assert.ok(capture.includes('RegistrationRedemptionApplication.Actor(null, true)'));
  assert.match(capture, /catch \(Exception unauthenticated\)/);
}
test('all five real HTTP scan adapters capture the authenticated actor and preserve exact inputs', () => actorAdapters(read(controllerPath)));
test('negative control: replacing any scan adapter actor is detected', () => {
  const source = read(controllerPath);
  const calls = [...source.matchAll(/registrationRedemptionApplication\.\w+\(redemptionActor\(\), /g)];
  assert.equal(calls.length, 5);
  for (const call of calls) {
    const changed = source.slice(0, call.index) + call[0].replace('redemptionActor()', '0L') + source.slice(call.index + call[0].length);
    assert.throws(() => actorAdapters(changed), assert.AssertionError);
  }
});
test('application owns scan orchestration without opening a new transaction or calling HTTP/authentication code', () => {
  const source = read(applicationPath).replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, '');
  assert.doesNotMatch(source, /@Transactional|extends BaseController|ApiRegistrationController|getAppUserId|Supplier<|UUID|new RegistrationSeatService/);
  assert.match(source, /private boolean hasEffectiveMerchantCoopOrder[\s\S]*RegistrationQueryApplication\.hasEffectiveMerchantCoopOrder\(\s*coopOrderMapper, topicId, memberId\)/);
});
