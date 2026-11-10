'use strict';

/* 玩家闭环读写入口：只包一层 Promise，不改原请求客户端的登录、重试与错误处理。
 * 写请求的 requestId 由调用方生成并保留；超时只查原状态，不换号重发。 */

// 这些后端 POST 都是 @RequestBody JSON；请求客户端对 POST 默认表单编码，必须显式带 JSON 头。
const JSON_HEADER = { 'content-type': 'application/json' };

function call(app, url, method, data, silent) {
  return new Promise(resolve => {
    app.sendRequest({
      url, method, data, header: method === 'POST' ? JSON_HEADER : undefined,
      hideLoading: true, silentError: silent !== false, autoErrorToast: false,
      success: body => resolve(Object.assign({ httpStatus: 200 }, body || {})),
      successStatusAbnormal: (body, status) => resolve(Object.assign({}, body || {}, { httpStatus: status, code: (body && body.code) || status })),
      fail: (body, status) => resolve(status
        ? Object.assign({}, body || {}, { httpStatus: status, code: (body && body.code) || status })
        : { code: 'fail', netFail: true, msg: '网络异常，请重试' })
    });
  });
}

const ok = res => !!res && (res.code === 200 || res.code === '200');

function idQuery(params) {
  const query = {};
  Object.keys(params || {}).forEach(key => {
    const value = params[key];
    if (value !== undefined && value !== null && value !== '' && value !== '0' && value !== 0) query[key] = value;
  });
  return query;
}

module.exports = {
  ok,
  report: (app, p) => call(app, '/api/play/player-journey/report', 'GET', idQuery(p)),
  memory: (app, p) => call(app, '/api/play/player-journey/memory', 'GET', idQuery(p)),
  choices: (app, p) => call(app, '/api/play/player-journey/choices', 'GET', idQuery(p)),
  seats: (app, registrationId) => call(app, '/api/play/player-journey/seats', 'GET', { registrationId }),
  rank: (app, p) => call(app, '/api/rank/list', 'GET', idQuery(p)),
  teamDetail: (app, teamId) => call(app, '/api/orient/team/detail', 'GET', { teamId }),
  referral: (app, token) => call(app, '/api/referral/landing/referral', 'POST', { token }),
  gift: (app, token) => call(app, '/api/referral/landing/gift', 'POST', { token }),
  issueReferral: (app, targetType, targetId) => call(app, '/api/marketing/referral/token', 'POST', { targetType, targetId }, false),
  affiliate: (app) => call(app, '/api/marketing/affiliate/summary', 'GET', { limit: 20 }),
  seatInvite: (app, registrationId, requestId) => call(app, '/api/play/seat-invitations/invite', 'POST', { registrationId, requestId }, false),
  seatInvitation: (app, invitationId) => call(app, '/api/play/seat-invitations/' + invitationId, 'GET', {}),
  seatClaim: (app, token, requestId) => call(app, '/api/play/seat-invitations/claim', 'POST', { token, requestId }, false),
  seatCancel: (app, invitationId, requestId) => call(app, '/api/play/seat-invitations/' + invitationId + '/cancel', 'POST', { requestId }, false),
  giftClaim: (app, token, requestId) => call(app, '/api/marketing/product/gifts/claim', 'POST', { token, requestId }, false)
};
