'use strict';
const { id, integer, centi, precise } = require('../../../utils/orient-score-view.js');
function project(data) {
  if (!data || !Array.isArray(data.list)) return null;
  if (!['SCORE', 'ROUTE'].includes(data.mode)) return {
    mode: data.rankMode || 'LINEAR',
    list: data.list.map(row => ({ ...row, rowKey: String(row.memberId), scoreText: String(row.score) + ' 关' })),
    me: data.me ? { ...data.me, scoreText: String(data.me.score) + ' 关' } : null
  };
  const rows = data.me ? data.list.concat(data.me) : data.list;
  if (!precise(data) || rows.some(row => !row || typeof row.rowKey !== 'string' || !row.rowKey
      || !id(row.rank) || !integer(row.scoreCenti)
      || typeof row.reachedEnding !== 'boolean' || typeof row.mine !== 'boolean'
      || row.elapsedMs != null && (!integer(row.elapsedMs) || integer(row.elapsedMs)[0] === '-'))) return null;
  if (new Set(data.list.map(row => row.rowKey)).size !== data.list.length) return null;
  const item = row => ({ ...row, nickname: row.mine ? '我' : '同行者',
    scoreText: data.mode === 'SCORE' ? centi(row.scoreCenti) : row.reachedEnding ? '已到达结局' : '尚未到达结局',
    elapsedText: row.elapsedMs == null ? '用时待确认' : integer(row.elapsedMs).padStart(4, '0').slice(0, -3) + ' 秒' });
  return { mode: data.mode, list: data.list.map(item), me: data.me ? item(data.me) : null };
}
module.exports = { project };
