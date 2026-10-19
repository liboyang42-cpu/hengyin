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
          :meta="taskMeta(scope.row)"
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
          :disabled="reviewSubmitting || !canReview(scope.row)"
          @click="openReview(scope.row, 1)"
          v-hasPermi="scope.row.bizType === 'MEDIA_ASSET' ? ['business:mediaReview:approve'] : ['business:audit:review']"
        >通过</el-button>
        <el-button
          size="mini"
          type="text"
          class="danger-action"
          :disabled="reviewSubmitting || !canReview(scope.row)"
          @click="openReview(scope.row, 2)"
          v-hasPermi="scope.row.bizType === 'MEDIA_ASSET' ? ['business:mediaReview:reject'] : ['business:audit:review']"
        >拒绝</el-button>
      </template>
    </cy-data-table>

    <el-drawer title="审核证据链" :visible.sync="detailOpen" size="640px" append-to-body @close="closeDetail">
      <div v-loading="detailLoading" class="audit-detail">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon />
        <el-button v-if="detailError" size="mini" @click="loadDetail(detailTaskId)">重试读取</el-button>
        <template v-if="detailTask">
          <cy-entity-header
            :eyebrow="`${bizTypeLabel(detailTask.bizType)} · #${detailTask.bizId}`"
            :title="`审核任务 #${detailTask.id}`"
            :status="{ label: statusLabel(detailTask.status), tone: statusTone(detailTask.status) }"
            :facts="auditHeaderFacts"
          >
            <template slot="actions">
<<<<<<< public
              <el-button size="mini" type="primary" :disabled="!canReview(detailTask)" @click="openReview(detailTask, 1)" v-hasPermi="detailTask.bizType === 'MEDIA_ASSET' ? ['business:mediaReview:approve'] : ['business:audit:review']">通过</el-button>
              <el-button size="mini" type="text" class="danger-action" :disabled="!canReview(detailTask)" @click="openReview(detailTask, 2)" v-hasPermi="detailTask.bizType === 'MEDIA_ASSET' ? ['business:mediaReview:reject'] : ['business:audit:review']">拒绝</el-button>
=======
              <el-button size="mini" type="primary" :disabled="detailLoading || reviewSubmitting || !canReview(detailTask)" @click="openReview(detailTask, 1)" v-hasPermi="['business:audit:review']">通过</el-button>
              <el-button size="mini" type="text" class="danger-action" :disabled="detailLoading || reviewSubmitting || !canReview(detailTask)" @click="openReview(detailTask, 2)" v-hasPermi="['business:audit:review']">拒绝</el-button>
>>>>>>> private-github/featu
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
<<<<<<< public
          <template v-if="detailTask.bizType === 'MEDIA_ASSET'">
            <el-alert title="文件在审核通过前仅作者可见。请实际检查图片、音频或模型；无法检查时请拒绝并填写原因，作者可重新提审。" type="info" :closable="false" />
            <el-image v-if="mediaType === 'IMAGE' && mediaPreviewUrl" :src="mediaPreviewUrl" fit="contain" style="max-width: 100%; max-height: 480px" :preview-src-list="[mediaPreviewUrl]" />
            <audio v-else-if="mediaType === 'AUDIO' && mediaPreviewUrl" :src="mediaPreviewUrl" controls preload="none" />
            <el-link v-else-if="mediaPreviewUrl" :href="mediaPreviewUrl" target="_blank" rel="noopener noreferrer">下载并检查模型文件</el-link>
            <el-button size="mini" @click="openDetail(detailRow)">刷新五分钟预览</el-button>
          </template>
          <cy-evidence-panel title="内容快照" :items="auditEvidenceItems" empty-text="当前任务没有附带内容快照" />
