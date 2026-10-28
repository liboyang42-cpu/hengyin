'use strict';
// 文档二 L3 · 下一站条件与效果的名单（10-28 原型 A e-route）。
// 打开下一站时读状态键注册表（GET /api/topic/state-keys，线索 / 标签 / 数值名字的真源）补进名单；
// 添加层里「新建「…」」走 L8 契约 §3.1 POST /api/topic/state-keys/named，键名由服务端生成、不显示。
const app = getApp();
const cyToast = require('../../../utils/toast.js');
const { isBizOk } = require('../../../utils/response-shape.js');
const sentence = require('../utils/publish/story-rule-sentence.js');

// 10-28 e-route：展开的结果行、去往勾选是否展开、条件 / 效果开的下一层（{ identity, title }）；节点编辑里照片是否展开。
const data = { routeMappingOpen: '', routeMappingDestOpen: false, routeMappingLayer: null, routeRuleNames: {}, nodePhotosOpen: false };
const methods = {
  // 打开下一站时的名单与可用变量（返回值即 routeRuleVariables）：d20 设定（属性、进度钟、特质）、主题物品和节点先给，注册表读回再补。
  _routeRuleState(declared, nodes) {
    const names = sentence.buildNames(this.data.formData.journeyRules, this.data.formData.themeRuntimeConfig, nodes, declared);
    const variables = sentence.editorVariables(declared, names);
    this._routeExtraVariables = variables;
    this.setData({ routeRuleNames: names, routeMappingOpen: '', routeMappingDestOpen: false, routeMappingLayer: null });
    this._loadRouteRuleRegistry(declared);
    return variables;
  },
  // 完成下一站时：编辑中可选、还没在路线图里声明的属性 / 进度钟 / 数值，用到了才补进 variables（前后端校验都要求先声明）。
  _declareRouteRuleVariables(graph) {
    const extra = this._routeExtraVariables || {};
    const used = JSON.stringify((this.data.routeMappingRows || []).map(row => row.ruleConfig || {}));
    graph.variables = Object.assign({}, graph.variables || {});
    Object.keys(extra).forEach(key => {
      if (!(key in graph.variables) && used.indexOf('"' + key + '"') >= 0) graph.variables[key] = extra[key];
    });
  },
  toggleRouteMappingRow(e) {
    const key = e.currentTarget.dataset.key;
    this.setData({ routeMappingOpen: this.data.routeMappingOpen === key ? '' : key, routeMappingDestOpen: false });
  },
  toggleRouteMappingDest() { this.setData({ routeMappingDestOpen: !this.data.routeMappingDestOpen }); },
  toggleNodePhotos() { this.setData({ nodePhotosOpen: !this.data.nodePhotosOpen }); },
  // 条件 / 效果的组件开、关下一层时通知这里：换头部、藏其它结果行。
  onRouteRuleLayer(e) {
    const detail = e.detail || {};
    this.setData({ routeMappingLayer: detail.open ? { identity: detail.identity, title: detail.title } : null });
  },
  closeRouteRuleLayer() {
    const index = (this.data.routeMappingRows || []).findIndex(row => row.mappingKey === this.data.routeMappingOpen);
    const editor = index >= 0 && this.selectComponent('#route-rules-' + index);
    if (editor) editor.closeLayer();
    else this.setData({ routeMappingLayer: null });
  },
  _loadRouteRuleRegistry(declared) {
    const topicId = this.data.editingTopicId;
    if (!topicId) return;
    const seq = this._routeRegistrySeq = (this._routeRegistrySeq || 0) + 1;
    const owner = this._routeMappingSourceNodeId;
    const current = () => seq === this._routeRegistrySeq && this.data.routeMappingShow && owner === this._routeMappingSourceNodeId;
    app.sendRequest({
      url: '/api/topic/state-keys', method: 'GET', hideLoading: true,
      data: { topicId, scope: this.data.operationScope },
      success: res => {
        if (!current() || !isBizOk(res) || !Array.isArray(res.data)) return;
        const names = sentence.mergeRegistry(this.data.routeRuleNames, res.data);
        const variables = sentence.editorVariables(declared, names);
        this._routeExtraVariables = variables;
        this.setData({ routeRuleNames: names, routeRuleVariables: variables });
      },
    });
  },
  // story-rule-editor 的 create 事件：{ identity, kind: clue | tag, label }。
  onRouteRuleCreate(e) {
    const detail = e.detail || {};
    const index = (this.data.routeMappingRows || []).findIndex(row => row.formIdentity === detail.identity);
    const editor = index >= 0 && this.selectComponent('#route-rules-' + index);
    const done = key => { if (editor) editor.created(key); };
    const topicId = this.data.editingTopicId;
    if (!topicId) { cyToast('请先保存主题草稿'); return done(''); }
    app.sendRequest({
      url: '/api/topic/state-keys/named', method: 'POST', hideLoading: true,
      data: JSON.stringify({ topicId: Number(topicId), scope: this.data.operationScope, kind: detail.kind, label: detail.label }),
      header: { 'Content-Type': 'application/json' },
      success: res => {
        const row = res && res.data;
        if (!isBizOk(res) || !row || !row.key) {
          // 契约列出的拒绝（重名、超长、内容安全）原样显示，其余只说没保存成功。
          cyToast(res && Number(res.code) === 400 && res.msg ? res.msg : '没有保存成功，请再试一次。');
          return done('');
        }
        const names = sentence.mergeRegistry(this.data.routeRuleNames, [{ stateKey: row.key, label: row.label }]);
        this.setData({ routeRuleNames: names }, () => done(row.key));
      },
      fail: () => { cyToast('没有保存成功，请再试一次。'); done(''); },
    });
  },
};

module.exports = { data, methods };
