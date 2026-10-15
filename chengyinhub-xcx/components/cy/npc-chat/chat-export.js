'use strict';

/**
 * 「导出我的对话记录」的纯格式化(无 wx 依赖,单测直接 require)。
 *
 * 数据真源:GET /api/ai/npc/chat/data/export → NpcChatDataExportVO
 *   { retentionDays: Integer, retentionPolicy: String, records: NpcChatLog[] }
 *   NpcChatLog = { id, userId, profileId, activityId, userMsg, npcReply, safetyFlag,
 *                  model, latencyMs, tokenIn, tokenOut, createTime }
 * 服务端已按登录态归属过滤、按 create_time 升序。本端不落任何存储:只在内存里拼两份文本,
 * 由用户复制「可读文本」(2026-10-15 用户决定:导出 = 复制文本)。
 */

const PREVIEW_LIMIT = 50;

function text(value) {
  return typeof value === 'string' ? value : '';
}

function pad(n) {
  return n < 10 ? '0' + n : String(n);
}

// createTime(java.util.Date,实体无 @JsonFormat)由 Jackson 按 ApplicationConfig 的 Asia/Shanghai 时区输出 ISO 串,
// 形如「2026-10-01T12:00:00.000+08:00」:去掉 T、毫秒和时区后缀即北京时间。毫秒时间戳按北京时间格式化;
// 都不是就标「时间未记录」。
function timeText(value) {
  if (typeof value === 'string' && value.trim()) {
    // 只吞 +08:00;万一服务端时区配置变了,保留原偏移照实展示,不把别的时区冒充成北京时间
    return value.trim().replace('T', ' ').replace(/\.\d+(?=(?:Z|[+-]\d{2}:?\d{2})?$)/, '').replace(/\+08:?00$/, '');
  }
  if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
    const d = new Date(value + 8 * 3600 * 1000);
    return d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate())
      + ' ' + pad(d.getUTCHours()) + ':' + pad(d.getUTCMinutes()) + ':' + pad(d.getUTCSeconds());
  }
  return '时间未记录';
}

/** 校验回包形状;不合法返回 null(调用方落错误态,不把半截数据当成「没有记录」)。 */
function normalizeExport(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
  if (!Array.isArray(data.records)) return null;
  const records = data.records.filter((item) => item && typeof item === 'object' && !Array.isArray(item));
  if (records.length !== data.records.length) return null;
  return {
    retentionDays: Number.isInteger(data.retentionDays) ? data.retentionDays : null,
    retentionPolicy: text(data.retentionPolicy).trim(),
    records,
  };
}

function recordLines(record) {
  const lines = ['[' + timeText(record.createTime) + ']'];
  const ask = text(record.userMsg).trim();
  const reply = text(record.npcReply).trim();
  lines.push('我：' + (ask || '（无内容）'));
  lines.push('AI：' + (reply || '（没有回复）'));
  return lines.join('\n');
}

/** 可读文本:标题 + 条数 + 保留政策 + 逐条对话。 */
function formatReadable(vo) {
  const head = ['城瘾 · 我的 AI 对话记录', '共 ' + vo.records.length + ' 条'];
  if (vo.retentionPolicy) head.push('保留说明：' + vo.retentionPolicy);
  return head.join('\n') + '\n\n' + vo.records.map(recordLines).join('\n\n');
}

/** 预览:只渲染前 PREVIEW_LIMIT 条,复制内容始终是全部。 */
function previewRows(vo) {
  return vo.records.slice(0, PREVIEW_LIMIT).map((record, index) => ({
    key: String(record.id != null ? record.id : 'i' + index),
    time: timeText(record.createTime),
    ask: text(record.userMsg).trim() || '（无内容）',
    reply: text(record.npcReply).trim() || '（没有回复）',
  }));
}

module.exports = { PREVIEW_LIMIT, normalizeExport, formatReadable, previewRows, timeText };
