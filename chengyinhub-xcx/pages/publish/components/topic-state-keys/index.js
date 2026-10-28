// 线索与物品(创作者):主题详情里一行,点开叠一张半屏,线索 / 物品 / 标签三组只显示中文名字。
// 线索、标签走 GET/POST/PUT/DELETE /api/topic/state-keys,键名由系统生成、不显示;
// 物品在主题物品表里,由页面经 items 传入,改动经 itemchange 事件交回页面。
// 只在编辑已有主题(有 topicId)时可用;SELLING 起后端冻结注册表,写操作会被后端拒绝。
// 被节点/剧情配置引用的项后端拒删并列出引用位置,这里只显示位置,不显示键名。
const app = getApp()
const { isBizOk } = require('../../../../utils/response-shape.js')
const modal = require('../../../../utils/modal.js')
const { KINDS, normalizeKeys, displayName, nameError, newKey, parseUsedIn } = require('./rules.js')

const SAVE_FAILED = '没有保存成功，请再试一次。'

function showBlocked(name, usedIn) {
  modal.show({ title: `不能删除「${name}」`, content: usedIn.length ? `用在${usedIn.join('、')}。` : '', showCancel: false, confirmText: '好' })
}

Component({
  options: { styleIsolation: 'apply-shared' },
  properties: {
    topicId: { type: String, value: '' },
    scope: { type: String, value: '' }, // '' | 'MERCHANT'(商家代管)
    readOnly: { type: Boolean, value: false },
    items: { type: Array, value: [] }, // [{ id, name, usedIn: [] }] 主题物品
  },
  data: {
    state: 'loading', // loading | ready | empty | error
    keys: [],
    clues: [],
    tags: [],
    itemRows: [],
    groups: [],
    errorKind: '',
    total: 0,
    open: false,
    expanded: '', // 'clue.xxx' | 'item:<id>'
    editName: '',
    adding: '', // '' | clue | item | tag
    addName: '',
    formError: '',
    saving: false,
    deleting: '',
    deleteError: '',
    notice: '',
  },
  observers: {
    'topicId, scope, readOnly'() { if (this._attached) this.load() },
    items() { this._view() },
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
        this._raw = {}
        const dc = this.selectComponent && this.selectComponent('#sk-dc')
        if (dc) dc.close()
        // Reset only the local view. Previously sent writes retain their server outcome.
        this.setData({ keys: [], state: this.properties.topicId ? 'loading' : 'empty',
          expanded: '', editName: '', adding: '', addName: '',
          formError: '', saving: false, deleting: '', deleteError: '', notice: '' })
        this._view()
      }
      return this._context
    },
    _canWrite(context) { return this._isCurrentContext(context) && !!context.topicId && !context.readOnly },
    _qs() {
      const scope = this.properties.scope === 'MERCHANT' ? '&scope=MERCHANT' : ''
      return `topicId=${encodeURIComponent(this.properties.topicId)}${scope}`
    },
    /** 三组显示行:只带名字和「用在」,键名只放在 id 里做定位。 */
    _view() {
      const row = k => ({ id: k.stateKey, name: displayName(k), usedIn: k.usedIn || [] })
      const keys = this.data.keys || []
      const clues = keys.filter(k => k.kind === 'clue').map(row)
      const tags = keys.filter(k => k.kind === 'tag').map(row)
      const itemRows = (this.properties.items || []).filter(i => i && i.id != null)
        .map(i => ({ id: 'item:' + i.id, itemId: i.id, name: String(i.name || '').trim() || '物品', usedIn: Array.isArray(i.usedIn) ? i.usedIn : [] }))
      const groups = [{ kind: 'clue', label: '线索', rows: clues }, { kind: 'item', label: '物品', rows: itemRows }, { kind: 'tag', label: '标签', rows: tags }]
      this.setData({ clues, tags, itemRows, groups, total: clues.length + tags.length + itemRows.length })
    },
    _row(id) {
      return this.data.clues.concat(this.data.tags, this.data.itemRows).find(r => r.id === id) || null
    },
    _names(kind) {
      const list = kind === 'clue' ? this.data.clues : kind === 'tag' ? this.data.tags : this.data.itemRows
      return list.map(r => r.name)
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
          if (!keys) { this.setData({ state: 'error' }); this._view(); return }
          // 已知的「用在」(删除被拒时拿到的)在重读后保留,直到后端列表自己带上。
          const known = {}
          ;(this.data.keys || []).forEach(k => { if (k.usedIn && k.usedIn.length) known[k.stateKey] = k.usedIn })
          keys.forEach(k => { if (!k.usedIn.length && known[k.stateKey]) k.usedIn = known[k.stateKey] })
          this._raw = {}
          res.data.forEach(raw => { this._raw[raw.stateKey] = raw })
          this.setData({ keys, state: keys.length ? 'ready' : 'empty' })
          this._view()
        },
        fail: () => { if (current()) this.setData({ state: 'error' }) },
        successStatusAbnormal: () => { if (current()) this.setData({ state: 'error' }) },
      })
    },
    onOpen() {
      this.setData({ open: true, expanded: '', adding: '', addName: '', formError: '', deleteError: '' })
      this.triggerEvent('sheetchange', { open: true })
    },
    onClose() {
      if (this.data.saving) return
      this.setData({ open: false, expanded: '', adding: '', addName: '', formError: '' })
      this.triggerEvent('sheetchange', { open: false })
    },
    /** 完成:先把就地写着的名字存下,再收起。 */
    onDone() {
      if (this.data.saving) return
      if (this.data.adding && this.data.addName.trim()) { this._afterSave = 'close'; this.onSave(); return }
      if (this.data.expanded && this._renamePending()) { this._afterSave = 'close'; this.onRename(); return }
      this.onClose()
    },
    onToggle(e) {
      if (this.data.saving || this.properties.readOnly) return
      const id = e.currentTarget.dataset.id
      if (this.data.expanded === id) { this.setData({ expanded: '', editName: '', formError: '' }); return }
      const row = this._row(id)
      const errorKind = !row ? '' : row.itemId != null ? 'item' : id.slice(0, id.indexOf('.'))
      this.setData({ expanded: row ? id : '', editName: row ? row.name : '', errorKind, adding: '', addName: '', formError: '', deleteError: '' })
    },
    onEditName(e) { this.setData({ editName: e.detail.value, formError: '' }) },
    _renamePending() {
      const row = this._row(this.data.expanded)
      return !!row && !!this.data.editName.trim() && this.data.editName.trim() !== row.name
    },
    _finishSave() {
      if (this._afterSave === 'close') { this._afterSave = null; this.onClose() }
      this._afterSave = null
    },
    onRename() {
      const context = this._syncContext()
      if (!this._canWrite(context) || this.data.saving) return
      const row = this._row(this.data.expanded)
      const name = this.data.editName.trim()
      if (!row || !name || name === row.name) { this._finishSave(); return }
      const kind = row.itemId != null ? 'item' : row.id.slice(0, row.id.indexOf('.'))
      const error = nameError(kind, name, this._names(kind).filter(n => n !== row.name))
      if (error) { this._afterSave = null; this.setData({ formError: error }); return }
      if (kind === 'item') {
        this.triggerEvent('itemchange', { action: 'rename', id: row.itemId, name })
        this._finishSave()
        return
      }
      const raw = (this._raw || {})[row.id]
      if (!raw) { this.setData({ formError: SAVE_FAILED }); return }
      const operation = this._saveOperation = {}
      const current = () => this._saveOperation === operation && this._callbackCurrent(context)
      const fail = () => { if (!current()) return; this._saveOperation = this._afterSave = null; this.setData({ saving: false, formError: SAVE_FAILED }) }
      this.setData({ saving: true, formError: '' })
      app.sendRequest({
        url: `/api/topic/state-keys/${encodeURIComponent(row.id)}`, method: 'PUT', hideLoading: true, silentError: true,
        data: JSON.stringify({ topicId: Number(context.topicId), scope: context.scope, key: Object.assign({}, raw, { label: name }) }),
        header: { 'Content-Type': 'application/json' },
        success: (res) => {
          if (!current()) return
          if (!isBizOk(res)) { fail(); return }
          this._saveOperation = null
          this.setData({ saving: false })
          this._finishSave()
          this.load()
        },
        fail,
        successStatusAbnormal: fail,
      })
    },
    onAddOpen(e) {
      if (!this._canWrite(this._syncContext())) return
      const kind = e.currentTarget.dataset.kind
      if (kind !== 'item' && !KINDS[kind]) return
      this.setData({ adding: kind, errorKind: kind, addName: '', expanded: '', formError: '' })
    },
    onAddCancel() { if (!this.data.saving) this.setData({ adding: '', addName: '', formError: '' }) },
    onAddInput(e) {
      const addName = e.detail.value
      this.setData({ addName, formError: nameError(this.data.adding, addName.trim(), this._names(this.data.adding)) })
    },
    onSave() {
      const context = this._syncContext()
      if (!this._canWrite(context) || this.data.saving) return
      const kind = this.data.adding
      const name = String(this.data.addName || '').trim()
      if (!name) { this._afterSave = null; return }
      const error = nameError(kind, name, this._names(kind))
      if (error) { this._afterSave = null; this.setData({ formError: error }); return }
      if (kind === 'item') {
        this.triggerEvent('itemchange', { action: 'add', name })
        this.setData({ adding: '', addName: '' })
        this._finishSave()
        return
      }
      const key = newKey(kind, name, this.data.keys)
      if (!key) return
      const operation = this._saveOperation = {}
      const current = () => this._saveOperation === operation && this._callbackCurrent(context)
      const fail = () => { if (!current()) return; this._saveOperation = this._afterSave = null; this.setData({ saving: false, formError: SAVE_FAILED }) }
      this.setData({ saving: true, formError: '' })
      app.sendRequest({
        url: '/api/topic/state-keys', method: 'POST', hideLoading: true, silentError: true,
        data: JSON.stringify({ topicId: Number(context.topicId), scope: context.scope, keys: [key] }),
        header: { 'Content-Type': 'application/json' },
        success: (res) => {
          if (!current()) return
          if (!isBizOk(res)) { fail(); return }
          this._saveOperation = null
          this.setData({ saving: false, adding: '', addName: '' })
          this._finishSave()
          this.load()
        },
        fail,
        successStatusAbnormal: fail,
      })
    },
    onAskDelete(e) {
      const context = this._syncContext()
      if (!this._canWrite(context) || this.data.deleting) return
      const key = e.currentTarget.dataset.key
      const row = this._row(key)
      const name = row ? row.name : ''
      if (row && row.usedIn.length) { showBlocked(name, row.usedIn); return }
      const dc = this.selectComponent && this.selectComponent('#sk-dc')
      if (dc && key) {
        this._deleteConfirmation = { context, key }
        dc.open('topic.stateKey.delete', { name, key, contextEpoch: context.epoch })
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
      const row = this._row(key)
      if (row && row.itemId != null) {
        this._deleteConfirmation = null
        dc.done()
        this.setData({ expanded: '' })
        this.triggerEvent('itemchange', { action: 'delete', id: row.itemId })
        return
      }
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
          if (!isBizOk(res)) {
            const usedIn = parseUsedIn(res && res.msg)
            if (!usedIn.length) { fail('没有删除成功，请再试一次。'); return }
            // 被用到:记下位置,这一行的「删除」变灰,弹窗说用在哪里。
            this._deleteOperation = this._deleteConfirmation = null
            dc.close()
            const keys = this.data.keys.map(k => (k.stateKey === key ? Object.assign({}, k, { usedIn }) : k))
            this.setData({ deleting: '', keys })
            this._view()
            showBlocked(row ? row.name : '', usedIn)
            return
          }
          this._deleteOperation = this._deleteConfirmation = null
          this.setData({ deleting: '', expanded: '' })
          dc.done()
          this.load()
        },
        fail: () => fail('网络异常，删除结果未确认'),
        successStatusAbnormal: () => fail('网络异常，删除结果未确认'),
      })
    },
  },
})