=======
          <el-alert v-if="isCandidateTask(detailTask)" :title="candidateScopeNotice" type="warning" :closable="false" show-icon />
          <el-alert v-if="candidateDetailError" :title="candidateDetailError" type="error" :closable="false" />
          <cy-evidence-panel :title="isCandidateTask(detailTask) ? '候选绑定元数据（不含正文）' : '内容快照'" :items="auditEvidenceItems" empty-text="当前任务没有附带内容快照" />
          <section v-if="isCandidateTask(detailTask)" class="candidate-preview" aria-label="冻结候选部分文本预览" data-testid="candidate-preview">
            <p>只读、部分白名单文本。未覆盖媒体、答案、配置、依赖与权利证据；预览成功不代表完整内容已核验，也不授予发布、权利或 AI 使用许可。</p>
            <p v-if="!hasCandidatePreviewPermission" role="status">当前会话没有审核权限，不能读取候选文本。</p>
            <p>服务端将重新核对当前权限、主题范围和任务分配；拥有菜单或任务分配不等于拥有主题范围。</p>
            <el-button size="mini" :disabled="!canLoadCandidatePreview" @click="loadCandidatePreview" v-hasPermi="['business:audit:review']" data-testid="candidate-preview-load">{{ previewLoading ? '正在读取冻结文本' : '读取冻结候选部分文本' }}</el-button>
            <el-button v-if="previewLoading || previewLoaded || previewError" size="mini" @click="clearCandidatePreview" data-testid="candidate-preview-clear">{{ previewLoading ? '取消预览读取' : '清除预览' }}</el-button>
            <el-button v-if="previewNeedsRefresh" size="mini" @click="loadDetail(detailTaskId)" data-testid="candidate-preview-refresh">重新读取绑定详情</el-button>
            <p v-if="previewBlocked" role="status">本次预览已暂停。请重新读取绑定详情或关闭后重新核对权限。</p>
            <el-alert v-if="previewError" :title="previewError" type="error" :closable="false" show-icon data-testid="candidate-preview-error" />
            <p v-if="previewLoaded" role="status" data-testid="candidate-preview-bound">已核对当前任务、版本、候选快照和内容哈希；仅显示下列部分文本。</p>
            <cy-evidence-panel v-if="previewLoaded" title="冻结候选部分白名单文本" :items="previewItems" empty-text="此冻结候选没有可展示的白名单文本；不代表完整内容为空或已通过核验。" data-testid="candidate-preview-text" />
          </section>
>>>>>>> private-github/featu
          <cy-timeline title="处置轨迹" :items="auditTimelineItems" empty-text="尚无处置记录" />
        </template>
      </div>
    </el-drawer>

    <el-dialog title="审核处理" :visible.sync="reviewOpen" width="560px" append-to-body
      :close-on-click-modal="!reviewSubmitting" :close-on-press-escape="!reviewSubmitting" :show-close="!reviewSubmitting" @close="closeReview">
      <div v-loading="reviewLoading">
        <el-alert v-if="isCandidateTask(reviewTask)" :title="candidateScopeNotice" type="warning" :closable="false" show-icon />
        <cy-evidence-panel v-if="isCandidateTask(reviewTask)" title="本次处理绑定" :items="candidateReviewItems" />
        <el-alert v-if="reviewError" :title="reviewError" type="error" :closable="false" show-icon class="review-feedback" />
        <p v-if="reviewCurrentTask" class="review-current" role="status">最近读取状态：{{ statusLabel(reviewCurrentTask.status) }} · 任务版本 {{ reviewCurrentTask.version }}。{{ reviewRetryPayload ? '重试将保留原请求，最终结果由服务端核验。' : '请核对后操作。' }}</p>
        <el-form ref="reviewForm" :model="reviewForm" label-width="84px">
          <el-form-item label="处理结果">
            <cy-status-tag :label="statusLabel(reviewForm.status)" :tone="statusTone(reviewForm.status)" />
          </el-form-item>
          <el-form-item label="处理原因" prop="reasonText">
            <el-input v-model="reviewForm.reasonText" :disabled="reviewSubmitting || !!reviewRetryPayload" type="textarea" :rows="4" maxlength="200" show-word-limit placeholder="填写可向相关用户公开的处理原因；内部凭证和个人信息请勿填入" />
          </el-form-item>
        </el-form>
      </div>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="reviewSubmitting" :disabled="reviewLoading || reviewBlocked" @click="submitReview">{{ reviewRetryPayload ? '重试原请求' : '确认处理' }}</el-button>
        <el-button :disabled="reviewSubmitting" @click="reviewOpen = false">{{ reviewRetryPayload || reviewBlocked ? '关闭并重新核对' : '取消' }}</el-button>
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
<<<<<<< public
import { getAuditTaskDetail, listAuditTasks, reviewAuditTask, reviewMediaAsset, submitTopicProjectReview, submitTopicSaleReview } from '@/api/business/auditWorkbench'
=======
import { getCandidatePreview, getAuditTaskDetail, listAuditTasks, reviewAuditTask, submitTopicProjectReview, submitTopicSaleReview } from '@/api/business/auditWorkbench'
>>>>>>> private-github/featu
import CyDataTable from '@/components/CyDataTable'
import CyEntityHeader from '@/components/CyEntityHeader'
import CyEntityLink from '@/components/CyEntityLink'
import CyEvidencePanel from '@/components/CyEvidencePanel'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyStatusTag from '@/components/CyStatusTag'
import CyTimeline from '@/components/CyTimeline'

