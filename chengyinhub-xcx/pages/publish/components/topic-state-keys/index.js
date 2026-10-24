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
    topicId(id) { if (id) this.load() },
  },
  methods: {
    _qs() {
      const scope = this.properties.scope === 'MERCHANT' ? '&scope=MERCHANT' : ''
      return `topicId=${encodeURIComponent(this.properties.topicId)}${scope}`
    },
    load() {
      if (!this.properties.topicId) return
      const seq = (this._seq = (this._seq || 0) + 1)
      this.setData({ state: this.data.keys.length ? 'ready' : 'loading', notice: '' })
      app.sendRequest({
        url: '/api/topic/state-keys', method: 'GET', hideLoading: true, silentError: true,
        data: { topicId: this.properties.topicId, scope: this.properties.scope },
        success: (res) => {
          if (seq !== this._seq) return
          const keys = isBizOk(res) ? normalizeKeys(res.data) : null
          if (!keys) { this.setData({ state: 'error' }); return }
          this.setData({ keys, state: keys.length ? 'ready' : 'empty' })
        },
        fail: () => { if (seq === this._seq) this.setData({ state: 'error' }) },
        successStatusAbnormal: () => { if (seq === this._seq) this.setData({ state: 'error' }) },
      })
    },
    onAddOpen() { this.setData({ adding: true, formError: '', suffix: '', label: '', initialValue: '' }) },
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
      if (this.data.saving) return
      const built = buildKey(this.data.prefixIndex, this.data.suffix, this.data.label, this.data.initialValue)
      if (!built.ok) { this.setData({ formError: built.error }); return }
      if (this.data.keys.some((k) => k.stateKey === built.key.stateKey)) {
        this.setData({ formError: '这个键已经注册过了' })
        return
      }
      this.setData({ saving: true, formError: '' })
      app.sendRequest({
        url: '/api/topic/state-keys', method: 'POST', hideLoading: true, silentError: true,
        data: JSON.stringify({ topicId: Number(this.properties.topicId), scope: this.properties.scope, keys: [built.key] }),
        header: { 'Content-Type': 'application/json' },
        success: (res) => {
          if (!isBizOk(res)) { this.setData({ saving: false, formError: (res && res.msg) || '注册失败，请稍后再试' }); return }
          this.setData({ saving: false, adding: false, notice: '已注册，发布前请确认节点引用都已配置' })
          this.load()
        },
        fail: () => { this.setData({ saving: false, formError: '网络异常，没有注册成功，可以再试一次' }) },
        successStatusAbnormal: () => { this.setData({ saving: false, formError: '网络异常，没有注册成功，可以再试一次' }) },
      })
    },
    onAskDelete(e) {
      if (this.data.deleting) return
      const key = e.currentTarget.dataset.key
      const dc = this.selectComponent && this.selectComponent('#sk-dc')
      if (dc && key) dc.open('topic.stateKey.delete', { name: key, key })
    },
    onConfirmDelete(e) {
      const dc = this.selectComponent && this.selectComponent('#sk-dc')
      const key = e.detail && e.detail.params && e.detail.params.key
      if (!dc || !key || this.data.deleting) return
      this.setData({ deleting: key, deleteError: '' })
      dc.busyOn()
      const fail = (text) => { this.setData({ deleting: '', deleteError: text }); dc.failed(text) }
      app.sendRequest({
        // 参数放 query:DELETE 的 body 后端 @RequestParam 读不到
        url: `/api/topic/state-keys?${this._qs()}&key=${encodeURIComponent(key)}`,
        method: 'DELETE', hideLoading: true, silentError: true,
        success: (res) => {
          if (!isBizOk(res)) { fail((res && res.msg) || '删除失败，请稍后再试'); return }
          this.setData({ deleting: '' })
          dc.done()
          this.load()
        },
        fail: () => { this.setData({ deleting: '', deleteError: '网络异常，没有删除成功，可以再试一次' }); dc.failed('网络异常，没有删除成功，可以再试一次') },
        successStatusAbnormal: () => fail('网络异常，没有删除成功，可以再试一次'),
      })
    },
  },
})
