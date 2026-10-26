<template>
  <el-dialog title="城市定向计分 · 申诉与争议" :visible="visible" width="94%" append-to-body :close-on-click-modal="false" :before-close="close">
    <cy-resource-state v-if="!canList" kind="forbidden" title="没有计分后台查看权限" description="需要已有的城市定向计分查看权限。" />
    <template v-else>
      <cy-orient-recovery :active="visible" />
      <p>单位为 centi（1 centi = 0.01 分）。处置只追加计分条目；结算后的变化影响展示分，不追发或追扣已发积分与 XP。</p>
      <cy-resource-state v-if="operationError" kind="error" title="处置尚未确认" :description="operationError" />
      <el-alert v-if="outcome" :title="outcome.message" :type="outcome.type" :closable="false" show-icon />
      <div v-if="command" class="scoring-toolbar">
        <el-button :loading="busy" :disabled="sessionLoading || queueLoading" @click="recover">只读核对原操作结果</el-button>
        <el-button v-if="canReview && retryAllowed" type="warning" :disabled="busy || sessionLoading || queueLoading" @click="retryCommand">重试原操作</el-button>
        <span>原操作保留在当前审核员的浏览器会话中；先核对，仍待处理时才可重试原参数。</span>
      </div>
      <div class="scoring-toolbar">
        <el-select v-model="status" :disabled="busy || preparing || Boolean(command)" placeholder="处理状态" @change="loadQueues">
          <el-option label="全部状态" value="" />
          <el-option label="待处理（0）" :value="0" />
          <el-option label="已采纳（1）" :value="1" />
          <el-option label="已驳回（2）" :value="2" />
        </el-select>
        <el-button :loading="queueLoading" :disabled="busy || preparing" @click="loadQueues">刷新队列</el-button>
        <span>每类显示最新 200 条匹配记录；可按对局编号查询完整对局明细。</span>
      </div>
      <el-tabs v-model="kind">
        <el-tab-pane label="计分申诉" name="appeals" />
        <el-tab-pane label="成绩争议" name="disputes" />
      </el-tabs>
      <cy-resource-state v-if="queueErrors[kind]" kind="error" title="队列读取失败" :description="queueErrors[kind]" retry-text="重新读取" :retrying="queueLoading || busy" @retry="loadQueues" />
      <el-table v-else v-loading="queueLoading" :data="queues[kind]" row-key="id">
        <el-table-column prop="id" label="记录编号" min-width="165" />
        <el-table-column prop="sessionId" label="对局编号" min-width="165" />
        <el-table-column prop="memberId" label="会员编号" min-width="165" />
        <el-table-column label="状态" width="105"><template slot-scope="scope">{{ statusLabel(scope.row.status) }}</template></el-table-column>
        <el-table-column prop="reason" label="申请原因" min-width="200" />
        <el-table-column prop="resolutionNote" label="处理说明" min-width="170" />
        <el-table-column prop="handledBy" label="处理人" width="100" />
        <el-table-column v-if="kind === 'disputes'" prop="windowDeadline" label="争议窗口截止" min-width="160" />
        <el-table-column prop="createTime" label="提交时间" min-width="160" />
        <el-table-column label="详情" fixed="right" width="105"><template slot-scope="scope"><el-button type="text" :disabled="busy || preparing || sessionLoading || Boolean(command)" @click="inspect(scope.row, kind)">查看与处理</el-button></template></el-table-column>
      </el-table>
      <cy-empty-state v-if="!queueLoading && !queueErrors[kind] && !queues[kind].length" title="当前筛选没有记录" />
      <el-form inline class="scoring-toolbar" @submit.native.prevent="lookupSession">
        <el-form-item label="完整对局编号"><el-input v-model="sessionInput" :disabled="busy || preparing || Boolean(command)" placeholder="输入对局编号" /></el-form-item>
        <el-form-item><el-button :loading="sessionLoading" :disabled="busy || preparing || Boolean(command)" @click="lookupSession">查询明细与复算</el-button></el-form-item>
      </el-form>
      <cy-resource-state v-if="sessionError" kind="error" title="对局明细读取失败" :description="sessionError" />
      <cy-resource-state v-if="verifyError" kind="error" title="复算读取失败" :description="verifyError" />
      <section v-if="detail">
        <h3>对局 #{{ detail.sessionId }} · {{ sessionStatus(detail.status) }}</h3>
        <p>主题 #{{ detail.topicId }} · 活动 #{{ detail.activityId }} · 展示总分 {{ formatCenti(detail.totalCenti) }}</p>
        <p>开始：{{ detail.startedAt || '未提供' }} · 结束：{{ detail.endedAt || '尚未结束' }} · 争议窗口截止：{{ detail.disputeWindowDeadline || '未提供' }}</p>
        <div class="scoring-toolbar"><span v-for="(value, dimension) in detail.byDimension" :key="dimension">{{ dimensionLabel(dimension) }}：{{ formatCenti(value) }}</span></div>
        <div v-if="detail.settlement">
          <h4>局终结算回执 #{{ detail.settlement.settlementId }}</h4>
          <p>结算分 {{ formatCenti(detail.settlement.totalCenti) }} · 条目数 {{ detail.settlement.entryCount }} · 水位 #{{ detail.settlement.ledgerWatermarkId }} · 已发积分 {{ detail.settlement.pointsAwarded }} · 已发 XP {{ detail.settlement.xpAwarded }}</p>
        </div>
        <p v-else>尚无局终结算回执。</p>
        <el-alert v-if="verification" :type="verification.consistent ? 'info' : 'error'" :closable="false" :title="verification.settled ? (verification.consistent ? '流水水位与结算投影一致' : '流水水位与结算投影不一致，请核查') : '尚未结算：当前流水可读，没有结算投影可供核对'" />
        <p v-if="verification">流水总分 {{ formatCenti(verification.ledgerTotalCenti) }}<template v-if="verification.settled"> · 水位内流水 {{ formatCenti(verification.ledgerAtWatermarkCenti) }} · 投影分 {{ formatCenti(verification.projectionTotalCenti) }} · 结算后变化 {{ formatCenti(verification.postSettlementDeltaCenti) }}</template></p>
        <cy-evidence-panel v-if="activeCase" :title="selectedCase.kind === 'appeals' ? '申诉依据与处理记录' : '争议依据与处理记录'" :items="caseEvidence(activeCase)" />
        <div v-if="activeCase && canReview && activeCase.status === 0" class="scoring-toolbar">
          <el-button type="primary" :disabled="!canAct" @click="openResolve(true)">{{ selectedCase.kind === 'appeals' ? '采纳申诉' : '采纳争议（不改分）' }}</el-button>
          <el-button type="danger" plain :disabled="!canAct" @click="openResolve(false)">驳回</el-button>
        </div>
        <h4>对局内申请（完整回读）</h4>
        <el-button v-for="item in detail.appeals" :key="`a-${item.id}`" type="text" :disabled="busy || preparing || Boolean(command)" @click="selectedCase = { kind: 'appeals', id: item.id }">申诉 #{{ item.id }} · {{ statusLabel(item.status) }}</el-button>
        <el-button v-for="item in detail.disputes" :key="`d-${item.id}`" type="text" :disabled="busy || preparing || Boolean(command)" @click="selectedCase = { kind: 'disputes', id: item.id }">争议 #{{ item.id }} · {{ statusLabel(item.status) }}</el-button>
        <h4>计分流水（保留正负方向）</h4>
        <p v-if="detail.timeline.some(item => item.deltaCenti === -2147483648)">含 int32 最小值条目，其反向分值超出 int32 范围，不能执行整条冲正；可通过申诉追加有效的修正分值。</p>
        <el-table :data="detail.timeline" row-key="entryId">
          <el-table-column prop="entryId" label="流水编号" min-width="165" />
          <el-table-column label="维度" min-width="150"><template slot-scope="scope">{{ dimensionLabel(scope.row.dimension) }}</template></el-table-column>
          <el-table-column label="变化" min-width="175"><template slot-scope="scope">{{ formatCenti(scope.row.deltaCenti) }}（{{ scope.row.deltaCenti }} centi）</template></el-table-column>
          <el-table-column label="累计" min-width="150"><template slot-scope="scope">{{ formatCenti(scope.row.runningCenti) }}</template></el-table-column>
          <el-table-column prop="reasonCode" label="原因码" min-width="160" />
          <el-table-column prop="sourceType" label="来源" width="115" />
          <el-table-column prop="nodeId" label="节点编号" min-width="150" />
          <el-table-column prop="reversesEntryId" label="所冲正流水" min-width="165" />
          <el-table-column prop="at" label="入账时间" min-width="160" />
          <el-table-column v-if="canReview" label="操作" width="90" fixed="right"><template slot-scope="scope"><el-button type="text" :disabled="!canAct || !canReverse(scope.row)" @click="openReverse(scope.row)">整条冲正</el-button></template></el-table-column>
        </el-table>
      </section>
    </template>
    <span slot="footer"><el-button :disabled="busy || preparing || sessionLoading" @click="close">关闭</el-button></span>
    <el-dialog :title="draft && draft.type === 'reverse' ? '整条冲正' : '处理计分申请'" :visible.sync="editOpen" width="560px" append-to-body :close-on-click-modal="false" :close-on-press-escape="false" :show-close="!busy && !preparing">
      <el-form v-if="draft" label-position="top">
        <template v-if="draft.type === 'reverse'">
          <p>向原流水追加相反分值，不修改原流水；结算后的变化不追发或追扣积分与 XP。</p>
          <el-form-item label="冲正原因码（留空使用后端默认 REVERSAL）"><el-input v-model="draft.data.reasonCode" maxlength="64" placeholder="大写字母、数字或下划线" /></el-form-item>
        </template>
        <template v-else>
          <p>{{ draft.data.accept ? (draft.type === 'disputes' ? '采纳成绩争议只记录决定，不自动更改流水或奖励。' : '采纳申诉将追加整条冲正或修正流水。') : '驳回将记录处理决定，不追加计分流水。' }}</p>
          <template v-if="draft.type === 'appeals' && draft.data.accept">
            <el-form-item label="修正方式"><el-radio-group v-model="draft.mode"><el-radio label="reverse" :disabled="!activeCase || !appealCanReverse">整条冲正被申诉流水</el-radio><el-radio label="adjust">追加修正分值</el-radio></el-radio-group></el-form-item>
            <el-form-item v-if="draft.mode === 'adjust'" label="修正分值（整数 centi，支持负值，不能为 0）"><el-input v-model="draft.adjustCenti" placeholder="例如 -125 = -1.25 分" /></el-form-item>
          </template>
          <el-form-item label="处理说明（可选，最多 500 字）"><el-input v-model="draft.data.note" type="textarea" :rows="4" maxlength="500" show-word-limit /></el-form-item>
        </template>
      </el-form>
      <span slot="footer"><el-button :disabled="busy || preparing" @click="editOpen = false">取消</el-button><el-button type="primary" :loading="busy || preparing" @click="submitDraft">核对并提交</el-button></span>
    </el-dialog>
  </el-dialog>
