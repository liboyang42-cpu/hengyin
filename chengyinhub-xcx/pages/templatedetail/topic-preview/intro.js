// 主题模板预览的介绍:只用已下发的 /api/template/topic-template/info 字段。
// 章名/导读口径与 pages/topic/utils/topic-motion.js 的 chapterHeading/chapterGuide 一致;
// 分包之间不许互引资源,所以这里是同口径的精简副本(topic-detail-walk2 测试钉住两边一致)。
const CN = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十'];
const SELF_ORDINAL = /^(第\s*[0-9一二三四五六七八九十百零两]+\s*章|结局|终章|序章|尾声)/;
const PUZZLE_MARK = /【\s*(题目|观察位置|谜题|提示|线索|密码表?|密文|答案)[^】]*】|(?:^|\s)(?:题目|观察位置)[:：]|密码表|对照表|[A-Z]\s*=\s*\d/;

function str(v) { return v == null ? '' : String(v).trim(); }

function heading(i, n, name) {
  const t = str(name);
  if (SELF_ORDINAL.test(t)) return t;
  const lead = (i === n - 1 && n > 2) ? '终章' : '第' + (CN[i] || String(i + 1)) + '章';
  return t ? lead + ' · ' + t : lead;
}

function guide(c) {
  const MAX = 60;
  const strip = (s) => { const m = PUZZLE_MARK.exec(s); return m ? s.slice(0, m.index).trim() : s; };
  let src = strip(str(c && c.description));
  if (!src) {
    for (const b of Array.isArray(c && c.blocks) ? c.blocks : []) {
      const t = b && b.type === 'text' ? strip(str(b.content)) : '';
      if (t) { src = t; break; }
    }
  }
  src = src.replace(/\s+/g, ' ');
  if (src.length <= MAX) return src;
  const head = src.slice(0, MAX);
  const cut = Math.max(head.lastIndexOf('。'), head.lastIndexOf('！'), head.lastIndexOf('？'), head.lastIndexOf('.'));
  return cut >= 12 ? head.slice(0, cut + 1) : head.replace(/[，,、；;\s]+$/, '') + '…';
}

function buildIntro(info) {
  const d = info || {};
  const chapters = (Array.isArray(d.chapters) ? d.chapters : []).filter(function (c) { return c && typeof c === 'object' && str(c.name); });
  const list = chapters.map(function (c, i) {
    return { key: str(c.id) || String(i), name: heading(i, chapters.length, c.name), guide: guide(c) };
  });
  return { desc: str(d.description).replace(/\s+/g, ' '), chapters: list };
}

module.exports = { buildIntro };
