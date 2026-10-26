<template>
  <el-dialog title="商家认领待审队列" :visible="visible" width="90%" append-to-body :close-on-click-modal="false" :before-close="close">
    <cy-resource-state v-if="!canAudit" kind="forbidden" title="没有商家认领审核权限" description="需要商家编辑权限才能读取和处理认领。" />
    <template v-else>
      <p>按提交顺序显示最早的 100 条待审申请；展开每条记录核对联系人、申请说明和证明材料后处理。</p>
      <cy-resource-state v-if="error" kind="error" title="认领处理尚未完成" :description="error" retry-text="重新读取待审队列" :retrying="loading || busy" @retry="loadPending" />
      <el-alert v-if="command" title="存在待核验操作，请先恢复结果。关闭窗口后仍会保留原请求，不要另行提交。" type="warning" :closable="false" show-icon />
      <div class="claim-toolbar">
        <el-button :loading="loading" :disabled="busy || preparing" @click="loadPending">刷新队列</el-button>
        <el-button v-if="command" type="warning" :loading="busy" :disabled="loading" @click="recover">恢复原请求并核验结果</el-button>
        <el-button type="primary" :disabled="!canSubmit || !selected.length" @click="prepare(selected, 'APPROVE', true)">批量通过</el-button>
        <el-button type="danger" plain :disabled="!canSubmit || !selected.length" @click="prepare(selected, 'REJECT', true)">批量驳回</el-button>
      </div>
      <el-table v-loading="loading" :data="rows" row-key="id" @selection-change="selected = $event">
        <el-table-column type="selection" width="45" :selectable="selectable" />
        <el-table-column type="expand">
          <template slot-scope="scope">
            <cy-evidence-panel title="认领资料与证明" :items="evidence(scope.row)" />
            <div v-for="(proof, index) in scope.row.proofs" :key="index" class="claim-proof">
              <span>{{ proof.proofType }} · {{ proof.assetId }}</span>
              <cy-media-preview v-if="proofUrl(proof.assetId)" :sources="[proofUrl(proof.assetId)]" label="认领证明材料" />
              <span v-else>资源标识无可用预览，请核实原始材料后处理。</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="认领编号" min-width="170" />
        <el-table-column prop="merchantId" label="商家编号" min-width="170" />
        <el-table-column prop="claimantMemberId" label="申请会员编号" min-width="170" />
        <el-table-column prop="contactName" label="联系人" min-width="100" />
        <el-table-column prop="contactPhone" label="联系电话" min-width="130" />
        <el-table-column prop="submittedAt" label="提交时间" min-width="160" />
        <el-table-column label="操作" width="145" fixed="right">
          <template slot-scope="scope">
            <el-button type="text" :disabled="!canSubmit" @click="prepare([scope.row], 'APPROVE', false)">通过</el-button>
            <el-button type="text" :disabled="!canSubmit" @click="prepare([scope.row], 'REJECT', false)">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>
      <cy-empty-state v-if="!loading && !error && !rows.length" title="当前没有待审认领" description="刷新可读取最新申请。" />
      <section v-if="results.length" aria-live="polite">
        <h3>本次处理结果</h3>
        <el-table :data="results" row-key="claimId">
          <el-table-column prop="claimId" label="认领编号" min-width="170" />
          <el-table-column prop="currentStatus" label="服务端状态" />
          <el-table-column prop="message" label="结果及回读核验" min-width="300" />
        </el-table>
      </section>
    </template>
    <span slot="footer"><el-button :disabled="busy || preparing" @click="close">关闭</el-button></span>
    <cy-confirm-action :visible.sync="rejectOpen" title="驳回商家认领" :impact="`将驳回选中的 ${draft ? draft.rows.length : 0} 条申请，原因会记录在审核结果中。`" confirm-text="确认驳回" :reason-maxlength="500" :loading="busy" @confirm="submitReject" />
  </el-dialog>
</template>

<script>
import { pendingMerchantClaims, auditMerchantClaim, auditMerchantClaimBatch, claimId } from '@/api/business/merchantClaim'
import { checkPermi } from '@/utils/permission'
import CyConfirmAction from '@/components/CyConfirmAction'
import CyResourceState from '@/components/CyResourceState'
import CyEmptyState from '@/components/CyEmptyState'
import CyEvidencePanel from '@/components/CyEvidencePanel'
import CyMediaPreview from '@/components/CyMediaPreview'

