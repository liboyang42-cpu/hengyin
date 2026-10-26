<template>
  <section v-if="canList">
    <h3>历史计分人工核对</h3>
    <p>缺少原请求号的历史待办在此处理。核对意见不证明原请求成功，不解除玩家冻结，也不发放或扣回奖励。</p>
    <el-alert v-if="message" :title="message" :closable="false" type="info" />
    <el-button :disabled="busy" @click="load">读取核对队列</el-button>
    <el-button v-if="command" :disabled="busy" @click="recover">只读核对原处置</el-button>
    <el-button v-if="command && retryAllowed && canReview" :disabled="busy" @click="retry">重试原核对意见</el-button>
    <el-table :data="rows" row-key="id">
      <el-table-column prop="id" label="核对编号" />
      <el-table-column prop="sessionId" label="对局编号" />
      <el-table-column prop="kind" label="原操作类型" />
      <el-table-column label="状态"><template slot-scope="scope">{{ scope.row.status === 0 ? '待核对' : '已记录意见' }}</template></el-table-column>
      <el-table-column label="操作"><template slot-scope="scope"><el-button type="text" :disabled="busy || Boolean(command)" @click="inspect(scope.row)">读取事实</el-button></template></el-table-column>
    </el-table>
    <section v-if="selected && detail">
      <p>对局 {{detail.sessionId}} · 核对 #{{selected.id}} · {{selected.kind}} · {{selected.status === 0 ? '待核对' : '已记录意见'}}</p>
      <p>当前账本 {{formatCenti(detail.totalCenti)}} · {{consistent ? '水位复算一致' : '复算尚未确认，不能提交意见'}}</p>
      <el-table :data="detail.timeline" row-key="entryId">
        <el-table-column prop="entryId" label="流水编号" />
        <el-table-column prop="dimension" label="维度" />
        <el-table-column prop="deltaCenti" label="增量 centi" />
        <el-table-column prop="reversesEntryId" label="冲正原条目" />
      </el-table>
      <p v-if="selected.resolutionNote">已记录意见：{{selected.resolutionNote}}</p>
      <el-form v-if="selected.status === 0" @submit.native.prevent="submit">
        <el-form-item label="实际关联记录编号（可空）"><el-input v-model="referenceId" :disabled="busy || Boolean(command) || !canReview || selected.kind === 'SYNC'" placeholder="结算/申诉/争议对应编号，不能用查无记录证明失败" /></el-form-item>
        <el-form-item label="核对意见"><el-input v-model="note" type="textarea" maxlength="500" :disabled="busy || Boolean(command) || !canReview" /></el-form-item>
        <el-button :disabled="busy || Boolean(command) || !consistent || !canReview" @click="submit">确认记录核对意见</el-button>
      </el-form>
    </section>
  </section>
