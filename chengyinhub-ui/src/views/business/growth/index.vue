<template>
  <div class="app-container cy-domain-page growth-config">
    <cy-page-header
      eyebrow="运营配置"
      title="成长任务配置"
      description="配置触发事件、经验奖励与启停状态。用户成长账户、进度和勋章只在用户 360 的成长档案中调证。"
    >
      <el-button
        slot="primary"
        type="primary"
        icon="el-icon-plus"
        v-hasPermi="['business:growth:edit']"
        @click="openMissionDialog()"
      >新增任务</el-button>
    </cy-page-header>

    <el-form ref="queryForm" :model="queryParams" size="small" :inline="true" label-width="72px" class="growth-config__filter">
      <cy-filter-bar :active-filters="activeFilters" @submit="handleQuery" @reset="resetQuery" @remove-filter="clearFilter">
        <el-form-item label="任务名称">
          <el-input v-model="queryParams.missionName" placeholder="搜索任务名称" clearable @keyup.enter.native="handleQuery" />
        </el-form-item>
        <template slot="more">
          <el-form-item label="任务编码">
            <el-input v-model="queryParams.missionCode" placeholder="精确或模糊查找" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="触发事件">
            <el-input v-model="queryParams.triggerEvent" placeholder="如 content_view" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="queryParams.status" placeholder="全部状态" clearable @change="handleQuery">
              <el-option label="启用" value="0" />
              <el-option label="已停用" value="1" />
            </el-select>
          </el-form-item>
        </template>
      </cy-filter-bar>
    </el-form>

    <cy-resource-state
      v-if="loadError"
      kind="error"
      title="成长任务暂时无法读取"
      description="没有改动任何任务配置。请重试；若仍失败，请核对任务读取权限和服务状态。"
      retry-text="重新读取"
      :retrying="missionLoading"
      @retry="getMissions"
    />

    <template v-else>
      <cy-empty-state
        v-if="!missionLoading && missionList.length === 0"
        :title="hasActiveFilters ? '没有匹配的成长任务' : '尚未配置成长任务'"
        :description="hasActiveFilters ? '可清除筛选后查看其他任务。' : '新增任务后，服务端事件才会产生对应的成长经验。'"
      />

      <cy-data-table
        v-else
        :data="missionList"
        :columns="missionColumns"
        :loading="missionLoading"
        :total="total"
        :page="queryParams.pageNum"
        :limit="queryParams.pageSize"
        empty-text="当前条件下没有成长任务"
        @refresh="getMissions"
        @pagination="handlePagination"
      >
        <template slot="status" slot-scope="scope">
          <cy-status-tag :label="missionStatusLabel(scope.row.status)" :tone="missionStatusTone(scope.row.status)" />
        </template>
        <template slot="actions" slot-scope="scope">
          <el-button
            type="text"
            size="mini"
            icon="el-icon-edit"
            v-hasPermi="['business:growth:edit']"
            @click="openMissionDialog(scope.row)"
          >编辑</el-button>
        </template>
      </cy-data-table>
    </template>

    <el-dialog :title="missionForm.id ? '编辑成长任务' : '新增成长任务'" :visible.sync="missionOpen" width="560px" append-to-body :close-on-click-modal="false">
      <el-form ref="missionForm" :model="missionForm" :rules="missionRules" label-width="96px">
        <el-form-item label="任务编码" prop="missionCode">
          <el-input v-model="missionForm.missionCode" :disabled="Boolean(missionForm.id)" placeholder="稳定事件标识，如 DAILY_CHECKIN" />
        </el-form-item>
        <el-form-item label="任务名称" prop="missionName">
          <el-input v-model="missionForm.missionName" placeholder="面向运营的任务名称" />
        </el-form-item>
        <el-form-item label="任务说明" prop="missionDesc">
          <el-input v-model="missionForm.missionDesc" type="textarea" :rows="3" maxlength="300" show-word-limit placeholder="说明完成口径和展示含义" />
        </el-form-item>
        <el-form-item label="触发事件" prop="triggerEvent">
          <el-input v-model="missionForm.triggerEvent" placeholder="服务端真实事件名，如 content_view" />
        </el-form-item>
        <el-form-item label="经验奖励" prop="expReward">
          <el-input-number v-model="missionForm.expReward" :min="0" :precision="0" controls-position="right" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="状态" prop="status">
              <el-radio-group v-model="missionForm.status">
                <el-radio label="0">启用</el-radio>
                <el-radio label="1">停用</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="排序" prop="sortNo">
              <el-input-number v-model="missionForm.sortNo" :min="0" :precision="0" controls-position="right" />
            </el-form-item>
          </el-col>
        </el-row>
        <p class="growth-config__audit-note">保存会记录后台操作审计；任务编码创建后不可在此页修改，以免改变既有事件语义。</p>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button :disabled="saving" @click="missionOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitMission">保存任务</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listGrowthMissions, saveGrowthMission } from '@/api/business/growth'
import CyDataTable from '@/components/CyDataTable'
import CyEmptyState from '@/components/CyEmptyState'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