export default {
  name: 'MerchantClaimAuditDialog',
  components: { CyConfirmAction, CyResourceState, CyEmptyState, CyEvidencePanel, CyMediaPreview },
  props: { visible: { type: Boolean, default: false } },
  data() {
    return { rows: [], selected: [], loading: false, busy: false, preparing: false, error: '', command: null, results: [], draft: null, rejectOpen: false }
  },
  computed: {
    canAudit() { return checkPermi(['business:merchant:edit']) },
    canSubmit() { return this.canAudit && !this.loading && !this.busy && !this.preparing && !this.error && !this.command },
    storageKey() { return `merchant-claim-audit:${claimId(this.$store.getters.id)}` }
  },
  watch: {
    visible: {
      immediate: true,
      handler(value) { if (value && this.canAudit) this.openQueue() }
    },
    rejectOpen(value) { if (!value) { this.preparing = false; this.draft = null } }
  },
  methods: {
    async openQueue() {
      try {
        this.command = JSON.parse(sessionStorage.getItem(this.storageKey) || 'null')
      } catch (e) {
        this.error = '无法读取保留的审核请求，请恢复当前浏览器的会话存储后重试。'
        return
      }
      await this.loadPending()
    },
    async loadPending() {
      if (!this.canAudit || this.loading) return false
      this.loading = true
      try {
        const response = await pendingMerchantClaims()
        if (!response || response.code !== 200 || !Array.isArray(response.data)) throw new Error('待审队列返回格式异常')
        const ids = new Set()
        const rows = response.data.map(row => {
          const id = claimId(row.id)
          if (ids.has(id) || row.status !== 'PENDING' || !Number.isInteger(row.version) || row.version < 0 || row.version > 2147483647 || !Array.isArray(row.proofs)) {
            throw new Error('待审数据状态、版本或证明材料异常')
          }
          ids.add(id)
          return { ...row, id, merchantId: claimId(row.merchantId), claimantMemberId: claimId(row.claimantMemberId) }
        })
        this.rows = rows
        this.selected = []
        this.error = ''
        return true
      } catch (e) {
        this.error = `待审队列读取失败：${e.message || '请检查网络和权限后重试'}`
        return false
      } finally {
        this.loading = false
      }
    },
    selectable() { return this.canSubmit },
    evidence(row) {
      return [{ kind: 'text', content: `认领编号：${row.id}\n商家编号：${row.merchantId}\n申请会员编号：${row.claimantMemberId}\n联系人：${row.contactName || '未提供'}\n电话：${row.contactPhone || '未提供'}\n申请说明：${row.statement || '未提供'}\n状态：${row.status} · 版本：${row.version}\n证明材料：${row.proofs.length} 份` }]
    },
    proofUrl(value) {
      const url = typeof value === 'string' ? value.trim() : ''
      return /^(https?:\/\/[^\s]+|\/(?!\/)[^\s]*)$/.test(url) ? url : ''
    },
    async prepare(rows, action, batch) {
      if (!this.canSubmit || !rows.length || rows.length > 100 || !['APPROVE', 'REJECT'].includes(action)) return
      const current = rows.map(row => this.rows.find(item => item.id === row.id && item.version === row.version))
      if (current.some(row => !row)) { this.error = '申请已变化，请刷新后重新选择'; return }
      this.preparing = true
      this.draft = { rows: current, action, batch }
      if (action === 'REJECT') { this.rejectOpen = true; return }
      try {
        await this.$modal.confirm(`确认通过这 ${current.length} 条商家认领？通过后将绑定申请人为商家所有者。请先核对证明材料。`)
        await this.startCommand('')
      } catch (e) {
        // Confirmation cancellation does not submit a command.
      } finally {
        this.preparing = false
        this.draft = null
      }
    },
    async submitReject(reason) {
      if (!this.canAudit || this.busy || this.command || !this.draft) return
      if (typeof reason !== 'string' || !reason.trim() || reason.trim().length > 500) {
        this.$modal.msgError('驳回原因必填，且不能超过 500 字')
        return
      }
      await this.startCommand(reason.trim())
      this.rejectOpen = false
      this.preparing = false
      this.draft = null
    },
    async startCommand(reason) {
      if (!this.canAudit || this.busy || this.command || !this.draft) return
      const draft = this.draft
      const items = draft.rows.map(row => ({ claimId: claimId(row.id), expectedStatus: row.status, expectedVersion: row.version }))
      const random = new Uint32Array(4)
      window.crypto.getRandomValues(random)
      const payload = { requestId: `claim-${Array.from(random, n => n.toString(16).padStart(8, '0')).join('')}`, action: draft.action, reason }
      if (draft.batch) payload.items = items
      else Object.assign(payload, items[0])
      this.command = { batch: draft.batch, payload, receipt: null }
      this.results = []
      try {
        this.saveCommand()
      } catch (e) {
        this.command = null
        this.error = '无法保存稳定请求编号，本次未提交。请恢复会话存储后重试。'
        return
      }
      await this.recover()
    },
    saveCommand() {
      if (this.command) sessionStorage.setItem(this.storageKey, JSON.stringify(this.command))
      else sessionStorage.removeItem(this.storageKey)
    },
    receiptResults(command) {
      const items = command.batch ? command.payload.items : [command.payload]
      const targetStatus = command.payload.action === 'APPROVE' ? 'APPROVED' : 'REJECTED'
      const receipt = command.batch ? command.receipt : [{
        claimId: command.receipt.id,
        success: command.receipt.status === targetStatus && command.receipt.auditRequestId === command.payload.requestId && command.receipt.version === command.payload.expectedVersion + 1,
        currentStatus: command.receipt.status, message: '审核响应与请求不一致'
      }]
      if (!Array.isArray(receipt) || receipt.length !== items.length || items.some(item => receipt.filter(result => claimId(result.claimId) === item.claimId).length !== 1)) {
        throw new Error('审核响应项目与原请求不一致')
      }
      if (!command.batch && !receipt[0].success) throw new Error('审核响应未证实原请求的状态和版本')
      if (receipt.some(result => typeof result.success !== 'boolean' || (result.success && result.currentStatus !== targetStatus))) {
        throw new Error('审核响应状态异常')
      }
      return receipt
    },
    async recover() {
      if (!this.canAudit || this.busy || this.loading || !this.command) return
      this.busy = true
      try {
        const command = this.command
        if (!command.receipt) {
          const response = await (command.batch ? auditMerchantClaimBatch(command.payload) : auditMerchantClaim(command.payload))
          if (!response || response.code !== 200 || !response.data) throw new Error('响应未包含可核验的处理结果')
          command.receipt = response.data
          try { this.receiptResults(command) } catch (e) { command.receipt = null; throw e }
          this.saveCommand()
        }
        const receipt = this.receiptResults(command)
        if (!await this.loadPending()) throw new Error('已收到审核结果，但独立回读队列失败；请恢复核验')
        let unresolved = false
        this.results = receipt.map(result => {
          const id = claimId(result.claimId)
          const stillPending = this.rows.some(row => row.id === id)
          if (result.success && stillPending) unresolved = true
          return { ...result, claimId: id, message: result.success
            ? (stillPending ? '返回已处理，但回读仍为待审，请继续核验' : '已处理；独立回读确认已移出待审队列')
            : `${result.code || '处理失败'}：${result.message || '请核实原因'}${stillPending ? '；回读仍待审' : '；当前队列无此申请，请核实状态'}` }
        })
        if (unresolved) throw new Error('部分已处理申请仍在待审队列，尚不能确认完成')
        sessionStorage.removeItem(this.storageKey)
        this.command = null
        this.error = ''
        this.$emit('changed')
      } catch (e) {
        if (e.confirmedRejected) {
          try {
            sessionStorage.removeItem(this.storageKey)
            this.command = null
            await this.loadPending()
            this.error = `审核请求被拒绝：${e.message}。请刷新并核对当前申请后处理。`
          } catch (storageError) {
            this.error = '审核被拒绝，但无法清除保留请求；请恢复会话存储后重试。'
          }
        } else {
          this.error = `结果尚未确认：${e.message || '请求失败或网络中断'}。保留原请求编号，使用恢复按钮核验，不要重新审核。`
        }
      } finally {
        this.busy = false
      }
    },
    close(done) {
      if (this.busy || this.preparing) return
      this.$emit('update:visible', false)
      if (typeof done === 'function') done()
    }
  }
}
</script>

<style scoped>
.claim-toolbar { display: flex; flex-wrap: wrap; gap: 10px; margin: 16px 0; }
.claim-proof { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-top: 12px; overflow-wrap: anywhere; }
</style>
