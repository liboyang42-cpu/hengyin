'use strict';

/* 玩家闭环 P1/P2/P4/P5/P7/P8/P9 与 §23.4 多作用域榜的展示投影。
 * 只把服务端事实翻成界面行；不计算金额、名次、资格或席位数。
 * 规则(UI优化施工清单 §二/§十二/§24.3):未知不显示成 0；应计不显示成到账；
 * 服务端没给出可用事实的动作不出按钮。 */

const isNum = value => typeof value === 'number' && Number.isFinite(value);
const isId = value => (typeof value === 'number' && Number.isSafeInteger(value) && value > 0)
  || (typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value));
const record = value => !!value && typeof value === 'object' && !Array.isArray(value);
const list = value => (Array.isArray(value) ? value : []);
const UNKNOWN = '未知';

function pad(n) { return n < 10 ? '0' + n : String(n); }

function parseDate(value) {
  if (value == null || value === '') return null;
  if (typeof value === 'number') return Number.isFinite(value) ? new Date(value) : null;
  const text = String(value).trim();
  const date = new Date(/^\d{4}-\d{2}-\d{2} /.test(text) ? text.replace(' ', 'T') + '+08:00' : text);
  return Number.isFinite(date.getTime()) ? date : null;
}

// 服务端时间统一按北京时间展示；不按手机时区另算周界。
function dateText(value, withTime) {
  const date = parseDate(value);
  if (!date) return '';
  const local = new Date(date.getTime() + 8 * 3600 * 1000);
  const day = local.getUTCFullYear() + '.' + pad(local.getUTCMonth() + 1) + '.' + pad(local.getUTCDate());
  return withTime ? day + ' ' + pad(local.getUTCHours()) + ':' + pad(local.getUTCMinutes()) : day;
}

function durationText(seconds) {
  if (!isNum(seconds) || seconds < 0) return UNKNOWN;
  const s = Math.floor(seconds);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  return h > 0 ? h + ':' + pad(m) + ':' + pad(r) : pad(m) + ':' + pad(r);
}

/* ——— P7 本局战报 ——— */
const RESULT_LABEL = {
  LEGITIMATE_COMPLETION: '已通关',
  ASSISTED_COMPLETION: '辅助完成',
  ENDED: '已结束',
  IN_PROGRESS: '进行中',
  NOT_STARTED: '未开始'
};

function reportView(data) {
  if (!record(data)) return null;
  const completed = isNum(data.completedNodes) ? data.completedNodes : null;
  const total = isNum(data.totalNodes) && data.totalNodes > 0 ? data.totalNodes : null;
  const score = record(data.score) ? data.score : {};
  const scored = score.state === 'SCORED' && isNum(score.score);
  const rank = record(data.rank) ? data.rank : {};
  const ranked = rank.qualified === true && isNum(rank.rank);
  const done = data.runState === 'COMPLETED';
  const view = {
    runId: isId(data.runId) ? String(data.runId) : '',
    topicId: isId(data.topicId) ? String(data.topicId) : '',
    activityId: isId(data.activityId) ? String(data.activityId) : '',
    legacy: data.scopeKind !== 'RUN',
    result: RESULT_LABEL[data.completionKind] || RESULT_LABEL[data.runState] || UNKNOWN,
    win: done && data.completionKind === 'LEGITIMATE_COMPLETION',
    finished: done || data.runState === 'ENDED',
    inProgress: data.runState === 'IN_PROGRESS',
    dateLabel: dateText(data.completedAt),
    nodesText: completed == null ? UNKNOWN : completed + '/' + (total == null ? '?' : total),
    elapsedText: durationText(data.elapsedSeconds),
    scoreText: scored ? String(score.score) : '本局不计分',
    scored,
    penaltyText: scored && isNum(score.penalties) && score.penalties > 0 ? '-' + score.penalties : '',
    rankText: ranked ? '第 ' + rank.rank + ' 名' : '暂无名次',
    rankSub: ranked && isNum(rank.score) ? '完成节点数 ' + rank.score : '',
    rankScope: rank.scope === 'quest' && isId(rank.scopeId) ? String(rank.scopeId) : '',
    nodes: list(data.nodes).filter(record).map(node => ({
      key: String(node.nodeId),
      name: node.name || '节点',
      mode: node.completionMode === 'SKIPPED' ? '已跳过' : node.completionMode === 'REVEALED' ? '看过答案' : '',
      scoreText: node.scored === true && isNum(node.score) ? String(node.score) : '不计分'
    })),
    rewardsText: data.rewardsState === 'AVAILABLE' ? rewardsSummary(data.rewards) : ''
  };
  view.shareTitle = view.win ? '我通关了，一起来玩' : '一起来玩';
  return view;
}

