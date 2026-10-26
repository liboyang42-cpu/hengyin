'use strict';
const { formatDateTimeForDisplay } = require('./index/index-format.js');

const DIMENSIONS = { TASK: '任务', PUZZLE: '解谜', BONUS: '奖励', PENALTY: '处罚', ADJUSTMENT: '调整' };
const KINDS = ['SYNC', 'SETTLE', 'APPEAL', 'DISPUTE'];
function integer(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return '';
  if (typeof value !== 'number' && typeof value !== 'string') return '';
  const text = String(value);
  if (!/^-?(0|[1-9]\d*)$/.test(text)) return '';
  const digits = text.replace(/^-/, '');
  const limit = text[0] === '-' ? '9223372036854775808' : '9223372036854775807';
  return digits.length < 19 || digits.length === 19 && digits <= limit ? text : '';
}
function id(value) { const text = integer(value); return text && text !== '0' && text[0] !== '-' ? text : ''; }
function centi(value, signed) {
  const text = integer(value);
  if (!text) return '—';
  const digits = text.replace(/^-/, '').padStart(3, '0');
  return (text[0] === '-' ? '-' : signed && text !== '0' ? '+' : '')
    + digits.slice(0, -2) + '.' + digits.slice(-2) + ' 分';
}
function precise(value) {
  if (typeof value === 'number') return Number.isSafeInteger(value);
  if (Array.isArray(value)) return value.every(precise);
  if (value && typeof value === 'object') return Object.keys(value).every(key => precise(value[key]));
  return true;
}
function scope(options) {
  options = options || {};
  for (const key of ['sessionId', 'topicId']) {
    if (options[key] !== undefined && options[key] !== '' && !id(options[key])) return null;
  }
  const activityId = options.activityId == null || options.activityId === '' ? '' : integer(options.activityId);
  if (activityId !== '' && activityId !== '0' && !id(activityId)
      || options.activityId != null && options.activityId !== '' && !activityId) return null;
  const result = { sessionId: id(options.sessionId), activityId, topicId: id(options.topicId) };
  if (activityId === '0' && !result.topicId && !result.sessionId) return null;
  return result.sessionId || result.activityId || result.topicId ? result : null;
}
function sameScope(data, target) {
  return !!data && !!id(data.sessionId) && (!target.sessionId || id(data.sessionId) === target.sessionId)
    && (!target.activityId || integer(data.activityId) === target.activityId)
    && (!target.topicId || id(data.topicId) === target.topicId);
}
function sessions(data, target) {
  if (!data || !precise(data) || !Array.isArray(data.sessions)) return null;
  if (!data.sessions.every(row => sameScope(row, target) && ['PREPARED', 'RUNNING', 'FINISHED', 'ABANDONED', 'DISQUALIFIED'].includes(row.phase)
      && [0, 1, 2, 3].includes(row.status))) return null;
  const phases = { PREPARED: '待开始', RUNNING: '进行中', FINISHED: '已完成', ABANDONED: '已放弃', DISQUALIFIED: '已取消资格' };
  const rows = data.sessions.map(row => ({ sessionId: id(row.sessionId), activityId: integer(row.activityId),
    topicId: id(row.topicId), phase: phases[row.phase], startedAt: time(row.startedAt) }));
  return new Set(rows.map(row => row.sessionId)).size === rows.length ? rows : null;
}
function statusText(value) { return ({ 0: '待处理', 1: '已采纳', 2: '已驳回' })[value] || '状态待确认'; }
function time(value) {
  if (value == null) return '';
  if (typeof value !== 'number') return String(value);
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : formatDateTimeForDisplay(date);
}
function settled(data, sessionId) {
  return !!data && data.state === 'SETTLED' && id(data.sessionId) === sessionId
    && !!id(data.settlementId) && !!integer(data.totalCenti)
    && /^\d+$/.test(integer(data.pointsAwarded)) && /^\d+$/.test(integer(data.xpAwarded));
}
function normalize(data, target) {
  if (!precise(data) || !sameScope(data, target) || ![0, 1, 2, 3].includes(data.status)
      || !integer(data.totalCenti) || !data.byDimension || typeof data.byDimension !== 'object' || Array.isArray(data.byDimension)
      || !Object.keys(DIMENSIONS).every(key => integer(data.byDimension[key])) || !Array.isArray(data.timeline)
      || !Array.isArray(data.appeals) || !Array.isArray(data.disputes)) return null;
  const recoveries = data.recoveries == null ? [] : data.recoveries;
  if (!Array.isArray(recoveries) || !recoveries.every(row => row && id(row.id) && id(row.sessionId) === id(data.sessionId)
      && KINDS.includes(row.kind) && [0, 1].includes(row.status)
      && (row.referenceId == null || id(row.referenceId)))) return null;
  const sessionId = id(data.sessionId), ended = [1, 2].includes(data.status);
  if (data.settlement && !settled(data.settlement, sessionId)) return null;
  if (!data.timeline.every(row => row && id(row.entryId) && integer(row.deltaCenti) && integer(row.runningCenti)
      && (row.nodeId == null || id(row.nodeId)) && (row.reversesEntryId == null || id(row.reversesEntryId)))) return null;
  const reviews = rows => rows.map(row => ({ id: id(row.id), status: row.status, statusText: statusText(row.status),
    reason: row.reason || '', note: row.resolutionNote || '', entryId: id(row.entryId), nodeId: id(row.nodeId) }));
  if (![...data.appeals, ...data.disputes].every(row => row && id(row.id) && id(row.sessionId) === sessionId
      && [0, 1, 2].includes(row.status))) return null;
  const dimensions = Object.keys(data.byDimension).map(key => ({ key, label: DIMENSIONS[key] || key, value: centi(data.byDimension[key], true) }));
  if (dimensions.some(row => row.value === '—')) return null;
  return { sessionId, total: centi(data.totalCenti), phase: data.status === 3 ? '已取消资格' : ended ? '对局已结束' : '对局进行中',
    dimensions, timeline: data.timeline.map(row => ({ entryId: id(row.entryId), nodeId: id(row.nodeId),
      dimension: DIMENSIONS[row.dimension] || row.dimension, delta: centi(row.deltaCenti, true),
      running: centi(row.runningCenti), reason: row.reasonCode || '', at: time(row.at), reversesEntryId: id(row.reversesEntryId),
      appealPending: data.appeals.some(a => Number(a.status) === 0 && id(a.entryId) === id(row.entryId)) })),
    recoveries: recoveries.map(row => ({ id: id(row.id), kind: row.kind, status: row.status,
      statusText: row.status === 0 ? '人工核对中' : '已记录核对意见', note: row.resolutionNote || '', referenceId: id(row.referenceId) })),
    appeals: reviews(data.appeals), disputes: reviews(data.disputes),
    settlement: data.settlement ? { id: id(data.settlement.settlementId), total: centi(data.settlement.totalCenti),
      points: integer(data.settlement.pointsAwarded), xp: integer(data.settlement.xpAwarded), at: time(data.settlement.settledAt) } : null,
    pendingReviewCount: integer(data.pendingReviewCount) || '0',
    deadline: time(data.disputeWindowDeadline), canSync: !data.settlement && data.status !== 3, canSettle: ended && !data.settlement && (data.pendingReviewCount == null || integer(data.pendingReviewCount) === '0'),
    canAppeal: true, canDispute: ended && !!data.disputeWindowDeadline && !data.disputes.some(a => Number(a.status) === 0) };
}
function writeKey(owner, sessionId, kind) { return owner + ':orient-score:' + sessionId + ':' + kind; }
function receiptMatches(data, pending, owner, sessionId, payload) {
  if (!precise(data) || !pending || !pending.requestId) return false;
  if (pending.kind === 'SYNC') return !!data && data.state === 'SYNCED' && data.kind === 'SYNC'
    && id(data.sessionId) === sessionId && id(data.memberId) === owner && data.idempotencyKey === pending.requestId
    && /^\d+$/.test(integer(data.added)) && !!integer(data.totalCenti);
  if (pending.kind === 'SETTLE') return settled(data, sessionId);
  if (!['APPEAL', 'DISPUTE'].includes(pending.kind)) return false;
  const row = data && data.record || data;
  if (!row || !id(row.id) || id(row.memberId) !== owner || id(row.sessionId) !== sessionId
      || row.idempotencyKey !== pending.requestId || ![0, 1, 2].includes(row.status)) return false;
  if (data.record && (data.kind !== pending.kind || data.idempotencyKey !== pending.requestId
      || data.state !== ({ 0: 'PENDING', 1: 'ACCEPTED', 2: 'REJECTED' })[row.status])) return false;
  return !payload || row.reason === payload.reason && (pending.kind !== 'APPEAL'
    || id(row.entryId) === id(payload.entryId) && id(row.nodeId) === id(payload.nodeId));
}
function leaderboard(data, sessionId) {
  if (!data || !precise(data) || id(data.sessionId) !== sessionId || !['SCORE', 'ROUTE'].includes(data.mode)
      || !Array.isArray(data.list) || data.list.length > 50) return null;
  const valid = row => row && id(row.rowKey) && id(row.rank) && integer(row.scoreCenti)
    && (row.elapsedMs === null || /^\d+$/.test(integer(row.elapsedMs)))
    && typeof row.mine === 'boolean' && typeof row.reachedEnding === 'boolean';
  if (!data.list.every(valid) || data.me != null && !valid(data.me)
      || new Set(data.list.map(row => row.rowKey)).size !== data.list.length) return null;
  const project = row => ({ rowKey: row.rowKey, rank: integer(row.rank), mine: row.mine,
    score: centi(row.scoreCenti), reachedEnding: row.reachedEnding,
    elapsed: row.elapsedMs === null ? '用时待确认' : integer(row.elapsedMs) + ' 毫秒' });
  return { mode: data.mode, list: data.list.map(project), me: data.me ? project(data.me) : null };
}
function failure(body, status) {
  const code = body && (body.errorCode || body.err);
  if (['ORIENT_SCORING_DISABLED', 'ORIENT_SESSION_DISABLED', 'ORIENT_SCORING_NOT_APPLICABLE', 'ORIENT_PRODUCT_TYPE_UNSUPPORTED'].includes(code)) return { kind: 'not-started', title: '城市定向计分尚未启用' };
  if (Number(status) === 401 || Number(status) === 403 || body && [401, 403, 2].includes(Number(body.code))
      || code === 'ORIENT_LOGIN_REQUIRED' || body && body.msg === '请先登录') return { kind: 'no-permission', title: '请使用本人账号查看对局' };
  if (code === 'ORIENT_BAD_REQUEST') return { kind: 'missing-param', title: '请从合法的对局记录进入' };
  if (['ORIENT_SESSION_NOT_FOUND', 'ORIENT_NOT_FOUND'].includes(code)) return { kind: 'empty', title: '没有可查看的本人对局' };
  return { kind: 'offline', title: '计分暂时无法读取' };
}
function rejected(body, status) {
  return [400, 401, 403, 404, 405, 413, 415, 426, 429].includes(Number(status))
    || !!(body && /^(ORIENT_|FORBIDDEN)/.test(body.errorCode || body.err || ''));
}
module.exports = { leaderboard, KINDS, integer, id, centi, precise, scope, sameScope, sessions, normalize, settled, writeKey, receiptMatches, failure, rejected };
