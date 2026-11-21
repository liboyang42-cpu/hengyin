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

    <el-divider content-position="left">登录前首会话（按安装去重）</el-divider>
    <el-table v-loading="preSignInLoading" :data="preSignInSteps" empty-text="暂无登录前数据">
      <el-table-column label="顺序" align="center" prop="stepOrder" width="80" />
      <el-table-column label="步骤" align="center" min-width="180">
        <template slot-scope="scope">{{ stepLabel(scope.row.stepName) }}</template>
      </el-table-column>
      <el-table-column label="安装数" align="center" prop="installCount" width="120" />
      <el-table-column label="次数" align="center" prop="eventCount" width="120" />
    </el-table>
    <div class="pre-sign-in-note">
      <span>窗口内完成账号关联的安装：{{ linkedInstallCount }}</span>
      <span v-if="preSignInDisabled">（服务端已关闭采集，数字不再增长）</span>
    </div>

    <el-divider content-position="left">登录后漏斗</el-divider>
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
import { listFunnel, getPreSignInFunnel } from '@/api/business/analytics'

const PRE_SIGN_IN_LABELS = {
  app_open: '打开 App',
  onboarding_step: '新手引导步骤',
  permission_prompt: '权限弹窗',
  permission_result: '权限结果',
  sign_in_start: '开始登录',
  sign_in_success: '登录成功',
  sign_in_failure: '登录失败',
  first_walk: '首次行走',
  first_fog_reveal: '首次点亮迷雾',
  first_reward: '首次获得奖励',
  first_share: '首次分享'
}

export default {
  name: 'BusinessAnalytics',
  data() {
    return {
      loading: false,
      funnelList: [],
      preSignInLoading: false,
      preSignInSteps: [],
      linkedInstallCount: 0,
      preSignInDisabled: false,
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
    stepLabel(name) {
      return PRE_SIGN_IN_LABELS[name] || name
    },
    getPreSignIn() {
      this.preSignInLoading = true
      getPreSignInFunnel(this.queryParams).then(response => {
        const data = response.data || {}
        this.preSignInSteps = data.steps || []
        this.linkedInstallCount = data.linkedInstallCount || 0
        this.preSignInDisabled = data.collectionEnabled === false
      }).finally(() => {
        this.preSignInLoading = false
      })
    },
    getList() {
      this.getPreSignIn()
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

<style scoped>
.pre-sign-in-note {
  margin: 8px 0 16px;
  font-size: 12px;
  color: #909399;
}
</style>
