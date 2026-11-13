// 主题模板预览的介绍:只用已下发的 /api/template/topic-template/info 字段,章名与导读沿用主题详情的口径。
const motion = require('../../topic/utils/topic-motion.js');

function str(v) { return v == null ? '' : String(v).trim(); }

function buildIntro(info) {
  const d = info || {};
  const chapters = (Array.isArray(d.chapters) ? d.chapters : []).filter(function (c) { return c && typeof c === 'object' && str(c.name); });
  const list = chapters.map(function (c, i) {
    return { key: str(c.id) || String(i), name: motion.chapterHeading(i, chapters.length, c.name), guide: motion.chapterGuide(c) };
  });
  return { desc: str(d.description).replace(/\s+/g, ' '), chapters: list };
}

module.exports = { buildIntro };