const CANDIDATE_TYPE = 'CONTENT_CAPTURE_REVIEW'
const CANDIDATE_SOURCE = 'content_capture_review_v1'
// Defense in depth for stale servers; generic permissions never grant NPC draft visibility.
function isNpcDraft(task) {
  return !!task && (task.bizType === 'MERCHANT_NPC_KNOWLEDGE_DRAFT'
    || task.sourceType === 'merchant_npc_public_facts_draft_v1')
}
const CANDIDATE_SCOPE_NOTICE = '候选绑定元数据不含正文。可主动读取当前绑定的冻结候选部分白名单文本；预览不是完整内容核验、权利证明或 AI 授权。通过仅记录候选审核结论，不构成完整发布、上架或授权。'

// 只展示服务端已定义的短字段，不展开未知 payload，不将可变 TOPIC 内容当成冻结候选。
function candidateSummary(task) {
  const invalid = { valid: false, items: [], error: '候选绑定缺失、格式不受支持或标识超出浏览器安全整数范围，请核对后重试。' }
  if (!task || task.bizType !== CANDIDATE_TYPE || task.sourceType !== CANDIDATE_SOURCE
      || typeof task.snapshotJson !== 'string' || task.snapshotJson.length > 4096) return invalid
  let value
  try { value = JSON.parse(task.snapshotJson) } catch (error) { return invalid }
  const positiveId = id => Number.isSafeInteger(id) && id > 0
  if (!value || Array.isArray(value) || value.schemaVersion !== 1 || value.contract !== CANDIDATE_SOURCE
      || value.candidateContract !== 'questify.content-capture-candidate.v1'
      || value.canonicalizationVersion !== 'questify.canonical-content-json.v1'
      || value.completeRelease !== false || value.reviewScope !== 'INCOMPLETE_CANDIDATE_ONLY'
      || !positiveId(value.snapshotId) || !positiveId(value.topicId) || !positiveId(value.submitterMemberId)
      || !positiveId(task.id) || value.topicId !== task.bizId || value.submitterMemberId !== task.submitterId
      || !Number.isSafeInteger(value.sourceConfigVersion) || value.sourceConfigVersion < 0
      || !Number.isSafeInteger(task.version) || task.version < 0
      || ![0, 1, 2, 3, 4].includes(task.status)
      || typeof value.contentHash !== 'string' || !/^[0-9a-f]{64}$/.test(value.contentHash)) return invalid
  return { valid: true, value, error: '', items: [{ kind: 'text', label: '持久化候选身份', content: [
    `候选快照 ID：${value.snapshotId}`, `主题 ID：${value.topicId}`, `提交成员 ID：${value.submitterMemberId}`,
    `来源配置版本：${value.sourceConfigVersion}`, `内容 SHA-256：${value.contentHash}`,
    `审核任务 ID：${task.id}`, `审核任务版本：${task.version}`, `来源：${CANDIDATE_SOURCE}`,
    `候选契约：${value.candidateContract}`, `规范化契约：${value.canonicalizationVersion}`,
    '审核范围：不完整候选（INCOMPLETE_CANDIDATE_ONLY）', '完整发布：否；此审核不赋予发布资格',
    '冻结候选正文：需主动读取部分白名单文本，未覆盖完整内容'
  ].join('\n') }] }
}

// Pinned to questify.content-capture-preview.v1, not the mutable narrative/editor model.
const PREVIEW_PATHS = new Set([
  'blockConfig.brief.body',
  'blockConfig.brief.carry[].text',
  'blockConfig.brief.question',
  'blockConfig.check.failText',
  'blockConfig.check.mods[].label',
  'blockConfig.check.skill',
  'blockConfig.check.successText',
  'blockConfig.classify.bins[].label',
  'blockConfig.classify.items[].label',
  'blockConfig.classify.prompt',
  'blockConfig.compare.left.items[].text',
  'blockConfig.compare.left.items[].time',
  'blockConfig.compare.left.label',
  'blockConfig.compare.prompt',
  'blockConfig.compare.right.items[].text',
  'blockConfig.compare.right.items[].time',
  'blockConfig.compare.right.label',
  'blockConfig.deliver.line',
  'blockConfig.deliver.reward',
  'blockConfig.enter.aside',
  'blockConfig.enter.opener',
  'blockConfig.enter.questions[].a',
  'blockConfig.enter.questions[].q',
  'blockConfig.match.left[].label',
  'blockConfig.match.prompt',
  'blockConfig.match.right[].label',
  'blockConfig.outcome.facts[].text',
  'blockConfig.outcome.line',
  'blockConfig.outcome.lineIfFail',
  'blockConfig.outcome.open',
  'blockConfig.outcome.title',
  'blockConfig.revisit.changes[].text',
  'blockConfig.route.todo',
  'blockConfig.route.walk',
  'blockConfig.sort.items[].label',
  'blockConfig.sort.prompt',
  'blockConfig.unlock.hook',
  'journeyStory.call.brief',
  'journeyStory.call.opener',
  'journeyStory.call.options[].line',
  'journeyStory.call.options[].reply',
  'journeyStory.ending.badges[].title',
  'journeyStory.ending.endings[].summary',
  'journeyStory.ending.endings[].title',
  'journeyStory.ending.epilogues[].ally',
  'journeyStory.ending.epilogues[].guarded',
  'journeyStory.ending.epilogues[].neutral',
  'journeyStory.ending.exhibits[].text',
  'journeyStory.stages[].end.changed[].text',
  'journeyStory.stages[].end.open',
  'journeyStory.stages[].question',
])
const PREVIEW_KEYS = ['contract', 'auditTaskId', 'auditTaskVersion', 'status', 'snapshotId', 'topicId',
  'sourceConfigVersion', 'contentHash', 'textEncoding', 'reviewScope', 'completeRelease',
  'publicationEligible', 'previewComplete', 'entries']
