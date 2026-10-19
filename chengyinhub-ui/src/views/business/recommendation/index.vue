<template>
  <div class="app-container">
    <cy-page-header title="推荐评分查询" description="查看已记录的评分与生成日期；本页不编辑推荐规则。" />
    <el-alert title="生产批次、规则版本、有效期与线上采用状态尚未提供，均待确认；有评分记录不代表正在使用。" type="info" :closable="false" show-icon />
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" label-width="80px">
      <el-form-item label="内容类型" prop="candidateType">
        <el-select v-model="queryParams.candidateType" placeholder="选择类型" clearable>
          <el-option label="主题" value="topic" />
          <el-option label="活动" value="activity" />
        </el-select>
      </el-form-item>
      <el-form-item label="数量" prop="pageSize">
        <el-input-number v-model="queryParams.pageSize" :min="1" :max="50" size="small" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <cy-resource-state v-if="loadError" kind="error" title="评分暂时无法读取" description="请重新读取；若仍失败，请核对查看权限。" retry-text="重新读取" :retrying="loading" @retry="getList" />
    <el-table v-else v-loading="loading" :data="scoreList" empty-text="暂无评分记录，生产状态待确认">
      <el-table-column label="评分日期" prop="statDate" min-width="160" />
      <el-table-column label="记录时间" prop="createTime" min-width="160" />
      <el-table-column label="用户编号" prop="memberId" width="110" />
      <el-table-column label="排序" prop="rankPosition" width="80" />
      <el-table-column label="内容类型" align="center" prop="candidateType" width="120" />
      <el-table-column label="内容ID" align="center" prop="candidateId" width="100" />
      <el-table-column label="评分" align="center" prop="score" width="120" />
      <el-table-column label="推荐原因" align="center" prop="reasonCodes" min-width="180" show-overflow-tooltip />
    </el-table>
    <pagination v-show="!loadError && total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />
    <local-author-trial />
  </div>
</template>

<script>
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import LocalAuthorTrial from './local-author-trial.vue'
import { listRecommendationScores } from '@/api/business/recommendation'

export default {
  name: 'BusinessRecommendation',
  components: { CyPageHeader, CyResourceState, LocalAuthorTrial },
  data() {
    return {
      loading: false,
      loadError: false,
      requestToken: 0,
      total: 0,
      scoreList: [],
      queryParams: {
        candidateType: 'topic',
        pageNum: 1,
        pageSize: 20
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
      return listRecommendationScores(this.queryParams).then(response => {
        if (requestToken !== this.requestToken) return
        if (!Array.isArray(response.rows)) throw new Error('评分数据不完整')
        this.scoreList = response.rows
        this.total = response.total
      }).catch(() => {
        if (requestToken !== this.requestToken) return
        this.scoreList = []
        this.total = 0
        this.loadError = true
      }).finally(() => {
        if (requestToken === this.requestToken) this.loading = false
      })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.queryParams.pageSize = 20
      this.handleQuery()
    }
  }
}
</script>
