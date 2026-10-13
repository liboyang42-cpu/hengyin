// cy-npc-chat：普通 POST 的客户端壳。流式观感只来自已审核全文的本地逐字呈现。
//
// ★ 两轨共用本组件,靠 scope 选端点:
//     scope='roam'     → /api/ai/npc/chat          bizId = 漫游会话 id
//     scope='merchant' → /api/ai/npc/merchant-chat bizId = 商家 id
//   共用的是「幂等 requestId + generation 隔离迟到响应 + 只显示已审核全文」这套壳,
//   它与是哪一轨无关;复制一份等于把这三条保证也复制一份,以后只会修好其中一份。
//   ⚠️ 两轨的**开关是分开的**(roamNpcChat 硬编码 false / merchantNpcChat 读配置),
//     由调用方各自判,组件不判 —— 组件判的话两轨就又绑一起了。
const analytics = require('../../../utils/analytics.js');
const npcPending = require('../../../utils/npc-pending-request.js');

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
        else this._restorePending();
      },
    },
    sessionId: { type: Number, value: 0, observer: function () { if (this.properties.show) this._restorePending(); } },
    /** 'roam' | 'merchant'。决定打哪个端点、以及 bizId 缺席时说什么。 */
    scope: { type: String, value: 'roam', observer: function () { if (this.properties.show) this._restorePending(); } },
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
    scrollIntoView: '',
    canRecover: false,
  },

  lifetimes: {
    attached: function () { this._detached = false; if (this.properties.show) this._restorePending(); },
    detached: function () { this._detached = true; this._invalidate(false); },
  },

  pageLifetimes: {
    hide: function () { this._hidden = true; this.cancelPending(); },
    show: function () { this._hidden = false; if (this.properties.show) this._restorePending(); },
  },

  methods: {
    onInput: function (event) {
      const chars = codePoints((event && event.detail && event.detail.value) || '').slice(0, MAX_MESSAGE_CODE_POINTS);
      const input = chars.join('');
      // 允许用户在等待时改问下一句：先 abort 旧 RequestTask，再用 generation 隔离迟到响应。
      this.setData({ input: input, inputCount: chars.length, canSend: !!input.trim() });
    },

    onSend: function () {
      if (this._restorePending(false)) return;
      const message = (this.data.input || '').trim();
      if (!message || this.data.isThinking || this.data.canRecover) return;
      if (!this.properties.sessionId) {
        this._appendAssistant(
          this.properties.scope === 'merchant'
            ? '这家店暂时不能对话。'
            : '先走几步开始这段漫游，再来问我吧。',
          'FAILED'
        );
        return;
      }
      const pending = Object.assign(this._context(), { requestId: makeRequestId() });
      if (!npcPending.save(pending)) {
        this._appendAssistant('无法保存原问题的恢复记录，请检查本机存储后再发送。', 'STORAGE_ERROR');
        return;
      }

      this._invalidate(false);
      const generation = ++this._generation;
      const userMessage = this._message('user', message);
      const messages = this._capMessages((this.data.messages || []).concat(userMessage));
      this.setData({
        messages: messages,
        input: '',
        inputCount: 0,
        canSend: false,
        state: 'GENERATING',
        isThinking: true,
        scrollIntoView: 'npc-msg-' + userMessage.id,
      });
      this._track('npc_chat_submit');

      this._pending = pending;
      this._sendPending(generation, message);
    },

    _owner: function () {
      const app = getApp();
      return String(app.getUserID ? app.getUserID() : '');
    },

    _context: function () {
      return npcPending.context(this._owner(), this.properties.scope, this.properties.sessionId);
    },

    _restorePending: function (query = true) {
      const target = this._context();
      const changed = this._activeContext && !npcPending.sameContext(this._activeContext, target);
      if (changed) {
        this._invalidate(false);
        this._pending = null;
        this.setData({ messages: [], input: '', inputCount: 0, canSend: false, canRecover: false, isThinking: false, state: 'IDLE' });
      }
      this._activeContext = target;
      if (!this.properties.sessionId) return !!changed;
      const stored = npcPending.read(target);
      if (stored.error) {
        this._invalidate(false);
        this._pending = null;
        this.setData({ canRecover: false });
        this._appendAssistant('无法读取原问题的恢复记录，请检查本机存储后再发送。', 'STORAGE_ERROR');
        return true;
      }
      if (!stored.pending) return !!changed;
      if (this._pending && this._pending.requestId === stored.pending.requestId && this.data.isThinking) return true;
      this._pending = stored.pending;
      this.setData({ canRecover: true, isThinking: false, state: 'UNKNOWN' });
      if (query) this.onRecover();
      return true;
    },

    onRecover: function () {
      const pending = this._pending;
      if (!pending || !this.data.canRecover || this.data.isThinking) return;
      if (!npcPending.sameContext(pending, this._context())) { this._restorePending(); return; }
      this._invalidate(false);
      this.setData({ isThinking: true, state: 'CHECKING' });
      this._queryPending(++this._generation);
    },

    _queryPending: function (generation) {
      const pending = this._pending;
      const control = getApp().sendRequest({
        url: '/api/ai/npc/request-status', method: 'GET',
        data: { requestId: pending.requestId, scope: pending.scope, bizId: pending.bizId },
        silentError: true,
        success: res => this._onResponse(generation, pending.requestId, res, true),
        fail: () => this._onNetworkFailure(generation),
        successStatusAbnormal: () => this._onNetworkFailure(generation),
      });
      if (this._isCurrent(generation)) this._requestControl = control;
    },

    _sendPending: function (generation, message) {
      const pending = this._pending;
      const requestId = pending.requestId;
      const app = getApp();
      const control = app.sendRequest({
        // ★ 两个字面量直接写在这里,不抽 helper:UI-GATE 的 U1 要求请求路径可枚举,
        //   helper 返回的字符串它核对不了,会判成「运行时变量路径」。
        url: this.properties.scope === 'merchant'
          ? '/api/ai/npc/merchant-chat'
          : '/api/ai/npc/chat',
        method: 'POST',
        data: { requestId: requestId, bizId: this.properties.sessionId, message },
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

    onSheetClose: function () {
      this.cancelPending();
      this.triggerEvent('close');
    },

    /** 页面 onHide/onUnload/切场景时调用的公开生命周期方法。 */
    cancelPending: function () {
      const unknown = !!this._pending && (this.data.isThinking || this.data.canRecover);
      this._invalidate(true);
      if (unknown) this.setData({ canRecover: true });
    },

    _onResponse: function (generation, requestId, res, readback) {
      if (!this._isCurrent(generation) || !this._pending || this._pending.owner !== this._owner()) return;
      this._requestControl = null;
      const payload = res && res.data;
      if (res && ['401', '403'].includes(String(res.code))) {
        if (readback) { this._onNetworkFailure(generation); return; }
        npcPending.clear(this._pending);
        this._pending = null;
        this.setData({ canRecover: false });
        this._appendAssistant(res.msg || '当前暂不能对话。', 'BLOCKED');
        return;
      }
      // requestId 不匹配按迟到/串线处理，绝不把另一条请求的文本写进当前对话。
      if (!payload || payload.requestId !== requestId) {
        this._onNetworkFailure(generation);
        return;
      }
      const terminal = String(res.code) === '200' && npcPending.isTerminal(payload);
      if (!terminal) { this._onNetworkFailure(generation); return; }
      this.setData({ canRecover: !terminal });
      if (terminal) { npcPending.clear(this._pending); this._pending = null; }
      this._trackComplete(payload);
      if (payload.outcomeStatus === 'SUCCEEDED' && payload.safetyDecision === 'PASS') {
        this._startTypewriter(generation, payload.safeText || '', payload.audioUrl);
        return;
      }
      this._appendAssistant(payload.safeText || '这次暂时无法回答，请稍后再试。', this._stateOf(payload));
    },

    _onNetworkFailure: function (generation) {
      if (!this._isCurrent(generation)) return;
      this._requestControl = null;
      this.setData({ canRecover: true });
      this._appendAssistant('回答结果待确认，请恢复原问题，不要重复发送。', 'UNKNOWN');
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
        isThinking: false,
        canSend: !!(this.data.input || '').trim(),
        scrollIntoView: 'npc-msg-' + assistant.id,
      });
    },

    _stateOf: function (payload) {
      if (payload.errorCode === 'RATE_LIMITED') return 'RATE_LIMITED';
      if (payload.errorCode === 'INPUT_BLOCK' || payload.errorCode === 'OUTPUT_BLOCK') return 'BLOCKED';
      if (payload.errorCode === 'REQUEST_IN_PROGRESS' || payload.outcomeStatus === 'PROCESSING') return 'CHECKING';
      return 'FAILED';
    },

    _invalidate: function (showCancelled) {
      const wasActive = this.data.isThinking || this.data.state === 'DELIVERING';
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
      const unknown = !!this._pending && (this.data.isThinking || this.data.canRecover);
      this._invalidate(false);
      if (unknown) {
        this.setData({ canRecover: true, state: 'UNKNOWN', isThinking: false });
        return;
      }
      this._pending = null;
      this.setData({
        canRecover: false, messages: [], input: '', inputCount: 0, state: 'IDLE', isThinking: false,
        canSend: false, scrollIntoView: '',
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
      return !this._detached && !this._hidden && this.properties.show && generation === this._generation
        && npcPending.sameContext(this._activeContext, this._context())
        && (!this._pending || npcPending.sameContext(this._pending, this._context()));
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
