// 主题状态键管理(创作者):GET/POST/DELETE /api/topic/state-keys。
// 只在编辑已有主题(有 topicId)时可用;SELLING 起后端冻结注册表,写操作会被后端拒绝并回原因。
// 被节点/剧情配置引用的键后端拒删并列出引用位置,这里原样展示。
const app = getApp()
const { isBizOk } = require('../../../../utils/response-shape.js')
const { PREFIXES, normalizeKeys, buildKey } = require('./rules.js')

Component({
  options: { styleIsolation: 'apply-shared' },
  properties: {
    topicId: { type: String, value: '' },
    scope: { type: String, value: '' }, // '' | 'MERCHANT'(商家代管)
    readOnly: { type: Boolean, value: false },
  },
  data: {
    state: 'loading', // loading | ready | empty | error
    keys: [],
    prefixes: PREFIXES,
    adding: false,
    prefixIndex: 0,
    suffix: '',
    label: '',
    initialValue: '',
    formError: '',
    saving: false,
    deleting: '',
    deleteError: '',
    notice: '',
  },
  observers: {
    'topicId, scope, readOnly'() { if (this._attached) this.load() },
  },
  lifetimes: {
    attached() { this._attached = true; this.load() },
    detached() {
      this._attached = false
      this._contextEpoch = (this._contextEpoch || 0) + 1
      this._seq = (this._seq || 0) + 1
      this._saveOperation = this._deleteOperation = this._deleteConfirmation = null
    },
  },
  pageLifetimes: {
    show() { if (this._attached && !this._isCurrentContext(this._context)) this.load() },
  },
  methods: {
    _captureContext() {
      return { topicId: this.properties.topicId, scope: this.properties.scope, readOnly: this.properties.readOnly,
        member: String(typeof app.getUserID === 'function' ? app.getUserID() || '' : ''),
        authorization: typeof app.getAuthorization === 'function' ? app.getAuthorization() : undefined,
        epoch: this._contextEpoch || 0 }
    },
    _isCurrentContext(context) {
      if (!this._attached || !context) return false
      const current = this._captureContext()
      return Object.keys(current).every(key => current[key] === context[key])
    },
    _callbackCurrent(context) {
      // Callers first check their request/operation identity. A superseded callback
      // must never reset another operation, even if the session has since renewed.
      if (!this._attached || this._context !== context) return false
      if (this._isCurrentContext(context)) return true
      const current = this._captureContext()
      if (Object.keys(current).every(key => key === 'authorization' || current[key] === context[key])) {
        // Same-member silent renewal can finish while this page stays visible.
        // Release the old view/dialog and read afresh; do not apply or replay its write.
        this._syncContext()
        this.load()
      }
      return false
    },
    _syncContext() {
      if (!this._attached) return null
      if (!this._isCurrentContext(this._context)) {
        this._contextEpoch = (this._contextEpoch || 0) + 1
        this._seq = (this._seq || 0) + 1
        this._saveOperation = this._deleteOperation = this._deleteConfirmation = null
        this._context = this._captureContext()
        const dc = this.selectComponent && this.selectComponent('#sk-dc')
        if (dc) dc.close()
        // Reset only the local view. Previously sent writes retain their server outcome.
        this.setData({ keys: [], state: this.properties.topicId ? 'loading' : 'empty',
          adding: false, prefixIndex: 0, suffix: '', label: '', initialValue: '',
          formError: '', saving: false, deleting: '', deleteError: '', notice: '' })
      }
      return this._context
    },
    _canWrite(context) { return this._isCurrentContext(context) && !!context.topicId && !context.readOnly },
    _qs() {
      const scope = this.properties.scope === 'MERCHANT' ? '&scope=MERCHANT' : ''
      return `topicId=${encodeURIComponent(this.properties.topicId)}${scope}`
    },
    load() {
      const context = this._syncContext()
      if (!context || !context.topicId) return
      const seq = (this._seq = (this._seq || 0) + 1)
      const current = () => seq === this._seq && this._callbackCurrent(context)
      this.setData({ state: this.data.keys.length ? 'ready' : 'loading', notice: '' })
      app.sendRequest({
        url: '/api/topic/state-keys', method: 'GET', hideLoading: true, silentError: true,
        data: { topicId: context.topicId, scope: context.scope },
        success: (res) => {
          if (!current()) return
          const keys = isBizOk(res) ? normalizeKeys(res.data) : null
          if (!keys) { this.setData({ state: 'error' }); return }
          this.setData({ keys, state: keys.length ? 'ready' : 'empty' })
        },
        fail: () => { if (current()) this.setData({ state: 'error' }) },
        successStatusAbnormal: () => { if (current()) this.setData({ state: 'error' }) },
      })
    },
    onAddOpen() { if (!this._canWrite(this._syncContext())) return; this.setData({ adding: true, formError: '', suffix: '', label: '', initialValue: '' }) },
    onAddCancel() { if (!this.data.saving) this.setData({ adding: false, formError: '' }) },
    onPrefix(e) { this.setData({ prefixIndex: Number(e.currentTarget.dataset.index) || 0, formError: '' }) },
    onInput(e) {
      const value = e.detail.value
      const field = e.currentTarget.dataset.field
      if (field === 'suffix') this.setData({ suffix: value, formError: '' })
      else if (field === 'label') this.setData({ label: value, formError: '' })
      else if (field === 'initialValue') this.setData({ initialValue: value, formError: '' })
    },
    onSave() {
      const context = this._syncContext()
      if (!this._canWrite(context) || this.data.saving) return
      const built = buildKey(this.data.prefixIndex, this.data.suffix, this.data.label, this.data.initialValue)
      if (!built.ok) { this.setData({ formError: built.error }); return }
      if (this.data.keys.some((k) => k.stateKey === built.key.stateKey)) {
        this.setData({ formError: '这个键已经注册过了' })
        return
      }
      const operation = this._saveOperation = {}
      const current = () => this._saveOperation === operation && this._callbackCurrent(context)
      this.setData({ saving: true, formError: '' })
      app.sendRequest({
        url: '/api/topic/state-keys', method: 'POST', hideLoading: true, silentError: true,
        data: JSON.stringify({ topicId: Number(context.topicId), scope: context.scope, keys: [built.key] }),
        header: { 'Content-Type': 'application/json' },
        success: (res) => {
          if (!current()) return
          this._saveOperation = null
          if (!isBizOk(res)) { this.setData({ saving: false, formError: (res && res.msg) || '注册失败，请稍后再试' }); return }
          this.setData({ saving: false, adding: false, notice: '已注册，发布前请确认节点引用都已配置' })
          this.load()
        },
        fail: () => { if (!current()) return; this._saveOperation = null; this.setData({ saving: false, formError: '网络异常，没有注册成功，可以再试一次' }) },
        successStatusAbnormal: () => { if (!current()) return; this._saveOperation = null; this.setData({ saving: false, formError: '网络异常，没有注册成功，可以再试一次' }) },
      })
    },
    onAskDelete(e) {
      const context = this._syncContext()
      if (!this._canWrite(context) || this.data.deleting) return
      const key = e.currentTarget.dataset.key
      const dc = this.selectComponent && this.selectComponent('#sk-dc')
      if (dc && key) {
        this._deleteConfirmation = { context, key }
        dc.open('topic.stateKey.delete', { name: key, key, contextEpoch: context.epoch })
      }
    },
    onConfirmDelete(e) {
      const context = this._syncContext()
      const dc = this.selectComponent && this.selectComponent('#sk-dc')
      const key = e.detail && e.detail.params && e.detail.params.key
      const confirmation = this._deleteConfirmation
      if (!dc || !key || !this._canWrite(context) || this.data.deleting
          || !confirmation || confirmation.key !== key || !this._isCurrentContext(confirmation.context)
          || e.detail.params.contextEpoch !== context.epoch) return
      const operation = this._deleteOperation = {}
      const current = () => this._deleteOperation === operation && this._callbackCurrent(context)
      this.setData({ deleting: key, deleteError: '' })
      dc.busyOn()
      const fail = (text) => {
        if (!current()) return
        this._deleteOperation = null
        this.setData({ deleting: '', deleteError: text }); dc.failed(text) }
      app.sendRequest({
        // 参数放 query:DELETE 的 body 后端 @RequestParam 读不到
        url: `/api/topic/state-keys?${this._qs()}&key=${encodeURIComponent(key)}`,
        method: 'DELETE', hideLoading: true, silentError: true,
        success: (res) => {
          if (!current()) return
          if (!isBizOk(res)) { fail((res && res.msg) || '删除失败，请稍后再试'); return }
          this._deleteOperation = this._deleteConfirmation = null
          this.setData({ deleting: '' })
          dc.done()
          this.load()
        },
        fail: () => fail('网络异常，没有删除成功，可以再试一次'),
        successStatusAbnormal: () => fail('网络异常，没有删除成功，可以再试一次'),
      })
    },
  },
})
