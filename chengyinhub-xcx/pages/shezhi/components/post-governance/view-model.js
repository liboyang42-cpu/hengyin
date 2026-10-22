// 帖子处置(F0521):me/enforcements 行是 SQL map(下划线键),同一处置可能因多次申诉 LEFT JOIN 出多行。
const TYPE_TEXT = {
  LABEL: '内容加标注', DOWNRANK: '降低推荐', SEARCH_EXCLUDE: '不出现在搜索中', INTERACTION_LIMIT: '限制互动',
  HIDE_PENDING_REVIEW: '隐藏待复核', REMOVE: '移除内容', WARNING: '警告', POST_RATE_LIMIT: '限制发帖频率',
  COMMENT_TIMEOUT: '暂停评论', READ_ONLY: '账号只读', TEMP_SUSPEND: '临时停用', PERMANENT_BAN: '永久停用',
};
const APPEAL_OPEN = ['SUBMITTED', 'REVIEWING'];

function positiveId(value) {
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^\d+$/.test(value))) return null;
  const n = Number(value);
  return Number.isSafeInteger(n) && n > 0 ? n : null;
}
function text(value) { return typeof value === 'string' ? value.trim() : ''; }
function timeText(value) {
  if (typeof value === 'string') return value.slice(0, 16).replace('T', ' ');
  if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
    const d = new Date(value);
    const pad = (n) => (n < 10 ? '0' : '') + n;
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }
  return '';
}

// 只有 UPHELD / REVERSED 是复核结论;ACTIVE 与申诉中都不得写成最终结论。
function stateOf(status, appealStatus) {
  if (status === 'REVERSED' || appealStatus === 'REVERSED') return { key: 'reversed', label: '已撤销处置', note: '申诉复核后原处置已撤销' };
  if (appealStatus === 'UPHELD') return { key: 'upheld', label: '维持处置', note: '申诉复核后维持原处置' };
  if (APPEAL_OPEN.includes(appealStatus)) return { key: 'appealing', label: '申诉处理中', note: '复核完成前不是最终结论' };
  if (status === 'ACTIVE') return { key: 'active', label: '处置生效中', note: '尚未经过申诉复核，不是最终结论' };
  if (status === 'EXPIRED') return { key: 'expired', label: '处置已到期', note: '' };
  return { key: 'unknown', label: '状态待确认', note: '' };
}

function enforcementRows(items) {
  if (!Array.isArray(items)) return null;
  const byId = new Map();
  for (const row of items) {
    if (!row || typeof row !== 'object' || Array.isArray(row)) return null;
    const id = positiveId(row.id);
    if (!id) return null;
    const appealId = positiveId(row.appeal_id);
    const prev = byId.get(id);
    // 同一处置取最新一条申诉(appeal_id 最大),没有申诉的行不覆盖已有申诉行
    if (prev && (!appealId || (prev.appealId && prev.appealId > appealId))) continue;
    byId.set(id, { row, appealId });
  }
  return Array.from(byId.values()).sort((a, b) => Number(b.row.id) - Number(a.row.id)).map(({ row, appealId }) => {
    const status = text(row.status).toUpperCase();
    const appealStatus = appealId ? text(row.appeal_status).toUpperCase() : '';
    const state = stateOf(status, appealStatus);
    const type = text(row.enforcement_type).toUpperCase();
    return {
      id: positiveId(row.id),
      typeText: TYPE_TEXT[type] || '处置',
      targetText: positiveId(row.post_id) ? '帖子处置' : '账号处置',
      timeText: timeText(row.create_time),
      endText: timeText(row.ends_at),
      stateKey: state.key,
      stateLabel: state.label,
      stateNote: state.note,
      decisionReason: state.key === 'upheld' || state.key === 'reversed' ? text(row.decision_reason) : '',
      canAppeal: status === 'ACTIVE' && !appealStatus,
    };
  });
}

module.exports = { enforcementRows, stateOf, TYPE_TEXT };
