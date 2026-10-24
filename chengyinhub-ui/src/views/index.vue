<template>
  <main class="app-container workbench">
    <cy-page-header
      eyebrow="城市运营控制台"
      title="今天从这里开始"
      description="常用功能按角色与使用频率靠前；待审数字全部来自真实接口，点击直接进入已筛选的业务页面。"
      :status="loading ? '同步中' : '实时队列'"
      :updated-at="updatedAt"
    >
      <el-button icon="el-icon-refresh" v-if="canReadSummary" :loading="loading" @click="getHomeData">刷新队列</el-button>
      <el-button v-if="hasPermission('business:audit:list')" slot="primary" type="primary" @click="goAuditWorkbench">进入审核台</el-button>
    </cy-page-header>

    <section class="workbench__quick" aria-labelledby="quick-actions-title">
      <div class="workbench__section-heading">
        <div>
          <span>高频入口</span>
          <h2 id="quick-actions-title">常用功能</h2>
          <p>依据当前账号权限展示；使用越多的入口会自动排到前面。</p>
        </div>
        <span class="workbench__role">{{ currentRole }}</span>
      </div>
      <div class="workbench__quick-grid">
        <button v-for="action in quickActions" :key="action.label" type="button" @click="openQuickAction(action)">
          <span class="workbench__quick-icon" aria-hidden="true"><i :class="action.icon" /></span>
          <span><strong>{{ action.label }}</strong><small>{{ action.description }}</small></span>
          <i class="el-icon-right workbench__quick-arrow" aria-hidden="true" />
        </button>
      </div>
    </section>

    <section class="workbench__pulse" aria-label="实时待审摘要">
      <button v-for="item in visibleReviewTasks" :key="item.key" type="button" @click="openTask(item)">
        <span>{{ item.type }}</span>
        <strong>{{ item.count }}</strong>
        <small>待处理 <i class="el-icon-right" /></small>
      </button>
    </section>

    <div class="workbench__grid">
      <cy-task-queue
        title="城市任务流"
        description="按业务风险组织，不再按数据库表名排列。"
        :items="visibleReviewTasks"
        :loading="loading"
        :error="error"
        :retryable="canReadSummary"
        @select="openTask"
        @retry="getHomeData"
      />

      <aside class="workbench__rail" aria-label="值守与数据接通状态">
        <div class="workbench__section-heading">
          <div>
            <span>值守视图</span>
            <h2>数据接通状态</h2>
          </div>
          <span class="workbench__source">实时</span>
        </div>
        <div class="workbench__metrics">
          <!-- 「已接通」以本次请求结果为准：无权或读取失败时不能同时显示已接通 -->
          <cy-metric-card
            label="待审队列"
            :value="`${visibleReviewTasks.length} 类`"
            description="商家、帖文、主题、活动和模板待审计数。"
            :connected="summaryLoaded"
          />
          <cy-metric-card
            label="SLA 与负责人"
            description="现有接口尚未返回领取人、等待时长和超时阈值。"
          />
          <cy-metric-card
            label="异常与业务健康"
            description="待接合作未确认、核销冲突、退款异常和任务停用口径。"
          />
        </div>

        <div class="workbench__notice">
          <span class="workbench__notice-mark">i</span>
          <div>
            <strong>为什么显示“尚未接通”</strong>
            <p>后台只展示能回读的数据。没有数据源的告警不会用假数字制造安全感。</p>
          </div>
        </div>
      </aside>
    </div>
  </main>
</template>

<script>
import { getHomeData } from '@/api/home'
import CyPageHeader from '@/components/CyPageHeader'
import CyTaskQueue from '@/components/CyTaskQueue'
import CyMetricCard from '@/components/CyMetricCard'

