<template>
  <section class="activity-cancel-refund-cases">
    <cy-page-header
      eyebrow="资金与售后 / 退款处理台"
      title="部分履约售后工单"
      description="记录部分票或部分章节已履约的售后请求；平台评估补位或退款，判额、处理凭证与第二人复核分步留痕。"
    >
      <el-button icon="el-icon-back" @click="$emit('back')">返回退款处理台</el-button>
    </cy-page-header>

    <section class="activity-cancel-refund-cases__notice" role="note">
      <i class="el-icon-warning-outline" />
      <div>
        <strong>已复核凭证不等于支付渠道已确认到账。</strong>
        <p>处理人与复核人必须是不同的两个人。本队列不会自动打款，也不会把父订单现金错误绑定到某一张子票。</p>
      </div>
    </section>

    <section class="activity-cancel-refund-cases__filter">
      <el-form :inline="true" size="small" :model="queryParams">
        <el-form-item label="俱乐部 ID">
          <el-input v-model="queryParams.clubId" clearable placeholder="全部俱乐部" @keyup.enter.native="handleQuery" />
        </el-form-item>
        <el-form-item label="处理状态">
          <el-select v-model="queryParams.status" clearable placeholder="全部状态">
            <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleQuery">查询</el-button>
          <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <cy-data-table
      :data="list"
      :columns="tableColumns"
      :loading="loading"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      default-density="comfortable"
      empty-text="当前没有部分履约售后工单"
      @pagination="getList"
      @refresh="getList"
    >
      <template slot="case" slot-scope="scope">
        <div class="case-stack"><strong>Case #{{ scope.row.id }}</strong><small>活动 #{{ scope.row.activityId }} · 俱乐部 #{{ scope.row.clubId || '—' }}</small></div>
      </template>
      <template slot="order" slot-scope="scope">
        <div class="case-stack"><span>{{ scope.row.parentOrderNo }}</span><small>付款人 #{{ scope.row.payerMemberId }} · 主单 #{{ scope.row.payerRegistrationId }}</small></div>
      </template>
      <template slot="eligibility" slot-scope="scope">
        <div class="eligibility-line"><span>未核销</span><el-tag v-for="id in ids(scope.row.unverifiedRegistrationIds)" :key="`u-${scope.row.id}-${id}`" size="mini" type="warning">#{{ id }}</el-tag></div>
        <div class="eligibility-line"><span>已核销</span><el-tag v-for="id in ids(scope.row.verifiedRegistrationIds)" :key="`v-${scope.row.id}-${id}`" size="mini" type="info">#{{ id }}</el-tag></div>
      </template>
      <template slot="amount" slot-scope="scope">
        <div class="case-stack"><strong>冻结 ¥{{ moneyFen(scope.row.paidCashAmountFen) }}</strong><small>判额 ¥{{ moneyFen(scope.row.assessedRefundAmountFen) }} · 登记 ¥{{ moneyFen(scope.row.actualRefundAmountFen) }}</small></div>
      </template>
      <template slot="status" slot-scope="scope">
        <cy-status-tag :label="statusLabel(scope.row.status)" :tone="statusTone(scope.row.status)" />
      </template>
      <template slot="handlers" slot-scope="scope">
        <div class="case-stack"><span>处理 #{{ scope.row.handledByUserId || '—' }}</span><small>复核 #{{ scope.row.verifiedByUserId || '—' }}</small></div>
      </template>
      <template slot="actions" slot-scope="scope">
        <el-button size="mini" type="text" icon="el-icon-view" @click="openDetail(scope.row)">调证</el-button>
        <el-button v-if="scope.row.status === 'PENDING_ASSESSMENT'" size="mini" type="text" @click="openAssess(scope.row)">判额</el-button>
        <el-button v-if="scope.row.status === 'MANUAL_REFUND_REQUIRED'" size="mini" type="text" @click="openHandle(scope.row)">登记凭证</el-button>
        <el-button v-if="scope.row.status === 'MANUAL_REFUND_RECORDED'" size="mini" type="text" @click="openVerify(scope.row)">第二人复核</el-button>
        <el-button v-if="scope.row.status === 'MANUAL_REFUND_RECORDED'" size="mini" type="text" class="danger-action" @click="openCorrection(scope.row, 'rework')">退回重做</el-button>
        <el-button v-if="scope.row.status === 'NO_REFUND'" size="mini" type="text" @click="openCorrection(scope.row, 'reopen')">重新评估</el-button>
      </template>
    </cy-data-table>

    <el-drawer title="部分履约售后详情" :visible.sync="detailOpen" size="580px" append-to-body>
      <div v-loading="detailLoading" class="case-detail">
        <template v-if="detail">
          <h3>Case #{{ detail.id }} · {{ statusLabel(detail.status) }}</h3>
          <dl>
            <div><dt>父订单</dt><dd>{{ detail.parentOrderNo }}</dd></div>
            <div><dt>活动 / 俱乐部</dt><dd>#{{ detail.activityId }} / #{{ detail.clubId || '—' }}</dd></div>
            <div><dt>付款人与支付主单</dt><dd>#{{ detail.payerMemberId }} / #{{ detail.payerRegistrationId }}</dd></div>
            <div><dt>冻结现金</dt><dd>¥{{ moneyFen(detail.paidCashAmountFen) }}</dd></div>
            <div><dt>判额 / 实际登记</dt><dd>¥{{ moneyFen(detail.assessedRefundAmountFen) }} / ¥{{ moneyFen(detail.actualRefundAmountFen) }}</dd></div>
            <div><dt>人工渠道</dt><dd>{{ channelLabel(detail.manualChannel) }}</dd></div>
          </dl>
          <section v-if="detail.caseType === 'PARTIAL_CHAPTERS'">
            <strong>章节履约快照</strong>
            <p v-for="chapter in chapterEntries(detail)" :key="chapter.registrationId + ':' + chapter.chapterId">
              票 #{{ chapter.registrationId }} · {{ chapter.chapterName || ('章节 #' + chapter.chapterId) }}：{{ chapter.status === 1 ? '已履约' : '未履约' }}
            </p>
          </section>
          <section v-else><strong>资格快照</strong><p>未核销：{{ ids(detail.unverifiedRegistrationIds).join('、') || '—' }}</p><p>已核销：{{ ids(detail.verifiedRegistrationIds).join('、') || '—' }}</p></section>
          <section><strong>判额说明</strong><p>{{ detail.assessmentNote || '—' }}</p></section>
          <section>
            <strong>人工处理说明</strong><p>{{ detail.handleNote || '—' }}</p>
            <el-link v-if="detail.evidencePreviewUrl" type="primary" :href="detail.evidencePreviewUrl" target="_blank" rel="noopener noreferrer">打开私有凭证（5 分钟有效）</el-link>
            <code v-if="detail.evidenceSha256">服务端 SHA-256 {{ detail.evidenceSha256 }}</code>
          </section>
          <section><strong>复核说明</strong><p>{{ detail.verificationNote || '—' }}</p><small>{{ detail.evidenceVerificationNotice || evidenceNotice }}</small></section>
        </template>
      </div>
    </el-drawer>

    <el-dialog title="人工退款判额" :visible.sync="assessOpen" width="540px" append-to-body>
      <el-form :model="assessForm" label-width="110px">
        <el-form-item label="判断结果">
          <el-radio-group v-model="assessForm.decision">
            <el-radio label="MANUAL_REFUND_REQUIRED">需要人工退款</el-radio>
            <el-radio label="NO_REFUND">无需退款</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="assessForm.decision === 'MANUAL_REFUND_REQUIRED'" label="退款判额">
          <el-input v-model.trim="assessForm.amountYuan" placeholder="最多两位小数"><template slot="prepend">¥</template></el-input>
        </el-form-item>
        <el-form-item label="判断依据"><el-input v-model.trim="assessForm.note" type="textarea" :rows="4" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <div slot="footer"><el-button type="primary" :loading="submitting" @click="submitAssess">确认判额</el-button><el-button :disabled="submitting" @click="assessOpen = false">取消</el-button></div>
    </el-dialog>

    <el-dialog title="登记人工退款凭证" :visible.sync="handleOpen" width="560px" append-to-body>
      <el-form :model="handleForm" label-width="120px">
        <el-form-item label="实际退款金额"><strong>¥{{ moneyFen(handleForm.actualRefundAmountFen) }}</strong><small class="form-hint">必须与已审核判额完全一致。</small></el-form-item>
        <el-form-item label="人工退款渠道">
          <el-select v-model="handleForm.channel" placeholder="请选择真实处理渠道" style="width: 100%">
            <el-option v-for="option in channelOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="退款凭证图片">
          <el-upload
            action="#"
            :auto-upload="false"
            :limit="1"
            :file-list="handleEvidenceFiles"
            accept=".jpg,.jpeg,.png,.gif"
            :on-change="onEvidenceChange"
            :on-remove="onEvidenceRemove"
          >
            <el-button size="small" icon="el-icon-upload2">选择受控凭证</el-button>
            <small slot="tip" class="form-hint">提交时先上传到私有 OSS；摘要由服务端读取对象计算，不能手填。</small>
          </el-upload>
        </el-form-item>
        <el-form-item label="处理说明"><el-input v-model.trim="handleForm.note" type="textarea" :rows="4" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <div slot="footer"><el-button type="primary" :loading="submitting" @click="submitHandle">提交待第二人复核</el-button><el-button :disabled="submitting" @click="handleOpen = false">取消</el-button></div>
    </el-dialog>

    <el-dialog title="第二人复核退款凭证" :visible.sync="verifyOpen" width="560px" append-to-body>
      <el-alert type="warning" :title="evidenceNotice" :closable="false" show-icon />
      <el-form :model="verifyForm" label-width="120px" class="verify-form">
        <el-form-item label="私有凭证">
          <el-link v-if="verifyTarget && verifyTarget.evidencePreviewUrl" type="primary" :href="verifyTarget.evidencePreviewUrl" target="_blank" rel="noopener noreferrer">打开凭证独立核对</el-link>
          <span v-else>凭证读取中…</span>
        </el-form-item>
        <el-form-item label="核对确认"><el-checkbox v-model="verifyForm.confirmed">我已打开凭证并核对金额、渠道与处理说明</el-checkbox></el-form-item>
        <el-form-item label="复核说明"><el-input v-model.trim="verifyForm.note" type="textarea" :rows="4" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <div slot="footer"><el-button type="primary" :loading="submitting" @click="submitVerify">确认凭证一致</el-button><el-button :disabled="submitting" @click="verifyOpen = false">取消</el-button></div>
    </el-dialog>

    <el-dialog :title="correctionAction === 'rework' ? '退回凭证重做' : '重新评估无需退款判定'" :visible.sync="correctionOpen" width="520px" append-to-body>
      <el-alert type="warning" title="原判断或凭证会写入不可变审计，当前责任单再回到上一步，不会覆盖历史。" :closable="false" show-icon />
      <el-form :model="correctionForm" label-width="90px" class="verify-form">
        <el-form-item label="纠错原因"><el-input v-model.trim="correctionForm.reason" type="textarea" :rows="4" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <div slot="footer"><el-button type="primary" :loading="submitting" @click="submitCorrection">确认纠错</el-button><el-button :disabled="submitting" @click="correctionOpen = false">取消</el-button></div>
    </el-dialog>
  </section>
