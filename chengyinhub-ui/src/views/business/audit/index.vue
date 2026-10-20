<template>
  <div class="app-container cy-domain-page">
    <cy-page-header
      eyebrow="审核与风控"
      title="统一审核台"
      description="所有内容审核与举报处置共用这一条证据链。业务详情只展示结果，审核决定只在这里写入。"
    >
      <el-button type="primary" icon="el-icon-upload2" @click="openCommercialSubmit" v-hasPermi="['business:audit:submit']">提交商业审核</el-button>
      <el-button icon="el-icon-refresh" @click="getList">刷新队列</el-button>
    </cy-page-header>

    <section class="audit-overview" aria-label="本页审核概览">
      <button v-for="item in overview" :key="item.key" type="button" class="audit-overview__item" @click="filterByStatus(item.status)">
        <span class="audit-overview__label">{{ item.label }}</span>
        <strong>{{ item.count }}</strong>
        <span>{{ item.note }}</span>
      </button>
    </section>

    <section class="cy-panel audit-filter">
      <div class="cy-panel__heading">
        <div>
          <h2>审核队列</h2>
          <p>按业务类型与处理状态定位对象，点击对象编号查看快照、举报热度和完整处置记录。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryParams" size="small" :inline="true" label-width="72px">
        <cy-filter-bar
          :active-filters="activeFilters"
          @submit="handleQuery"
          @reset="resetQuery"
          @remove-filter="clearFilter"
        >
          <el-form-item label="业务类型" prop="bizType">
            <el-select v-model="queryParams.bizType" placeholder="全部类型" clearable filterable>
              <el-option v-for="item in bizTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="审核状态" prop="status">
            <el-select v-model="queryParams.status" placeholder="全部状态" clearable>
              <el-option label="待审核" :value="0" />
              <el-option label="升级审核" :value="3" />
              <el-option label="已通过" :value="1" />
              <el-option label="已拒绝" :value="2" />
              <el-option label="已取消" :value="4" />
            </el-select>
          </el-form-item>
          <el-form-item label="对象 ID" prop="bizId">
            <el-input v-model="queryParams.bizId" placeholder="精确定位" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
        </cy-filter-bar>
      </el-form>
    </section>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />

    <cy-data-table
      :data="auditList"
      :columns="tableColumns"
      :loading="loading"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      default-density="comfortable"
      empty-text="当前筛选下没有审核任务"
      @pagination="getList"
      :show-refresh="false"
    >
      <template slot="subject" slot-scope="scope">
        <cy-entity-link
          :name="bizTypeLabel(scope.row.bizType)"
          :entity-type="scope.row.bizType"
          type-label="审核对象"
          :entity-id="scope.row.bizId"
          :meta="`任务 #${scope.row.id}`"
          @open="openDetail(scope.row)"
        />
      </template>
      <template slot="risk" slot-scope="scope">
        <div class="table-stack">
          <span>{{ scope.row.sourceType || '业务提交' }}</span>
          <small>{{ scope.row.riskLevel ? `风险 ${scope.row.riskLevel}` : '未标注风险等级' }}</small>
        </div>
      </template>
      <template slot="status" slot-scope="scope">
        <cy-status-tag :label="statusLabel(scope.row.status)" :tone="statusTone(scope.row.status)" />
      </template>
      <template slot="schedule" slot-scope="scope">
        <div class="table-stack">
          <span>{{ parseTime(scope.row.submittedAt || scope.row.createTime, '{y}-{m}-{d} {h}:{i}') || '—' }}</span>
          <small>截止 {{ parseTime(scope.row.deadlineAt, '{m}-{d} {h}:{i}') || '未设置' }}</small>
        </div>
      </template>
      <template slot="actions" slot-scope="scope">
        <el-button size="mini" type="text" icon="el-icon-view" @click="openDetail(scope.row)">查看证据</el-button>
        <el-button
          size="mini"
          type="text"
          :disabled="!canReview(scope.row)"
          @click="openReview(scope.row, 1)"
          v-hasPermi="scope.row.bizType === 'MEDIA_ASSET' ? ['business:mediaReview:approve'] : ['business:audit:review']"
        >通过</el-button>
        <el-button
          size="mini"
          type="text"
          class="danger-action"
          :disabled="!canReview(scope.row)"
          @click="openReview(scope.row, 2)"
          v-hasPermi="scope.row.bizType === 'MEDIA_ASSET' ? ['business:mediaReview:reject'] : ['business:audit:review']"
        >拒绝</el-button>
      </template>
    </cy-data-table>

    <el-drawer title="审核证据链" :visible.sync="detailOpen" size="640px" append-to-body>
      <div v-loading="detailLoading" class="audit-detail">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon />
        <el-button v-if="detailError" @click="openDetail(detailRow)">重新加载</el-button>
        <template v-if="detailTask">
          <cy-entity-header
            :eyebrow="`${bizTypeLabel(detailTask.bizType)} · #${detailTask.bizId}`"
            :title="`审核任务 #${detailTask.id}`"
            :status="{ label: statusLabel(detailTask.status), tone: statusTone(detailTask.status) }"
            :facts="auditHeaderFacts"
          >
            <template slot="actions">
              <el-button size="mini" type="primary" :disabled="!canReview(detailTask)" @click="openReview(detailTask, 1)" v-hasPermi="detailTask.bizType === 'MEDIA_ASSET' ? ['business:mediaReview:approve'] : ['business:audit:review']">通过</el-button>
              <el-button size="mini" type="text" class="danger-action" :disabled="!canReview(detailTask)" @click="openReview(detailTask, 2)" v-hasPermi="detailTask.bizType === 'MEDIA_ASSET' ? ['business:mediaReview:reject'] : ['business:audit:review']">拒绝</el-button>
            </template>
          </cy-entity-header>
          <template v-if="detailTask.bizType === 'HANGOUT'">
            <el-alert title="请结合冻结快照核实；内容缺失时应补充调查或升级审核，不能视为无违规。" type="info" :closable="false" />
            <el-button size="mini" @click="investigateSameTarget">查看同组局审核历史</el-button>
            <el-button size="mini" :disabled="Number(detailTask.status) !== 0" @click="openReview(detailTask, 3)" v-hasPermi="['business:audit:review']">缺证，升级审核</el-button>
          </template>
          <template v-if="detailTask.bizType === 'TEAM'">
            <el-alert title="队伍举报：拒绝=成立，队伍改回仅邀请并从地图撤下，不解散、不踢人；通过=不成立，队伍保持公开。请结合快照核实。" type="info" :closable="false" />
            <el-button size="mini" @click="investigateSameTarget">查看同队伍审核历史</el-button>
            <el-button size="mini" :disabled="Number(detailTask.status) !== 0" @click="openReview(detailTask, 3)" v-hasPermi="['business:audit:review']">缺证，升级审核</el-button>
          </template>
          <template v-if="detailTask.bizType === 'MEDIA_ASSET'">
            <el-alert title="文件在审核通过前仅作者可见。请实际检查图片、音频或模型；无法检查时请拒绝并填写原因，作者可重新提审。" type="info" :closable="false" />
            <el-image v-if="mediaType === 'IMAGE' && mediaPreviewUrl" :src="mediaPreviewUrl" fit="contain" style="max-width: 100%; max-height: 480px" :preview-src-list="[mediaPreviewUrl]" />
            <audio v-else-if="mediaType === 'AUDIO' && mediaPreviewUrl" :src="mediaPreviewUrl" controls preload="none" />
            <el-link v-else-if="mediaPreviewUrl" :href="mediaPreviewUrl" target="_blank" rel="noopener noreferrer">下载并检查模型文件</el-link>
            <el-button size="mini" @click="openDetail(detailRow)">刷新五分钟预览</el-button>
          </template>
          <cy-evidence-panel title="内容快照" :items="auditEvidenceItems" empty-text="当前任务没有附带内容快照" />
          <cy-timeline title="处置轨迹" :items="auditTimelineItems" empty-text="尚无处置记录" />
        </template>
      </div>
    </el-drawer>

    <el-dialog title="审核处理" :visible.sync="reviewOpen" width="520px" append-to-body>
      <el-form ref="reviewForm" :model="reviewForm" label-width="84px">
        <el-form-item label="处理结果">
          <cy-status-tag :label="statusLabel(reviewForm.status)" :tone="statusTone(reviewForm.status)" />
        </el-form-item>
        <el-form-item label="处理原因" prop="reasonText">
          <el-input v-model="reviewForm.reasonText" type="textarea" :rows="4" placeholder="填写可向相关用户公开的处理原因；内部凭证和个人信息请勿填入" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitReview">确认处理</el-button>
        <el-button @click="reviewOpen = false">取消</el-button>
      </div>
    </el-dialog>

    <el-dialog title="提交商业主题审核" :visible.sync="commercialSubmitOpen" width="520px" append-to-body>
      <el-form :model="commercialSubmitForm" label-width="96px">
        <el-form-item label="主题 ID">
          <el-input-number v-model="commercialSubmitForm.topicId" :min="1" :precision="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="审核阶段">
          <el-radio-group v-model="commercialSubmitForm.phase">
            <el-radio-button label="PROJECT">立项审核</el-radio-button>
            <el-radio-button label="SALE">开售审核</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="commercialSubmitForm.phase === 'PROJECT'" label="通过后进入">
          <el-select v-model="commercialSubmitForm.requestedTarget">
            <el-option label="招募中" value="RECRUITING" />
            <el-option label="待定价" value="PRICING" />
          </el-select>
        </el-form-item>
        <p class="commercial-submit__hint">纯 UGC（无收费票、未开招商、无商家供给位）将免审直通；商业触发事实与源 ID 会冻结进审核快照。</p>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitCommercialReview">确认提交</el-button>
        <el-button @click="commercialSubmitOpen = false">取消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { getAuditTaskDetail, listAuditTasks, reviewAuditTask, reviewMediaAsset, submitTopicProjectReview, submitTopicSaleReview } from '@/api/business/auditWorkbench'
