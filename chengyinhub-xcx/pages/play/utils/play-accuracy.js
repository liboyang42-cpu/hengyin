'use strict';
// 到达定位精度:阈值只在这里定义一次。
// 与漫游轨迹过滤同口径(utils/roam-track-filter.js 的 accuracy > 80);后端 /api/play/arrive 也按 80m 拒绝,
// 拒绝文案含「定位精度不足」。精度缺失(空)后端一律拒绝(ARRIVE_ACCURACY_MISSING,O-01 2026-10-23 删除空精度放行),
// 所以自动到达只在拿到合法精度且 ≤80m 时才发(canAutoArrive)。
const MAX_ACCURACY_M = 80;
const LOW_ACCURACY_KEYWORD = '定位精度不足';
const LOW_ACCURACY_HINT = '定位精度不足 · 可扫码完成';

const num = (v) => (typeof v === 'number' && Number.isFinite(v) && v >= 0 ? v : undefined);

/** 取出可上报的精度(米);缺失/非法返回 undefined(JSON 序列化时整字段省略) */
function accuracyOf(loc) { return loc ? num(loc.accuracy) : undefined; }
function isLowAccuracy(accuracy) { const a = num(accuracy); return a !== undefined && a > MAX_ACCURACY_M; }
/** 自动到达是否值得发:精度必须已知且不超阈值;缺失/非法后端必拒,发了只会每次定位回调刷一次拒绝 */
function canAutoArrive(accuracy) { const a = num(accuracy); return a !== undefined && a <= MAX_ACCURACY_M; }
function isLowAccuracyMsg(msg) { return typeof msg === 'string' && msg.indexOf(LOW_ACCURACY_KEYWORD) >= 0; }

module.exports = { MAX_ACCURACY_M, LOW_ACCURACY_HINT, accuracyOf, isLowAccuracy, canAutoArrive, isLowAccuracyMsg };
