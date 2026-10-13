<template>
  <div class="app-container ticket-locator">
    <cy-page-header
      eyebrow="供给 / 票务"
      title="票务定位"
      description="票务已归属主题或活动详情；此页只用于检索和回到所属对象，不提供独立改价、删除或导出。"
      status="只读过渡"
    />

    <el-form ref="queryForm" :model="queryForm" size="small" :inline="true" label-width="84px">
      <cy-filter-bar
        :active-filters="activeFilters"
        @submit="handleQuery"
        @reset="resetQuery"
        @remove-filter="clearFilter"
      >
        <el-form-item label="票务名称">
          <el-input
            v-model="queryForm.name"
            placeholder="按票务名称检索"
            clearable
            @keyup.enter.native="handleQuery"
          />
        </el-form-item>
        <template #more>
          <el-form-item label="所属类型">
            <el-select v-model="queryForm.ownerType" clearable placeholder="主题或活动">
              <el-option label="主题" :value="1" />
              <el-option label="活动" :value="2" />
            </el-select>
          </el-form-item>
          <el-form-item label="所属对象 ID">
            <el-input
              v-model="queryForm.ownerId"
              placeholder="精确定位主题或活动"
              clearable
              @keyup.enter.native="handleQuery"
            />
          </el-form-item>
        </template>
      </cy-filter-bar>
    </el-form>

    <el-alert
      v-if="locatorError"
      class="ticket-locator__error"
      type="error"
      :closable="false"
      title="票务列表暂时无法读取，请稍后刷新。"
    />

    <cy-data-table
      :data="ticketList"
      :columns="tableColumns"
      :loading="loading"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      empty-text="没有匹配的票务；可调整名称或所属对象后重试。"
      @pagination="handlePagination"
      @refresh="getList"
    >
      <template #ticket="{ row }">
        <div class="ticket-locator__ticket">
          <strong>{{ row.name || '未命名票务' }}</strong>
          <span>#{{ row.id }}</span>
        </div>
      </template>
      <template #owner="{ row }">
        <cy-entity-link
          v-if="canOpenOwner(row)"
          :name="ownerLabel(row)"
          :entity-type="ownerEntityType(row)"
          :type-label="ownerTypeLabel(row.ownerType)"
          :entity-id="row.ownerId"
          meta="查看详情中的票务"
          @open="openOwner(row)"
        />
        <span v-else-if="hasKnownOwner(row)">无所属详情查看权限</span>
        <span v-else>未记录所属对象</span>
      </template>
      <template #price="{ row }">
        {{ money(row.price) }}
      </template>
      <template #window="{ row }">
        {{ dateRange(row.startTime, row.endTime) }}
      </template>
      <template #inventory="{ row }">
        {{ inventoryLabel(row) }}
      </template>
    </cy-data-table>
  </div>
</template>

<script>
import { listTicket } from '@/api/business/ticket'
import CyDataTable from '@/components/CyDataTable'
import CyEntityLink from '@/components/CyEntityLink'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'

const emptyFilters = () => ({
  ownerType: null,
  ownerId: null,
  name: null
})