</template>

<script>
import { listScoringAppeals, listScoringDisputes, getScoringSession, verifyScoringSession, resolveScoringAppeal, resolveScoringDispute, reverseScoringEntry, scoringId, formatCenti, adjustmentCenti } from '@/api/business/orientScoring'
import { checkPermi } from '@/utils/permission'
import CyResourceState from '@/components/CyResourceState'
import CyEmptyState from '@/components/CyEmptyState'
import CyEvidencePanel from '@/components/CyEvidencePanel'
import CyOrientRecovery from './OrientRecoveryConsole.vue'

export default {
  name: 'OrientScoringConsole',
  components: { CyResourceState, CyEmptyState, CyEvidencePanel, CyOrientRecovery },
  props: { visible: { type: Boolean, default: false } },
  data() {
    return {
      kind: 'appeals', status: 0, queues: { appeals: [], disputes: [] }, queueErrors: { appeals: '', disputes: '' },
      queueLoading: false, queueReadId: 0, sessionLoading: false, sessionReadId: 0,
      sessionInput: '', detail: null, verification: null, sessionError: '', verifyError: '', selectedCase: null,
      busy: false, preparing: false, editOpen: false, draft: null, command: null, retryAllowed: false, operationError: '', outcome: null
    }
  },
  computed: {
    canList() { return checkPermi(['business:orientScoring:list']) },
    canReview() { return this.canList && checkPermi(['business:orientScoring:review']) },
    canAct() { return this.canReview && !!this.detail && !!this.verification && !this.sessionError && !this.verifyError && !this.sessionLoading && !this.busy && !this.preparing && !this.command },
    storageKey() { return `orient-scoring-admin:${scoringId(this.$store.getters.id)}` },
    activeCase() { return this.detail && this.selectedCase ? this.detail[this.selectedCase.kind].find(item => item.id === this.selectedCase.id) || null : null },
    appealCanReverse() { return !!(this.activeCase && this.activeCase.entryId && this.detail.timeline.some(item => item.entryId === this.activeCase.entryId && this.canReverse(item))) }
  },
  watch: { visible: { immediate: true, handler(value) { if (value && this.canList) this.openConsole() } } },
  methods: {
    formatCenti,
    statusLabel(value) { return ({ 0: '待处理（0）', 1: '已采纳（1）', 2: '已驳回（2）' })[value] || `未知状态（${value}）` },
    sessionStatus(value) { return ({ 0: '进行中', 1: '已完成', 2: '已结束' })[value] || `状态 ${value}` },
    dimensionLabel(value) { return `${({ TASK: '任务', PUZZLE: '谜题', PENALTY: '处罚', BONUS: '奖励', ADJUSTMENT: '修正' })[value] || '未知维度'}（${value}）` },
    normalizeCase(row) {
      if (!row || ![0, 1, 2].includes(row.status)) throw new Error('申请状态异常')
      const result = { ...row, id: scoringId(row.id), sessionId: scoringId(row.sessionId), memberId: scoringId(row.memberId) }
      for (const key of ['entryId', 'nodeId', 'resolutionEntryId']) result[key] = row[key] == null ? null : scoringId(row[key])
      return result
    },
    async openConsole() {
      try { this.command = JSON.parse(sessionStorage.getItem(this.storageKey) || 'null') } catch (e) { this.operationError = '无法读取保留的计分操作，请恢复浏览器会话存储后重试'; return }
      await this.loadQueues()
      if (this.command) await this.recover()
    },
    async loadQueues() {
      if (!this.canList) return
      const requestId = ++this.queueReadId
      this.queueLoading = true
      const responses = await Promise.allSettled([listScoringAppeals(this.status), listScoringDisputes(this.status)])
      if (requestId !== this.queueReadId) return
      responses.forEach((result, index) => {
        const kind = index === 0 ? 'appeals' : 'disputes'
        try {
          if (result.status !== 'fulfilled') throw result.reason
          if (!result.value || result.value.code !== 200 || !Array.isArray(result.value.data)) throw new Error('队列返回格式异常')
          const rows = result.value.data.map(this.normalizeCase)
          if (new Set(rows.map(row => row.id)).size !== rows.length) throw new Error('队列编号重复')
          this.queues[kind] = rows
          this.queueErrors[kind] = ''
        } catch (e) { this.queueErrors[kind] = `${e.scoringCode || ''} ${e.message || '请检查网络与权限'}`.trim() }
      })
      this.queueLoading = false
    },
    async readSession(id) {
      if (!this.canList) return false
      let sessionId
      try { sessionId = scoringId(id) } catch (e) { this.sessionError = e.message; return false }
      const requestId = ++this.sessionReadId
      this.sessionInput = sessionId
      this.sessionLoading = true
      this.detail = null
      this.verification = null
      this.sessionError = ''
      this.verifyError = ''
      const responses = await Promise.allSettled([getScoringSession(sessionId), verifyScoringSession(sessionId)])
      if (requestId !== this.sessionReadId) return false
      try {
        const result = responses[0]
        if (result.status !== 'fulfilled') throw result.reason
        const data = result.value && result.value.data
        if (result.value.code !== 200 || !data || scoringId(data.sessionId) !== sessionId || !Array.isArray(data.timeline) || !Array.isArray(data.appeals) || !Array.isArray(data.disputes) || !data.byDimension) throw new Error('对局明细返回格式异常')
        const timeline = data.timeline.map(entry => {
          if (!Number.isInteger(entry.deltaCenti) || entry.deltaCenti < -2147483648 || entry.deltaCenti > 2147483647) throw new Error('流水分值不是有效 int32 centi')
          return { ...entry, entryId: scoringId(entry.entryId), nodeId: entry.nodeId == null ? null : scoringId(entry.nodeId), reversesEntryId: entry.reversesEntryId == null ? null : scoringId(entry.reversesEntryId) }
        })
        if (new Set(timeline.map(entry => entry.entryId)).size !== timeline.length) throw new Error('流水编号重复')
        const appeals = data.appeals.map(this.normalizeCase)
        const disputes = data.disputes.map(this.normalizeCase)
        if ([...appeals, ...disputes].some(row => row.sessionId !== sessionId) || [appeals, disputes].some(rows => new Set(rows.map(row => row.id)).size !== rows.length)) throw new Error('对局申请归属或编号异常')
        if ([data.totalCenti, ...Object.values(data.byDimension), ...timeline.map(entry => entry.runningCenti)].some(value => (typeof value === 'number' && !Number.isSafeInteger(value)) || !/^-?\d+$/.test(String(value)))) throw new Error('对局分值格式或精度异常')
        this.detail = { ...data, sessionId, timeline, appeals, disputes }
      } catch (e) { this.sessionError = `${e.scoringCode || ''} ${e.message || '请检查网络与权限'}`.trim() }
      try {
        const result = responses[1]
        if (result.status !== 'fulfilled') throw result.reason
        const data = result.value && result.value.data
        if (result.value.code !== 200 || !data || scoringId(data.sessionId) !== sessionId || typeof data.settled !== 'boolean' || typeof data.consistent !== 'boolean') throw new Error('复算返回格式异常')
        const fields = data.settled ? ['ledgerTotalCenti', 'ledgerAtWatermarkCenti', 'projectionTotalCenti', 'postSettlementDeltaCenti'] : ['ledgerTotalCenti']
        if (fields.some(key => (typeof data[key] === 'number' && !Number.isSafeInteger(data[key])) || !/^-?\d+$/.test(String(data[key])))) throw new Error('复算分值格式或精度异常')
        if (this.detail && String(data.ledgerTotalCenti) !== String(this.detail.totalCenti)) throw new Error('明细与复算不在同一流水快照，请重新读取')
        this.verification = data
      } catch (e) { this.verifyError = `${e.scoringCode || ''} ${e.message || '请检查网络与权限'}`.trim() }
      this.sessionLoading = false
      return !!this.detail && !!this.verification
    },
    async lookupSession() {
      if (this.busy || this.preparing || this.command) return
      this.selectedCase = null
      await this.readSession(this.sessionInput)
    },
    async inspect(row, kind) {
      if (!this.canList || this.busy || this.preparing || this.command) return
      this.selectedCase = { kind, id: row.id }
      await this.readSession(row.sessionId)
    },
    caseEvidence(row) {
      return [{ kind: 'text', content: `记录 #${row.id} · 会员 #${row.memberId}\n状态：${this.statusLabel(row.status)}\n关联流水：${row.entryId || '无（漏记类）'} · 节点：${row.nodeId || '无'}\n申请原因：${row.reason || '未提供'}\n处理说明：${row.resolutionNote || '未提供'}\n处理人：${row.handledBy || '未处理'} · 时间：${row.handledAt || '未提供'}\n修正流水：${row.resolutionEntryId || '无'}` }]
    },
    canReverse(entry) {
      return !!this.detail && entry.reversesEntryId == null && entry.deltaCenti !== -2147483648 && !this.detail.timeline.some(item => item.reversesEntryId === entry.entryId)
    },
    openResolve(accept) {
      if (!this.canAct || !this.activeCase || this.activeCase.status !== 0) return
      this.draft = { type: this.selectedCase.kind, id: this.activeCase.id, sessionId: this.detail.sessionId, data: { accept, note: '' }, mode: this.appealCanReverse ? 'reverse' : 'adjust', adjustCenti: '' }
      this.editOpen = true
    },
    openReverse(entry) {
      if (!this.canAct || !this.canReverse(entry)) return
      this.draft = { type: 'reverse', id: entry.entryId, sessionId: this.detail.sessionId, original: { ...entry }, data: { reasonCode: 'REVERSAL' } }
      this.editOpen = true
    },
    async submitDraft() {
      if (!this.canAct || !this.draft) return
      const command = JSON.parse(JSON.stringify(this.draft))
      try {
        if (command.type === 'reverse') {
          command.data.reasonCode = command.data.reasonCode.trim() || 'REVERSAL'
          if (!/^[A-Z0-9_]{1,64}$/.test(command.data.reasonCode)) throw new Error('原因码仅支持 1 至 64 位大写字母、数字或下划线')
          const random = new Uint32Array(4)
          window.crypto.getRandomValues(random)
          command.data.idempotencyKey = `admin-reverse:${Array.from(random, n => n.toString(16).padStart(8, '0')).join('')}`
        } else {
          command.data.note = command.data.note.trim()
          if (command.data.note.length > 500) throw new Error('处理说明不能超过 500 字')
          if (command.type === 'appeals' && command.data.accept) {
            if (command.mode === 'adjust') command.data.adjustCenti = adjustmentCenti(command.adjustCenti)
            else {
              if (!this.appealCanReverse) throw new Error('没有可整条冲正的申诉流水，请使用有效的修正分值')
              command.original = { ...this.detail.timeline.find(item => item.entryId === this.activeCase.entryId) }
            }
          }
        }
      } catch (e) { this.$modal.msgError(e.message); return }
      this.preparing = true
      try {
        await this.$modal.confirm(command.type === 'reverse' ? '确认追加整条反向流水？原流水与已发奖励不会改写。' : '确认提交当前处置？提交后将只读核对会话明细与复算结果。')
        if (!this.canReview || this.command) return
        this.command = { ...command, receipt: null, rejection: null }
        try { this.saveCommand() } catch (e) { this.command = null; this.operationError = '无法保存原操作，本次未提交。请恢复浏览器会话存储后重试'; return }
        this.editOpen = false
        this.outcome = null
        await this.sendCommand()
      } catch (e) {
        // Dismissing the confirmation leaves the draft editable.
      } finally { this.preparing = false }
    },
    saveCommand() { sessionStorage.setItem(this.storageKey, JSON.stringify(this.command)) },
    async sendCommand() {
      if (!this.canReview || this.busy || !this.command) return
      this.busy = true
      this.retryAllowed = false
      try {
        const command = this.command
        const response = command.type === 'reverse' ? await reverseScoringEntry(command.id, command.data) : command.type === 'appeals' ? await resolveScoringAppeal(command.id, command.data) : await resolveScoringDispute(command.id, command.data)
        if (!response || response.code !== 200 || !response.data) throw new Error('响应缺少处置回执')
        const receipt = { ...response.data, id: scoringId(response.data.id) }
        if (scoringId(receipt.sessionId) !== scoringId(command.sessionId) || (command.type === 'reverse'
          ? scoringId(receipt.reversesEntryId) !== command.id || receipt.idempotencyKey !== command.data.idempotencyKey
          : receipt.id !== command.id || receipt.status !== (command.data.accept ? 1 : 2))) throw new Error('处置回执与原请求不一致')
        command.receipt = receipt
        command.rejection = null
        this.saveCommand()
      } catch (e) {
        if (e.scoringCode) {
          this.command.rejection = { code: e.scoringCode, message: e.message }
          try { this.saveCommand() } catch (storageError) { /* The original command is already retained. */ }
        }
        this.operationError = `处置响应尚未确认：${e.scoringCode || ''} ${e.message || '网络异常'}`
      }
      try { await this.reconcile(); await this.loadQueues() } finally { this.busy = false }
    },
    async recover() {
      if (!this.canList || this.busy || this.sessionLoading || !this.command) return
      this.busy = true
      try { await this.reconcile() } finally { this.busy = false }
    },
    async retryCommand() {
      if (!this.canReview || this.busy || this.preparing || !this.command || !this.retryAllowed) return
      this.preparing = true
      try {
        await this.$modal.confirm('已回读仍待处理。确认使用原参数重试？冲正沿用原幂等键，申请处置沿用原决定。')
        await this.sendCommand()
      } catch (e) {
        // Cancellation preserves the unresolved command.
      } finally { this.preparing = false }
    },
    finishCommand(message, type = 'success') {
      sessionStorage.removeItem(this.storageKey)
      this.command = null
      this.retryAllowed = false
      this.operationError = ''
      this.outcome = { message, type }
    },
    async reconcile() {
      const command = this.command
      this.retryAllowed = false
      if (!command) return
      try {
        if (!await this.readSession(command.sessionId)) throw new Error('独立明细或复算读取失败，请只读核对原操作结果')
        if (command.type === 'reverse') {
          const entry = this.detail.timeline.find(item => item.reversesEntryId === command.id)
          if (entry) {
            const matches = entry.deltaCenti === -command.original.deltaCenti && entry.dimension === command.original.dimension && entry.reasonCode === command.data.reasonCode && entry.sourceType === 'ADMIN'
            const own = matches && command.receipt && command.receipt.id === entry.entryId && command.receipt.idempotencyKey === command.data.idempotencyKey
            const note = this.verification.consistent ? '' : ' 复算不一致，仍需人工核查。'
            this.finishCommand((own ? `冲正流水 #${entry.entryId} 已独立回读确认。` : `原流水已存在冲正 #${entry.entryId}；已回读，但不能确认归属本次请求，请核对原因与分值。`) + note, own && this.verification.consistent ? 'success' : 'warning')
            return
          }
        } else {
          const row = this.detail[command.type].find(item => item.id === command.id)
          if (!row) throw new Error('完整会话中未找到原申请，尚不能确认处理结果')
          this.selectedCase = { kind: command.type, id: row.id }
          if (row.status !== 0) {
            const target = command.data.accept ? 1 : 2
            let matches = row.status === target && (row.resolutionNote || '').trim() === command.data.note && row.handledBy === this.$store.getters.name
            if (matches && command.type === 'appeals' && command.data.accept) {
              const entry = this.detail.timeline.find(item => item.entryId === row.resolutionEntryId)
              matches = !!entry && entry.sourceType === 'APPEAL' && entry.reasonCode === 'APPEAL_ACCEPTED' && (command.mode === 'adjust'
                ? entry.dimension === 'ADJUSTMENT' && entry.deltaCenti === Number(command.data.adjustCenti) && entry.reversesEntryId == null
                : entry.reversesEntryId === command.original.entryId && entry.deltaCenti === -command.original.deltaCenti && entry.dimension === command.original.dimension)
            }
            const note = this.verification.consistent ? '' : ' 复算不一致，仍需人工核查。'
            this.finishCommand(matches ? `申请 #${row.id} 的决定与关联流水已独立回读确认。${command.type === 'disputes' ? '争议决定不自动改分。' : ''}${note}` : `申请 #${row.id} 已由 ${row.handledBy || '其他处理人'} ${this.statusLabel(row.status)}，与原操作未完全匹配，请核对处理记录。${note}`, matches && this.verification.consistent ? 'success' : 'warning')
            return
          }
        }
        if (command.rejection) {
          this.finishCommand(`请求被拒绝：${command.rejection.code} ${command.rejection.message}；独立回读仍待处理。`, 'error')
          return
        }
        this.retryAllowed = true
        this.operationError = '独立回读仍未发现原操作结果。可能仍在处理中；继续只读核对，或确认后使用原参数重试。'
      } catch (e) { this.operationError = `结果未知：${e.message || '读取失败'}。原操作仍被保留，请稍后只读核对。` }
    },
    close(done) {
      if (this.busy || this.preparing || this.sessionLoading) return
      this.$emit('update:visible', false)
      if (typeof done === 'function') done()
    }
  }
}
</script>

<style scoped>
.scoring-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin: 16px 0; }
</style>
