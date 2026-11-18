'use strict';
/**
 * 自由探索(productType 2)每个地点的「到访方式」(FE-STORY 7.10),只给主题作者。
 *
 *   GET  /api/topic/free-explore/visit-config?topicId=X
 *        → data: [{ nodeId, name, chapterId, visitCheck: GPS|SCAN|ONLINE, visitRadius: Int|null, hasCoordinates }]
 *   POST /api/topic/free-explore/visit-config  JSON { nodeId, visitCheck, visitRadius: Int|null } → 更新后的这一项
 *
 * 半径只有 GPS 才有:300 / 400 / 500 米,null 即默认 300。服务端是权威,这里只做选项与错误文字;
 * 错误写在字段下面,不只弹 toast。页面接线见 pages/publish/fabu/theme-fe-visit.js。
 */
const CHECKS = [
  { key: 'GPS', label: 'GPS（默认）' },
  { key: 'SCAN', label: '扫码' },
  { key: 'ONLINE', label: '仅线上' },
];
const RADIUS_MIN = 300;
const RADIUS_MAX = 500;
const RADIUS_STEP = 100;
const RADIUS_DEFAULT = 300;

const ERRORS = {
  VISIT_RADIUS_INVALID: '范围只能选 300、400 或 500 米',
  VISIT_GPS_COORD_REQUIRED: '这里还没有坐标，请先选位置或改为扫码',
  VISIT_CHECK_INVALID: '请选择到访方式',
  FORBIDDEN: '只有主题作者可以修改到访方式',
  NETWORK: '网络异常，没有确认保存，请重试',
  FAILED: '没有保存成功，请再试一次',
  LOAD: '到访方式加载失败',
};

function normCheck(v) {
  const s = String(v || '').toUpperCase();
  return CHECKS.some((c) => c.key === s) ? s : 'GPS';
}

/** 显示用半径:null / 缺省 → 300;只取 300 / 400 / 500 */
function shownRadius(v) {
  if (v == null || v === '') return RADIUS_DEFAULT;
  const n = Number(v);
  return radiusValid(n) ? n : RADIUS_DEFAULT;
}

/** 合法半径:300–500 之间的整百 */
function radiusValid(n) {
  return Number.isInteger(n) && n >= RADIUS_MIN && n <= RADIUS_MAX && n % RADIUS_STEP === 0;
}

/** GET 查询参数 */
function loadQuery(topicId) {
  return { topicId: Number(topicId) };
}

/** POST 请求体。非 GPS 不带半径(null);GPS 带选中的半径。 */
function saveBody(nodeId, visitCheck, visitRadius) {
  const check = normCheck(visitCheck);
  return { nodeId: Number(nodeId), visitCheck: check, visitRadius: check === 'GPS' ? Number(visitRadius) : null };
}

/** 发请求前本地能确定的拒绝(与服务端同一口径);返回错误文字,'' = 可以发 */
function precheck(row, visitCheck, visitRadius) {
  const check = String(visitCheck || '').toUpperCase();
  if (!CHECKS.some((c) => c.key === check)) return ERRORS.VISIT_CHECK_INVALID;
  if (check !== 'GPS') return '';
  if (!radiusValid(Number(visitRadius))) return ERRORS.VISIT_RADIUS_INVALID;
  if (row && row.hasCoordinates === false) return ERRORS.VISIT_GPS_COORD_REQUIRED;
  return '';
}

function isOk(res) { return !!res && Number(res.code) === 200; }

/** 回包 → 字段下方的错误文字。status 是 HTTP 状态(successStatusAbnormal / fail 的第二个参数)。 */
function errorText(res, status) {
  const body = res || {};
  const code = String(body.errorCode || (body.data && body.data.errorCode) || '');
  if (ERRORS[code] && code !== 'FORBIDDEN') return ERRORS[code];
  if (Number(status) === 403 || Number(body.code) === 403) return ERRORS.FORBIDDEN;
  if (body.netFail) return ERRORS.NETWORK;
  return body.msg || ERRORS.FAILED;
}

/** GET 回包里的一行 → 编辑器视图 */
function rowView(item) {
  if (!item || item.nodeId == null) return null;
  return {
    nodeId: String(item.nodeId), name: item.name || '', chapterId: item.chapterId,
    check: normCheck(item.visitCheck), radius: shownRadius(item.visitRadius), hasCoordinates: item.hasCoordinates !== false,
  };
}

function rowsById(list) {
  const out = {};
  (Array.isArray(list) ? list : []).forEach((item) => { const v = rowView(item); if (v) out[v.nodeId] = v; });
  return out;
}

module.exports = {
  CHECKS, ERRORS, RADIUS_MIN, RADIUS_MAX, RADIUS_STEP, RADIUS_DEFAULT,
  normCheck, shownRadius, radiusValid, loadQuery, saveBody, precheck, errorText, isOk, rowView, rowsById,
};
