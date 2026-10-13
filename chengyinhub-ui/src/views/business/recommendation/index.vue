<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" label-width="80px">
      <el-form-item label="内容类型" prop="candidateType">
        <el-select v-model="queryParams.candidateType" placeholder="选择类型" clearable>
          <el-option label="主题" value="topic" />
          <el-option label="活动" value="activity" />
        </el-select>
      </el-form-item>
      <el-form-item label="数量" prop="limit">
        <el-input-number v-model="queryParams.limit" :min="1" :max="50" size="small" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="scoreList" empty-text="暂无推荐评分">
      <el-table-column label="内容类型" align="center" prop="candidateType" width="120" />
      <el-table-column label="内容ID" align="center" prop="candidateId" width="100" />
      <el-table-column label="评分" align="center" prop="score" width="120" />
      <el-table-column label="推荐原因" align="center" prop="reasonCodes" min-width="180" show-overflow-tooltip />
    </el-table>
  </div>
</template>

<script>
import { listRecommendationScores } from '@/api/business/recommendation'

export default {
  name: 'BusinessRecommendation',
  data() {
    return {
      loading: false,
      scoreList: [],
      queryParams: {
        candidateType: 'topic',
        limit: 20
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listRecommendationScores(this.queryParams).then(response => {
        this.scoreList = response.rows || []
      }).finally(() => {
        this.loading = false
      })
    },
    handleQuery() {
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.queryParams.limit = 20
      this.handleQuery()
    }
  }
}
</script>
