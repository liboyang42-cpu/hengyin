'use strict';
// 到达定位精度:阈值只在这里定义一次。
// 与漫游轨迹过滤同口径(utils/roam-track-filter.js 的 accuracy > 80);后端 /api/play/arrive 也按 80m 拒绝,
// 拒绝文案含「定位精度不足」。精度缺失(空)一律放行,由后端按原有距离判定。
const MAX_ACCURACY_M = 80;
const LOW_ACCURACY_KEYWORD = '定位精度不足';
const LOW_ACCURACY_HINT = '定位精度不足 · 可扫码完成';

const num = (v) => (typeof v === 'number' && Number.isFinite(v) && v >= 0 ? v : undefined);

/** 取出可上报的精度(米);缺失/非法返回 undefined(JSON 序列化时整字段省略) */
function accuracyOf(loc) { return loc ? num(loc.accuracy) : undefined; }
function isLowAccuracy(accuracy) { const a = num(accuracy); return a !== undefined && a > MAX_ACCURACY_M; }
function isLowAccuracyMsg(msg) { return typeof msg === 'string' && msg.indexOf(LOW_ACCURACY_KEYWORD) >= 0; }

module.exports = { MAX_ACCURACY_M, LOW_ACCURACY_HINT, accuracyOf, isLowAccuracy, isLowAccuracyMsg };
