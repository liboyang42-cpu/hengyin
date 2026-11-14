'use strict';
// 外部游戏目录(只读)。GET /api/external-game/catalog?cursor=&limit= → { items, hasMore, nextCursor };
// GET /api/external-game/catalog/{gameId} → 同一条目 + 限时签名下载地址(小程序不用)。
// 小程序不运行外部游戏:游戏包是服务端签名的 JSON 归档,没有可托管的网页地址,
// web-view 也只能打开已配置的业务域名;所以详情页只给「在城瘾 App 中游玩」的说明。

var PAGE_SIZE = 20;
var GAME_ID = /^[a-z0-9][a-z0-9._-]{2,63}$/;

// 分级只有 4+ / 9+ / 12+ / 17+(ExternalGamePackageValidator.RATINGS),原样显示;权限只有这三种。
var PERMISSION = { storage: '本地存储', haptics: '振动', audio: '声音' };

function text(value) {
  return typeof value === 'string' ? value.trim() : '';
}

function validGameId(value) {
  return typeof value === 'string' && GAME_ID.test(value);
}

function ratingText(value) {
  return text(value);
}

function sizeText(bytes) {
  var n = Number(bytes);
  if (!isFinite(n) || n <= 0) return '';
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1024 / 1024).toFixed(1) + ' MB';
}

function dateText(iso) {
  var t = Date.parse(iso || '');
  if (!isFinite(t)) return '';
  var d = new Date(t);
  function p(n) { return n < 10 ? '0' + n : String(n); }
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

/** 一条目录项 → 页面行;缺 gameId 或格式不对的项丢弃(不让坏数据进详情跳转)。 */
function normalizeGame(raw) {
  if (!raw || typeof raw !== 'object' || !validGameId(raw.gameId)) return null;
  var perms = Array.isArray(raw.permissions) ? raw.permissions.filter(function (p) { return typeof p === 'string'; }) : [];
  return {
    gameId: raw.gameId,
    name: text(raw.name) || raw.gameId,
    author: text(raw.author),
    summary: text(raw.summary),
    version: text(raw.version),
    ratingText: ratingText(raw.contentRating),
    sizeText: sizeText(raw.packageBytes),
    approvedText: dateText(raw.approvedAt),
    permissionText: perms.map(function (p) { return PERMISSION[p] || p; }).join('、'),
  };
}

/** 目录页 → { rows, hasMore, nextCursor };形状不对返回 null(页面按失败处理)。 */
function normalizePage(data) {
  if (!data || typeof data !== 'object' || !Array.isArray(data.items)) return null;
  var rows = [];
  data.items.forEach(function (item) { var row = normalizeGame(item); if (row) rows.push(row); });
  var more = data.hasMore === true && typeof data.nextCursor === 'string' && data.nextCursor !== '';
  return { rows: rows, hasMore: more, nextCursor: more ? data.nextCursor : null };
}

/** 追加下一页时按 gameId 去重(同一游戏换版本后游标可能把它带回来)。 */
function appendRows(existing, incoming) {
  var seen = {};
  var out = [];
  (existing || []).concat(incoming || []).forEach(function (row) {
    if (!row || seen[row.gameId]) return;
    seen[row.gameId] = true;
    out.push(row);
  });
  return out;
}

module.exports = {
  PAGE_SIZE: PAGE_SIZE,
  validGameId: validGameId,
  ratingText: ratingText,
  sizeText: sizeText,
  normalizeGame: normalizeGame,
  normalizePage: normalizePage,
  appendRows: appendRows,
};
