'use strict';

/**
 * 枚举 → 接口字符串的单一适配入口(C:U-180)。
 *
 * 后端枚举一律以大写字符串下发/接收(PUBLIC / CONFIRMED / PAUSED ...)。页面里散落的
 * `String(x || '').trim().toUpperCase()` 统一收口到这里,今后后端改名或加别名只改一处。
 * 只做形态归一,不做取值校验:认不认得某个值由调用方的白名单/映射表决定。
 *
 * null / undefined → ''(调用方按「未知」处理);数字等非字符串按 String() 转换。
 */
function enumCode(value) {
  if (value === null || value === undefined) return '';
  return String(value).trim().toUpperCase();
}

/** 按归一后的码查表;查不到给 fallback。 */
function enumLabel(map, value, fallback) {
  const code = enumCode(value);
  return Object.prototype.hasOwnProperty.call(map || {}, code) ? map[code] : fallback;
}

module.exports = { enumCode: enumCode, enumLabel: enumLabel };
