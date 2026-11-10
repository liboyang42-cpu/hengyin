const COLORS = [
  'var(--cy-color-play-accent)',
  'var(--cy-color-play-accent)',
  'var(--cy-color-text-secondary)',
  'var(--cy-color-play-accent)',
  'var(--cy-color-text-tertiary)',
];
// r4-motion 2026-11-10(MOTION.md M12):brand 调色 = 品牌强调色 + 白 + 两个弱色;默认仍是上面那组
const PALETTES = {
  brand: ['var(--cy-color-brand)', '#FFFFFF', 'var(--cy-color-brand)', 'var(--cy-color-text-secondary)', 'var(--cy-color-text-tertiary)'], /* ds-ok 彩片里的白 */
};
const MAX_PIECES = 44;   // 44 片 CSS 彩片是上限,不加画布分支
const reducedMotionBehavior = require('../../../behaviors/reduced-motion.js');

// 从 play/roam 既有的逐片数据驱动彩旗收口而来；eventKey 变化即换 key 重播，
// 不靠 JS 计时器清场，单轮时长只由 WXSS 的 --cy-motion-celebrate 决定。
function buildPieces(eventKey, count, palette) {
  const n = Math.max(1, Math.min(MAX_PIECES, Math.round(Number(count) || MAX_PIECES)));
  const colors = PALETTES[palette] || COLORS;
  return Array.from({ length: n }, (_, index) => ({
    id: eventKey + ':' + index,
    left: Math.round(Math.random() * 100),
    color: colors[index % colors.length],
    /* 每片自己的起跳时刻。全部同时开始的话,四十四片会连成横穿屏幕的一条线往下掉 ——
       硬币落面那一下看起来就是「屏幕上划过一条虚线」,而不是撒了一把彩片。
       延时只能写在行内:组件 WXSS 不支持 :nth-child。 */
    delay: Math.round(Math.random() * 620),
  }));
}

Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    eventKey: {
      type: String,
      value: '',
      observer(eventKey) {
        if (!eventKey || this.data.reducedMotion) {
          this.setData({ pieces: [] });
          return;
        }
        this.setData({ pieces: buildPieces(eventKey, this.data.count, this.data.palette) });
      },
    },
    // 片数:默认 44;「六成」场景(徽章到手时的第二次庆祝等)传 26。只在下一次 eventKey 变化时生效
    count: { type: Number, value: 44 },
    // 调色:默认沿用游玩页强调色那组;brand = 品牌色 + 白 + 两个弱色
    palette: { type: String, value: '' },
  },
  data: { pieces: [] },
  observers: {
    reducedMotion(reducedMotion) {
      if (reducedMotion) this.setData({ pieces: [] });
    },
  },
});
