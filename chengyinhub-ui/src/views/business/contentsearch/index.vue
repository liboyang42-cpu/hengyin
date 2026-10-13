<template>
  <div class="app-container cy-domain-page">
    <cy-page-header
      eyebrow="审核与风控"
      title="内容检索"
      description="检索帖文与评论证据；审核队列由举报等现有风控链路生成，处置只在统一审核台完成。"
    >
      <el-button icon="el-icon-s-check" @click="openAuditQueue">前往统一审核台</el-button>
    </cy-page-header>

    <section class="cy-panel content-search__filter">
      <div class="cy-panel__heading">
        <div>
          <h2>内容范围</h2>
          <p>两类内容仍由各自的真实查询接口提供数据；切换类型不会把两份分页结果拼成不可信的“全部”。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryParams" size="small" :inline="true" label-width="72px">
        <cy-filter-bar :active-filters="activeFilters" @submit="handleQuery" @reset="resetQuery" @remove-filter="clearFilter">
          <el-form-item label="内容类型">
            <el-radio-group v-model="contentType" size="small" @change="switchContentType">
              <el-radio-button label="SQUARE">帖文</el-radio-button>
              <el-radio-button label="COMMENT">评论</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="发布人" prop="memberId">
            <el-input v-model="queryParams.memberId" placeholder="用户 ID" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
          <el-form-item v-if="contentType === 'SQUARE'" label="查阅状态" prop="checkStatus">
            <el-select v-model="queryParams.checkStatus" placeholder="全部状态" clearable>
              <el-option label="未查阅" value="0" />
              <el-option label="已查阅" value="1" />
              <el-option label="已处理" value="2" />
            </el-select>
          </el-form-item>
          <template v-if="contentType === 'COMMENT'" slot="more">
            <el-form-item label="所属类型" prop="ownerType">
              <el-select v-model="queryParams.ownerType" placeholder="全部类型" clearable>
                <el-option label="主题" value="1" />
                <el-option label="活动" value="2" />
                <el-option label="创意广场" value="3" />
              </el-select>
            </el-form-item>
            <el-form-item label="所属对象" prop="ownerId">
              <el-input v-model="queryParams.ownerId" placeholder="对象 ID" clearable @keyup.enter.native="handleQuery" />
            </el-form-item>
          </template>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-resource-state
      v-if="loadError"
      kind="error"
      title="内容列表暂时无法读取"
      description="请刷新重试；审核处理不会在这个检索页降级执行。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <cy-data-table
      v-else
      :data="contentList"
      :columns="tableColumns"
      :loading="loading"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      default-density="comfortable"
      :empty-text="emptyText"
      @pagination="getList"
      @refresh="getList"
    >
      <template slot="subject" slot-scope="scope">
        <button type="button" class="content-search__subject" @click="openEvidence(scope.row)">
          <strong>{{ scope.row.contents | sanitize || '（无文字内容）' }}</strong>
          <span>#{{ scope.row.id }}</span>
        </button>
      </template>
      <template slot="context" slot-scope="scope">
        <template v-if="contentType === 'SQUARE'">
          <dict-tag :options="dict.type.sys_square_type" :value="scope.row.dataType" />
        </template>
        <span v-else>{{ ownerTypeLabel(scope.row.ownerType) }} #{{ scope.row.ownerId || '—' }}</span>
      </template>
      <template slot="media" slot-scope="scope">
        <div v-if="imageList(scope.row).length" class="content-search__media">
          <el-image
            :src="imageList(scope.row)[0]"
            :preview-src-list="imageList(scope.row)"
            fit="cover"
          >
            <div slot="error" class="content-search__image-error"><i class="el-icon-picture-outline" /></div>
          </el-image>
          <span v-if="imageList(scope.row).length > 1">+{{ imageList(scope.row).length - 1 }}</span>
        </div>
        <span v-else>无图片</span>
      </template>
      <template slot="metrics" slot-scope="scope">
        <template v-if="contentType === 'SQUARE'">
          <span>赞 {{ scope.row.likeNum || 0 }} · 踩 {{ scope.row.nolikeNum || 0 }}</span>
        </template>
        <template v-else>
          <span>评分 {{ scope.row.rating === null || scope.row.rating === undefined ? '—' : scope.row.rating }}</span>
        </template>
      </template>
      <template slot="status" slot-scope="scope">
        <dict-tag :options="dict.type.sys_business_status" :value="scope.row.status" />
        <small v-if="contentType === 'SQUARE'" class="content-search__secondary-status">
          <dict-tag :options="dict.type.sys_merchant_status" :value="scope.row.checkStatus" />
        </small>
      </template>
      <template slot="actions" slot-scope="scope">
        <el-button size="mini" type="text" icon="el-icon-view" @click="openEvidence(scope.row)">查看证据</el-button>
        <el-button size="mini" type="text" icon="el-icon-s-check" @click="openAudit(scope.row)">前往审核台</el-button>
      </template>
    </cy-data-table>

    <el-drawer title="内容证据" :visible.sync="detailOpen" size="640px" append-to-body>
      <div v-loading="detailLoading" class="content-search__detail">
        <cy-resource-state
          v-if="detailError"
          kind="error"
          title="内容详情暂时无法读取"
          description="可以返回列表重试，也可以从统一审核台查看已有审核快照。"
          retry-text="重新读取"
          :retrying="detailLoading"
          @retry="retryDetail"
        />
        <template v-else-if="detail">
          <cy-entity-header
            :eyebrow="contentKindLabel(detail)"
            :title="`内容 #${detail.id}`"
            :facts="detailFacts"
          >
            <template slot="actions">
              <el-button size="mini" type="text" icon="el-icon-s-check" @click="openAudit(detail, detailSource)">前往审核台</el-button>
            </template>
          </cy-entity-header>
          <section class="content-search__evidence">
            <h3>正文</h3>
            <p>{{ detail.contents | sanitize || '（无文字内容）' }}</p>
          </section>
          <section v-if="imageList(detail).length" class="content-search__evidence">
            <h3>图片证据</h3>
            <div class="content-search__image-grid">
              <el-image
                v-for="image in imageList(detail)"
                :key="image"
                :src="image"
                :preview-src-list="imageList(detail)"
                fit="cover"
              />
            </div>
          </section>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script>
