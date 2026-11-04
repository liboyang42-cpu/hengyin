'use strict';

// 个性化地图集中配置:所有 <map> 共用同一个 subkey,样式只靠 layer-style(该 key 下绑定样式的序号,从 1 起)区分。
// 仓库里永远空 key、四个序号为 0;上传前由 scripts/release-map-style.js 填进上传拷贝。
// 四套(owner 2026-11-04 定稿):black 漫游 / 附近 / 玩家搜索地图与星尘、末世、没设外观的主题;white 商家与编辑器;
// paper 纸本;cyber 即 2077,夜城。没填 key = 微信官方默认底图。背景、费用与接入步骤见 components/cy/free-map/DARK_MAP.md。
const MAP_STYLE = {
  subkey: '',
  black: 0,
  white: 0,
  paper: 0,
  cyber: 0,
};

const DEFAULT_STYLE = { subkey: '', layerStyle: 1 };
const KINDS = ['black', 'white', 'paper', '2077'];
const FIELD = { black: 'black', white: 'white', paper: 'paper', 2077: 'cyber' };

function configured(style) {
  const s = style || MAP_STYLE;
  return !!s.subkey;
}

/** kind: black / white / paper / 2077 → { subkey, layerStyle };没配 = 官方默认。 */
function mapStyleFor(kind, style) {
  const s = style || MAP_STYLE;
  const n = Number(s[FIELD[kind] || 'black']);
  if (!s.subkey || !Number.isInteger(n) || n < 1) return Object.assign({}, DEFAULT_STYLE);
  return { subkey: s.subkey, layerStyle: n };
}

/** 主题外观 → 样式:纸本 paper,夜城 2077,其余 black。 */
function kindForLook(look) {
  const style = look && !look.legacy ? look.style : '';
  if (style === 'PAPER') return 'paper';
  if (style === 'CYBER') return '2077';
  return 'black';
}

/** 叠加层按哪种底配色;没配 key 时返回 '',配色不变。 */
function mapToneFor(kind, style) {
  return configured(style) && mapStyleFor(kind, style).subkey ? kind : '';
}

// 浅底上白线白圈看不见:换墨色、浅描边;队色线只加深描边。
const LIGHT_TONES = {
  white: { core: '#1F1F1FF2', glow: '#1F1F1F2E', edge: '#FFFFFFCC', team: '#0000008C',
    fill: 'rgba(31,31,31,.10)', ring: 'rgba(31,31,31,.42)', targetFill: 'rgba(31,31,31,.14)', targetRing: 'rgba(31,31,31,.6)' },
  paper: { core: '#3B2A1CF2', glow: '#3B2A1C2E', edge: '#F3EAD8CC', team: '#2A1A0E8C',
    fill: 'rgba(59,42,28,.10)', ring: 'rgba(59,42,28,.42)', targetFill: 'rgba(59,42,28,.14)', targetRing: 'rgba(59,42,28,.6)' },
};

function isWhitish(color) {
  const c = String(color || '').toUpperCase();
  return /^#(F[0-9A-F]){3}/.test(c) || /^RGBA?\(\s*2[3-5]\d\s*,\s*2[3-5]\d\s*,\s*2[3-5]\d/.test(c);
}
function alphaOf(color) {
  const c = String(color || '');
  if (/^#[0-9A-Fa-f]{8}$/.test(c)) return parseInt(c.slice(7), 16) / 255;
  const m = c.match(/^rgba\([^)]*,\s*([\d.]+)\s*\)$/i);
  return m ? Number(m[1]) : 1;
}

/** polyline 按底配色;深底与默认底原样返回。 */
function toneLines(lines, tone) {
  const t = LIGHT_TONES[tone];
  if (!t || !Array.isArray(lines)) return lines;
  return lines.map((line) => {
    if (!line || typeof line !== 'object') return line;
    if (isWhitish(line.color)) {
      const out = Object.assign({}, line, { color: alphaOf(line.color) < 0.5 ? t.glow : t.core });
      if (line.borderWidth) out.borderColor = t.edge;
      return out;
    }
    return line.borderWidth ? Object.assign({}, line, { borderColor: t.team }) : line;
  });
}

/** 节点光圈 / 目标光晕:kind = candidate | target。 */
function toneCircle(kind, tone) {
  const t = LIGHT_TONES[tone];
  if (kind === 'target') return t ? { fillColor: t.targetFill, color: t.targetRing } : { fillColor: 'rgba(255,255,255,.22)', color: 'rgba(255,255,255,.55)' };
  return t ? { fillColor: t.fill, color: t.ring } : { fillColor: 'rgba(255,255,255,.16)', color: 'rgba(255,255,255,.4)' };
}

// 试做,默认关:漫游 16 级以上显示 3D 楼块并俯仰约 30°。
const ROAM_3D = { on: false, minScale: 16, skew: 30 };

function roam3dFor(scale, cfg) {
  const c = cfg || ROAM_3D;
  return !!c.on && Number(scale) >= c.minScale;
}

/** 玩家地图:black。 */
function playerMapStyle(style) { return mapStyleFor('black', style); }

/** 商家 / 编辑器地图:white。 */
function lightMapStyle(style) { return mapStyleFor('white', style); }

module.exports = { MAP_STYLE, ROAM_3D, roam3dFor, KINDS, configured, mapStyleFor, kindForLook, mapToneFor, toneLines, toneCircle, playerMapStyle, lightMapStyle };
