/**
 * cy-post-drafts · 草稿弹层(Figma 379:923)。Filled / Loading 两态 + 稿上没画的 Empty。
 *
 * 页面用法:
 *   wxml: <cy-post-drafts id="drafts" show="{{draftsShow}}" loading="{{draftsLoading}}"
 *                         drafts="{{drafts}}"
 *                         bind:select="onDraftPick" bind:delete="onDraftDelete" bind:close="closeDrafts" />
 *   js:   onDraftPick(e)   { ... }   // e.detail = { index, key, item } —— 拿 key 去 loadDraft
 *         onDraftDelete(e) {
 *           // e.detail 同上。先从列表拿掉,再 toast.undo:4 秒内撤销就恢复,到期才 removeDraft。
 *         }
 *
 * 数据边界:组件**不读 storage**。草稿的真源是 utils/publish/pro-editor-draft.js
 * (fabu 页在用的那一套,已拍板复用,不新造),由页面 load 好再传进来 —— 组件只渲染 + 发事件。
 * drafts 每项:{ key, avatar, nickname, date, summary }
 *   key      = 传给 pro-editor-draft 的 draftUuid(新草稿)或 topicId(既有主题)。
 *              组件不解释它,原样回传给页面。
 *   date     = 已格式化好的展示串(组件不做时间格式化,那是页面/工具层的事)。
 *   summary  = 摘要,两行截断。
 *
 * 删除不再走确认闸(2026-10-23 业主定):草稿只在本机,改成「先删、提示条里可撤销」。
 * 组件点垃圾桶直接上报 delete;页面负责从列表拿掉 + toast.undo + 到期才真正删 storage。
 */
Component({
  behaviors: [require('../../../../../behaviors/reduced-motion.js')],
  properties: {
    show:    { type: Boolean, value: false },
    /* 草稿还在读:走骨架而不是先闪一下空态(空态是个结论,读完才知道对不对)。 */
    loading: { type: Boolean, value: false },
    drafts:  { type: Array,   value: [] },
    /* sheetStyle:上层的高度/位置。由调用方算(只有它拿得到胶囊坐标),
       组件不自己推 —— 推出来的值在 env() 返回 0 的设备上会压住胶囊。 */
    sheetStyle: { type: String, value: '' },
  },
  methods: {
    /* 遮罩 / 「取消」:只上报,show 的所有权在页面 */
    onClose() {
      this.triggerEvent('close');
    },

    /* 选中一条:先收面板再上报 —— 接着要跳去编辑器,面板留在原地是碍事的。 */
    onSelect(e) {
      const row = this._row(e);
      if (!row) return;
      this.triggerEvent('close');
      this.triggerEvent('select', row);
    },

    /* 2026-10-23 业主定:删草稿改成「先删、提示条里可撤销」,不再先弹确认 ——
     * 草稿只在本机,撤销就是什么都没发生。点垃圾桶直接上报,由页面先从列表拿掉、
     * 再用 toast.undo 留 4 秒撤销,到期才真正删 storage(见 post-compose 的 onDeleteDraft)。 */
    onDelete(e) {
      const row = this._row(e);
      if (!row) return;
      this.triggerEvent('delete', row);
    },

    /* dataset.index → 那一条草稿。越界/脏 index 一律返回 null,不让它往下走。 */
    _row(e) {
      const dataset = (e && e.currentTarget && e.currentTarget.dataset) || {};
      const index = Number(dataset.index);
      const item = (this.data.drafts || [])[index];
      if (!item) return null;
      return { index, key: item.key, item };
    },

  },
});
