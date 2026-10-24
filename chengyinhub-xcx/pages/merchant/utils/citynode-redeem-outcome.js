'use strict';

// PRIVATE-GROUP-nucleation-original-request:据点核销(/api/verify/citynode/redeem)的回执分类。
// 与 merchant-verification.js 同一口径——只有能绑定到原请求的明确回执才算 success/failed,其余一律 unknown。
const UNKNOWN_MESSAGE = '核销结果待确认，请勿重复核销';
const { isBizOk } = require('./response-shape.js');
const record = value => value && typeof value === 'object' && !Array.isArray(value);
const positiveId = value => (typeof value === 'number' || typeof value === 'string')
  && /^[1-9]\d*$/.test(String(value)) && Number.isSafeInteger(Number(value));

// 据点核销码 v1.{poiId}.citynode_{memberId}.{exp}.{nonce}.{sig}
function cityNodeTarget(code) {
  const parts = String(code || '').split('.');
  const match = parts.length === 6 && parts[0] === 'v1' && /^citynode_([1-9]\d*)$/.exec(parts[2]);
  return match && positiveId(parts[1]) && positiveId(match[1]) ? { poiId: parts[1], memberId: match[1] } : null;
}

// redeemCityNodeCode 整体 @Transactional:这些文案要么在任何写入之前返回,要么随 ServiceException 整笔回滚。
const NOT_REDEEMED = ['请先登录', '据点不存在', '无权核销该据点', '玩家未到店打卡,或该券已核销', '券库存不足,核销失败'];
// VerifyDynCodeUtil 验码阶段的后端原话(尚未碰库),给商家换成下一步能做的话
function codeRejected(msg) {
  return msg === '核销码无效' || msg === '空码' || msg === '格式错误' || msg === '签名不匹配' || msg === '参数错误'
    || msg === '已过期' || msg === '非据点核销码' || msg === '核销码格式错误';
}

function classifyCityNodeRedeem(response, channel, httpStatus, code) {
  const unknown = { status: 'unknown', response: { code: 202, msg: UNKNOWN_MESSAGE } };
  const status = Number(httpStatus);
  if (channel === 'fail' && httpStatus === 2 && record(response) && (response.code === 2 || response.code === '2'))
    return { status: 'failed', response: { code: 401, msg: '登录状态已失效，未执行核销' } };
  if ([400, 401, 403, 404, 405, 413, 415, 426, 429].includes(status))
    return { status: 'failed', response: { code: status, msg: '请求被拒绝，未执行核销' } };
  if (channel !== 'success' || httpStatus && status !== 200 || !record(response)) return unknown;
  if (isBizOk(response)) {
    const data = response.data, target = cityNodeTarget(code);
    // 回执须回到码里的同一据点与玩家;couponGranted 严格布尔且与成功文案一致
    const ok = !!target && record(data) && String(data.poiId) === target.poiId && String(data.memberId) === target.memberId
      && positiveId(data.poiId) && positiveId(data.memberId) && Number.isSafeInteger(data.couponId) && data.couponId >= 0
      && typeof data.couponGranted === 'boolean'
      && response.msg === (data.couponGranted ? '核销成功,券已发放玩家' : '核销成功,该券已失效或停发,未发放');
    return ok ? { status: 'success', response } : unknown;
  }
  if (response.code === 500 || response.code === '500') {
    if (NOT_REDEEMED.includes(response.msg)) return { status: 'failed', response };
    if (codeRejected(response.msg)) return { status: 'failed', response: { code: 500, msg: '核销码无效或已过期，请让玩家刷新后重扫' } };
  }
  return unknown;
}

module.exports = { cityNodeTarget, classifyCityNodeRedeem };
