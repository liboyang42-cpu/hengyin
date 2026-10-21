// CL6 联合主办展示 · 官方活动参与方(official_event_party)的纯视图模型。
//
// 只认后端 OfficialEventParty 的真实字段:id / partyType / partyId / role / status /
// statusAt / invitedAt。行里**没有**名称与头像 —— 名称由调用方经 options.names
// (键 `${partyType}:${partyId}`)显式传入;没传就退回参与方类型的通用称呼,不编造。
//
// 可见性(用户 2026-10-15 决定:官方活动可有多个主办,展示出来):
// - 公开:ACCEPTED / ACTIVE / COMPLETED(与后端 isManageParty + R9-24 归档只读口径一致)
// - 其余(INVITED 待确认、DECLINED、WITHDRAWN、SUSPENDED、ARCHIVED、未知)只给活动管理者看
// 排序:主办方 → 协办方(按接受时间) → 其他角色(按角色表顺序,再按接受时间)。

const ROLE_LABEL = {
  ORGANIZER: '主办方', CO_ORGANIZER: '协办方', FULFILLMENT_MERCHANT: '商家履约',
  FULFILLMENT_CLUB: '俱乐部履约', SPONSOR: '支持方', PROMOTION_PARTNER: '推广伙伴'
};
const ROLE_ORDER = ['ORGANIZER', 'CO_ORGANIZER', 'FULFILLMENT_MERCHANT', 'FULFILLMENT_CLUB', 'SPONSOR', 'PROMOTION_PARTNER'];

// 管理者视角看「别人」的状态,所以 INVITED 是「待对方确认」,不是收件箱里的「待你确认」。
const STATUS_META = {
  INVITED: { text: '待对方确认', variant: 'warning' },
  ACCEPTED: { text: '已接受', variant: 'success' },
  ACTIVE: { text: '履约中', variant: 'info' },
  COMPLETED: { text: '已完成', variant: 'neutral' },
  DECLINED: { text: '已拒绝', variant: 'danger' },
  WITHDRAWN: { text: '已撤出', variant: 'neutral' },
  SUSPENDED: { text: '已暂停', variant: 'warning' },
  ARCHIVED: { text: '已归档', variant: 'neutral' }
};
const PUBLIC_STATUS = { ACCEPTED: true, ACTIVE: true, COMPLETED: true };

const PARTY_TYPE_FALLBACK = { OFFICIAL: '城瘾官方', MERCHANT: '合作商家', CLUB: '合作俱乐部' };
const HOST_ROLES = { ORGANIZER: true, CO_ORGANIZER: true };

function upper(v) { return v == null ? '' : String(v).trim().toUpperCase(); }

// 后端 Date 可能序列化为毫秒数或 "yyyy-MM-dd HH:mm:ss"(上海时间);只用于排序,解析不了按缺失处理。
function timeOf(v) {
  if (v == null || v === '') return null;
  if (typeof v === 'number') return isFinite(v) ? v : null;
  const s = String(v).trim();
  if (/^\d+$/.test(s)) return Number(s);
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?)?/.exec(s);
  if (!m) return null;
  const t = Date.UTC(+m[1], +m[2] - 1, +m[3], +(m[4] || 0), +(m[5] || 0), +(m[6] || 0)) - 8 * 3600 * 1000;
  return isFinite(t) ? t : null;
}

function roleLabel(role) { return ROLE_LABEL[upper(role)] || '合作方'; }
function statusMeta(status) { return STATUS_META[upper(status)] || { text: '状态待确认', variant: 'neutral' }; }
function isPublicStatus(status) { return !!PUBLIC_STATUS[upper(status)]; }

function nameEntry(names, partyType, partyId) {
  if (!names || typeof names !== 'object' || partyId == null) return null;
  const hit = names[partyType + ':' + partyId];
  if (typeof hit === 'string') return { name: hit.trim(), avatar: '' };
  if (hit && typeof hit === 'object') {
    return { name: String(hit.name || '').trim(), avatar: String(hit.avatar || '').trim() };
  }
  return null;
}

