// cy-npc-chat：普通 POST 的客户端壳。流式观感只来自已审核全文的本地逐字呈现。
//
// ★ 两轨共用本组件,靠 scope 选端点:
//     scope='roam'     → /api/ai/npc/chat          bizId = 漫游会话 id
//     scope='merchant' → /api/ai/npc/merchant-chat bizId = 商家 id
//   共用的是「幂等 requestId + generation 隔离迟到响应 + 只显示已审核全文」这套壳,
//   它与是哪一轨无关;复制一份等于把这三条保证也复制一份,以后只会修好其中一份。
//   ⚠️ 两轨的**开关是分开的**(roamNpcChat 硬编码 false / merchantNpcChat 读配置),
//     由调用方各自判,组件不判 —— 组件判的话两轨就又绑一起了。
const analytics = require('../../../../utils/analytics.js');
const chatExport = require('./chat-export.js');

const MAX_MESSAGE_CODE_POINTS = 300;
const MAX_VISIBLE_MESSAGES = 20;
const TYPE_INTERVAL_MS = 24;

function codePoints(text) {
  return Array.from(text || '');
}

function makeRequestId() {
  // 与服务端的 UUID 契约一致；requestId 只做幂等键，不承载用户或业务信息。
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (char) {
    const random = Math.floor(Math.random() * 16);
    const value = char === 'x' ? random : ((random & 0x3) | 0x8);
    return value.toString(16);
  });
}

