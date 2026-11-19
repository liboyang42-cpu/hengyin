// CG-01-P2-9 / CG-09-P2-6:玩法站的过程埋点。只记事件名、主题 id、站 id 与枚举码,不记题面、答案或玩家输入。
//   stage_enter        进入一站的玩法
//   hint_open          打开提示(新提示面板或旧提示阶梯)
//   skip               跳过本站(服务端已结算 0 分 + 辅助完成)
//   assisted_complete  辅助完成(跳过 / 看答案),via 写是哪一种
//   wrong_answer       服务端判答错
//   stage_issue        玩家报这一站有问题,category 为下面四种之一
const analytics = require('../../../utils/analytics.js');
const cyToast = require('../../../utils/toast.js');

const ISSUE_CATEGORIES = [
  { code: 'ALT_MATERIAL', label: '现场换了材料' },
  { code: 'NOT_FOUND', label: '找不到' },
  { code: 'UNCLEAR', label: '看不清' },
  { code: 'LOGIC_ERROR', label: '题目逻辑有误' },
];

function stagePayload(topicId, nodeId, extra) {
  const properties = Object.assign({}, nodeId != null && nodeId !== '' ? { nodeId: String(nodeId) } : {}, extra || {});
  return { bizType: 'topic', bizId: topicId, properties };
}

function methods() {
  // 写成 const 对象再返回:台账 / 处理器门禁只扫模块里 `const X = {` 的键
  const api = {
    _stageEvent(name, nodeId, extra) {
      if (this.data.isPreview || this.data.isMock) return false;
      return analytics.track(name, stagePayload(this.data.topicId, nodeId, extra));
    },
    /** 提示面板最底下「这一站有问题」:选一类就记一条,不打断游玩。 */
    reportStageIssue() {
      this._issueNodeId = (this.data.helpPanel && this.data.helpPanel.nodeId) || (this.data.game && this.data.game.nodeId);
      this.setData({ issueSheetShow: true });
    },
    onIssueSheetCancel() { this._issueNodeId = null; this.setData({ issueSheetShow: false }); },
    onIssueSheetSelect(e) {
      const picked = ISSUE_CATEGORIES[Number(e && e.detail && e.detail.index)];
      const nodeId = this._issueNodeId;
      this._issueNodeId = null;
      this.setData({ issueSheetShow: false });
      if (!picked) return;
      this._stageEvent('stage_issue', nodeId, { category: picked.code });
      cyToast('已记录');
    },
  };
  return api;
}

module.exports = { methods, stagePayload, ISSUE_CATEGORIES, data: { issueSheetShow: false, issueSheetItems: ISSUE_CATEGORIES.map((c) => c.label) } };
