<template>
  <div class="app-container cy-domain-page verification-evidence">
    <cy-page-header
      eyebrow="风险与调证"
      title="核销证据"
      description="查询扫码与带队场次的核销事实，用于调证与范围核对；本页不改变报名、核销或结算结果。"
    />

    <section class="cy-panel verification-evidence__intro" aria-label="核销证据范围">
      <div class="cy-panel__heading">
        <div>
          <h2>两条核销证据</h2>
          <p>扫码流水回答“谁扫过哪张报名单”；团核销记录回答“哪个商家接待了哪个领队的团”。两者都是只读事实，不以历史状态码推断核销结果。</p>
        </div>
        <span class="verification-evidence__read-only">只读调证</span>
      </div>
    </section>

    <el-tabs v-model="activeTab" class="verification-evidence__tabs">
      <el-tab-pane label="扫码核销流水" name="scans">
        <section class="cy-panel" aria-label="扫码核销流水筛选和结果">
          <div class="cy-panel__heading">
            <div>
              <h2>扫码核销流水</h2>
              <p>选择核销台账行时，编号指核销商家；选择报名主行时，编号指报名玩家。空列表可能是正常业务事实。</p>
            </div>
          </div>
          <el-form :model="scanQuery" size="small" :inline="true" label-width="80px">
            <cy-filter-bar :active-filters="scanFilters" @submit="loadScans" @reset="resetScans" @remove-filter="clearScanFilter">
              <el-form-item label="记录口径">
                <el-select v-model="scanQuery.registrationType" style="width: 196px">
                  <el-option label="核销台账行（商家）" :value="1" />
                  <el-option label="报名主行（玩家）" :value="0" />
                </el-select>
              </el-form-item>
              <el-form-item :label="scanQuery.registrationType === 1 ? '核销商家' : '报名玩家'">
                <el-input v-model="scanQuery.memberId" :placeholder="scanQuery.registrationType === 1 ? '商家编号' : '玩家编号'" clearable />
              </el-form-item>
              <el-form-item label="报名 / 核销单">
                <el-input v-model="scanQuery.registrationId" placeholder="输入报名 / 核销单编号" clearable />
              </el-form-item>
            </cy-filter-bar>
          </el-form>

          <cy-resource-state
            v-if="scanError"
            kind="error"
            title="扫码核销证据暂时无法读取"
            :description="scanError"
            retry-text="重新读取"
            :retrying="scanLoading"
            @retry="loadScans"
          />

          <div v-else v-loading="scanLoading" class="verification-evidence__table-wrap">
            <el-table v-if="scans.length" :data="scans" class="verification-evidence__table">
              <el-table-column label="流水" prop="id" width="150" show-overflow-tooltip />
              <el-table-column :label="scanQuery.registrationType === 1 ? '核销商家' : '报名玩家'" prop="memberId" width="140" align="center" />
              <el-table-column label="报名 / 核销单" width="128" align="center">
                <template slot-scope="scope">#{{ scope.row.registrationId || '未记录' }}</template>
              </el-table-column>
              <el-table-column label="核销方式" prop="validationMethod" width="112" align="center" />
              <el-table-column label="地点事实" min-width="190" show-overflow-tooltip>
                <template slot-scope="scope">{{ scope.row.name || scope.row.address || '未记录' }}</template>
              </el-table-column>
              <el-table-column label="发生时间" prop="createTime" width="168" align="center" />
            </el-table>
            <cy-empty-state
              v-else-if="!scanLoading"
              title="没有匹配的扫码核销流水"
              description="当前筛选下没有记录不代表核销失败；可调整口径、成员或报名单后重新读取。"
              icon="el-icon-document-remove"
            />
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="团核销记录" name="groups">
        <section class="cy-panel" aria-label="团核销记录筛选和结果">
          <div class="cy-panel__heading">
            <div>
              <h2>团核销记录</h2>
              <p>用于核对商家、领队与主题之间已经发生的接待事实；它不替代报名明细或结算批次。</p>
            </div>
          </div>
          <el-form :model="groupQuery" size="small" :inline="true" label-width="68px">
            <cy-filter-bar :active-filters="groupFilters" @submit="loadGroups" @reset="resetGroups" @remove-filter="clearGroupFilter">
              <el-form-item label="主题">
                <el-input v-model="groupQuery.topicId" placeholder="输入主题编号" clearable />
              </el-form-item>
              <el-form-item label="商家">
                <el-input v-model="groupQuery.merchantMemberId" placeholder="输入商家编号" clearable />
              </el-form-item>
              <el-form-item label="领队">
                <el-input v-model="groupQuery.leaderMemberId" placeholder="输入领队编号" clearable />
              </el-form-item>
            </cy-filter-bar>
          </el-form>

          <cy-resource-state
            v-if="groupError"
            kind="error"
            title="团核销证据暂时无法读取"
            :description="groupError"
            retry-text="重新读取"
            :retrying="groupLoading"
            @retry="loadGroups"
          />

          <div v-else v-loading="groupLoading" class="verification-evidence__table-wrap">
            <el-table v-if="groups.length" :data="groups" class="verification-evidence__table">
              <el-table-column label="主题" min-width="180" show-overflow-tooltip>
                <template slot-scope="scope">{{ scope.row.topicName || ('主题 #' + scope.row.topicId) }}</template>
              </el-table-column>
              <el-table-column label="商家" min-width="170">
                <template slot-scope="scope">
                  <div class="verification-evidence__party">
                    <strong>{{ scope.row.merchantName || '名称未记录' }}</strong>
                    <span>编号 #{{ scope.row.merchantMemberId || '未记录' }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="领队" min-width="170">
                <template slot-scope="scope">
                  <div class="verification-evidence__party">
                    <strong>{{ scope.row.leaderName || '名称未记录' }}</strong>
                    <span>编号 #{{ scope.row.leaderMemberId || '未记录' }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="合作单" width="120" align="center">
                <template slot-scope="scope">#{{ scope.row.coopOrderId || '未记录' }}</template>
              </el-table-column>
              <el-table-column label="核销时间" prop="verifyTime" width="168" align="center" />
            </el-table>
            <cy-empty-state
              v-else-if="!groupLoading"
              title="没有匹配的团核销记录"
              description="当前主题、商家和领队条件下没有接待事实时，空列表是正常结果。"
              icon="el-icon-document-remove"
            />
          </div>
        </section>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import { scanList, groupVerifyList } from '@/api/business/verifyEvidence'
