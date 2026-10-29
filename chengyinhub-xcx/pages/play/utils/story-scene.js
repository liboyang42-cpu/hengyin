/**
 * utils/story-scene.js —— 故事流背景随剧情换景(2026-09-23 用户裁决「优化顺序第 3 项」)
 *
 * 参数逐字取自《预制人生故事流原型.html》的 SC 表(去掉了底色 bg:底色由章节氛围的 CSS 决定,
 * 作者在发布页配的那一档;这里只管底色之上的粒子、光晕、光束、散景)。
 */
const MOOD_SCENES = { DEFAULT: 'city', BLUE: 'hush', RED: 'grown', YELLOW: 'afternoon', WHITE: 'office' };

const SCENES = {
  void:      { n: 0.06, col: [255, 255, 255], size: 0.9, vx: 0,     vy: -0.04, alpha: 0.35, glow: 0,    gx: 0.3,  gy: 0.2, gcol: [255, 190, 120], beam: 0,    bokeh: 0 },
  awake:     { n: 0.4,  col: [200, 215, 255], size: 1,   vx: 0,     vy: -0.12, alpha: 0.6,  glow: 0.06, gx: 0.5,  gy: 0.1, gcol: [140, 170, 255], beam: 0,    bokeh: 0 },
  city:      { n: 0.35, col: [255, 225, 190], size: 1,   vx: 0.05,  vy: -0.05, alpha: 0.5,  glow: 0.08, gx: 0.8,  gy: 0.9, gcol: [120, 160, 255], beam: 0,    bokeh: 1 },
  afternoon: { n: 0.95, col: [255, 212, 140], size: 1.7, vx: 0.04,  vy: -0.2,  alpha: 0.85, glow: 0.36, gx: 0.18, gy: 0.1, gcol: [255, 188, 110], beam: 0,    bokeh: 0 },
  hush:      { n: 0.8,  col: [236, 240, 246], size: 1.2, vx: 0,     vy: 0.01,  alpha: 0.45, glow: 0,    gx: 0.8,  gy: 0.1, gcol: [255, 255, 255], beam: 0.12, bokeh: 0 },
  grown:     { n: 0.5,  col: [210, 215, 220], size: 1,   vx: 0,     vy: -0.3,  alpha: 0.5,  glow: 0.04, gx: 0.5,  gy: 0.5, gcol: [200, 200, 210], beam: 0,    bokeh: 0 },
  office:    { n: 0.9,  col: [190, 236, 255], size: 1.1, vx: 0,     vy: 0,     alpha: 0.55, glow: 0.08, gx: 0.5,  gy: 0,   gcol: [190, 240, 255], beam: 0,    bokeh: 0 },
};

/** 氛围(类名或预设值)→ 场景;读到梦块 → 苏醒。 */
function sceneFor(mood, inDream) {
  if (inDream) return 'awake';
  const key = String(mood || '').replace(/^atmosphere--/, '').toUpperCase();
  return MOOD_SCENES[key] || 'city';
}

function initial(name) {
  const s = SCENES[name] || SCENES.city;
  return JSON.parse(JSON.stringify(s));
}

/** 往目标场景插值一步(原型每帧 k=0.025;减动效给 1 = 直接到位)。返回新对象,不改入参。 */
function step(cur, name, k) {
  const tg = SCENES[name] || SCENES.city;
  const out = {};
  Object.keys(tg).forEach((key) => {
    const a = cur[key];
    const b = tg[key];
    if (Array.isArray(b)) out[key] = b.map((v, i) => (k >= 1 ? v : a[i] + (v - a[i]) * k));
    else out[key] = k >= 1 ? b : a + (b - a) * k;
  });
  return out;
}

module.exports = { SCENES, sceneFor, initial, step };
