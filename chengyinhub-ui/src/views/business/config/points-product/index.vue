<template>
  <div class="app-container cy-domain-page">
    <cy-page-header
      eyebrow="商品与积分"
      title="积分商城商品"
      description="管理积分商城目录。商品创建后先进入审核中；上架和下架不会删除商品、SKU 或历史订单。"
    >
      <el-button
        v-hasPermi="['business:product:add']"
        type="primary"
        icon="el-icon-plus"
        @click="openCreate"
      >新增商品</el-button>
    </cy-page-header>

    <section class="cy-panel product-filter">
      <div class="cy-panel__heading">
        <div>
          <h2>商品目录</h2>
          <p>按名称和状态定位商品；目录操作只改变可见状态，资料和规格在商品编辑页维护。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryParams" size="small" :inline="true" label-width="72px">
        <cy-filter-bar :active-filters="activeFilters" @submit="handleQuery" @reset="resetQuery" @remove-filter="clearFilter">
          <el-form-item label="商品名称" prop="productName">
            <el-input v-model="queryParams.productName" placeholder="名称关键词" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item label="上架状态" prop="publishStatus">
            <el-select v-model="queryParams.publishStatus" placeholder="全部状态" clearable>
              <el-option label="审核中" :value="0" />
              <el-option label="已上架" :value="1" />
              <el-option label="已下架" :value="2" />
            </el-select>
          </el-form-item>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-resource-state
      v-if="loadError"
      kind="error"
      title="商品目录暂时无法读取"
      description="请稍后重试；本页尚未提交任何商品状态变更。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <cy-data-table
      v-else
      :data="productList"
      :columns="tableColumns"
      :loading="loading"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      default-density="comfortable"
      empty-text="当前筛选下没有积分商城商品"
      @pagination="handlePagination"
      @refresh="getList"
    >
      <template slot="image" slot-scope="scope">
        <cy-media-preview v-if="mediaSources(scope.row).length" :sources="mediaSources(scope.row)" :label="scope.row.productName || '商品图片'" :width="72" :height="54" />
        <span v-else class="muted-cell">未配置图片</span>
      </template>
      <template slot="product" slot-scope="scope">
        <div class="table-stack">
          <strong>{{ scope.row.productName || '未命名商品' }}</strong>
          <small><cy-copy-value :value="scope.row.id" label="商品 ID" /></small>
        </div>
      </template>
      <template slot="price" slot-scope="scope">
        <span>{{ formatPrice(scope.row.price) }}</span>
      </template>
      <template slot="status" slot-scope="scope">
        <cy-status-tag :label="statusLabel(scope.row.publishStatus)" :tone="statusTone(scope.row.publishStatus)" />
      </template>
      <template slot="updated" slot-scope="scope">
        <div class="table-stack">
          <span>{{ scope.row.updateTime || scope.row.createTime || '—' }}</span>
          <small>{{ scope.row.updateBy || scope.row.createBy || '系统记录' }}</small>
        </div>
      </template>
      <template slot="actions" slot-scope="scope">
        <el-button
          v-hasPermi="['business:product:edit']"
          size="mini"
          type="text"
          :disabled="statusSubmittingId === scope.row.id"
          @click="openEdit(scope.row)"
        >编辑</el-button>
        <el-button
          v-hasPermi="['business:product:add']"
          size="mini"
          type="text"
          :disabled="statusSubmittingId === scope.row.id"
          @click="openCopy(scope.row)"
        >复制</el-button>
        <el-button
          v-if="scope.row.publishStatus !== STATUS_ON_SHELF"
          v-hasPermi="['business:product:edit']"
          size="mini"
          type="text"
          :loading="statusSubmittingId === scope.row.id"
          :disabled="statusSubmittingId !== null"
          @click="changePublishStatus(scope.row, STATUS_ON_SHELF)"
        >上架</el-button>
        <el-button
          v-if="scope.row.publishStatus !== STATUS_OFF_SHELF"
          v-hasPermi="['business:product:edit']"
          size="mini"
          type="text"
          class="downline-action"
          :loading="statusSubmittingId === scope.row.id"
          :disabled="statusSubmittingId !== null"
          @click="changePublishStatus(scope.row, STATUS_OFF_SHELF)"
        >下架</el-button>
        <el-button
          v-if="scope.row.publishStatus !== STATUS_PENDING"
          v-hasPermi="['business:product:edit']"
          size="mini"
          type="text"
          :loading="statusSubmittingId === scope.row.id"
          :disabled="statusSubmittingId !== null"
          @click="changePublishStatus(scope.row, STATUS_PENDING)"
        >恢复审核</el-button>
      </template>
    </cy-data-table>
  </div>
</template>

<script>
import { listProduct, updateProductPublishStatus } from '@/api/business/product'
import CyCopyValue from '@/components/CyCopyValue'
import CyDataTable from '@/components/CyDataTable'
import CyFilterBar from '@/components/CyFilterBar'
import CyMediaPreview from '@/components/CyMediaPreview'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

const STATUS_PENDING = 0
const STATUS_ON_SHELF = 1
const STATUS_OFF_SHELF = 2

const emptyQuery = () => ({
  pageNum: 1,
  pageSize: 20,
  productName: null,
  publishStatus: null,
  delFlag: 0
})

