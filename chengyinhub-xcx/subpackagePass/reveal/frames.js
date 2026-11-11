// 钥匙开场的序列帧清单(l-icons 2026-11-11):由 docs/delivery/questo-ui-20261109/proto/icons/key3d.js 在无头 Chrome 里烤出,
// 画面是原型画布的上半区 390×520(×1.5 = 585×780 px/帧),3 列网格 WebP。
// intro = 0–4.6s @ 18fps(83 帧,碎片飞入 → 合体 → 金尘 → 方块公转);idle = 6s 周期 @ 12fps(72 帧,缓慢摆动 + 方块公转,首尾相接)。
// 重新烤:docs/delivery/questo-ui-20261109/l-icons/bake/(本 lane 的烤帧脚本)。
module.exports = {
  fw: 585, fh: 780, cols: 3, introFps: 18, idleFps: 12,
  intro: [
  { src: '/subpackagePass/frames/key-intro-0.webp', n: 6 },
  { src: '/subpackagePass/frames/key-intro-1.webp', n: 6 },
  { src: '/subpackagePass/frames/key-intro-2.webp', n: 6 },
  { src: '/subpackagePass/frames/key-intro-3.webp', n: 6 },
  { src: '/subpackagePass/frames/key-intro-4.webp', n: 6 },
  { src: '/subpackagePass/frames/key-intro-5.webp', n: 6 },
  { src: '/subpackagePass/frames/key-intro-6.webp', n: 6 },
  { src: '/subpackagePass/frames/key-intro-7.webp', n: 6 },
  { src: '/subpackagePass/frames/key-intro-8.webp', n: 6 },
  { src: '/subpackagePass/frames/key-intro-9.webp', n: 6 },
  { src: '/subpackagePass/frames/key-intro-10.webp', n: 6 },
  { src: '/subpackagePass/frames/key-intro-11.webp', n: 6 },
  { src: '/subpackagePass/frames/key-intro-12.webp', n: 6 },
  { src: '/subpackagePass/frames/key-intro-13.webp', n: 5 }
  ],
  idle: [
  { src: '/subpackagePass/frames/key-idle-0.webp', n: 6 },
  { src: '/subpackagePass/frames/key-idle-1.webp', n: 6 },
  { src: '/subpackagePass/frames/key-idle-2.webp', n: 6 },
  { src: '/subpackagePass/frames/key-idle-3.webp', n: 6 },
  { src: '/subpackagePass/frames/key-idle-4.webp', n: 6 },
  { src: '/subpackagePass/frames/key-idle-5.webp', n: 6 },
  { src: '/subpackagePass/frames/key-idle-6.webp', n: 6 },
  { src: '/subpackagePass/frames/key-idle-7.webp', n: 6 },
  { src: '/subpackagePass/frames/key-idle-8.webp', n: 6 },
  { src: '/subpackagePass/frames/key-idle-9.webp', n: 6 },
  { src: '/subpackagePass/frames/key-idle-10.webp', n: 6 },
  { src: '/subpackagePass/frames/key-idle-11.webp', n: 6 }
  ],
};
