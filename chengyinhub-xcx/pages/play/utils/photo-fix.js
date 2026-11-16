'use strict';
// x-template-games R2:照片没人判,带坐标的站「奖品要到场」——拍照随带最近一次定位。
//  - 只在城市定向(mode=1)且这一站有坐标时带;线上站与自由探索(扫码进店)不带。
//  - 定位超过 2 分钟视为旧读数,不带(服务端按「没带定位」只发探索值与徽章,不拦完成)。
//  - 不为拍照单独弹定位:进度免费,没有定位照样提交。
const MAX_AGE_MS = 2 * 60 * 1000;

function photoFix(loc, node, mode, now) {
  if (Number(mode) !== 1 || !node || !node.lat || !node.lng || !loc) return {};
  const at = Number(loc.capturedAt);
  const clock = Number.isFinite(now) ? now : Date.now();
  if (!Number.isFinite(at) || at <= 0 || clock - at > MAX_AGE_MS) return {};
  if (!Number.isFinite(Number(loc.longitude)) || !Number.isFinite(Number(loc.latitude))) return {};
  const fix = { longitude: loc.longitude, latitude: loc.latitude };
  if (loc.accuracy !== undefined && loc.accuracy !== null) fix.accuracy = loc.accuracy;
  return fix;
}

module.exports = { photoFix, MAX_AGE_MS };