import CyDataTable from '@/components/CyDataTable'
import CyEntityHeader from '@/components/CyEntityHeader'
import CyEntityLink from '@/components/CyEntityLink'
import CyEvidencePanel from '@/components/CyEvidencePanel'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyStatusTag from '@/components/CyStatusTag'
import CyTimeline from '@/components/CyTimeline'

const STATUS_PENDING = 0
const STATUS_APPROVED = 1
const STATUS_REJECTED = 2
const STATUS_ESCALATED = 3
const STATUS_CANCELLED = 4

const STATUS_TEXT = {
  [STATUS_PENDING]: '待审核',
  [STATUS_APPROVED]: '已通过',
  [STATUS_REJECTED]: '已拒绝',
  [STATUS_ESCALATED]: '升级审核',
  [STATUS_CANCELLED]: '已取消'
}

const ACTION_META = {
  SUBMIT: { label: '提交审核', tone: 'brand' },
  REPORT: { label: '收到举报', tone: 'danger' },
  APPROVE: { label: '审核通过', tone: 'success' },
  REJECT: { label: '审核拒绝', tone: 'danger' },
  ESCALATE: { label: '升级审核', tone: 'warning' },
  CANCEL: { label: '取消任务', tone: 'neutral' }
}

// TEAM 快照里的裸数字:审核员要一眼看出「能不能公开、还在不在招」。
const TEAM_JOIN_MODE = { 1: '仅邀请', 2: '公开申请' }
const TEAM_STATUS = { 0: '招募中', 1: '已满员', 2: '进行中', 3: '已结束', 4: '已解散' }

