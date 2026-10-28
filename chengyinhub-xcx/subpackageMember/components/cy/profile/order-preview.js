// 主页「我的订单」预览:只留还要用的订单(待核销 / 待开始 / 进行中),已完成、已取消、已退款、已过期一律不进。
// 状态真源 = utils/order-status.js(与订单页同一读模型),这里不另算一套。
const { summarizeOrderState, orderClockNow } = require('../../../../utils/order-status.js');
const { toTimestamp, chinaParts } = require('../../../../utils/datetime.js');

const OPEN_KEYS = ['not_started', 'in_progress', 'non_refundable'];

function pad(n) { return n < 10 ? '0' + n : String(n); }

function dateText(value) {
  const ts = toTimestamp(value);
  const p = Number.isFinite(ts) && ts ? chinaParts(ts) : null;
  return p ? p.month + '月' + p.day + '日 ' + pad(p.hours) + ':' + pad(p.minutes) : '';
}

function isOpenOrder(row, phoneNow) {
  if (!row) return false;
  const now = orderClockNow(row, phoneNow);   // U-033:服务端时刻优先,传入的手机时钟只兜底
  const state = summarizeOrderState(row, now);
  if (OPEN_KEYS.indexOf(state.key) < 0) return false;
  // non_refundable 先于「活动已结束」判定:已过活动结束时间的不再算待用
  const source = row.cmsActivity || row.cmsTopic || {};
  const end = toTimestamp(source.endDate || source.endTime);
  if (Number.isFinite(end) && end && (typeof now === 'number' ? now : Date.now()) > end) return false;
  return Number(row.verificationStatus) !== 1;
}

// P-48(owner 2026-10-27):卡上只说「是什么主题」—— 玩法类型(1 城市定向 / 2 自由探索,活动统一「活动」)· 具体类别。
// 认不出的段落不显示,不编;两段都没有时交空串,由模板退回档期。
function kindTextOf(row, source) {
  const productType = Number(source.productType != null ? source.productType : source.mode);
  const type = row.cmsActivity ? '活动' : (productType === 1 ? '城市定向' : (productType === 2 ? '自由探索' : ''));
  const cats = Array.isArray(source.sysCategoryList) ? source.sysCategoryList : [];
  const hit = cats.filter(function (c) { return c && typeof c.categoryName === 'string' && c.categoryName.trim(); })[0];
  const category = hit ? hit.categoryName.trim() : (typeof source.categoryName === 'string' ? source.categoryName.trim() : '');
  return [type, category].filter(Boolean).join(' · ');
}

// 已付款未核销:进行中(含不可退款)=待核销;未开始=待开始。
// P-48:票数与「出示核销码」按钮已按 owner 要求去掉(核销码在订单/票夹里出示),卡片只留状态章、标题、主题信息。
function shapeOrderPreview(row, resolveImg, phoneNow) {
  const state = summarizeOrderState(row, orderClockNow(row, phoneNow));
  const source = row.cmsActivity || row.cmsTopic || {};
  const pics = source.pics ? String(source.pics).split(';').filter(function (u) { return u.trim(); }) : [];
  const rawCover = source.imgUrl || pics[0] || '';
  const toVerify = state.key !== 'not_started';
  const start = dateText(source.startDate || source.startTime);
  const end = dateText(source.endDate || source.endTime);
  return {
    id: row.id,
    title: source.name || row.topicName || '探索订单',
    cover: resolveImg ? resolveImg(rawCover) : rawCover,
    statusText: toVerify ? '待核销' : '待开始',
    statusTone: toVerify ? 'verify' : 'wait',
    timeText: start && end && start !== end ? start + ' – ' + end : (start || end),
    kindText: kindTextOf(row, source),
  };
}

function buildOrderPreviewList(rows, resolveImg, now, limit) {
  const t = typeof now === 'number' ? now : Date.now();
  return (rows || []).filter(function (r) { return isOpenOrder(r, t); })
    .slice(0, limit || 8).map(function (r) { return shapeOrderPreview(r, resolveImg, t); });
}

module.exports = { isOpenOrder, shapeOrderPreview, buildOrderPreviewList };
