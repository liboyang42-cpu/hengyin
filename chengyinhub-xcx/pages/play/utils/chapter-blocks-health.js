// P1-021:章节故事流块解析回落时的可观测性。
// 后端 /api/play/nodes 的 chapters[].blocks 应是块对象数组;拿到别的形状(未解析的 JSON 串、对象、
// 混进非对象条目)时,本页会回落到 chapter.description 或静默跳过坏块 —— 功能不坏,但作者编排的
// 块流没铺出来,以前零观测。这里只判形状、给原因码,埋点 properties 只放 id/原因码,不放正文。
const EVENT = 'chapter_blocks_parse_failed';

function blocksParseFailure(raw) {
  if (raw === undefined || raw === null) return '';           // 没下发块流 = 走 description,是正常路径
  if (typeof raw === 'string') return 'unparsed_string';
  if (!Array.isArray(raw)) return 'not_array';
  for (let i = 0; i < raw.length; i += 1) {
    const b = raw[i];
    if (!b || typeof b !== 'object' || Array.isArray(b) || typeof b.type !== 'string' || !b.type) return 'bad_block';
  }
  return '';
}

/** 同一章同一原因在本页实例内只报一次;seen 由调用方持有(页面实例上)。 */
function reportBlocksParseFailure(analytics, seen, chapterId, raw) {
  const reason = blocksParseFailure(raw);
  if (!reason || !analytics || typeof analytics.track !== 'function') return reason;
  const id = Number(chapterId);
  const key = (Number.isFinite(id) ? id : 0) + ':' + reason;
  if (seen && seen[key]) return reason;
  if (seen) seen[key] = true;
  analytics.track(EVENT, { bizType: 'chapter', bizId: Number.isFinite(id) && id > 0 ? id : 0, properties: { reason } });
  return reason;
}

module.exports = { EVENT, blocksParseFailure, reportBlocksParseFailure };
