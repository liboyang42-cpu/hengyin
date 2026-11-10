<template>
  <div class="app-container">
    <el-tabs v-model="tab" @tab-click="load">
      <el-tab-pane label="活动" name="campaigns">
        <el-form :inline="true" size="small">
          <el-form-item label="状态">
            <el-select v-model="campaignQuery.status" clearable placeholder="全部" style="width: 120px">
              <el-option v-for="(label, value) in campaignStatusOptions" :key="value" :label="label" :value="Number(value)" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="loadCampaigns">查询</el-button>
            <el-button v-hasPermi="['business:videobounty:config']" icon="el-icon-plus" @click="openCampaign(null)">新建活动</el-button>
          </el-form-item>
        </el-form>
        <p v-if="campaignError" class="qa-error">{{ campaignError }}</p>
        <el-table v-loading="loading" :data="campaigns" empty-text="暂无活动">
          <el-table-column label="活动" min-width="180">
            <template slot-scope="s">
              <div class="qa-stack"><span>{{ s.row.title }}</span><small>{{ s.row.campaignKey }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template slot-scope="s">{{ campaignStatusLabel(s.row.status) }}</template>
          </el-table-column>
          <el-table-column label="归属作者" width="110">
            <template slot-scope="s">{{ s.row.ownerMemberId ? '#' + s.row.ownerMemberId : '未设置' }}</template>
          </el-table-column>
          <el-table-column label="每千次（参考值）" width="140">
            <template slot-scope="s">{{ moneyText(s.row.ratePerThousand, s.row.currency) }}</template>
          </el-table-column>
          <el-table-column label="单条封顶（参考值）" width="150">
            <template slot-scope="s">{{ moneyText(s.row.perVideoCap, s.row.currency) }}</template>
          </el-table-column>
          <el-table-column label="窗口" width="80">
            <template slot-scope="s">{{ s.row.windowDays ? s.row.windowDays + ' 天' : '—' }}</template>
          </el-table-column>
          <el-table-column label="规则版本" prop="ruleVersion" width="120" />
          <el-table-column label="操作" width="200">
            <template slot-scope="s">
              <el-button v-hasPermi="['business:videobounty:config']" type="text" @click="openCampaign(s.row)">编辑</el-button>
              <el-dropdown v-hasPermi="['business:videobounty:config']" trigger="click" @command="status => changeStatus(s.row, status)">
                <el-button type="text">状态<i class="el-icon-arrow-down el-icon--right" /></el-button>
                <el-dropdown-menu slot="dropdown">
                  <el-dropdown-item v-for="(label, value) in campaignStatusOptions" :key="value" :command="Number(value)" :disabled="Number(value) === s.row.status">{{ label }}</el-dropdown-item>
                </el-dropdown-menu>
              </el-dropdown>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="投稿审核" name="submissions">
        <el-form :inline="true" size="small">
          <el-form-item label="活动">
            <el-select v-model="submissionQuery.campaignId" clearable placeholder="全部" style="width: 180px">
              <el-option v-for="c in campaigns" :key="c.id" :label="c.title || c.campaignKey" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="submissionQuery.status" clearable placeholder="全部" style="width: 120px">
              <el-option v-for="(label, value) in submissionStatusOptions" :key="value" :label="label" :value="Number(value)" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="searchSubmissions">查询</el-button>
          </el-form-item>
        </el-form>
        <p v-if="submissionError" class="qa-error">{{ submissionError }}</p>
        <el-table v-loading="loading" :data="submissions" empty-text="暂无投稿" @expand-change="loadAttribution">
          <el-table-column type="expand">
            <template slot-scope="s">
              <div class="qa-expand">
                <h4>原视频组</h4>
                <dl class="qa-facts">
                  <div><dt>作者自报（建议值，不参与封顶）</dt><dd>{{ s.row.originGroupKey || '—' }}</dd></div>
                  <div><dt>核验组键</dt><dd>{{ s.row.verifiedGroupKey || '未核验' }}</dd></div>
                  <div><dt>核验人</dt><dd>{{ s.row.verifiedBy ? '#' + s.row.verifiedBy : '—' }}</dd></div>
                  <div><dt>核验时间</dt><dd>{{ timeText(s.row.verifiedAt) }}</dd></div>
                  <div><dt>证据引用</dt><dd>{{ s.row.verifiedSourceRef || '—' }}</dd></div>
                  <div><dt>核验版本</dt><dd>{{ countText(s.row.verifiedGroupVersion) }}</dd></div>
                </dl>
                <h4>成交归因</h4>
                <p v-if="attribution[s.row.id] && attribution[s.row.id].error" class="qa-error">{{ attribution[s.row.id].error }}</p>
                <p v-else-if="!attribution[s.row.id]">读取中</p>
                <dl v-else-if="attribution[s.row.id].view.confirmed" class="qa-facts">
                  <div><dt>归因订单</dt><dd>{{ attribution[s.row.id].view.attributedOrders }}</dd></div>
                  <div><dt>已付订单</dt><dd>{{ attribution[s.row.id].view.paidOrders }}</dd></div>
                  <div><dt>已付金额</dt><dd>{{ attribution[s.row.id].view.paidAmount }}</dd></div>
                  <div><dt>退款订单</dt><dd>{{ attribution[s.row.id].view.refundedOrders }}</dd></div>
                  <div><dt>退款金额</dt><dd>{{ attribution[s.row.id].view.refundedAmount }}</dd></div>
                </dl>
                <p v-else>{{ attribution[s.row.id].view.text }}</p>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="投稿" width="70">
            <template slot-scope="s">#{{ s.row.id }}</template>
          </el-table-column>
          <el-table-column label="平台 / 链接" min-width="170">
            <template slot-scope="s">
              <div class="qa-stack"><span>{{ platformLabel(s.row.platform) }}</span><small class="qa-ellipsis">{{ s.row.sourceUrl || s.row.canonicalVideoKey }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="主题 / 作者" width="120">
            <template slot-scope="s">
              <div class="qa-stack"><span>主题 #{{ s.row.topicId }}</span><small>作者 #{{ s.row.memberId }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template slot-scope="s">{{ submissionStatusLabel(s.row.status) }}</template>
          </el-table-column>
          <el-table-column label="原视频组" min-width="140">
            <template slot-scope="s">
              <div class="qa-stack">
                <span>{{ groupVerifyLabel(s.row) }}</span>
                <small v-if="accrualBlockedByGroup(s.row)">未核验，暂无应计</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="提交时间" width="140">
            <template slot-scope="s">{{ timeText(s.row.submittedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" min-width="230">
            <template slot-scope="s">
              <el-button v-if="canReviewSubmission(s.row.status)" v-hasPermi="['business:videobounty:review']" type="text" @click="openReview(s.row)">审核</el-button>
              <template v-if="s.row.status === 2">
                <el-button v-hasPermi="['business:videobounty:review']" type="text" @click="openGroup(s.row)">核验组</el-button>
                <el-button v-hasPermi="['business:videobounty:review']" type="text" @click="openEvidence(s.row)">录证据</el-button>
                <el-button v-hasPermi="['business:videobounty:review']" type="text" @click="recompute(s.row)">重算应计</el-button>
              </template>
              <el-button v-if="canRevokeSubmission(s.row.status)" v-hasPermi="['business:videobounty:review']" type="text" class="qa-danger" @click="openRevoke(s.row)">撤销</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p v-if="rowNotice.text" :class="rowNotice.error ? 'qa-error' : 'qa-ok'">{{ rowNotice.text }}</p>
        <div class="qa-pager">
          <el-button size="mini" :disabled="submissionQuery.offset === 0" @click="pageSubmissions(-1)">上一页</el-button>
          <el-button size="mini" :disabled="submissions.length < submissionQuery.limit" @click="pageSubmissions(1)">下一页</el-button>
        </div>
      </el-tab-pane>

      <el-tab-pane label="应计" name="accruals">
        <el-form :inline="true" size="small">
          <el-form-item label="活动">
            <el-select v-model="accrualQuery.campaignId" clearable placeholder="全部" style="width: 180px">
              <el-option v-for="c in campaigns" :key="c.id" :label="c.title || c.campaignKey" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="searchAccruals">查询</el-button>
          </el-form-item>
        </el-form>
        <p v-if="accrualError" class="qa-error">{{ accrualError }} <el-button type="text" @click="loadAccruals">重试</el-button></p>
        <el-table v-else v-loading="loading" :data="accruals" empty-text="还没有应计记录">
          <el-table-column label="投稿" width="90">
            <template slot-scope="s">#{{ s.row.submissionId }}</template>
          </el-table-column>
          <el-table-column label="原视频组" prop="groupKey" min-width="140" />
          <el-table-column label="规则版本" prop="ruleVersion" width="110" />
          <el-table-column label="有效自然播放" width="120">
            <template slot-scope="s">{{ countText(s.row.basisViews) }}</template>
          </el-table-column>
          <el-table-column label="同组合计" width="110">
            <template slot-scope="s">{{ countText(s.row.groupBasisViews) }}</template>
          </el-table-column>
          <el-table-column label="应计" width="120">
            <template slot-scope="s">{{ moneyText(s.row.payableAmount, s.row.currency) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="160">
            <template slot-scope="s">{{ accrualPayoutLabel(s.row.payoutState) }}</template>
          </el-table-column>
          <el-table-column label="窗口" min-width="190">
            <template slot-scope="s">{{ dateText(s.row.windowStart) }} – {{ dateText(s.row.windowEnd) }}</template>
          </el-table-column>
        </el-table>
        <div class="qa-pager">
          <el-button size="mini" :disabled="accrualQuery.offset === 0" @click="pageAccruals(-1)">上一页</el-button>
          <el-button size="mini" :disabled="accruals.length < accrualQuery.limit" @click="pageAccruals(1)">下一页</el-button>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog :title="campaignForm.id ? '编辑活动' : '新建活动'" :visible.sync="campaignOpen" width="560px" append-to-body>
      <el-form :model="campaignForm" label-width="150px" size="small">
        <el-form-item label="活动键"><el-input v-model="campaignForm.campaignKey" :disabled="!!campaignForm.id" maxlength="64" /></el-form-item>
        <el-form-item label="标题"><el-input v-model="campaignForm.title" maxlength="100" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="campaignForm.description" type="textarea" :rows="2" maxlength="500" /></el-form-item>
        <el-form-item label="归属作者会员 ID"><el-input v-model="campaignForm.ownerMemberId" :disabled="locks.owner" /></el-form-item>
        <el-form-item label="币种"><el-input v-model="campaignForm.currency" :disabled="!!campaignForm.id" maxlength="3" /></el-form-item>
        <el-form-item label="每千次（参考值，非已批准费率）"><el-input v-model="campaignForm.ratePerThousand" :disabled="locks.rule" /></el-form-item>
        <el-form-item label="单条封顶（参考值）"><el-input v-model="campaignForm.perVideoCap" :disabled="locks.rule" /></el-form-item>
        <el-form-item label="窗口天数"><el-input v-model="campaignForm.windowDays" :disabled="locks.rule" /></el-form-item>
        <el-form-item label="总预算封顶（参考值）"><el-input v-model="campaignForm.totalBudgetCap" :disabled="locks.rule" /></el-form-item>
        <el-form-item label="规则版本"><el-input v-model="campaignForm.ruleVersion" :disabled="locks.rule" maxlength="32" /></el-form-item>
        <p v-if="campaignFormError" class="qa-error">{{ campaignFormError }}</p>
      </el-form>
      <div slot="footer">
        <el-button type="primary" :loading="saving" @click="saveCampaign">保存</el-button>
        <el-button @click="campaignOpen = false">取消</el-button>
      </div>
    </el-dialog>

    <el-dialog title="审核投稿" :visible.sync="reviewOpen" width="520px" append-to-body>
      <el-form :model="reviewForm" label-width="90px" size="small">
        <el-form-item label="结论">
          <el-radio-group v-model="reviewForm.decision">
            <el-radio label="APPROVE">通过</el-radio>
            <el-radio label="REJECT">驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="reviewForm.decision === 'APPROVE'" label="发布时间">
          <el-date-picker v-model="reviewForm.publishedAt" type="datetime" value-format="timestamp" />
          <p v-if="reviewErrors.publishedAt" class="qa-error">{{ reviewErrors.publishedAt }}</p>
        </el-form-item>
        <el-form-item label="审核说明">
          <el-input v-model="reviewForm.reviewNote" type="textarea" :rows="3" maxlength="500" />
          <p v-if="reviewErrors.reviewNote" class="qa-error">{{ reviewErrors.reviewNote }}</p>
        </el-form-item>
        <p v-if="reviewErrors.server" class="qa-error">{{ reviewErrors.server }}</p>
      </el-form>
      <div slot="footer">
        <el-button type="primary" :loading="saving" @click="submitReview">确认</el-button>
        <el-button @click="reviewOpen = false">取消</el-button>
      </div>
    </el-dialog>

    <el-dialog title="核验原视频组" :visible.sync="groupOpen" width="560px" append-to-body>
      <dl v-if="groupRow" class="qa-facts">
        <div><dt>作者自报（建议值，不参与封顶）</dt><dd>{{ groupRow.originGroupKey || '—' }}</dd></div>
        <div><dt>当前核验组键</dt><dd>{{ groupRow.verifiedGroupKey || '未核验' }}</dd></div>
        <div><dt>核验版本</dt><dd>{{ countText(groupRow.verifiedGroupVersion) }}</dd></div>
      </dl>
      <el-form :model="groupForm" label-width="90px" size="small">
        <el-form-item label="核验组键">
          <el-input v-model="groupForm.verifiedGroupKey" :disabled="groupLocked" maxlength="128" />
          <p v-if="groupLocked" class="qa-error">该投稿已有应计，原视频组不可再变更</p>
        </el-form-item>
        <el-form-item label="证据引用">
          <el-input v-model="groupForm.sourceRef" maxlength="256" />
          <p v-if="groupErrors.sourceRef" class="qa-error">{{ groupErrors.sourceRef }}</p>
        </el-form-item>
        <el-form-item label="理由">
          <el-input v-model="groupForm.reason" type="textarea" :rows="2" maxlength="200" />
          <p v-if="groupErrors.reason" class="qa-error">{{ groupErrors.reason }}</p>
        </el-form-item>
        <p v-if="groupErrors.server" class="qa-error">{{ groupErrors.server }}</p>
      </el-form>
      <div slot="footer">
        <el-button v-if="groupConflict" @click="refreshGroupRow">刷新</el-button>
        <el-button type="primary" :loading="saving" :disabled="groupLocked || groupConflict" @click="submitGroup">提交核验</el-button>
        <el-button @click="groupOpen = false">取消</el-button>
      </div>
    </el-dialog>

    <el-dialog title="录入外部播放证据" :visible.sync="evidenceOpen" width="560px" append-to-body>
      <el-form :model="evidenceForm" label-width="90px" size="small">
        <el-form-item label="来源">
          <el-radio-group v-model="evidenceForm.evidenceType">
            <el-radio :label="1">人工核验录入</el-radio>
            <el-radio :label="2">平台导出报表</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="来源引用">
          <el-input v-model="evidenceForm.sourceRef" maxlength="256" />
          <p v-if="evidenceErrors.sourceRef" class="qa-error">{{ evidenceErrors.sourceRef }}</p>
        </el-form-item>
        <el-form-item label="统计期间">
          <el-date-picker v-model="evidenceForm.period" type="datetimerange" value-format="timestamp" range-separator="至" />
        </el-form-item>
        <el-form-item label="播放数"><el-input v-model="evidenceForm.views" /></el-form-item>
        <el-form-item label="自然流量"><el-switch v-model="evidenceForm.organic" /></el-form-item>
        <el-form-item label="可核验"><el-switch v-model="evidenceForm.verifiable" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="evidenceForm.note" maxlength="200" /></el-form-item>
        <p v-if="evidenceErrors.server" class="qa-error">{{ evidenceErrors.server }}</p>
      </el-form>
      <div slot="footer">
        <el-button type="primary" :loading="saving" @click="submitEvidence">保存</el-button>
        <el-button @click="evidenceOpen = false">取消</el-button>
      </div>
    </el-dialog>

    <el-dialog title="撤销投稿" :visible.sync="revokeOpen" width="480px" append-to-body>
      <el-form label-width="60px" size="small">
        <el-form-item label="理由">
          <el-input v-model="revokeForm.note" type="textarea" :rows="3" maxlength="200" />
          <p v-if="revokeForm.error" class="qa-error">{{ revokeForm.error }}</p>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button type="danger" :loading="saving" @click="submitRevoke">撤销</el-button>
        <el-button @click="revokeOpen = false">取消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  listCampaigns, createCampaign, updateCampaign, changeCampaignStatus, listSubmissions, reviewSubmission,
  revokeSubmission, verifyGroup, recordEvidence, recomputeAccrual, listAccruals, submissionAttribution
} from '@/api/business/videobounty'
import {
  businessError, countText, moneyText, campaignStatusLabel, submissionStatusLabel, canReviewSubmission,
  canRevokeSubmission, groupVerifyLabel, accrualBlockedByGroup, accrualPayoutLabel, attributionView, platformLabel
} from '@/utils/questo-admin'

const PAGE = 20
const CAMPAIGN_STATUS = { 0: '草稿', 1: '进行中', 2: '已暂停', 3: '已关闭' }
const SUBMISSION_STATUS = { 0: '已提交', 1: '审核中', 2: '已通过', 3: '已驳回', 4: '已撤销', 5: '申诉中', 6: '已取消' }

function blank(value) { return value === null || value === undefined || String(value).trim() === '' }
function numberOrNull(value) { return blank(value) ? null : Number(value) }

export default {
  name: 'BusinessVideoBounty',
  data() {
    return {
      tab: 'campaigns',
      loading: false,
      saving: false,
      campaignStatusOptions: CAMPAIGN_STATUS,
      submissionStatusOptions: SUBMISSION_STATUS,
      campaigns: [],
      campaignError: '',
      campaignQuery: { status: null },
      submissions: [],
      submissionError: '',
      submissionQuery: { campaignId: null, status: null, offset: 0, limit: PAGE },
      accruals: [],
      accrualError: '',
      accrualQuery: { campaignId: null, offset: 0, limit: PAGE },
      attribution: {},
      rowNotice: { text: '', error: false },
      campaignOpen: false,
      campaignForm: {},
      campaignFormError: '',
      locks: { rule: false, owner: false },
      reviewOpen: false,
      reviewRow: null,
      reviewForm: {},
      reviewErrors: {},
      groupOpen: false,
      groupRow: null,
      groupForm: {},
      groupErrors: {},
      groupLocked: false,
      groupConflict: false,
      evidenceOpen: false,
      evidenceRow: null,
      evidenceForm: {},
      evidenceErrors: {},
      revokeOpen: false,
      revokeRow: null,
      revokeForm: { note: '', error: '' }
    }
  },
  created() {
    this.loadCampaigns()
  },
  methods: {
    countText, moneyText, campaignStatusLabel, submissionStatusLabel, canReviewSubmission, canRevokeSubmission,
    groupVerifyLabel, accrualBlockedByGroup, accrualPayoutLabel, platformLabel,
    timeText(value) { return value ? this.parseTime(value, '{y}-{m}-{d} {h}:{i}') : '—' },
    dateText(value) { return value ? this.parseTime(value, '{y}-{m}-{d}') : '—' },
    load() {
      if (this.tab === 'campaigns') this.loadCampaigns()
      if (this.tab === 'submissions') this.loadSubmissions()
      if (this.tab === 'accruals') this.loadAccruals()
    },
    loadCampaigns() {
      this.loading = true
      this.campaignError = ''
      return listCampaigns(this.campaignQuery.status).then(res => {
        this.campaigns = res.data || []
      }).catch(err => {
        this.campaigns = []
        this.campaignError = businessError(err).message
      }).finally(() => { this.loading = false })
    },
    searchSubmissions() { this.submissionQuery.offset = 0; this.loadSubmissions() },
    pageSubmissions(step) {
      this.submissionQuery.offset = Math.max(0, this.submissionQuery.offset + step * PAGE)
      this.loadSubmissions()
    },
    loadSubmissions() {
      this.loading = true
      this.submissionError = ''
      this.attribution = {}
      return listSubmissions(this.submissionQuery).then(res => {
        this.submissions = res.data || []
      }).catch(err => {
        this.submissions = []
        this.submissionError = businessError(err).message
      }).finally(() => { this.loading = false })
    },
    searchAccruals() { this.accrualQuery.offset = 0; this.loadAccruals() },
    pageAccruals(step) {
      this.accrualQuery.offset = Math.max(0, this.accrualQuery.offset + step * PAGE)
      this.loadAccruals()
    },
    loadAccruals() {
      this.loading = true
      this.accrualError = ''
      return listAccruals(this.accrualQuery).then(res => {
        this.accruals = res.data || []
      }).catch(() => {
        // 读取失败不显示 0 元，也不显示“还没有应计记录”。
        this.accruals = []
        this.accrualError = '读取失败'
      }).finally(() => { this.loading = false })
    },
    loadAttribution(row) {
      if (this.attribution[row.id]) return
      submissionAttribution(row.id).then(res => {
        this.$set(this.attribution, row.id, { view: attributionView(res.data) })
      }).catch(err => {
        this.$set(this.attribution, row.id, { error: businessError(err).message })
      })
    },
    openCampaign(row) {
      this.campaignFormError = ''
      this.locks = { rule: false, owner: false }
      this.campaignForm = row ? {
        id: row.id,
        campaignKey: row.campaignKey,
        title: row.title,
        description: row.description,
        ownerMemberId: row.ownerMemberId,
        currency: row.currency,
        ratePerThousand: row.ratePerThousand,
        perVideoCap: row.perVideoCap,
        windowDays: row.windowDays,
        totalBudgetCap: row.totalBudgetCap,
        ruleVersion: row.ruleVersion
      } : { currency: 'CNY' }
      this.campaignOpen = true
    },
    saveCampaign() {
      const f = this.campaignForm
      // 现金兑付不提供开关：恒 0。
      const payload = {
        campaignKey: f.campaignKey,
        title: f.title,
        description: f.description,
        ownerMemberId: numberOrNull(f.ownerMemberId),
        currency: f.currency,
        ratePerThousand: numberOrNull(f.ratePerThousand),
        perVideoCap: numberOrNull(f.perVideoCap),
        windowDays: numberOrNull(f.windowDays),
        totalBudgetCap: numberOrNull(f.totalBudgetCap),
        ruleVersion: f.ruleVersion,
        cashPayoutEnabled: 0
      }
      this.saving = true
      this.campaignFormError = ''
      const call = f.id ? updateCampaign(f.id, payload) : createCampaign(payload)
      call.then(() => {
        this.campaignOpen = false
        this.loadCampaigns()
      }).catch(err => {
        const e = businessError(err)
        if (e.reasonCode === 'RULE_LOCKED_BY_ACCRUAL' || e.reasonCode === 'CURRENCY_LOCKED') {
          this.locks.rule = true
          this.campaignFormError = '已有应计，规则不可修改，请新建活动'
        } else if (e.reasonCode === 'OWNER_LOCKED_BY_ACCRUAL') {
          this.locks.owner = true
          this.campaignFormError = '已有应计，归属不可修改，请新建活动'
        } else {
          this.campaignFormError = e.message
        }
      }).finally(() => { this.saving = false })
    },
    changeStatus(row, status) {
      changeCampaignStatus(row.id, status).then(() => this.loadCampaigns()).catch(err => {
        this.campaignError = businessError(err).message
      })
    },
    openReview(row) {
      this.reviewRow = row
      this.reviewForm = { decision: 'APPROVE', publishedAt: null, reviewNote: '' }
      this.reviewErrors = {}
      this.reviewOpen = true
    },
    submitReview() {
      const f = this.reviewForm
      const errors = {}
      if (f.decision === 'APPROVE' && !f.publishedAt) errors.publishedAt = '通过需要填写发布时间'
      if (f.decision === 'REJECT' && blank(f.reviewNote)) errors.reviewNote = '驳回需要填写理由'
      this.reviewErrors = errors
      if (Object.keys(errors).length) return
      this.saving = true
      reviewSubmission(this.reviewRow.id, {
        decision: f.decision,
        publishedAt: f.decision === 'APPROVE' ? f.publishedAt : null,
        reviewNote: f.reviewNote
      }).then(() => {
        this.reviewOpen = false
        this.loadSubmissions()
      }).catch(err => {
        this.reviewErrors = { server: businessError(err).message }
      }).finally(() => { this.saving = false })
    },
    openGroup(row) {
      this.groupRow = row
      this.groupForm = { verifiedGroupKey: row.verifiedGroupKey || '', sourceRef: '', reason: '' }
      this.groupErrors = {}
      this.groupLocked = false
      this.groupConflict = false
      this.groupOpen = true
    },
    refreshGroupRow() {
      const id = this.groupRow && this.groupRow.id
      this.loadSubmissions().then(() => {
        const fresh = this.submissions.find(item => item.id === id)
        if (fresh) this.openGroup(fresh)
      })
    },
    submitGroup() {
      const f = this.groupForm
      const errors = {}
      if (blank(f.sourceRef)) errors.sourceRef = '请填写证据引用'
      if (blank(f.reason)) errors.reason = '请填写理由'
      this.groupErrors = errors
      if (Object.keys(errors).length) return
      this.saving = true
      verifyGroup(this.groupRow.id, {
        verifiedGroupKey: f.verifiedGroupKey,
        sourceRef: f.sourceRef,
        reason: f.reason,
        expectedVersion: this.groupRow.verifiedGroupVersion === null || this.groupRow.verifiedGroupVersion === undefined
          ? 0 : this.groupRow.verifiedGroupVersion
      }).then(() => {
        this.groupOpen = false
        this.loadSubmissions()
      }).catch(err => {
        const e = businessError(err)
        if (e.reasonCode === 'GROUP_LOCKED_BY_ACCRUAL') {
          this.groupLocked = true
          this.groupErrors = {}
        } else if (e.reasonCode === 'GROUP_VERIFY_VERSION_CONFLICT') {
          // 不自动重试：刷新后由运营重新确认。
          this.groupConflict = true
          this.groupErrors = { server: '核验版本冲突，请刷新后重试' }
        } else {
          this.groupErrors = { server: e.message }
        }
      }).finally(() => { this.saving = false })
    },
    openEvidence(row) {
      this.evidenceRow = row
      this.evidenceForm = { evidenceType: 1, sourceRef: '', period: null, views: '', organic: true, verifiable: true, note: '' }
      this.evidenceErrors = {}
      this.evidenceOpen = true
    },
    submitEvidence() {
      const f = this.evidenceForm
      if (blank(f.sourceRef)) {
        this.evidenceErrors = { sourceRef: '请填写外部来源引用' }
        return
      }
      this.saving = true
      this.evidenceErrors = {}
      recordEvidence(this.evidenceRow.id, {
        evidenceType: f.evidenceType,
        sourceRef: f.sourceRef,
        periodStart: f.period ? f.period[0] : null,
        periodEnd: f.period ? f.period[1] : null,
        views: numberOrNull(f.views),
        organic: f.organic,
        verifiable: f.verifiable,
        note: f.note
      }).then(() => {
        this.evidenceOpen = false
        this.rowNotice = { text: `投稿 #${this.evidenceRow.id} 证据已录入`, error: false }
      }).catch(err => {
        this.evidenceErrors = { server: businessError(err).message }
      }).finally(() => { this.saving = false })
    },
    recompute(row) {
      this.rowNotice = { text: '', error: false }
      recomputeAccrual(row.id).then(() => {
        this.rowNotice = { text: `投稿 #${row.id} 应计已重算（只记录，未开放兑付）`, error: false }
      }).catch(err => {
        const e = businessError(err)
        const text = e.reasonCode === 'ACCRUAL_GROUP_UNVERIFIED' ? '请先核验原视频组' : e.message
        this.rowNotice = { text: `投稿 #${row.id}：${text}`, error: true }
      })
    },
    openRevoke(row) {
      this.revokeRow = row
      this.revokeForm = { note: '', error: '' }
      this.revokeOpen = true
    },
    submitRevoke() {
      if (blank(this.revokeForm.note)) {
        this.revokeForm.error = '请填写撤销理由'
        return
      }
      this.saving = true
      revokeSubmission(this.revokeRow.id, this.revokeForm.note).then(() => {
        this.revokeOpen = false
        this.loadSubmissions()
      }).catch(err => {
        this.revokeForm.error = businessError(err).message
      }).finally(() => { this.saving = false })
    }
  }
}
</script>

<style scoped>
.qa-stack { display: flex; flex-direction: column; line-height: 1.4; }
.qa-stack small { color: var(--cy-color-text-secondary, #909399); }
.qa-ellipsis { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 260px; }
.qa-error { color: #F56C6C; font-size: 12px; margin: 4px 0 0; line-height: 1.4; }
.qa-ok { color: #67C23A; font-size: 12px; margin: 8px 0 0; }
.qa-danger { color: #F56C6C; }
.qa-pager { margin-top: 12px; text-align: right; }
.qa-expand { padding: 0 24px; }
.qa-expand h4 { margin: 8px 0; font-size: 13px; }
.qa-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px 24px; margin: 0 0 12px; }
.qa-facts dt { color: var(--cy-color-text-secondary, #909399); font-size: 12px; }
.qa-facts dd { margin: 2px 0 0; word-break: break-all; }
</style>
