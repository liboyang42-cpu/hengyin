// C1 channels-structure(2026-11-15):俱乐部频道目录与管理的纯逻辑,页面 pages/club/channels 只负责请求和渲染。
// 权限只由服务端判定(MANAGE_CHANNEL:主理人/管理员);这里的判断只决定显示哪些按钮。
// 接口与 App 同一套:GET /api/guilds/1/{clubId}/channels(成员目录)、GET .../channels/manage(管理清单,
// 非管理员 403)、POST .../channels(创建)、PATCH /api/channels/{id}(改名/话题/慢速)、
// POST /api/channels/{id}/edit(PATCH 的 POST 别名,wx.request 不支持 PATCH)、POST /api/channels/{id}/archive(归档)、POST .../channels/reorder(排序与移动)。

const GUILD_CLUB = 1;
const KIND_CATEGORY = 0;
const KIND_TEXT = 1;
const KIND_ANNOUNCEMENT = 2;
const MAX_POSITION = 100000;
// 写操作的审计原因走请求头 X-Audit-Log-Reason,与 body.reason 必须逐字一致;只用 ASCII,避免请求头编码问题。
const AUDIT_REASON = 'club channel manage (mini-program)';

const KIND_LABELS = { 0: '分类', 1: '文字频道', 2: '公告频道', 3: '话题频道' };

function kindLabel(kind) {
  return KIND_LABELS[Number(kind)] || '频道';
}

function hasPermission(row, name) {
  const list = row && Array.isArray(row.my_permissions_list) ? row.my_permissions_list : [];
  return list.indexOf(name) >= 0;
}

function decorateChannel(row) {
  const kind = Number(row && row.kind);
  const status = Number(row && row.status);
  return {
    id: String(row.id),
    name: String(row.name || ''),
    topic: row.topic ? String(row.topic) : '',
    kind: kind,
    kindLabel: kindLabel(kind),
    status: status,
    archived: status === 1,
    isDefault: Number(row.is_default) === 1,
    // 公告频道:普通成员只读,服务端拒绝成员发言;这里只用于提示文案。
    readOnly: status === 1 || (row.my_permissions_list ? !hasPermission(row, 'SEND_MESSAGE') : kind === KIND_ANNOUNCEMENT),
    unread: Number(row.unread_count || 0)
  };
}

// 成员目录 → [{ key, title, channels }],未分组在前,分类按服务端顺序。
function directorySections(dir) {
  const sections = [];
  const ungrouped = dir && Array.isArray(dir.ungrouped) ? dir.ungrouped : [];
  if (ungrouped.length) sections.push({ key: 'top', title: '', channels: ungrouped.map(decorateChannel) });
  const groups = dir && Array.isArray(dir.groups) ? dir.groups : [];
  groups.forEach((g) => {
    const channels = Array.isArray(g.channels) ? g.channels.map(decorateChannel) : [];
    if (!channels.length || !g.category) return;
    sections.push({ key: String(g.category.id), title: String(g.category.name || ''), channels: channels });
  });
  return sections;
}

function liveRows(rows) {
  return (Array.isArray(rows) ? rows : []).filter((r) => Number(r.status) === 0 || Number(r.status) === 1);
}

function ordered(rows) {
  return rows.slice().sort((a, b) => {
    const pa = Number(a.position) || 0;
    const pb = Number(b.position) || 0;
    if (pa !== pb) return pa - pb;
    return Number(a.id) - Number(b.id);
  });
}

function categories(rows) {
  return ordered(liveRows(rows).filter((r) => Number(r.kind) === KIND_CATEGORY));
}

function channelsIn(rows, parentId) {
  return ordered(liveRows(rows).filter((r) => Number(r.kind) !== KIND_CATEGORY && String(r.parent_id) === String(parentId)));
}

function siblings(rows, row) {
  return Number(row.kind) === KIND_CATEGORY ? categories(rows) : channelsIn(rows, row.parent_id);
}

function isDefaultRow(row) {
  return Number(row && row.is_default) === 1;
}

function canMove(rows, row, direction) {
  if (!row || isDefaultRow(row)) return false;
  const list = siblings(rows, row).filter((r) => !isDefaultRow(r));
  const index = list.findIndex((r) => String(r.id) === String(row.id));
  if (index < 0) return false;
  return direction === 'up' ? index > 0 : index < list.length - 1;
}

// 与邻居交换后重新编号 1..n(默认频道固定 0),只返回位置有变化的行。
function planMove(rows, row, direction) {
  if (!canMove(rows, row, direction)) return [];
  const list = siblings(rows, row).filter((r) => !isDefaultRow(r));
  const index = list.findIndex((r) => String(r.id) === String(row.id));
  const other = direction === 'up' ? index - 1 : index + 1;
  const tmp = list[index];
  list[index] = list[other];
  list[other] = tmp;
  const items = [];
  list.forEach((r, i) => {
    if ((Number(r.position) || 0) !== i + 1) items.push(placement(r, String(r.parent_id), i + 1));
  });
  return items;
}

