// OFF-AI-DEL-01:设置页「AI 对话记录」—— 导出 / 清除。
// 复用 NPC 聊天面板的同一条服务端链路:导出 GET /api/ai/npc/chat/data/export(chat-export 格式化后复制文本);
// 清除 POST /api/ai/npc/chat/data/delete(身份只取登录态 = 仅本人记录;author-write 回执幂等,与面板同一 bucket)。
const chatExport = require('../npc-chat/chat-export.js');
const authorWrites = require('../../../../utils/author-write-recovery.js');
const cyToast = require('../../../../utils/toast.js');

const ITEMS = ['导出对话记录', { label: '清除对话记录', danger: true }];

Component({
  properties: {
    show: { type: Boolean, value: false },
  },
  observers: {
    show(visible) { this._open = !!visible; },
  },
  methods: {
    onSelect(e) {
      const index = Number(e.detail && e.detail.index);
      this.triggerEvent('close');
      if (index === 0) this.exportRecords();
      else if (index === 1) this.askClear();
    },
    onCancel() { this.triggerEvent('close'); },

    exportRecords() {
      if (this._exporting) return;
      this._exporting = true;
      const app = getApp();
      const failMsg = '对话记录没导出来，请重试';
      const toast = (title) => cyToast(title);
      app.sendRequest({
        url: '/api/ai/npc/chat/data/export', method: 'GET', hideLoading: true, autoErrorToast: false,
        success: (res) => {
          this._exporting = false;
          const ok = res && (res.code == 200 || res.code == '200');
          const vo = ok ? chatExport.normalizeExport(res.data) : null;
          if (!vo) { toast(ok ? failMsg : app.getRequestErrorMessage(res, failMsg)); return; }
          if (!vo.records.length) { toast('还没有对话记录'); return; }
          wx.setClipboardData({ data: chatExport.formatReadable(vo), fail: () => toast('复制没有成功，请重试') });
        },
        fail: () => { this._exporting = false; toast('网络没连上，请重试'); },
      });
    },

    askClear() {
      const dc = this.selectComponent('#ai-records-dc');
      if (dc) dc.open('ai.npc.chatData.delete', {});
    },

    onConfirmClear() {
      const dc = this.selectComponent('#ai-records-dc');
      if (!dc || this._clearing) return;
      this._clearing = true;
      dc.busyOn();
      const app = getApp();
      const fail = (message) => { this._clearing = false; dc.failed(message); };
      authorWrites.run({ app, storage: wx, bucket: 'npc-delete', operation: 'NPC_CHAT_DELETE',
        endpoint: '/api/ai/npc/chat/data/delete', body: {},
        validResult: data => !!data && typeof data.deleted === 'number' && Number.isSafeInteger(data.deleted) && data.deleted >= 0,
        send: (intent, callbacks) => app.sendRequest({
          ...callbacks,
          url: '/api/ai/npc/chat/data/delete', method: 'POST', data: intent.body,
          hideLoading: true, autoErrorToast: false,
        }),
        onSuccess: (res) => {
          this._clearing = false;
          const deleted = res.data.deleted;
          dc.done(deleted > 0 ? '已清除 ' + deleted + ' 条对话' : '没有需要清除的对话');
        },
        onRejected: res => fail(res.msg || '没清除成功，请重试'),
        onUnknown: fail,
      });
    },
  },
  data: { items: ITEMS },
});