// 背包是主题/活动范围的既有物品，不写「本局获得」。
function rewardsSummary(rewards) {
  if (!record(rewards)) return '';
  const items = list(rewards.items).length || list(rewards.cards).length || list(rewards.objects).length;
  return items > 0 ? items + ' 件' : '';
}

/* ——— §23.4 / P9 多作用域榜 ——— */
const SCOPE_LABEL = { quest: '单关', city: '城市', country: '国家', world: '世界', club: '俱乐部' };

// 只出服务端能接收的作用域：城市/国家需要冻结地理码，俱乐部需要真实俱乐部ID。
function rankScopes(ctx) {
  const c = record(ctx) ? ctx : {};
  const tabs = [];
  if (isId(c.topicId)) tabs.push({ key: 'quest', label: SCOPE_LABEL.quest, scopeId: String(c.topicId) });
  if (c.cityCode && /^[0-9A-Za-z_-]{2,32}$/.test(String(c.cityCode))) tabs.push({ key: 'city', label: SCOPE_LABEL.city, scopeId: String(c.cityCode) });
  if (c.countryCode && /^[A-Za-z]{2}$/.test(String(c.countryCode))) tabs.push({ key: 'country', label: SCOPE_LABEL.country, scopeId: String(c.countryCode).toUpperCase() });
  tabs.push({ key: 'world', label: SCOPE_LABEL.world, scopeId: '' });
  if (isId(c.clubId)) tabs.push({ key: 'club', label: SCOPE_LABEL.club, scopeId: String(c.clubId) });
  if (isId(c.teamId)) tabs.push({ key: 'team', label: '队内', scopeId: String(c.teamId) });
  return tabs;
}

function initial(name) { return String(name || '玩').trim().slice(0, 1) || '玩'; }

function rankView(data, requested) {
  if (!record(data)) return null;
  // 服务端拒绝或改了作用域就不冒充所选榜。
  if (requested && (data.scope !== requested.scope || String(data.scopeId || '') !== String(requested.scopeId || '')
    || data.period !== requested.period)) return { mismatch: true };
  const entries = list(data.entries).filter(entry => record(entry) && isNum(entry.rank)).map(entry => ({
    key: String(entry.memberId) + ':' + entry.rank,
    rank: entry.rank,
    name: entry.nickname || '玩家',
    initial: initial(entry.nickname),
    avatar: entry.avatar || '',
    scoreText: isNum(entry.score) ? String(entry.score) : UNKNOWN
  }));
  const me = record(data.me) && isNum(data.me.rank) ? {
    rank: data.me.rank,
    scoreText: isNum(data.me.score) ? String(data.me.score) : UNKNOWN
  } : null;
  const coverage = record(data.coverage) ? data.coverage : {};
  const window = data.period === 'week' && data.periodStart && data.periodEnd
    ? dateText(data.periodStart) + ' – ' + dateText(new Date(parseDate(data.periodEnd).getTime() - 1000)) : '';
  return {
    entries,
    me,
    meText: me ? '第 ' + me.rank + ' 名' : '暂无名次',
    basis: data.scoreBasis === 'COMPLETED_NODES' ? '完成节点数' : '',
    window,
    unattributed: isNum(coverage.unattributedRuns) && coverage.unattributedRuns > 0
      ? coverage.unattributedRuns + ' 局缺少冻结地理，未计入' : ''
  };
}