export default {
  name: 'PointsProductCatalog',
  components: { CyCopyValue, CyDataTable, CyFilterBar, CyMediaPreview, CyPageHeader, CyResourceState, CyStatusTag },
  data() {
    return {
      STATUS_ON_SHELF,
      STATUS_OFF_SHELF,
      STATUS_PENDING,
      loading: false,
      loadError: false,
      listRequestId: 0,
      statusSubmittingId: null,
      total: 0,
      productList: [],
      queryParams: emptyQuery(),
      tableColumns: [
        { key: 'image', label: '主图', width: 96, locked: true },
        { key: 'product', label: '商品', minWidth: 200, locked: true },
        { key: 'price', label: '积分售价', width: 110, align: 'right' },
        { key: 'status', label: '上架状态', width: 120 },
        { key: 'updated', label: '最近操作', minWidth: 170 },
        { key: 'actions', label: '操作', width: 280, fixed: 'right', align: 'right', locked: true }
      ]
    }
  },
  computed: {
    activeFilters() {
      return [
        this.queryParams.productName && { key: 'productName', label: '商品名称', value: this.queryParams.productName },
        this.queryParams.publishStatus !== null && this.queryParams.publishStatus !== undefined && {
          key: 'publishStatus', label: '上架状态', value: this.statusLabel(this.queryParams.publishStatus)
        }
      ].filter(Boolean)
    }
  },
  created() {
    this.hydrateQuery(this.$route.query)
    this.getList()
  },
  methods: {
    getList() {
      const requestId = ++this.listRequestId
      const query = Object.assign({}, this.queryParams)
      this.loading = true
      this.loadError = false
      return listProduct(query).then(response => {
        if (requestId !== this.listRequestId) return
        this.productList = response.rows || []
        this.total = response.total || 0
      }).catch(() => {
        if (requestId === this.listRequestId) this.loadError = true
      }).finally(() => {
        if (requestId === this.listRequestId) this.loading = false
      })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    handlePagination() {
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.queryParams = emptyQuery()
      this.getList()
    },
    clearFilter(key) {
      this.queryParams[key] = null
      this.handleQuery()
    },
    hydrateQuery(query) {
      const source = query || {}
      const pageNum = Number(source.pageNum)
      const pageSize = Number(source.pageSize)
      const publishStatus = Number(source.publishStatus)
      this.queryParams = Object.assign(emptyQuery(), {
        pageNum: Number.isInteger(pageNum) && pageNum > 0 ? pageNum : 1,
        pageSize: Number.isInteger(pageSize) && pageSize > 0 ? pageSize : 20,
        productName: typeof source.productName === 'string' && source.productName.trim()
          ? source.productName.trim()
          : null,
        publishStatus: [STATUS_PENDING, STATUS_ON_SHELF, STATUS_OFF_SHELF].includes(publishStatus)
          ? publishStatus
          : null
      })
    },
    openCreate() {
      this.$router.push({ path: '/config/points-product/create' })
    },
    openEdit(row) {
      this.$router.push({ path: `/config/points-product/${row.id}/edit` })
    },
    openCopy(row) {
      this.$router.push({ path: `/config/points-product/${row.id}/edit`, query: { action: 'copy' } })
    },
    changePublishStatus(row, publishStatus) {
      if (!row || !row.id || row.publishStatus === publishStatus || this.statusSubmittingId !== null) return Promise.resolve()
      const action = publishStatus === STATUS_ON_SHELF
        ? '上架'
        : (publishStatus === STATUS_OFF_SHELF ? '下架' : '恢复审核')
      return this.$modal.confirm(`确认${action}“${row.productName || `商品 #${row.id}`}”吗？商品和 SKU 不会被删除。`)
        .then(() => {
          this.statusSubmittingId = row.id
          return updateProductPublishStatus({ id: row.id, publishStatus })
        })
        .then(() => {
          this.$modal.msgSuccess(`商品已${action}`)
          return this.getList()
        })
        .catch(() => {})
        .finally(() => {
          this.statusSubmittingId = null
        })
    },
    mediaSources(row) {
      const album = typeof row.albumPics === 'string' ? row.albumPics.split(',') : []
      return [row.pic].concat(album).filter(Boolean)
    },
    formatPrice(value) {
      return value === null || value === undefined || value === '' ? '—' : `${value} 积分`
    },
    statusLabel(status) {
      if (status === STATUS_ON_SHELF) return '已上架'
      if (status === STATUS_OFF_SHELF) return '已下架'
      return '审核中'
    },
    statusTone(status) {
      if (status === STATUS_ON_SHELF) return 'success'
      if (status === STATUS_OFF_SHELF) return 'neutral'
      return 'warning'
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-domain-page { color: var(--cy-color-text-primary); }
.cy-panel { margin-bottom: 20px; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); box-shadow: var(--cy-shadow-card); }
.cy-panel__heading { padding: 20px var(--cy-space-page) 16px; }
.cy-panel__heading h2 { margin: 0; font-size: 17px; }
.cy-panel__heading p { margin: var(--cy-space-0-5) 0 0; color: var(--cy-color-text-secondary); font-size: 13px; }
.table-stack { display: grid; gap: var(--cy-legacy-3); min-width: 0; }
.table-stack strong, .table-stack span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.table-stack small, .muted-cell { color: var(--cy-color-text-tertiary); }
.downline-action { color: var(--cy-color-text-secondary); }
</style>
