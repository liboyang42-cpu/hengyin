// cy-case-anim · 结案动图(r2 b2-ug-04:成功 / 失败 / 等待都要有动态的图)。
//
// 素材:Google Noto Animated Emoji(🔎 1f50e 搜查 / 🎉 1f389 结案 / 😵 1f635 指认失败),
//   https://googlefonts.github.io/noto-emoji-animation/ ,许可 CC BY 4.0(署名见本注释与 r2-kits RESULT)。改动:缩小尺寸、降帧率与色数,第一帧另存 PNG。
//   原 512px GIF 经 ffmpeg 降到 128–144px、10–12 帧/秒、32–48 色;每张 ≤ 92 KB。
// 放在 subpackageTools 分包:玩法壳所在的 pages/play 分包已接近单包上限,动图不进它。
// 调用方(pages/play)按分包异步化引用,必须声明 componentPlaceholder。
// 减弱动效时只出第一帧静图。

const reducedMotionBehavior = require('../../../behaviors/reduced-motion.js');

const KINDS = ['search', 'success', 'fail'];

Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    kind: { type: String, value: 'search' },
    size: { type: Number, value: 220 },
  },
  data: { src: '' },
  observers: {
    'kind, reducedMotion': function (kind, reduced) {
      const k = KINDS.indexOf(kind) >= 0 ? kind : 'search';
      this.setData({ src: '/subpackageTools/components/case-anim/img/' + k + (reduced ? '.png' : '.gif') });
    },
  },
});
