<template>
  <div class="app-container">
    <el-form :inline="true" size="small" @submit.native.prevent>
      <el-form-item label="主题名">
        <el-input v-model="query.keyword" clearable style="width: 200px" @keyup.enter.native="search" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="search">查询</el-button>
      </el-form-item>
    </el-form>
    <p v-if="listError" class="qa-error">{{ listError }} <el-button type="text" @click="load">重试</el-button></p>
    <el-table v-else v-loading="loading" :data="rows" empty-text="没有已公开主题的落地信息">
      <el-table-column label="主题" width="110">
        <template slot-scope="s">#{{ s.row.topicId }}</template>
      </el-table-column>
      <el-table-column label="原文" prop="sourceLocale" width="80" />
      <el-table-column label="落地摘要" prop="landingSummary" min-width="240" show-overflow-tooltip />
      <el-table-column label="团体" width="80">
        <template slot-scope="s">{{ eligibilityLabel(s.row.groupEligible) }}</template>
      </el-table-column>
      <el-table-column label="学校" width="80">
        <template slot-scope="s">{{ eligibilityLabel(s.row.schoolEligible) }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="160">
        <template slot-scope="s">{{ timeText(s.row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template slot-scope="s">
          <el-button v-hasPermi="['business:contentlanding:review']" type="text" @click="openTopic(s.row)">译文</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="load" />

    <el-drawer :title="topicId ? `主题 #${topicId} 译文` : '译文'" :visible.sync="drawerOpen" size="560px" append-to-body>
      <div class="qa-drawer">
        <p v-if="detailError" class="qa-error">{{ detailError }}</p>
        <p v-else-if="detailLoading">读取中</p>
        <p v-else-if="!translations.length">没有译文</p>
        <section v-for="t in translations" :key="t.locale" class="qa-translation">
          <header>
            <strong>{{ t.locale }}</strong>
            <span :class="['qa-state', t.translationState === 'APPROVED' ? 'is-approved' : 'is-pending']">{{ translationStateLabel(t.translationState) }}</span>
          </header>
          <dl>
            <dt>标题</dt><dd>{{ t.title || '—' }}</dd>
            <dt>副标题</dt><dd>{{ t.subtitle || '—' }}</dd>
            <dt>摘要</dt><dd>{{ t.summary || '—' }}</dd>
          </dl>
          <el-button size="mini" @click="openReview(t)">审校</el-button>
        </section>
      </div>
    </el-drawer>

    <el-dialog :title="reviewing ? `审校 ${reviewing.locale}` : '审校'" :visible.sync="reviewOpen" width="520px" append-to-body>
      <el-form label-width="60px" size="small">
        <el-form-item label="结论">
          <el-radio-group v-model="reviewForm.approve">
            <el-radio :label="true">审校通过</el-radio>
            <el-radio :label="false">退回待审校</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="理由">
          <el-input v-model="reviewForm.reason" type="textarea" :rows="3" maxlength="200" show-word-limit />
          <p v-if="reviewErrors.reason" class="qa-error">{{ reviewErrors.reason }}</p>
        </el-form-item>
        <p v-if="reviewErrors.server" class="qa-error">{{ reviewErrors.server }}</p>
      </el-form>
      <div slot="footer">
        <el-button v-if="reviewStale" @click="refreshAfterConflict">刷新</el-button>
        <el-button type="primary" :loading="saving" :disabled="!digest || reviewStale" @click="submitReview">确认</el-button>
        <el-button @click="reviewOpen = false">取消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listLanding, getTranslationDetail, getTranslationDigest, reviewTranslation } from '@/api/business/contentLanding'
import { businessError, translationStateLabel, eligibilityLabel } from '@/utils/questo-admin'

const NOT_OPEN = '该功能尚未对当前角色开放'
const STALE = '内容已被作者修改，请刷新后重新审校'

function serverMessage(err) {
  const e = businessError(err)
  if (Number(e.code) === 403) return NOT_OPEN
  if (Number(e.code) === 409) return STALE
  return e.message
}

export default {
  name: 'BusinessContentLanding',
  data() {
    return {
      loading: false,
      rows: [],
      total: 0,
      listError: '',
      query: { keyword: '', pageNum: 1, pageSize: 10 },
      drawerOpen: false,
      topicId: null,
      detailLoading: false,
      detailError: '',
      translations: [],
      reviewOpen: false,
      reviewing: null,
      digest: null,
      reviewForm: { approve: true, reason: '' },
      reviewErrors: {},
      reviewStale: false,
      saving: false
    }
  },
  created() {
    this.load()
  },
  methods: {
    translationStateLabel, eligibilityLabel,
    timeText(value) { return value ? this.parseTime(value, '{y}-{m}-{d} {h}:{i}') : '—' },
    search() { this.query.pageNum = 1; this.load() },
    load() {
      this.loading = true
      this.listError = ''
      listLanding(this.query).then(res => {
        const page = res.data || {}
        this.rows = page.rows || []
        this.total = page.total || 0
      }).catch(err => {
        this.rows = []
        this.listError = serverMessage(err)
      }).finally(() => { this.loading = false })
    },
    openTopic(row) {
      this.topicId = row.topicId
      this.drawerOpen = true
      this.loadDetail()
    },
    loadDetail() {
      this.detailLoading = true
      this.detailError = ''
      this.translations = []
      return getTranslationDetail(this.topicId).then(res => {
        const vo = res.data || {}
        this.translations = (vo.translations || []).filter(t => t.locale !== vo.sourceLocale)
      }).catch(err => {
        this.detailError = serverMessage(err)
      }).finally(() => { this.detailLoading = false })
    },
    openReview(translation) {
      this.reviewing = translation
      this.digest = null
      this.reviewStale = false
      this.reviewErrors = {}
      this.reviewForm = { approve: translation.translationState !== 'APPROVED', reason: '' }
      this.reviewOpen = true
      // 先取摘要，提交时原样回传：防止看 A 审 B。
      getTranslationDigest(this.topicId, translation.locale).then(res => {
        this.digest = res.data || null
      }).catch(err => {
        this.reviewErrors = { server: serverMessage(err) }
      })
    },
    submitReview() {
      const reason = (this.reviewForm.reason || '').trim()
      if (!reason) {
        this.reviewErrors = { reason: '请填写理由' }
        return
      }
      this.saving = true
      this.reviewErrors = {}
      reviewTranslation({
        topicId: this.topicId,
        locale: this.reviewing.locale,
        expectedVersion: this.reviewing.rowVersion,
        observedContentHash: this.digest,
        approve: this.reviewForm.approve,
        reason
      }).then(res => {
        const vo = res.data || {}
        this.translations = (vo.translations || []).filter(t => t.locale !== vo.sourceLocale)
        this.reviewOpen = false
      }).catch(err => {
        const e = businessError(err)
        // 409 不自动重试。
        if (Number(e.code) === 409) this.reviewStale = true
        this.reviewErrors = { server: serverMessage(err) }
      }).finally(() => { this.saving = false })
    },
    refreshAfterConflict() {
      const locale = this.reviewing && this.reviewing.locale
      this.loadDetail().then(() => {
        const fresh = this.translations.find(t => t.locale === locale)
        if (fresh) this.openReview(fresh)
        else this.reviewOpen = false
      })
    }
  }
}
</script>

<style scoped>
.qa-error { color: #F56C6C; font-size: 12px; margin: 4px 0 8px; }
.qa-drawer { padding: 0 20px 20px; }
.qa-translation { border-bottom: 1px solid #EBEEF5; padding: 12px 0; }
.qa-translation header { display: flex; align-items: center; gap: 8px; }
.qa-translation dl { display: grid; grid-template-columns: 56px 1fr; gap: 6px 12px; margin: 10px 0; }
.qa-translation dt { color: #909399; }
.qa-translation dd { margin: 0; white-space: pre-wrap; word-break: break-word; }
.qa-state { font-size: 12px; padding: 0 6px; border-radius: 4px; }
.qa-state.is-pending { color: #E6A23C; background: #FDF6EC; }
.qa-state.is-approved { color: #67C23A; background: #F0F9EB; }
</style>