function roleRank(role) {
  const i = ROLE_ORDER.indexOf(role);
  return i < 0 ? ROLE_ORDER.length : i;
}

function compareEntries(a, b) {
  if (a._rank !== b._rank) return a._rank - b._rank;
  // 「其他角色」组内先按角色表顺序(商家履约 → 俱乐部履约 → 支持方 → 推广伙伴)
  if (a._roleRank !== b._roleRank) return a._roleRank - b._roleRank;
  // 公开(已接受)的排在待确认/已拒绝前面;同组按接受时间,再按邀请时间,最后按行 id 稳定。
  if (a.isPublic !== b.isPublic) return a.isPublic ? -1 : 1;
  const ta = a._acceptAt, tb = b._acceptAt;
  if (ta !== tb) {
    if (ta == null) return 1;
    if (tb == null) return -1;
    return ta - tb;
  }
  const ia = a._invitedAt, ib = b._invitedAt;
  if (ia !== ib) {
    if (ia == null) return 1;
    if (ib == null) return -1;
    return ia - ib;
  }
  return (a._rowId || 0) - (b._rowId || 0);
}

function toEntry(party, names) {
  const role = upper(party.role);
  const status = upper(party.status);
  const partyType = upper(party.partyType);
  const isPublic = isPublicStatus(status);
  const named = nameEntry(names, partyType, party.partyId);
  const name = (named && named.name) || PARTY_TYPE_FALLBACK[partyType] || '合作方';
  const meta = statusMeta(status);
  const rowId = Number(party.id);
  return {
    key: party.id != null ? 'p' + party.id : partyType + ':' + party.partyId + ':' + role,
    name,
    avatar: (named && named.avatar) || '',
    named: !!(named && named.name),
    role,
    roleLabel: roleLabel(role),
    status,
    statusText: meta.text,
    statusVariant: meta.variant,
    isPublic,
    isHost: !!HOST_ROLES[role],
    _rank: Math.min(roleRank(role), 2),
    _roleRank: roleRank(role),
    _acceptAt: isPublic ? timeOf(party.statusAt) : null,
    _invitedAt: timeOf(party.invitedAt),
    _rowId: isFinite(rowId) ? rowId : 0
  };
}

function strip(entry) {
  const out = {};
  Object.keys(entry).forEach((k) => { if (k.charAt(0) !== '_') out[k] = entry[k]; });
  return out;
}

/**
 * @param {Array} parties  OfficialEventParty 原样数组(读接口返回什么就传什么)
 * @param {{viewerIsManager?: boolean, names?: Object, heroLimit?: number}} options
 * @returns {{hero: Array, heroMore: number, list: Array, hostCount: number, hasPending: boolean}}
 */
function buildOfficialPartiesView(parties, options) {
  const opts = options || {};
  const manager = opts.viewerIsManager === true;
  const limit = Math.max(1, Number(opts.heroLimit) || 3);
  const seen = {};
  const entries = [];
  (Array.isArray(parties) ? parties : []).forEach((p) => {
    if (!p || typeof p !== 'object') return;
    const e = toEntry(p, opts.names);
    if (seen[e.key]) return;
    seen[e.key] = true;
    if (!e.isPublic && !manager) return;
    entries.push(e);
  });
  const ordered = entries.sort(compareEntries);

  const heroAll = ordered.filter((e) => e.isHost && e.isPublic);
  return {
    hero: heroAll.slice(0, limit).map(strip),
    heroMore: Math.max(0, heroAll.length - limit),
    list: ordered.map(strip),
    hostCount: heroAll.length,
    hasPending: ordered.some((e) => !e.isPublic)
  };
}

module.exports = {
  ROLE_LABEL,
  STATUS_META,
  roleLabel,
  statusMeta,
  isPublicStatus,
  buildOfficialPartiesView,
  _timeOf: timeOf
};
