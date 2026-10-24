'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const repo = path.resolve(__dirname, '../../..');
const read = relative => fs.readFileSync(path.join(repo, relative), 'utf8');
const controllerPath = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java';
function method(source, signature) {
  const start = source.indexOf(signature), end = source.indexOf('\n    }', start);
  assert.ok(start >= 0 && end > start, signature); return source.slice(start, end);
}
function fallbackContract(source) {
  for (const signature of ['public AjaxResult scanDynamicCode(String code)', 'public AjaxResult scanGroupMemberTicket(String code, Long activityId)']) {
    assert.match(method(source, signature), /if \(!result.valid\) \{\s*AjaxResult original = replayOriginalSeat\([\s\S]*?if \(original != null\) return original;\s*return AjaxResult.error\(result.reason/, signature);
  }
  const scan = method(source, 'public AjaxResult scanQrCode(String type, String code, Long activityId)');
  assert.match(scan, /if \(ctx.error != null\) \{\s*AjaxResult original = replayOriginalSeat\([\s\S]*?if \(original != null\) return original;\s*return ctx.error;/);
  assert.match(scan, /if \(tooEarly != null\) \{\s*AjaxResult original = replayOriginalSeat\([\s\S]*?if \(original != null\) return original;\s*return tooEarly;/);
  assert.match(scan, /catch \(ServiceException e\) \{\s*AjaxResult original = replayOriginalSeat\([\s\S]*?if \(original != null\) return original;\s*return new AjaxResult\(e.getCode\(\), e.getMessage\(\)\)/);
}
// Java execution belongs to controlled CI. These checks prove source wiring only, not HTTP/SQL authorization.
test('seat terminal read is reachable before transport/payment/window refusal is returned', () => fallbackContract(read(controllerPath)));

test('terminal fallback obtains current actor and verifier-only typed read, never a buyer self-read or economic command', () => {
  const body = method(read(controllerPath), 'private AjaxResult replayOriginalSeat(');
  assert.ok(body.indexOf('getAppUserId()') < body.indexOf('registrationSeatService.verifierSeatReceipt('));
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
  const source = read(controllerPath), scan = method(source, 'public AjaxResult scanQrCode(String type, String code, Long activityId)');
  const topic = scan.slice(scan.indexOf('} else if (type.equals("topic"))'));
  assert.ok(topic.indexOf('return scanGroupMemberTicket(code, activityId);') < topic.indexOf('CmsRegistration cmsRegistration = resolveRegistrationByScanCode(code);'));
  assert.match(topic, /seatIdentity.valid && seatIdentity.type.startsWith\("topic_seat_"\)/);
  assert.match(method(source, 'public AjaxResult scanGroupMemberTicket(String code, Long activityId)'), /VerifyDynCodeUtil.verify\(code, System.currentTimeMillis\(\), dynSecret\)/);
});
test('negative control: deleting any refused-state fallback is detected without weakening original refusal', () => {
  const original = read(controllerPath);
  const calls = [...original.matchAll(/AjaxResult original = replayOriginalSeat\([^\n]*\);/g)];
  assert.equal(calls.length, 5, 'all five refusal-return sites must remain enumerated');
  for (const call of calls) {
    const changed = original.slice(0, call.index) + 'AjaxResult original = null;' + original.slice(call.index + call[0].length);
    assert.notEqual(changed, original); assert.throws(() => fallbackContract(changed), assert.AssertionError);
  }
});