import { getComment, listComment } from '@/api/business/comment'
import { getSquare, listSquare } from '@/api/business/square'
import CyDataTable from '@/components/CyDataTable'
import CyEntityHeader from '@/components/CyEntityHeader'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

const CONTENT_TYPES = ['SQUARE', 'COMMENT']

export default {
  name: 'ContentSearch',
  components: { CyDataTable, CyEntityHeader, CyFilterBar, CyPageHeader, CyResourceState },
  dicts: ['sys_business_status', 'sys_square_type', 'sys_merchant_status'],
  props: {
    defaultType: {
      type: String,
      default: 'SQUARE',
      validator: value => CONTENT_TYPES.includes(value)
    }
  },
  data() {
    return {
      loading: false,
      loadError: false,
      detailLoading: false,
      detailError: false,
      detailOpen: false,
      total: 0,
      contentList: [],
      detail: null,
      detailSource: null,
      listRequestId: 0,
      detailRequestId: 0,
      contentType: this.defaultType,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        memberId: null,
        checkStatus: null,
        ownerType: null,
        ownerId: null
      }
    }
  },
  computed: {
    tableColumns() {
      return [
        { key: 'subject', label: '内容', minWidth: 230 },
        { key: 'member', prop: 'memberId', label: '发布人', width: 110, formatter: row => row.memberId ? `#${row.memberId}` : '—' },
        { key: 'context', label: this.contentType === 'SQUARE' ? '发布类型' : '所属对象', minWidth: 130 },
        { key: 'media', label: '图片', width: 110 },
        { key: 'metrics', label: this.contentType === 'SQUARE' ? '互动' : '评分', width: 130 },
        { key: 'status', label: '状态', minWidth: 120 },
        { key: 'actions', label: '调证', width: 190, fixed: 'right', align: 'right', locked: true }
      ]
    },
    activeFilters() {
      return [
        this.queryParams.memberId && { key: 'memberId', label: '发布人', value: this.queryParams.memberId },
        this.contentType === 'SQUARE' && this.queryParams.checkStatus !== null && { key: 'checkStatus', label: '查阅状态', value: this.checkStatusLabel(this.queryParams.checkStatus) },
        this.contentType === 'COMMENT' && this.queryParams.ownerType && { key: 'ownerType', label: '所属类型', value: this.ownerTypeLabel(this.queryParams.ownerType) },
        this.contentType === 'COMMENT' && this.queryParams.ownerId && { key: 'ownerId', label: '所属对象', value: this.queryParams.ownerId }
      ].filter(Boolean)
    },
    emptyText() {
      return this.contentType === 'SQUARE' ? '当前条件下没有帖文' : '当前条件下没有评论'
    },
    detailFacts() {
      if (!this.detail) return []
      const facts = [
        { label: '发布人', value: this.detail.memberId ? `#${this.detail.memberId}` : '—' },
        { label: '内容编号', value: `#${this.detail.id}` }
      ]
      if (this.detailSource === 'COMMENT') {
        facts.push({ label: '所属对象', value: `${this.ownerTypeLabel(this.detail.ownerType)} #${this.detail.ownerId || '—'}` })
      } else {
        facts.push({ label: '互动', value: `赞 ${this.detail.likeNum || 0} · 踩 ${this.detail.nolikeNum || 0}` })
      }
      return facts
    }
  },
  created() {
    const routeType = this.$route.query.contentType
    this.contentType = CONTENT_TYPES.includes(routeType) ? routeType : this.defaultType
    Object.keys(this.queryParams).forEach(key => {
      if (this.$route.query[key] !== undefined) this.queryParams[key] = this.$route.query[key]
    })
    if (this.contentType === 'SQUARE' && this.$route.query.check_status !== undefined) {
      this.queryParams.checkStatus = this.$route.query.check_status
    }
    this.getList()
  },
  methods: {
    listRequest() {
      return this.contentType === 'SQUARE' ? listSquare : listComment
    },
    detailRequest(contentType = this.contentType) {
      return contentType === 'SQUARE' ? getSquare : getComment
    },
    queryForCurrentType() {
      const query = {
        pageNum: this.queryParams.pageNum,
        pageSize: this.queryParams.pageSize,
        memberId: this.queryParams.memberId
      }
      if (this.contentType === 'SQUARE') {
        query.checkStatus = this.queryParams.checkStatus
      } else {
        query.ownerType = this.queryParams.ownerType
        query.ownerId = this.queryParams.ownerId
      }
      return query
    },
    getList() {
      const requestId = ++this.listRequestId
      const request = this.listRequest()
      const query = this.queryForCurrentType()
      this.loading = true
      this.loadError = false
      return request(query).then(response => {
        if (requestId !== this.listRequestId) return
        this.contentList = response.rows || []
        this.total = response.total || 0
      }).catch(() => {
        if (requestId !== this.listRequestId) return
        this.contentList = []
        this.total = 0
        this.loadError = true
      }).finally(() => {
        if (requestId === this.listRequestId) this.loading = false
      })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.syncQueryToUrl()
      this.getList()
    },
    resetQuery() {
      this.queryParams.memberId = null
      this.queryParams.checkStatus = null
      this.queryParams.ownerType = null
      this.queryParams.ownerId = null
      this.queryParams.pageNum = 1
      this.syncQueryToUrl()
      this.getList()
    },
    clearFilter(key) {
      this.queryParams[key] = null
      this.handleQuery()
    },
    switchContentType() {
      this.queryParams.checkStatus = null
      this.queryParams.ownerType = null
      this.queryParams.ownerId = null
      this.queryParams.pageNum = 1
      this.syncQueryToUrl()
      this.getList()
    },
    syncQueryToUrl() {
      const query = { contentType: this.contentType }
      Object.keys(this.queryParams).forEach(key => {
        const value = this.queryParams[key]
        if (value !== null && value !== '' && value !== undefined) query[key] = value
      })
      this.$router.replace({ path: this.$route.path, query }).catch(() => {})
    },
    openEvidence(row, source = this.contentType) {
      const requestId = ++this.detailRequestId
      this.detailOpen = true
      this.detailLoading = true
      this.detailError = false
      this.detail = row
      this.detailSource = source
      return this.detailRequest(source)(row.id).then(response => {
        if (requestId !== this.detailRequestId) return
        this.detail = response.data || row
      }).catch(() => {
        if (requestId !== this.detailRequestId) return
        this.detailError = true
      }).finally(() => {
        if (requestId === this.detailRequestId) this.detailLoading = false
      })
    },
    retryDetail() {
      if (this.detail) this.openEvidence(this.detail, this.detailSource)
    },
    openAuditQueue() {
      this.$router.push({ name: 'Audit', query: { bizType: this.contentType } })
    },
    openAudit(row, source = this.contentType) {
      this.$router.push({ name: 'Audit', query: { bizType: source, bizId: row.id } })
    },
    contentKindLabel() {
      return this.detailSource === 'COMMENT' ? '评论证据' : '帖文证据'
    },
    ownerTypeLabel(value) {
      return { 1: '主题', 2: '活动', 3: '创意广场', '1': '主题', '2': '活动', '3': '创意广场' }[value] || '未知对象'
    },
    checkStatusLabel(value) {
      return { 0: '未查阅', 1: '已查阅', 2: '已处理', '0': '未查阅', '1': '已查阅', '2': '已处理' }[value] || String(value)
    },
    imageList(row) {
      const source = row && (row.pics || row.imgArr)
      return source ? source.split(';').filter(item => item.trim()) : []
    }
  }
}
</script>

