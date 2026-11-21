/**
 * utils/reading-motion.js —— 阅读面动效档的 JS 镜像(2026-11-21 业主「动画和间距可以快一点、
 * 小一点,读得快的人会着急」)。
 *
 * style/tokens.wxss 的 --cy-motion-read-* 在 JS 里读不到,计时器(setTimeout 链、逐帧解码)只能各留一份;
 * 两处漂移、以及阅读面任何一处超出 CAPS,由 tests/unit/reading-pace-1121.test.js 判红。
 * 规则:
 *   · 读者在等的东西(正文显形、章节门、结局、完成页)一律走本组,不写字面长时长;
 *   · 点一下即走完(页面 skipReveal / 章节门 onTap),动画中途从不拦点击;
 *   · 减弱动态 = 立即(各处已有的 reducedMotion 分支照旧)。
 */

const READ_FADE_MS = 160;
const READ_RISE_MS = 180;
const READ_STAGGER_MS = 40;
const READ_CHAR_MS = 12;
const READ_SCATTER_MS = 240;
const READ_SWEEP_MS = 300;
const READ_TRANSITION_MS = 320;
// 解码(乱码逐字还原)每一拍;6 拍 ≈ 一个 read-rise
const READ_DECODE_TICK_MS = 30;

/** 阅读面上限(任务单 2026-11-21):淡入 120–180、逐条 ≤40、逐字 ≤12、转场 ≤350、自动前进 / 等待 ≤600。 */
const CAPS = {
  fadeMs: 180,
  staggerMs: 40,
  charMs: 12,
  transitionMs: 350,
  waitMs: 600,
};

module.exports = {
  READ_FADE_MS,
  READ_RISE_MS,
  READ_STAGGER_MS,
  READ_CHAR_MS,
  READ_SCATTER_MS,
  READ_SWEEP_MS,
  READ_TRANSITION_MS,
  READ_DECODE_TICK_MS,
  CAPS,
};
