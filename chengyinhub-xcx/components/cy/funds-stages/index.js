const { buildFundsStages } = require('./view-model.js');
const app = getApp();

function identityKey() {
  const gd = app.globalData || {};
  const member = typeof app.getUserID === 'function' ? app.getUserID() : gd.user_id;
  const role = typeof app.getUserRole === 'function' ? app.getUserRole() : gd.role;
  const type = typeof app.getUserType === 'function' ? app.getUserType() : gd.user_type;
  return [member == null ? '' : member, role || '', type == null ? '' : type].join('|');
}

// 全部为 0 且不显示可提现行时,整块不画(否则只剩一个空的底色框)。
function hasVisibleRow(stages, showWithdrawable) {
  return !!(showWithdrawable || stages.pending || stages.disputed || !stages.amountsKnown
    || (Array.isArray(stages.complaintPeriod) && stages.complaintPeriod.length));
}

Component({
  properties: {
    // 提现页顶部已有「可提现余额」时传 false,避免同屏两个可提现数
    showWithdrawable: { type: Boolean, value: true },
  },
  data: { state: 'loading', stages: null, hasRows: false },
  lifetimes: {
    attached() { this._detached = false; this._hidden = false; this.load(); },
    detached() { this._detached = true; this._loadEpoch = (this._loadEpoch || 0) + 1; },
  },
  pageLifetimes: {
    show() {
      const refresh = this._hidden || this._loadIdentity !== identityKey();
      this._hidden = false;
      if (refresh) this.load();
    },
    hide() { this._hidden = true; this._loadEpoch = (this._loadEpoch || 0) + 1; },
  },
  methods: {
    // CU-C-92:可提现余额只有本组件这一处数据源(它自己拉的 /api/wallet/stages)。
    // 要按余额决定提现入口能不能用的页面(俱乐部分润)通过 bind:stages 取用,
    // 别各自再打一次同一接口 —— 两个数迟早漂成两说。
    // known=false 表示「算不出」:amountsKnown=false 时余额会被低估,不能当 0 用。
    emitStages(stages) {
      const withdrawable = stages ? Number(stages.withdrawable) : NaN;
      const known = !!stages && stages.amountsKnown && Number.isFinite(withdrawable);
      this.triggerEvent('stages', { known: known, positive: known && withdrawable > 0 });
    },
    _acceptRead(epoch) {
      if (epoch !== this._loadEpoch || this._detached || this._hidden) return false;
      const current = identityKey();
      if (current === this._loadIdentity) return true;
      const previousMember = this._loadIdentity.split('|')[0];
      const currentMember = current.split('|')[0];
      this._loadEpoch += 1;
      this.setData({ state: 'error', stages: null, hasRows: false });
      if ((!previousMember || previousMember === '0') && currentMember && currentMember !== '0') this.load();
      return false;
    },
    load() {
      if (this._detached || this._hidden) return;
      this._loadIdentity = identityKey();
      const epoch = this._loadEpoch = (this._loadEpoch || 0) + 1;
      const hadStages = Boolean(this.data.stages);
      this.setData({ state: 'loading', stages: null, hasRows: false });
      if (hadStages) this.emitStages(null);
      app.sendRequest({
        hideLoading: true,
        url: '/api/wallet/stages',
        data: {},
        method: 'POST',
        header: { 'content-type': 'application/json' },
        success: (res) => {
          if (!this._acceptRead(epoch)) return;
          const stages = res && res.code == 200 ? buildFundsStages(res.data) : null;
          if (stages) this.setData({ state: 'ready', stages, hasRows: hasVisibleRow(stages, this.data.showWithdrawable) });
          else this.setData({ state: 'error' });
          this.emitStages(stages);
        },
        fail: () => {
          if (!this._acceptRead(epoch)) return;
          this.setData({ state: 'error' }); this.emitStages(null);
        },
      });
    },
  },
});
