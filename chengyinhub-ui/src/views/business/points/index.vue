<template>
  <div class="app-container cy-domain-page points-config">
    <cy-page-header
      eyebrow="运营配置"
      title="积分配置"
      description="系统内置积分规则直接影响注册、报名、评论、邀请和游玩奖励；这里只调整积分数值，设为 0 即停止该规则的奖励。"
    >
      <el-button
        slot="primary"
        plain
        icon="el-icon-wallet"
        v-hasPermi="['business:settlementrule:list']"
        @click="openSettlementRules"
      >查看结算规则</el-button>
    </cy-page-header>

    <cy-resource-state
      v-if="loadError"
      kind="error"
      title="积分规则暂时无法读取"
      description="没有改动任何积分配置。请重试；若仍失败，请核对积分规则读取权限和服务状态。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <template v-else>
      <cy-empty-state
        v-if="!loading && settingList.length === 0"
        title="尚未配置积分规则"
        description="积分奖励不会自动补发。请先核对生产规则数据与触发事件后再处理。"
      />

      <cy-data-table
        v-else
        :data="settingList"
        :columns="columns"
        :loading="loading"
        :total="total"
        :page="queryParams.pageNum"
        :limit="queryParams.pageSize"
        empty-text="当前没有积分规则"
        @refresh="getList"
        @pagination="handlePagination"
      >
        <template slot="rule" slot-scope="scope">
          <strong>{{ scope.row.title || '未命名规则' }}</strong>
          <div class="points-config__event">事件类型 {{ scope.row.eventType == null ? '-' : scope.row.eventType }}</div>
        </template>
        <template slot="value" slot-scope="scope">
          <strong>{{ displayPoints(scope.row.value) }}</strong>
        </template>
        <template slot="actions" slot-scope="scope">
          <el-button
            type="text"
            size="mini"
            icon="el-icon-edit"
            v-hasPermi="['business:pointsetting:edit']"
            @click="openEdit(scope.row)"
          >调整积分</el-button>
        </template>
      </cy-data-table>
    </template>

    <el-dialog title="调整积分奖励" :visible.sync="editOpen" width="480px" append-to-body :close-on-click-modal="false">
      <el-form ref="pointForm" :model="pointForm" :rules="pointRules" label-width="88px">
        <el-form-item label="规则">
          <span>{{ pointForm.title || '-' }}</span>
        </el-form-item>
        <el-form-item label="事件类型">
          <span>{{ pointForm.eventType == null ? '-' : pointForm.eventType }}</span>
        </el-form-item>
        <el-form-item label="规则说明">
          <span>{{ pointForm.description || '-' }}</span>
        </el-form-item>
        <el-form-item label="奖励积分" prop="value">
          <el-input-number v-model="pointForm.value" :min="0" :max="100000" :precision="0" controls-position="right" />
          <p class="points-config__hint">填 0 会停止后续奖励，不会回收已发积分；规则标识和事件类型不能在此页变更。</p>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button :disabled="saving" @click="editOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存积分</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listSetting, updateSetting } from '@/api/business/pointsetting'
import CyDataTable from '@/components/CyDataTable'
import CyEmptyState from '@/components/CyEmptyState'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

export default {
  name: 'PointsConfig',
  components: { CyDataTable, CyEmptyState, CyPageHeader, CyResourceState },
  data() {
    return {
      loading: false,
      loadError: false,
      saving: false,
      editOpen: false,
      requestToken: 0,
      total: 0,
      settingList: [],
      queryParams: {
        pageNum: 1,
        pageSize: 20
      },
      pointForm: this.emptyPointForm(),
      pointRules: {
        value: [{ required: true, type: 'number', message: '请输入 0 到 100000 的整数积分', trigger: 'change' }]
      },
      columns: [
        { key: 'rule', label: '积分规则', prop: 'title', minWidth: 190, locked: true, showOverflowTooltip: true },
        { key: 'description', label: '奖励说明', prop: 'description', minWidth: 230, showOverflowTooltip: true },
        { key: 'value', label: '奖励积分', prop: 'value', width: 110, align: 'right' },
        { key: 'sortOrder', label: '排序', prop: 'sortOrder', width: 80, align: 'center' },
        { key: 'actions', label: '操作', width: 100, align: 'right', fixed: 'right', locked: true }
      ]
    }
  },
  created() {
    this.getList()
  },
  methods: {
    emptyPointForm() {
      return { id: null, title: '', description: '', eventType: null, value: 0 }
    },
    getList() {
      const requestToken = ++this.requestToken
      this.loading = true
      this.loadError = false
      return listSetting({
        pageNum: this.queryParams.pageNum,
        pageSize: this.queryParams.pageSize,
        type: 1
      }).then(response => {
        if (requestToken !== this.requestToken) return null
        this.settingList = response.rows || []
        this.total = response.total || 0
        return true
      }).catch(() => {
        if (requestToken !== this.requestToken) return null
        this.settingList = []
        this.total = 0
        this.loadError = true
        return false
      }).then(result => {
        if (requestToken === this.requestToken) this.loading = false
        return result
      })
    },
    handlePagination({ page, limit }) {
      this.queryParams.pageNum = page
      this.queryParams.pageSize = limit
      return this.getList()
    },
    openEdit(row) {
      this.pointForm = {
        id: row.id,
        title: row.title || '',
        description: row.description || '',
        eventType: row.eventType,
        value: Number(row.value || 0)
      }
      this.editOpen = true
      this.$nextTick(() => {
        if (this.$refs.pointForm) this.$refs.pointForm.clearValidate()
      })
    },
    submitForm() {
      this.$refs.pointForm.validate(valid => {
        if (!valid || this.saving) return
        this.saving = true
        updateSetting({ id: this.pointForm.id, value: this.pointForm.value })
          .then(() => {
            this.$modal.msgSuccess('积分奖励已保存')
            this.editOpen = false
            return this.getList()
          })
          .then(reloaded => {
            if (reloaded === false) this.$modal.msgWarning('积分奖励已保存，但列表刷新失败，请稍后重新读取')
          })
          .catch(() => {
            this.$modal.msgError('保存失败，请核对积分值后重试')
          })
          .then(() => {
            this.saving = false
          })
      })
    },
    displayPoints(value) {
      return `${Number(value || 0)} 积分`
    },
    openSettlementRules() {
      this.$router.push({ path: '/p3-platform/settlementrule' })
    }
  }
}
</script>

<style lang="scss" scoped>
.points-config__event { margin-top: var(--cy-legacy-3); color: var(--cy-color-text-tertiary); font-size: 12px; }
.points-config__hint { margin: 8px 0 0; color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.65; }
</style>