function previewPathAllowed(path) {
  if (typeof path !== 'string' || path.length > 192) return false
  if (/^nodes\[(0|[1-9][0-9]*)\]\.(name|description)$/.test(path)
      || /^chapters\[(0|[1-9][0-9]*)\]\.(name|description|blocks\[(0|[1-9][0-9]*)\]\.content)$/.test(path)) return true
  const field = path.startsWith('story.') ? path.slice(6)
    : path.replace(/^nodeJourney\[[1-9][0-9]{0,18}\]\./, '')
  return field !== path && PREVIEW_PATHS.has(field)
    && (path.startsWith('story.') ? field.startsWith('journeyStory.') : field.startsWith('blockConfig.'))
}
function candidatePreviewItems(dto, task) {
  const summary = candidateSummary(task)
  const invalid = () => { throw new Error('Invalid frozen candidate preview') }
  if (!summary.valid || !dto || Array.isArray(dto) || typeof dto !== 'object'
      || Object.keys(dto).length !== PREVIEW_KEYS.length || PREVIEW_KEYS.some(key => !Object.prototype.hasOwnProperty.call(dto, key))) return invalid()
  const value = summary.value
  if (dto.contract !== 'questify.content-capture-preview.v1' || dto.textEncoding !== 'HTML_ESCAPED_PLAIN_TEXT'
      || dto.reviewScope !== 'INCOMPLETE_CANDIDATE_TEXT_ONLY' || dto.completeRelease !== false
      || dto.publicationEligible !== false || dto.previewComplete !== false
      || dto.auditTaskId !== String(task.id) || dto.auditTaskVersion !== task.version || dto.status !== task.status
      || dto.snapshotId !== String(value.snapshotId) || dto.topicId !== String(value.topicId)
      || dto.sourceConfigVersion !== String(value.sourceConfigVersion) || dto.contentHash !== value.contentHash
      || !Array.isArray(dto.entries) || dto.entries.length > 256) return invalid()
  let total = 0
  return dto.entries.map(entry => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry) || Object.keys(entry).length !== 2
        || !previewPathAllowed(entry.path) || typeof entry.text !== 'string' || !entry.text.length) return invalid()
    total += entry.text.length
    if (total > 32768 || /[<>"'\u0000-\u0008\u000b-\u001f\u007f-\u009f\p{Cf}\p{Cs}]/u.test(entry.text)
        || /&(?!amp;|lt;|gt;|quot;|#39;)/.test(entry.text)) return invalid()
    // Invert only the server's five escapes once. Never use an HTML parser, DOM, innerHTML or recursive decoding.
    // Vue interpolation below renders the result as text; literal frozen "&lt;" stays literal "&lt;".
    const escapes = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&#39;': "'" }
    const text = entry.text.replace(/&(amp|lt|gt|quot|#39);/g, entity => escapes[entity])
    // [CONTROL] expands one raw character to nine, so a decoded-length limit alone would reject valid DTOs.
    if (text.replace(/\[CONTROL\]/g, '_').length > 4096) return invalid()
    return { kind: 'text', label: entry.path, content: text }
  })
}

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
      auditViewActive: true,
      auditRoutePath: null,
      auditContextSequence: 0,
      loading: false,
      loadError: '',
      detailLoading: false,
      detailError: '',
      previewSequence: 0,
      previewLoading: false,
      previewLoaded: false,
      previewBlocked: false,
      previewNeedsRefresh: false,
      previewError: '',
      previewItems: [],
      detailTaskId: null,
      detailRow: null,
      detailSequence: 0,
      listSequence: 0,
      reviewLoading: false,
      reviewSubmitting: false,
      reviewBlocked: false,
      reviewError: '',
      reviewTask: null,
      reviewCurrentTask: null,
      reviewRetryPayload: null,
      reviewSequence: 0,
      candidateScopeNotice: CANDIDATE_SCOPE_NOTICE,
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
<<<<<<< public
        { label: '媒体文件审核', value: 'MEDIA_ASSET' },
=======
        { label: '内容候选审核（不发布）', value: 'CONTENT_CAPTURE_REVIEW' },
>>>>>>> private-github/featu
        { label: '主题立项审核', value: 'TOPIC_PROJECT_REVIEW' },
        { label: '主题开售审核', value: 'TOPIC_SALE_REVIEW' },
        { label: '主题', value: 'TOPIC' },
        { label: '活动', value: 'ACTIVITY' },
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
    // Includes credentials as an in-memory fence only; never render or transmit this key.
    auditContextKey() {
      const identity = this.$store && this.$store.getters || {}
      return JSON.stringify([this.$route && this.$route.fullPath, identity.id, identity.token,
        identity.roles || [], identity.permissions || []])
    },
    candidatePreviewOwnerKey() {
      const task = this.detailTask
      return JSON.stringify([this.detailOpen, this.detailTaskId, task && [task.id, task.bizType, task.bizId,
        task.sourceType, task.submitterId, task.currentAssignee, task.version, task.status, task.snapshotJson]])
    },
    hasCandidatePreviewPermission() {
      const permissions = this.$store && this.$store.getters && this.$store.getters.permissions || []
      return Array.isArray(permissions) && (permissions.includes('business:audit:review') || permissions.includes('*:*:*'))
    },
    canLoadCandidatePreview() {
      return this.auditViewActive && this.detailOpen && !this.detailLoading && !this.previewLoading
        && !this.previewBlocked && !this.reviewOpen && !this.reviewSubmitting
        && this.hasCandidatePreviewPermission && candidateSummary(this.detailTask).valid
    },
    candidateDetailError() {
      return this.isCandidateTask(this.detailTask) ? candidateSummary(this.detailTask).error : ''
    },
    candidateReviewItems() {
      return candidateSummary(this.reviewTask).items
    },
    auditHeaderFacts() {
      if (!this.detailTask || isNpcDraft(this.detailTask)) return []
      return [
        { label: '提交人', value: this.detailTask.submitterId ? `#${this.detailTask.submitterId}` : '—' },
        { label: '举报人数', value: this.reportCount },
        { label: '风险等级', value: this.detailTask.riskLevel || '未标注' }
      ]
    },
    auditEvidenceItems() {
      if (!this.detailTask || isNpcDraft(this.detailTask)) return []
      if (this.detailTask.bizType === CANDIDATE_TYPE) return candidateSummary(this.detailTask).items
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
      if (isNpcDraft(this.detailTask)) return []
      return this.detailRecords.map(record => {
        const action = this.actionMeta(record.action)
        return {
          id: record.id,
          title: action.label,
          summary: record.reasonText || record.reasonCode || '未填写补充说明',
          actor: record.operatorId ? `操作人 #${record.operatorId}` : '系统',
          occurredAt: this.parseTime(record.createTime, '{y}-{m}-{d} {h}:{i}') || '—',
          tone: action.tone,
          evidence: record.snapshotJson ? [this.detailTask && this.detailTask.bizType === CANDIDATE_TYPE ? '附有当时候选绑定元数据，无正文' : '附有当时内容快照'] : []
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
    candidatePreviewOwnerKey: { sync: true, handler() { this.clearCandidatePreview(); this.previewBlocked = false; this.previewNeedsRefresh = false } },
    auditContextKey: {
      // Async/batched watchers miss A -> B -> A changes in the same tick.
      sync: true,
      handler() {
        this.invalidateAuditContext()
        const sequence = this.auditContextSequence
        this.$nextTick(() => {
          if (sequence !== this.auditContextSequence || !this.auditViewActive
              || this.$route.path !== this.auditRoutePath) return
          this.applyRouteFilters()
          this.getList()
        })
      }
    }
  },
  created() {
    this.auditRoutePath = this.$route.path
    this.applyRouteFilters()
    this.getList()
  },
  activated() {
    if (this.auditViewActive) return
    this.auditViewActive = true
    this.applyRouteFilters()
    this.getList()
  },
  deactivated() {
    this.auditViewActive = false
    this.invalidateAuditContext()
  },
  beforeDestroy() {
    this.auditViewActive = false
    this.invalidateAuditContext()
  },
  methods: {
    captureAuditContext() {
      const sequence = this.auditContextSequence
      return {
        isCurrent: () => this.auditViewActive && sequence === this.auditContextSequence,
        // Re-fence after our own successful logout, whose state clears intentionally retire the old context.
        captureCurrent: () => this.captureAuditContext()
      }
    },
    applyRouteFilters() {
      this.queryParams = { ...this.queryParams, pageNum: 1,
        bizType: this.$route.query.bizType || null, bizId: this.$route.query.bizId || null, status: null }
    },
    invalidateAuditContext() {
      this.clearCandidatePreview()
      this.previewBlocked = false
      this.previewNeedsRefresh = false
      this.auditContextSequence++
      this.listSequence++
      this.detailSequence++
      this.reviewSequence++
      this.loading = false
      this.loadError = ''
      this.auditList = []
      this.total = 0
      this.detailOpen = false
      this.detailLoading = false
      this.detailError = ''
      this.detailTaskId = null
      this.detailRow = null
      this.detailTask = null
      this.detailRecords = []
      this.reportCount = 0
      this.reviewOpen = false
      this.reviewLoading = false
      this.reviewSubmitting = false
      this.reviewBlocked = false
      this.reviewError = ''
      this.reviewTask = null
      this.reviewCurrentTask = null
      this.reviewRetryPayload = null
      this.reviewForm = { taskId: null, status: null, reasonText: null }
      this.commercialSubmitOpen = false
    },
    bizTypeLabel(type) {
      const item = this.bizTypeOptions.find(option => option.value === type)
      return item ? item.label : (type || '未知类型')
    },
    getList() {
      if (!this.auditViewActive) return Promise.resolve()
      const sequence = ++this.listSequence
      this.loading = true
      this.loadError = ''
      return listAuditTasks({ ...this.queryParams }, this.captureAuditContext()).then(response => {
        if (sequence !== this.listSequence) return
        if (!response || !Array.isArray(response.rows) || !Number.isSafeInteger(response.total) || response.total < 0) {
          throw new Error('Invalid audit queue response')
        }
        if (response.rows.some(isNpcDraft)) throw new Error('审核类型暂不可读取')
        this.auditList = response.rows
        this.total = response.total
      }).catch(() => {
        if (sequence === this.listSequence) {
          this.loadError = '审核队列加载失败，请刷新重试。'
        }
      }).finally(() => { if (sequence === this.listSequence) this.loading = false })
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
    isCandidateTask(row) {
      return !!row && row.bizType === CANDIDATE_TYPE
    },
    taskMeta(row) {
      return row.bizType === CANDIDATE_TYPE ? `任务 #${row.id} · 候选审核 · 版本 ${row.version}` : `任务 #${row.id}`
    },
    canReview(row) {
      if (!row || isNpcDraft(row) || (row.bizType === CANDIDATE_TYPE && !candidateSummary(row).valid)) return false
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
      if (!this.auditViewActive || isNpcDraft(row)) return Promise.resolve()
      this.detailRow = row
      this.detailOpen = true
      return this.loadDetail(row.id)
    },
    loadDetail(id) {
      if (!this.auditViewActive) return Promise.resolve()
      this.clearCandidatePreview()
      this.previewBlocked = false
      this.previewNeedsRefresh = false
      const sequence = ++this.detailSequence
      this.detailTaskId = id
      this.detailLoading = true
      this.detailError = ''
      this.detailTask = null
      this.mediaPreviewUrl = ''; this.mediaType = ''
      this.detailRecords = []
      this.reportCount = 0
      return getAuditTaskDetail(id, this.captureAuditContext()).then(response => {
        if (sequence !== this.detailSequence || !this.detailOpen) return
        if (!response || !response.task || isNpcDraft(response.task) || response.task.id !== id
          || !Array.isArray(response.records) || !Number.isSafeInteger(response.reportCount) || response.reportCount < 0) {
          throw new Error('Invalid audit detail response')
        }
        this.mediaPreviewUrl = response.mediaPreviewUrl || ''; this.mediaType = response.mediaType || ''
        this.detailTask = response.task
        this.detailRecords = response.records
        this.reportCount = response.reportCount
      }).catch(() => {
        if (sequence === this.detailSequence && this.detailOpen) this.detailError = '审核证据加载失败，未使用队列中的旧快照。请重新加载。'
      }).finally(() => { if (sequence === this.detailSequence) this.detailLoading = false })
    },
    clearCandidatePreview() {
      this.previewSequence++
      this.previewLoading = false
      this.previewLoaded = false
      this.previewError = ''
      this.previewItems = []
    },
    loadCandidatePreview() {
      if (!this.canLoadCandidatePreview) return Promise.resolve()
      this.clearCandidatePreview()
      const sequence = this.previewSequence
      const owner = this.candidatePreviewOwnerKey
      const task = { ...this.detailTask }
      const summary = candidateSummary(task).value
      const auditContext = this.captureAuditContext()
      const current = () => auditContext.isCurrent() && sequence === this.previewSequence
        && owner === this.candidatePreviewOwnerKey && this.detailOpen && !this.reviewOpen
      // Fence request dispatch, interceptor side effects, response handling and any 401 confirmation.
      const context = { isCurrent: current, captureCurrent: auditContext.captureCurrent }
      this.previewLoading = true
      const binding = { expectedTaskVersion: task.version, snapshotId: String(summary.snapshotId),
        sourceConfigVersion: String(summary.sourceConfigVersion), contentHash: summary.contentHash }
      return getCandidatePreview(task.id, binding, context).then(response => {
        if (!current()) return
        if (!response || response.code !== 200) throw new Error('Invalid frozen candidate preview response')
        const items = candidatePreviewItems(response.data, task)
        if (!current()) return
        this.previewItems = items
        this.previewLoaded = true
      }).catch(error => {
        if (!current()) return
        this.previewItems = []
        this.previewLoaded = false
        if (error && (error.code === 409 || error.errorCode === 'CONTENT_PREVIEW_CONFLICT')) {
          this.previewBlocked = true
          this.previewNeedsRefresh = true
          this.previewError = '候选绑定或任务版本已变化。请主动重新读取绑定详情，核对后再读取预览；不会自动重试、审核或发布。'
          return
        }
        if (error && (error.code === 401 || error.code === 403 || error.errorCode === 'CONTENT_PREVIEW_FORBIDDEN')) {
          this.previewBlocked = true
          this.previewError = '当前登录、审核权限、主题范围或任务分配不允许读取预览。请恢复授权后关闭并重新读取任务；不会显示旧文本。'
        } else {
          this.previewError = '冻结文本暂不可用或返回内容无法安全核对。未使用旧文本或可变主题替代；请稍后主动重试。'
        }
      }).finally(() => { if (current()) this.previewLoading = false })
    },
    closeDetail() {
      if (this.detailOpen) return
      this.clearCandidatePreview()
      this.detailSequence++
      this.detailLoading = false
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
<<<<<<< public
      this.reviewBizType = row.bizType
=======
      if (!this.auditViewActive || this.reviewSubmitting || isNpcDraft(row)) return
      this.clearCandidatePreview()
      const sequence = ++this.reviewSequence
>>>>>>> private-github/featu
      this.reviewForm = { taskId: row.id, expectedVersion: row.version, status: status, reasonText: null }
      this.reviewTask = row
      this.reviewCurrentTask = null
      this.reviewRetryPayload = null
      this.reviewError = ''
      this.reviewBlocked = false
      this.reviewLoading = false
      this.reviewOpen = true
      if (!this.isCandidateTask(row)) return
      // 候选操作必须重新读取已有管理员详情，不调用成员提审/状态 API。
      this.reviewLoading = true
      return getAuditTaskDetail(row.id, this.captureAuditContext()).then(response => {
        if (sequence !== this.reviewSequence || !this.reviewOpen) return
        const task = response.task
        if (!task || isNpcDraft(task) || task.id !== row.id || task.bizType !== CANDIDATE_TYPE || !candidateSummary(task).valid) {
          throw new Error('候选绑定无法核对')
        }
        this.reviewTask = task
        this.reviewCurrentTask = task
        this.reviewForm.expectedVersion = task.version
        if (!this.canReview(task)) {
          this.reviewBlocked = true
          this.reviewError = '该任务已不在可审核状态，请关闭并核对最新证据。'
        }
      }).catch(() => {
        if (sequence !== this.reviewSequence) return
        this.reviewBlocked = true
        this.reviewError = '最新候选绑定读取失败或无法核对，请关闭后重新查看证据。'
      }).finally(() => { if (sequence === this.reviewSequence) this.reviewLoading = false })
    },
    closeReview() {
      if (this.reviewOpen) return
      this.reviewSequence++
      this.reviewLoading = false
      this.reviewRetryPayload = null
    },
    refreshReviewState(taskId, sequence) {
      this.getList()
      return getAuditTaskDetail(taskId, this.captureAuditContext()).then(response => {
        if (sequence !== this.reviewSequence || !this.reviewOpen) return
        if (!response.task || isNpcDraft(response.task) || response.task.id !== taskId) throw new Error('审核任务读取不完整')
        this.reviewCurrentTask = response.task
        if (this.detailOpen && this.detailTaskId === taskId) return this.loadDetail(taskId)
      }).catch(() => {
        if (sequence === this.reviewSequence) this.reviewError += ' 最新状态暂时无法读取，请稍后核对。'
      })
    },
    submitReview() {
<<<<<<< public
      const review = this.reviewBizType === 'MEDIA_ASSET' ? reviewMediaAsset : reviewAuditTask
      return review(this.reviewForm).then(() => {
        this.$modal.msgSuccess('审核处理成功')
=======
      if (isNpcDraft(this.reviewTask) || isNpcDraft(this.reviewCurrentTask)) return
      if (!this.auditViewActive || !this.reviewOpen || this.reviewSubmitting || this.reviewLoading || this.reviewBlocked) return
      if (this.isCandidateTask(this.reviewTask) && !candidateSummary(this.reviewTask).valid) return
      if (!this.reviewRetryPayload && this.reviewForm.status === STATUS_REJECTED && !String(this.reviewForm.reasonText || '').trim()) {
        this.reviewError = '拒绝审核必须填写处理原因。'
        return
      }
      const sequence = this.reviewSequence
      // 未知失败可能发生在服务端提交之后；保留原版本/结果/原因以便精确重放，绝不自动改版本再提交。
      const payload = this.reviewRetryPayload || { ...this.reviewForm }
      this.reviewRetryPayload = { ...payload }
      this.reviewSubmitting = true
      this.reviewError = ''
      return reviewAuditTask({ ...payload }, this.captureAuditContext()).then(() => {
        if (sequence !== this.reviewSequence) return
        this.reviewSubmitting = false
        this.$modal.msgSuccess(this.isCandidateTask(this.reviewTask) ? '候选审核结论已记录；未发布或授权' : '审核处理成功')
>>>>>>> private-github/featu
        this.reviewOpen = false
        this.reviewRetryPayload = null
        this.getList()
        if (this.detailOpen && this.detailTaskId === payload.taskId) this.loadDetail(payload.taskId)
      }).catch(error => {
        if (sequence !== this.reviewSequence) return
        const conflict = error && (error.code === 409 || ['AUDIT_STATE_CONFLICT', 'AUDIT_SNAPSHOT_STALE'].includes(error.errorCode))
        if (conflict) {
          this.reviewBlocked = true
          this.reviewRetryPayload = null
          this.reviewError = '审核状态或候选版本已变化。本次未自动重提，请关闭并核对最新证据后重新选择处理结果。'
        } else if (error && error.code === 400) {
          this.reviewRetryPayload = null
          this.reviewError = error.errorCode === 'REJECT_REASON_REQUIRED' ? '拒绝审核必须填写处理原因。' : '审核参数未被接受，请核对处理结果和原因。'
        } else if (error && (error.code === 401 || error.code === 403)) {
          this.reviewBlocked = true
          this.reviewError = '登录状态或审核权限已变化，请恢复授权后重新读取任务。'
        } else {
          this.reviewError = '未收到可确认的处理结果，请先核对最新状态。可重试原请求；不会自动重提或改写原版本。'
        }
        return this.refreshReviewState(payload.taskId, sequence)
      }).finally(() => { if (sequence === this.reviewSequence) this.reviewSubmitting = false })
    },
    openCommercialSubmit() {
      this.commercialSubmitForm = { topicId: null, phase: 'PROJECT', requestedTarget: 'RECRUITING' }
      this.commercialSubmitOpen = true
    },
    submitCommercialReview() {
      if (!this.auditViewActive) return
      const sequence = this.auditContextSequence
      const form = this.commercialSubmitForm
      if (!form.topicId) {
        this.$modal.msgError('请输入主题 ID')
        return
      }
      const request = form.phase === 'PROJECT'
        ? submitTopicProjectReview(form.topicId, { requestedTarget: form.requestedTarget }, this.captureAuditContext())
        : submitTopicSaleReview(form.topicId, this.captureAuditContext())
      request.then(response => {
        if (sequence !== this.auditContextSequence) return
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
.candidate-preview { margin-top: 20px; padding-top: 12px; border-top: 1px solid var(--cy-color-border-subtle); }
.candidate-preview > p { color: var(--cy-color-text-secondary); font-size: 13px; line-height: 1.6; }
.candidate-preview .el-alert { margin-top: 12px; }
.review-feedback { margin-top: 16px; }
.review-current { color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.6; }
.commercial-submit__hint { margin: 0 0 0 96px; color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.6; }
@media (max-width: 900px) { .audit-overview { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 600px) { .audit-overview { grid-template-columns: 1fr; } }
</style>
