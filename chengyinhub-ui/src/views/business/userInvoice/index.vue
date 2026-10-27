<template>
  <div class="app-container">
    <el-alert title="仅显示获授权开票主体。此页记录人工处理事实，不调用外部开票。退款核对期间请勿继续开具。" type="info" :closable="false" />
    <el-button :loading="loading" @click="load()">刷新列表</el-button>
    <el-table :data="rows" @row-click="open">
      <el-table-column prop="applicationNo" label="申请号" />
      <el-table-column prop="issuerName" label="开票主体" />
      <el-table-column prop="titleName" label="抬头" />
      <el-table-column prop="taxNoMasked" label="税号（掩码）" />
      <el-table-column prop="status" label="状态" />
      <el-table-column prop="totalAmount" label="申请金额" />
    </el-table>
    <el-button :disabled="rows.length < 50 || loading" @click="load(rows[rows.length - 1].id)">下一页</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="pending" :title="'待确认请求：' + pending.requestId + '。未查到回执不能证明未执行。'" type="warning" :closable="false" />
    <el-button v-if="pending" :loading="busy" @click="recover">查询原请求回执</el-button>
    <el-button v-if="pending && frozen" :loading="busy" @click="sendFrozen">重试原请求和原输入</el-button>
    <el-button v-if="pending" :loading="busy" @click="closeOriginal">封存原请求并回读</el-button>
    <section v-if="detail">
      <h3>{{ detail.applicationNo }} · {{ detail.issuerName }}</h3>
      <el-button v-hasPermi="['business:userInvoice:process']" :loading="busy" @click="refreshRefundFacts">刷新退款事实与核对状态</el-button>
      <p>状态：{{ detail.status }}；处理人编号：{{ detail.handlerId || '未认领' }}；核对：{{ detail.reconciliationStatus }}；原处理号：{{ detail.operationId || '尚未开始' }}</p>
      <p>抬头：{{ detail.titleName }}；税号：{{ taxNo || detail.taxNoMasked }}</p>
      <el-button v-hasPermi="['business:userInvoice:taxView']" :disabled="busy" @click="reveal">受审计查看完整税号</el-button>
      <el-button v-if="taxNo" @click="taxNo = ''">隐藏税号</el-button>
      <p>完整税号仅供本次指定申请核对；离开或隐藏页面立即清除。</p>
      <el-table :data="detail.orders"><el-table-column prop="orderId" label="订单" /><el-table-column prop="amount" label="金额" /></el-table>
      <el-form label-width="130px">
        <el-form-item label="已核验发票号码"><el-input v-model="invoiceNo" :disabled="!!pending" maxlength="64" /></el-form-item>
        <el-form-item label="核对记录编号"><el-input v-model="evidenceRef" :disabled="!!pending" maxlength="128" placeholder="填写受控证据记录编号，勿填税号或票据正文" /></el-form-item>
        <el-form-item label="人工核对结果">
          <el-select v-model="outcome" :disabled="!!pending">
            <el-option label="尚未查明，保留暂停" value="UNRESOLVED" />
            <el-option label="证据确认未开具，关闭并释放占用" value="NO_EXTERNAL_ISSUE" />
            <el-option label="发现已开具，保留占用转税务复核" value="EXTERNAL_ISSUED" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-hasPermi="['business:userInvoice:process']">
        <el-button :disabled="blocked || detail.status !== 'SUBMITTED'" @click="act('CLAIM')">认领</el-button>
        <el-button :disabled="blocked || detail.status !== 'CLAIMED'" @click="act('START')">登记开始人工开票</el-button>
        <el-button :disabled="blocked || detail.status !== 'PROCESSING'" @click="act('UNKNOWN')">外部结果未知</el-button>
        <el-button :disabled="blocked || !['PROCESSING', 'ISSUE_UNKNOWN'].includes(detail.status)" @click="act('RECORD_ISSUED')">凭证确认已开具</el-button>
        <el-button :disabled="blocked || !['SUBMITTED', 'CLAIMED'].includes(detail.status)" @click="act('REJECT')">资料核对未通过</el-button>
      </div>
      <el-button v-hasPermi="['business:userInvoice:reconcile']" :disabled="blocked || detail.status !== 'PAUSED'" @click="act('RECONCILE')">记录人工核对结果</el-button>
    </section>
  </div>
