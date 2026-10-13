<template>
  <div class="app-container merchant-dispatch">
    <cy-page-header
      eyebrow="商家与合作"
      title="商家调配"
      status="唯一决策入口"
      description="从主题报名候选中选定承接商家。选定只占用主题竞争位；合作邀约、条款与结算仍在各自流程中完成。"
    >
      <el-button slot="primary" size="small" icon="el-icon-refresh" :loading="loading" :disabled="actioning" @click="getTree">刷新候选</el-button>
    </cy-page-header>

    <el-form ref="queryForm" :model="queryParams" size="small" :inline="true" label-width="72px">
      <cy-filter-bar :active-filters="activeFilters" @submit="handleQuery" @reset="resetQuery" @remove-filter="clearFilter">
        <el-form-item label="主题名称">
          <el-input
            v-model="queryParams.topicName"
            placeholder="搜索主题名称"
            clearable
            @keyup.enter.native="handleQuery"
          />
        </el-form-item>
        <template slot="more">
          <el-form-item label="主题编号">
            <el-input
              v-model="queryParams.topicId"
              placeholder="精确定位"
              clearable
              inputmode="numeric"
              @keyup.enter.native="handleQuery"
            />
          </el-form-item>
        </template>
      </cy-filter-bar>
    </el-form>

    <cy-confirm-action
      :visible.sync="rejectDialogVisible"
      title="驳回商家报名"
      impact="驳回只会将该报名标记为落选，不会删除报名记录；原因会保留在调配记录中。"
      severity="danger"
      confirm-text="确认驳回"
      reason-label="驳回原因"
      reason-placeholder="请填写可追溯的驳回原因"
      :reason-maxlength="200"
      :loading="actioning"
      @confirm="confirmReject"
    />

    <cy-resource-state
      v-if="loadError"
      kind="error"
      title="候选报名未能加载"
      :description="loadErrorAfterAction ? '报名状态已更新，但候选列表未能刷新。请重试刷新；若仍失败，请核对调配接口和当前权限。' : '没有修改任何报名状态。请重试；若仍失败，请核对调配接口和当前权限。'"
      retry-text="重新加载"
      :retrying="loading"
      @retry="getTree"
    />

    <template v-else>
      <cy-empty-state
        v-if="!loading && filteredTree.length === 0"
        :title="hasActiveFilters ? '没有匹配的商家报名' : '暂无待调配的商家报名'"
        :description="hasActiveFilters ? '可清除筛选后查看其他主题的候选。' : '商家报名进入待调配状态后会显示在这里。'"
        :action-text="hasActiveFilters ? '清除筛选' : '刷新候选'"
        :action-loading="loading"
        @action="hasActiveFilters ? resetQuery() : getTree()"
      />

      <section v-else v-loading="loading" class="merchant-dispatch__topics" aria-label="商家报名调配列表">
        <el-collapse v-model="activeTopics">
          <el-collapse-item
            v-for="topic in filteredTree"
            :key="topic.topicId"
            :name="String(topic.topicId)"
          >
            <template slot="title">
              <div class="merchant-dispatch__topic-title">
                <div>
                  <span class="merchant-dispatch__topic-name">{{ topic.topicName || `未命名主题 #${topic.topicId}` }}</span>
                  <span class="merchant-dispatch__topic-id">主题 #{{ topic.topicId }}</span>
                </div>
                <div class="merchant-dispatch__topic-meta">
                  <cy-status-tag :label="poolLabel(topic)" :tone="poolTone(topic)" />
                  <span>{{ (topic.registrations || []).length }} 名候选</span>
                </div>
              </div>
            </template>

            <el-table :data="topic.registrations" size="small" class="merchant-dispatch__table">
              <el-table-column label="商家" min-width="180">
                <template slot-scope="scope">
                  <strong>商家 #{{ scope.row.memberId || '未记录' }}</strong>
                  <span class="merchant-dispatch__secondary">联系方式受保护</span>
                </template>
              </el-table-column>
              <el-table-column label="报名方式" min-width="110">
                <template slot-scope="scope">{{ modeLabel(scope.row.mode) }}</template>
              </el-table-column>
              <el-table-column label="竞争位" min-width="150" show-overflow-tooltip>
                <template slot-scope="scope">
                  <span v-if="hasNodeSlot(scope.row)">{{ scope.row.addressName || `节点 #${scope.row.nodeId}` }}</span>
                  <span v-else class="merchant-dispatch__secondary">主题级，不占竞争位</span>
                </template>
              </el-table-column>
              <el-table-column label="内容模板" width="104" align="center">
                <template slot-scope="scope">{{ scope.row.templateId != null ? `#${scope.row.templateId}` : '未关联' }}</template>
              </el-table-column>
              <el-table-column label="活动说明" min-width="180" prop="activityDesc" show-overflow-tooltip />
              <el-table-column label="调配状态" width="116" align="center">
                <template slot-scope="scope">
                  <cy-status-tag :label="auditText(scope.row.auditStatus)" :tone="auditTone(scope.row.auditStatus)" />
                </template>
              </el-table-column>
              <el-table-column label="处理原因" min-width="180" prop="reason" show-overflow-tooltip>
                <template slot-scope="scope">{{ scope.row.reason || '—' }}</template>
              </el-table-column>
              <el-table-column label="操作" width="176" align="right">
                <template slot-scope="scope">
                  <el-button
                    type="primary"
                    size="mini"
                    :loading="isActioning(scope.row)"
                    :disabled="!canSelect(topic, scope.row) || actioning"
                    v-hasPermi="['business:registrationmerchant:edit']"
                    @click="selectRegistration(scope.row)"
                  >选定商家</el-button>
                  <el-button
                    type="text"
                    size="mini"
                    :disabled="!isPending(scope.row) || actioning"
                    v-hasPermi="['business:registrationmerchant:edit']"
                    @click="openReject(scope.row)"
                  >驳回报名</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </section>
    </template>
  </div>