import CyEmptyState from '@/components/CyEmptyState'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

export default {
  name: 'RiskVerificationEvidence',
  components: { CyEmptyState, CyFilterBar, CyPageHeader, CyResourceState },
  data() {
    return {
      activeTab: 'scans',
      scanLoading: false,
      groupLoading: false,
      scanError: '',
      groupError: '',
      scanRequestId: 0,
      groupRequestId: 0,
      scans: [],
      groups: [],
      scanQuery: { memberId: '', registrationId: '', registrationType: 1 },
      groupQuery: { topicId: '', merchantMemberId: '', leaderMemberId: '' }
    }
  },
  computed: {
    scanFilters() {
      const filters = [{ key: 'registrationType', label: '记录口径', value: this.scanQuery.registrationType === 1 ? '核销台账行（商家）' : '报名主行（玩家）' }]
      if (this.scanQuery.memberId) filters.push({ key: 'memberId', label: this.scanQuery.registrationType === 1 ? '核销商家' : '报名玩家', value: this.scanQuery.memberId })
      if (this.scanQuery.registrationId) filters.push({ key: 'registrationId', label: '报名 / 核销单', value: this.scanQuery.registrationId })
      return filters
    },
    groupFilters() {
      return [
        ['topicId', '主题'],
        ['merchantMemberId', '商家'],
        ['leaderMemberId', '领队']
      ].filter(([key]) => this.groupQuery[key]).map(([key, label]) => ({ key, label, value: this.groupQuery[key] }))
    }
  },
  created() {
    if (this.ensureCanonicalRoute()) return
    this.applyRouteQuery(this.$route.query)
    this.reloadEvidence()
  },
  watch: {
    '$route'(to, from) {
      if (this.ensureCanonicalRoute()) return
      if (to.fullPath === from.fullPath) return
      this.applyRouteQuery(to.query)
      this.reloadEvidence()
    }
  },
  methods: {
    defaultScanQuery() {
      return { memberId: '', registrationId: '', registrationType: 1 }
    },
    defaultGroupQuery() {
      return { topicId: '', merchantMemberId: '', leaderMemberId: '' }
    },
    ensureCanonicalRoute() {
      if (!this.$route || this.$route.path !== '/business/verifyevidence') return false
      this.$router.replace({ name: 'riskVerificationEvidence', query: Object.assign({}, this.$route.query || {}) })
      return true
    },
    applyRouteQuery(query) {
      const routeQuery = query || {}
      this.activeTab = routeQuery.tab === 'groups' ? 'groups' : 'scans'
      this.scanQuery = {
        memberId: this.queryValue(routeQuery.memberId),
        registrationId: this.queryValue(routeQuery.registrationId),
        registrationType: String(routeQuery.registrationType) === '0' ? 0 : 1
      }
      this.groupQuery = {
        topicId: this.queryValue(routeQuery.topicId),
        merchantMemberId: this.queryValue(routeQuery.merchantMemberId),
        leaderMemberId: this.queryValue(routeQuery.leaderMemberId)
      }
    },
    queryValue(value) {
      return value == null ? '' : String(value)
    },
    numOrOmit(value) {
      const text = this.queryValue(value).trim()
      return /^\d+$/.test(text) ? Number(text) : undefined
    },
    reloadEvidence() {
      return Promise.all([this.loadScans(), this.loadGroups()])
    },
    loadScans() {
      const requestId = ++this.scanRequestId
      this.scanLoading = true
      this.scanError = ''
      const params = {
        memberId: this.numOrOmit(this.scanQuery.memberId),
        registrationId: this.numOrOmit(this.scanQuery.registrationId),
        registrationType: this.scanQuery.registrationType
      }
      return scanList(params)
        .then(response => {
          if (requestId !== this.scanRequestId) return
          this.scans = response.data || []
        })
        .catch(() => {
          if (requestId !== this.scanRequestId) return
          this.scans = []
          this.scanError = '扫码核销证据读取失败，请重新读取。'
        })
        .finally(() => {
          if (requestId === this.scanRequestId) this.scanLoading = false
        })
    },
    loadGroups() {
      const requestId = ++this.groupRequestId
      this.groupLoading = true
      this.groupError = ''
      const params = {
        topicId: this.numOrOmit(this.groupQuery.topicId),
        merchantMemberId: this.numOrOmit(this.groupQuery.merchantMemberId),
        leaderMemberId: this.numOrOmit(this.groupQuery.leaderMemberId)
      }
      return groupVerifyList(params)
        .then(response => {
          if (requestId !== this.groupRequestId) return
          this.groups = response.data || []
        })
        .catch(() => {
          if (requestId !== this.groupRequestId) return
          this.groups = []
          this.groupError = '团核销证据读取失败，请重新读取。'
        })
        .finally(() => {
          if (requestId === this.groupRequestId) this.groupLoading = false
        })
    },
    resetScans() {
      this.scanQuery = this.defaultScanQuery()
      return this.loadScans()
    },
    resetGroups() {
      this.groupQuery = this.defaultGroupQuery()
      return this.loadGroups()
    },
    clearScanFilter(key) {
      if (key === 'registrationType') this.scanQuery.registrationType = 1
      else this.scanQuery[key] = ''
      return this.loadScans()
    },
    clearGroupFilter(key) {
      this.groupQuery[key] = ''
      return this.loadGroups()
    }
  }
}
</script>

<style lang="scss" scoped>
.verification-evidence__intro { margin-bottom: 20px; }
.verification-evidence__read-only { flex: none; padding: var(--cy-legacy-5) 9px; border: 1px solid var(--cy-color-border-strong); border-radius: 999px; color: var(--cy-color-text-secondary); font-size: 12px; font-weight: 700; }
.verification-evidence__tabs ::v-deep .el-tabs__header { margin-bottom: var(--cy-space-3-5); }
.verification-evidence__tabs ::v-deep .el-tabs__content { overflow: visible; }
.verification-evidence__table-wrap { min-height: 144px; }
.verification-evidence__table { width: 100%; }
.verification-evidence__party { display: grid; gap: var(--cy-legacy-3); }
.verification-evidence__party strong { color: var(--cy-color-text-primary); font-size: 13px; font-weight: 600; }
.verification-evidence__party span { color: var(--cy-color-text-tertiary); font-size: 12px; }

@media (max-width: 720px) {
  .verification-evidence__read-only { display: none; }
}
</style>