export default {
  name: 'BusinessAuditWorkbench',
  components: { CyDataTable, CyEntityHeader, CyEntityLink, CyEvidencePanel, CyFilterBar, CyPageHeader, CyStatusTag, CyTimeline },
  data() {
    return {
      loading: false,
      loadError: '',
      listRequestSeq: 0,
      detailLoading: false,
      detailError: '',
      detailRequestSeq: 0,
      detailRow: null,
      detailOpen: false,
      reviewOpen: false,
      commercialSubmitOpen: false,
      total: 0,
      reportCount: 0,
      auditList: [],
      detailTask: null,
      mediaPreviewUrl: '',
      mediaType: '',
      detailRecords: [],
      tableColumns: [
        { key: 'subject', label: '审核对象', minWidth: 210 },
        { key: 'risk', label: '风险与来源', minWidth: 180 },
        { key: 'submitter', prop: 'submitterId', label: '提交人', width: 110, formatter: row => `#${row.submitterId || '—'}` },
        { key: 'status', label: '状态', width: 120 },
        { key: 'schedule', label: '提交 / 截止', minWidth: 190 },
        { key: 'actions', label: '处理', width: 210, fixed: 'right', align: 'right', locked: true }
      ],
      bizTypeOptions: [
        { label: '媒体文件审核', value: 'MEDIA_ASSET' },
        { label: '主题立项审核', value: 'TOPIC_PROJECT_REVIEW' },
        { label: '主题开售审核', value: 'TOPIC_SALE_REVIEW' },
        { label: '主题', value: 'TOPIC' },
        { label: '活动', value: 'ACTIVITY' },
        { label: '官方活动', value: 'OFFICIAL_EVENT' },
        { label: '优惠券抽检', value: 'COUPON' },
        { label: '玩法模板', value: 'TEMPLATE' },
        { label: '商家城市节点', value: 'merchant_node' },
        { label: '商家节点模板', value: 'merchant_node_template' },
        { label: '创意广场', value: 'SQUARE' },
        { label: '评论', value: 'COMMENT' },
        { label: '俱乐部动态', value: 'CLUB_POST' },
        { label: '动态评论', value: 'CLUB_POST_COMMENT' },
        { label: '玩家队伍', value: 'TEAM' },
        { label: '私信消息', value: 'IM_MESSAGE' }
      ],
      queryParams: { pageNum: 1, pageSize: 10, bizType: null, bizId: null, status: null },
      reviewForm: { taskId: null, status: null, reasonText: null },
      reviewBizType: '',
      commercialSubmitForm: { topicId: null, phase: 'PROJECT', requestedTarget: 'RECRUITING' }
    }
  },
  computed: {
    auditHeaderFacts() {
      if (!this.detailTask) return []
      return [
        { label: '提交人', value: this.detailTask.submitterId ? `#${this.detailTask.submitterId}` : '—' },
        { label: '举报人数', value: this.reportCount },
        { label: '风险等级', value: this.detailTask.riskLevel || '未标注' }
      ]
    },
    auditEvidenceItems() {
      if (!this.detailTask) return []
      const bizType = this.detailTask.bizType
      const render = bizType === 'HANGOUT' ? (value, label) => this.hangoutEvidence(value, label)
        : bizType === 'TEAM' ? (value, label) => this.teamEvidence(value, label) : null
      if (!render) return this.detailTask.snapshotJson
        ? [{ kind: 'text', content: this.snapshotText(this.detailTask.snapshotJson) }] : []
      const items = render(this.detailTask.snapshotJson || '{}', '首次举报快照')
      this.detailRecords.filter(record => record.action === 'REPORT' && record.snapshotJson).forEach(record => {
        items.push(...render(record.snapshotJson, `举报记录 #${record.id}`))
      })
      return items
    },
    auditTimelineItems() {
      return this.detailRecords.map(record => {
        const action = this.actionMeta(record.action)
        return {
          id: record.id,
          title: action.label,
          summary: record.reasonText || record.reasonCode || '未填写补充说明',
          actor: record.operatorId ? `操作人 #${record.operatorId}` : '系统',
          occurredAt: this.parseTime(record.createTime, '{y}-{m}-{d} {h}:{i}') || '—',
          tone: action.tone,
          evidence: record.snapshotJson ? ['附有当时内容快照'] : []
        }
      })
    },
    activeFilters() {
      return [
        this.queryParams.bizType && { key: 'bizType', label: '业务类型', value: this.bizTypeLabel(this.queryParams.bizType) },
        this.queryParams.status !== null && this.queryParams.status !== undefined && { key: 'status', label: '审核状态', value: this.statusLabel(this.queryParams.status) },
        this.queryParams.bizId && { key: 'bizId', label: '对象 ID', value: this.queryParams.bizId }
      ].filter(Boolean)
    },
    overview() {
      const count = status => this.auditList.filter(item => item.status === status).length
      return [
        { key: 'pending', label: '待审核', count: count(STATUS_PENDING), note: '本页待处理', status: STATUS_PENDING },
        { key: 'escalated', label: '升级审核', count: count(STATUS_ESCALATED), note: '需要复核判断', status: STATUS_ESCALATED },
        { key: 'approved', label: '已通过', count: count(STATUS_APPROVED), note: '本页已完成', status: STATUS_APPROVED },
        { key: 'rejected', label: '已拒绝', count: count(STATUS_REJECTED), note: '本页已处置', status: STATUS_REJECTED }
      ]
    }
  },
  created() {
    if (this.$route.query.bizType) {
      this.queryParams.bizType = this.$route.query.bizType
    }
    if (this.$route.query.bizId) {
      this.queryParams.bizId = this.$route.query.bizId
    }
    this.getList()
  },
  methods: {
    bizTypeLabel(type) {
      const item = this.bizTypeOptions.find(option => option.value === type)
      return item ? item.label : (type || '未知类型')
    },
    getList() {
      const requestSeq = ++this.listRequestSeq
      this.loading = true
      this.loadError = ''
      return listAuditTasks(this.queryParams).then(response => {
        if (requestSeq !== this.listRequestSeq) return
        if (!response || !Array.isArray(response.rows) || !Number.isSafeInteger(response.total) || response.total < 0) {
          throw new Error('Invalid audit queue response')
        }
        this.auditList = response.rows
        this.total = response.total
      }).catch(() => {
        if (requestSeq === this.listRequestSeq) this.loadError = '审核队列加载失败，请刷新重试。'
      }).finally(() => {
        if (requestSeq === this.listRequestSeq) this.loading = false
      })
    },
    statusLabel(status) {
      return STATUS_TEXT[status] || '未知状态'
    },
    statusTone(status) {
      return {
        [STATUS_PENDING]: 'warning',
        [STATUS_APPROVED]: 'success',
        [STATUS_REJECTED]: 'danger',
        [STATUS_ESCALATED]: 'brand',
        [STATUS_CANCELLED]: 'neutral'
      }[status] || 'neutral'
    },
    actionMeta(action) {
      return ACTION_META[action] || { label: action || '状态更新', tone: 'neutral' }
    },
    canReview(row) {
      return !this.loading && !this.loadError && (row.status === STATUS_PENDING || row.status === STATUS_ESCALATED)
    },
    filterByStatus(status) {
      this.queryParams.status = status
      this.handleQuery()
    },
    clearFilter(key) {
      this.queryParams[key] = null
      this.handleQuery()
    },
    openDetail(row) {
      const requestSeq = ++this.detailRequestSeq
      this.detailOpen = true
      this.detailLoading = true
      this.detailError = ''
      this.detailRow = row
      this.detailTask = null
      this.mediaPreviewUrl = ''; this.mediaType = ''
      this.detailRecords = []
      this.reportCount = 0
      return getAuditTaskDetail(row.id).then(response => {
        if (requestSeq !== this.detailRequestSeq || !this.detailOpen) return
        if (!response || !response.task || response.task.id !== row.id || !Array.isArray(response.records)
          || !Number.isSafeInteger(response.reportCount) || response.reportCount < 0) {
          throw new Error('Invalid audit detail response')
        }
        this.mediaPreviewUrl = response.mediaPreviewUrl || ''; this.mediaType = response.mediaType || ''
        this.detailTask = response.task
        this.detailRecords = response.records
        this.reportCount = response.reportCount
      }).catch(() => {
        if (requestSeq === this.detailRequestSeq && this.detailOpen) this.detailError = '审核证据加载失败，请重新加载。'
      }).finally(() => {
        if (requestSeq === this.detailRequestSeq) this.detailLoading = false
      })
    },
    investigateSameTarget() {
      this.queryParams = { ...this.queryParams, bizType: this.detailTask.bizType, bizId: this.detailTask.bizId, status: null, pageNum: 1 }
      this.detailOpen = false
      this.getList()
    },
    /** 快照扁平化:对象展开成「标签 / 子标签：值」逐行,审核员看的是事实不是 JSON。 */
    flatSnapshotLines(snapshot, labels) {
      const lines = []
      const walk = (node, prefix) => {
        if (node !== null && typeof node === 'object') {
          Object.keys(node).forEach(key => walk(node[key], prefix ? `${prefix} / ${labels[key] || key}` : labels[key] || key))
        } else {
          lines.push(`${prefix}：${node === null ? '未记录' : String(node)}`)
        }
      }
      walk(snapshot, '')
      return lines
    },
    hangoutEvidence(value, label) {
      let snapshot
      try { snapshot = JSON.parse(value) } catch (error) {
        return [{ kind: 'text', label, content: `证据格式无法解析，请补充调查。原文：${value}` }]
      }
      const labels = { bizType: '业务类型', bizId: '组局编号', reason: '举报陈述', content: '举报时内容',
        title: '标题', description: '说明', addressName: '地点', startAt: '开始时间', status: '当时状态',
        conversationId: '历史群编号', memberId: '局主编号', ownerNickname: '当时局主昵称', ownerRole: '当时局主身份', coverUrl: '封面地址',
        available: '原始内容可用', evidenceStatus: '证据状态', missingReason: '缺证说明', investigation: '补充调查路径' }
      const lines = this.flatSnapshotLines(snapshot, labels)
      if (!snapshot || !snapshot.content || snapshot.content.available === false) lines.unshift('原始内容证据缺失，请查看同组局审核历史、补充材料或升级审核。')
      const items = [{ kind: 'text', label, content: lines.join('\n') }]
      const cover = snapshot && snapshot.content && snapshot.content.coverUrl
      if (cover && /^https?:\/\//i.test(cover)) items.push({ kind: 'image', src: cover, alt: '举报时组局封面' })
      return items
    },
    teamEvidence(value, label) {
      let snapshot
      try { snapshot = JSON.parse(value) } catch (error) {
        return [{ kind: 'text', label, content: `证据格式无法解析，请补充调查。原文：${value}` }]
      }
      const content = snapshot && snapshot.content
      if (content) {
        if (TEAM_JOIN_MODE[content.joinMode]) content.joinMode = TEAM_JOIN_MODE[content.joinMode]
        if (TEAM_STATUS[content.status]) content.status = TEAM_STATUS[content.status]
      }
      const labels = { bizType: '业务类型', bizId: '队伍编号', reason: '举报陈述', content: '举报时队伍',
        title: '队伍名称', leaderMemberId: '队长编号', ownerType: '场次类型', ownerId: '场次编号',
        joinMode: '组队方式', status: '队伍状态', joinedCount: '已加入人数', maxMembers: '队伍人数上限',
        available: '原始内容可用', evidenceStatus: '证据状态' }
      const lines = this.flatSnapshotLines(snapshot, labels)
      if (!snapshot || !snapshot.content || snapshot.content.available === false) lines.unshift('原始内容证据缺失，请查看同队伍审核历史、补充材料或升级审核。')
      return [{ kind: 'text', label, content: lines.join('\n') }]
    },
    snapshotText(value) {
      if (!value) return '当前任务没有附带内容快照'
      try { return JSON.stringify(JSON.parse(value), null, 2) } catch (error) { return value }
    },
    openReview(row, status) {
      this.reviewBizType = row.bizType
      this.reviewForm = { taskId: row.id, expectedVersion: row.version, status: status, reasonText: null }
      this.reviewOpen = true
    },
    submitReview() {
      const review = this.reviewBizType === 'MEDIA_ASSET' ? reviewMediaAsset : reviewAuditTask
      return review(this.reviewForm).then(() => {
        this.$modal.msgSuccess('审核处理成功')
        this.reviewOpen = false
        this.detailOpen = false
        this.getList()
      })
    },
    openCommercialSubmit() {
      this.commercialSubmitForm = { topicId: null, phase: 'PROJECT', requestedTarget: 'RECRUITING' }
      this.commercialSubmitOpen = true
    },
    submitCommercialReview() {
      const form = this.commercialSubmitForm
      if (!form.topicId) {
        this.$modal.msgError('请输入主题 ID')
        return
      }
      const request = form.phase === 'PROJECT'
        ? submitTopicProjectReview(form.topicId, { requestedTarget: form.requestedTarget })
        : submitTopicSaleReview(form.topicId)
      request.then(response => {
        this.$modal.msgSuccess(response.msg || '审核提交成功')
        this.commercialSubmitOpen = false
        this.queryParams.bizType = form.phase === 'PROJECT' ? 'TOPIC_PROJECT_REVIEW' : 'TOPIC_SALE_REVIEW'
        this.queryParams.bizId = form.topicId
        this.handleQuery()
      })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.handleQuery()
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-domain-page { color: var(--cy-color-text-primary); }
.cy-panel { margin-bottom: 20px; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); box-shadow: var(--cy-shadow-card); }
.cy-panel__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding: 20px var(--cy-space-page) 16px; }
.cy-panel__heading h2 { margin: 0; font-size: 17px; }
.cy-panel__heading p { margin: var(--cy-space-0-5) 0 0; color: var(--cy-color-text-secondary); font-size: 13px; }
.audit-overview { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--cy-space-2-5); margin-bottom: 20px; }
.audit-overview__item { padding: var(--cy-space-3-5); border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); color: inherit; text-align: left; cursor: pointer; box-shadow: var(--cy-shadow-card); }
.audit-overview__item:hover { border-color: #cfc7fb; }
.audit-overview__item:focus-visible { outline: 3px solid var(--cy-color-focus); }
.audit-overview__label { display: block; color: var(--cy-color-text-secondary); font-size: 12px; }
.audit-overview strong { display: block; margin: 8px 0 var(--cy-legacy-3); font-size: 26px; }
.audit-overview__item > span:last-child { color: var(--cy-color-text-tertiary); font-size: 12px; }
.table-stack span, .table-stack small { display: block; }
.table-stack small { margin-top: 4px; color: var(--cy-color-text-tertiary); }
.danger-action { color: var(--cy-color-status-danger); }
.audit-detail { min-height: 260px; padding: 0 24px var(--cy-space-7); }
.commercial-submit__hint { margin: 0 0 0 96px; color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.6; }
@media (max-width: 900px) { .audit-overview { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 600px) { .audit-overview { grid-template-columns: 1fr; } }
</style>
