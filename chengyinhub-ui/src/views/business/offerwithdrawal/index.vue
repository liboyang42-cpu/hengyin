<template>
  <div class="app-container">
    <el-form :inline="true" size="small" @submit.native.prevent>
      <el-form-item label="门店">
        <el-input v-model="query.merchantId" clearable placeholder="门店编号" style="width: 140px" @keyup.enter.native="search" />
      </el-form-item>
      <el-form-item label="供给">
        <el-input v-model="query.offerId" clearable placeholder="供给编号" style="width: 140px" @keyup.enter.native="search" />
      </el-form-item>
      <el-form-item label="结果">
        <el-select v-model="query.outcome" clearable style="width: 150px" @change="search">
          <el-option v-for="(v, k) in outcomes" :key="k" :label="tabText(k)" :value="k" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="search">查询</el-button>
      </el-form-item>
    </el-form>

    <p v-if="listError" class="ow-error">{{ listError }} <el-button type="text" @click="load">重试</el-button></p>
    <el-table v-else v-loading="loading" :data="rows" empty-text="没有供给退出记录" @row-click="openDetail">
      <el-table-column label="编号" width="80">
        <template slot-scope="s">#{{ s.row.id }}</template>
      </el-table-column>
      <el-table-column label="门店" prop="merchantId" width="100" />
      <el-table-column label="供给" prop="offerId" width="90" />
      <el-table-column label="主题 / 章节" width="130">
        <template slot-scope="s">{{ s.row.topicId || '—' }} / {{ s.row.chapterId || '—' }}</template>
      </el-table-column>
      <el-table-column label="结果" width="120">
        <template slot-scope="s"><el-tag size="small" :type="outcomeTag(s.row.outcome)">{{ outcomeLabel(s.row.outcome) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作人" prop="actorId" width="100" />
      <el-table-column label="请求编号" min-width="260">
        <template slot-scope="s"><code class="ow-code">{{ s.row.requestId }}</code></template>
      </el-table-column>
      <el-table-column label="完成时间" width="150">
        <template slot-scope="s">{{ msText(s.row.completedAt) }}</template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="load" />

    <el-drawer :title="detail ? `回执 #${detail.id}` : '回执'" :visible.sync="drawerOpen" size="560px" append-to-body>
      <div v-if="detail" class="ow-drawer">
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="结果">{{ outcomeLabel(detail.outcome) }}</el-descriptions-item>
          <el-descriptions-item label="门店 / 店主">{{ detail.merchantId }} / {{ detail.ownerId }}</el-descriptions-item>
          <el-descriptions-item label="操作人">{{ detail.actorId }}</el-descriptions-item>
          <el-descriptions-item label="供给">{{ detail.offerId }}</el-descriptions-item>
          <el-descriptions-item label="主题 / 章节">{{ detail.topicId || '—' }} / {{ detail.chapterId || '—' }}</el-descriptions-item>
          <el-descriptions-item label="写入时状态">供给 {{ dash(detail.statusAtWrite) }} · 必选 {{ dash(detail.requiredAtWrite) }} · 主题 {{ dash(detail.userStatusAtWrite) }}</el-descriptions-item>
          <el-descriptions-item label="供给更新">{{ msText(detail.offerUpdatedAt) }}</el-descriptions-item>
          <el-descriptions-item label="完成">{{ msText(detail.completedAt) }}</el-descriptions-item>
          <el-descriptions-item label="请求编号"><code class="ow-code">{{ detail.requestId }}</code></el-descriptions-item>
          <el-descriptions-item label="意图摘要"><code class="ow-code">{{ detail.intentSHA256 || '—' }}</code></el-descriptions-item>
          <el-descriptions-item label="快照摘要"><code class="ow-code">{{ detail.expectedSHA256 || '—' }}</code></el-descriptions-item>
          <el-descriptions-item label="原因摘要"><code class="ow-code">{{ detail.reasonSHA256 || '—' }}</code></el-descriptions-item>
        </el-descriptions>
      </div>
    </el-drawer>
  </div>
</template>

<script>
import { listOfferWithdrawals } from '@/api/business/reviewQueues'
import { unwrap, reviewErrorText } from '@/utils/review-queue'

const OUTCOMES = {
  WITHDRAWN: { label: '已退出', tag: 'success' },
  REJECTED_STATE: { label: '状态不允许', tag: 'warning' },
  REJECTED_CHANGED: { label: '快照已变化', tag: 'warning' },
  REJECTED_INPUT: { label: '请求无效', tag: 'info' }
}

function positive(value) {
  const n = Number(String(value || '').trim())
  return Number.isInteger(n) && n > 0 ? n : undefined
}

export default {
  name: 'BusinessOfferWithdrawal',
  data() {
    return {
      outcomes: OUTCOMES,
      loading: false,
      rows: [],
      total: 0,
      counts: {},
      listError: '',
      query: { merchantId: '', offerId: '', outcome: '', pageNum: 1, pageSize: 20 },
      drawerOpen: false,
      detail: null
    }
  },
  created() {
    this.load()
  },
  methods: {
    outcomeLabel(o) { return (OUTCOMES[o] && OUTCOMES[o].label) || o || '—' },
    outcomeTag(o) { return (OUTCOMES[o] && OUTCOMES[o].tag) || 'info' },
    tabText(k) { const n = this.counts[k]; return n ? `${this.outcomeLabel(k)} (${n})` : this.outcomeLabel(k) },
    dash(v) { return v === null || v === undefined ? '—' : v },
    msText(ms) { return ms ? this.parseTime(Number(ms), '{y}-{m}-{d} {h}:{i}:{s}') : '—' },
    search() { this.query.pageNum = 1; this.load() },
    load() {
      const params = {
        merchantId: positive(this.query.merchantId),
        offerId: positive(this.query.offerId),
        outcome: this.query.outcome || undefined,
        pageNum: this.query.pageNum,
        pageSize: this.query.pageSize
      }
      this.loading = true
      this.listError = ''
      listOfferWithdrawals(params).then(unwrap).then(page => {
        this.rows = page.rows || []
        this.total = page.total || 0
        this.counts = page.counts || {}
      }).catch(err => {
        this.rows = []
        this.listError = reviewErrorText(err)
      }).finally(() => { this.loading = false })
    },
    openDetail(row) {
      this.detail = row
      this.drawerOpen = true
    }
  }
}
</script>

<style scoped>
.ow-error { color: #f56c6c; }
.ow-code { font-size: 12px; word-break: break-all; }
.ow-drawer { padding: 0 20px 20px; }
</style>
