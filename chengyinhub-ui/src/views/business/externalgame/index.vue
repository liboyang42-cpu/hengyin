<template>
  <div class="app-container">
    <el-tabs v-model="tab" @tab-click="search">
      <el-tab-pane v-for="t in tabs" :key="t.key" :name="t.key" :label="tabLabel(t)" />
    </el-tabs>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button v-hasPermi="['business:audit:review']" plain size="mini" icon="el-icon-alarm-clock" :loading="sweeping" @click="sweep">执行超时升级</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button plain size="mini" icon="el-icon-refresh" @click="load">刷新</el-button>
      </el-col>
      <el-col v-if="timeoutDays" :span="6" class="eg-hint">待审超过 {{ timeoutDays }} 天自动升级</el-col>
    </el-row>

    <p v-if="listError" class="eg-error">{{ listError }} <el-button type="text" @click="load">重试</el-button></p>
    <el-table v-else v-loading="loading" :data="rows" empty-text="队列为空" @row-click="openDetail">
      <el-table-column label="提交" width="90">
        <template slot-scope="s">#{{ s.row.submissionId }}</template>
      </el-table-column>
      <el-table-column label="游戏" min-width="200">
        <template slot-scope="s">
          <div class="eg-name">{{ s.row.name || s.row.gameId }}</div>
          <div class="eg-sub">{{ s.row.gameId }} · v{{ s.row.version }}</div>
        </template>
      </el-table-column>
      <el-table-column label="作者" width="130">
        <template slot-scope="s">{{ s.row.author || '—' }}<div class="eg-sub">会员 {{ s.row.authorMemberId }}</div></template>
      </el-table-column>
      <el-table-column label="分级" prop="contentRating" width="80" />
      <el-table-column label="状态" width="100">
        <template slot-scope="s"><el-tag size="small" :type="statusTag(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="举报" width="70">
        <template slot-scope="s"><span :class="{ 'eg-warn': s.row.openReports > 0 }">{{ s.row.openReports || 0 }}</span></template>
      </el-table-column>
      <el-table-column label="提交时间" width="150">
        <template slot-scope="s">{{ timeText(s.row.submittedAt) }}</template>
      </el-table-column>
      <el-table-column label="截止" width="150">
        <template slot-scope="s">{{ timeText(s.row.reviewDueAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template slot-scope="s">
          <el-button type="text" @click.stop="openDetail(s.row)">详情</el-button>
          <template v-if="canDecide(s.row.status)">
            <el-button v-hasPermi="['business:audit:review']" type="text" @click.stop="openAction('approve', s.row)">通过</el-button>
            <el-button v-hasPermi="['business:audit:review']" type="text" class="eg-danger" @click.stop="openAction('reject', s.row)">驳回</el-button>
            <el-button v-if="canEscalate(s.row.status)" v-hasPermi="['business:audit:review']" type="text" @click.stop="openAction('escalate', s.row)">升级</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="load" />

    <el-drawer :title="detail ? `${detail.name || detail.gameId} v${detail.version}` : '提交详情'" :visible.sync="drawerOpen" size="640px" append-to-body>
      <div class="eg-drawer">
        <p v-if="detailError" class="eg-error">{{ detailError }}</p>
        <p v-else-if="detailLoading">读取中</p>
        <template v-else-if="detail">
          <div class="eg-actions">
            <el-tag :type="statusTag(detail.status)">{{ statusLabel(detail.status) }}</el-tag>
            <template v-if="canDecide(detail.status)">
              <el-button v-hasPermi="['business:audit:review']" size="mini" type="primary" @click="openAction('approve', detail)">通过</el-button>
              <el-button v-hasPermi="['business:audit:review']" size="mini" type="danger" plain @click="openAction('reject', detail)">驳回</el-button>
              <el-button v-if="canEscalate(detail.status)" v-hasPermi="['business:audit:review']" size="mini" @click="openAction('escalate', detail)">升级</el-button>
            </template>
            <el-button size="mini" icon="el-icon-download" :loading="downloading" @click="downloadPackage">游戏包</el-button>
          </div>

          <el-descriptions :column="2" size="small" border>
            <el-descriptions-item label="游戏标识">{{ detail.gameId }}</el-descriptions-item>
            <el-descriptions-item label="提交">#{{ detail.submissionId }}</el-descriptions-item>
            <el-descriptions-item label="作者">{{ detail.author || '—' }}（会员 {{ detail.authorMemberId }}）</el-descriptions-item>
            <el-descriptions-item label="分级">{{ detail.contentRating || '—' }}</el-descriptions-item>
            <el-descriptions-item label="入口">{{ detail.entry || '—' }}</el-descriptions-item>
            <el-descriptions-item label="变现">{{ detail.monetization || '—' }}</el-descriptions-item>
            <el-descriptions-item label="大小">{{ bytesText(detail.packageBytes) }} · {{ detail.fileCount }} 个文件</el-descriptions-item>
            <el-descriptions-item label="上一版">{{ detail.previousSubmissionId ? '#' + detail.previousSubmissionId : '—' }}</el-descriptions-item>
            <el-descriptions-item label="权限" :span="2">{{ listText(detail.permissions) }}</el-descriptions-item>
            <el-descriptions-item label="外链域名" :span="2">{{ listText(detail.allowedDomains) }}</el-descriptions-item>
            <el-descriptions-item label="SHA-256" :span="2"><code class="eg-code">{{ detail.sha256 }}</code></el-descriptions-item>
            <el-descriptions-item label="简介" :span="2">{{ detail.summary || '—' }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.escalated" label="升级原因" :span="2">{{ eventReasonText(detail.escalationReason) }}（{{ detail.escalationSource }}，{{ timeText(detail.escalatedAt) }}）</el-descriptions-item>
            <el-descriptions-item v-if="detail.rejectReason" label="驳回原因" :span="2">{{ detail.rejectReason }}</el-descriptions-item>
          </el-descriptions>

          <h4>文件</h4>
          <el-table :data="detail.files || []" size="mini" max-height="220" empty-text="没有文件">
            <el-table-column label="路径" prop="path" min-width="300" show-overflow-tooltip />
            <el-table-column label="大小" width="100">
              <template slot-scope="s">{{ bytesText(s.row.bytes) }}</template>
            </el-table-column>
          </el-table>

          <h4>审核记录</h4>
          <el-timeline v-if="(detail.timeline || []).length" class="eg-timeline">
            <el-timeline-item v-for="e in detail.timeline" :key="e.eventId" :timestamp="timeText(e.at)" placement="top">
              {{ statusLabel(e.fromStatus) }} → {{ statusLabel(e.toStatus) }} · {{ actorLabel(e.actorType) }}<span v-if="e.actorId"> {{ e.actorId }}</span>
              <div v-if="e.reason" class="eg-sub">{{ eventReasonText(e.reason) }}</div>
            </el-timeline-item>
          </el-timeline>
          <p v-else class="eg-sub">没有记录</p>

          <h4>玩家举报（最近 20 条）</h4>
          <el-table :data="detail.reports || []" size="mini" empty-text="没有举报">
            <el-table-column label="原因" prop="reasonCode" width="130" />
            <el-table-column label="说明" prop="detail" min-width="180" show-overflow-tooltip />
            <el-table-column label="版本" prop="version" width="70" />
            <el-table-column label="状态" prop="status" width="80" />
            <el-table-column label="时间" width="130">
              <template slot-scope="s">{{ timeText(s.row.createdAt) }}</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-drawer>

    <el-dialog :title="actionTitle" :visible.sync="actionOpen" width="480px" append-to-body>
      <el-form label-width="60px" size="small" @submit.native.prevent>
        <el-form-item :label="action === 'approve' ? '备注' : '原因'">
          <el-input v-model="actionText" type="textarea" :rows="3" :maxlength="reasonMax" show-word-limit />
        </el-form-item>
        <p v-if="actionError" class="eg-error">{{ actionError }}</p>
      </el-form>
      <div slot="footer">
        <el-button :type="action === 'reject' ? 'danger' : 'primary'" :loading="saving" @click="submitAction">确认</el-button>
        <el-button @click="actionOpen = false">取消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  listExternalGameReviews, getExternalGameReview, downloadExternalGamePackage,
  approveExternalGame, rejectExternalGame, escalateExternalGame, sweepExternalGameTimeouts
} from '@/api/business/reviewQueues'
import {
  reviewStatusLabel, reviewStatusTag, canDecide, canEscalate, actorLabel, eventReasonText,
  unwrap, reviewErrorText, timeText, bytesText, REASON_MAX
} from '@/utils/review-queue'

const TABS = [
  { key: 'OPEN', status: undefined, label: '待处理', counts: ['PENDING', 'ESCALATED'] },
  { key: 'ESCALATED', status: 'ESCALATED', label: '已升级', counts: ['ESCALATED'] },
  { key: 'APPROVED', status: 'APPROVED', label: '已通过', counts: ['APPROVED'] },
  { key: 'REJECTED', status: 'REJECTED', label: '已驳回', counts: ['REJECTED'] },
  { key: 'CANCELLED', status: 'CANCELLED', label: '已取消', counts: ['CANCELLED'] }
]
const ACTION_TITLE = { approve: '通过', reject: '驳回', escalate: '升级' }

export default {
  name: 'BusinessExternalGameReview',
  data() {
    return {
      tabs: TABS,
      tab: 'OPEN',
      loading: false,
      rows: [],
      total: 0,
      counts: {},
      timeoutDays: null,
      listError: '',
      query: { pageNum: 1, pageSize: 20 },
      sweeping: false,
      drawerOpen: false,
      detail: null,
      detailId: null,
      detailLoading: false,
      detailError: '',
      downloading: false,
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
      const t = this.actionTarget
      return `${ACTION_TITLE[this.action]}${t ? ' · ' + (t.name || t.gameId) + ' v' + t.version : ''}`
    }
  },
  created() {
    this.load()
    const sid = this.$route.query.submissionId
    if (sid) this.openDetail({ submissionId: String(sid) })
  },
  methods: {
    canDecide, canEscalate, actorLabel, eventReasonText, timeText, bytesText,
    statusLabel: reviewStatusLabel,
    statusTag: reviewStatusTag,
    listText(list) { return Array.isArray(list) && list.length ? list.join('、') : '无' },
    tabLabel(t) {
      const n = t.counts.reduce((sum, k) => sum + (Number(this.counts[k]) || 0), 0)
      return n ? `${t.label} (${n})` : t.label
    },
    search() { this.query.pageNum = 1; this.load() },
    load() {
      const tab = TABS.find(t => t.key === this.tab) || TABS[0]
      this.loading = true
      this.listError = ''
      listExternalGameReviews({ ...this.query, status: tab.status }).then(unwrap).then(page => {
        this.rows = page.rows || []
        this.total = page.total || 0
        this.counts = page.counts || {}
        this.timeoutDays = page.reviewTimeoutDays
      }).catch(err => {
        this.rows = []
        this.listError = reviewErrorText(err)
      }).finally(() => { this.loading = false })
    },
    sweep() {
      this.sweeping = true
      sweepExternalGameTimeouts().then(res => {
        if (Number(res && res.code) !== 200) throw res
        this.$modal.msgSuccess(`已升级 ${res.escalated || 0} 条超时提交`)
        this.load()
      }).catch(err => this.$modal.msgError(reviewErrorText(err))).finally(() => { this.sweeping = false })
    },
    openDetail(row) {
      this.detailId = row.submissionId
      this.drawerOpen = true
      this.loadDetail()
    },
    loadDetail() {
      this.detailLoading = true
      this.detailError = ''
      this.detail = null
      return getExternalGameReview(this.detailId).then(unwrap).then(vo => { this.detail = vo })
        .catch(err => { this.detailError = reviewErrorText(err) })
        .finally(() => { this.detailLoading = false })
    },
    downloadPackage() {
      if (!this.detail) return
      this.downloading = true
      const name = `${this.detail.gameId}-v${this.detail.version}.questify-game.json`
      downloadExternalGamePackage(this.detail.submissionId).then(blob => {
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = name
        a.click()
        URL.revokeObjectURL(url)
      }).catch(() => this.$modal.msgError('游戏包下载失败')).finally(() => { this.downloading = false })
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
      const id = this.actionTarget.submissionId
      const call = this.action === 'approve' ? approveExternalGame(id, text)
        : this.action === 'reject' ? rejectExternalGame(id, text) : escalateExternalGame(id, text)
      this.saving = true
      this.actionError = ''
      call.then(unwrap).then(() => {
        this.actionOpen = false
        this.$modal.msgSuccess(`已${ACTION_TITLE[this.action]}`)
        this.load()
        if (this.drawerOpen && this.detailId === id) this.loadDetail()
      }).catch(err => { this.actionError = reviewErrorText(err) }).finally(() => { this.saving = false })
    }
  }
}
</script>

<style scoped>
.eg-error { color: #f56c6c; }
.eg-hint { line-height: 28px; color: #909399; font-size: 12px; }
.eg-name { font-weight: 500; }
.eg-sub { color: #909399; font-size: 12px; }
.eg-warn { color: #e6a23c; font-weight: 600; }
.eg-danger { color: #f56c6c; }
.eg-drawer { padding: 0 20px 20px; }
.eg-drawer h4 { margin: 18px 0 8px; }
.eg-actions { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.eg-code { font-size: 12px; word-break: break-all; }
.eg-timeline { padding-left: 4px; }
</style>