// P9 队内成员名次：未完成任务者没有名次，不补第一名；看板关闭时不造排名。
function teamRankView(data) {
  if (!record(data)) return null;
  const members = list(data.members).filter(record);
  const ranked = members.filter(m => m.ranked === true && isNum(m.rank)).sort((a, b) => a.rank - b.rank);
  const unranked = members.filter(m => !(m.ranked === true && isNum(m.rank)));
  const row = m => ({
    key: String(m.memberId || m.nickname || Math.random()),
    rank: m.ranked === true && isNum(m.rank) ? m.rank : null,
    name: m.nickname || m.name || '队员',
    initial: initial(m.nickname || m.name),
    avatar: m.avatar || '',
    scoreText: isNum(m.doneTaskCount) ? String(m.doneTaskCount) : UNKNOWN,
    mine: m.mine === true
  });
  return {
    entries: ranked.map(row).concat(unranked.map(row)),
    basis: data.rankBasis === 'COMPLETED_TASK_COUNT' ? '完成任务数' : '',
    hasRank: ranked.length > 0
  };
}

/* ——— P8 同行回忆 ——— */
function memoryView(data) {
  if (!record(data)) return null;
  const moments = list(data.moments).filter(record).map(m => ({
    key: String(m.nodeId),
    name: m.name || '节点',
    text: m.fragmentText || '',
    pic: m.picUrl || '',
    at: dateText(m.checkinTime, true)
  }));
  return {
    moments,
    photos: moments.filter(m => m.pic),
    participants: isNum(data.participantCount) && data.participantCount > 1 ? data.participantCount + ' 人同行' : '',
    // 没有同意事实就不展示同行私照；只显示本人照片。
    peerPhotos: data.photoShareCapability === 'AVAILABLE'
  };
}

/* ——— P4 玩法模式 ——— */
const SCOPE_TEXT = { PER_MEMBER: '各自', PER_TEAM: '全队共享', SHARED_WORLD: '全场共享' };
const UNAVAILABLE_TEXT = {
  TEAM_MODE_DISABLED: '本主题未开启组队',
  RUN_CONFIG_SNAPSHOT_MISSING: '本局规则读取不到',
  UNAVAILABLE: '暂不可选'
};

function choicesView(data, supported) {
  if (!record(data)) return null;
  const caps = list(data.requiredCapabilities).filter(cap => record(cap) && cap.key);
  // supported 未传 = 本端未声明能力清单，不据此拦截，也不声称已支持。
  const missing = Array.isArray(supported) ? caps.filter(cap => supported.indexOf(cap.key) < 0).map(cap => cap.key) : [];
  const modes = list(data.choices).filter(record).map(choice => {
    const team = choice.key === 'SHARED_TEAM';
    const cap = record(choice.teamCapacity) ? choice.teamCapacity : null;
    return {
      key: choice.key,
      title: team ? '协作共享' : '各自挑战',
      rows: [
        { k: '进度', v: SCOPE_TEXT[choice.progressScope] || UNKNOWN },
        { k: '提示', v: SCOPE_TEXT[choice.hintScope] || UNKNOWN },
        { k: '计分', v: SCOPE_TEXT[choice.scoreScope] || UNKNOWN }
      ].concat(team && cap && isNum(cap.joinedCount) && isNum(cap.maxMembers)
        ? [{ k: '队伍', v: cap.joinedCount + '/' + cap.maxMembers }] : []),
      available: choice.available === true && missing.length === 0,
      reason: choice.available !== true ? (UNAVAILABLE_TEXT[choice.unavailableReason] || '暂不可选')
        : missing.length ? '当前版本暂不支持本主题玩法，请更新后再试' : '',
      teamId: cap && isId(cap.teamId) ? String(cap.teamId) : ''
    };
  });
  return {
    frozen: data.configState === 'FROZEN_RUN',
    unavailable: data.configState === 'UNAVAILABLE' || data.configState === 'RUN_CONFIG_SNAPSHOT_MISSING' || !modes.length,
    modes,
    teamId: (modes.find(m => m.teamId) || {}).teamId || ''
  };
}

/* ——— P5 票内席位 ——— */
const SEAT_LABEL = {
  BUYER: '我', VERIFIED: '已核销', RESERVED: '已占用', AVAILABLE: '可邀请',
  EXPIRED: '已过期', INVITED: '等待领取', CLAIMED: '已领取'
};

