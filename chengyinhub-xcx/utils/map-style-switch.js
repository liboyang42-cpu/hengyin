// 自由图(漫游、附近)的底图切换:选择写本地存储,layer-style 不能热改,所以先撤图、下一拍按新样式重建(同 play 的 fmStyleReady)。
// 写成 const 对象再返回:台账 / 处理器门禁(xcx-scan)只扫模块里 `const X = {` 的键。
const mapStyle = require('./map-style.js');

/** 页面 data 的初始四项,模块加载时读一次存储。 */
const LABELS = mapStyle.KIND_ITEMS.map((i) => i.label);
function indexOf(kind) { return mapStyle.KIND_ITEMS.findIndex((i) => i.kind === kind); }

function initialData(storage) {
  const kind = mapStyle.freeMapKind(storage), next = mapStyle.mapStyleFor(kind);
  return { fmKind: kind, fmSubkey: next.subkey, fmLayerStyle: next.layerStyle, fmTone: mapStyle.mapToneFor(kind), fmStyleReady: true, mapKindItems: mapStyle.KIND_ITEMS, mapKindLabels: LABELS, mapKindIndex: indexOf(kind) };
}

function methods() {
  const api = {
    /** 把页面切到 kind;persist 为真时记住选择。after 在新地图挂上后调用。 */
    _applyMapKind(kind, persist, after) {
      const k = mapStyle.KINDS.indexOf(String(kind)) >= 0 ? String(kind) : 'black';
      if (persist) mapStyle.saveFreeMapKind(k);
      const d = this.data || {};
      if (d.fmKind === k && d.fmStyleReady) return;
      const next = mapStyle.mapStyleFor(k);
      const patch = { fmKind: k, fmSubkey: next.subkey, fmLayerStyle: next.layerStyle, fmTone: mapStyle.mapToneFor(k), fmStyleReady: true, mapKindIndex: indexOf(k) };
      this.setData({ fmStyleReady: false });
      const later = typeof wx !== 'undefined' && wx.nextTick ? wx.nextTick : (fn) => setTimeout(fn, 0);
      later(() => {
        if (this._unloaded) return;
        this.setData(patch, () => { if (this._onMapKindRebuilt) this._onMapKindRebuilt(); if (after) after(); });
      });
    },
    /** onShow:别的页改了选择,回来跟着换。 */
    _syncMapKindPref() {
      const kind = mapStyle.freeMapKind();
      if (kind !== (this.data || {}).fmKind) this._applyMapKind(kind, false);
    },
  };
  return api;
}

module.exports = { initialData, methods };
