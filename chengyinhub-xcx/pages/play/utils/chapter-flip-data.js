// 章节翻转过场的数据(纯函数,已单测)。只写拿得到的真读数:没有的格子整格不出,不拿 0 凑。
const { summarizePuzzleScores } = require('./play-puzzle-summary.js');

const STYLES = ['riso', 'engrave'];

function pad2(n) { return String(n).padStart(2, '0'); }
function firstImg(v) { return v ? String(v).split(/[,;]/)[0].trim() : ''; }
/** 面上的小标签:「01 · 提篮桥」—— 章节名「 · 」前那段,太长截到 6 字 */
function faceLabel(num, title) {
  const head = String(title || '').split(/[·・]/)[0].trim().slice(0, 6);
  return head ? num + ' · ' + head : num;
}
function chapterNodes(nodes, id) { return (nodes || []).filter((n) => n && id != null && String(n.chapterId) === String(id)); }
function face(card, i, nodes) {
  return {
    cover: firstImg(card && card.cover),
    style: STYLES[i % 2],
    label: faceLabel(pad2(i + 1), card && card.title),
    photos: chapterNodes(nodes, card && card.id).map((n) => firstImg(n.imgUrl)).filter(Boolean).slice(0, 3),
  };
}
function goldFace(cover, topicName) { return { cover: firstImg(cover), style: 'gold', label: topicName ? '通关 · ' + String(topicName).slice(0, 6) : '通关', photos: [] }; }

/**
 * 章节开场:cards 是不含序章的章节卡,chapterId 是正要打开的那章。
 * 上一章在本局里玩过(至少一个节点完成)才先出「上一章完成」那一屏。
 */
function buildGateFlip({ cards, chapterId, nodes, topicCover, topicName, desc }) {
  const list = (cards || []).filter((c) => c && !c.opening);
  const idx = list.findIndex((c) => String(c.id) === String(chapterId));
  const cur = idx >= 0 ? list[idx] : null;
  const prev = idx > 0 ? list[idx - 1] : null;
  const curFace = face(cur, Math.max(idx, 0), nodes);
  const prevNodes = prev ? chapterNodes(nodes, prev.id) : [];
  const prevDone = prevNodes.filter((n) => n.done).length;
  let endInfo = null;
  if (prev && prevDone > 0) {
    const puzzle = summarizePuzzleScores(prevNodes);
    endInfo = {
      number: pad2(idx),
      kicker: '第 ' + idx + ' 章 · 完成',
      title: prev.title || '',
      desc: '',
      stats: [{ v: prevDone + '/' + prevNodes.length, k: '坐标' }].concat(puzzle.count ? [{ v: String(puzzle.score), k: '解谜分' }] : []),
    };
  }
  const n = cur && cur.nodeCount ? cur.nodeCount : chapterNodes(nodes, cur && cur.id).length;
  const info = {
    kicker: idx < 0 ? '' : '第 ' + (idx + 1) + ' 章 / 共 ' + Math.max(list.length, 1) + ' 章',
    title: (cur && cur.title) || '',
    desc: desc || '',
    stats: [
      n ? { v: String(n), k: '坐标' } : null,
      cur && cur.mileage ? { v: String(cur.mileage).replace(/km$/, ' km'), k: '距离' } : null,
      cur && cur.timeText ? { v: String(cur.timeText).replace(/\s*分钟$/, ' 分'), k: '预计' } : null,
    ].filter(Boolean),
  };
  const faces = [prev ? face(prev, idx - 1, nodes) : Object.assign({}, curFace, { style: STYLES[(Math.max(idx, 0) + 1) % 2] }), curFace, goldFace(topicCover, topicName)];
  return { faces, endInfo, info };
}

/** 通关:三面 = 倒数第二章、最后一章、金色通关面;数据只写拿得到的合计。 */
function buildFinale({ cards, nodes, topicCover, topicName, doneCount, elapsed, score, puzzle }) {
  const list = (cards || []).filter((c) => c && !c.opening);
  const last = list.length - 1;
  const a = last > 0 ? face(list[last - 1], last - 1, nodes) : face(list[0], 1, nodes);
  const b = last >= 0 ? face(list[last], last, nodes) : face(null, 0, nodes);
  const stats = [
    doneCount ? { v: String(doneCount), k: '坐标' } : null,
    elapsed ? { v: elapsed, k: '用时' } : null,
    score ? { v: Number(score).toLocaleString('en-US'), k: '探索值' } : (puzzle && puzzle.count ? { v: String(puzzle.score), k: '解谜分' } : null),
  ].filter(Boolean);
  return { faces: [a, b, goldFace(topicCover || (b && b.cover), topicName)], info: { kicker: '全部完成', title: topicName || '', desc: '', stats } };
}

module.exports = { buildGateFlip, buildFinale, faceLabel };
