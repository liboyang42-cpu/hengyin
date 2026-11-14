'use strict';
// 非现金奖励(券承载,无现金价值)。服务端为准:
//   GET  /api/rewards/noncash?limit=&cursor=          我的奖励分页 { items, nextCursor, asOf }(RewardWire)
//   GET  /api/rewards/noncash/subjects/{topicId}      该主题当前提供的奖励(catalog)
//   GET  /api/rewards/noncash/versions/{versionId}?branch=THEME&subjectId=   本人可否领取(offer;拒绝是 errorCode 数据)
//   POST /api/rewards/noncash/claims {requestId, versionId, branch, subjectId}   领取;同 requestId 重放
//   POST /api/rewards/noncash/claims/recover {requestId}                        回包丢失时按原 requestId 回读
// 领取开关由服务端策略决定(POLICY_CLOSED = 未开放),页面只在 offer 没有 errorCode 时显示领取按钮。

var PAGE_SIZE = 20;
var BRANCH = 'THEME';
var VERSION_ID = /^[A-Za-z0-9._:-]{1,128}$/;
var TOPIC_ID = /^[1-9][0-9]{0,18}$/;

var OFFER_BLOCK = {
  POLICY_CLOSED: '奖励领取暂未开放',
  STOCK_EXHAUSTED: '奖励已领完',
  PLEDGE_NOT_FROZEN: '奖励还在审核中',
  SUBJECT_NOT_OFFERED: '该门店已不再提供这个奖励',
  NO_AUTHORITATIVE_FACT: '完成这个主题后可领取',
  MINOR_CLOSED: '未成年人暂不可领取',
};

var VALIDITY = { UPCOMING: '未到使用时间', IN_WINDOW: '可使用', ELAPSED: '已过期' };
var VALIDITY_TONE = { UPCOMING: 'mono', IN_WINDOW: 'green', ELAPSED: 'done' };

function validTopicId(value) { return TOPIC_ID.test(String(value == null ? '' : value)); }
function validVersionId(value) { return typeof value === 'string' && VERSION_ID.test(value); }

function text(value) { return typeof value === 'string' ? value.trim() : ''; }

function ms(value) {
  var n = Number(value);
  return isFinite(n) && n > 0 ? n : null;
}

function dayText(value) {
  var t = ms(value);
  if (t === null) return '';
  var d = new Date(t);
  function p(n) { return n < 10 ? '0' + n : String(n); }
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

function windowText(start, end) {
  var a = dayText(start);
  var b = dayText(end);
  if (!a && !b) return '';
  return (a || '—') + ' 至 ' + (b || '—');
}

function offerBlockText(code) {
  if (!code) return '';
  return OFFER_BLOCK[code] || '暂不可领取';
}

/** catalog → 页面卡片;形状不对返回 null。 */
function normalizeCatalog(data) {
  if (!data || typeof data !== 'object' || !validVersionId(data.versionId)) return null;
  return {
    versionId: data.versionId,
    terms: text(data.terms),
    available: Number(data.available) > 0 ? Number(data.available) : 0,
    redeemText: windowText(data.redeemStart, data.redeemEnd),
    errorCode: text(data.errorCode),
  };
}

/** offer → { claimable, blockText, ... };服务端 errorCode 存在即不可领。 */
function normalizeOffer(data) {
  if (!data || typeof data !== 'object' || !validVersionId(data.versionId)) return null;
  var code = text(data.errorCode);
  return {
    versionId: data.versionId,
    terms: text(data.terms),
    available: Number(data.available) > 0 ? Number(data.available) : 0,
    serviceText: windowText(data.serviceStart, data.serviceEnd),
    redeemText: windowText(data.redeemStart, data.redeemEnd),
    errorCode: code,
    claimable: !code,
    blockText: offerBlockText(code),
  };
}

/** 领取/回读结果:有 awardId 即已领到;state=UNKNOWN 表示服务端还没落定,只能回读;其余带 errorCode 为拒绝。 */
function claimOutcome(data) {
  if (!data || typeof data !== 'object') return { kind: 'unknown' };
  if (typeof data.awardId === 'string' && data.awardId) return { kind: 'awarded', awardId: data.awardId };
  if (data.state === 'UNKNOWN') return { kind: 'unknown' };
  var code = text(data.errorCode);
  return { kind: 'refused', errorCode: code, text: offerBlockText(code) || '领取未成功' };
}

function normalizeReward(raw) {
  if (!raw || typeof raw !== 'object' || typeof raw.awardId !== 'string' || !raw.awardId) return null;
  var validity = text(raw.validityStatus);
  return {
    awardId: raw.awardId,
    title: text(raw.rewardTitle) || '门店奖励',
    conditions: text(raw.redemptionConditions),
    validText: windowText(raw.validFrom, raw.validUntil),
    awardedText: dayText(raw.awardedAt),
    validityText: VALIDITY[validity] || '',
    validityTone: VALIDITY_TONE[validity] || 'mono',
  };
}

function normalizeRewardPage(data) {
  if (!data || typeof data !== 'object' || !Array.isArray(data.items)) return null;
  var rows = [];
  data.items.forEach(function (item) { var row = normalizeReward(item); if (row) rows.push(row); });
  var next = typeof data.nextCursor === 'string' && data.nextCursor ? data.nextCursor : null;
  return { rows: rows, nextCursor: next, hasMore: !!next };
}

function appendRewards(existing, incoming) {
  var seen = {};
  var out = [];
  (existing || []).concat(incoming || []).forEach(function (row) {
    if (!row || seen[row.awardId]) return;
    seen[row.awardId] = true;
    out.push(row);
  });
  return out;
}

/** 领取幂等键:小写 UUID v4 形状。 */
function newRequestId(random) {
  var rnd = typeof random === 'function' ? random : Math.random;
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    var r = Math.floor(rnd() * 16) & 15;
    return (c === 'x' ? r : (r & 3) | 8).toString(16);
  });
}

function pendingKey(versionId, topicId) { return 'cy_noncash_claim_' + versionId + '_' + topicId; }

module.exports = {
  PAGE_SIZE: PAGE_SIZE,
  BRANCH: BRANCH,
  validTopicId: validTopicId,
  validVersionId: validVersionId,
  offerBlockText: offerBlockText,
  normalizeCatalog: normalizeCatalog,
  normalizeOffer: normalizeOffer,
  claimOutcome: claimOutcome,
  normalizeReward: normalizeReward,
  normalizeRewardPage: normalizeRewardPage,
  appendRewards: appendRewards,
  newRequestId: newRequestId,
  pendingKey: pendingKey,
};
