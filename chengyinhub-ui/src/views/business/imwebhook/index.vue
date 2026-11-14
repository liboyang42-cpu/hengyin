<template>
  <div class="app-container">
    <el-tabs v-model="state" @tab-click="search">
      <el-tab-pane v-for="t in tabs" :key="t" :name="t" :label="tabLabel(t)" />
    </el-tabs>
    <el-form :inline="true" size="small" @submit.native.prevent>
      <el-form-item label="订阅">
        <el-input v-model="subscriptionId" clearable placeholder="订阅编号" style="width: 140px" @keyup.enter.native="search" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="search">查询</el-button>
      </el-form-item>
    </el-form>

    <p v-if="listError" class="wh-error">{{ listError }} <el-button type="text" @click="load">重试</el-button></p>
    <el-table v-else v-loading="loading" :data="rows" empty-text="没有投递记录">
      <el-table-column label="投递" width="80">
        <template slot-scope="s">#{{ s.row.id }}</template>
      </el-table-column>
      <el-table-column label="订阅" min-width="180">
        <template slot-scope="s">
          <div>{{ s.row.subscription_name || '—' }} <span class="wh-sub">#{{ s.row.subscription_id }}</span></div>
          <div class="wh-sub">{{ s.row.url_host || '—' }}<el-tag v-if="!s.row.subscription_active" size="mini" type="info" class="wh-tag">已停用</el-tag></div>
        </template>
      </el-table-column>
      <el-table-column label="频道" width="140">
        <template slot-scope="s">{{ s.row.owner_type }} {{ s.row.owner_id }}<div class="wh-sub">频道 {{ s.row.channel_id }}</div></template>
      </el-table-column>
      <el-table-column label="事件" prop="event_type" width="150" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template slot-scope="s"><el-tag size="small" :type="stateTag(s.row.state)">{{ stateLabel(s.row.state) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="尝试" width="70">
        <template slot-scope="s">{{ s.row.attempt }}/{{ maxAttempt }}</template>
      </el-table-column>
      <el-table-column label="最后结果" min-width="180" show-overflow-tooltip>
        <template slot-scope="s">
          <span v-if="s.row.http_status">HTTP {{ s.row.http_status }} · </span>{{ s.row.reason || '—' }}
          <div v-if="s.row.response_snippet" class="wh-sub">{{ s.row.response_snippet }}</div>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="150">
        <template slot-scope="s">{{ shortTime(s.row.updated_at || s.row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template slot-scope="s">
          <el-button v-if="s.row.retryable" v-hasPermi="['business:audit:review']" type="text" @click="redrive(s.row)">重新投递</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="load" />
  </div>
</template>

<script>
import { listWebhookDeliveries, redriveWebhookDelivery } from '@/api/business/reviewQueues'
import { unwrap, reviewErrorText } from '@/utils/review-queue'

const STATES = {
  DEAD: { label: '死信', tag: 'danger' },
  FAILED: { label: '失败', tag: 'warning' },
  UNKNOWN: { label: '结果未知', tag: 'warning' },
  PENDING: { label: '待投递', tag: '' },
  INFLIGHT: { label: '投递中', tag: '' },
  SENT: { label: '已送达', tag: 'success' },
  CANCELLED: { label: '已取消', tag: 'info' },
  ALL: { label: '全部', tag: '' }
}
const TABS = ['DEAD', 'FAILED', 'UNKNOWN', 'PENDING', 'SENT', 'ALL']
const REDRIVE_ERRORS = {
  DELIVERY_NOT_RETRYABLE: '该投递已变化或不可重试，已刷新',
  WEBHOOK_PAUSED: '订阅已暂停，不能重新投递',
  WEBHOOK_NOT_FOUND: '订阅已删除'
}

export default {
  name: 'BusinessImWebhookDelivery',
  data() {
    return {
      tabs: TABS,
      state: 'DEAD',
      subscriptionId: '',
      loading: false,
      rows: [],
      total: 0,
      counts: {},
      maxAttempt: 5,
      listError: '',
      query: { pageNum: 1, pageSize: 20 }
    }
  },
  created() {
    this.load()
  },
  methods: {
    stateLabel(s) { return (STATES[s] && STATES[s].label) || s || '—' },
    stateTag(s) { return (STATES[s] && STATES[s].tag) || 'info' },
    tabLabel(t) {
      const n = t === 'ALL' ? 0 : Number(this.counts[t]) || 0
      return n ? `${this.stateLabel(t)} (${n})` : this.stateLabel(t)
    },
    shortTime(v) { return v ? String(v).slice(0, 16) : '—' },
    search() { this.query.pageNum = 1; this.load() },
    load() {
      const sub = Number(String(this.subscriptionId || '').trim())
      const params = { ...this.query, state: this.state, subscriptionId: Number.isInteger(sub) && sub > 0 ? sub : undefined }
      this.loading = true
      this.listError = ''
      listWebhookDeliveries(params).then(unwrap).then(page => {
        this.rows = page.rows || []
        this.total = page.total || 0
        this.counts = page.counts || {}
        if (page.maxRetryAttempt) this.maxAttempt = page.maxRetryAttempt
      }).catch(err => {
        this.rows = []
        this.listError = reviewErrorText(err)
      }).finally(() => { this.loading = false })
    },
    redrive(row) {
      const tip = row.state === 'DEAD' ? '死信重新投递会把尝试次数归零。' : ''
      this.$modal.confirm(`${tip}确认重新投递 #${row.id}？`).then(() => {
        return redriveWebhookDelivery(row.id).then(unwrap).then(() => {
          this.$modal.msgSuccess('已重新排队')
          this.load()
        }).catch(err => {
          const code = err && typeof err === 'object' ? err.errorCode : null
          this.$modal.msgError(REDRIVE_ERRORS[code] || reviewErrorText(err))
          if (code === 'DELIVERY_NOT_RETRYABLE') this.load()
        })
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.wh-error { color: #f56c6c; }
.wh-sub { color: #909399; font-size: 12px; }
.wh-tag { margin-left: 6px; }
</style>
