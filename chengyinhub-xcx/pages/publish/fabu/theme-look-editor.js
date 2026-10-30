// 发布页「画面风格」：主题选一种风格 + 这种风格自己的参数 + 转场 + 故事流字体。
// 存在 formData.look（JSON 字符串），保存时随 payload.look 上送，后端 ThemeLook 严格校验。
const themeLook = require('../utils/publish/theme-look.js');

const SWATCH = { STARDUST: 'look-sw--stardust', PAPER: 'look-sw--paper', SURVIVAL: 'look-sw--survival', CYBER: 'look-sw--cyber' };

function view(raw) {
  const n = themeLook.normalizeLook(raw), style = themeLook.styleOf(n.style);
  const label = (list, v) => (list.find((x) => x[0] === v) || [, ''])[1];
  return {
    styles: themeLook.STYLES.map((s) => ({ value: s.value, label: s.label, sw: SWATCH[s.value], on: s.value === n.style })),
    params: style.params.map((p) => ({ key: p.key, label: p.label, options: p.options.map((o) => ({ value: o[0], label: o[1], on: n.params[p.key] === o[0] })) })),
    transitions: themeLook.TRANSITIONS.map((t) => ({ value: t[0], label: t[0] === 'AUTO' ? '跟随风格 · ' + label(themeLook.TRANSITIONS, style.transition) : t[1], on: n.transition === t[0] })),
    fonts: themeLook.STORY_FONTS.map((f) => ({ value: f[0], label: f[1], on: n.storyFont === f[0] })),
    summary: style.label + ' · ' + label(themeLook.TRANSITIONS, themeLook.resolveTransition(n)) + ' · ' + label(themeLook.STORY_FONTS, n.storyFont),
  };
}

const data = { lookSheetShow: false, lookView: view(null), lookPreview: { show: false, look: null } };

const methods = {
  _refreshLookView() { this.setData({ lookView: view(this.data.formData.look) }); },
  openLookEditor() { this._refreshLookView(); this.setData({ lookSheetShow: true }); },
  closeLookEditor() { this.setData({ lookSheetShow: false }); },
  _patchLook(fn) {
    const n = themeLook.normalizeLook(this.data.formData.look);
    fn(n);
    const raw = themeLook.serializeLook(themeLook.normalizeLook(n));
    this.setData({ 'formData.look': raw, lookView: view(raw) });
  },
  // 点一种风格:先从底部弹出预览,给个大概的样子;点「使用这个风格」才写回。
  // 换风格时参数换成新风格的默认值,转场与字体保留作者已选的。
  onLookStyle(e) {
    const v = e.currentTarget.dataset.value, n = themeLook.normalizeLook(this.data.formData.look);
    if (n.style !== v) { n.style = v; n.params = {}; }
    this.setData({ lookPreview: { show: true, look: themeLook.normalizeLook(n) } });
  },
  onLookPreviewUse(e) {
    const look = e.detail.look;
    this._patchLook((n) => { n.style = look.style; n.params = look.params; });
    this.setData({ 'lookPreview.show': false });
  },
  onLookPreviewClose() { this.setData({ 'lookPreview.show': false }); },
  onLookParam(e) { const d = e.currentTarget.dataset; this._patchLook((n) => { n.params[d.key] = d.value; }); },
  onLookTransition(e) { const v = e.currentTarget.dataset.value; this._patchLook((n) => { n.transition = v; }); },
  onLookFont(e) { const v = e.currentTarget.dataset.value; this._patchLook((n) => { n.storyFont = v; }); },
  /** 保存 payload 用：与默认相同时上送空串（= 默认风格）。 */
  _lookPayload() { return themeLook.serializeLook(this.data.formData.look); },
};

module.exports = { data, methods, view };
