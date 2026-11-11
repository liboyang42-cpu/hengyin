// 外观主色的 JS 镜像(l-icons 2026-11-11):章节门画布画圆点章节号 / 章节印要用外观主色 --lk-a 与墨色 --lk-ink,
// 画布读不到 CSS 变量,所以把 pages/play/look-tokens.wxss 里四种外观 × 五种配色的 a / ink / bg 逐字抄成一份。
// tests/unit/l-icons-20261111.test.js 从 wxss 重新解析并与这里逐项比对,改 wxss 必须同步改这里。
var LOOK_COLORS = {
  STARDUST: { DEFAULT: { a: '#E9E2D0', ink: '#F2F2F0', bg: '#0A0A0A' }, BLUE: { a: '#CFE0FF', ink: '#F5F8FF', bg: '#0E1A33' }, RED: { a: '#FFB8AC', ink: '#FFF4F5', bg: '#2A0E12' }, YELLOW: { a: '#F2D58A', ink: '#FFFBEF', bg: '#2A2208' }, WHITE: { a: '#111318', ink: '#111318', bg: '#F5F6F8' } },
  PAPER: { DEFAULT: { a: '#D0503C', ink: '#ECE6D8', bg: '#1D1B18' }, BLUE: { a: '#E8C66A', ink: '#EEF0EA', bg: '#1C2A44' }, RED: { a: '#E8C66A', ink: '#F4E6DC', bg: '#3A1A17' }, YELLOW: { a: '#A3281D', ink: '#2B241A', bg: '#E6DCC4' }, WHITE: { a: '#B0302A', ink: '#1E1C19', bg: '#F4F1EA' } },
  SURVIVAL: { DEFAULT: { a: '#FF8A3D', ink: '#E4E7E9', bg: '#0E1012' }, BLUE: { a: '#FFB35C', ink: '#EAF3FB', bg: '#0B1626' }, RED: { a: '#7FE6C4', ink: '#F3E3E0', bg: '#1C0A0A' }, YELLOW: { a: '#8FD3FF', ink: '#FFF2DC', bg: '#2A1D0C' }, WHITE: { a: '#D4561E', ink: '#14202B', bg: '#E3E9EE' } },
  CYBER: { DEFAULT: { a: '#F3E600', ink: '#ECEBE4', bg: '#0B0B0F' }, BLUE: { a: '#2DE2E6', ink: '#E2F1FF', bg: '#06101C' }, RED: { a: '#FF2E4C', ink: '#FFE8EA', bg: '#12060A' }, YELLOW: { a: '#FCEE0A', ink: '#FFF6CC', bg: '#100E04' }, WHITE: { a: '#E0002B', ink: '#101216', bg: '#E6E7EA' } },
};
// 老主题(没选过外观)的章节门:回落原型深色(chapter-flip/index.wxss .cf 的 #1F1F21 / #F5F5F2),主色用原来的金
var LEGACY = { a: '#F1C94B', ink: '#F5F5F2', bg: '#1F1F21' };

/** look = { style, palette, legacy } (theme-look.normalizeLook 的产物);palette 没设或读不懂按 DEFAULT。 */
function colorsFor(look) {
  if (!look || look.legacy || !look.style) return LEGACY;
  var st = LOOK_COLORS[String(look.style).toUpperCase()];
  if (!st) return LEGACY;
  return st[String(look.palette || 'DEFAULT').toUpperCase()] || st.DEFAULT;
}

module.exports = { LOOK_COLORS: LOOK_COLORS, LEGACY: LEGACY, colorsFor: colorsFor };
