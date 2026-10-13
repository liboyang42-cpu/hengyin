<template>
  <section class="cy-panel" aria-label="俱乐部直付计酬">
    <div class="cy-panel__heading"><h2>俱乐部直付计酬</h2><span>俱乐部直接付款</span></div>
    <el-form inline @submit.native.prevent="load">
      <el-form-item label="主题编号"><el-input v-model.trim="topicId" placeholder="输入主题编号" /></el-form-item>
      <el-button :loading="loading" @click="load">查询直付账目</el-button>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-else-if="loaded">
      <p>有效核销 {{ activeCount }} 人次 · 待收 ¥{{ sum(['OPEN']) }} · 已结清 ¥{{ sum(['SETTLED']) }} · 待退回 ¥{{ sum(['REFUND_DUE']) }}</p>
      <el-table v-loading="loading" :data="rows" empty-text="本主题暂无俱乐部直付核销">
        <el-table-column prop="payerClubId" label="付款俱乐部" />
        <el-table-column prop="merchantMemberId" label="收款商家" />
        <el-table-column prop="redemptionId" label="核销编号" />
        <el-table-column prop="perHeadFeeSnapshot" label="应收金额" />
        <el-table-column label="状态"><template slot-scope="s">{{ label(s.row.status) }}</template></el-table-column>
        <el-table-column prop="payVoucherNo" label="付款凭证" />
        <el-table-column prop="refundVoucherNo" label="退回凭证" />
        <el-table-column label="记录动作" width="150">
          <template slot-scope="s">
            <el-button v-if="s.row.status === 'OPEN' && Number(s.row.perHeadFeeSnapshot) > 0" v-hasPermi="['business:payablebatch:edit']" type="text" @click="open(s.row, false)">记录已结清</el-button>
            <el-button v-if="s.row.status === 'REFUND_DUE'" v-hasPermi="['business:payablebatch:edit']" type="text" @click="open(s.row, true)">记录已退回</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <el-dialog :title="refund ? '记录商家已退回款项' : '记录俱乐部已付款'" :visible.sync="dialog" width="460px" append-to-body>
      <template v-if="selected">
        <p>俱乐部 {{ selected.payerClubId }} → 商家 {{ selected.merchantMemberId }} · ¥{{ selected.perHeadFeeSnapshot }}</p>
        <el-form label-position="top"><el-form-item label="已发生付款的凭证编号"><el-input v-model.trim="voucher" maxlength="128" /></el-form-item></el-form>
      </template>
      <span slot="footer"><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="record">确认记录</el-button></span>
    </el-dialog>
  </section>
</template>

<script>
import { listClubDirectReceivables, recordClubDirectPayment } from '@/api/business/payablebatch'
export default {
  name: 'ClubDirectReceivables',
  data() { return { topicId: '', rows: [], loaded: false, loading: false, error: '', dialog: false, selected: null, refund: false, voucher: '', saving: false } },
  computed: { activeCount() { return this.rows.filter(r => ['OPEN', 'SETTLED'].includes(r.status)).length } },
  methods: {
    label(status) { return { OPEN: '待结清', SETTLED: '已结清', VOID: '核销已撤销', REFUND_DUE: '撤销后待退回', REFUNDED: '已退回' }[status] || status },
    sum(states) { return (this.rows.filter(r => states.includes(r.status)).reduce((total, r) => total + Math.round(Number(r.perHeadFeeSnapshot) * 100), 0) / 100).toFixed(2) },
    async load() {
      if (!/^[1-9]\d*$/.test(this.topicId)) return this.$message.error('请输入有效主题编号')
      this.loading = true; this.error = ''; this.rows = []; this.loaded = false
      try {
        const result = await listClubDirectReceivables(this.topicId)
        if (!Array.isArray(result.data)) throw new Error('账目数据格式错误')
        this.rows = result.data; this.loaded = true
      } catch (e) { this.error = e.message || '直付账目读取失败，请重试' }
      finally { this.loading = false }
    },
    open(row, refund) { this.selected = row; this.refund = refund; this.voucher = ''; this.dialog = true },
    async record() {
      if (!this.voucher) return this.$message.error('请填写付款凭证编号')
      this.saving = true
      try {
        const r = this.selected
        await recordClubDirectPayment(r.id, { topicId: r.topicId, payerClubId: r.payerClubId, merchantMemberId: r.merchantMemberId, amount: r.perHeadFeeSnapshot, voucherNo: this.voucher, refund: this.refund })
        this.dialog = false; await this.load()
      } finally { this.saving = false }
    }
  }
}
</script>
