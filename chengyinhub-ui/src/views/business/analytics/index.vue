<template>
  <div class="app-container">
    <cy-page-header title="业务行为计数" description="按日展示用户数和事件次数，不等同于订单、会话或实际到账。" />
    <el-alert title="按日、业务类型和城市分组；人数按用户去重，后续步骤要求不早于当日开始行为。未关联原订单或会话，跨日归因与退款回改口径待定。" type="info" :closable="false" show-icon />
    <p>生成作业状态、来源版本与有效期：待确认。无记录不代表业务为零，支付事件不代表渠道到账。</p>
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" label-width="80px">
      <el-form-item label="开始日期" prop="startDate">
        <el-date-picker
          v-model="queryParams.startDate"
          type="date"
          value-format="yyyy-MM-dd"
          placeholder="选择开始日期"
          clearable
        />
      </el-form-item>
      <el-form-item label="结束日期" prop="endDate">
        <el-date-picker
          v-model="queryParams.endDate"
          type="date"
          value-format="yyyy-MM-dd"
          placeholder="选择结束日期"
          clearable
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <cy-resource-state v-if="loadError" kind="error" title="行为计数暂时无法读取" description="请重新读取；若仍失败，请核对查看权限。" retry-text="重新读取" :retrying="loading" @retry="getList" />
    <el-table v-else v-loading="loading" :data="funnelList" empty-text="暂无聚合记录，生成作业状态待确认">
      <el-table-column label="日期" align="center" prop="statDate" width="130" />
      <el-table-column label="事件" align="center" prop="stepName" min-width="160" />
      <el-table-column label="业务类型" align="center" prop="bizType" width="120" />
      <el-table-column label="城市" align="center" prop="cityCode" width="120" />
      <el-table-column label="人数" align="center" prop="memberCount" width="120" />
      <el-table-column label="次数" align="center" prop="eventCount" width="120" />
      <el-table-column label="记录生成时间" prop="createTime" min-width="170" />
    </el-table>
  </div>
</template>

<script>
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import { listFunnel } from '@/api/business/analytics'

export default {
  name: 'BusinessAnalytics',
  components: { CyPageHeader, CyResourceState },
  data() {
    return {
      loading: false,
      loadError: false,
      requestToken: 0,
      funnelList: [],
      queryParams: {
        startDate: null,
        endDate: null
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      const requestToken = ++this.requestToken
      this.loading = true
      this.loadError = false
      return listFunnel(this.queryParams).then(response => {
        if (requestToken !== this.requestToken) return
        if (!response.data || !Array.isArray(response.data.rows)) throw new Error('统计数据不完整')
        this.funnelList = response.data.rows
      }).catch(() => {
        if (requestToken !== this.requestToken) return
        this.funnelList = []
        this.loadError = true
      }).finally(() => {
        if (requestToken === this.requestToken) this.loading = false
      })
    },
    handleQuery() {
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.handleQuery()
    }
  }
}
</script>
