/* cy-option-sheet · 底部选项弹层(2026-09-02,收编 wx.showActionSheet)
 *
 * 病:`wx.showActionSheet` 是系统弹层 —— 系统蓝、系统字、系统圆角,完全在设计体系外,
 * Do not use 页明令禁止。生产代码里还有 9 处,而现有拦截只有页级一条断言
 * (club-journey-audit-fix-contract),新页天然不受约束 = 典型的闸放错层。
 *
 * 形态照 vault §3.22:平列表,行高 56、行间 1px 分隔线、顶部标题左 + ✕ 右。
 * 两种语义,别混:
 *   ① 一次选择(默认):单选圆点右对齐 + 底部一个主 CTA,选中再确认。
 *   ② 动作菜单(immediate):点一行立刻执行,没有圆点也没有 CTA
 *      —— IM 的「+」「更多」这类不是"选择"而是"动作",套确认反而多一步。
 *
 * 回调形状与 showActionSheet 对齐(select.detail.index === 原 res.tapIndex),
 * 所以 9 个落点的业务分支一行都不用改;取消走 cancel(原 fail)。
 */
/* 2026-10-23 业主定:动作菜单里的破坏性动作(删除 / 移出 / 解散 / 拉黑 / 举报…)标红,
 * 与 cy-post-actions 的 .pa-row--danger 同色。只在 immediate(点了就执行)时生效 ——
 * 一次选择的选项列表里「取消报名」之类是待选项,不是马上执行的动作,不标红。
 * 对象项可显式传 { label, danger: true|false } 覆盖自动判断。 */
const DANGER_LABEL = /^(删除|移出|移除|解散|退出|注销|撤回|拉黑|踢出|举报)/;

Component({
  options: { multipleSlots: true },
  properties: {
    // G-2:透传给内层弹层的返回守卫开关;宿主页已有自己的 page-container 覆盖它时传 false
    backGuard: { type: Boolean, value: true },
    show: { type: Boolean, value: false },
    title: { type: String, value: '请选择' },
    /* 选项。字符串数组即可(与 itemList 同形);要禁用某项时传
     * { label, disabled } 对象。 */
    items: { type: Array, value: [] },
    /* true = 动作菜单:点一行立刻发 select,不画圆点、不画 CTA */
    immediate: { type: Boolean, value: false },
    confirmText: { type: String, value: '确定' },
    /* 预选中项(仅非 immediate);-1 = 无 */
    defaultIndex: { type: Number, value: -1 },
    /* 游戏内沿用勾选标记；其他选择弹窗用实心圆与中心点。 */
    checkmark: { type: Boolean, value: false },
  },
  data: {
    rows: [],
    picked: -1,
  },
  observers: {
    items(list) { this._buildRows(list, this.data.immediate); },
    immediate(v) { this._buildRows(this.data.items, v); },
    show(v) {
      if (v) this.setData({ picked: this.data.defaultIndex });
    },
  },
  lifetimes: {
    detached() { this._eventContextClosed = true; },
  },
  methods: {
    // Optional one-opening contract. Legacy controlled hosts remain unchanged.
    // The owning Page keeps auth/pending/epoch/hop; only this opaque UI key travels.
    bindEventContext(value) {
      if (this._eventContextClosed || this._boundEventContext !== undefined
          || typeof value !== 'string' || !value || value.length > 128) return false;
      this._boundEventContext = value;
      return true;
    },
    _emitSelect(index, label) {
      if (this._eventContextClosed || this._eventContextSettled) return;
      const detail = { index, label };
      if (this._boundEventContext !== undefined) {
        this._eventContextSettled = true;
        detail.eventContext = this._boundEventContext;
      }
      this.triggerEvent('select', detail);
    },
    _buildRows(list, immediate) {
      this.setData({
        rows: (list || []).map((item, index) => {
          const row = typeof item === 'string'
            ? { label: item, disabled: false, index }
            : { label: (item && item.label) || '', disabled: !!(item && item.disabled), index };
          const explicit = item && typeof item === 'object' && typeof item.danger === 'boolean' ? item.danger : null;
          row.danger = !!immediate && (explicit === null ? DANGER_LABEL.test(row.label) : explicit);
          return row;
        }),
      });
    },
    onRow(e) {
      if (this._eventContextClosed || this._eventContextSettled) return;
      const index = Number(e.currentTarget.dataset.index);
      const row = this.data.rows[index];
      if (!row || row.disabled) return;
      if (this.data.immediate) {
        this._emitSelect(index, row.label);
        return;
      }
      this.setData({ picked: index });
    },
    onConfirm() {
      const index = this.data.picked;
      if (index < 0) return;
      this._emitSelect(index, this.data.rows[index].label);
    },
    /* 关闭 = 取消。showActionSheet 的 fail 分支(用户划走/点取消)在这里对应 cancel,
     * 9 个落点里有 3 个靠它回收状态(team-up 的 callbacks.cancel/complete、
     * roam POI 的 _handleInteractionFailure),不能只发 close 了事。 */
    onClose() {
      if (this._eventContextClosed || this._eventContextSettled) return;
      if (this._boundEventContext !== undefined) {
        this._eventContextSettled = true;
        this.triggerEvent('cancel', { eventContext: this._boundEventContext });
      } else this.triggerEvent('cancel');
    },
  },
});