<style lang="scss" scoped>
.content-search__filter { margin-bottom: 20px; }
.content-search__subject { display: block; max-width: 100%; padding: 0; border: 0; background: transparent; color: inherit; text-align: left; cursor: pointer; }
.content-search__subject strong, .content-search__subject span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.content-search__subject strong { color: var(--cy-color-text-primary); font-weight: 600; }
.content-search__subject span { margin-top: 4px; color: var(--cy-color-text-tertiary); font-size: 12px; }
.content-search__subject:focus-visible { outline: 3px solid var(--cy-color-focus); outline-offset: 3px; }
.content-search__media { position: relative; display: inline-flex; width: 72px; height: 48px; overflow: hidden; border-radius: var(--cy-radius-control); background: var(--cy-color-bg-subtle); }
.content-search__media .el-image { width: 100%; height: 100%; }
.content-search__media > span { position: absolute; right: 4px; bottom: 4px; padding: 1px var(--cy-legacy-5); border-radius: 10px; background: var(--cy-color-action-primary); color: var(--cy-color-text-inverse); font-size: 11px; }
.content-search__image-error { display: grid; width: 100%; height: 100%; place-items: center; color: var(--cy-color-text-tertiary); }
.content-search__secondary-status { display: block; margin-top: 4px; }
.content-search__detail { min-height: 260px; padding: 0 24px var(--cy-space-7); }
.content-search__evidence { padding: 20px 0; border-bottom: 1px solid var(--cy-color-border-subtle); }
.content-search__evidence h3 { margin: 0 0 var(--cy-space-1-5); color: var(--cy-color-text-primary); font-size: 14px; }
.content-search__evidence p { margin: 0; color: var(--cy-color-text-secondary); line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.content-search__image-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: var(--cy-space-1-5); }
.content-search__image-grid .el-image { width: 100%; height: 100px; border-radius: var(--cy-radius-control); background: var(--cy-color-bg-subtle); }
</style>
