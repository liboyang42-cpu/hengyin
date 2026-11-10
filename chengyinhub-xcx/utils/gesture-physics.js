/**
 * utils/gesture-physics.js —— 手势物理(qx-u1,2026-11-10)
 *
 * 弹层拖拽、全屏层侧滑返回共用的纯数学:阻尼、末段速度、松手判定、档位偏移。
 * 不碰 wx、不碰 DOM,单测直接跑(tests/unit/gesture-physics.test.js)。
 *
 * ⚠️ 跟手要 60fps,真正的触摸处理在 WXS(utils/wxs/gesture.wxs)里跑,WXS 不能 require JS,
 *    所以那边有一份同名函数的镜像。两份由 gesture-physics-parity 单测在同一组输入上逐值比对,
 *    改这里的常数或公式必须同步改 WXS,否则判红。写法刻意停在 ES5(var / function),两边能逐行对照。
 *
 * 业主 2026-11-09 定的参数(LANES.md「qx-u1」):
 *   · 下滑关闭:速度 > 0.5 px/ms 或位移 > 面板高 30%
 *   · 其余情形吸附到最近的档位
 *   · 越过最高档往上拖:位移 × 0.25 的阻尼,松手弹回
 *   · 减动效:没有阻尼(顶到最高档就停),吸附直接到位
 */
var DISMISS_VELOCITY = 0.5;   // px/ms
var DISMISS_RATIO = 0.3;      // 面板高(或屏宽)的比例
var RUBBER = 0.25;            // 越界阻尼系数
var TAP_SLOP = 4;             // px:小于这个位移算点击,不进入拖拽
var EDGE = 32;                // px:侧滑返回只从左缘这一条起手,不和相册左右翻页抢手势
var LEVEL_RATIO = { peek: 0.3, half: 0.6, full: 0.8 };

/* 越界阻尼:raw 是手指给出的原始偏移(px,向下为正),min 是最高档的偏移(通常 0)。
 * 低于 min(往上拽过头)的部分只走 25%;reduced=true 时直接顶在 min。 */
function resist(raw, min, reduced) {
  if (raw >= min) return raw;
  if (reduced) return min;
  return min + (raw - min) * RUBBER;
}

/* 末段速度(px/ms):只看最后一段采样,不看全程均速 —— 慢拖后一甩要能认出来。
 * 没有上一采样点(只按下没动)时返回 0。 */
function velocity(prev, prevT, cur, curT) {
  if (prev === null || prev === undefined || prevT === null || prevT === undefined) return 0;
  var dt = curT - prevT;
  if (dt < 1) dt = 1;
  return (cur - prev) / dt;
}

/* 档位偏移:levels 是从高到低允许停靠的档(如 'full,half'),panelH 是面板实测高,winH 是窗口高。
 * 面板以自身高度为最高档(偏移 0),低档 = 面板下移 panelH - ratio × winH。
 * 返回升序、去重、全为非负整数的偏移表;第一项恒为 0。 */
function detentOffsets(levels, panelH, winH) {
  var out = [0];
  if (!levels || !(panelH > 0) || !(winH > 0)) return out;
  var list = String(levels).split(',');
  for (var i = 0; i < list.length; i++) {
    var r = LEVEL_RATIO[list[i].trim()];
    if (!r) continue;
    var off = Math.round(panelH - r * winH);
    if (off > 0 && out.indexOf(off) < 0) out.push(off);
  }
  out.sort(function (a, b) { return a - b; });
  return out;
}

function nearest(list, x) {
  var best = list[0];
  for (var i = 1; i < list.length; i++) {
    if (Math.abs(list[i] - x) < Math.abs(best - x)) best = list[i];
  }
  return best;
}

/* 松手判定(弹层,纵向)。
 *   offset   当前偏移(已过阻尼),px,向下为正
 *   v        末段速度,px/ms,向下为正
 *   height   面板实测高;0 = 没量到 ⇒ 位移闸失效,只看速度(慢拖不会被 0 阈值误关)
 *   detents  detentOffsets() 的结果
 * 返回 { action: 'dismiss' } 或 { action: 'snap', to: <偏移> }。
 *   · 向下甩(v > 0.5)且已经在最低档之下 ⇒ 关;在两档之间 ⇒ 落到下一档
 *   · 向上甩(v < -0.5)⇒ 回到上一档
 *   · 慢拖越过最低档再往下 30%(相对于最低档时的可见高度)⇒ 关
 *   · 其余 ⇒ 最近的档 */
function sheetRelease(offset, v, height, detents) {
  var d = detents && detents.length ? detents : [0];
  var lowest = d[d.length - 1];
  var i;
  if (v > DISMISS_VELOCITY) {
    if (offset > lowest) return { action: 'dismiss' };
    for (i = 0; i < d.length; i++) if (d[i] > offset) return { action: 'snap', to: d[i] };
  }
  if (v < -DISMISS_VELOCITY) {
    for (i = d.length - 1; i >= 0; i--) if (d[i] < offset) return { action: 'snap', to: d[i] };
    return { action: 'snap', to: 0 };
  }
  if (height > 0 && offset - lowest > (height - lowest) * DISMISS_RATIO) return { action: 'dismiss' };
  return { action: 'snap', to: nearest(d, offset) };
}

/* 松手判定(全屏层侧滑返回,横向):同一套阈值 —— 速度 > 0.5 px/ms 或位移 > 屏宽 30% 就关,否则弹回。 */
function swipeRelease(dx, vx, width) {
  if (vx > DISMISS_VELOCITY && dx > 0) return true;
  return width > 0 && dx > width * DISMISS_RATIO;
}

module.exports = {
  DISMISS_VELOCITY: DISMISS_VELOCITY,
  DISMISS_RATIO: DISMISS_RATIO,
  RUBBER: RUBBER,
  TAP_SLOP: TAP_SLOP,
  EDGE: EDGE,
  LEVEL_RATIO: LEVEL_RATIO,
  resist: resist,
  velocity: velocity,
  detentOffsets: detentOffsets,
  nearest: nearest,
  sheetRelease: sheetRelease,
  swipeRelease: swipeRelease,
};
