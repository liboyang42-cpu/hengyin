'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const repo = path.resolve(__dirname, '../../..');
const read = p => fs.readFileSync(path.join(repo, p), 'utf8');
const controllerPath = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiVerifyController.java';
const applicationPath = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/service/verify/RegistrationTicketApplication.java';
function method(source, signature) {
  const start = source.indexOf(signature), end = source.indexOf('\n    }', start);
  assert.ok(start >= 0 && end > start, signature); return source.slice(start, end);
}
function actorDelegation(source) {
  assert.match(method(source, 'public AjaxResult issueDynCode(Long registrationId, Integer seatNo)'), /\{\s*return registrationTicketApplication.issueDynCode\(appUid\(\), registrationId, seatNo\);\s*$/);
  assert.match(method(source, 'public AjaxResult seatReceipt(Long registrationId, Integer seatNo, Long activityId)'), /\{\s*return registrationTicketApplication.seatReceipt\(appUid\(\), registrationId, seatNo, activityId\);\s*$/);
  assert.match(source, /public AjaxResult issueDynCode\(Long registrationId\) \{ return issueDynCode\(registrationId, null\); \}/);
}
function originalGateOrder(source) {
  const body = method(source, 'public AjaxResult issueDynCode(Long actorMemberId, Long registrationId, Integer seatNo)');
  const ordered = ['memberId == null || memberId <= 0', 'if (registrationId == null)', 'registrationService.selectCmsRegistrationById(registrationId)',
    'OwnershipUtils.notOwner(reg.getMemberId(), memberId)', 'Integer regStatus = reg.getRegistrationStatus()',
    'if (multiTopicSeat && seatNo == null)', 'registrationSeatService.prepareOwnedSeat(registrationId, memberId, seatNo)',
    'perkLedgerService.isTicketFullyConsumed(', 'activityService.selectCmsActivityById(', 'VerifyDynCodeUtil.issue(', 'qrcodeService.generateInlineQrcode('];
  let previous = -1;
  for (const gate of ordered) { const next = body.indexOf(gate); assert.ok(next > previous, gate); previous = next; }
}
// Source wiring only. Behavioral/transaction/JWT verification remains with controlled Java execution.
test('ticket HTTP and Java compatibility entrances delegate the real actor and unchanged target to the actual application owner', () => actorDelegation(read(controllerPath)));
test('whole issuance admission/preparation/consumption/activity/sign/PNG order has exactly one application owner', () => {
  originalGateOrder(read(applicationPath));
  const source = read(controllerPath);
  assert.doesNotMatch(source, /private (?:ICmsRegistrationService|ICmsActivityService|.*RegistrationSeatService|.*IPerkLedgerService) /);
  assert.doesNotMatch(method(source, 'public AjaxResult issueDynCode(Long registrationId, Integer seatNo)'), /select|prepare|issue\(|new HashMap|isTicketFullyConsumed/);
});
test('receipt projection retains the same server authorization owner, exact UNKNOWN response and raw terminal receipt', () => {
  const body = method(read(applicationPath), 'public AjaxResult seatReceipt(Long actorMemberId, Long registrationId, Integer seatNo, Long activityId)');
  assert.match(body, /registrationSeatService.seatReceipt\(registrationId, seatNo, activityId, actorMemberId\)/);
  assert.match(body, /receipt == null \? new AjaxResult\(520, "原席位核销结果待确认，请勿改选其他席位重发"\)/);
  assert.match(body, /AjaxResult.success\("该席位已核销，请勿重复提供权益", receipt\)/);
  assert.doesNotMatch(body, /prepareOwnedSeat|redeemGroupSeat|redeemActivitySeat|UUID|new Receipt/);
});
test('application owns no HTTP context, callback to controller, optional dependency or expanded transaction', () => {
  const source = read(applicationPath);
  assert.match(source, /@Service\s+public class RegistrationTicketApplication/);
  assert.doesNotMatch(source, /extends BaseController|ApiVerifyController|getAppUserId|HttpServlet|@Transactional|TransactionTemplate|@Lazy|required\s*=\s*false/);
  assert.equal((source.match(/private final (?:ICmsRegistrationService|RegistrationSeatService|ICmsActivityService|IPerkLedgerService|QrcodeServiceImpl) /g) || []).length, 5);
  assert.match(read('chengyinhub-admin/src/main/java/com/chengyinhub/web/service/verify/CityNodeRedemptionApplication.java'),
    /@Transactional\(rollbackFor = Exception.class\)\s+public AjaxResult redeemCityNodeCode/);
});
test('the original five-minute TTL and signing configuration have equal local values and keep local PNG generation', () => {
  const source = read(applicationPath);
  const cityNode = read('chengyinhub-admin/src/main/java/com/chengyinhub/web/service/verify/CityNodeRedemptionApplication.java');
  const ticketTtl = source.match(/\bpublic static final long DYN_TTL_MS\s*=\s*([0-9][0-9_]*)L\s*;/);
  const cityNodeTtl = cityNode.match(/\bprivate static final long DYN_TTL_MS\s*=\s*([0-9][0-9_]*)L\s*;/);
  assert.ok(ticketTtl, 'ticket owner must declare its actual numeric TTL');
  assert.ok(cityNodeTtl, 'city-node owner must declare its actual numeric TTL');
  const ticketMs = Number(ticketTtl[1].replace(/_/g, ''));
  const cityNodeMs = Number(cityNodeTtl[1].replace(/_/g, ''));
  assert.equal(ticketMs, 300000);
  assert.equal(cityNodeMs, 300000);
  assert.equal(ticketMs, cityNodeMs);
  assert.doesNotMatch(cityNode, /\bRegistrationTicketApplication\b/);
  for (const owner of [source, cityNode]) {
    assert.match(owner, /data.put\("ttlMs", DYN_TTL_MS\)/);
    assert.match(owner, /data.put\("expiresAt", now \+ DYN_TTL_MS\)/);
  }
  assert.match(source, /@Value\("\$\{verify.dyncode-secret:\$\{token.secret\}\}"\) String dynSecret/);
  assert.match(source, /VerifyDynCodeUtil.issue\(reg.getId\(\), type, now, DYN_TTL_MS, nonce, dynSecret\)/);
  assert.match(source, /qrcodeService.generateInlineQrcode\(code, 300, 300\)/);
  assert.doesNotMatch(source, /qrcodeService.generateQrcode\(/);
});
test('negative controls: replacing actor delegation or removing the real ownership gate fails the boundary contract', () => {
  const controller = read(controllerPath), app = read(applicationPath);
  const wrongActor = controller.replace('issueDynCode(appUid(), registrationId, seatNo)', 'issueDynCode(registrationId, registrationId, seatNo)');
  assert.notEqual(wrongActor, controller); assert.throws(() => actorDelegation(wrongActor), assert.AssertionError);
  const noOwner = app.replace('OwnershipUtils.notOwner(reg.getMemberId(), memberId)', 'false');
  assert.notEqual(noOwner, app); assert.throws(() => originalGateOrder(noOwner), assert.AssertionError);
});
