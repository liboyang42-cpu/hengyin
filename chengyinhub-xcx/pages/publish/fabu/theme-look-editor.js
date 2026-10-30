// 发布页「画面风格」(2026-10-30 业主,对齐原型 v7):预览常驻;风格 → 这种风格自己的参数 →(末世)每章时间段 → 主题配色 → 故事字体。
// 主题配色 = 原章节配色提到主题层(同五档),每种风格把五档映射成自己的底色;转场跟随风格,作者不选。
// 存在 formData.look(JSON 字符串),保存时随 payload.look 上送,后端 ThemeLook 严格校验。
const themeLook = require('../utils/publish/theme-look.js');
const { PRESETS: ATMOSPHERES, normalizeAtmosphere } = require('../../../utils/chapter-atmosphere.js');

const SWATCH = { STARDUST: 'look-sw--stardust', PAPER: 'look-sw--paper', SURVIVAL: 'look-sw--survival', CYBER: 'look-sw--cyber' };

// 还没设主题配色的老主题:预览先按第一章的章节配色画,点了才写进主题
function effectivePalette(n, chapters) {
  if (n.palette) return n.palette;
  const first = (chapters || []).find((c) => !c.opening) || (chapters || [])[0];
  return normalizeAtmosphere(first && first.atmospherePreset);
}

function view(raw, chapters) {
  const n = themeLook.normalizeLook(raw), style = themeLook.styleOf(n.style), palette = effectivePalette(n, chapters);
  const label = (list, v) => (list.find((x) => x[0] === v) || [, ''])[1];
  const pal = ATMOSPHERES.find((a) => a.value === palette);
  return {
    look: Object.assign({}, n, { palette }),
    themed: !!n.palette,
    styleKey: n.style.toLowerCase(),
    styles: themeLook.STYLES.map((s) => ({ value: s.value, label: s.label, sw: SWATCH[s.value], on: s.value === n.style })),
    params: style.params.map((p) => ({ key: p.key, label: p.label, options: p.options.map((o) => ({ value: o[0], label: o[1], on: n.params[p.key] === o[0] })) })),
    palettes: ATMOSPHERES.map((a) => ({ value: a.value, label: a.label, note: a.note, className: a.className, on: a.value === palette })),
    fonts: themeLook.STORY_FONTS.map((f) => ({ value: f[0], label: f[1], on: f[0] === n.storyFont })),
    // 末世:每章一个时间段(按章节顺序),再点一次取消
    times: n.style !== 'SURVIVAL' ? [] : (chapters || []).map((c, i) => ({ index: i, name: c.name || ('第 ' + (i + 1) + ' 章'),
      options: themeLook.TIMES.map((t) => ({ value: t[0], label: t[1], on: !!n.times && n.times[i] === t[0] })) })),
    summary: style.label + ' · ' + pal.label + ' · ' + label(themeLook.STORY_FONTS, n.storyFont),
  };
}

const data = { lookSheetShow: false, lookView: view(null) };

const methods = {
  _refreshLookView() { this.setData({ lookView: view(this.data.formData.look, this.data.formData.chapters) }); },
  openLookEditor() { this._refreshLookView(); this.setData({ lookSheetShow: true }); },
  closeLookEditor() { this.setData({ lookSheetShow: false }); },
  _patchLook(fn) {
    const n = themeLook.normalizeLook(this.data.formData.look);
    fn(n);
    const raw = themeLook.serializeLook(n);
    this.setData({ 'formData.look': raw, lookView: view(raw, this.data.formData.chapters) });
  },
  // 换风格:参数换成新风格的默认,主题配色与字体保留;转场始终跟随风格
  onLookStyle(e) {
    const v = e.currentTarget.dataset.value;
    this._patchLook((n) => { if (n.style !== v) { n.style = v; n.params = {}; } n.transition = 'AUTO'; });
  },
  onLookParam(e) { const d = e.currentTarget.dataset; this._patchLook((n) => { n.params[d.key] = d.value; }); },
  onLookPalette(e) { const v = e.currentTarget.dataset.value; this._patchLook((n) => { n.palette = v; }); },
  onLookFont(e) { const v = e.currentTarget.dataset.value; this._patchLook((n) => { n.storyFont = v; }); },
  onLookTime(e) {
    const d = e.currentTarget.dataset, i = Number(d.index);
    this._patchLook((n) => { const t = (n.times || []).slice(); while (t.length <= i) t.push(''); t[i] = t[i] === d.value ? '' : d.value; n.times = t; });
  },
  /** 保存 payload 用：与默认相同时上送空串（= 默认风格）。 */
  _lookPayload() { return themeLook.serializeLook(this.data.formData.look); },
};

module.exports = { data, methods, view };
