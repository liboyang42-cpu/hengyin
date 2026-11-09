// §21 固定玩法预设:GET /api/preset/gameplay/list → 作者填 requiredFields → POST /api/preset/gameplay/draft。
// 目录里没有答案、没有题目正文,端上不预填一份完整玩法;作者输入原样送,缺项由服务端一次列全。
// requestId 每张表单一个:结果未知时同号重发只拿回原草稿,不会多建一份。

const KINDS = ['text', 'number', 'choice', 'order', 'bin_map'];

function str(v) { return v == null ? '' : String(v); }

// 选项是稳定标识(s1/c2/b3…);显示文案取作者在同组文字字段里填的内容。
// 同组 = 键名去掉末尾数字后相同、且个数与选项数相等的一组 TEXT 字段;有多组同样大小时取声明位置
// 在本字段之前且离它最近的那一组(T06 的 bin1/bin2 与 choice1/choice2 都是 2 项)。找不到就显示「第 N 项」。
function textGroups(fields) {
  const groups = {};
  fields.forEach((f, index) => {
    const m = f.kind === 'text' && /^(.*?)(\d+)$/.exec(f.key);
    if (!m) return;
    const g = groups[m[1]] = groups[m[1]] || { keys: [], last: -1 };
    g.keys.push(f.key); g.last = index;
  });
  return groups;
}

function groupFor(ids, fields, before) {
  const groups = textGroups(fields);
  const sized = Object.keys(groups).filter(g => groups[g].keys.length === ids.length);
  const preceding = sized.filter(g => groups[g].last < before).sort((a, b) => groups[b].last - groups[a].last);
  if (preceding.length) return groups[preceding[0]].keys;
  return sized.length === 1 ? groups[sized[0]].keys : null;
}

function optionLabels(ids, fields, values, before) {
  const group = groupFor(ids, fields, before == null ? fields.length : before);
  return ids.map((id, i) => {
    const typed = group ? str(values[group[i]]).trim() : '';
    return typed || '第 ' + (i + 1) + ' 项';
  });
}

function list(data) {
  const presets = data && Array.isArray(data.presets) ? data.presets : [];
  return presets.filter(p => p && p.presetKey).map(p => ({
    presetKey: str(p.presetKey),
    title: str(p.title).replace(/（结构预设）$/, ''),
    purpose: str(p.purpose),
    rebinds: p.cityBinding && Array.isArray(p.cityBinding.requiredRebinds) ? p.cityBinding.requiredRebinds.length : 0,
    raw: p
  }));
}

// 表单行:按 kind 选控件;服务端没声明的 kind 不画(也不送)
function formRows(preset, values, missingKeys) {
  const miss = new Set(missingKeys || []);
  const fields = (preset && Array.isArray(preset.requiredFields) ? preset.requiredFields : []).filter(f => f && KINDS.includes(f.kind));
  return fields.map((f, at) => {
    const v = values[f.key];
    const row = { key: f.key, label: str(f.label), kind: f.kind, missing: miss.has(f.key) };
    if (f.kind === 'text') Object.assign(row, { value: str(v), maxLength: Number(f.maxLength) > 0 ? Number(f.maxLength) : 140 });
    if (f.kind === 'number') Object.assign(row, { value: v == null ? '' : str(v), hint: f.min != null && f.max != null ? f.min + '–' + f.max : '' });
    if (f.kind === 'choice') {
      const ids = Array.isArray(f.options) ? f.options : [];
      const labels = optionLabels(ids, fields, values, at);
      row.options = ids.map((id, i) => ({ id, label: labels[i], on: v === id }));
    }
    if (f.kind === 'order') {
      const ids = Array.isArray(f.options) ? f.options : [];
      const labels = optionLabels(ids, fields, values, at);
      const current = Array.isArray(v) && v.length === ids.length ? v : ids;
      row.items = current.map(id => ({ id, label: labels[ids.indexOf(id)] }));
    }
    if (f.kind === 'bin_map') {
      const items = Array.isArray(f.items) ? f.items : [];
      const bins = Array.isArray(f.bins) ? f.bins : [];
      const itemLabels = optionLabels(items, fields, values, at);
      const binLabels = optionLabels(bins, fields, values, at);
      const map = v && typeof v === 'object' && !Array.isArray(v) ? v : {};
      row.items = items.map((id, i) => ({ id, label: itemLabels[i], bins: bins.map((b, j) => ({ id: b, label: binLabels[j], on: map[id] === b })) }));
    }
    return row;
  });
}

// 请求体:只送服务端声明过的键;没填的不送(由服务端一次列出缺项)。ORDER 未动过也按当前顺序送。
function draftBody(preset, values, requestId) {
  const fields = {};
  (preset.requiredFields || []).forEach(f => {
    if (!f || !KINDS.includes(f.kind)) return;
    const v = values[f.key];
    if (f.kind === 'order') { fields[f.key] = Array.isArray(v) ? v.slice() : (f.options || []).slice(); return; }
    if (v == null || v === '') return;
    if (f.kind === 'bin_map') { if (Object.keys(v).length) fields[f.key] = Object.assign({}, v); return; }
    fields[f.key] = f.kind === 'number' ? str(v).trim() : v;
  });
  return { presetKey: preset.presetKey, requestId, fields };
}

// requiredFields 全是必填(服务端缺一项就整体拒绝)。本地先标出空着的字段,红字写在字段下;
// 服务端缺项清单超过 120 字会被统一掩码成「系统繁忙」,所以不能只靠回包。
function missing(preset, values) {
  return (preset && Array.isArray(preset.requiredFields) ? preset.requiredFields : []).filter(f => {
    if (!f || !KINDS.includes(f.kind) || f.kind === 'order') return false;
    const v = values[f.key];
    if (f.kind === 'bin_map') return !(v && (f.items || []).every(item => v[item]));
    return v == null || str(v).trim() === '';
  }).map(f => f.key);
}

function moveItem(order, index, delta) {
  const next = order.slice();
  const to = index + delta;
  if (to < 0 || to >= next.length) return next;
  const [item] = next.splice(index, 1);
  next.splice(to, 0, item);
  return next;
}

function newRequestId(seed) {
  return ('pd-' + Date.now().toString(36) + '-' + (seed || Math.random().toString(36).slice(2, 10))).slice(0, 64);
}

// 回包:成功 = 带 draftId;服务端拒绝 = 原文;回执 state UNKNOWN/网络失败 = 结果未确认(同号重查)
function draftResult(res) {
  const data = res && String(res.code) === '200' && res.data && typeof res.data === 'object' ? res.data : null;
  const draftId = data && /^[1-9]\d*$/.test(str(data.draftId)) ? str(data.draftId) : '';
  if (draftId) return { state: 'CREATED', draftId, todos: Array.isArray(data.authorTodos) ? data.authorTodos.map(str) : [] };
  const receipt = res && (res.writeReceipt || res.data && res.data.writeReceipt);
  if (receipt && receipt.state === 'UNKNOWN') return { state: 'UNKNOWN' };
  return { state: 'REJECTED', message: str(res && res.msg) || '建草稿失败' };
}

module.exports = { list, formRows, draftBody, missing, moveItem, newRequestId, draftResult, optionLabels };
