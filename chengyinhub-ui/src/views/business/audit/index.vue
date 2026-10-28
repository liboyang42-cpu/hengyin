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
          </template>
          <div v-if="appealAttachments.length" class="appeal-attachments">
            <div class="appeal-attachments__title">申诉附件（{{ appealAttachments.length }}）</div>
            <el-alert v-if="appealAttachmentsPreviewDenied" title="无媒体审核读取权限，仅显示附件编号。" type="info" :closable="false" />
            <div class="appeal-attachments__list">
              <div v-for="item in appealAttachments" :key="item.assetId" class="appeal-attachments__item">
                <el-image v-if="item.mediaType === 'IMAGE' && item.previewUrl" :src="item.previewUrl" fit="cover" class="appeal-attachments__thumb" :preview-src-list="appealAttachmentImageUrls" />
                <el-link v-else-if="item.previewUrl" :href="item.previewUrl" target="_blank" rel="noopener noreferrer">查看附件 #{{ item.assetId }}</el-link>
                <span v-else class="appeal-attachments__missing">附件 #{{ item.assetId }}（预览不可用）</span>
              </div>
            </div>
          </div>
          <el-alert v-if="detailTask.bizType === 'COMMUNITY_POST' && communityMediaPreviewError" :title="communityMediaPreviewError" type="warning" :closable="false" show-icon />
          <el-button v-if="detailTask.bizType === 'MEDIA_ASSET' || detailTask.bizType === 'COMMUNITY_POST' || appealAttachments.length" size="mini" @click="openDetail(detailRow)">刷新五分钟预览</el-button>
          <cy-evidence-panel title="内容快照" :items="auditEvidenceItems" empty-text="当前任务没有附带内容快照" />
          <cy-timeline title="处置轨迹" :items="auditTimelineItems" empty-text="尚无处置记录" />
        </template>
      </div>
    </el-drawer>

    <el-dialog title="审核处理" @close="saveReviewDraft" :visible.sync="reviewOpen" width="520px" append-to-body>
      <el-alert v-if="reviewError" :title="reviewError" type="warning" :closable="false" />
      <el-form ref="reviewForm" :model="reviewForm" label-width="84px" :disabled="reviewBusy || reviewFrozen">
        <section v-if="supportsProblems">
          <p>可选逐项说明，最多50项，每项200字。章节、节点及引用玩法的归属由后台校验。</p>
          <p>逐项保存取决于现有审核配置；审核成功不代表逐项明细已保存。</p>
          <el-button :disabled="reviewForm.problems.length >= 50" @click="addProblem">添加问题</el-button>
          <div v-for="(problem, index) in reviewForm.problems" :key="index">
            <el-form-item :label="'问题 ' + (index + 1)">
              <el-select v-model="problem.itemType">
                <el-option v-for="type in problemTypes" :key="type" :label="problemTypeLabels[type]" :value="type" />
              </el-select>
              <el-input v-model="problem.itemId" placeholder="条目编号；主题或模板本身可留空" />
              <el-select v-model="problem.reasonCode" clearable placeholder="原因分类（可选）"><el-option v-for="option in problemReasons" :key="option.value" :label="option.label" :value="option.value" /></el-select>
              <el-input v-model="problem.reasonText" type="textarea" maxlength="200" show-word-limit placeholder="说明具体问题（必填）" />
              <el-button @click="reviewForm.problems.splice(index, 1)">删除此项</el-button>
            </el-form-item>
          </div>
        </section>
        <el-form-item label="处理结果">
          <cy-status-tag :label="statusLabel(reviewForm.status)" :tone="statusTone(reviewForm.status)" />
        </el-form-item>
        <el-form-item label="处理原因" prop="reasonText">
          <el-input v-model="reviewForm.reasonText" type="textarea" :rows="4" placeholder="填写可向相关用户公开的处理原因；内部凭证和个人信息请勿填入" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :disabled="reviewBusy || reviewFrozen" @click="submitReview">确认处理</el-button>
        <el-button v-if="reviewFrozen && !reviewConfirmed" :disabled="reviewBusy" @click="submitReview">继续原请求</el-button>
        <el-button v-if="reviewConfirmed" :disabled="reviewBusy" @click="finishConfirmedReview">完成本次核对</el-button>
        <el-button v-if="reviewRow" :disabled="reviewBusy" @click="recoverReview">读取当前审核事实</el-button>
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
      communityMediaPreview: null,
      communityMediaPreviewError: '',
      appealAttachments: [],
      appealAttachmentsPreviewDenied: false,
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
        { label: '社区帖文', value: 'COMMUNITY_POST' },
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
      reviewRow: null, reviewBusy: false, reviewFrozen: false, reviewConfirmed: false, reviewError: '',
      reviewCommand: null, reviewOwner: '', reviewWasUnknown: false,
      problemReasons: [
        { value: 'TEXT_RISK', label: '文字内容违规' }, { value: 'IMAGE_RISK', label: '图片内容违规' },
        { value: 'FALSE_INFORMATION', label: '虚假信息' }, { value: 'PRICE_ISSUE', label: '价格信息有误' },
        { value: 'LOCATION_ISSUE', label: '地点信息有误' }, { value: 'INTELLECTUAL_PROPERTY', label: '知识产权或内容盗用' },
        { value: 'MINOR_SAFETY', label: '未成年人安全' }, { value: 'OTHER', label: '其他' }
      ],
      problemTypeLabels: { TOPIC: '主题', CHAPTER: '章节', NODE: '节点', TEMPLATE: '玩法模板' },
      commercialSubmitForm: { topicId: null, phase: 'PROJECT', requestedTarget: 'RECRUITING' }
    }
  },
  computed: {
    guardReview() { return this.reviewBizType === 'play_arrive_guard' },
    recoverableReview() { return this.supportsProblems || this.guardReview },
    supportsProblems() {
      return this.reviewForm.status === 2 && this.reviewRow && this.reviewRow.sourceType !== 'APPEAL'
        && ['TOPIC', 'TEMPLATE', 'TEMPLATE_LIBRARY'].includes(this.reviewBizType)
    },
    problemTypes() { return this.reviewBizType === 'TOPIC' ? ['TOPIC', 'CHAPTER', 'NODE', 'TEMPLATE'] : ['TEMPLATE'] },
    auditReviewer() { return String(this.$store.getters.id || '') + ':' + String(this.$store.getters.token || '') },
    appealAttachmentImageUrls() {
      return this.appealAttachments.filter(item => item.mediaType === 'IMAGE' && item.previewUrl).map(item => item.previewUrl)
    },
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
      if (!render) {
        const items = this.detailTask.snapshotJson
          ? [{ kind: 'text', content: this.snapshotText(this.detailTask.snapshotJson) }] : []
        if (bizType === 'COMMUNITY_POST' && this.communityMediaPreview) {
          items.push(...this.communityMediaPreview.items.map(item => ({ kind: 'image', src: item.url })))
        }
        return items
      }
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
  watch: {
    reviewForm: { deep: true, handler() { if (this.reviewOpen && !this.reviewFrozen) this.saveReviewDraft() } },
    auditReviewer() {
      this.reviewOpen = false; this.reviewForm = { problems: [] }; this.reviewRow = null
      this.reviewCommand = null; this.reviewFrozen = false; this.reviewBusy = false; this.reviewError = ''; this.reviewOwner = ''; this.reviewConfirmed = false; this.reviewWasUnknown = false
    }
  },
  beforeDestroy() { this.reviewOwner = '' },
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
    // S531-3:申诉附件只认正整数编号;预览地址必须是 https 短签名,否则按「预览不可用」展示编号。
    normalizeAppealAttachments(raw) {
      if (!Array.isArray(raw)) return []
      return raw.filter(item => item && Number.isSafeInteger(item.assetId) && item.assetId > 0).map(item => ({
        assetId: item.assetId,
        mediaType: typeof item.mediaType === 'string' ? item.mediaType : '',
        previewUrl: typeof item.previewUrl === 'string' && /^https:\/\//.test(item.previewUrl) ? item.previewUrl : ''
      }))
    },
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
      this.communityMediaPreview = null
      this.communityMediaPreviewError = ''
      this.appealAttachments = []
      this.appealAttachmentsPreviewDenied = false
      this.detailRecords = []
      this.reportCount = 0
      return getAuditTaskDetail(row.id).then(response => {
        if (requestSeq !== this.detailRequestSeq || !this.detailOpen) return
        if (!response || !response.task || response.task.id !== row.id || !Array.isArray(response.records)
          || !Number.isSafeInteger(response.reportCount) || response.reportCount < 0) {
          throw new Error('Invalid audit detail response')
        }
        this.mediaPreviewUrl = response.mediaPreviewUrl || ''; this.mediaType = response.mediaType || ''
        if (response.task.bizType === 'COMMUNITY_POST') {
          const preview = response.communityMediaPreview
          if (preview && preview.taskId === response.task.id && preview.taskVersion === response.task.version
            && preview.status === 'READY' && preview.expiresInSeconds === 300 && Array.isArray(preview.items)
            && preview.items.every(item => item && Number.isSafeInteger(item.id) && item.id > 0
              && typeof item.url === 'string' && /^https:\/\//.test(item.url))) {
            this.communityMediaPreview = preview
          } else {
            this.communityMediaPreviewError = '当前图片预览不可用，请刷新；原内容快照与处置轨迹仍保留。'
          }
        }
        this.appealAttachments = this.normalizeAppealAttachments(response.appealAttachments)
        this.appealAttachmentsPreviewDenied = response.appealAttachmentsPreviewDenied === true
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
    reviewStorageKey() {
      const id = this.exactAuditId(this.$store.getters.id)
      return 'audit-item-review:' + id
    },
    exactAuditId(value) {
      if (typeof value === 'number' && !Number.isSafeInteger(value)) throw Error('编号精度异常，请重新读取')
      const id = String(value == null ? '' : value).trim()
      if (!/^[1-9]\d{0,18}$/.test(id) || id.length === 19 && id > '9223372036854775807') throw Error('请输入有效条目编号')
      return id
    },
    saveReviewDraft() {
      if (!this.reviewRow || !this.recoverableReview || this.reviewOwner !== this.auditReviewer) return false
      try {
        const value = JSON.stringify({ row: this.reviewRow, form: this.reviewForm, command: this.reviewCommand,
          frozen: this.reviewFrozen, confirmed: this.reviewConfirmed, receiptVersion: 1, unknown: this.reviewWasUnknown })
        sessionStorage.setItem(this.reviewStorageKey(), value)
        if (sessionStorage.getItem(this.reviewStorageKey()) !== value) throw Error('草稿未保存')
        return true
      } catch (e) { this.reviewError = '审核草稿保存失败，本次不能提交：' + e.message; return false }
    },
    openReview(row, status) {
      if (this.reviewBusy) return
      this.reviewBizType = row.bizType
      this.reviewForm = { taskId: row.id, expectedVersion: row.version, status, reasonText: null, problems: [] }
      this.reviewRow = { ...row }
      this.reviewConfirmed = false; this.reviewFrozen = false; this.reviewCommand = null
      if (row.bizType !== 'play_arrive_guard' && (!['TOPIC', 'TEMPLATE', 'TEMPLATE_LIBRARY'].includes(row.bizType) || status !== 2 || row.sourceType === 'APPEAL')) {
        this.reviewOpen = true; this.reviewError = ''; return
      }
      this.reviewOwner = this.auditReviewer; this.reviewOpen = true; this.reviewError = ''
      try {
        const stored = JSON.parse(sessionStorage.getItem(this.reviewStorageKey()) || 'null')
        const same = stored && (stored.frozen || String(stored.row.id) === String(row.id) && stored.form.status === status)
        this.reviewRow = same ? stored.row : { ...row }
        this.exactAuditId(this.reviewRow.id)
        this.reviewBizType = this.reviewRow.bizType
        this.reviewForm = same ? stored.form : { taskId: row.id, expectedVersion: row.version, status, reasonText: '', problems: [] }
        this.reviewCommand = same ? stored.command : null
        if (this.reviewCommand && (this.exactAuditId(this.reviewCommand.taskId) !== this.exactAuditId(this.reviewRow.id)
          || (this.guardReview ? ![1, 2].includes(this.reviewCommand.status) : this.reviewCommand.status !== 2) || this.reviewCommand.expectedVersion !== this.reviewForm.expectedVersion
          || typeof this.reviewCommand.requestId !== 'string' || !this.reviewCommand.requestId || this.reviewCommand.requestId.length > 64)) throw Error('原请求与审核对象不匹配')
        this.reviewWasUnknown = !!(same && (stored.unknown || stored.frozen && !stored.confirmed)); this.reviewFrozen = !!(same && stored.frozen); this.reviewConfirmed = !!(same && stored.receiptVersion === 1 && stored.confirmed)
        if (this.reviewFrozen) this.reviewError = this.reviewConfirmed
          ? '原审核回执已确认，原说明保留供核对。'
          : '原审核结果待确认，输入已冻结；仅可读取事实或继续原请求。'
      } catch (e) { this.reviewRow = null; this.reviewFrozen = true; this.reviewError = '无法恢复原审核，请保留记录：' + e.message }
    },
    addProblem() {
      if (!this.supportsProblems || this.reviewBusy || this.reviewFrozen || this.reviewForm.problems.length >= 50) return
      this.reviewForm.problems.push({ itemType: this.problemTypes[0], itemId: '', reasonCode: '', reasonText: '' })
    },
    finishConfirmedReview() {
      if (!this.reviewConfirmed || this.reviewBusy || this.reviewOwner !== this.auditReviewer) return
      try {
        sessionStorage.removeItem(this.reviewStorageKey())
        this.reviewOpen = false; this.reviewRow = null; this.reviewCommand = null; this.reviewFrozen = false
      } catch (e) { this.reviewError = '已确认的核对记录未能清理，请保留原记录' }
    },
    reviewPayload() {
      const form = this.reviewForm
      const payload = { taskId: this.exactAuditId(form.taskId), expectedVersion: form.expectedVersion,
        status: form.status, reasonText: String(form.reasonText || '').trim() }
      if (!Number.isSafeInteger(payload.expectedVersion) || payload.expectedVersion < 0) throw Error('审核版本缺失，请读取最新证据')
      if (payload.status === 2 && !payload.reasonText) throw Error('请填写整体处理原因')
      if (this.supportsProblems) {
        if (form.problems.length > 50) throw Error('逐项问题最多50条')
        payload.problems = form.problems.map((problem, index) => {
          if (!this.problemTypes.includes(problem.itemType)) throw Error('问题类型不属于本次审核')
          const itemId = String(problem.itemId || '').trim() ? this.exactAuditId(problem.itemId) : null
          const whole = problem.itemType === 'TOPIC' || this.reviewBizType !== 'TOPIC'
          if (!whole && !itemId) throw Error('第' + (index + 1) + '项必须填写条目编号')
          if (whole && itemId && itemId !== this.exactAuditId(this.reviewRow.bizId)) throw Error('问题条目不属于本次审核内容')
          const reasonText = String(problem.reasonText || '').trim()
          if (!reasonText || reasonText.length > 200) throw Error('第' + (index + 1) + '项请填写1至200字说明')
          const reasonCode = problem.reasonCode || null
          if (reasonCode && !this.problemReasons.some(option => option.value === reasonCode)) throw Error('请选择有效原因分类')
          return { itemType: problem.itemType, itemId, reasonCode, reasonText }
        })
      }
      return payload
    },
    async recoverReview() {
      if (!this.reviewCommand) return this.openDetail(this.reviewRow)
      const owner = this.auditReviewer, command = this.reviewCommand
      try {
        const response = await getAuditTaskDetail(command.taskId, command.requestId)
        if (owner !== this.auditReviewer || owner !== this.reviewOwner || command !== this.reviewCommand) return
        const receipt = response && (this.guardReview ? response.guardReviewReceipt : response.itemProblemReceipt)
        if (!response || response.code !== 200 || !receipt || receipt.state !== 'FOUND') throw Error('原审核回执尚未确认；继续保留原请求')
        if (receipt.taskId !== command.taskId || receipt.requestId !== command.requestId
          || receipt.expectedVersion !== command.expectedVersion || receipt.status !== command.status
          || receipt.reasonText !== command.reasonText) throw Error('回执与原审核意图不一致')
        if (receipt.applied !== true && receipt.applied !== false) throw Error('逐项处理状态不明确')
        if (receipt.applied && (!Array.isArray(receipt.problems) || receipt.problems.length !== (command.problems || []).length
          || receipt.problems.some((item, index) => !item || ['itemType', 'itemId', 'reasonCode', 'reasonText'].some(key => item[key] !== command.problems[index][key])))) throw Error('逐项回执与原输入不一致')
        this.reviewConfirmed = true
        this.reviewError = this.guardReview ? '守卫申诉处理回执已确认；成立后仍须在有效原局重新现场核验，不直接放行或补发奖励。' : receipt.applied ? '原审核及逐项说明已由服务端回执确认。' : '原审核已确认；本次逐项开关关闭，逐项说明未保存。'
      } catch (e) {
        if (owner !== this.auditReviewer || owner !== this.reviewOwner) return
        this.reviewError = e.message || '读取失败，保留原请求'
        this.reviewWasUnknown = true
      }
      if (owner === this.auditReviewer && owner === this.reviewOwner) this.saveReviewDraft()
    },
    async submitReview() {
      if (!this.recoverableReview && !this.reviewFrozen) {
        const review = this.reviewBizType === 'MEDIA_ASSET' ? reviewMediaAsset : reviewAuditTask
        const { problems, ...payload } = this.reviewForm
        return review(payload).then(() => {
          this.$modal.msgSuccess('审核处理成功'); this.reviewOpen = false; this.detailOpen = false; this.getList()
        })
      }
      if (this.reviewBusy || !this.reviewRow || this.reviewConfirmed || this.reviewOwner !== this.auditReviewer) return
      const owner = this.auditReviewer
      // Keep the existing permissions; hidden buttons are not the authorization boundary.
      const permissions = this.$store.getters.permissions || []
      const permission = this.reviewBizType === 'MEDIA_ASSET'
        ? (this.reviewForm.status === 1 ? 'business:mediaReview:approve' : 'business:mediaReview:reject') : 'business:audit:review'
      if (!permissions.includes('*:*:*') && !permissions.includes(permission)) { this.reviewError = '没有本次审核处理权限'; return }
      this.reviewBusy = true; this.reviewError = ''
      try {
        if (!this.reviewFrozen) {
          const payload = this.reviewPayload()
          payload.requestId = 'audit-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2)
          this.reviewCommand = payload; this.reviewFrozen = true
          if (!this.saveReviewDraft()) { this.reviewFrozen = false; this.reviewCommand = null; return }
        }
        if (!this.reviewCommand) throw Error('原请求不完整，不能提交')
        const review = this.reviewBizType === 'MEDIA_ASSET' ? reviewMediaAsset : reviewAuditTask
        const result = await review(JSON.parse(JSON.stringify(this.reviewCommand)))
        if (owner !== this.auditReviewer || owner !== this.reviewOwner) return
        if (!result || result.code !== 200) throw Error(result && result.msg || '审核结果未确认')
        // POST acceptance is not independent proof of the original immutable intent.
        this.reviewWasUnknown = true
        await this.recoverReview()
        this.getList()
      } catch (e) {
        if (owner !== this.auditReviewer || owner !== this.reviewOwner) return
        this.reviewError = (e && e.message || '审核结果待确认') + '；原输入已保留。'
        // Only a first attempt rejected before any write can return to editing. An UNKNOWN retry stays frozen.
        if (e && e.auditCode === 400 && !this.reviewWasUnknown) { this.reviewFrozen = false; this.reviewCommand = null }
        else if (this.reviewCommand) this.reviewWasUnknown = true
        this.saveReviewDraft()
      } finally { if (owner === this.auditReviewer && owner === this.reviewOwner) this.reviewBusy = false }
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
.appeal-attachments { margin: var(--cy-space-3-5) 0; }
.appeal-attachments__title { margin-bottom: 8px; font-weight: 600; }
.appeal-attachments__list { display: flex; flex-wrap: wrap; gap: var(--cy-space-2-5); margin-top: 8px; }
.appeal-attachments__thumb { width: 120px; height: 120px; border-radius: var(--cy-radius-card); }
.appeal-attachments__missing { color: var(--cy-color-text-tertiary); font-size: 12px; }
.commercial-submit__hint { margin: 0 0 0 96px; color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.6; }
@media (max-width: 900px) { .audit-overview { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 600px) { .audit-overview { grid-template-columns: 1fr; } }
</style>