</template>

<script>
import {
  assessActivityCancelRefundCase, getActivityCancelRefundCase, handleActivityCancelRefundCase,
  listActivityCancelRefundCases, reopenActivityCancelRefundAssessment,
  reworkActivityCancelRefundCase, uploadActivityCancelRefundEvidence,
  verifyActivityCancelRefundCase
} from '@/api/business/refund'
import CyDataTable from '@/components/CyDataTable'
import CyPageHeader from '@/components/CyPageHeader'
import CyStatusTag from '@/components/CyStatusTag'

const STATUS_OPTIONS = [
  { value: 'PENDING_ASSESSMENT', label: '待判额' },
  { value: 'MANUAL_REFUND_REQUIRED', label: '待人工退款' },
  { value: 'MANUAL_REFUND_RECORDED', label: '待第二人复核' },
  { value: 'MANUAL_REFUND_EVIDENCE_VERIFIED', label: '凭证已复核' },
  { value: 'NO_REFUND', label: '无需退款' }
]

const CHANNEL_OPTIONS = [
  { value: 'original_retry', label: '原路重试' },
  { value: 'offline_transfer', label: '线下转账' },
  { value: 'wx_abnormal_change', label: '微信异常退款到零钱' },
  { value: 'wx_abnormal_bankcard', label: '微信异常退款到银行卡' }
]

