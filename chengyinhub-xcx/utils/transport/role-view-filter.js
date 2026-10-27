// G-4(2026-10-27 p89/p95):玩家视角不请求商家工作台接口、不展示商家身份报错。纯函数,视角由 request-client 注入。
// ① 下表接口玩家视角直接不发,按 403 静默失败;共用接口(coop/list、access/me、营销同意、公开主页…)不在表里。
// ② 任何接口在玩家视角回商家身份 403:清 msg、不 toast,打 roleViewSuppressed,页面按「没有商家数据」处理。
var MERCHANT_ONLY_ENDPOINTS = [
  /^\/api\/merchant\/predict\//,
  /^\/api\/merchant\/relation-home(?:\?|$)/,
  /^\/api\/merchant\/operators(?:\/|\?|$)/,
  /^\/api\/merchant\/dashboard(?:\?|$)/,
  /^\/api\/coop\/mybiz(?:\?|$)/
];
var MERCHANT_DENIAL = /商家(?:身份|申请归属)|商户身份/;

function isMerchantOnlyUrl(url) {
  var path = String(url || '');
  return MERCHANT_ONLY_ENDPOINTS.some(function (pattern) { return pattern.test(path); });
}

function isMerchantDenial(body, statusCode) {
  if (!body || typeof body !== 'object') return false;
  return (statusCode == 403 || body.code == 403) && MERCHANT_DENIAL.test(String(body.msg || body.message || ''));
}

function suppress(body) {
  if (!body || typeof body !== 'object') return body;
  if (typeof body.msg === 'string') body.msg = '';
  if (typeof body.message === 'string') body.message = '';
  body.roleViewSuppressed = true;
  return body;
}

function blockedBody() { return { code: 403, msg: '', data: null, roleViewSuppressed: true }; }

function isSuppressed(res) { return !!(res && typeof res === 'object' && res.roleViewSuppressed === true); }

module.exports = {
  MERCHANT_ONLY_ENDPOINTS: MERCHANT_ONLY_ENDPOINTS,
  isMerchantOnlyUrl: isMerchantOnlyUrl,
  isMerchantDenial: isMerchantDenial,
  suppress: suppress,
  blockedBody: blockedBody,
  isSuppressed: isSuppressed
};
