'use strict';
// 地图组队 P 方案(Figma txQoyVyKb3aQdFCnc8qmhj 590:1014 · P1–P6)的纯函数:卡片状态 / 文案 / errorCode 分支 / marker。
// 页面在 subpackageRoam/nearby,只做接线;单测 tests/unit/map-team.test.js 钉住。
// ⚠️ 队伍私密字段(inviteCode / leaderMemberId / ownerType)一律不进视图:decorateTeam 与 myTeamRows 只白名单取字段。
// ⚠️ 失败只按 errorCode 分支,不解析 msg(msg 只原样当失败原因展示)。

const { MAP_AVATAR_COLORS: C } = require('../../utils/play-visual-tokens.js');
// 申请人行与失效倒计时在主包共用(队伍详情页也列申请,第十一轮 g2 j4 F4)
const { expireLeft, applicantRows } = require('../../utils/team-applicant-rows.js');

const RADII = [1000, 3000, 5000, 10000, 20000];
// 走查 P-38(业主):筛选条「全部」= 所有距离,不在范围档位里;服务端照样按距离由近到远截断条数
const ALL_RADIUS = 1000000;
const STATUSES = ['NONE', 'PENDING', 'JOINED', 'LEADER', 'REJECTED'];
const TEAM_MARKER_TAG = 4;   // 与 roam-hangout.parseMarkerId 的 1/2/3 不撞号