const STATUS_TONE = {
  PENDING_ASSESSMENT: 'warning',
  MANUAL_REFUND_REQUIRED: 'danger',
  MANUAL_REFUND_RECORDED: 'warning',
  MANUAL_REFUND_EVIDENCE_VERIFIED: 'success',
  NO_REFUND: 'neutral'
}

// 枚举只接受合同规定的标量键: 字符串枚举只认非空字符串, 数字枚举只认整数或纯整数字符串。
// 数组/布尔/对象/小数/空白一律失败关闭; 只认自有键, 避免原型键渲染 native function。
function strictInt(value) {
  if (typeof value === 'number') return Number.isInteger(value) ? value : null
  if (typeof value === 'string' && /^-?\d+$/.test(value)) return Number(value)
  return null
}
function enumKey(value, numeric) {
  if (numeric) {
    const parsed = strictInt(value)
    return parsed === null ? null : String(parsed)
  }
  return typeof value === 'string' && value !== '' ? value : null
}
function enumLookup(map, value, fallback, numeric) {
  const key = enumKey(value, numeric)
  if (key === null) return fallback
  return Object.prototype.hasOwnProperty.call(map, key) ? map[key] : fallback
}

function firstQueryValue(value) { return Array.isArray(value) ? value[0] : value }
function positiveInteger(value, fallback) {
  const parsed = Number(firstQueryValue(value))
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback
}
function optionalQueryValue(value) {
  const normalized = firstQueryValue(value)
  return normalized === undefined || normalized === null || normalized === '' ? null : String(normalized)
}
function createQueryParams(query = {}) {
  return {
    pageNum: positiveInteger(query.pageNum, 1),
    pageSize: positiveInteger(query.pageSize, 10),
    clubId: optionalQueryValue(query.clubId),
    status: STATUS_OPTIONS.some(item => item.value === firstQueryValue(query.status)) ? firstQueryValue(query.status) : null
  }
}

