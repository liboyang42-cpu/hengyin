// r2-join 落地页「怎么玩」：只翻译 /api/play/player-journey/choices 已下发的事实。
// - 一起玩 = SHARED_TEAM；各自比 = INDEPENDENT 且 competition=true；其余 INDEPENDENT 写「各自玩」。
// - 「全队一个分数」「结束后排名」只在服务端口径支持时出现，读不到就不写。
// - 实时/随时（Live/Anytime）服务端没有字段，不画。
const view = require('./journey-view.js');

const SCOPE = { PER_MEMBER: '各自', PER_TEAM: '全队共享', SHARED_WORLD: '全场共享' };

function rec(v) { return v !== null && typeof v === 'object' && !Array.isArray(v); }

function tileOf(raw, mode) {
  const team = raw.key === 'SHARED_TEAM';
  const compete = !team && raw.competition === true;
  let sub = '';
  if (team && raw.scoreScope === 'PER_TEAM') sub = '全队一个分数';
  else if (compete) sub = '结束后排名';
  const rules = [];
  if (SCOPE[raw.hintScope]) rules.push({ k: '提示', v: SCOPE[raw.hintScope], on: raw.hintScope !== 'PER_MEMBER' });
  if (SCOPE[raw.progressScope]) rules.push({ k: '进度', v: SCOPE[raw.progressScope], on: raw.progressScope !== 'PER_MEMBER' });
  if (SCOPE[raw.scoreScope]) rules.push({ k: '计分', v: SCOPE[raw.scoreScope], on: raw.scoreScope !== 'PER_MEMBER' });
  const team4 = (mode.rows || []).find(r => r.k === '队伍');
  if (team4) rules.push({ k: '队伍', v: team4.v, on: true });
  return {
    key: raw.key,
    name: team ? '一起玩' : compete ? '各自比' : '各自玩',
    sub,
    icon: team ? 'tab-club' : compete ? 'flag' : 'walk',
    available: mode.available,
    reason: mode.reason,
    rules
  };
}

/** choices 原始回包 → 模式卡。不可用配置或没有模式时返回 null（整块不画）。 */
function modeTiles(data, supported) {
  const base = view.choicesView(data, supported);
  if (!base || base.unavailable) return null;
  const raws = (Array.isArray(data.choices) ? data.choices : []).filter(rec);
  if (raws.length !== base.modes.length) return null;
  const tiles = raws.map((raw, i) => tileOf(raw, base.modes[i]));
  const first = tiles.find(t => t.available) || tiles[0];
  return { tiles, active: first ? first.key : '' };
}

module.exports = { modeTiles };