function num(v) {
  if (v == null || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function km(m) {
  const n = num(m) || 0;
  return n >= 1000 ? (Math.round(n / 100) / 10) + ' km' : Math.round(n) + ' m';
}

function statusOf(t) {
  const s = t && t.viewerStatus;
  return STATUSES.indexOf(s) >= 0 ? s : 'NONE';
}

function teamName(t) {
  // 第十二轮 k7(j4 F15):没起名的队伍按队长昵称区分
  const title = String((t && t.title) || '').trim();
  if (title) return title;
  const leader = String((t && t.leaderName) || '').trim();
  return leader ? leader + '的队伍' : '玩家队伍';
}

/** P2/P3/P4/P5/被拒/已加入 → 卡片该出什么。按钮对象 { text, action };null = 不出。 */
function teamCardState(t, now) {
  const s = statusOf(t);
  if (s === 'LEADER') return { mode: 'leader', notice: null, primary: null, secondary: null, foot: '' };
  if (s === 'JOINED') {
    return { mode: 'joined', notice: { tone: 'ok', text: '✓ 你已在这支队伍里' }, primary: { text: '进入队伍', action: 'enter' }, secondary: null, foot: '' };
  }
  if (s === 'PENDING') {
    const left = expireLeft(t && t.applyExpireTime, now);
    return { mode: 'pending', notice: { tone: 'wait', text: '◷ 已申请 · 等队长同意' }, primary: null,
      secondary: { text: '撤回申请', action: 'withdraw' },
      foot: left ? left + '后自动失效' : '开场前未处理会自动失效' };
  }
  if (s === 'REJECTED') {
    return { mode: 'rejected', notice: { tone: 'bad', text: '不能再申请这支队伍' }, primary: null, secondary: null, foot: '' };
  }
  if (t && t.viewerHasTicket === true) {
    return { mode: 'apply', notice: { tone: 'ok', text: '✓ 你已持有这一场的票' }, primary: { text: '申请加入', action: 'apply' },
      secondary: null, foot: '' };
  }
  return { mode: 'buy', notice: { tone: 'plain', text: '加入队伍需要先持有这一场的票' }, primary: { text: '去买这场的票', action: 'buy' },
    secondary: null, foot: '' };
}

/** nearby 一行 → 卡片视图。白名单取字段,私密字段进不来。 */
function decorateTeam(t, now) {
  const it = t || {};
  const s = statusOf(it);
  const joined = num(it.joinedCount) || 0;
  const max = num(it.maxMembers) || 0;
  const left = Math.max(0, max - joined);
  const name = teamName(it);
  const free = Number(it.productType) === 2;
  const place = it.addressName ? it.addressName + (it.coordSource === 'GATHER' ? '集合' : '附近') : '';
  const pending = num(it.pendingCount) || 0;
  return {
    teamId: it.teamId,
    activityId: it.activityId,
    topicId: it.topicId,
    name,
    heading: String(it.activityName || '').trim() || name,
    sub: [free ? '自由探索' : '城市定向', place, it.distance != null ? '距你 ' + km(it.distance) : ''].filter(Boolean).join(' · '),
    membersLine: ['队长 ' + (it.leaderName || '玩家'), '已组 ' + joined + ' 人', left ? '还差 ' + left + ' 人满员' : '已满员'].join(' · '),
    faces: (Array.isArray(it.memberAvatars) ? it.memberAvatars : []).filter(Boolean).slice(0, 3),
    plate: s === 'LEADER' ? '我的队伍 · ' + pending + ' 人申请' : s === 'PENDING' ? name + ' · 申请中' : name + ' · ' + joined + '/' + max,
    joinedCount: joined,
    maxMembers: max,
    viewerStatus: s,
    viewerHasTicket: it.viewerHasTicket === true,
    applyExpireTime: it.applyExpireTime,
    latitude: num(it.latitude),
    longitude: num(it.longitude),
    card: teamCardState(it, now),
  };
}

function headerText(n) { return '附近的队伍 · ' + (Number(n) || 0) + ' 支在招募'; }
function rangeText(m) { return '范围 ' + km(m); }
function nextRadius(m) { const i = RADII.indexOf(Number(m)); return RADII[(i + 1) % RADII.length]; }
function myTeamsText(n) { return Number(n) > 0 ? '我的队伍 · ' + Number(n) : '我的队伍'; }

const GONE = { dropTeam: true, title: '这支队伍现在申请不了', why: '队伍已满、活动已开始或正在审核，地图上已经刷新' };
const ERRORS = {
  apply: {
    TICKET_REQUIRED: { title: '要先买这一场的票', patch: { viewerHasTicket: false, viewerStatus: 'NONE' }, primary: '去买票', secondary: '先不买', primaryAction: 'buy' },
    APPLY_REJECTED: { title: '不能再申请这支队伍', patch: { viewerStatus: 'REJECTED' } },
    APPLY_PENDING: { title: '你已经申请过了', patch: { viewerStatus: 'PENDING' } },
    ALREADY_JOINED: { title: '你已在这支队伍里', patch: { viewerStatus: 'JOINED' } },
    TEAM_FULL: GONE, ACTIVITY_STARTED: GONE, TEAM_UNDER_REVIEW: GONE,
    TEAM_NOT_PUBLIC: { dropTeam: true, title: '这支队伍只接受邀请', why: '队长已把它改成邀请制，地图上已经刷新' },
    APPLY_BLOCKED: { title: '暂时不能申请这支队伍' },
  },
  withdraw: {
    APPLY_NOT_PENDING: { title: '这条申请已经处理或失效', refresh: true },
  },
  handle: {
    APPLY_NOT_PENDING: { title: '这条申请已经处理或失效', dropApplicant: true },
    TICKET_REQUIRED: { title: '对方已经退票', dropApplicant: true },
    TEAM_FULL: { title: '队伍已经满员', refresh: true },
    APPLY_BLOCKED: { title: '不能同意这条申请', dropApplicant: true },
  },
};
const FALLBACK_TITLE = { apply: '申请没发出去', withdraw: '撤回没成功', handle: '处理没成功', my: '我的队伍没读到', applications: '申请列表没读到' };

/** 失败 → 失败半屏(cy-result-sheet kind=fail)要画什么 + 界面该怎么改。只认 errorCode。 */
function resolveTeamError(op, res) {
  const body = res || {};
  const hit = (ERRORS[op] || {})[body.errorCode] || null;
  const msg = typeof body.msg === 'string' ? body.msg : '';
  return {
    title: hit ? hit.title : (FALLBACK_TITLE[op] || '操作没成功'),
    why: (hit && hit.why) || msg || '请稍后再试',
    patch: (hit && hit.patch) || null,
    dropTeam: !!(hit && hit.dropTeam),
    dropApplicant: !!(hit && hit.dropApplicant),
    refresh: !!(hit && hit.refresh),
    primary: (hit && hit.primary) || '',
    secondary: (hit && hit.secondary) || '',
    primaryAction: (hit && hit.primaryAction) || '',
  };
}

/** P6:已加入(/api/team/my)+ 申请中/被拒(/api/team/my-applications)。 */
function myTeamRows(joinedTeams, applications, now) {
  const rows = [];
  const seen = {};
  (Array.isArray(joinedTeams) ? joinedTeams : []).forEach((t) => {
    if (!t || !t.id || Number(t.status) === 3 || Number(t.status) === 4 || seen[t.id]) return;
    seen[t.id] = true;
    rows.push({ key: 'j' + t.id, teamId: t.id, name: teamName(t), sub: (num(t.joinedCount) || 0) + '/' + (num(t.maxMembers) || 0) + ' 人',
      badge: '已加入', tone: 'ok', action: { text: '进入队伍', kind: 'primary', act: 'enter' } });
  });
  (Array.isArray(applications) ? applications : []).forEach((a) => {
    if (!a || !a.teamId || seen[a.teamId]) return;
    if (a.applyStatus === 'PENDING') {
      seen[a.teamId] = true;
      const left = expireLeft(a.applyExpireTime, now);
      rows.push({ key: 'p' + a.teamId, teamId: a.teamId, name: teamName(a), sub: '队长 ' + (a.leaderName || '玩家') + (left ? ' · ' + left + '后失效' : ''),
        badge: '申请中', tone: 'wait', action: { text: '撤回申请', kind: 'secondary', act: 'withdraw' } });
    } else if (a.applyStatus === 'REJECTED') {
      seen[a.teamId] = true;
      rows.push({ key: 'r' + a.teamId, teamId: a.teamId, name: teamName(a), sub: '不能再申请这支队伍',
        badge: '队长未同意', tone: 'bad', action: { text: '看附近队伍', kind: 'secondary', act: 'nearby' } });
    }
  });
  return rows;
}

/** 点位:圆头像 + 人数角标(roam-hangout-marker.drawMarker 的 player 形态)。 */
function teamMarkerSpec(t) {
  const s = statusOf(t);
  const mine = s === 'JOINED' || s === 'LEADER';
  return { role: 'player', state: mine ? 'joined' : 'open', color: mine ? C.player : C.cream,
    initial: String((t && t.leaderName) || '队').slice(0, 1), badge: String(num(t && t.joinedCount) || 0) };
}

function teamIconKey(t) {
  const sp = teamMarkerSpec(t);
  return 'tm-' + sp.state + '-' + sp.badge + '-' + encodeURIComponent(String((t && t.leaderAvatar) || sp.initial)).slice(-40);
}

function buildTeamMarkers(teams, icons, fallbackIcon) {
  const ic = icons || {};
  const out = [];
  (Array.isArray(teams) ? teams : []).forEach((t) => {
    const lat = num(t && t.latitude); const lng = num(t && t.longitude);
    if (!t || !t.teamId || lat == null || lng == null) return;
    const icon = ic[teamIconKey(t)];
    out.push({ id: Number(t.teamId) * 10 + TEAM_MARKER_TAG, latitude: lat, longitude: lng,
      width: icon ? 54 : 14, height: icon ? 54 : 14, anchor: { x: 0.5, y: 0.5 }, zIndex: 12, iconPath: icon || fallbackIcon });
  });
  return out;
}

function leaderSub(t) {
  const j = num(t && t.joinedCount) || 0; const m = num(t && t.maxMembers) || 0;
  return '你是队长 · 已组 ' + j + '/' + m + ' · 还能再加 ' + Math.max(0, m - j) + ' 人';
}


module.exports = {
  RADII, ALL_RADIUS, TEAM_MARKER_TAG, teamCardState, decorateTeam, headerText, rangeText, nextRadius, myTeamsText,
  resolveTeamError, myTeamRows, teamMarkerSpec, teamIconKey, buildTeamMarkers, applicantRows, leaderSub,
};
