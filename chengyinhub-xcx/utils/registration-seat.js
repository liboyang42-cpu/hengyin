'use strict';

const record = value => value && typeof value === 'object' && !Array.isArray(value);
const id = value => (typeof value === 'string' || typeof value === 'number') && /^[1-9]\d*$/.test(String(value))
  && (typeof value !== 'number' || Number.isSafeInteger(value));
const seatNumber = value => id(value) && Number.isSafeInteger(Number(value)) && Number(value) <= 2147483647;

// 只提取关联身份；签名、归属、支付和核销授权仍全部由服务端判断。
function parseSeatCode(code) {
  const parts = String(code || '').split('.');
  const match = /^(activity|topic)_seat_([1-9]\d*)$/.exec(parts[2] || '');
  return parts.length === 6 && parts[0] === 'v1' && id(parts[1]) && match && seatNumber(match[2])
    ? { registrationId: parts[1], seatNo: Number(match[2]), kind: match[1] } : null;
}
function seatTarget(request) {
  if (!request || !['/api/registration/scan_dynamic_code', '/api/registration/scan_group_member_ticket'].includes(request.url)) return null;
  const data = request.data || {}, parsed = parseSeatCode(data.code);
  if (!parsed || parsed.kind === 'topic' && request.url !== '/api/registration/scan_group_member_ticket') return null;
  const target = { registrationId: parsed.registrationId, seatNo: parsed.seatNo };
  if (request.url === '/api/registration/scan_group_member_ticket') {
    if (!id(data.activityId)) return null;
    target.activityId = String(data.activityId);
  }
  return target;
}
function validTarget(value) {
  return record(value) && id(value.registrationId) && seatNumber(value.seatNo)
    && (value.activityId === undefined || id(value.activityId))
    && Object.keys(value).every(key => ['registrationId', 'seatNo', 'activityId'].includes(key));
}
function sameTarget(a, b) {
  return validTarget(a) && validTarget(b) && String(a.registrationId) === String(b.registrationId)
    && Number(a.seatNo) === Number(b.seatNo) && String(a.activityId || '') === String(b.activityId || '');
}
// 不带二维码 nonce/过期时间，也不因换场次绕开原席位 UNKNOWN。
function seatKey(owner, target) { return String(owner) + ':seat:' + target.registrationId + ':' + target.seatNo; }
function validSeatReceipt(data, target) {
  return validTarget(target) && record(data) && ['seatId', 'registrationId', 'activityId', 'verifierMemberId'].every(key => id(data[key]))
    && String(data.registrationId) === String(target.registrationId) && Number(data.seatNo) === Number(target.seatNo)
    && seatNumber(data.seatNo) && (target.activityId === undefined || String(data.activityId) === String(target.activityId))
    && (data.topicId == null || id(data.topicId)) && typeof data.replayed === 'boolean'
    && (data.registrationFirstTime === undefined || typeof data.registrationFirstTime === 'boolean')
    && typeof data.verifiedAt === 'string' && Number.isFinite(Date.parse(data.verifiedAt.replace(' ', 'T')));
}
function captureLogin(app, storage) {
  try {
    const revision = app.getSession().getRevision();
    if (!Number.isSafeInteger(revision) || revision < 0) return null;
    return { revision, owner: String(app.getUserID && app.getUserID() || ''), authorization: app.getAuthorization && app.getAuthorization(),
      generation: String(app._featureFlagsEpoch || 0) + ':' + String(storage.getStorageSync && storage.getStorageSync('authorization_issued_at') || '') };
  } catch (error) { return null; }
}
function loginCurrent(app, storage, original) {
  const now = captureLogin(app, storage);
  return !!(original && original.owner && now && now.owner === original.owner
    && now.authorization === original.authorization && now.generation === original.generation && now.revision === original.revision);
}
function seatRecovery(app, target, isCurrent) {
  return { target, isCurrent, readReceipt(original, done) {
    if (!isCurrent()) return null;
    return app.sendRequest({ url: '/api/verify/seat/receipt', method: 'POST', data: Object.assign({}, original),
      hideLoading: true, silentError: true, autoErrorToast: false,
      success: response => done(response), fail: () => done(null), successStatusAbnormal: () => done(null) });
  } };
}
function seatChoice(payload, registrationId) {
  return record(payload) && payload.requiresSeatSelection === true && String(payload.registrationId) === String(registrationId)
    && seatNumber(payload.seatCount) && Number(payload.seatCount) > 1 && !payload.code && !payload.qrcodeUrl;
}
function issuedSeat(payload, registrationId, seatNo) {
  const parsed = parseSeatCode(payload && payload.code);
  return !!(parsed && record(payload) && id(payload.seatId) && String(payload.registrationId) === String(registrationId)
    && String(parsed.registrationId) === String(registrationId) && Number(parsed.seatNo) === Number(seatNo)
    && Number(payload.seatNo) === Number(seatNo) && [0, 1].includes(payload.seatStatus));
}
module.exports = { seatTarget, validTarget, sameTarget, seatKey, validSeatReceipt, captureLogin, loginCurrent, seatRecovery, seatChoice, issuedSeat, seatNumber };
