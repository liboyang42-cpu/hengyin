// U06-01:队伍席位、邀请期限与入队阻塞码,只翻译 /api/team/info 的 seats / invite / viewer(B6 合同)。
// 不推算「可占未付款席位」「预留申请席位」(已撤回字段);申请不占席;末席由服务端裁决,
// 阻塞码只做提示文案,不替代写接口的回包。字段缺失 = 未知,整行不画,不写 0。

const BLOCKER_TEXT = {
  TEAM_FULL: '队伍已满',
  TEAM_UNDER_REVIEW: '队伍审核中',
  TICKET_REQUIRED: '需要先买这一场的票',
  APPLY_REJECTED: '你的申请已被拒绝',
  APPLY_BLOCKED: '暂时不能加入这支队伍',
  APPLY_PENDING: '申请审核中',
  TEAM_NOT_PUBLIC: '队伍不公开招募',
  INVITE_UNAVAILABLE: '邀请已失效',
  INVITE_REVOKED: '邀请已失效',
  INVITE_EXPIRED: '邀请已过期'
};

function count(v) { return Number.isSafeInteger(v) && v >= 0 ? v : null; }

function pad(n) { return (n < 10 ? '0' : '') + n; }
function fmt(value) {
  if (!value) return '';
  const d = new Date(typeof value === 'string' ? value.replace(/\.\d+/, '').replace(/-/g, '/').replace('T', ' ').replace(/([+-]\d\d):?(\d\d)$/, ' GMT$1$2') : value);
  if (isNaN(d.getTime())) return '';
  return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

// 席位行:持有效票 / 票已失效(只在 >0 时) / 待审申请(只在 >0 时) / 还可加入
function seatRows(seats) {
  if (!seats || typeof seats !== 'object') return [];
  const rows = [];
  // 已入队 / 容量由页头「当前成员」显示,这里不重复
  if (count(seats.paidOccupied) != null) rows.push({ key: 'paid', label: '持有效票', value: String(seats.paidOccupied) });
  if (count(seats.paidTicketMissing) > 0) rows.push({ key: 'missing', label: '票已失效', value: String(seats.paidTicketMissing), bad: true });
  if (count(seats.pendingApplications) > 0) rows.push({ key: 'pending', label: '待审申请', value: String(seats.pendingApplications) });
  if (count(seats.available) != null) rows.push({ key: 'available', label: '还可加入', value: String(seats.available) });
  return rows;
}

// 邀请期限只回放 invite.expiresAt;expired 以服务端为准
function inviteText(invite) {
  if (!invite || typeof invite !== 'object') return '';
  if (invite.expired === true || invite.available === false) return '邀请已失效';
  const at = fmt(invite.expiresAt);
  return at ? at + ' 到期' : '';
}

function blockerText(viewer, kind) {
  const list = viewer && Array.isArray(viewer[kind === 'apply' ? 'applyBlockers' : 'joinBlockers']) ? viewer[kind === 'apply' ? 'applyBlockers' : 'joinBlockers'] : [];
  const code = list.find(c => BLOCKER_TEXT[c]);
  return { code: code || '', text: code ? BLOCKER_TEXT[code] : '' };
}

// r2-join U06-01 席位条:空位数优先用服务端 seats.available,否则 maxMembers - joinedCount;
// 两者都读不到 = 未知,一个空位都不画(不把未知画成 0 或满员)。最多画 12 席。
function openSeats(team, seats) {
  let n = seats && typeof seats === 'object' ? count(seats.available) : null;
  if (n == null && team) {
    const max = count(team.maxMembers);
    const joined = count(team.joinedCount);
    if (max != null && joined != null) n = Math.max(0, max - joined);
  }
  if (n == null) return [];
  const out = [];
  for (let i = 0; i < Math.min(n, 12); i++) out.push(i);
  return out;
}

// 席位条下方的小标签:持有效票 / 票已失效(红) / 待审申请。「还可加入」已由空位表达,不重复。
function seatChips(seats) {
  return seatRows(seats).filter(r => r.key !== 'available');
}

// 加入页没有成员名单:已入队席位按 joinedCount 画实心圈;读不到不画。
function joinedSeats(team) {
  const n = team ? count(team.joinedCount) : null;
  const out = [];
  for (let i = 0; i < Math.min(n || 0, 12); i++) out.push(i);
  return out;
}

module.exports = { seatRows, inviteText, blockerText, fmt, openSeats, seatChips, joinedSeats, BLOCKER_TEXT };