function seatsView(data) {
  if (!record(data)) return null;
  const free = isNum(data.freeSeats) ? data.freeSeats : null;
  const seats = list(data.seats).filter(record).map(seat => ({
    key: String(seat.seatNo),
    no: seat.seatNo,
    label: SEAT_LABEL[seat.state] || UNKNOWN,
    state: seat.state,
    expires: seat.state === 'INVITED' && seat.invitationExpiresAt ? dateText(seat.invitationExpiresAt, true) + ' 前有效' : '',
    // 撤销只对等待领取且服务端声明支持时出现；释放已领席位按 §24.3 暂不开放。
    canCancel: seat.state === 'INVITED' && data.seatCancelSupported === true && isId(seat.invitationId),
    invitationId: isId(seat.invitationId) ? String(seat.invitationId) : ''
  }));
  return {
    registrationId: isId(data.registrationId) ? String(data.registrationId) : '',
    topicId: isId(data.topicId) ? String(data.topicId) : '',
    ownerType: data.ownerType,
    ownerId: isId(data.ownerId) ? String(data.ownerId) : '',
    countText: isNum(data.seatCount) ? data.seatCount + ' 人票' : UNKNOWN,
    freeText: free == null ? UNKNOWN : String(free),
    canInvite: data.buyerIsCaller === true && data.readOnly === false && free != null && free > 0,
    expires: data.expiresAt ? dateText(data.expiresAt, true) : '',
    seats
  };
}

const SEAT_ERROR_TEXT = {
  400: '链接无效',
  403: '请先登录',
  409: ''
};

/* ——— P1 落地 ——— */
function landingKind(options) {
  const o = record(options) ? options : {};
  const kind = o.k === 'g' ? 'gift' : o.k === 's' ? 'seat' : o.k === 'r' ? 'referral' : '';
  const token = typeof o.t === 'string' ? decodeURIComponent(o.t) : '';
  return kind && token && token.length <= 512 ? { kind, token } : null;
}

const REFERRAL_STATE = {
  INVALID_TOKEN: '邀请链接无效',
  TOKEN_EXPIRED: '邀请链接已过期',
  TOKEN_REVOKED: '邀请已撤销',
  TARGET_UNAVAILABLE: '这个主题暂不可见',
  INVITER_UNAVAILABLE: '邀请人已不可见'
};
const GIFT_STATE = {
  INVALID_TOKEN: '礼物链接无效',
  TOKEN_EXPIRED: '礼物链接已过期',
  CLAIMED: '礼物已被领取',
  GIFT_EXPIRED: '礼物已过期',
  GIFT_REVOKED: '礼物已撤回',
  PRODUCT_UNAVAILABLE: '礼物商品暂不可用'
};

function referralView(data) {
  if (!record(data)) return null;
  const valid = data.state === 'VALID';
  const target = record(data.target) ? data.target : {};
  return {
    valid,
    stateText: valid ? '' : (REFERRAL_STATE[data.state] || '邀请链接无效'),
    inviter: valid && record(data.inviter) ? { name: data.inviter.nickname || '朋友', avatar: data.inviter.avatar || '', initial: initial(data.inviter.nickname) } : null,
    // 普通推荐只说邀请，不承诺免费或优惠。
    headline: '朋友邀请你一起玩',
    title: valid ? (target.title || '') : '',
    targetType: target.type,
    targetId: isId(target.id) ? String(target.id) : '',
    expires: valid && data.expiresAt ? dateText(data.expiresAt, true) + ' 前有效' : '',
    closed: data.registrationState === 'CLOSED',
    canView: valid && data.nextAction === 'VIEW_TARGET'
  };
}

function giftView(data) {
  if (!record(data)) return null;
  const product = record(data.product) ? data.product : {};
  const valid = data.state === 'VALID';
  return {
    valid,
    stateText: valid ? '' : (GIFT_STATE[data.state] || '礼物链接无效'),
    headline: valid ? '朋友送你一局' : '',
    title: product.topicTitle || product.productName || '',
    topicId: isId(product.topicId) ? String(product.topicId) : '',
    expires: valid && data.claimExpiresAt ? dateText(data.claimExpiresAt, true) + ' 前领取' : '',
    canClaim: valid && data.nextAction === 'CLAIM_GIFT'
  };
}

