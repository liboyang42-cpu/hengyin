<template>
  <div class="app-container">
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

    <el-table v-loading="loading" :data="funnelList" empty-text="暂无漏斗数据">
      <el-table-column label="日期" align="center" prop="statDate" width="130" />
      <el-table-column label="事件" align="center" prop="stepName" min-width="160" />
      <el-table-column label="业务类型" align="center" prop="bizType" width="120" />
      <el-table-column label="城市" align="center" prop="cityCode" width="120" />
      <el-table-column label="人数" align="center" prop="memberCount" width="120" />
      <el-table-column label="次数" align="center" prop="eventCount" width="120" />
      <el-table-column label="转化率" align="center" prop="conversionRate" width="120" />
    </el-table>
  </div>
</template>

<script>
import { listFunnel } from '@/api/business/analytics'

export default {
  name: 'BusinessAnalytics',
  data() {
    return {
      loading: false,
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
      this.loading = true
      listFunnel(this.queryParams).then(response => {
        const data = response.data || {}
        this.funnelList = data.rows || []
      }).finally(() => {
        this.loading = false
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
