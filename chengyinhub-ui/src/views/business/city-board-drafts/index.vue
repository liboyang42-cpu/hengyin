<template>
  <section class="city-draft app-container" aria-labelledby="city-draft-title">
    <h1 id="city-draft-title">城市棋盘草稿 · City board drafts</h1>
    <p class="notice">隔离评估 / Isolated evaluation. 保存与预览不发布棋盘、不批准规则、不扣发积分。</p>
    <p v-if="!enabled" role="alert">城市草稿工作台未启用 / Workbench disabled.</p>
    <template v-else>
      <div class="toolbar">
        <label>草稿 ID / Draft ID <input v-model.trim="draftId" :disabled="busy || pending || !!saved" maxlength="64"></label>
        <button :disabled="busy || !draftId" @click="reopen">重新读取 / Reopen</button>
        <button :disabled="busy" @click="reset">新建草稿 / New draft</button>
      </div>
      <p role="status" aria-live="polite">{{ status }}</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="saved">已保存版本 / Saved version: {{ saved.version }} · {{ saved.payloadHash }}</p>
      <p v-if="pending" class="notice">保存结果未确认。表单已锁定，请重试原请求或重新读取。/ Save outcome unconfirmed. Retry the exact request or reopen.</p>
      <form @submit.prevent="save">
        <fieldset :disabled="busy || !!pending || blocked || !catalog">
          <legend>平台规则 / platform_rule</legend>
          <div class="fields">
            <label>名称 / Name <input v-model.trim="form.name" maxlength="120" required></label>
            <label>片区 / Region <select v-model="form.regionId" :disabled="!!saved" required @change="regionChanged"><option value="" disabled>请选择 / Select</option><option v-for="id in regions" :key="id" :value="id">{{ id }}</option></select></label>
            <label>时区 / IANA timezone <input v-model.trim="form.timezone" placeholder="例如 / e.g. Asia/Shanghai" required></label>
            <label>开始 / Opens (ISO UTC) <input v-model.trim="form.opensAt" placeholder="YYYY-MM-DDTHH:mm:ssZ" required></label>
            <label>结束 / Closes (ISO UTC) <input v-model.trim="form.closesAt" placeholder="YYYY-MM-DDTHH:mm:ssZ" required></label>
            <label v-for="ref in referenceFields" :key="ref.field">{{ ref.label }}
              <select :value="form.rule[ref.field] && form.rule[ref.field].id" required @change="selectReference(ref.field, $event.target.value)">
                <option value="">请选择版本引用 / Select versioned reference</option>
                <option v-for="item in references(ref.kind)" :key="item.id" :value="item.id">{{ item.name }} · v{{ item.version }}</option>
              </select>
              <small v-if="form.rule[ref.field]">{{ form.rule[ref.field].hash }}</small>
            </label>
            <label v-for="field in numericFields" :key="field.key">{{ field.label }}<input v-model.number="form.rule[field.key]" type="number" step="1" :min="field.min" required></label>
          </div>
          <label>服务点 / Service points (最多 / maximum 50)
            <select multiple :value="form.servicePoints.map(p => p.placeId)" @change="selectPlaces">
              <option v-for="place in places" :key="place.placeId" :value="place.placeId">{{ place.name }} · {{ place.placeId }}</option>
            </select>
          </label>
          <p>所有成本、奖励与预算必须显式填写。这里的数值是未批准草稿。/ All values are explicit, unapproved draft values.</p>
          <button type="button" @click="validate">校验引用与约束 / Validate</button>
          <button type="submit">保存新版本 / Save version</button>
        </fieldset>
      </form>
      <div class="toolbar">
        <button v-if="pending" :disabled="busy" @click="retry">重试原保存 / Retry exact save</button>
        <button :disabled="busy || !saved || !!pending || dirty" @click="preview">玩家只读预览 / Player preview</button>
      </div>
      <section v-if="playerPreview" aria-label="Player read-only preview" class="preview">
        <h2>玩家只读预览 / Player read-only preview</h2>
        <p>未批准草稿 / DRAFT_UNAPPROVED · v{{ playerPreview.version }} · 不可加入 / Cannot join</p>
        <h3>{{ playerPreview.platform_rule.name }}</h3>
        <p>{{ playerPreview.platform_rule.regionId }} · {{ playerPreview.platform_rule.timezone }}</p>
        <p>{{ playerPreview.platform_rule.opensAt }} → {{ playerPreview.platform_rule.closesAt }}</p>
        <dl><template v-for="field in numericFields"><dt :key="field.key + '-label'">{{ field.label }}</dt><dd :key="field.key">{{ playerPreview.platform_rule.rule[field.key] }}</dd></template></dl>
        <h3>地点事实 / merchant_operational_fact</h3>
        <p>保存时的验证快照，不代表当前营业或发布许可。/ Validation snapshot at save time; not current opening or publication permission.</p>
        <ul><li v-for="place in playerPreview.merchant_operational_fact.places" :key="place.placeId">{{ place.placeId }} · v{{ place.version }} · 服务容量 / capacity {{ place.capacity }}</li></ul>
        <h3>玩家策略 / player_strategy</h3><p>草稿预览只读，不创建参与、经营权、行动或积分。/ Read-only: no participation, ownership, actions or points.</p>
        <button @click="playerPreview = null">关闭预览 / Close preview</button>
      </section>
    </template>
  </section>