export default {
  name: 'TicketLocator',
  components: { CyDataTable, CyEntityLink, CyFilterBar, CyPageHeader },
  data() {
    return {
      loading: false,
      locatorError: false,
      requestId: 0,
      total: 0,
      ticketList: [],
      queryForm: emptyFilters(),
      queryParams: Object.assign({ pageNum: 1, pageSize: 10 }, emptyFilters()),
      tableColumns: [
        { key: 'ticket', label: '票务', prop: 'name', minWidth: 180, locked: true },
        { key: 'owner', label: '所属对象', minWidth: 190 },
        { key: 'price', label: '票价', prop: 'price', width: 104, align: 'right' },
        { key: 'window', label: '有效时间', minWidth: 210 },
        { key: 'inventory', label: '库存', minWidth: 110, align: 'right' }
      ]
    }
  },
  computed: {
    activeFilters() {
      const filters = []
      if (this.queryForm.name) filters.push({ key: 'name', label: '票务名称', value: this.queryForm.name })
      if (this.queryForm.ownerType != null) filters.push({ key: 'ownerType', label: '所属类型', value: this.ownerTypeLabel(this.queryForm.ownerType) })
      if (this.queryForm.ownerId) filters.push({ key: 'ownerId', label: '所属对象 ID', value: this.queryForm.ownerId })
      return filters
    }
  },
  created() {
    this.hydrateFromRoute(this.$route.query)
    this.getList()
  },
  beforeRouteUpdate(to, from, next) {
    this.hydrateFromRoute(to.query)
    this.getList()
    next()
  },
  methods: {
    hydrateFromRoute(query = {}) {
      const ownerType = Number(query.ownerType)
      this.queryForm = {
        ownerType: ownerType === 1 || ownerType === 2 ? ownerType : null,
        ownerId: query.ownerId || null,
        name: query.name || null
      }
      this.queryParams = Object.assign({ pageNum: 1, pageSize: 10 }, this.queryForm)
    },
    getList() {
      const requestId = ++this.requestId
      this.loading = true
      this.locatorError = false
      const query = Object.assign({}, this.queryParams)
      return listTicket(query)
        .then(response => {
          if (requestId !== this.requestId) return
          this.ticketList = response.rows || []
          this.total = response.total || 0
        })
        .catch(() => {
          if (requestId !== this.requestId) return
          this.ticketList = []
          this.total = 0
          this.locatorError = true
        })
        .finally(() => {
          if (requestId === this.requestId) this.loading = false
        })
    },
    handleQuery() {
      this.queryParams = Object.assign({ pageNum: 1, pageSize: this.queryParams.pageSize }, this.queryForm)
      return this.getList()
    },
    handlePagination({ page, limit }) {
      this.queryParams.pageNum = page
      this.queryParams.pageSize = limit
      return this.getList()
    },
    resetQuery() {
      this.queryForm = emptyFilters()
      this.queryParams = Object.assign({ pageNum: 1, pageSize: this.queryParams.pageSize }, this.queryForm)
      return this.getList()
    },
    clearFilter(key) {
      this.queryForm[key] = null
      this.handleQuery()
    },
    hasKnownOwner(row) {
      return (Number(row.ownerType) === 1 || Number(row.ownerType) === 2) && row.ownerId != null
    },
    canOpenOwner(row) {
      if (!this.hasKnownOwner(row)) return false
      const permission = Number(row.ownerType) === 1 ? 'business:topic:query' : 'business:activity:query'
      const permissions = (this.$store.getters && this.$store.getters.permissions) || []
      return permissions.includes('*:*:*') || permissions.includes(permission)
    },
    ownerTypeLabel(ownerType) {
      return Number(ownerType) === 1 ? '主题' : Number(ownerType) === 2 ? '活动' : '未知类型'
    },
    ownerEntityType(row) {
      return Number(row.ownerType) === 1 ? 'topic' : 'activity'
    },
    ownerLabel(row) {
      return `${this.ownerTypeLabel(row.ownerType)}票务`
    },
    openOwner(row) {
      if (!this.hasKnownOwner(row)) {
        this.$modal.msgWarning('该票缺少可定位的主题或活动归属')
        return
      }
      if (!this.canOpenOwner(row)) {
        this.$modal.msgWarning(`没有查看${this.ownerTypeLabel(row.ownerType)}详情的权限`)
        return
      }
      this.$router.push({
        name: Number(row.ownerType) === 1 ? 'topicDetail' : 'activityDetail',
        query: { id: row.ownerId }
      })
    },
    money(value) {
      if (value == null || value === '' || Number.isNaN(Number(value))) return '未记录'
      return `¥${Number(value).toFixed(2)}`
    },
    dateRange(startTime, endTime) {
      if (!startTime && !endTime) return '未记录'
      return `${startTime || '未设置'} 至 ${endTime || '未设置'}`
    },
    inventoryLabel(row) {
      if (row.totalInventory == null && row.remainingInventory == null) return '未记录'
      return `${row.remainingInventory == null ? '—' : row.remainingInventory} / ${row.totalInventory == null ? '—' : row.totalInventory}`
    }
  }
}
</script>

<style lang="scss" scoped>
.ticket-locator__error { margin-bottom: 16px; }
.ticket-locator__ticket { display: grid; gap: 4px; }
.ticket-locator__ticket strong { color: var(--cy-color-text-primary); }
.ticket-locator__ticket span { color: var(--cy-color-text-tertiary); font-size: 12px; }
</style>