export default {
  name: 'Index',
  components: { CyPageHeader, CyTaskQueue, CyMetricCard },
  data() {
    return {
      record: {},
      loading: false,
      error: '',
      updatedAt: '',
      summaryLoaded: false,
      actionUsage: {}
    }
  },
  computed: {
    reviewTasks() {
      const task = (key, type, field, description, permission, route) => ({
        key,
        type,
        count: Number(this.record[field] || 0),
        description,
        source: '实时待审队列',
        permission,
        route
      })
      return [
        // 「只看待审」的 query 参数名按目标页各自的实体字段给，不共用一个写法：
        // mms_merchant 没有 check_status 列，待审计数用的就是 status=0
        //（UmsMemberMapper.xml merchantAuditNum: del_flag=0 AND `status`=0，
        //  MmsMerchantMapper.xml 的列表条件也只认 status）。
        task('merchant', '商家准入', 'merchantAuditNum', '核对主体资料与经营资格', 'business:merchant:list', { name: 'Merchant', query: { status: 0 } }),
        task('square', '帖文审核', 'creativeSquareNum', '处理发布内容与举报风险', 'business:square:list', { name: 'Square', query: { check_status: 0 } }),
        // scope 必带:topic/index.vue:583 从 URL 取 scope，缺了会落到「无来源」的全量视图
        // —— 那是任何侧栏入口都产生不出的状态（2007 带 scope=official、2237 带 plaza），
        // 且 isOfficialEntry() 为假会藏掉新增与批量选择（:166 / :191）。
        task('topic', '主题审核', 'topicNum', '核对主题资料、档期与发布范围', 'business:topic:list', { name: 'OfficialTopic', query: { check_status: 0, scope: 'official' } }),
        task('activity', '活动审核', 'activityNum', '核对活动时间、地点与报名信息', 'business:activity:list', { path: '/business/activity', query: { check_status: 0 } }),
        task('template', '模板审核', 'memberTemplateNum', '核对模板内容与可用范围', 'business:membertemplate:list', { path: '/business/membertemplate', query: { check_status: 0 } })
      ]
    },
    // /business/home/data 要求 business:analytics:list（HomeController）。
    canReadSummary() {
      return this.hasPermission('business:analytics:list')
    },
    // 无权限的角色不该看到点不动的卡片：跳转按 route name 走之后，
    // 未授权账号那条动态路由根本不会注册，$router.push 抛的错又被
    // router/index.js 末尾的 .catch(err => err) 吞掉 ⇒ 点了零反馈零报错。
    // 所以这里跟 quickActions 用同一套 hasPermission 过滤。
    visibleReviewTasks() {
      return this.reviewTasks.filter(task => this.hasPermission(task.permission))
    },
    totalPending() {
      return this.visibleReviewTasks.reduce((sum, item) => sum + item.count, 0)
    },
    quickActions() {
      const actions = [
        { label: '统一审核台', description: '集中处理内容与供给审核', route: { name: 'Audit' }, permission: 'business:audit:list', icon: 'el-icon-finished', priority: 1 },
        { label: '主题管理', description: '选择主题后查看档期与发布状态', route: { name: 'OfficialTopic', query: { scope: 'official' } }, permission: 'business:topic:list', icon: 'el-icon-map-location', priority: 2 },
        { label: '活动管理', description: '处理活动与报名信息', route: { path: '/business/activity' }, permission: 'business:activity:list', icon: 'el-icon-date', priority: 3 },
        { label: '商家准入', description: '核对主体与经营资料', route: { name: 'Merchant' }, permission: 'business:merchant:list', icon: 'el-icon-office-building', priority: 4 },
        { label: '用户调证', description: '查询用户、积分与余额明细', route: { path: '/business/member' }, permission: 'business:member:list', icon: 'el-icon-user', priority: 5 },
        { label: '订单查询', description: '定位订单与履约状态', route: { path: '/business/order' }, permission: 'business:order:list', icon: 'el-icon-tickets', priority: 6 },
        { label: '提现审核', description: '复核收款资料与处理结果', route: { path: '/business/memberwithdrawal' }, permission: 'business:memberwithdrawal:list', icon: 'el-icon-bank-card', priority: 7 },
        { label: '出金队列', description: '查看通道出金与人工回补', route: { path: '/finance/withdraw/wechat' }, permission: 'business:cashwithdraw:list', icon: 'el-icon-wallet', priority: 8 }
      ]
      return actions
        .filter(action => this.hasPermission(action.permission))
        .sort((a, b) => (this.actionUsage[b.label] || 0) - (this.actionUsage[a.label] || 0) || a.priority - b.priority)
        .slice(0, 6)
    },
    currentRole() {
      const roles = (this.$store && this.$store.state.user.roles) || []
      if (roles.includes('admin')) return '系统管理员'
      if (roles.includes('cy_finance')) return '财务结算'
      if (roles.includes('cy_auditor')) return '审核风控'
      if (roles.includes('cy_support')) return '客服售后'
      if (roles.includes('cy_operator')) return '日常运营'
      return '当前账号'
    }
  },
  mounted() {
    this.restoreActionUsage()
    this.getHomeData()
  },
  methods: {
    getHomeData() {
      if (!this.canReadSummary) {
        // 无 business:analytics:list 不请求 /business/home/data，避免每次登录都弹「没有权限」。
        this.summaryLoaded = false
        this.error = '当前账号无权查看待审摘要，请从左侧有权限的业务入口进入。'
        return Promise.resolve()
      }
      this.loading = true
      this.error = ''
      return getHomeData()
        .then(response => {
          this.record = response.data || {}
          this.summaryLoaded = true
          this.updatedAt = this.parseTime(new Date(), '{y}-{m}-{d} {h}:{i}')
        })
        .catch(() => {
          // 拦截器对业务错误码（含 403）与网络错误都 reject，这里不能一律归因为网络。
          this.summaryLoaded = false
          this.error = '待审摘要读取失败，请稍后重试；如持续失败，请联系管理员核对账号权限或服务状态。'
        })
        .finally(() => {
          this.loading = false
        })
    },
    openTask(item) {
      this.$router.push(item.route)
    },
    hasPermission(permission) {
      const permissions = (this.$store && this.$store.state.user.permissions) || []
      return permissions.includes('*:*:*') || permissions.includes(permission)
    },
    restoreActionUsage() {
      try {
        this.actionUsage = JSON.parse(localStorage.getItem('cy-web-quick-action-usage') || '{}')
      } catch (error) {
        this.actionUsage = {}
      }
    },
    openQuickAction(action) {
      const usage = { ...this.actionUsage, [action.label]: (this.actionUsage[action.label] || 0) + 1 }
      this.actionUsage = usage
      localStorage.setItem('cy-web-quick-action-usage', JSON.stringify(usage))
      this.$router.push(action.route)
    },
    goAuditWorkbench() {
      if (!this.hasPermission('business:audit:list')) return
      this.$router.push({ name: 'Audit' })
    }
  }
}
</script>

