// Only presentation metadata fields use this list; never pass authored passage text here.
const PRODUCTION_LABELS = new Set(['旁白', '旁白：', '旁白:', '【旁白】', '叙述', 'narrator', 'narration', '导演提示', '镜头提示', '主持提示', '制作备注']);
function playerStoryLabel(value) {
  if (typeof value !== 'string') return '';
  const label = value.trim();
  return PRODUCTION_LABELS.has(label.toLowerCase()) ? '' : label;
}
module.exports = { playerStoryLabel };
