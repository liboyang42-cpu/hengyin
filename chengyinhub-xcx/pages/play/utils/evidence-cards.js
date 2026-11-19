// CG-01-P2-7:本局已获「证据卡」回看。证据卡就是每站解开后给的那段文字反馈(模板「文字反馈」passText),
// 只收已完成的站;没写反馈的站不出卡。通用:任何主题、任何玩法都走这一份,不按路线写特例。
function evidenceCards(nodes) {
  return (nodes || []).filter((n) => n && n.done && String(n.passText || '').trim())
    .map((n, i) => ({ key: String(n.nodeId != null ? n.nodeId : i), name: String(n.name || ''), text: String(n.passText).trim() }));
}

function methods() {
  // 写成 const 对象再返回:台账 / 处理器门禁只扫模块里 `const X = {` 的键
  const api = {
    openEvidenceCards() {
      this.setData({ sessionToolsOpen: false, evidence: { show: true, items: evidenceCards(this.data.nodes) } });
    },
    closeEvidenceCards() { this.setData({ 'evidence.show': false }); },
  };
  return api;
}

module.exports = { evidenceCards, methods, data: { evidence: { show: false, items: [] }, evidenceCount: 0 } };