</template>

<script>
import { dispatchTree, selectDispatch, rejectDispatch } from '@/api/business/registrationmerchant'
import CyEmptyState from '@/components/CyEmptyState'
import CyConfirmAction from '@/components/CyConfirmAction'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

export default {
  name: 'RegistrationMerchantDispatch',
  components: {
    CyConfirmAction,
    CyEmptyState,
    CyFilterBar,
    CyPageHeader,
    CyResourceState,
    CyStatusTag
  },
  data() {
    return {
      loading: false,
      loadError: false,
      loadErrorAfterAction: false,
      tree: [],
      activeTopics: [],
      actioning: false,
      actioningId: null,
      rejectDialogVisible: false,
      rejectTarget: null,
      requestToken: 0,
      queryParams: {
        topicName: '',
        topicId: ''
      }
    }
  },
  computed: {
    filteredTree() {
      const keyword = this.queryParams.topicName.trim().toLowerCase()
      if (!keyword) return this.tree
      return this.tree.filter(topic => (topic.topicName || '').toLowerCase().includes(keyword))
    },
    activeFilters() {
      const filters = []
      if (this.queryParams.topicName) filters.push({ key: 'topicName', label: '主题名称', value: this.queryParams.topicName })
      if (this.queryParams.topicId) filters.push({ key: 'topicId', label: '主题编号', value: this.queryParams.topicId })
      return filters
    },
    hasActiveFilters() {
      return this.activeFilters.length > 0
    }
  },
  created() {
    this.queryParams.topicId = this.$route.query.topicId || ''
    this.queryParams.topicName = this.$route.query.topicName || ''
    this.getTree()
  },
  methods: {
    getTree(afterAction = false) {
      if (this.actioning && !afterAction) return Promise.resolve()
      const requestToken = ++this.requestToken
      this.loading = true
      this.loadError = false
      this.loadErrorAfterAction = false
      return dispatchTree(this.queryParams.topicId || undefined)
        .then(response => {
          if (requestToken !== this.requestToken) return
          this.tree = Array.isArray(response.data) ? response.data : []
          this.activeTopics = this.tree.map(topic => String(topic.topicId))
        })
        .catch(() => {
          if (requestToken !== this.requestToken) return
          this.loadError = true
          this.loadErrorAfterAction = afterAction
          this.tree = []
          this.activeTopics = []
        })
        .then(() => {
          if (requestToken === this.requestToken) this.loading = false
        })
    },
    handleQuery() {
      return this.getTree()
    },
    resetQuery() {
      this.queryParams.topicName = ''
      this.queryParams.topicId = ''
      return this.getTree()
    },
    clearFilter(key) {
      if (key === 'topicName' || key === 'topicId') this.queryParams[key] = ''
      return this.getTree()
    },
    hasNodeSlot(row) {
      return row.nodeId != null && row.nodeId !== 0
    },
    isPending(row) {
      return row.auditStatus == null || Number(row.auditStatus) === 0
    },
    isActioning(row) {
      return this.actioning && this.actioningId === row.id
    },
    slotTaken(topic, row) {
      if (!this.hasNodeSlot(row)) return false
      return (topic.registrations || []).some(candidate =>
        candidate.id !== row.id && candidate.nodeId === row.nodeId && Number(candidate.auditStatus) === 1
      )
    },
    canSelect(topic, row) {
      return this.isPending(row) && !this.slotTaken(topic, row)
    },
    auditText(status) {
      return Number(status) === 1 ? '已选定' : Number(status) === 2 ? '已驳回' : '待调配'
    },
    auditTone(status) {
      return Number(status) === 1 ? 'success' : Number(status) === 2 ? 'neutral' : 'warning'
    },
    poolLabel(topic) {
      return Number(topic.merchantStatus) === 1 ? '商家承接' : '自办主题'
    },
    poolTone(topic) {
      return Number(topic.merchantStatus) === 1 ? 'brand' : 'neutral'
    },
    modeLabel(mode) {
      return Number(mode) === 1 ? '城市定向' : Number(mode) === 2 ? '自由探索' : '未记录'
    },
    selectRegistration(row) {
      if (!this.isPending(row) || this.actioning) return Promise.resolve()
      this.actioning = true
      this.actioningId = row.id
      let confirmed = false
      return this.$modal.confirm('选定后将占用该主题竞争位；同一节点的其他待调配报名会自动落选。合作邀约和条款不会在此生成。')
        .then(() => {
          confirmed = true
          return this.runAction(
            () => selectDispatch(row.id),
            '已选定中标商家'
          )
        })
        .catch(() => {
          if (confirmed) this.$modal.msgError('未能选定商家，请刷新候选后重试')
        })
        .then(() => {
          this.releaseAction()
        })
    },
    openReject(row) {
      if (!this.isPending(row) || this.actioning) return
      this.rejectTarget = row
      this.rejectDialogVisible = true
    },
    confirmReject(reason) {
      if (!this.rejectTarget || this.actioning) return Promise.resolve()
      const target = this.rejectTarget
      this.actioning = true
      this.actioningId = target.id
      return this.runAction(
        () => rejectDispatch(target.id, reason),
        '已驳回报名'
      )
        .then(() => {
          this.rejectDialogVisible = false
          this.rejectTarget = null
        })
        .catch(() => {
          this.$modal.msgError('未能驳回报名，请刷新候选后重试')
        })
        .then(() => {
          this.releaseAction()
        })
    },
    runAction(request, successMessage) {
      return request()
        .then(response => {
          this.$modal.msgSuccess(response.msg || successMessage)
          return this.getTree(true)
        })
    },
    releaseAction() {
      this.actioning = false
      this.actioningId = null
    }
  }
}
</script>