</template>
<script>
import { listScoringRecoveries, reviewScoringRecovery, getScoringSession, verifyScoringSession, scoringId, formatCenti } from '@/api/business/orientScoring'
import { checkPermi } from '@/utils/permission'
export default {
  name: 'OrientRecoveryConsole',
  props: { active: { type: Boolean, default: false } },
  data() { return { rows: [], selected: null, detail: null, consistent: false, note: '', referenceId: '', command: null, retryAllowed: false, busy: false, message: '', epoch: 0 } },
  computed: {
    canList() { return checkPermi(['business:orientScoring:list']) },
    canReview() { return this.canList && checkPermi(['business:orientScoring:review']) },
    reviewer() { return String(this.$store.getters.id || '') },
    storageKey() { return 'orient-score-recovery:' + scoringId(this.reviewer) }
  },
  watch: {
    active: { immediate: true, handler(value) { this.reset(); if (value) this.load() } },
    reviewer() { this.reset(); if (this.active) this.load() }
  },
  beforeDestroy() { this.reset() },
  methods: {
    formatCenti,
    reset() { this.epoch++; this.rows = []; this.selected = null; this.detail = null; this.consistent = false; this.command = null; this.busy = false; this.message = ''; this.note = ''; this.referenceId = ''; this.retryAllowed = false },
    capture() { return { epoch: this.epoch, reviewer: this.reviewer } },
    current(c) { return this.active && this.canList && c.epoch === this.epoch && c.reviewer === this.reviewer },
    normalize(row) {
      if (!row || !['SYNC', 'SETTLE', 'APPEAL', 'DISPUTE'].includes(row.kind) || ![0, 1].includes(row.status)) throw Error('核对记录格式异常')
      return { ...row, id: scoringId(row.id), sessionId: scoringId(row.sessionId), memberId: scoringId(row.memberId), referenceId: row.referenceId == null ? null : scoringId(row.referenceId) }
    },
    async load() {
      if (!this.active || !this.canList || this.busy) return
      const c = this.capture(); this.busy = true
      try {
        const stored = JSON.parse(sessionStorage.getItem(this.storageKey) || 'null')
        if (stored) {
          const selected = this.normalize(stored.selected)
          if (stored.reviewer !== c.reviewer || !stored.data || typeof stored.data.note !== 'string' || !stored.data.note.trim() || stored.data.note.length > 500) throw Error('保留处置格式异常，不能重发')
          if (stored.data.referenceId) scoringId(stored.data.referenceId)
          this.command = { ...stored, selected }
        }
        const response = await listScoringRecoveries('')
        if (!this.current(c)) return
        if (!response || response.code !== 200 || !Array.isArray(response.data)) throw Error('核对队列读取失败')
        this.rows = response.data.map(this.normalize)
      } catch (e) { if (this.current(c)) this.message = e.message || '核对队列读取失败' }
      finally { if (this.current(c)) this.busy = false }
      if (this.current(c) && this.command) await this.recover()
    },
    async read(row, c) {
      const results = await Promise.all([getScoringSession(row.sessionId), verifyScoringSession(row.sessionId)])
      if (!this.current(c)) return false
      const [detail, verification] = results
      if (!detail || detail.code !== 200 || !detail.data || scoringId(detail.data.sessionId) !== row.sessionId
          || !Array.isArray(detail.data.recoveries) || !Array.isArray(detail.data.timeline)) throw Error('本局事实读取失败')
      const found = detail.data.recoveries.map(this.normalize).find(x => x.id === row.id)
      if (!found || found.memberId !== row.memberId || found.sessionId !== row.sessionId || found.kind !== row.kind) throw Error('核对对象归属异常')
      this.selected = found; this.detail = detail.data
      this.consistent = !!(verification && verification.code === 200 && verification.data
        && scoringId(verification.data.sessionId) === row.sessionId && verification.data.consistent === true
        && String(verification.data.ledgerTotalCenti) === String(detail.data.totalCenti))
      return true
    },
    async inspect(row) {
      if (!this.canList || this.busy || this.command) return
      const c = this.capture(); this.busy = true; this.detail = null; this.consistent = false; this.note = ''; this.referenceId = ''
      try { await this.read(this.normalize(row), c) } catch (e) { if (this.current(c)) this.message = e.message }
      finally { if (this.current(c)) this.busy = false }
    },
    async submit() {
      if (!this.canReview || this.busy || this.command || !this.selected || this.selected.status !== 0 || !this.consistent) return
      const c = this.capture(); this.busy = true
      try {
        const note = this.note.trim()
        if (!note || note.length > 500) throw Error('请填写1至500字核对意见')
        const referenceId = this.referenceId.trim() ? scoringId(this.referenceId.trim()) : null
        if (this.selected.kind === 'SYNC' && referenceId) throw Error('同步不能绑定不明流水')
        const command = { reviewer: c.reviewer, selected: { ...this.selected }, data: { note, referenceId } }
        await this.$modal.confirm('仅记录本局核对意见，不证明原请求成功，也不解除玩家冻结。确认提交？')
        if (!this.current(c) || !this.canReview) return
        sessionStorage.setItem(this.storageKey, JSON.stringify(command))
        if (sessionStorage.getItem(this.storageKey) !== JSON.stringify(command)) throw Error('原处置未能保存，本次未提交')
        this.command = command
      } catch (e) { if (this.current(c)) this.message = e.message || '未提交，原待办保留' }
      finally { if (this.current(c)) this.busy = false }
      if (this.current(c) && this.command) await this.send()
    },
    async send() {
      if (!this.canReview || this.busy || !this.command) return
      const c = this.capture(), command = this.command; this.busy = true; this.retryAllowed = false
      try { await reviewScoringRecovery(command.selected.id, command.data) }
      catch (e) { if (this.current(c)) this.message = '提交结果未确认，正在独立核对' }
      finally { if (this.current(c)) this.busy = false }
      if (this.current(c)) await this.recover()
    },
    async recover() {
      if (!this.canList || this.busy || !this.command) return
      const c = this.capture(), command = this.command; this.busy = true; this.retryAllowed = false
      try {
        if (!await this.read(command.selected, c)) return
        if (this.selected.status === 1) {
          const own = this.selected.handledBy === this.$store.getters.name && this.selected.resolutionNote === command.data.note
            && this.selected.referenceId === command.data.referenceId
          sessionStorage.removeItem(this.storageKey)
          if (sessionStorage.getItem(this.storageKey)) throw Error('处置已记录，但本机待办未能清理')
          this.command = null
          this.message = own ? '核对意见已独立回读；玩家原操作仍保持待确认。' : '该核对已由审核人员处理，请查看实际意见；不代表本次提交成功。'
        } else {
          this.retryAllowed = this.consistent
          this.message = '当前仍待核对；保留原意见，可再次只读核对或同参数重试。'
        }
      } catch (e) { if (this.current(c)) this.message = e.message || '处置仍待确认' }
      finally { if (this.current(c)) this.busy = false }
    },
    async retry() {
      if (!this.canReview || !this.retryAllowed || this.busy || !this.command) return
      const c = this.capture(); this.busy = true
      try {
        await this.$modal.confirm('使用原核对编号和原意见重试，不改变决定。')
        if (!this.current(c) || !this.canReview) return
      } catch (e) { return }
      finally { if (this.current(c)) this.busy = false }
      if (this.current(c)) await this.send()
    }
  }
}
</script>