<style lang="scss" scoped>
.workbench {
  max-width: 1680px;
  margin: 0 auto;
}

.workbench__quick {
  margin-bottom: 20px;
  padding: var(--cy-space-4-5);
  border: 1px solid var(--cy-color-border-subtle);
  border-radius: var(--cy-radius-card);
  background: var(--cy-color-bg-surface);
  box-shadow: var(--cy-shadow-card);
}

.workbench__quick-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: var(--cy-space-1-5); }
.workbench__quick-grid button { position: relative; display: flex; align-items: center; gap: 12px; min-height: 74px; padding: 13px; border: 1px solid var(--cy-color-border-subtle); border-radius: 14px; background: var(--cy-color-bg-subtle); color: inherit; font: inherit; text-align: left; cursor: pointer; transition: border-color .2s ease, background-color .2s ease, box-shadow .2s ease; }
.workbench__quick-grid button:hover { border-color: #D8D0FF; background: var(--cy-color-brand-soft); box-shadow: 0 8px 18px rgba(106, 74, 240, .08); }
.workbench__quick-grid button > span:nth-child(2) { min-width: 0; }
.workbench__quick-grid strong { display: block; color: var(--cy-color-text-primary); font-size: 13px; }
.workbench__quick-grid small { display: block; margin-top: var(--cy-legacy-5); overflow: hidden; color: var(--cy-color-text-secondary); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.workbench__quick-icon { display: inline-flex; flex: none; align-items: center; justify-content: center; width: 38px; height: 38px; border-radius: 12px; background: #fff; color: var(--cy-color-brand); font-size: 18px; box-shadow: 0 4px 12px rgba(50, 43, 87, .07); }
.workbench__quick-arrow { margin-left: auto; color: var(--cy-color-text-tertiary); }
.workbench__role { padding: var(--cy-space-0-5) var(--cy-space-1-5); border-radius: 999px; background: var(--cy-color-brand-soft); color: var(--cy-color-brand) !important; letter-spacing: 0 !important; }

.workbench__pulse { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; margin-bottom: 20px; }
.workbench__pulse button { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; min-height: 116px; padding: var(--cy-space-3-5); border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); color: inherit; font: inherit; text-align: left; cursor: pointer; box-shadow: var(--cy-shadow-card); }
.workbench__pulse button:hover { border-color: #D8D0FF; }
.workbench__pulse span { grid-column: 1 / -1; align-self: start; color: var(--cy-color-text-secondary); font-size: 13px; font-weight: 700; }
.workbench__pulse strong { color: var(--cy-color-text-primary); font-size: 30px; font-variant-numeric: tabular-nums; }
.workbench__pulse small { color: var(--cy-color-brand); font-size: 12px; font-weight: 700; }

.workbench__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(300px, .8fr);
  align-items: start;
  gap: 20px;
}

.workbench__rail {
  padding: 20px;
  border: 1px solid var(--cy-color-border-subtle);
  border-radius: var(--cy-radius-card);
  background: var(--cy-color-bg-surface);
  box-shadow: var(--cy-shadow-card);
}

.workbench__section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.workbench__section-heading span { color: var(--cy-color-brand); font-size: 12px; font-weight: 800; letter-spacing: .08em; }
.workbench__section-heading h2 { margin: var(--cy-legacy-5) 0 0; color: var(--cy-color-text-primary); font-size: 18px; }
.workbench__section-heading p { margin: var(--cy-legacy-5) 0 0; color: var(--cy-color-text-secondary); font-size: 12px; }
.workbench__section-heading .workbench__source { padding: 4px 8px; border-radius: 999px; background: var(--cy-color-status-success-soft); color: var(--cy-color-status-success); letter-spacing: 0; }
.workbench__metrics { display: grid; gap: var(--cy-space-1-5); }

.workbench__notice {
  display: flex;
  gap: 12px;
  margin-top: 16px;
  padding: var(--cy-space-2-5);
  border-radius: 6px;
  background: var(--cy-color-bg-subtle);
}
.workbench__notice-mark { display: inline-flex; flex: none; align-items: center; justify-content: center; width: var(--cy-space-4-5); height: var(--cy-space-4-5); border-radius: 50%; background: var(--cy-color-text-primary); color: #fff; font-size: 12px; font-weight: 800; }
.workbench__notice strong { color: var(--cy-color-text-primary); font-size: 13px; }
.workbench__notice p { margin: 4px 0 0; color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.55; }

@media (max-width: 1320px) {
  .workbench__quick-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}

@media (max-width: 1100px) {
  .workbench__quick-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .workbench__pulse { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .workbench__grid { grid-template-columns: 1fr; }
}

@media (max-width: 720px) {
  .workbench__quick-grid, .workbench__pulse { grid-template-columns: 1fr; }
}
</style>
