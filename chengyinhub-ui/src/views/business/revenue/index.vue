<template>
  <div class="app-container">
    <el-tabs v-model="tab">
      <el-tab-pane label="按订单" name="order">
        <el-form :inline="true" size="small" @submit.native.prevent>
          <el-form-item label="报名订单 ID">
            <el-input v-model="orderId" clearable style="width: 180px" @keyup.enter.native="loadOrder" />
            <p v-if="orderInputError" class="qa-error">{{ orderInputError }}</p>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="loadOrder">查询</el-button>
          </el-form-item>
        </el-form>
        <p v-if="orderError" class="qa-error">{{ orderError }}</p>
        <p v-else-if="orderLoaded && !allocation" class="qa-muted">收入尚未入账</p>
        <template v-else-if="allocation">
          <dl class="qa-facts">
            <div><dt>状态</dt><dd>{{ allocationStatusLabel(allocation.status) }}</dd></div>
            <div><dt>实付</dt><dd>{{ fenText(allocation.paidFen, allocation.currency) }}</dd></div>
            <div><dt>分配基数</dt><dd>{{ fenText(allocation.netFen, allocation.currency) }}</dd></div>
            <div><dt>已冲回</dt><dd>{{ fenText(allocation.reversedFen, allocation.currency) }}</dd></div>
            <div><dt>可结算时间</dt><dd>{{ timeText(allocation.settleableAt) }}</dd></div>
            <div><dt>规则</dt><dd>{{ allocation.ruleKey || '—' }} v{{ allocation.ruleVersion || '—' }}</dd></div>
          </dl>
          <h4>应计分项</h4>
          <el-table :data="accrueLines" size="small" empty-text="没有应计分项">
            <el-table-column label="角色" width="90">
              <template slot-scope="s">{{ lineRoleLabel(s.row.lineRole) }}</template>
            </el-table-column>
            <el-table-column label="收款会员" width="110">
              <template slot-scope="s">{{ s.row.recipientMemberId ? '#' + s.row.recipientMemberId : '—' }}</template>
            </el-table-column>
            <el-table-column label="金额" width="120">
              <template slot-scope="s">{{ fenText(s.row.amountFen, s.row.currency) }}</template>
            </el-table-column>
            <el-table-column label="来源事实" prop="sourceFact" min-width="160" />
            <el-table-column label="时间" width="150">
              <template slot-scope="s">{{ timeText(s.row.createTime) }}</template>
            </el-table-column>
          </el-table>
          <h4>退款冲回</h4>
          <el-table :data="reverseLines" size="small" empty-text="没有冲回">
            <el-table-column label="退款" prop="sourceFact" min-width="160" />
            <el-table-column label="角色" width="90">
              <template slot-scope="s">{{ lineRoleLabel(s.row.lineRole) }}</template>
            </el-table-column>
            <el-table-column label="收款会员" width="110">
              <template slot-scope="s">{{ s.row.recipientMemberId ? '#' + s.row.recipientMemberId : '—' }}</template>
            </el-table-column>
            <el-table-column label="冲回" width="120">
              <template slot-scope="s">{{ fenText(s.row.amountFen, s.row.currency) }}</template>
            </el-table-column>
            <el-table-column label="时间" width="150">
              <template slot-scope="s">{{ timeText(s.row.createTime) }}</template>
            </el-table-column>
          </el-table>
          <h4>投递任务</h4>
          <p v-if="taskError" class="qa-error">{{ taskError }}</p>
          <el-table v-else :data="tasks" size="small" empty-text="没有投递任务">
            <el-table-column label="任务" prop="id" width="90" />
            <el-table-column label="类型" width="120">
              <template slot-scope="s">{{ taskKindLabel(s.row.taskKind) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template slot-scope="s">{{ taskStatusLabel(s.row.status) }}</template>
            </el-table-column>
            <el-table-column label="尝试" prop="attempts" width="70" />
            <el-table-column label="原因码" prop="reasonCode" min-width="160" />
          </el-table>
        </template>
      </el-tab-pane>

      <el-tab-pane label="按作者" name="author">
        <el-form :inline="true" size="small" @submit.native.prevent>
          <el-form-item label="作者会员 ID">
            <el-input v-model="authorId" clearable style="width: 180px" @keyup.enter.native="loadAuthor" />
            <p v-if="authorInputError" class="qa-error">{{ authorInputError }}</p>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="loadAuthor">查询</el-button>
          </el-form-item>
        </el-form>
        <p v-if="authorError" class="qa-error">{{ authorError }}</p>
        <el-table v-else v-loading="loading" :data="authorRows" :empty-text="authorLoaded ? '收入尚未入账' : '输入作者会员 ID 查询'">
          <el-table-column label="订单" width="110">
            <template slot-scope="s">#{{ s.row.orderId }}</template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template slot-scope="s">{{ allocationStatusLabel(s.row.status) }}</template>
          </el-table-column>
          <el-table-column label="实付" width="110">
            <template slot-scope="s">{{ fenText(s.row.paidFen, s.row.currency) }}</template>
          </el-table-column>
          <el-table-column label="已冲回" width="110">
            <template slot-scope="s">{{ fenText(s.row.reversedFen, s.row.currency) }}</template>
          </el-table-column>
          <el-table-column label="可结算时间" width="150">
            <template slot-scope="s">{{ timeText(s.row.settleableAt) }}</template>
          </el-table-column>
          <el-table-column label="合同" prop="contractRef" min-width="140" />
          <el-table-column label="操作" width="80">
            <template slot-scope="s">
              <el-button type="text" @click="openOrder(s.row.orderId)">分项</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import { getAllocationByOrder, listAllocationsByAuthor, listPostingTasks } from '@/api/business/revenue'
import { businessError, fenText, allocationStatusLabel, lineRoleLabel } from '@/utils/questo-admin'

const ORDER_REGISTRATION = 1
const TASK_KIND = { 1: '应计', 2: '退款冲回', 3: '渠道冲回' }
const TASK_STATUS = { 0: '待处理', 1: '已完成', 2: '失败', 3: '处理中' }

function message(err) {
  const e = businessError(err)
  return Number(e.code) === 403 ? '该功能尚未对当前角色开放' : e.message
}

export default {
  name: 'BusinessRevenue',
  data() {
    return {
      tab: 'order',
      loading: false,
      orderId: '',
      orderInputError: '',
      orderError: '',
      orderLoaded: false,
      allocation: null,
      lines: [],
      tasks: [],
      taskError: '',
      authorId: '',
      authorInputError: '',
      authorError: '',
      authorLoaded: false,
      authorRows: []
    }
  },
  computed: {
    accrueLines() { return this.lines.filter(line => Number(line.entryType) !== 2) },
    reverseLines() { return this.lines.filter(line => Number(line.entryType) === 2) }
  },
  methods: {
    fenText, allocationStatusLabel, lineRoleLabel,
    taskKindLabel(kind) { return TASK_KIND[kind] || '—' },
    taskStatusLabel(status) { return TASK_STATUS[status] || '状态待确认' },
    timeText(value) { return value ? this.parseTime(value, '{y}-{m}-{d} {h}:{i}') : '—' },
    loadOrder() {
      this.orderInputError = ''
      this.orderError = ''
      const id = String(this.orderId).trim()
      if (!/^\d+$/.test(id)) {
        this.orderInputError = '请输入数字订单 ID'
        return
      }
      this.orderLoaded = false
      this.allocation = null
      this.lines = []
      getAllocationByOrder(ORDER_REGISTRATION, id).then(res => {
        this.allocation = res.data || null
        this.lines = res.lines || []
        this.orderLoaded = true
      }).catch(err => { this.orderError = message(err) })
      this.taskError = ''
      listPostingTasks(id).then(res => { this.tasks = res.data || [] }).catch(err => {
        this.tasks = []
        this.taskError = message(err)
      })
    },
    loadAuthor() {
      this.authorInputError = ''
      this.authorError = ''
      const id = String(this.authorId).trim()
      if (!/^\d+$/.test(id)) {
        this.authorInputError = '请输入数字会员 ID'
        return
      }
      this.loading = true
      listAllocationsByAuthor(id).then(res => {
        this.authorRows = res.data || []
        this.authorLoaded = true
      }).catch(err => {
        this.authorRows = []
        this.authorError = message(err)
      }).finally(() => { this.loading = false })
    },
    openOrder(orderId) {
      this.tab = 'order'
      this.orderId = String(orderId)
      this.loadOrder()
    }
  }
}
</script>

<style scoped>
.qa-error { color: #F56C6C; font-size: 12px; margin: 4px 0 8px; line-height: 1.4; }
.qa-muted { color: #909399; }
.qa-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px 24px; margin: 0 0 16px; }
.qa-facts dt { color: #909399; font-size: 12px; }
.qa-facts dd { margin: 2px 0 0; }
h4 { margin: 16px 0 8px; font-size: 13px; }
</style>