<style lang="scss" scoped>
.merchant-dispatch__topics {
  overflow: hidden;
  border: 1px solid var(--cy-color-border-subtle);
  border-radius: var(--cy-radius-card);
  background: var(--cy-color-bg-surface);
  box-shadow: var(--cy-shadow-card);
}

.merchant-dispatch__topics ::v-deep .el-collapse { border: 0; }
.merchant-dispatch__topics ::v-deep .el-collapse-item__header { height: auto; min-height: 64px; padding: 0 var(--cy-space-page); border-bottom-color: var(--cy-color-border-subtle); color: inherit; line-height: 1.4; }
.merchant-dispatch__topics ::v-deep .el-collapse-item__wrap { border-bottom-color: var(--cy-color-border-subtle); }
.merchant-dispatch__topics ::v-deep .el-collapse-item__content { padding: 0; }

.merchant-dispatch__topic-title { display: flex; flex: 1; align-items: center; justify-content: space-between; gap: 16px; padding-right: 12px; }
.merchant-dispatch__topic-name { color: var(--cy-color-text-primary); font-size: 15px; font-weight: 700; }
.merchant-dispatch__topic-id { margin-left: 8px; color: var(--cy-color-text-tertiary); font-size: 12px; }
.merchant-dispatch__topic-meta { display: flex; align-items: center; gap: var(--cy-space-1-5); color: var(--cy-color-text-tertiary); font-size: 12px; }
.merchant-dispatch__table { width: 100%; }
.merchant-dispatch__table ::v-deep th { height: 40px; background: var(--cy-color-bg-subtle); color: var(--cy-color-text-secondary); font-size: 12px; font-weight: 700; }
.merchant-dispatch__table ::v-deep td { height: 48px; border-bottom-color: var(--cy-color-border-subtle); color: var(--cy-color-text-primary); }
.merchant-dispatch__secondary { display: block; margin-top: var(--cy-legacy-3); color: var(--cy-color-text-tertiary); font-size: 12px; }

@media (max-width: 720px) {
  .merchant-dispatch__topic-title { align-items: flex-start; flex-direction: column; gap: var(--cy-space-0-5); }
  .merchant-dispatch__topic-id { margin-left: 0; }
}
</style>
