'use strict';
// 角色 Cast v1:主题角色配置的规范化与缓存(契约 docs/design/cast-contract-1119.md)。
// 主题详情(pages/topic,E5「人物」)与游玩页(pages/play)两个分包都要用,所以放主包;
// 详情页读到的配置记在这里,游玩页开局直接用,不再查一次主题信息。只放纯函数与一张内存表。

const str = (v) => (v == null ? '' : String(v));
const isObj = (v) => !!v && typeof v === 'object' && !Array.isArray(v);

const CAST_COLORS = ['#E8604C', '#3D8BFD', '#2FB57A', '#B98AF0', '#F2B33D', '#8E8E93']; // ds-ok 契约 castConfig.color 的六色白名单(与后端 CastConfigSupport 逐字一致)
/** 服务端 castConfig → 玩家侧视图。没配 / 没开 / 形状不对一律 null(= 功能关闭)。 */
function normalizeCast(raw) {
  let cfg = raw;
  if (typeof cfg === 'string') { try { cfg = JSON.parse(cfg); } catch (e) { return null; } }
  if (!isObj(cfg) || cfg.enabled !== true) return null;
  const cast = (Array.isArray(cfg.cast) ? cfg.cast : []).filter((c) => isObj(c) && str(c.id) && str(c.name).trim()).slice(0, 12).map((c) => {
    const voice = isObj(c.voice) ? c.voice : {};
    const lines = isObj(c.endingLines) ? c.endingLines : {};
    return {
      id: str(c.id), name: str(c.name).trim(), identity: str(c.identity).trim(),
      avatarUrl: str(c.avatarUrl), color: CAST_COLORS.indexOf(c.color) >= 0 ? c.color : CAST_COLORS[5],
      voice: { mode: ['preset', 'record', 'none'].indexOf(voice.mode) >= 0 ? voice.mode : 'none', preset: str(voice.preset) },
      clues: (Array.isArray(c.clues) ? c.clues : []).filter(isObj).slice(0, 6)
        .map((k) => ({ text: str(k.text).trim(), chapterId: k.chapterId == null || k.chapterId === '' ? null : str(k.chapterId) })),
      lockedCount: Math.max(0, Number(c.lockedCount) || 0),   // 服务端不下发锁住线索的正文,只给个数(契约 E4)
      redacted: typeof c.lockedCount === 'number',            // 玩家读口按本局进度裁过:下发的线索就是已解锁的
      endingLines: { low: str(lines.low).trim(), mid: str(lines.mid).trim(), high: str(lines.high).trim() },
    };
  });
  const identities = (Array.isArray(cfg.identities) ? cfg.identities : []).filter((x) => isObj(x) && str(x.key) && str(x.title).trim())
    .slice(0, 4).map((x) => ({ key: str(x.key), title: str(x.title).trim(), sub: str(x.sub).trim() }));
  return { enabled: true, playerName: cfg.playerName === true, playerIdentity: cfg.playerIdentity === true && identities.length > 0, identities, cast };
}

function initialOf(name) { return Array.from(str(name).trim())[0] || ''; }

/** 头像:有图用图,没图用彩色首字 */
function face(member) {
  const url = str(member.avatarUrl);
  // media-asset:N 是作者素材引用,客户端解不了 → 用彩色首字,不画破图
  return { url: /^media-asset:/.test(url) ? '' : url, initial: initialOf(member.name), color: member.color };
}

/** E5 主题详情「人物」:头像 54px、名字、身份的第一小句 */
function routeCastRow(cfg) {
  if (!cfg) return [];
  return cfg.cast.map((c) => ({ id: c.id, name: c.name, face: face(c), identity: str(c.identity).split(/[，,。；;、]/)[0].trim() }));
}

const cache = new Map();   // topicId → 规范化后的配置(null = 这个主题没开)
function remember(topicId, raw) { if (topicId) cache.set(str(topicId), normalizeCast(raw)); return cache.get(str(topicId)) || null; }
function cached(topicId) { return cache.has(str(topicId)) ? { cfg: cache.get(str(topicId)) } : null; }

module.exports = { CAST_COLORS, normalizeCast, initialOf, face, routeCastRow, remember, cached };