Component({
  properties: {
    show: {
      type: Boolean,
      value: false,
      observer: function (visible) {
        if (!visible) this._resetForClose();
        else this._recoverPending();
      },
    },
    sessionId: { type: Number, value: 0 },
    /** 'roam' | 'merchant'。决定打哪个端点、以及 bizId 缺席时说什么。 */
    scope: { type: String, value: 'roam' },
    /** scope='merchant' 时的标题(形象名)与空态招呼语,由店铺主页传入。 */
    npcName: { type: String, value: '' },
    greeting: { type: String, value: '' },
  },

  data: {
    messages: [],
    input: '',
    inputCount: 0,
    state: 'IDLE',
    isThinking: false,
    canSend: false,
    retryAvailable: false,
    scrollIntoView: '',
    clearError: '',
    // 「导出我的对话记录」:'' 关闭 | loading | ready | empty | error。导出只在内存里,不落本地存储。
    exportState: '',
    exportError: '',
    exportCount: 0,
    exportPolicy: '',
    exportPreview: [],
    exportCopied: '',
  },

  lifetimes: {
    detached: function () { this.cancelPending(); },
  },

  methods: {
    onInput: function (event) {
      const chars = codePoints((event && event.detail && event.detail.value) || '').slice(0, MAX_MESSAGE_CODE_POINTS);
      const input = chars.join('');
      // 允许用户在等待时改问下一句：先 abort 旧 RequestTask，再用 generation 隔离迟到响应。
      this.setData({ input: input, inputCount: chars.length, canSend: !!input.trim() });
    },

    onSend: function () {
      const message = (this.data.input || '').trim();
      if (!message) return;
      if (!this.properties.sessionId) {
        this._appendAssistant(
          this.properties.scope === 'merchant'
            ? '这家店暂时不能对话。'
            : '先走几步开始这段漫游，再来问我吧。',
          'FAILED'
        );
        return;
      }

      this._invalidate(false);
      const generation = ++this._generation;
      const retry = this._retryRequested && this._retryMessage
        && this._retryMessage.bizId === this.properties.sessionId && this._retryMessage.scope === this.properties.scope;
      this._retryRequested = false;
      // 新消息重新计自动回查次数;同一条消息的重试沿用已用次数(F-R2S-15)。
      if (!retry) this._retryCount = 0;
      const requestId = retry ? this._retryMessage.requestId : makeRequestId();
      const userMessage = retry ? this._retryMessage.userMessage : this._message('user', message);
      const messages = retry ? this.data.messages : this._capMessages((this.data.messages || []).concat(userMessage));
      this._retryMessage = { requestId, message, userMessage, bizId: this.properties.sessionId, scope: this.properties.scope };
      // 结果未确认前一直留着:关掉再打开时按 requestId 只读回读,不重发(W10b #27)。
      this._pendingRecovery = this._retryMessage;
      this._retryMode = 'send';
      this.setData({
        messages: messages,
        input: '',
        inputCount: 0,
        canSend: false,
        state: 'GENERATING',
        retryAvailable: false,
        isThinking: true,
        scrollIntoView: 'npc-msg-' + userMessage.id,
      });
      this._track('npc_chat_submit');

      const app = getApp();
      const control = app.sendRequest({
        // ★ 两个字面量直接写在这里,不抽 helper:UI-GATE 的 U1 要求请求路径可枚举,
        //   helper 返回的字符串它核对不了,会判成「运行时变量路径」。
        url: this.properties.scope === 'merchant'
          ? '/api/ai/npc/merchant-chat'
          : '/api/ai/npc/chat',
        method: 'POST',
        data: { requestId: requestId, bizId: this.properties.sessionId, message: message },
        header: { 'content-type': 'application/json' },
        timeout: 30000,
        silentError: true,
        success: (res) => this._onResponse(generation, requestId, res),
        fail: () => this._onNetworkFailure(generation),
        successStatusAbnormal: () => this._onNetworkFailure(generation),
      });
      // request-client 的控制器覆盖静默重登后的 RequestTask；不直接持有一次 wx.request 的裸 task。
      if (this._isCurrent(generation)) this._requestControl = control;
    },

    onRetrySend: function () {
      if (!this._retryMessage || !this.data.retryAvailable) return;
      // 回读恢复出来的待确认问题:「查询处理结果」只再读一次原请求,绝不重新提交。
      if (this._retryMode === 'readback') { this._recoverPending(); return; }
      const draft = this.data.input;
      this.setData({ input: this._retryMessage.message });
      this._retryRequested = true;
      this.onSend();
      if (draft) this.setData({ input: draft });
    },

    /** 「清除我的对话记录」:先弹危险确认(见 utils/danger-actions.js 'ai.npc.chatData.delete')。 */
    onAskClearData: function () {
      const dc = this.selectComponent && this.selectComponent('#npc-dc');
      if (dc) dc.open('ai.npc.chatData.delete', {});
    },

    /** 「导出我的对话记录」:拉服务端全量记录,给预览 + 复制文本(用户决定:导出 = 复制文本);不写任何本地存储。 */
    onExportData: function () {
      if (this._clearing || this.data.exportState === 'loading') return;
      // 不打断在途提问:导出是只读快照,只含已落库的记录;回答照常在后台逐字呈现,返回对话即可看到
      const seq = (this._exportSeq || 0) + 1;
      this._exportSeq = seq;
      this._exportReadable = '';
      this.setData({ exportState: 'loading', exportError: '', exportCopied: '', exportPreview: [], exportCount: 0, exportPolicy: '' });
      const failMsg = '对话记录没导出来，请重试';
      const current = () => this.properties.show && seq === this._exportSeq;
      getApp().sendRequest({
        url: '/api/ai/npc/chat/data/export',
        method: 'GET',
        hideLoading: true,
        autoErrorToast: false,
        success: (res) => {
          if (!current()) return;
          const vo = res && (res.code == 200 || res.code == '200') ? chatExport.normalizeExport(res.data) : null;
          if (!vo) {
            const msg = res && res.code != 200 && res.code != '200'
              ? getApp().getRequestErrorMessage(res, failMsg) : failMsg;
            this.setData({ exportState: 'error', exportError: msg });
            return;
          }
          this._exportReadable = chatExport.formatReadable(vo);
          this.setData({
            exportState: vo.records.length ? 'ready' : 'empty',
            exportCount: vo.records.length,
            exportPolicy: vo.retentionPolicy,
            exportPreview: chatExport.previewRows(vo),
          });
        },
        fail: () => {
          if (!current()) return;
          this.setData({ exportState: 'error', exportError: '网络没连上，对话记录没导出来，请重试' });
        },
      });
    },

    onCopyExport: function () {
      const data = this._exportReadable;
      if (!data || this.data.exportState !== 'ready') return;
      wx.setClipboardData({
        data: data,
        success: () => this.setData({ exportCopied: '已复制全部对话文本，可以粘贴到备忘录或发给自己' }),
        fail: () => this.setData({ exportCopied: '', exportState: 'error', exportError: '复制没有成功，可以再试一次' }),
      });
    },

    onCloseExport: function () {
      this._exportSeq = (this._exportSeq || 0) + 1;
      this._exportReadable = '';
      this.setData({ exportState: '', exportError: '', exportCopied: '', exportPreview: [], exportCount: 0, exportPolicy: '' });
    },

    onConfirmClearData: function () {
      const dc = this.selectComponent && this.selectComponent('#npc-dc');
      if (!dc || this._clearing) return;
      this._clearing = true;
      // 删除期间不能再有在途提问:迟到的回答会在服务端重新写出一条刚删掉的记录
      this._invalidate(false);
      this.setData({ clearError: '' });
      dc.busyOn();
      const failMsg = '清除没有成功，对话记录还在，请重试';
      getApp().sendRequest({
        url: '/api/ai/npc/chat/data/delete',
        method: 'POST',
        data: {},
        hideLoading: true,
        silentError: true,
        success: (res) => {
          if (!res || (res.code != 200 && res.code != '200')) { this._clearing = false; this.setData({ clearError: failMsg }); dc.failed(failMsg); return; }
          this._clearing = false;
          // 本地列表只是服务端记录的镜像,服务端删成功后同步清空
          this.setData({ messages: [], state: 'IDLE', isThinking: false, scrollIntoView: '' });
          this.onCloseExport();
          const deleted = res.data && Number(res.data.deleted);
          dc.done(Number.isFinite(deleted) && deleted > 0 ? '已删除 ' + deleted + ' 条对话记录，当前对话也已清空。' : '');
        },
        fail: () => { this._clearing = false; this.setData({ clearError: '清除没有成功，对话记录还在，请重试' }); dc.failed(failMsg) },
        successStatusAbnormal: () => { this._clearing = false; this.setData({ clearError: failMsg }); dc.failed(failMsg) },
      });
    },

    onSheetClose: function () {
      this.cancelPending();
      this.triggerEvent('close');
    },

    /** 页面 onHide/onUnload/切场景时调用的公开生命周期方法。 */
    cancelPending: function () {
      this._invalidate(true);
    },

    _onResponse: function (generation, requestId, res) {
      if (!this._isCurrent(generation)) return;
      this._requestControl = null;
      const payload = res && res.data;
      // requestId 不匹配按迟到/串线处理，绝不把另一条请求的文本写进当前对话。
      if (!payload || payload.requestId !== requestId) {
        this._onNetworkFailure(generation);
        return;
      }
      if (res.code != 200 && res.code != '200') {
        this._trackComplete(payload);
        this._pendingRecovery = null;
        this._appendAssistant(payload.safeText || '这次没有收到有效回答，请稍后再试。', 'FAILED');
        return;
      }
      this._trackComplete(payload);
      if (payload.outcomeStatus === 'SUCCEEDED' && payload.safetyDecision === 'PASS') {
        this._retryMessage = null;
        this._pendingRecovery = null;
        this._retryCount = 0;
        this._startTypewriter(generation, payload.safeText || '', payload.audioUrl);
        return;
      }
      const state = this._stateOf(payload);
      if (state === 'CHECKING' && payload.asrReason) this._retryMode = 'readback';
      if (state !== 'CHECKING') this._pendingRecovery = null;
      this._appendAssistant(payload.safeText || '这次暂时无法回答，请稍后再试。', state);
      if (state === 'CHECKING' && (this._retryCount || 0) < 3) {
        this._retryCount = (this._retryCount || 0) + 1;
        this._retryTimer = setTimeout(() => { if (this._isCurrent(generation)) this.onRetrySend(); }, this._retryCount * 2000);
      }
    },

    /**
     * 关闭/重开后恢复上一条未确认的问题:GET /api/ai/npc/chat/request 只读回读原请求(W10b #27)。
     * 服务端不进模型、不扣额度;只有回读明确说「没有这条」时,才把「重新发送」交还给用户。
     */
    _recoverPending: function () {
      const pending = this._pendingRecovery;
      if (!pending || pending.bizId !== this.properties.sessionId || pending.scope !== this.properties.scope) return;
      this._invalidate(false);
      const generation = ++this._generation;
      this._retryMessage = pending;
      this._retryMode = 'readback';
      const messages = (this.data.messages || []).some((item) => item.id === pending.userMessage.id)
        ? this.data.messages : this._capMessages((this.data.messages || []).concat(pending.userMessage));
      this.setData({
        messages: messages, state: 'CHECKING', retryAvailable: false, isThinking: true,
        scrollIntoView: 'npc-msg-' + pending.userMessage.id,
      });
      const control = getApp().sendRequest({
        url: '/api/ai/npc/chat/request',
        method: 'GET',
        data: { requestId: pending.requestId, scope: pending.scope, bizId: pending.bizId },
        hideLoading: true,
        autoErrorToast: false,
        success: (res) => this._onReadback(generation, pending, res),
        fail: () => this._onReadbackUnavailable(generation),
        successStatusAbnormal: () => this._onReadbackUnavailable(generation),
      });
      if (this._isCurrent(generation)) this._requestControl = control;
    },

    _onReadback: function (generation, pending, res) {
      if (!this._isCurrent(generation)) return;
      this._requestControl = null;
      const payload = res && res.data;
      if (!res || (res.code != 200 && res.code != '200') || !payload || payload.requestId !== pending.requestId) {
        this._onReadbackUnavailable(generation);
        return;
      }
      if (payload.outcomeStatus === 'SUCCEEDED' && payload.safetyDecision === 'PASS') {
        this._pendingRecovery = null;
        this._retryMessage = null;
        this._startTypewriter(generation, payload.safeText || '', payload.audioUrl);
        return;
      }
      if (payload.outcomeStatus === 'NOT_FOUND') {
        // 服务端没收到这条:此时重新发送才是安全的(同一 requestId,服务端仍幂等)。
        this._pendingRecovery = null;
        this._retryMode = 'send';
        this._appendAssistant(payload.safeText || '上一条问题没有送达，可以再发一次。', 'FAILED');
        return;
      }
      const state = this._stateOf(payload);
      if (state !== 'CHECKING') {
        this._pendingRecovery = null;
        this._retryMessage = null;
      }
      this._appendAssistant(payload.safeText || '上一条问题的结果还没确认，稍后再查看。', state);
    },

    _onReadbackUnavailable: function (generation) {
      if (!this._isCurrent(generation)) return;
      this._requestControl = null;
      // 读不到 ≠ 没送达:保留待确认状态,只提供「查询处理结果」,不提供重发。
      this._appendAssistant('暂时查不到上一条问题的结果，稍后再查看。', 'CHECKING');
    },

    _onNetworkFailure: function (generation) {
      if (!this._isCurrent(generation)) return;
      this._requestControl = null;
      this._appendAssistant('网络异常，这条问题还没有送达，可以再试一次。', 'FAILED');
    },

    _startTypewriter: function (generation, text, audioUrl) {
      this._playVoice(audioUrl);
      const assistant = this._message('assistant', '');
      const chars = codePoints(text);
      const messages = this._capMessages((this.data.messages || []).concat(assistant));
      this.setData({ messages: messages, state: 'DELIVERING', isThinking: false, scrollIntoView: 'npc-msg-' + assistant.id });
      let cursor = 0;
      const tick = () => {
        if (!this._isCurrent(generation)) return;
        cursor = Math.min(chars.length, cursor + 2);
        const next = (this.data.messages || []).map((item) =>
          item.id === assistant.id ? Object.assign({}, item, { text: chars.slice(0, cursor).join('') }) : item
        );
        this.setData({ messages: next, scrollIntoView: 'npc-msg-' + assistant.id });
        if (cursor < chars.length) {
          this._typeTimer = setTimeout(tick, TYPE_INTERVAL_MS);
        } else if (this._isCurrent(generation)) {
          this._typeTimer = null;
          this.setData({ state: 'DONE', isThinking: false, canSend: !!(this.data.input || '').trim() });
        }
      };
      tick();
    },

    _appendAssistant: function (text, state) {
      const assistant = this._message('assistant', text);
      const messages = this._capMessages((this.data.messages || []).concat(assistant));
      this.setData({
        messages: messages,
        state: state || 'FAILED',
        retryAvailable: !!this._retryMessage && (state === 'FAILED' || state === 'CHECKING'),
        isThinking: false,
        canSend: !!(this.data.input || '').trim(),
        scrollIntoView: 'npc-msg-' + assistant.id,
      });
    },

    _stateOf: function (payload) {
      if (payload.errorCode === 'RATE_LIMITED') return 'RATE_LIMITED';
      if (payload.errorCode === 'INPUT_BLOCK' || payload.errorCode === 'OUTPUT_BLOCK') return 'BLOCKED';
      if (payload.errorCode === 'REQUEST_IN_PROGRESS' || payload.errorCode === 'REQUEST_OUTCOME_UNKNOWN'
        || payload.outcomeStatus === 'PROCESSING') return 'CHECKING';
      return 'FAILED';
    },

    _invalidate: function (showCancelled) {
      const wasActive = this.data.isThinking || this.data.state === 'DELIVERING';
      if (this._retryTimer) { clearTimeout(this._retryTimer); this._retryTimer = null; }
      this._generation = (this._generation || 0) + 1;
      if (this._typeTimer) {
        clearTimeout(this._typeTimer);
        this._typeTimer = null;
      }
      if (this._requestControl && typeof this._requestControl.abort === 'function') this._requestControl.abort();
      this._requestControl = null;
      this._stopVoice();
      if (showCancelled && wasActive) {
        this._track('npc_chat_cancel');
        this.setData({ state: 'CANCELLED', isThinking: false, canSend: !!(this.data.input || '').trim() });
      }
    },

    _resetForClose: function () {
      this._invalidate(false);
      // _pendingRecovery 刻意保留:重开时按 requestId 回读,而不是让用户再发一遍。
      this._retryMessage = null;
      this._retryCount = 0;
      this.onCloseExport();
      this.setData({
        messages: [], input: '', inputCount: 0, state: 'IDLE', isThinking: false,
        canSend: false, retryAvailable: false, scrollIntoView: '',
      });
    },

    _playVoice: function (audioUrl) {
      if (this.properties.scope !== 'merchant' || !audioUrl) return;
      this._stopVoice();
      const audio = wx.createInnerAudioContext();
      this._voiceAudio = audio;
      audio.src = audioUrl;
      audio.onEnded(() => this._stopVoice());
      audio.onError(() => this._stopVoice());
      audio.play();
    },

    _stopVoice: function () {
      if (!this._voiceAudio) return;
      try { this._voiceAudio.stop(); this._voiceAudio.destroy(); } catch (e) {}
      this._voiceAudio = null;
    },

    _isCurrent: function (generation) {
      return this.properties.show && generation === this._generation;
    },

    _message: function (role, text) {
      this._messageSeq = (this._messageSeq || 0) + 1;
      return { id: Date.now() + '-' + this._messageSeq, role: role, text: text };
    },

    _capMessages: function (messages) {
      return messages.slice(-MAX_VISIBLE_MESSAGES);
    },

    _trackComplete: function (payload) {
      this._track('npc_chat_complete', {
        outcomeStatus: (payload && payload.outcomeStatus) || 'FAILED',
        safetyDecision: (payload && payload.safetyDecision) || 'NOT_RUN',
      });
    },

    _track: function (eventName, extra) {
      analytics.track(eventName, Object.assign({
        bizType: 'roam',
        bizId: Number(this.properties.sessionId) || 0,
      }, extra || {}));
    },
  },
});
