// U02-03 / U02-04:作者编辑器里的「地点待办」与来源版本。
// 数据只读 POST /api/template/topic-template/binding-status(作者本人/商家岗位),写只走 /binding。
// 规则(施工清单 §三 U02-02/03):只有 level=error 才算「挡住发布」;warn 只提示,端上不拦。
// 不展示 observationNote / referenceMediaIds / confirmedBy 之外的玩家信息;本页是作者页。

const STATUS_TEXT = {
  UNBOUND: '未绑定',
  REBIND_REQUIRED: '待在新城市重绑',
  BOUND_UNVERIFIED: '已绑定 · 未现场核验',
  LEGACY_UNVERIFIED: '沿用 · 未复核'
};

const POLICY_TEXT = {
  NONE: '不能替代',
  ALTERNATE_EVIDENCE: '可换其他证据',
  ALTERNATE_NODE: '可换备用地点',
  SKIP_ALLOWED: '可跳过'
};

const INTENT_TEXT = { NEW_CITY: '换城采用', SAME_CITY: '同城沿用', LEGACY: '旧版采用' };

function text(v) { return v == null ? '' : String(v).trim(); }

// 作者主题里带 id 与地址的节点,供「选这个节点」绑定
function bindableNodes(chapters) {
  const out = [];
  (Array.isArray(chapters) ? chapters : []).forEach(c => {
    (c && Array.isArray(c.nodes) ? c.nodes : []).forEach(n => {
      const id = n && n.id != null ? String(n.id) : '';
      if (!/^[1-9]\d*$/.test(id)) return;
      const hasPlace = text(n.address) || text(n.latitude) && text(n.longitude);
      if (!hasPlace) return;
      out.push({ id, label: (text(n.name) || '未命名节点') + (text(n.address) ? ' · ' + text(n.address) : '') });
    });
  });
  return out;
}

// 节点 id → 当前地址(没有地址的节点不收):地点待办每行显示「现在绑在哪」,并据此给「替换地点」
function nodePlaces(chapters) {
  const out = {};
  (Array.isArray(chapters) ? chapters : []).forEach(c => {
    (c && Array.isArray(c.nodes) ? c.nodes : []).forEach(n => {
      const id = n && n.id != null ? String(n.id) : '';
      if (!/^[1-9]\d*$/.test(id)) return;
      const place = text(n.address) || text(n.name);
      if (place && (text(n.address) || text(n.latitude) && text(n.longitude))) out[id] = place;
    });
  });
  return out;
}

// 每个地点待办行对应的编辑器节点:已绑定的用绑定的 nodeId;同城沿用后还没绑定的,按同名节点认领(唯一才算)。
// 返回 { slotKey: { id, place } },place 是节点当前地址(没有地址就不认领)。
function slotNodes(view, chapters) {
  const out = {};
  if (!view || !Array.isArray(view.rows)) return out;
  const places = nodePlaces(chapters);
  const byName = {};
  (Array.isArray(chapters) ? chapters : []).forEach(c => {
    (c && Array.isArray(c.nodes) ? c.nodes : []).forEach(n => {
      const id = n && n.id != null ? String(n.id) : '';
      const name = text(n && n.name);
      if (!/^[1-9]\d*$/.test(id) || !name || !places[id]) return;
      (byName[name] = byName[name] || []).push(id);
    });
  });
  view.rows.forEach(row => {
    let id = row.nodeId && places[row.nodeId] ? row.nodeId : '';
    if (!id && byName[row.title] && byName[row.title].length === 1) id = byName[row.title][0];
    if (id) out[row.slotKey] = { id, place: places[id], claimed: !row.nodeId };
  });
  return out;
}

function view(data) {
  if (!data || typeof data !== 'object') return null;
  const contract = data.contract && typeof data.contract === 'object' ? data.contract : {};
  const slots = Array.isArray(contract.locationSlots) ? contract.locationSlots : [];
  if (!slots.length) return null; // 没有地点合同:整块不画
  const bindings = {};
  (Array.isArray(contract.locationBindings) ? contract.locationBindings : []).forEach(b => { if (b && b.slotKey) bindings[b.slotKey] = b; });
  const issues = {};
  (Array.isArray(data.bindingIssues) ? data.bindingIssues : []).forEach(i => { if (i && i.slotKey) issues[i.slotKey] = i; });
  const rows = slots.map(slot => {
    const b = bindings[slot.slotKey] || {};
    const issue = issues[slot.slotKey] || null;
    const status = text(b.verificationStatus) || 'UNBOUND';
    return {
      slotKey: slot.slotKey,
      title: text(slot.title) || '地点',
      what: text(slot.observationTarget),
      how: text(slot.observationAction),
      fallback: POLICY_TEXT[text(slot.alternativePolicy) || 'NONE'] || '',
      statusText: STATUS_TEXT[status] || '状态待确认',
      bound: status === 'BOUND_UNVERIFIED',
      blocking: !!issue && issue.level === 'error',
      warn: !!issue && issue.level === 'warn',
      issueText: issue ? text(issue.message) : '',
      nodeId: b.nodeId != null ? String(b.nodeId) : '',
      revision: Number.isSafeInteger(Number(b.bindingRevision)) ? Number(b.bindingRevision) : null
    };
  });
  const adoption = data.adoption && typeof data.adoption === 'object' ? data.adoption : null;
  const source = text(data.sourceTemplateTopicId);
  const rev = text(data.sourceContentRevisionId);
  return {
    rows,
    blockingCount: rows.filter(r => r.blocking).length,
    warnCount: rows.filter(r => r.warn).length,
    // U02-04:来源身份与修订;旧来源没有修订 = 未锁定来源修订,不补 1
    source: source ? {
      topicText: '来源模板 #' + source,
      revisionText: rev ? '来源修订 #' + rev : '未锁定来源修订',
      intentText: adoption ? INTENT_TEXT[text(adoption.bindingIntent)] || '' : ''
    } : null
  };
}

function summaryText(v) {
  if (!v) return '';
  if (v.blockingCount) return v.blockingCount + ' 项挡住发布';
  if (v.warnCount) return v.warnCount + ' 项待复核';
  return v.rows.every(r => r.bound) ? '已全部绑定' : '';
}

module.exports = { view, summaryText, bindableNodes, nodePlaces, slotNodes, STATUS_TEXT };