export default {
  name: 'ActivityCancelRefundCases',
  components: { CyDataTable, CyPageHeader, CyStatusTag },
  props: { routeQuery: { type: Object, default: () => ({}) } },
  data() {
    return {
      statusOptions: STATUS_OPTIONS,
      channelOptions: CHANNEL_OPTIONS,
      evidenceNotice: '凭证摘要已由第二人复核，不代表微信、银行或用户已确认到账。',
      loading: false,
      listRequestId: 0,
      list: [],
      total: 0,
      queryParams: createQueryParams(),
      tableColumns: [
        { key: 'case', label: '责任单', minWidth: 175 },
        { key: 'order', label: '父订单 / 付款人', minWidth: 235 },
        { key: 'eligibility', label: '资格快照', minWidth: 210 },
        { key: 'amount', label: '金额', minWidth: 205 },
        { key: 'status', label: '处理状态', minWidth: 145 },
        { key: 'handlers', label: '处理 / 复核', minWidth: 140 },
        { key: 'actions', label: '处理', width: 330, fixed: 'right', align: 'right', locked: true }
      ],
      detailOpen: false,
      detailLoading: false,
      detailRequestId: 0,
      detail: null,
      submitting: false,
      assessOpen: false,
      assessForm: {},
      handleOpen: false,
      handleForm: {},
      handleEvidenceFiles: [],
      verifyOpen: false,
      verifyRequestId: 0,
      verifyTarget: null,
      verifyForm: {},
      correctionOpen: false,
      correctionAction: null,
      correctionForm: {}
    }
  },
  watch: {
    routeQuery: {
      immediate: true,
      handler(query) {
        this.queryParams = createQueryParams(query)
        this.getList()
      }
    }
  },
  methods: {
    chapterEntries(row) {
      try {
        const chapters = JSON.parse(row.chapterSnapshotJson)
        return Array.isArray(chapters) ? chapters : []
      } catch (error) {
        return []
      }
    },
    ids(value) { return Array.isArray(value) ? value : [] },
    moneyFen(value) {
      if (value === null || value === undefined) return '—'
      const normalized = String(value).trim()
      if (!/^\d+$/.test(normalized)) return '—'
      const integer = normalized.replace(/^0+(?=\d)/, '') || '0'
      const padded = integer.padStart(3, '0')
      return `${padded.slice(0, -2)}.${padded.slice(-2)}`
    },
    statusLabel(status) {
      const match = STATUS_OPTIONS.find(item => item.value === status)
      return match ? match.label : '状态异常'
    },
    statusTone(status) {
      return enumLookup(STATUS_TONE, status, 'neutral', false)
    },
    channelLabel(channel) {
      const match = CHANNEL_OPTIONS.find(item => item.value === channel)
      return match ? match.label : (typeof channel === 'string' && channel !== '' ? channel : '—')
    },
    requestId(action, caseId) { return `activity-cancel-${action}-${caseId}-${Date.now().toString(36)}` },
    yuanToFen(value) {
      const normalized = String(value === undefined || value === null ? '' : value).trim()
      if (!/^(0|[1-9]\d{0,9})(\.\d{1,2})?$/.test(normalized)) return null
      const parts = normalized.split('.')
      return Number(parts[0]) * 100 + Number((parts[1] || '').padEnd(2, '0'))
    },
    getList() {
      const requestId = ++this.listRequestId
      this.loading = true
      return listActivityCancelRefundCases(this.queryParams).then(response => {
        if (requestId !== this.listRequestId) return
        this.list = response.rows || []
        this.total = response.total || 0
      }).finally(() => { if (requestId === this.listRequestId) this.loading = false })
    },
    handleQuery() { this.queryParams.pageNum = 1; return this.getList() },
    resetQuery() { this.queryParams = createQueryParams(); return this.getList() },
    openDetail(row) {
      const requestId = ++this.detailRequestId
      this.detailOpen = true
      this.detailLoading = true
      this.detail = null
      return getActivityCancelRefundCase(row.id)
        .then(response => {
          if (requestId !== this.detailRequestId) return
          this.detail = response.data || null
        })
        .finally(() => {
          if (requestId === this.detailRequestId) this.detailLoading = false
        })
    },
    openAssess(row) {
      this.assessForm = { caseId: row.id, expectedVersion: row.version, decision: 'MANUAL_REFUND_REQUIRED', amountYuan: '', note: '', requestId: this.requestId('assess', row.id) }
      this.assessOpen = true
    },
    submitAssess() {
      const amountFen = this.assessForm.decision === 'NO_REFUND' ? 0 : this.yuanToFen(this.assessForm.amountYuan)
      if (amountFen === null || (this.assessForm.decision === 'MANUAL_REFUND_REQUIRED' && amountFen <= 0)) return this.$modal.msgError('人工退款判额必须大于 0，且最多保留两位小数')
      if (!this.assessForm.note) return this.$modal.msgError('请填写判断依据')
      this.submitting = true
      return assessActivityCancelRefundCase({
        caseId: this.assessForm.caseId,
        expectedVersion: this.assessForm.expectedVersion,
        decision: this.assessForm.decision,
        assessedRefundAmountFen: amountFen,
        note: this.assessForm.note,
        requestId: this.assessForm.requestId
      }).then(() => {
        this.$modal.msgSuccess(this.assessForm.decision === 'NO_REFUND' ? '已记录无需退款判断' : '判额完成，已进入人工退款处理')
        this.assessOpen = false
        return this.getList()
      }).finally(() => { this.submitting = false })
    },
    openHandle(row) {
      this.handleForm = {
        caseId: row.id,
        expectedVersion: row.version,
        actualRefundAmountFen: row.assessedRefundAmountFen,
        channel: null,
        evidenceObjectKey: null,
        note: '',
        requestId: this.requestId('handle', row.id)
      }
      this.handleEvidenceFiles = []
      this.handleOpen = true
    },
    onEvidenceChange(file, files) {
      this.handleEvidenceFiles = files.slice(-1)
      this.handleForm.evidenceObjectKey = null
    },
    onEvidenceRemove() {
      this.handleEvidenceFiles = []
      this.handleForm.evidenceObjectKey = null
    },
    submitHandle() {
      if (!this.handleForm.channel) return this.$modal.msgError('请选择真实人工退款渠道')
      const evidenceFile = this.handleEvidenceFiles[0] && this.handleEvidenceFiles[0].raw
      if (!this.handleForm.evidenceObjectKey && !evidenceFile) return this.$modal.msgError('请选择退款凭证图片')
      if (!this.handleForm.note) return this.$modal.msgError('请填写人工处理说明')
      this.submitting = true
      const upload = this.handleForm.evidenceObjectKey
        ? Promise.resolve({ objectKey: this.handleForm.evidenceObjectKey })
        : uploadActivityCancelRefundEvidence(evidenceFile, {
          caseId: this.handleForm.caseId,
          expectedVersion: this.handleForm.expectedVersion,
          requestId: this.handleForm.requestId
        })
      return upload.then(response => {
        const objectKey = response && (response.objectKey || (response.data && response.data.objectKey))
        if (!objectKey) throw new Error('凭证上传未返回受控对象标识')
        this.handleForm.evidenceObjectKey = objectKey
        return handleActivityCancelRefundCase({ ...this.handleForm })
      }).then(() => {
        this.$modal.msgSuccess('人工退款凭证已登记，等待第二人复核')
        this.handleOpen = false
        return this.getList()
      }).finally(() => { this.submitting = false })
    },
    openVerify(row) {
      const requestId = ++this.verifyRequestId
      this.verifyTarget = null
      this.verifyForm = { caseId: row.id, expectedVersion: row.version, confirmed: false, note: '', requestId: this.requestId('verify', row.id) }
      this.verifyOpen = true
      return getActivityCancelRefundCase(row.id).then(response => {
        if (requestId !== this.verifyRequestId) return
        this.verifyTarget = response.data || null
      })
    },
    submitVerify() {
      if (!this.verifyTarget || !this.verifyTarget.evidencePreviewUrl) return this.$modal.msgError('凭证尚未可用，请重新打开复核')
      if (!this.verifyForm.confirmed) return this.$modal.msgError('请先打开并核对受控凭证')
      if (!this.verifyForm.note) return this.$modal.msgError('请填写复核说明')
      this.submitting = true
      const payload = { ...this.verifyForm }
      delete payload.confirmed
      return verifyActivityCancelRefundCase(payload).then(() => {
        this.$modal.msgSuccess('凭证已由第二人复核；仍不代表支付渠道确认到账')
        this.verifyOpen = false
        return this.getList()
      }).finally(() => { this.submitting = false })
    },
    openCorrection(row, action) {
      this.correctionAction = action
      this.correctionForm = {
        caseId: row.id,
        expectedVersion: row.version,
        reason: '',
        requestId: this.requestId(action, row.id)
      }
      this.correctionOpen = true
    },
    submitCorrection() {
      if (!this.correctionForm.reason) return this.$modal.msgError('请填写纠错原因')
      this.submitting = true
      const action = this.correctionAction === 'rework'
        ? reworkActivityCancelRefundCase
        : reopenActivityCancelRefundAssessment
      return action({ ...this.correctionForm }).then(() => {
        this.$modal.msgSuccess(this.correctionAction === 'rework' ? '已退回重新登记凭证' : '已重开人工判额')
        this.correctionOpen = false
        return this.getList()
      }).finally(() => { this.submitting = false })
    }
  }
}
</script>

