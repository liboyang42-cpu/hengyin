<template>
  <div class="app-container">
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button plain size="mini" icon="el-icon-refresh" @click="load">刷新</el-button>
      </el-col>
      <el-col :span="10" class="ws-hint">
        <el-tag v-if="autoReview === true" size="small" type="info">当前为自动审核：提交时由服务端判定，队列通常为空</el-tag>
      </el-col>
    </el-row>

    <p v-if="listError" class="ws-error">{{ listError }} <el-button type="text" @click="load">重试</el-button></p>
    <el-table v-else v-loading="loading" :data="rows" empty-text="没有待审的工坊专业化提交">
      <el-table-column label="编号" width="80">
        <template slot-scope="s">#{{ s.row.id }}</template>
      </el-table-column>
      <el-table-column label="请求" min-width="220">
        <template slot-scope="s">
          <code class="ws-code">{{ s.row.requestId }}</code>
          <div class="ws-sub">模板 {{ s.row.templateId || '—' }} · 作者 {{ s.row.ownerMemberId }}</div>
        </template>
      </el-table-column>
      <el-table-column label="缺少的专业输入" min-width="180" show-overflow-tooltip>
        <template slot-scope="s">{{ missingText(s.row.missingProfessionalInputs) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template slot-scope="s"><el-tag size="small" :type="statusTag(s.row.reviewStatus)">{{ statusLabel(s.row.reviewStatus) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="提交时间" width="140">
        <template slot-scope="s">{{ timeText(s.row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template slot-scope="s">
          <el-button type="text" @click="openEvents(s.row)">记录</el-button>
          <template v-if="canDecide(s.row.reviewStatus)">
            <el-button v-hasPermi="['business:audit:review']" type="text" @click="openAction('approve', s.row)">通过</el-button>
            <el-button v-hasPermi="['business:audit:review']" type="text" class="ws-danger" @click="openAction('reject', s.row)">驳回</el-button>
            <el-button v-if="canEscalate(s.row.reviewStatus)" v-hasPermi="['business:audit:review']" type="text" @click="openAction('escalate', s.row)">升级</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer :title="eventsFor ? `#${eventsFor.id} 审核记录` : '审核记录'" :visible.sync="eventsOpen" size="480px" append-to-body>
      <div class="ws-drawer">
        <el-descriptions v-if="eventsFor" :column="1" size="small" border>
          <el-descriptions-item label="请求">{{ eventsFor.requestId }}</el-descriptions-item>
          <el-descriptions-item label="命令摘要"><code class="ws-code">{{ eventsFor.commandHash || '—' }}</code></el-descriptions-item>
          <el-descriptions-item label="目标主题">{{ eventsFor.targetTopicId || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-timeline v-if="eventsFor && (eventsFor.events || []).length" class="ws-timeline">
          <el-timeline-item v-for="(e, i) in eventsFor.events" :key="i" :timestamp="timeText(e.at)" placement="top">
            {{ statusLabel(e.fromStatus) }} → {{ statusLabel(e.toStatus) }} · {{ actorLabel(e.actorType) }}<span v-if="e.actorId"> {{ e.actorId }}</span>
            <div v-if="e.reason" class="ws-sub">{{ eventReasonText(e.reason) }}</div>
          </el-timeline-item>
        </el-timeline>
        <p v-else class="ws-sub">没有记录</p>
      </div>
    </el-drawer>

    <el-dialog :title="actionTitle" :visible.sync="actionOpen" width="480px" append-to-body>
      <p v-if="action === 'approve'" class="ws-sub">通过会重跑服务端校验并建模板；校验不过将自动驳回。</p>
      <el-form label-width="60px" size="small" @submit.native.prevent>
        <el-form-item :label="action === 'approve' ? '备注' : '原因'">
          <el-input v-model="actionText" type="textarea" :rows="3" :maxlength="reasonMax" show-word-limit />
        </el-form-item>
        <p v-if="actionError" class="ws-error">{{ actionError }}</p>
      </el-form>
      <div slot="footer">
        <el-button :type="action === 'reject' ? 'danger' : 'primary'" :loading="saving" @click="submitAction">确认</el-button>
        <el-button @click="actionOpen = false">取消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listWorkshopReviews, approveWorkshop, rejectWorkshop, escalateWorkshop } from '@/api/business/reviewQueues'
import {
  reviewStatusLabel, reviewStatusTag, canDecide, canEscalate, actorLabel, eventReasonText,
  unwrap, reviewErrorText, timeText, REASON_MAX
} from '@/utils/review-queue'

const ACTION_TITLE = { approve: '通过', reject: '驳回', escalate: '升级' }

export default {
  name: 'BusinessWorkshopReview',
  data() {
    return {
      loading: false,
      rows: [],
      autoReview: null,
      listError: '',
      eventsOpen: false,
      eventsFor: null,
      actionOpen: false,
      action: 'approve',
      actionTarget: null,
      actionText: '',
      actionError: '',
      saving: false,
      reasonMax: REASON_MAX
    }
  },
  computed: {
    actionTitle() {
      return `${ACTION_TITLE[this.action]}${this.actionTarget ? ' · #' + this.actionTarget.id : ''}`
    }
  },
  created() {
    this.load()
  },
  methods: {
    canDecide, canEscalate, actorLabel, eventReasonText, timeText,
    statusLabel: reviewStatusLabel,
    statusTag: reviewStatusTag,
    missingText(value) {
      if (!value) return '—'
      try {
        const list = typeof value === 'string' ? JSON.parse(value) : value
        return Array.isArray(list) && list.length ? list.join('、') : '—'
      } catch (e) {
        return String(value)
      }
    },
    load() {
      this.loading = true
      this.listError = ''
      listWorkshopReviews().then(unwrap).then(data => {
        this.rows = data.items || []
        this.autoReview = data.autoReview
      }).catch(err => {
        this.rows = []
        this.listError = reviewErrorText(err)
      }).finally(() => { this.loading = false })
    },
    openEvents(row) {
      this.eventsFor = row
      this.eventsOpen = true
    },
    openAction(action, row) {
      this.action = action
      this.actionTarget = row
      this.actionText = ''
      this.actionError = ''
      this.actionOpen = true
    },
    submitAction() {
      const text = this.actionText.trim()
      if (this.action !== 'approve' && !text) { this.actionError = '请填写原因'; return }
      const id = this.actionTarget.id
      const call = this.action === 'approve' ? approveWorkshop(id, text)
        : this.action === 'reject' ? rejectWorkshop(id, text) : escalateWorkshop(id, text)
      this.saving = true
      this.actionError = ''
      call.then(unwrap).then(vo => {
        this.actionOpen = false
        const rejectedByCheck = this.action === 'approve' && vo && vo.reviewStatus === 'REJECTED'
        if (rejectedByCheck) this.$modal.msgWarning('服务端校验未通过，已自动驳回')
        else this.$modal.msgSuccess(`已${ACTION_TITLE[this.action]}`)
        this.load()
      }).catch(err => { this.actionError = reviewErrorText(err) }).finally(() => { this.saving = false })
    }
  }
}
</script>

<style scoped>
.ws-error { color: #f56c6c; }
.ws-hint { line-height: 28px; }
.ws-sub { color: #909399; font-size: 12px; }
.ws-danger { color: #f56c6c; }
.ws-code { font-size: 12px; word-break: break-all; }
.ws-drawer { padding: 0 20px 20px; }
.ws-timeline { margin-top: 16px; padding-left: 4px; }
</style>