export default {
  name: 'BusinessGrowth',
  components: { CyDataTable, CyEmptyState, CyFilterBar, CyPageHeader, CyResourceState, CyStatusTag },
  data() {
    return {
      missionLoading: false,
      missionOpen: false,
      saving: false,
      loadError: false,
      requestToken: 0,
      total: 0,
      missionList: [],
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        missionCode: '',
        missionName: '',
        triggerEvent: '',
        status: ''
      },
      missionForm: this.emptyMissionForm(),
      missionRules: {
        missionCode: [{ required: true, message: '请输入任务编码', trigger: 'blur' }],
        missionName: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
        triggerEvent: [{ required: true, message: '请输入服务端触发事件', trigger: 'blur' }],
        expReward: [{ required: true, type: 'number', message: '请设置经验奖励', trigger: 'change' }]
      },
      missionColumns: [
        { key: 'missionCode', label: '任务编码', prop: 'missionCode', minWidth: 150, locked: true, showOverflowTooltip: true },
        { key: 'missionName', label: '任务名称', prop: 'missionName', minWidth: 160, showOverflowTooltip: true },
        { key: 'triggerEvent', label: '触发事件', prop: 'triggerEvent', minWidth: 160, showOverflowTooltip: true },
        { key: 'expReward', label: '经验奖励', prop: 'expReward', width: 96, align: 'center' },
        { key: 'status', label: '状态', prop: 'status', width: 100, align: 'center' },
        { key: 'sortNo', label: '排序', prop: 'sortNo', width: 80, align: 'center' },
        { key: 'actions', label: '操作', width: 94, align: 'right', fixed: 'right', locked: true }
      ]
    }
  },
  computed: {
    activeFilters() {
      const filters = []
      if (this.queryParams.missionName) filters.push({ key: 'missionName', label: '任务名称', value: this.queryParams.missionName })
      if (this.queryParams.missionCode) filters.push({ key: 'missionCode', label: '任务编码', value: this.queryParams.missionCode })
      if (this.queryParams.triggerEvent) filters.push({ key: 'triggerEvent', label: '触发事件', value: this.queryParams.triggerEvent })
      if (this.queryParams.status !== '') filters.push({ key: 'status', label: '状态', value: this.missionStatusLabel(this.queryParams.status) })
      return filters
    },
    hasActiveFilters() {
      return this.activeFilters.length > 0
    }
  },
  created() {
    this.getMissions()
  },
  methods: {
    emptyMissionForm() {
      return {
        id: null,
        missionCode: '',
        missionName: '',
        missionDesc: '',
        triggerEvent: '',
        expReward: 0,
        status: '0',
        sortNo: 0
      }
    },
    getMissions() {
      const requestToken = ++this.requestToken
      this.missionLoading = true
      this.loadError = false
      return listGrowthMissions(this.queryParams)
        .then(response => {
          if (requestToken !== this.requestToken) return
          this.missionList = response.rows || []
          this.total = response.total || 0
          return true
        })
        .catch(() => {
          if (requestToken !== this.requestToken) return null
          this.missionList = []
          this.total = 0
          this.loadError = true
          return false
        })
        .then(result => {
          if (requestToken === this.requestToken) this.missionLoading = false
          return result
        })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      return this.getMissions()
    },
    resetQuery() {
      this.queryParams = {
        pageNum: 1,
        pageSize: this.queryParams.pageSize,
        missionCode: '',
        missionName: '',
        triggerEvent: '',
        status: ''
      }
      return this.getMissions()
    },
    clearFilter(key) {
      if (Object.prototype.hasOwnProperty.call(this.queryParams, key)) this.queryParams[key] = ''
      return this.handleQuery()
    },
    handlePagination({ page, limit }) {
      this.queryParams.pageNum = page
      this.queryParams.pageSize = limit
      return this.getMissions()
    },
    openMissionDialog(row) {
      this.missionForm = row ? {
        id: row.id,
        missionCode: row.missionCode || '',
        missionName: row.missionName || '',
        missionDesc: row.missionDesc || '',
        triggerEvent: row.triggerEvent || '',
        expReward: Number(row.expReward || 0),
        status: String(row.status == null ? '0' : row.status),
        sortNo: Number(row.sortNo || 0)
      } : this.emptyMissionForm()
      this.missionOpen = true
      this.$nextTick(() => {
        if (this.$refs.missionForm) this.$refs.missionForm.clearValidate()
      })
    },
    submitMission() {
      this.$refs.missionForm.validate(valid => {
        if (!valid || this.saving) return
        this.saving = true
        saveGrowthMission(this.missionForm)
          .then(() => {
            this.$modal.msgSuccess('成长任务已保存')
            this.missionOpen = false
            return this.getMissions()
          })
          .then(reloaded => {
            if (reloaded === false) {
              this.$modal.msgWarning('成长任务已保存，但列表刷新失败，请稍后重新读取')
            }
          })
          .catch(() => {
            this.$modal.msgError('保存失败，请核对任务字段后重试')
          })
          .then(() => {
            this.saving = false
          })
      })
    },
    missionStatusLabel(status) {
      return String(status) === '0' ? '启用' : '已停用'
    },
    missionStatusTone(status) {
      return String(status) === '0' ? 'success' : 'neutral'
    }
  }
}
</script>

<style lang="scss" scoped>
.growth-config__filter { margin: 0 0 var(--cy-space-3-5); padding: var(--cy-space-3-5) 20px 0; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); box-shadow: var(--cy-shadow-card); }
.growth-config__audit-note { margin: 0; padding: 12px var(--cy-space-2-5); border-radius: var(--cy-radius-control); background: var(--cy-color-bg-subtle); color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.65; }
</style>
