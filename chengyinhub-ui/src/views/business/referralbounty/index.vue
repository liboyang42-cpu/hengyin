<template>
  <div class="app-container">
    <el-form :inline="true" size="small" @submit.native.prevent>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width: 140px" @change="search">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="拓展人 ID">
        <el-input v-model="query.inviterMemberId" clearable style="width: 140px" @keyup.enter.native="search" />
      </el-form-item>
      <el-form-item label="商家 ID">
        <el-input v-model="query.merchantMemberId" clearable style="width: 140px" @keyup.enter.native="search" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="search">查询</el-button>
      </el-form-item>
    </el-form>
    <el-alert v-if="moneyOpen === false" type="info" :closable="false" show-icon
              title="资金开关关闭：可以驳回，暂不能审核通过发放" style="margin-bottom: 8px" />
    <p v-if="loadError" class="qa-error">{{ loadError }} <el-button type="text" @click="load">重试</el-button></p>
    <el-table v-else v-loading="loading" :data="rows" empty-text="没有符合条件的赏金记录">
      <el-table-column label="记录" width="80">
        <template slot-scope="s">#{{ s.row.id }}</template>
      </el-table-column>
      <el-table-column label="拓展人" width="100">
        <template slot-scope="s">#{{ s.row.inviterMemberId }}</template>
      </el-table-column>
      <el-table-column label="商家" width="100">
        <template slot-scope="s">#{{ s.row.merchantMemberId }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template slot-scope="s">{{ statusLabel(s.row.status) }}</template>
      </el-table-column>
      <el-table-column label="金额" width="90">
        <template slot-scope="s">{{ s.row.bountyAmount === null || s.row.bountyAmount === undefined ? '—' : '¥' + s.row.bountyAmount }}</template>
      </el-table-column>
      <el-table-column label="达成时间" width="150">
        <template slot-scope="s">{{ timeText(s.row.activatedAt) }}</template>
      </el-table-column>
      <el-table-column label="构成激活的报名" prop="verifiedHeadsSnapshot" min-width="140" />
      <el-table-column label="审核" min-width="160">
        <template slot-scope="s">{{ s.row.reviewBy ? s.row.reviewBy + ' · ' + (s.row.reviewNote || '') : '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template slot-scope="s">
          <el-button v-if="s.row.status === 4" v-hasPermi="['business:referralbounty:review']" type="text"
                     :disabled="moneyOpen !== true" @click="approve(s.row)">通过并发放</el-button>
          <el-button v-if="s.row.status === 0 || s.row.status === 4" v-hasPermi="['business:referralbounty:review']"
                     type="text" @click="reject(s.row)">驳回</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="load" />
  </div>
</template>

<script>
import { listReferralBounties, getReferralBountyMoneySwitch, approveReferralBounty, rejectReferralBounty } from '@/api/business/referralbounty'
import { businessError } from '@/utils/questo-admin'

const STATUS = { 0: '待激活', 1: '已发放', 2: '已追回', 3: '已失效', 4: '待审核', 5: '已驳回' }

export default {
  name: 'BusinessReferralBounty',
  data() {
    return {
      query: { pageNum: 1, pageSize: 20, status: 4, inviterMemberId: '', merchantMemberId: '' },
      statusOptions: Object.keys(STATUS).map(k => ({ value: Number(k), label: STATUS[k] })),
      rows: [], total: 0, loading: false, loadError: '', moneyOpen: null
    }
  },
  created() {
    getReferralBountyMoneySwitch().then(res => { this.moneyOpen = !!(res.data && res.data.open) }).catch(() => { this.moneyOpen = null })
    this.load()
  },
  methods: {
    statusLabel(status) { return STATUS[status] || '状态待确认' },
    timeText(value) { return value ? this.parseTime(value, '{y}-{m}-{d} {h}:{i}') : '—' },
    search() { this.query.pageNum = 1; this.load() },
    params() {
      const p = { pageNum: this.query.pageNum, pageSize: this.query.pageSize }
      if (this.query.status !== '' && this.query.status !== null && this.query.status !== undefined) p.status = this.query.status
      if (/^\d+$/.test(String(this.query.inviterMemberId).trim())) p.inviterMemberId = String(this.query.inviterMemberId).trim()
      if (/^\d+$/.test(String(this.query.merchantMemberId).trim())) p.merchantMemberId = String(this.query.merchantMemberId).trim()
      return p
    },
    load() {
      this.loading = true
      this.loadError = ''
      listReferralBounties(this.params()).then(res => {
        this.rows = res.rows || []
        this.total = res.total || 0
      }).catch(err => {
        this.rows = []
        this.loadError = businessError(err).message
      }).finally(() => { this.loading = false })
    },
    approve(row) {
      this.$prompt('确认后给拓展人 #' + row.inviterMemberId + ' 入账 ¥' + row.bountyAmount + '（48 小时内不可提现）', '通过并发放', {
        inputPlaceholder: '备注（可选）', confirmButtonText: '通过并发放', cancelButtonText: '取消'
      }).then(({ value }) => approveReferralBounty(row.id, value)).then(() => {
        this.$modal.msgSuccess('已发放')
        this.load()
      }).catch(err => { if (err !== 'cancel') this.$modal.msgError(businessError(err).message) })
    },
    reject(row) {
      this.$prompt('驳回后不会入账，且不能撤回', '驳回赏金', {
        inputPlaceholder: '驳回原因（必填）', inputValidator: v => !!(v && v.trim()) || '请填写驳回原因',
        confirmButtonText: '驳回', cancelButtonText: '取消'
      }).then(({ value }) => rejectReferralBounty(row.id, value)).then(() => {
        this.$modal.msgSuccess('已驳回')
        this.load()
      }).catch(err => { if (err !== 'cancel') this.$modal.msgError(businessError(err).message) })
    }
  }
}
</script>

<style scoped>
.qa-error { color: #F56C6C; font-size: 12px; margin: 4px 0 8px; }
</style>
