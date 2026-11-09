// 故事流长按拖拽的几何(2026-11-09 qx-a5d)。块高各不相同(段落 / 图片 / 玩法卡),
// 所以不用 movable-area 的定高格子,而是按长按那一刻量到的每块位置算落点。
// 纯函数:页面只负责量矩形、记手指、setData。

var EDGE_PX = 64;       // 手指离滚动区上下沿多近开始自动滚
var EDGE_MAX_STEP = 14; // 每帧最多滚多少 px

// rects: [{ top, height }](长按那一刻、相对滚动内容的位置);from: 被拖块下标;
// centerY: 被拖块中线当前在同一坐标系里的位置。返回松手后它应在的最终下标。
function targetIndex(rects, from, centerY) {
  var list = Array.isArray(rects) ? rects : [];
  if (from < 0 || from >= list.length) return from;
  var target = 0;
  for (var i = 0; i < list.length; i++) {
    if (i === from) continue;
    if (centerY > list[i].top + list[i].height / 2) target++;
  }
  return target;
}

// 其余块让位:被拖块从 from 移到 target,夹在中间的块整体上移或下移一个「被拖块高 + 间距」。
function shiftFor(rects, from, target) {
  var list = Array.isArray(rects) ? rects : [];
  if (from < 0 || from >= list.length) return list.map(function () { return 0; });
  var self = list[from];
  var next = list[from + 1];
  var prev = list[from - 1];
  var gap = next ? next.top - (self.top + self.height) : (prev ? self.top - (prev.top + prev.height) : 0);
  var slot = self.height + Math.max(0, gap);
  return list.map(function (rect, i) {
    if (target > from && i > from && i <= target) return -slot;
    if (target < from && i >= target && i < from) return slot;
    return 0;
  });
}

// 手指贴近滚动区边缘时的自动滚步长(负 = 往上)。离边越近越快。
function edgeScrollStep(clientY, top, bottom) {
  if (!(bottom > top)) return 0;
  if (clientY < top + EDGE_PX) return -Math.ceil(EDGE_MAX_STEP * Math.min(1, (top + EDGE_PX - clientY) / EDGE_PX));
  if (clientY > bottom - EDGE_PX) return Math.ceil(EDGE_MAX_STEP * Math.min(1, (clientY - (bottom - EDGE_PX)) / EDGE_PX));
  return 0;
}

module.exports = {
  targetIndex: targetIndex,
  shiftFor: shiftFor,
  edgeScrollStep: edgeScrollStep,
  EDGE_PX: EDGE_PX,
};
