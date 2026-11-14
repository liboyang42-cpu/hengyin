/* 买家看到的「N 章」口径:章名以「结局」开头的不算章,单独写「M 个结局」。
   详情页(topic-motion)和确认订单(checkout-view)共用,别各写一份。 */
function text(v) { return v === null || v === undefined ? '' : String(v).trim(); }

const ENDING_NAME = /^结局/;
function isEndingChapter(c) { return ENDING_NAME.test(text(c && c.name)); }

/* 「4 章 · 3 个结局」:totalChapterCount 把结局也算进去了,只数故事章;
   结局数只能从已下发的 chaptersList 里认,认不出就不写。 */
function chapterCountText(info) {
  const d = info || {};
  const list = (d.chaptersList || []).filter(Boolean);
  const total = Number(d.totalChapterCount) > 0 ? Number(d.totalChapterCount) : list.length;
  const endings = Math.min(list.filter(isEndingChapter).length, total);
  const story = total - endings;
  if (story <= 0 && !endings) return '';
  if (story <= 0) return endings + ' 个结局';
  return story + ' 章' + (endings ? ' · ' + endings + ' 个结局' : '');
}

module.exports = { isEndingChapter, chapterCountText };
