/**
 * P-25:城市定向谜题的「看提示」按钮文案。
 * - 计数写的是**下一条**是第几条:没看过是 1/2,看过一条是 2/2(原来看完第一条仍写 1/2)。
 * - 点之前就说清代价:解谜分上限按服务端 PuzzleScorePolicy 100 → 70 → 40,每看一条少 30 分。
 *   这里只用于展示;真扣多少仍以服务端回执的 scoreCap 为准。
 */
const CAPS = [100, 70, 40];

function capAfter(used) {
  const n = Math.max(0, Number(used) || 0);
  return CAPS[Math.min(n, CAPS.length - 1)];
}

function puzzleHintLabel(used, count) {
  const total = Math.max(1, Number(count) || 0);
  const n = Math.max(0, Number(used) || 0);
  const next = Math.min(n + 1, total);
  const cost = capAfter(n) - capAfter(n + 1);
  return next + '/' + total + (cost > 0 ? ' · −' + cost + ' 分' : '');
}

module.exports = { puzzleHintLabel, capAfter };
