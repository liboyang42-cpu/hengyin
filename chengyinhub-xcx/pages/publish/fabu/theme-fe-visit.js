'use strict';
// FE-STORY 7.10:节点抽屉里的「到访方式」一组(自由探索、已保存的主题、已入库的地点;只给主题作者)。
// 打开抽屉读一次 GET;点选即存一条 POST。错误写在字段下面(cy-inline-error),不弹 toast。
// 一次点选只发一次写;没收到回包或 5xx(结果不明)时只回读 GET 看服务端实际存了什么,不自动重发。
const cfg = require('../utils/publish/fe-visit-config.js');

const EMPTY = { show: false, nodeId: '', check: 'GPS', radius: cfg.RADIUS_DEFAULT, loading: false, saving: false, error: '' };

const data = {
  feVisitChecks: cfg.CHECKS, feVisitRadiusMin: cfg.RADIUS_MIN, feVisitRadiusMax: cfg.RADIUS_MAX, feVisitRadiusStep: cfg.RADIUS_STEP,
  feVisit: Object.assign({}, EMPTY),
};

const methods = {
  /** 开节点抽屉时调用;不是自由探索 / 主题没保存 / 地点没入库就收起这一组 */
  _openFeVisitConfig(node) {
    const topicId = this.data.editingTopicId;
    const nodeId = node && node.id != null ? String(node.id) : '';
    const seq = this._feVisitSeq = (this._feVisitSeq || 0) + 1;
    if (Number((this.data.formData || {}).productType) !== 2 || !topicId || !nodeId) {
      this.setData({ feVisit: Object.assign({}, EMPTY) });
      return;
    }
    const row = this._feVisitRows && this._feVisitRows[nodeId];
    this.setData({ feVisit: Object.assign({}, EMPTY, { show: true, nodeId, loading: !row },
      row ? { check: row.check, radius: row.radius } : {}) });
    this._readFeVisit(topicId, nodeId, seq, false);
  },
  _closeFeVisitConfig() {
    this._feVisitSeq = (this._feVisitSeq || 0) + 1;
    this.setData({ feVisit: Object.assign({}, EMPTY) });
  },
  /** 读 GET。keepError:保存结果不明后的回读,只刷新选中项,字段下的提示保留 */
  _readFeVisit(topicId, nodeId, seq, keepError) {
    const live = () => seq === this._feVisitSeq && this.data.feVisit.nodeId === nodeId;
    const loadFail = (msg) => { if (live()) this.setData(keepError ? { 'feVisit.loading': false } : { 'feVisit.loading': false, 'feVisit.error': msg }); };
    getApp().sendRequest({
      url: '/api/free-explore/visit-config', method: 'GET', data: cfg.loadQuery(topicId), hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (!live()) return;
        if (!cfg.isOk(res) || !Array.isArray(res.data)) {
          loadFail(Number(res && res.code) === 403 ? cfg.ERRORS.FORBIDDEN : ((res && res.msg) || cfg.ERRORS.LOAD));
          return;
        }
        this._feVisitRows = cfg.rowsById(res.data);
        const mine = this._feVisitRows[nodeId];
        if (!mine) { this.setData({ feVisit: Object.assign({}, EMPTY) }); return; }
        const patch = { 'feVisit.loading': false, 'feVisit.check': mine.check, 'feVisit.radius': mine.radius };
        if (!keepError) patch['feVisit.error'] = '';
        this.setData(patch);
      },
      successStatusAbnormal: (res, status) => loadFail(Number(status) === 403 ? cfg.ERRORS.FORBIDDEN : cfg.ERRORS.LOAD),
      fail: () => loadFail(cfg.ERRORS.LOAD),
    });
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
    const topicId = this.data.editingTopicId;
    const row = this._feVisitRows && this._feVisitRows[nodeId];
    const issue = cfg.precheck(row, check, radius);
    if (issue) { this.setData({ 'feVisit.error': issue }); return; }
    const seq = this._feVisitSeq;
    const prev = { check: v.check, radius: v.radius };
    const live = () => seq === this._feVisitSeq && this.data.feVisit.nodeId === nodeId;
    const reject = (msg) => { if (live()) this.setData({ 'feVisit.saving': false, 'feVisit.check': prev.check, 'feVisit.radius': prev.radius, 'feVisit.error': msg }); };
    const readBack = () => { if (live()) this._readFeVisit(topicId, nodeId, seq, true); };
    this.setData({ 'feVisit.saving': true, 'feVisit.error': '', 'feVisit.check': check, 'feVisit.radius': check === 'GPS' ? Number(radius) : v.radius });
    getApp().sendRequest({
      url: '/api/free-explore/visit-config', method: 'POST', data: JSON.stringify(cfg.saveBody(nodeId, check, radius)),
      header: { 'Content-Type': 'application/json' }, hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (!cfg.isOk(res)) { reject(cfg.errorText(res)); return; }
        const saved = cfg.rowView(Object.assign({}, row || {}, res.data || {}, { nodeId })) || { check, radius };
        this._feVisitRows = Object.assign({}, this._feVisitRows, { [nodeId]: Object.assign({}, row || {}, saved) });
        if (live()) this.setData({ 'feVisit.saving': false, 'feVisit.check': saved.check, 'feVisit.radius': saved.radius, 'feVisit.error': '' });
      },
      successStatusAbnormal: (res, status) => {
        reject(cfg.errorText(res, status));
        if (Number(status) >= 500) readBack();   // 结果不明:只回读
      },
      // 没收到回包:不重发,只提示并回读;作者再点一次才会再发
      fail: () => { reject(cfg.ERRORS.NETWORK); readBack(); },
    });
  },
};

module.exports = { data, methods };