</template>
<script>
import request from '@/utils/request'

// Never persist tax numbers, invoice input or full responses. Only original command identity survives navigation.
export default {
  name: 'UserInvoiceProcessing',
  data() { return { rows: [], detail: null, loading: false, busy: false, error: '', taxNo: '',
    invoiceNo: '', evidenceRef: '', outcome: 'UNRESOLVED', pending: null, frozen: null, epoch: 0, owner: '' } },
  computed: { blocked() { return this.busy || !!this.pending } },
  created() { this.owner = this.identity(); this.restore(); this.load() },
  mounted() { document.addEventListener('visibilitychange', this.hidden) },
  beforeDestroy() { this.clearView(); document.removeEventListener('visibilitychange', this.hidden) },
  watch: { '$store.getters.id'() { this.clearView(); this.owner = this.identity(); this.restore(); this.load() } },
  methods: {
    identity() { return String(this.$store.getters.id || '') },
    key() { return 'user-invoice-command:' + this.owner },
    active(epoch) { return epoch === this.epoch && this.owner === this.identity() },
    clearView() { this.epoch++; this.taxNo = ''; this.invoiceNo = ''; this.evidenceRef = ''; this.rows = []; this.detail = null; this.pending = null; this.frozen = null; this.busy = false; this.loading = false },
    hidden() { if (document.hidden) { this.epoch++; this.taxNo = ''; this.frozen = null; this.invoiceNo = ''; this.evidenceRef = ''; this.busy = false; this.loading = false } },
    restore() {
      try {
        const p = JSON.parse(sessionStorage.getItem(this.key()) || 'null')
        if (p && /^[1-9]\d{0,18}$/.test(p.id) && /^[A-Za-z0-9_-]{1,64}$/.test(p.requestId)) this.pending = p
      } catch (_) { this.error = '原请求记录无法读取，请先核对处理记录' }
    },
    newRequest() { return 'inv_' + crypto.randomUUID().replace(/-/g, '') },
    async load(before) {
      const epoch = this.epoch; this.loading = true
      try { const r = await request({ url: '/business/userInvoice/list', params: before ? { before } : {} }); if (this.active(epoch)) this.rows = r.data || [] }
      catch (_) { if (this.active(epoch)) this.error = '列表读取失败，请重试' }
      finally { if (this.active(epoch)) this.loading = false }
    },
    async open(row) {
      if (this.busy) return
      const epoch = ++this.epoch; this.taxNo = ''; this.detail = null; this.error = ''
      this.invoiceNo = ''; this.evidenceRef = ''; this.loading = false
      try { const r = await request({ url: '/business/userInvoice/' + row.id }); if (this.active(epoch)) this.detail = r.data }
      catch (_) { if (this.active(epoch)) this.error = '详情不可读，请确认主体授权及配置' }
    },
    async refreshRefundFacts() {
      if (!this.detail || this.busy) return
      const epoch = this.epoch, id = this.detail.id; this.busy = true; this.taxNo = ''
      try {
        const r = await request({ url: '/business/userInvoice/' + id + '/refresh-refund-facts', method: 'post', headers: { repeatSubmit: false } })
        if (this.active(epoch) && r.data.id === id) this.detail = r.data
      } catch (_) { if (this.active(epoch)) this.error = '退款事实刷新结果未确认，请重试；原处理号与申请请求仍保留' }
      finally { if (this.active(epoch)) this.busy = false }
    },
    async reveal() {
      if (!this.detail || this.busy) return
      const epoch = this.epoch, id = this.detail.id; this.taxNo = ''; this.busy = true
      try {
        const r = await request({ url: '/business/userInvoice/' + id + '/tax-number', method: 'post', data: { requestId: this.newRequest() } })
        if (this.active(epoch) && this.detail && this.detail.id === id && r.data.id === id) this.taxNo = r.data.taxNo || ''
      } catch (_) { if (this.active(epoch)) this.error = '完整税号查看未成功，请确认专用权限' }
      finally { if (this.active(epoch)) this.busy = false }
    },
    async act(action) {
      if (this.busy || this.pending || !this.detail) return
      const epoch = this.epoch
      try { await this.$confirm('确认记录本次人工处理事实？外部结果不明时请保留原处理号，不要重新开具。', '发票处理') } catch (_) { return }
      if (!this.active(epoch) || this.pending || !this.detail) return
      const c = { requestId: this.newRequest(), version: this.detail.version, action,
        operationId: this.detail.operationId || null, invoiceNo: action === 'RECORD_ISSUED' ? this.invoiceNo : null,
        evidenceRef: ['RECORD_ISSUED', 'REJECT', 'RECONCILE'].includes(action) ? this.evidenceRef : null,
        outcome: action === 'RECONCILE' ? this.outcome : null }
      if (['RECORD_ISSUED', 'REJECT', 'RECONCILE'].includes(action) && !/^[A-Za-z0-9_:/.-]{1,128}$/.test(c.evidenceRef || '')) {
        this.error = '请填写受控核对记录编号，不提交税号或票据正文'; return
      }
      if (action === 'RECORD_ISSUED' && !/^[A-Za-z0-9-]{1,64}$/.test(c.invoiceNo || '')) { this.error = '请填写已核验的发票号码'; return }
      const p = { id: this.detail.id, requestId: c.requestId, action }
      try { sessionStorage.setItem(this.key(), JSON.stringify(p)) } catch (_) { this.error = '无法保存原请求号，尚未发送'; return }
      this.pending = p; this.frozen = Object.freeze(c); this.taxNo = ''
      await this.sendFrozen()
    },
    confirm(r) {
      if (!this.pending || !r || r.id !== this.pending.id || r.requestId !== this.pending.requestId || r.action !== this.pending.action) throw Error('回执不匹配')
      const id = this.pending.id
      sessionStorage.removeItem(this.key()); this.pending = null; this.frozen = null; this.error = ''; this.busy = false
      return this.open({ id }).then(() => { if (['DECLINED', 'FENCED'].includes(r.status)) this.error = '本次原请求已拒绝或封存，请依据最新状态重新确认' })
    },
    async sendFrozen() {
      if (!this.pending || !this.frozen || this.busy) return
      const epoch = this.epoch, p = { ...this.pending }; this.busy = true
      try {
        const r = await request({ url: '/business/userInvoice/' + p.id + (p.action === 'RECONCILE' ? '/reconcile' : '/commands'), method: 'post', data: { ...this.frozen }, headers: { repeatSubmit: false } })
        if (this.active(epoch)) await this.confirm(r.data)
      } catch (_) { if (this.active(epoch)) this.error = '操作结果未确认；保留原请求和冻结输入，请查询原回执' }
      finally { if (this.active(epoch)) this.busy = false }
    },
    async closeOriginal() {
      if (!this.pending || this.busy) return
      const epoch = this.epoch, p = { ...this.pending }
      try { await this.$confirm('封存该原请求号；如已执行，将返回原回执。外部处理号及订单占用保持，请依据最新状态继续核对。', '封存原请求') } catch (_) { return }
      if (!this.active(epoch) || !this.pending || this.pending.requestId !== p.requestId) return
      this.busy = true
      try {
        const r = await request({ url: '/business/userInvoice/' + p.id + '/requests/' + p.requestId + (p.action === 'RECONCILE' ? '/close-reconcile' : '/close'),
          method: 'post', data: { action: p.action }, headers: { repeatSubmit: false } })
        if (this.active(epoch)) await this.confirm(r.data)
      } catch (_) { if (this.active(epoch)) this.error = '封存结果未确认，保留原请求号继续查询' }
      finally { if (this.active(epoch)) this.busy = false }
    },
    async recover() {
      if (!this.pending || this.busy) return
      const epoch = this.epoch, p = { ...this.pending }; this.busy = true
      try {
        const r = await request({ url: '/business/userInvoice/' + p.id + '/requests/' + p.requestId })
        if (this.active(epoch)) await this.confirm(r.data)
      } catch (_) { if (this.active(epoch)) this.error = '未确认原请求；不能据此开始另一请求，请保留请求号核对' }
      finally { if (this.active(epoch)) this.busy = false }
    }
  }
}
</script>
