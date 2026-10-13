<template>
  <div class="app-container cy-domain-page member-growth-detail">
    <cy-page-header
      eyebrow="用户与客服 / 用户调证"
      title="会员成长档案"
      description="只读查看当前用户的成长等级、经验、任务进度与勋章；查看不会创建成长账户或发放奖励。"
    >
      <el-button icon="el-icon-back" @click="backToMember">返回用户调证</el-button>
    </cy-page-header>

    <cy-resource-state
      v-if="detailError"
      kind="error"
      :title="detailErrorTitle"
      :description="detailErrorMessage"
      retry-text="重新读取"
      :retrying="loading"
      @retry="loadDetail"
    />

    <template v-else-if="center">
      <cy-entity-header
        eyebrow="用户 360 子视图"
        :title="`用户 #${memberId} 的成长档案`"
        :status="accountStatus"
        :facts="growthFacts"
        class="member-growth-detail__header"
      />

      <section class="member-growth-detail__metrics" aria-label="成长概览">
        <article class="member-growth-detail__metric">
          <span>成长等级</span>
          <strong>Lv.{{ growth.levelNo || 1 }}</strong>
          <p>经验达到每 100 点自动升级</p>
        </article>
        <article class="member-growth-detail__metric">
          <span>累计经验</span>
          <strong>{{ growth.expValue || 0 }}</strong>
          <p>仅统计成长经验，不等同于可抵现积分</p>
        </article>
        <article class="member-growth-detail__metric">
          <span>统一积分余额</span>
          <strong>{{ center.points || 0 }}</strong>
          <p>积分明细仍在用户 360 的积分子页查看</p>
        </article>
        <article class="member-growth-detail__metric">
          <span>已获得勋章</span>
          <strong>{{ badges.length }}</strong>
          <p>按解锁时间倒序展示</p>
        </article>
      </section>

      <section class="cy-panel member-growth-detail__section">
        <div class="cy-panel__heading">
          <div>
            <h2>当前成长任务</h2>
            <p>任务配置来自成长任务中心；本页只读，不直接补发经验。</p>
          </div>
        </div>
        <cy-empty-state
          v-if="missions.length === 0"
          title="当前没有启用的成长任务"
          description="请由有配置权限的运营人员在成长任务配置页启用任务。"
        />
        <el-table v-else :data="missions" size="small">
          <el-table-column label="任务" min-width="180">
            <template slot-scope="scope">
              <strong>{{ scope.row.missionName || scope.row.missionCode }}</strong>
              <span class="member-growth-detail__secondary">{{ scope.row.missionCode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="触发事件" prop="triggerEvent" min-width="150" show-overflow-tooltip />
          <el-table-column label="经验奖励" width="100" align="center">
            <template slot-scope="scope">+{{ scope.row.expReward || 0 }}</template>
          </el-table-column>
          <el-table-column label="说明" min-width="220" prop="missionDesc" show-overflow-tooltip />
        </el-table>
      </section>

      <section class="cy-panel member-growth-detail__section">
        <div class="cy-panel__heading">
          <div>
            <h2>最近任务进度</h2>
            <p>仅展示服务端已记录的成长奖励进度，最多保留最近 100 条。</p>
          </div>
        </div>
        <cy-empty-state
          v-if="progress.length === 0"
          title="尚无成长任务进度"
          description="用户完成真实业务事件后，系统才会记录对应进度。"
        />
        <el-table v-else :data="progress" size="small">
          <el-table-column label="完成日期" width="128">
            <template slot-scope="scope">{{ formatDate(scope.row.progressDate) }}</template>
          </el-table-column>
          <el-table-column label="任务编码" prop="missionCode" min-width="160" />
          <el-table-column label="获得经验" width="112" align="center">
            <template slot-scope="scope">+{{ scope.row.rewardExp || 0 }}</template>
          </el-table-column>
          <el-table-column label="完成次数" width="100" align="center" prop="completeCount" />
          <el-table-column label="业务来源" min-width="160">
            <template slot-scope="scope">{{ sourceLabel(scope.row) }}</template>
          </el-table-column>
        </el-table>
      </section>

      <section class="cy-panel member-growth-detail__section">
        <div class="cy-panel__heading">
          <div>
            <h2>已获得勋章</h2>
            <p>勋章由已完成的真实业务行为解锁，本页不提供人工授予。</p>
          </div>
        </div>
        <cy-empty-state
          v-if="badges.length === 0"
          title="尚未获得勋章"
          description="当用户满足对应成长条件后，系统会自动记录勋章。"
        />
        <div v-else class="member-growth-detail__badges">
          <article v-for="badge in badges" :key="badge.id || `${badge.badgeCode}-${badge.unlockTime}`" class="member-growth-detail__badge">
            <el-image v-if="badge.iconUrl" :src="badge.iconUrl" fit="cover" class="member-growth-detail__badge-icon" />
            <div v-else class="member-growth-detail__badge-icon member-growth-detail__badge-icon--empty"><i class="el-icon-medal" /></div>
            <div>
              <strong>{{ badge.badgeName || badge.badgeCode }}</strong>
              <span>{{ formatDate(badge.unlockTime) }} 解锁</span>
            </div>
          </article>
        </div>
      </section>
    </template>
  </div>
</template>

<script>
import { getMemberGrowthCenter } from '@/api/business/growth'
import CyEmptyState from '@/components/CyEmptyState'
import CyEntityHeader from '@/components/CyEntityHeader'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

export default {
  name: 'MemberGrowthDetail',
  components: { CyEmptyState, CyEntityHeader, CyPageHeader, CyResourceState },
  data() {
    return {
      loading: false,
      detailError: false,
      detailErrorTitle: '会员成长档案暂时无法读取',
      detailErrorMessage: '请检查用户编号、当前权限或网络后重试。',
      requestToken: 0,
      memberId: null,
      center: null
    }
  },
  computed: {
    growth() {
      return (this.center && this.center.growth) || {}
    },
    missions() {
      return (this.center && this.center.missions) || []
    },
    progress() {
      return (this.center && this.center.progress) || []
    },
    badges() {
      return (this.center && this.center.badges) || []
    },
    accountStatus() {
      return this.growth.id ? { label: '已有成长账户', tone: 'success' } : { label: '尚未发放成长经验', tone: 'neutral' }
    },
    growthFacts() {
      return [
        { label: '成长等级', value: `Lv.${this.growth.levelNo || 1}` },
        { label: '累计经验', value: this.growth.expValue || 0 },
        { label: '最近活跃', value: this.formatDate(this.growth.lastActiveTime) },
        { label: '成长账户', value: this.growth.id ? `#${this.growth.id}` : '尚未创建' }
      ]
    }
  },
  watch: {
    '$route.params.memberId'() {
      this.loadDetail()
    }
  },
  created() {
    this.loadDetail()
  },
  methods: {
    loadDetail() {
      const memberId = Number(this.$route.params.memberId)
      if (!Number.isInteger(memberId) || memberId <= 0) {
        this.memberId = null
        this.center = null
        this.detailError = true
        this.detailErrorTitle = '用户编号无效'
        this.detailErrorMessage = '请从用户 360 的关联调证入口进入成长档案。'
        return Promise.resolve()
      }
      const requestToken = ++this.requestToken
      this.memberId = memberId
      this.loading = true
      this.detailError = false
      this.center = null
      return getMemberGrowthCenter(memberId)
        .then(response => {
          if (requestToken !== this.requestToken) return
          this.center = response.data || null
          if (!this.center) throw new Error('empty growth center')
        })
        .catch(() => {
          if (requestToken !== this.requestToken) return
          this.center = null
          this.detailError = true
          this.detailErrorTitle = '会员成长档案暂时无法读取'
          this.detailErrorMessage = '用户不存在，或当前角色没有用户调证权限。'
        })
        .then(() => {
          if (requestToken === this.requestToken) this.loading = false
        })
    },
    formatDate(value) {
      return value ? this.parseTime(value, '{y}-{m}-{d}') : '未记录'
    },
    sourceLabel(row) {
      if (!row.bizType) return '系统成长事件'
      return row.bizId ? `${row.bizType} #${row.bizId}` : row.bizType
    },
    backToMember() {
      this.$router.push({ name: 'customerMember' })
    }
  }
}
</script>

<style lang="scss" scoped>
.member-growth-detail__header { margin-bottom: 20px; }
.member-growth-detail__metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--cy-space-2-5); margin-bottom: 20px; }
.member-growth-detail__metric { padding: var(--cy-space-3-5); border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); box-shadow: var(--cy-shadow-card); }
.member-growth-detail__metric span { display: block; color: var(--cy-color-text-secondary); font-size: 12px; font-weight: 700; }
.member-growth-detail__metric strong { display: block; margin: 12px 0 var(--cy-space-0-5); color: var(--cy-color-text-primary); font-size: 27px; font-variant-numeric: tabular-nums; }
.member-growth-detail__metric p { margin: 0; color: var(--cy-color-text-tertiary); font-size: 12px; line-height: 1.6; }
.member-growth-detail__section { margin-top: var(--cy-space-3-5); }
.member-growth-detail__secondary { display: block; margin-top: var(--cy-legacy-3); color: var(--cy-color-text-tertiary); font-size: 12px; }
.member-growth-detail__badges { display: grid; grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); gap: 12px; }
.member-growth-detail__badge { display: flex; align-items: center; gap: 12px; min-width: 0; padding: var(--cy-space-2-5); border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-control); background: var(--cy-color-bg-subtle); }
.member-growth-detail__badge-icon { flex: none; width: 42px; height: 42px; border-radius: 50%; overflow: hidden; }
.member-growth-detail__badge-icon--empty { display: grid; place-items: center; background: var(--cy-color-brand-soft); color: var(--cy-color-brand-strong); font-size: 20px; }
.member-growth-detail__badge strong, .member-growth-detail__badge span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.member-growth-detail__badge strong { color: var(--cy-color-text-primary); }
.member-growth-detail__badge span { margin-top: 4px; color: var(--cy-color-text-tertiary); font-size: 12px; }
.member-growth-detail ::v-deep .el-table { overflow: hidden; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); }
.member-growth-detail ::v-deep .el-table::before { display: none; }
.member-growth-detail ::v-deep .el-table th { background: var(--cy-color-bg-subtle); color: var(--cy-color-text-secondary); }
@media (max-width: 960px) { .member-growth-detail__metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { .member-growth-detail__metrics { grid-template-columns: 1fr; } }
</style>
