/**
 * 玩法模板 · 检定段(原型 A e-temp,2026-10-28 晚):分组列表 + 就地展开。
 * 数据仍写在 advanced.check(skill / tier / mods / bargain / successText / failText),
 * 这里只把选择翻成配置,作者看不到键名。主题上下文由 fabu 经 checkCtx 带来(见 check-context.js)。
 */
const ctxUtil = require('../utils/publish/check-context.js');
const cfg = require('../utils/publish/advanced-game-config.js');

const clone = v => JSON.parse(JSON.stringify(v));
const BONUS = [1, 2, 3];

const data = {
  checkView: {
    skillState: 'off', skillValue: '', skillOptions: [], attrs: [], mods: [], modAddOff: false,
    bargainShow: false, bargainOn: false, prices: [], priceAddShow: false, luckLabel: '',
  },
  checkOpen: '',           // 一次只展开一行:skill / luck / mod-<i> / price-<i>
  checkSheet: { show: false, mode: '', title: '', kinds: [], open: '' },
  bargainBonus: BONUS,
};

const methods = {
  _initCheckCtx(options) {
    this._checkCtxRaw = options && options.checkCtx;
    this._checkCtx = ctxUtil.parse(this._checkCtxRaw);
  },
  _refreshCheckView(adv) {
    const ctx = this._checkCtx;
    const check = ((adv || this.data.advanced || {}).check) || {};
    const mods = Array.isArray(check.mods) ? check.mods : [];
    const rows = ctxUtil.modRows(ctx, mods);
    const bargain = check.bargain || null;
    const luck = cfg.luckIndex(mods);
    this.setData({ checkView: {
      skillState: ctxUtil.skillState(ctx),
      skillValue: ctxUtil.skillValue(ctx, check.skill),
      skillText: ctxUtil.skillText(ctx, check.skill),
      skillOptions: ctxUtil.skillOptions(ctx, check.skill),
      attrs: (ctx && ctx.attrs) || [],
      mods: rows,
      modAddOff: ctxUtil.modTotal(mods) >= ctxUtil.capOf(ctx) || mods.length >= cfg.MAX_MODS || !ctxUtil.modKindRows(ctx).length,
      bargainShow: ctxUtil.bargainVisible(ctx, check),
      bargainOn: !!bargain,
      bargainLabel: bargain ? bargain.label || '' : '',
      bargainBonus: bargain ? bargain.bonus : 0,
      prices: bargain ? ctxUtil.priceRows(ctx, bargain.effects) : [],
      priceAddShow: !!bargain && (bargain.effects || []).length < ctxUtil.MAX_BARGAIN_EFFECTS && ctxUtil.priceKindRows(ctx).length > 0,
      luckLabel: luck >= 0 ? cfg.LUCK_OPTIONS[luck].label : '',
    } });
  },
  toggleCheckRow(e) {
    const key = e.currentTarget.dataset.open;
    this.setData({ checkOpen: this.data.checkOpen === key ? '' : key });
  },
  retryCheckCtx() {
    this._checkCtx = ctxUtil.parse(this._checkCtxRaw);
    this._refreshCheckView();
  },
  pickCheckSkill(e) {
    this.setData({ checkOpen: '' });
    this._setFormState({ 'advanced.check.skill': e.currentTarget.dataset.key || '', advancedConfigError: '' });
  },
  pickCheckTierSeg(e) {
    this.pickCheckTier({ detail: { value: Number(e.currentTarget.dataset.index) } });
  },
  goD20Settings() {
    // 属性在主题的「d20 与角色设定」里加:回到主题页
    wx.navigateBack({ delta: 1 });
  },
  // ===== 情境加值 =====
  _patchMod(index, fn) {
    const mods = clone((this.data.advanced.check || {}).mods || []);
    if (!mods[index]) return;
    const next = fn(mods[index]);
    if (next) mods[index] = next; else mods.splice(index, 1);
    this._writeMods(mods);
  },
  stepModAmount(e) {
    const { index, delta, max } = e.currentTarget.dataset;
    this._patchMod(Number(index), mod => Object.assign(mod, { value: Math.max(1, Math.min(Number(max), (Number(mod.value) || 0) + Number(delta))) }));
  },
  stepModThreshold(e) {
    const { index, delta } = e.currentTarget.dataset;
    this._patchMod(Number(index), mod => {
      mod.when = Object.assign({}, mod.when, { value: Math.max(0, Math.min(99, (Number(mod.when && mod.when.value) || 0) + Number(delta))) });
      return mod;
    });
  },
  pickModMode(e) {
    const { index, mode } = e.currentTarget.dataset;
    const ctx = this._checkCtx;
    // 换回固定加值要占 1 点上限;上限用完时这一下不生效
    if (mode !== 'attr' && ctxUtil.modTotal((this.data.advanced.check || {}).mods) >= ctxUtil.capOf(ctx)) return;
    this._patchMod(Number(index), mod => {
      if (mode === 'attr') {
        const first = (ctx && ctx.attrs || [])[0];
        if (!first) return mod;
        delete mod.value; mod.var = mod.var || first.key;
      } else { delete mod.var; mod.value = 1; }
      return mod;
    });
  },
  pickModAttr(e) {
    const { index, key } = e.currentTarget.dataset;
    this._patchMod(Number(index), mod => Object.assign(mod, { var: key }));
  },
  removeMod(e) {
    this.setData({ checkOpen: '' });
    this._patchMod(Number(e.currentTarget.dataset.index), () => null);
  },
  // ===== 魔鬼交易 =====
  _writeBargain(bargain) {
    const check = clone(this.data.advanced.check || {});
    if (bargain) check.bargain = bargain; else delete check.bargain;
    this._setFormState({ 'advanced.check': check, advancedConfigError: '' });
  },
  toggleBargain(e) {
    const on = !!(e.detail && e.detail.value);
    this._writeBargain(on ? Object.assign({ label: '', bonus: 2, effects: [] }, (this.data.advanced.check || {}).bargain) : null);
  },
  inputBargainLabel(e) {
    const bargain = clone((this.data.advanced.check || {}).bargain || {});
    bargain.label = e.detail.value;
    this._writeBargain(bargain);
  },
  pickBargainBonus(e) {
    const bargain = clone((this.data.advanced.check || {}).bargain || {});
    bargain.bonus = Number(e.currentTarget.dataset.bonus);
    this._writeBargain(bargain);
  },
  stepPrice(e) {
    const { index, delta } = e.currentTarget.dataset;
    const bargain = clone((this.data.advanced.check || {}).bargain || {});
    const row = ctxUtil.priceRows(this._checkCtx, bargain.effects)[Number(index)];
    const eff = (bargain.effects || [])[Number(index)];
    if (!eff || !row || !row.stepper) return;
    let value = (Number(eff.value) || 0) + Number(delta);
    if (eff.op === 'INC' && value === 0) value += Number(delta);
    eff.value = Math.max(row.stepper.min, Math.min(row.stepper.max, value));
    this._writeBargain(bargain);
  },
  removePrice(e) {
    const bargain = clone((this.data.advanced.check || {}).bargain || {});
    bargain.effects = (bargain.effects || []).filter((_, i) => i !== Number(e.currentTarget.dataset.index));
    this.setData({ checkOpen: '' });
    this._writeBargain(bargain);
  },
  // ===== 「添加加值 / 添加代价」半屏:种类一列,点一种就地展开名字 =====
  openCheckSheet(e) {
    const mode = e.currentTarget.dataset.mode;
    if (mode === 'mod' && this.data.checkView.modAddOff) return;
    const kinds = mode === 'price' ? ctxUtil.priceKindRows(this._checkCtx) : ctxUtil.modKindRows(this._checkCtx);
    this.setData({ checkSheet: { show: true, mode, title: mode === 'price' ? '添加代价' : '添加加值', kinds, open: '' } });
  },
  closeCheckSheet() {
    this.setData({ 'checkSheet.show': false, 'checkSheet.open': '' });
  },
  toggleCheckKind(e) {
    const key = e.currentTarget.dataset.key;
    this.setData({ 'checkSheet.open': this.data.checkSheet.open === key ? '' : key });
  },
  pickCheckName(e) {
    const { kind, key } = e.currentTarget.dataset;
    const ctx = this._checkCtx;
    const check = clone(this.data.advanced.check || {});
    if (this.data.checkSheet.mode === 'price') {
      const eff = ctxUtil.newPrice(ctx, kind, key);
      if (!eff || !check.bargain) return;
      check.bargain.effects = (check.bargain.effects || []).concat(eff);
      this.setData({ checkOpen: 'price-' + (check.bargain.effects.length - 1) });
      this._setFormState({ 'advanced.check': check, advancedConfigError: '' });
    } else {
      const mod = ctxUtil.newMod(ctx, kind, key);
      if (!mod) return;
      const mods = (check.mods || []).concat(mod);
      this.setData({ checkOpen: 'mod-' + (mods.length - 1) });
      this._writeMods(mods);
    }
    this.closeCheckSheet();
  },
};

module.exports = { data, methods };
