'use strict';
/**
 * 自由探索(productType 2)每个地点的「到访方式」(FE-STORY 7.10),只给主题作者。
 *
 *   GET  /api/free-explore/visit-config?topicId=X
 *        → data: [{ nodeId, name, chapterId, visitCheck: GPS|SCAN|ONLINE, visitRadius: Int|null, hasCoordinates }]
 *   POST /api/free-explore/visit-config  JSON { nodeId, visitCheck, visitRadius: Int|null } → 更新后的这一项
 *
 * 半径只有 GPS 才有:300 / 400 / 500 米,null 即默认 300。服务端是权威,这里只做选项与错误文字;
 * 错误写在字段下面,不只弹 toast。一次点选只发一次 POST;结果不明时只回读 GET,不自动重发。
 */
const URL = '/api/free-explore/visit-config';
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

function loadRequest(topicId) {
  return { url: URL, method: 'GET', data: { topicId: Number(topicId) } };
}

/** 保存请求体。非 GPS 不带半径(null);GPS 带选中的半径。 */
function saveRequest(nodeId, visitCheck, visitRadius) {
  const check = normCheck(visitCheck);
  const body = { nodeId: Number(nodeId), visitCheck: check, visitRadius: check === 'GPS' ? Number(visitRadius) : null };
  return { url: URL, method: 'POST', data: JSON.stringify(body), header: { 'Content-Type': 'application/json' }, body };
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

const EMPTY = { show: false, nodeId: '', check: 'GPS', radius: RADIUS_DEFAULT, loading: false, saving: false, error: '' };

const data = {
  feVisitChecks: CHECKS, feVisitRadiusMin: RADIUS_MIN, feVisitRadiusMax: RADIUS_MAX, feVisitRadiusStep: RADIUS_STEP,
  feVisit: Object.assign({}, EMPTY),
};

/** 页面 mixin:节点抽屉里的「到访方式」一组。依赖 page.data.formData.productType / editingTopicId,app.sendRequest。 */
function methods() {
  const app = () => getApp();
  return {
    /** 开节点抽屉时调用:自由探索、已保存的主题、已入库的地点才出现这一组;每次打开都读一次 */
    _openFeVisitConfig(node) {
      const topicId = this.data.editingTopicId;
      const nodeId = node && node.id != null ? String(node.id) : '';
      if (Number((this.data.formData || {}).productType) !== 2 || !topicId || !nodeId) {
        this._feVisitSeq = (this._feVisitSeq || 0) + 1;
        this.setData({ feVisit: Object.assign({}, EMPTY) });
        return;
      }
      const seq = this._feVisitSeq = (this._feVisitSeq || 0) + 1;
      const row = this._feVisitRows && this._feVisitRows[nodeId];
      this.setData({ feVisit: Object.assign({}, EMPTY, { show: true, nodeId, loading: !row },
        row ? { check: row.check, radius: row.radius } : {}) });
      this._readFeVisit(topicId, nodeId, seq, false);
    },
    /** 读 GET。keepError:保存结果不明后回读,只刷新选中项,字段下的提示保留 */
    _readFeVisit(topicId, nodeId, seq, keepError) {
      const r = loadRequest(topicId);
      const done = (patch) => { if (seq === this._feVisitSeq && this.data.feVisit.nodeId === nodeId) this.setData(patch); };
      const loadFail = (msg) => done(keepError ? { 'feVisit.loading': false } : { 'feVisit.loading': false, 'feVisit.error': msg });
      app().sendRequest(Object.assign({}, r, { hideLoading: true, silentError: true,
        success: (res) => {
          if (!isOk(res) || !Array.isArray(res.data)) { loadFail(Number(res && res.code) === 403 ? ERRORS.FORBIDDEN : ((res && res.msg) || ERRORS.LOAD)); return; }
          if (seq !== this._feVisitSeq) return;
          this._feVisitRows = rowsById(res.data);
          const mine = this._feVisitRows[nodeId];
          if (!mine) { done({ feVisit: Object.assign({}, EMPTY) }); return; }
          const patch = { 'feVisit.loading': false, 'feVisit.check': mine.check, 'feVisit.radius': mine.radius };
          if (!keepError) patch['feVisit.error'] = '';
          done(patch);
        },
        successStatusAbnormal: (res, status) => loadFail(Number(status) === 403 ? ERRORS.FORBIDDEN : ERRORS.LOAD),
        fail: () => loadFail(ERRORS.LOAD),
      }));
    },
    _closeFeVisitConfig() {
      this._feVisitSeq = (this._feVisitSeq || 0) + 1;
      this.setData({ feVisit: Object.assign({}, EMPTY) });
    },
    /** 分段选择:GPS(默认) / 扫码 / 仅线上 */
    onFeVisitCheck(e) {
      const check = String((e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.check) || '');
      const v = this.data.feVisit || {};
      if (!v.show || v.saving || v.loading || check === v.check) return;
      this._saveFeVisit(check, check === 'GPS' ? v.radius : null);
    },
    /** GPS 范围步进器:300 / 400 / 500 米 */
    onFeVisitRadius(e) {
      const radius = Number(e && e.detail && e.detail.value);
      const v = this.data.feVisit || {};
      if (!v.show || v.saving || v.loading || v.check !== 'GPS' || radius === v.radius) return;
      this._saveFeVisit('GPS', radius);
    },
    _saveFeVisit(check, radius) {
      const v = this.data.feVisit;
      const nodeId = v.nodeId;
      const row = this._feVisitRows && this._feVisitRows[nodeId];
      const issue = precheck(row, check, radius);
      if (issue) { this.setData({ 'feVisit.error': issue }); return; }
      const seq = this._feVisitSeq;
      const prev = { check: v.check, radius: v.radius };
      const r = saveRequest(nodeId, check, radius);
      this.setData({ 'feVisit.saving': true, 'feVisit.error': '', 'feVisit.check': check, 'feVisit.radius': check === 'GPS' ? Number(radius) : v.radius });
      const live = () => seq === this._feVisitSeq && this.data.feVisit.nodeId === nodeId;
      const reject = (msg) => { if (live()) this.setData({ 'feVisit.saving': false, 'feVisit.check': prev.check, 'feVisit.radius': prev.radius, 'feVisit.error': msg }); };
      app().sendRequest({ url: r.url, method: r.method, data: r.data, header: r.header, hideLoading: true, silentError: true,
        success: (res) => {
          if (!isOk(res)) { reject(errorText(res)); return; }
          const saved = rowView(Object.assign({}, row || {}, res.data || {}, { nodeId })) || { check, radius };
          this._feVisitRows = Object.assign({}, this._feVisitRows, { [nodeId]: Object.assign({}, row || {}, saved) });
          if (live()) this.setData({ 'feVisit.saving': false, 'feVisit.check': saved.check, 'feVisit.radius': saved.radius, 'feVisit.error': '' });
        },
        successStatusAbnormal: (res, status) => {
          reject(errorText(res, status));
          if (Number(status) >= 500 && live()) this._readFeVisit(this.data.editingTopicId, nodeId, seq, true);   // 结果不明:只回读
        },
        // 没收到回包:不重发,只提示并回读 GET 看服务端实际存的是什么;作者再点一次才会再发
        fail: () => {
          reject(ERRORS.NETWORK);
          if (live()) this._readFeVisit(this.data.editingTopicId, nodeId, seq, true);
        },
      });
    },
  };
}

module.exports = {
  URL, CHECKS, ERRORS, RADIUS_MIN, RADIUS_MAX, RADIUS_STEP, RADIUS_DEFAULT,
  normCheck, shownRadius, radiusValid, loadRequest, saveRequest, precheck, errorText, rowView, rowsById, data, methods,
};
