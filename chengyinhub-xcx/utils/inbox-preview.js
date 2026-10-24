'use strict';
/**
 * 站内消息的「会话 / 收到的邀请」归一与预览合并(2026-10-22 owner 裁决 L03)。
 *
 * 收件箱页(subpackageB/pages/im/list)与商家工作台「我的消息」读的是同一份
 * /api/im/conversations + /api/coop/list,时间/摘要/归类口径必须一致,所以 fmtTime /
 * fmtPreview / conversationTab 逐字照 im/list 的实现。im/list 本轮不改(它的动态 setData
 * 按行号登记在 UI-GATE-0 基线里,动它就得重生成冻结基线);后续可让 im/list 委托这里。
 *
 * buildInboxPreview 把会话、收到的邀请、经营动态(/api/merchant/events)合成一列,
 * 新的在前,只取前 limit 条。纯函数,不碰 setData / 计时器,便于单测。
 */
const { toTimestamp, chinaDateKey, chinaParts } = require('./datetime');
const { decodeDisplayText } = require('./display-text.js');

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

function fmtPreview(c) {
  const t = c.lastMsgType;
  if (t == 2) return '[图片]';
  // 预览是纯文本:服务端过滤存下的 &lt; 等在这里解码一次
  if (t == 3) return decodeDisplayText(String(c.lastMsgText || '[系统卡片]').replace(/[\r\n]+/g, ' '));
  return decodeDisplayText(String(c.lastMsgText || '').replace(/[\r\n]+/g, ' '));
}

function fmtTime(v, now) {
  if (!v) return '';
  // 时刻锚定 +08:00,日历判断与显示字段一律走中国时区,不用本地 getHours/toDateString——
  // 手机时区非 UTC+8 时,本地方法会把中国的"今天 14:30"显示成别的日期/时刻。
  const ts = toTimestamp(v);
  if (isNaN(ts)) return '';
  const nowTs = now == null ? Date.now() : now;
  const diff = (nowTs - ts) / 1000;
  if (diff < 60) return '刚刚';
  if (diff < 3600) return Math.floor(diff / 60) + '分钟前';
  const parts = chinaParts(ts);
  const pad = function (n) { return n < 10 ? '0' + n : '' + n; };
  if (chinaDateKey(ts) === chinaDateKey(nowTs)) return pad(parts.hours) + ':' + pad(parts.minutes);
  if (chinaDateKey(ts) === chinaDateKey(nowTs - 86400000)) return '昨天';
  if (diff < 7 * 86400) return WEEKDAYS[parts.weekday];
  return parts.month + '月' + parts.day + '日';
}

// 会话归类:单聊、城瘾小助手、官方客服都在收件箱;群聊(type=4)不在小程序露出。null = 不展示。
function conversationTab(c) {
  const t = parseInt(c && c.type) || 1;
  if (t === 4) return null;
  return 'in';
}

// /api/merchant/events 只下发 "MM-dd HH:mm"(中国时区、无年份):补当前中国年份;
// 补出来落在未来(跨年的 12 月流水)就退一年。
function eventTimestamp(text, now) {
  const m = String(text == null ? '' : text).trim().match(/^(\d{2})-(\d{2}) (\d{2}):(\d{2})$/);
  if (!m) return NaN;
  const nowTs = now == null ? Date.now() : now;
  const year = Number(chinaDateKey(nowTs).slice(0, 4));
  const at = (y) => toTimestamp(y + '-' + m[1] + '-' + m[2] + 'T' + m[3] + ':' + m[4] + ':00+08:00');
  const ts = at(year);
  if (isNaN(ts)) return NaN;
  return ts > nowTs + 60000 ? at(year - 1) : ts;
}

function conversationItem(c, now) {
  const cp = c.counterparty || {};
  const type = parseInt(c.type) || 1;
  const name = cp.nickname || (type == 2 ? '系统通知' : '城瘾消息');
  return {
    key: 'conv-' + c.conversationId,
    kind: 'conversation',
    icon: type == 2 ? 'bell' : 'comment',
    title: name,
    sub: fmtPreview(c),
    ts: toTimestamp(c.lastMsgAt),
    time: fmtTime(c.lastMsgAt, now),
    conversationId: c.conversationId,
    name: name,
    avatar: cp.avatar || '',
    type: type,
  };
}

// 入参是 coop-invite-view.decorate 之后的行(与 im/list 同一投影)
function inviteItem(it, now) {
  const subParts = [it.topicText, it.statusText].filter(Boolean);
  return {
    key: 'invite-' + it.inviteId,
    kind: 'invite',
    icon: 'connect',
    title: it.partnerName ? it.partnerName + ' 的' + (it.typeText || '合作') + '邀请' : (it.typeText || '合作') + '邀请',
    sub: subParts.join(' · '),
    ts: toTimestamp(it.createTime),
    time: fmtTime(it.createTime, now),
    inviteId: it.inviteId,
  };
}

function eventItem(e, index, now) {
  const ts = eventTimestamp(e.time, now);
  return {
    key: 'event-' + index,
    kind: 'event',
    icon: e.type === 'income' ? 'finance' : (e.type === 'verify' ? 'check' : 'bell'),
    title: e.label || '经营动态',
    sub: e.content || '',
    ts: ts,
    time: isNaN(ts) ? String(e.time || '') : fmtTime(ts, now),
    type: e.type || '',
  };
}

/**
 * @param {Object} sources conversations(/api/im/conversations 原始行) / invites(decorate 后的收到的邀请)
 *                         / events(/api/merchant/events 原始行);缺哪份就当空
 * @param {Object} [options] now / limit(默认 3)
 */
function buildInboxPreview(sources, options) {
  const src = sources || {};
  const opts = options || {};
  const now = opts.now == null ? Date.now() : opts.now;
  const limit = opts.limit > 0 ? opts.limit : 3;
  const items = []
    .concat((src.conversations || []).filter((c) => c && c.conversationId != null && conversationTab(c) === 'in')
      .map((c) => conversationItem(c, now)))
    .concat((src.invites || []).filter((it) => it && it.inviteId).map((it) => inviteItem(it, now)))
    .concat((src.events || []).filter(Boolean).map((e, i) => eventItem(e, i, now)));
  // 新的在前;取不到时间的排最后(仍保持各自原顺序)
  const ranked = items.map((item, order) => ({ item, order }));
  ranked.sort((a, b) => {
    const at = isNaN(a.item.ts) ? -Infinity : a.item.ts;
    const bt = isNaN(b.item.ts) ? -Infinity : b.item.ts;
    if (at !== bt) return bt - at;
    return a.order - b.order;
  });
  return ranked.slice(0, limit).map((r) => r.item);
}

module.exports = { fmtPreview, fmtTime, conversationTab, eventTimestamp, buildInboxPreview };
