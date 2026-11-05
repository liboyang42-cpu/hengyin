// 第九轮 m2 第 2 项:同场队伍路线(客户端)。
// 只在有场次(activityId>0)、作者没关、本人开着分享时工作。约 12 秒一次轮询,失败退避到 60 秒;
// 页面藏到后台就停,回来再开。每次只上报自上次成功以来新走的点。没有 socket。
const { createPoller } = require('./poller.js');
const { TEAM_ROUTE_COLORS } = require('../../../utils/play-visual-tokens.js');

const INTERVAL_MS = 12000;
const MAX_INTERVAL_MS = 60000;
const MAX_UPLOAD = 40;

function colorForIndex(i) { return TEAM_ROUTE_COLORS[i % TEAM_ROUTE_COLORS.length]; }

/** 后端回的队伍 → 地图要的线(光晕 + 亮线)、头像图钉、右侧一排 */
function shapeTeams(rows, colorByKey) {
  const teams = [];
  (Array.isArray(rows) ? rows : []).forEach((t) => {
    if (!t || !t.key || !Array.isArray(t.pts)) return;
    const pts = t.pts.filter((p) => Array.isArray(p) && Number.isFinite(Number(p[0])) && Number.isFinite(Number(p[1])))
      .map((p) => ({ latitude: Number(p[0]), longitude: Number(p[1]) }));
    if (!pts.length) return;
    if (!colorByKey[t.key]) colorByKey[t.key] = colorForIndex(Object.keys(colorByKey).length);
    const done = Math.max(0, Number(t.done) || 0);
    teams.push({
      key: String(t.key), color: colorByKey[t.key], name: String(t.name || ''), avatar: String(t.avatar || ''),
      tag: done > 0 ? '第 ' + done + ' 站' : '', points: pts, last: pts[pts.length - 1],
    });
  });
  return teams;
}

function teamLines(teams) {
  const out = [];
  (teams || []).forEach((t) => {
    if (t.points.length < 2) return;
    out.push({ points: t.points, color: t.color + '42', width: 13, borderWidth: 0 });
    out.push({ points: t.points, color: t.color, width: 4, borderColor: '#00000052', borderWidth: 1 }); /* ds-ok 原生 polyline 只吃字面色 */
  });
  return out;
}

/** 头像图钉的 marker id 段:与节点 id、玩家/箭头保留 id 都不撞 */
const PIN_BASE = 880000000;
function teamPins(teams) {
  return (teams || []).map((t, i) => ({
    id: PIN_BASE + i, key: t.key, lat: t.last.latitude, lng: t.last.longitude,
    img: t.avatar, initial: String(t.name || '队').slice(0, 1), color: t.color, tag: t.tag,
  }));
}

/**
 * opts: { api: { state(data), consent(data), sync(data) } → Promise<res>, activityId, readPoints() → [{lat,lng}], done() → number,
 *         onTeams(teams), onState(state) }
 */
function createTeamTrail(opts) {
  const o = opts || {};
  const colorByKey = {};
  let sentCount = 0;
  let stopped = false;
  let state = { available: false, share: false, decided: false };

  function run() {
    if (stopped) return Promise.resolve(true);
    const all = o.readPoints ? o.readPoints() : [];
    if (sentCount > all.length) sentCount = 0;
    const fresh = all.slice(sentCount).slice(-MAX_UPLOAD);
    const upto = all.length;
    return Promise.resolve(o.api.sync({
      activityId: o.activityId,
      pts: fresh.map((p) => [Number(p.lat), Number(p.lng)]),
      done: o.done ? o.done() : 0,
    })).then((res) => {
      if (stopped) return true;
      if (!res || String(res.code) !== '200' || !res.data) return false;
      sentCount = upto;
      const d = res.data;
      if (!d.available || !d.share) {
        state = { available: !!d.available, share: !!d.share, decided: state.decided };
        poller.stop();
        if (o.onTeams) o.onTeams([]);
        if (o.onState) o.onState(state);
        return true;
      }
      if (o.onTeams) o.onTeams(shapeTeams(d.teams, colorByKey));
      return true;
    }, () => false);
  }

  const poller = createPoller({ run, interval: INTERVAL_MS, maxInterval: MAX_INTERVAL_MS });

  return {
    state: () => state,
    load() {
      return Promise.resolve(o.api.state({ activityId: o.activityId })).then((res) => {
        const d = res && String(res.code) === '200' && res.data ? res.data : null;
        state = d && d.available ? { available: true, share: !!d.share, decided: !!d.decided } : { available: false, share: false, decided: false };
        return state;
      }, () => state);
    },
    /** 本人表态。服务端没确认时按本人刚选的来:选了关就本机也关(不轮询、不上传),不能沿用服务端默认的开 */
    consent(share) {
      const failed = () => {
        state = { available: state.available, share: !!share && state.share, decided: false };
        return Object.assign({ ok: false }, state);
      };
      return Promise.resolve(o.api.consent({ activityId: o.activityId, share: !!share })).then((res) => {
        const d = res && String(res.code) === '200' && res.data ? res.data : null;
        if (!d) return failed();
        state = { available: !!d.available, share: !!d.share, decided: true };
        return Object.assign({ ok: true }, state);
      }, failed);
    },
    start() { if (!stopped && state.available && state.share) poller.start(); },
    pause() { poller.stop(); },
    isRunning: () => poller.isActive(),
    /** 本人结束本局:通知服务端删掉自己的点,之后不再轮询 */
    finish() {
      poller.stop();
      if (stopped) return Promise.resolve();
      stopped = true;
      if (!state.available || !state.share) return Promise.resolve();
      return Promise.resolve(o.api.sync({ activityId: o.activityId, pts: [], done: 0, finished: true })).catch(() => {});
    },
    tick: run,
  };
}

module.exports = { createTeamTrail, shapeTeams, teamLines, teamPins, PIN_BASE, INTERVAL_MS, MAX_INTERVAL_MS };
