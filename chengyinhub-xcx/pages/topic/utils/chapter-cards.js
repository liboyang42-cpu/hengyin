// 详情 tab「节点介绍」:一章一张卡(2026-11-19 业主)。
// 原来按站点循环、图取玩法封面,每张卡都是同一张主题封面;现按章节出卡,图用章节自己的 img_arr。
// 纯函数,不碰 wx / app:图片地址解析由调用方传入(app.getImgUrl)。

const FALLBACK_COVER = '/images/route_city_cover.png';

function text(v) {
  if (v === null || v === undefined) return '';
  const s = String(v).trim();
  return s === 'null' ? '' : s;
}

// img_arr 可能是逗号/分号分隔串,也可能是 JSON 数组串或真数组;取第一张
function firstImage(imgArr) {
  if (Array.isArray(imgArr)) return text(imgArr.find((v) => text(v)));
  const raw = text(imgArr);
  if (!raw) return '';
  if (raw.charAt(0) === '[') {
    try {
      const list = JSON.parse(raw);
      if (Array.isArray(list)) {
        for (const v of list) {
          const src = text(v && typeof v === 'object' ? v.url : v);
          if (src) return src;
        }
        return '';
      }
    } catch (e) { /* 不是合法 JSON 就当分隔串 */ }
  }
  return raw.replace(/^\[|\]$/g, '').split(/[,;]/).map((s) => s.trim().replace(/^["']|["']$/g, '')).filter(Boolean)[0] || '';
}

// 结局章不出卡(不能剧透):名字以「结局」开头,或玩法数与时长都为 0
function isEnding(chapter) {
  if (!chapter) return true;
  if (text(chapter.name).indexOf('结局') === 0) return true;
  return !(Number(chapter.templateCount) > 0) && !(Number(chapter.totalTime) > 0);
}

// total_time 是秒;为 0/缺失时用本章各站玩法时长(分钟)求和;都没有返回 0(调用方不写时长)
function chapterMinutes(chapter) {
  const seconds = Number(chapter && chapter.totalTime);
  if (seconds > 0) return Math.max(1, Math.round(seconds / 60));
  let sum = 0;
  for (const node of (chapter && chapter.nodes) || []) {
    const d = Number(node && node.cmsMemberTemplate && node.cmsMemberTemplate.duration);
    if (d > 0) sum += d;
  }
  return Math.round(sum);
}

function metaText(chapter) {
  const parts = [];
  const games = Number(chapter.templateCount);
  if (games > 0) parts.push('含 ' + games + ' 个玩法');
  const minutes = chapterMinutes(chapter);
  if (minutes > 0) parts.push('约 ' + minutes + ' 分钟');
  return parts.join(' · ');
}

function buildChapterCards(chaptersList, topicCover, resolveImg) {
  const resolve = typeof resolveImg === 'function' ? resolveImg : (s) => s;
  const cover = (src) => (src ? resolve(src) : '') || '';
  const list = Array.isArray(chaptersList) ? chaptersList.slice() : [];
  // sort_id 升序;没有 sort_id 的保持原顺序排在后面
  const ordered = list.map((c, i) => ({ c, i })).sort((a, b) => {
    const sa = Number(a.c && a.c.sortId), sb = Number(b.c && b.c.sortId);
    const ha = a.c && a.c.sortId != null && !isNaN(sa), hb = b.c && b.c.sortId != null && !isNaN(sb);
    if (ha && hb && sa !== sb) return sa - sb;
    if (ha !== hb) return ha ? -1 : 1;
    return a.i - b.i;
  }).map((x) => x.c);
  return ordered.filter((c) => !isEnding(c)).map((c, i) => ({
    key: String(c.id != null ? c.id : 'c' + i),
    name: text(c.name),
    img: cover(firstImage(c.imgArr)) || cover(firstImage(topicCover)) || FALLBACK_COVER,
    meta: metaText(c),
    description: text(c.description),
  }));
}

// 一次只展开一张:点开着的那张收起,点别的换成那张
function toggleOpen(openKey, key) {
  const k = text(key);
  return !k || text(openKey) === k ? '' : k;
}

module.exports = { buildChapterCards, toggleOpen, firstImage, isEnding, chapterMinutes, metaText, FALLBACK_COVER };