<style lang="scss" scoped>
.activity-cancel-refund-cases__notice { display: flex; gap: 14px; margin-bottom: 18px; padding: 16px 18px; border: 1px solid #f3d19e; border-radius: var(--cy-radius-card); background: #fdf6ec; color: #8a5a12; }
.activity-cancel-refund-cases__notice i { margin-top: 2px; font-size: 20px; }
.activity-cancel-refund-cases__notice strong, .activity-cancel-refund-cases__notice p { display: block; margin: 0; }
.activity-cancel-refund-cases__notice p { margin-top: 5px; font-size: 13px; line-height: 1.55; }
.activity-cancel-refund-cases__filter { margin-bottom: 18px; padding: 16px 18px 0; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); }
.case-stack span, .case-stack strong, .case-stack small { display: block; }
.case-stack small { margin-top: 4px; color: var(--cy-color-text-tertiary); }
.eligibility-line { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; margin: 3px 0; }
.eligibility-line > span { width: 48px; color: var(--cy-color-text-tertiary); font-size: 12px; }
.case-detail { padding: 0 24px 30px; }
.case-detail h3 { margin: 0 0 16px; }
.case-detail dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.case-detail dl div, .case-detail section { padding: 12px; border-radius: 8px; background: var(--cy-color-bg-subtle); }
.case-detail dt { color: var(--cy-color-text-tertiary); font-size: 12px; }
.case-detail dd { margin: 5px 0 0; font-weight: 700; }
.case-detail section { margin-top: 10px; }
.case-detail section p { margin: 6px 0 0; color: var(--cy-color-text-secondary); line-height: 1.55; }
.case-detail code, .verify-form code { overflow-wrap: anywhere; font-size: 11px; }
.case-detail small { display: block; margin-top: 8px; color: var(--cy-color-status-warning); }
.form-hint { display: block; color: var(--cy-color-text-tertiary); }
.danger-action { color: var(--cy-color-status-danger); }
.verify-form { margin-top: 18px; }
@media (max-width: 700px) { .case-detail dl { grid-template-columns: 1fr; } }
</style>
