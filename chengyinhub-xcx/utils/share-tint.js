'use strict';
/* qx-a7 V1-P36(参考 Spotify 分享):分享卡底色取自内容本身的封面。
 * 输入 canvas getImageData 的 RGBA 像素,按饱和度加权取主色,再压到白字可读的暗度。
 * 拿不到像素 / 全透明 / 读图失败时返回 '',调用方保留原色块。纯函数,无 wx 依赖。 */

function hex2(n) {
  const v = Math.max(0, Math.min(255, Math.round(n)));
  return (v < 16 ? '0' : '') + v.toString(16).toUpperCase();
}

function hslToRgb(h, s, l) {
  if (s === 0) return [l * 255, l * 255, l * 255];
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
  const p = 2 * l - q;
  const f = (t) => {
    let x = t;
    if (x < 0) x += 1;
    if (x > 1) x -= 1;
    if (x < 1 / 6) return p + (q - p) * 6 * x;
    if (x < 1 / 2) return q;
    if (x < 2 / 3) return p + (q - p) * (2 / 3 - x) * 6;
    return p;
  };
  return [f(h + 1 / 3) * 255, f(h) * 255, f(h - 1 / 3) * 255];
}

function rgbToHsl(r, g, b) {
  const R = r / 255; const G = g / 255; const B = b / 255;
  const max = Math.max(R, G, B); const min = Math.min(R, G, B);
  const l = (max + min) / 2;
  if (max === min) return [0, 0, l];
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  let h;
  if (max === R) h = (G - B) / d + (G < B ? 6 : 0);
  else if (max === G) h = (B - R) / d + 2;
  else h = (R - G) / d + 4;
  return [h / 6, s, l];
}

// 白字压在上面要看得清:亮度压到 0.16–0.30,饱和度封顶 0.6(不出刺眼色)
const L_MIN = 0.16;
const L_MAX = 0.30;
const S_MAX = 0.6;

function tintFromPixels(data) {
  if (!data || typeof data.length !== 'number' || data.length < 4) return '';
  let r = 0; let g = 0; let b = 0; let total = 0;
  for (let i = 0; i + 3 < data.length; i += 4) {
    if (data[i + 3] < 128) continue;
    const pr = data[i]; const pg = data[i + 1]; const pb = data[i + 2];
    const max = Math.max(pr, pg, pb); const min = Math.min(pr, pg, pb);
    const sat = max === 0 ? 0 : (max - min) / max;
    // 越鲜艳权重越大;近黑像素几乎不算,否则暗部会把主色拖成灰黑
    const w = (0.1 + sat * sat * 4) * (max < 24 ? 0.1 : 1);
    r += pr * w; g += pg * w; b += pb * w; total += w;
  }
  if (!(total > 0)) return '';
  const hsl = rgbToHsl(r / total, g / total, b / total);
  const l = Math.min(L_MAX, Math.max(L_MIN, hsl[2] * 0.6));
  const s = Math.min(S_MAX, hsl[1]);
  const rgb = hslToRgb(hsl[0], s, l);
  return '#' + hex2(rgb[0]) + hex2(rgb[1]) + hex2(rgb[2]);
}

module.exports = { tintFromPixels, L_MIN, L_MAX, S_MAX };
