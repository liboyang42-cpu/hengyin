<template>
  <el-drawer :title="`${memberId} -- 余额变动明细`" size="75%" :visible.sync="visible">
    <div class="app-container">
      <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
        <el-form-item label="变化金额" prop="changeBalance">
          <el-input v-model="queryParams.changeBalance" placeholder="精确变化金额" clearable @keyup.enter.native="handleQuery" />
        </el-form-item>
        <el-form-item label="事件 ID" prop="eventId">
          <el-input v-model="queryParams.eventId" placeholder="报名、提现等事件 ID" clearable @keyup.enter.native="handleQuery" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
          <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-row :gutter="10" class="mb8">
        <right-toolbar :showSearch.sync="showSearch" @queryTable="getList" />
      </el-row>

      <el-alert
        v-if="readError"
        title="余额流水暂时无法读取"
        description="读取失败不会显示成空流水，请稍后重试。"
        type="error"
        show-icon
        class="mb8"
      />
      <el-table v-loading="loading" :data="memberbalancedetailList">
        <el-table-column label="ID" align="center" prop="id" />
        <el-table-column label="用户 ID" align="center" prop="memberId" />
        <el-table-column label="变化前余额" align="center" prop="currentBalance" />
        <el-table-column label="变化后余额" align="center" prop="afterBalance" />
        <el-table-column label="变化金额" align="center" prop="changeBalance" />
        <el-table-column label="变化原因" align="center" prop="changeReason" />
        <el-table-column label="对应事件 ID" align="center" prop="eventId" />
        <el-table-column label="变化类型" align="center">
          <template slot-scope="scope">
            <span :style="getChangeTypeStyle(scope.row.changeType)">{{ getChangeTypeName(scope.row.changeType) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="变化时间" align="center" prop="createTime" width="180" />
      </el-table>

      <pagination
        v-show="total > 0"
        :total="total"
        :page.sync="queryParams.pageNum"
        :limit.sync="queryParams.pageSize"
        @pagination="getList"
      />
    </div>
  </el-drawer>
</template>

<script>
import { listMemberbalancedetail } from "@/api/business/memberbalancedetail"

const createQueryParams = memberId => ({
  pageNum: 1,
  pageSize: 10,
  memberId: memberId || null,
  changeBalance: null,
  eventId: null
})

export default {
  name: 'MemberBalanceDetail',
  data() {
    return {
      memberId: null,
      visible: false,
      loading: false,
      readError: false,
      requestId: 0,
      showSearch: true,
      total: 0,
      memberbalancedetailList: [],
      queryParams: createQueryParams(null)
    }
  },
  methods: {
    init(memberId) {
      this.memberId = memberId
      this.queryParams = createQueryParams(memberId)
      this.memberbalancedetailList = []
      this.total = 0
      this.readError = false
      this.visible = true
      return this.getList()
    },
    getChangeTypeName(type) {
      return { 1: '收入', 2: '支出', '1': '收入', '2': '支出' }[type] || '未知类型'
    },
    getChangeTypeStyle(type) {
      return { 1: { color: '#27ae60', fontWeight: 'bold' }, 2: { color: '#e74c3c' }, '1': { color: '#27ae60', fontWeight: 'bold' }, '2': { color: '#e74c3c' } }[type] || { color: '#999' }
    },
    getList() {
      if (!this.memberId) return Promise.resolve()
      const requestId = ++this.requestId
      const query = Object.assign({}, this.queryParams, { memberId: this.memberId })
      this.loading = true
      this.readError = false
      return listMemberbalancedetail(query).then(response => {
        if (requestId !== this.requestId) return
        this.memberbalancedetailList = response.rows || []
        this.total = response.total || 0
      }).catch(() => {
        if (requestId !== this.requestId) return
        this.memberbalancedetailList = []
        this.total = 0
        this.readError = true
      }).finally(() => {
        if (requestId === this.requestId) this.loading = false
      })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.queryParams.memberId = this.memberId
      return this.getList()
    },
    resetQuery() {
      this.queryParams = createQueryParams(this.memberId)
      return this.getList()
    }
  }
}
</script>