</template>

<script>
import { cityBoardDraftsEnabled, readCityDraftCatalog, readCityDraft, validateCityDraft, saveCityDraft, previewCityDraft } from '@/api/business/city-board-drafts'
const clone = value => JSON.parse(JSON.stringify(value))
const referenceFields = [
  { field: 'rulesRef', kind: 'RULES', label: '规则参考 / Rules reference' },
  { field: 'taskRef', kind: 'TASK', label: '任务 / Task' },
  { field: 'diceRef', kind: 'DICE', label: '骰子 / Dice policy' },
  { field: 'challengeRef', kind: 'CHALLENGE', label: '挑战 / Challenge policy' },
  { field: 'settlementRef', kind: 'SETTLEMENT', label: '结算 / Settlement policy' }
]
const numericFields = [
  { key: 'actionBudget', label: '行动预算 / Action budget', min: 1 },
  { key: 'maxHoldings', label: '持有上限 / Maximum holdings', min: 1 },
  { key: 'protectionSeconds', label: '保护秒数 / Protection seconds', min: 0 },
  { key: 'acquisitionCostPoints', label: '取得成本（积分）/ Acquisition points', min: 0 },
  { key: 'serviceFeePoints', label: '服务费（积分）/ Service fee points', min: 0 },
  { key: 'taskRewardPoints', label: '任务奖励（积分）/ Task reward points', min: 0 },
  { key: 'rewardBudgetPoints', label: '奖励预算（积分）/ Reward budget points', min: 0 }
]
function blank() {
  const rule = {}
  referenceFields.forEach(ref => { rule[ref.field] = null })
  numericFields.forEach(field => { rule[field.key] = null })
  return { classification: 'platform_rule', name: '', regionId: '', timezone: '', opensAt: '', closesAt: '', rule, servicePoints: [] }
}
function requestId() {
  const bytes = new Uint8Array(16)
  window.crypto.getRandomValues(bytes)
  return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
}
export default {
  name: 'CityBoardDraftWorkbench',
  data() { return { enabled: cityBoardDraftsEnabled, referenceFields, numericFields, catalog: null, draftId: '', form: blank(), saved: null, playerPreview: null, pending: null, busy: false, blocked: false, status: '', error: '', epoch: 0, disposed: false, active: true, catalogLoading: false } },
  computed: {
    regions() { return this.catalog ? this.catalog.regionIds : [] },
    places() { return this.catalog ? this.catalog.places.filter(p => p.regionId === this.form.regionId) : [] },
    dirty() { return !this.saved || JSON.stringify(this.form) !== JSON.stringify(this.saved.payload) }
  },
  watch: {
    '$route.fullPath': { handler() { this.contextChanged() }, sync: true },
    '$store.getters.id': { handler() { this.contextChanged() }, sync: true },
    '$store.getters.token': { handler() { this.contextChanged() }, sync: true },
    '$store.getters.roles': { handler() { this.contextChanged() }, deep: true, sync: true },
    '$store.getters.permissions': { handler() { this.contextChanged() }, deep: true, sync: true }
  },
  created() { this.loadCatalog() },
  beforeDestroy() { this.disposed = true; this.invalidate() },
  deactivated() { this.active = false; this.invalidate() },
  activated() { this.active = true; if (!this.catalog && !this.catalogLoading) this.loadCatalog() },
  methods: {
    invalidate() { this.epoch++; this.catalog = null; this.saved = null; this.pending = null; this.playerPreview = null; this.form = blank(); this.draftId = ''; this.status = ''; this.error = ''; this.busy = false; this.blocked = false; this.catalogLoading = false },
    contextFingerprint() {
      const getters = this.$store.getters
      return JSON.stringify([this.$route.fullPath, getters.id, getters.token, getters.roles, getters.permissions])
    },
    requestContext() {
      const epoch = this.epoch, fingerprint = this.contextFingerprint()
      return { isCurrent: () => !this.disposed && this.active && epoch === this.epoch && fingerprint === this.contextFingerprint(), captureCurrent: () => this.requestContext() }
    },
    contextChanged() {
      this.invalidate()
      const epoch = this.epoch
      this.$nextTick(() => {
        if (!this.disposed && this.active && epoch === this.epoch && /^\/platform\/city-board-drafts(?:\/|$|\?|#)/.test(this.$route.fullPath)) this.loadCatalog()
      })
    },
    async loadCatalog() {
      if (!this.enabled || this.disposed || !this.active || this.catalogLoading || !this.$store.getters.token) return
      const epoch = ++this.epoch; this.catalogLoading = true
      try { const response = await readCityDraftCatalog(this.requestContext()); if (epoch === this.epoch && !this.disposed) this.catalog = response.data }
      catch (e) { if (epoch === this.epoch && !this.disposed) this.error = e.message || '读取失败 / Read failed' }
      finally { if (epoch === this.epoch && !this.disposed) this.catalogLoading = false }
    },
    references(kind) { return this.catalog ? this.catalog.references.filter(r => r.kind === kind && r.regionId === this.form.regionId) : [] },
    regionChanged() { referenceFields.forEach(ref => { this.form.rule[ref.field] = null }); this.form.servicePoints = []; this.playerPreview = null },
    selectReference(field, id) { const ref = this.catalog.references.find(r => r.id === id && r.regionId === this.form.regionId); this.form.rule[field] = ref ? { id: ref.id, version: ref.version, hash: ref.hash } : null; this.playerPreview = null },
    selectPlaces(event) { this.form.servicePoints = Array.from(event.target.selectedOptions, option => ({ placeId: option.value, role: 'SERVICE' })); this.playerPreview = null },
    command() { return { draftId: this.draftId, requestId: requestId(), expectedVersion: this.saved ? this.saved.version : 0, expectedHash: this.saved ? this.saved.payloadHash : null, payload: clone(this.form) } },
    async operation(action) {
      if (this.busy || this.disposed) return
      this.busy = true; this.error = ''; const epoch = this.epoch
      try { const value = await action(); if (epoch !== this.epoch || this.disposed) return; return value }
      catch (e) { if (epoch === this.epoch && !this.disposed) { this.error = e.errorCode || e.message || '请求未确认 / Request unconfirmed'; if ([400, 401, 403, 409].includes(e.code)) { this.pending = null; this.blocked = e.code !== 400; if (e.code === 409) this.status = '版本冲突，请重新读取后核对修改。/ Version conflict: reopen and review before editing.' } } }
      finally { if (epoch === this.epoch && !this.disposed) this.busy = false }
    },
    async validate() {
      const response = await this.operation(() => validateCityDraft(this.command(), this.requestContext()))
      if (response) this.status = '草稿校验通过，尚未保存或批准。/ Draft validates; not yet saved or approved.'
    },
    async save() {
      if (this.pending || this.busy || this.blocked) return
      this.pending = clone(this.command()); this.playerPreview = null
      await this.retry()
    },
    async retry() {
      if (!this.pending) return
      const response = await this.operation(() => saveCityDraft(clone(this.pending), this.requestContext()))
      if (response) { this.saved = clone(response.data); this.form = clone(response.data.payload); this.pending = null; this.status = '草稿已保存。未发布或发放积分。/ Draft saved. No publication or points issued.' }
    },
    async reopen() {
      if (this.busy || !this.draftId) return
      if ((this.dirty || this.pending) && !window.confirm('重新读取会替换未保存修改。继续？ / Reopen replaces unsaved edits. Continue?')) return
      const response = await this.operation(() => readCityDraft(this.draftId, this.requestContext()))
      if (response) { this.saved = clone(response.data); this.form = clone(response.data.payload); this.pending = null; this.blocked = false; this.playerPreview = null; this.status = '已读取保存版本 / Saved version reopened' }
    },
    async preview() {
      if (!this.saved || this.dirty || this.pending) return
      const response = await this.operation(() => previewCityDraft({ draftId: this.saved.draftId, expectedVersion: this.saved.version, expectedHash: this.saved.payloadHash }, this.requestContext()))
      if (response) this.playerPreview = response.data
    },
    reset() {
      if (this.busy || ((this.dirty || this.pending) && !window.confirm('放弃当前编辑？ / Discard current edits?'))) return
      this.invalidate(); this.draftId = ''; this.status = ''; this.error = ''; this.loadCatalog()
    }
  }
}
</script>

<style scoped>
.city-draft { max-width: 1100px; margin: auto; color: #1f2937; }
.notice { background: #fff7df; padding: 14px; border-left: 4px solid #b7791f; }
.error { color: #a61b1b; white-space: pre-wrap; }
.toolbar { display: flex; flex-wrap: wrap; gap: 12px; align-items: end; margin: 16px 0; }
.fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 18px; }
label { display: flex; flex-direction: column; gap: 7px; margin: 8px 0; }
input, select, button { font: inherit; padding: 10px; border: 1px solid #8795a8; border-radius: 5px; }
button { cursor: pointer; background: #eef5fc; margin: 5px; }
button:disabled { cursor: not-allowed; opacity: .5; }
small { overflow-wrap: anywhere; }
fieldset { border: 1px solid #cbd5e1; padding: 20px; }
select[multiple] { min-height: 110px; }
.preview { border: 2px solid #47709a; padding: 20px; margin: 20px 0; }
dt { font-weight: bold; } dd { margin: 4px 0 16px; }
</style>