// 把频道(不是分类)移到某个分类末尾,parentId '0' 表示移出分类。
function planMoveTo(rows, row, parentId) {
  const target = String(parentId);
  if (!row || Number(row.kind) === KIND_CATEGORY || isDefaultRow(row) || String(row.parent_id) === target) return [];
  if (target !== '0' && !categories(rows).some((c) => String(c.id) === target)) return [];
  const positions = channelsIn(rows, target).map((r) => Number(r.position) || 0);
  const last = positions.length ? Math.max.apply(null, positions) : 0;
  return [placement(row, target, Math.min(Math.max(last + 1, 1), MAX_POSITION))];
}

function placement(row, parentId, position) {
  return { id: String(row.id), parent_id: String(parentId), position: position, expected_version: String(row.version) };
}

function decorateManaged(rows, row) {
  return Object.assign(decorateChannel(row), {
    parentId: String(row.parent_id),
    version: String(row.version),
    canUp: canMove(rows, row, 'up'),
    canDown: canMove(rows, row, 'down')
  });
}

// 管理清单 → 未分组 + 每个分类(含空分类),只列正常/已归档的行。
function manageSections(rows) {
  const sections = [{ key: '0', title: '未分组', category: null, channels: channelsIn(rows, '0').map((r) => decorateManaged(rows, r)) }];
  categories(rows).forEach((c) => {
    sections.push({
      key: String(c.id),
      title: String(c.name || ''),
      category: decorateManaged(rows, c),
      channels: channelsIn(rows, c.id).map((r) => decorateManaged(rows, r))
    });
  });
  return sections;
}

function newRequestId() {
  return 'mp-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
}

function writeHeader() {
  return { 'content-type': 'application/json', 'X-Audit-Log-Reason': AUDIT_REASON };
}

function reorderBody(items, requestId) {
  return { items: items, request_id: requestId || newRequestId(), reason: AUDIT_REASON };
}

// 创建:服务端要求六个字段齐全;话题可空(null)。
function createBody(form, requestId) {
  const kind = Number(form && form.kind);
  const name = String((form && form.name) || '').trim();
  const topic = String((form && form.topic) || '').trim();
  return {
    name: name,
    topic: topic ? topic : null,
    kind: [KIND_CATEGORY, KIND_TEXT, KIND_ANNOUNCEMENT].indexOf(kind) >= 0 ? kind : KIND_TEXT,
    parent_id: kind === KIND_CATEGORY ? '0' : String((form && form.parentId) || '0'),
    visibility: 1,
    slowmode_sec: clampSlowmode(form && form.slowmodeSec),
    request_id: requestId || newRequestId(),
    reason: AUDIT_REASON
  };
}

// 编辑:只发有变化的字段;没有变化返回 null(页面直接提示「没有修改」)。
function editBody(row, form, requestId) {
  const out = {};
  const name = String((form && form.name) || '').trim();
  const topic = String((form && form.topic) || '').trim();
  if (name && name !== row.name) out.name = name;
  if (topic !== String(row.topic || '')) out.topic = topic ? topic : null;
  const slow = clampSlowmode(form && form.slowmodeSec);
  if (Number(row.slowmode_sec) >= 0 && slow !== Number(row.slowmode_sec || 0)) out.slowmode_sec = slow;
  if (!Object.keys(out).length) return null;
  out.expected_version = String(row.version);
  out.request_id = requestId || newRequestId();
  out.reason = AUDIT_REASON;
  return out;
}

function archiveBody(row, archived, requestId) {
  return { archived: archived ? 1 : 0, expected_version: String(row.version), request_id: requestId || newRequestId(), reason: AUDIT_REASON };
}

function clampSlowmode(value) {
  const n = Math.floor(Number(value) || 0);
  return Math.min(Math.max(n, 0), 21600);
}

function validName(name) {
  const text = String(name || '').trim();
  return text.length > 0 && Array.from(text).length <= 64 && text.indexOf('/') < 0 && text.indexOf('#') < 0;
}

// 服务端 errorCode → 玩家看得懂的话。
const ERROR_TEXT = {
  CHANNEL_FORBIDDEN: '只有主理人和管理员可以管理频道',
  GUILD_FORBIDDEN: '加入俱乐部后才能查看频道',
  VERSION_CONFLICT: '频道刚被别人改过，已刷新，请再试一次',
  CHANNEL_NAME_TAKEN: '同一分类下已有同名频道',
  CHANNEL_STATE_CONFLICT: '这个频道当前不能这样操作',
  CHANNEL_NOT_FOUND: '频道不存在或已删除',
  GUILD_NOT_FOUND: '俱乐部不存在或已关闭',
  INVALID_FIELD: '填写的内容不符合要求'
};

function errorText(body, fallback) {
  const code = body && (body.errorCode || body.error_code);
  if (code && ERROR_TEXT[code]) return ERROR_TEXT[code];
  return fallback || '操作失败，请稍后再试';
}

module.exports = {
  GUILD_CLUB,
  KIND_CATEGORY,
  KIND_TEXT,
  KIND_ANNOUNCEMENT,
  AUDIT_REASON,
  kindLabel,
  hasPermission,
  decorateChannel,
  directorySections,
  manageSections,
  categories,
  channelsIn,
  canMove,
  planMove,
  planMoveTo,
  newRequestId,
  writeHeader,
  reorderBody,
  createBody,
  editBody,
  archiveBody,
  validName,
  errorText
};