/* ——— P2 推广中心 ——— */
const BUCKETS = [
  { key: 'pending', label: '待确认' },
  { key: 'confirmed', label: '已确认' },
  { key: 'paid', label: '已标记付款' },
  { key: 'reversed', label: '已冲销' }
];

function money(bucket) {
  if (!record(bucket)) return UNKNOWN;
  if (bucket.amountUnavailableReason === 'MULTIPLE_CURRENCIES') return '多币种暂不合计';
  if (bucket.netAmount == null) return UNKNOWN;
  const n = Number(bucket.netAmount);
  return Number.isFinite(n) ? '¥' + n.toFixed(2) : UNKNOWN;
}

function affiliateView(data) {
  if (!record(data)) return null;
  const allocations = record(data.allocations) ? data.allocations : {};
  const buckets = BUCKETS.map(b => {
    const bucket = allocations[b.key];
    return { key: b.key, label: b.label, amount: money(bucket),
      count: record(bucket) && isNum(bucket.allocationCount) ? bucket.allocationCount + ' 笔' : '' };
  });
  const any = BUCKETS.some(b => record(allocations[b.key]) && allocations[b.key].allocationCount > 0);
  const campaigns = list(data.campaigns).filter(record).map(c => ({
    key: String(c.campaignKey || '') + ':' + String(c.targetId || ''),
    title: c.targetTitle || '主题',
    paid: isNum(c.paidOrders) ? c.paidOrders : null,
    attributed: isNum(c.attributedOrders) ? c.attributedOrders : null,
    refunded: isNum(c.refundedOrders) ? c.refundedOrders : null
  }));
  const links = list(data.credentials).filter(record).map(c => ({
    key: String(c.targetType) + ':' + String(c.targetId) + ':' + String(c.issuedAt || ''),
    title: c.targetTitle || '主题',
    status: c.active === true ? '有效' : '已失效',
    active: c.active === true,
    expires: c.expiresAt ? dateText(c.expiresAt) + ' 到期' : ''
  }));
  const held = record(data.held) && isNum(data.held.orders) && data.held.orders > 0 ? data.held.orders + ' 笔待核算' : '';
  const payout = record(data.payout) ? data.payout : {};
  return {
    buckets,
    hasAllocations: any,
    hasRecords: any || campaigns.length > 0,
    campaigns,
    links,
    held,
    // 只有服务端声明可提现才出按钮；玩家没有提现通道时只读。
    canPayout: payout.available === true && typeof payout.endpoint === 'string' && !!payout.endpoint
  };
}

/* ——— qx-u2 首屏 hero(ref 1)：两行大标题 + 一行弱化事实 ———
 * 只换行，不改服务端/既有文案；未登记的标题原样一行。 */
const HERO_LINES = {
  '朋友邀请你一起玩': ['朋友邀请你', '一起玩'],
  '朋友送你一局': ['朋友送你', '一局'],
  '朋友邀请你同行': ['朋友邀请你', '同行'],
  '已领取同行席位': ['已领取', '同行席位'],
  '礼物已领取': ['礼物', '已领取'],
  '本局战报': ['本局', '战报'],
  '主题记录': ['主题', '记录']
};

function heroLines(text) {
  const t = String(text || '').trim();
  if (!t) return [];
  return (HERO_LINES[t] || [t]).slice();
}

function factLine(facts) {
  return list(facts).filter(f => record(f) && f.v).map(f => (f.k ? f.k + ' ' : '') + f.v).join(' · ');
}

function newRequestId(prefix) {
  return String(prefix || 'journey') + '-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
}

module.exports = {
  isId, dateText, durationText,
  reportView, rankScopes, rankView, teamRankView, memoryView, choicesView, seatsView,
  landingKind, referralView, giftView, affiliateView, newRequestId, SEAT_ERROR_TEXT,
  heroLines, factLine
};
