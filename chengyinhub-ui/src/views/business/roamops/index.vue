<template>
  <div class="app-container">
    <cy-page-header eyebrow="俱乐部与漫游" title="漫游与游玩运营" description="只读视图：漫游会话、揭雾格数、队伍与游玩局。只显示数量与状态，不显示任何个人轨迹或位置。">
      <el-button slot="primary" size="small" icon="el-icon-refresh" :loading="loading" @click="load">刷新</el-button>
    </cy-page-header>

    <el-tabs v-model="tab" @tab-click="load">
      <el-tab-pane label="漫游会话" name="sessions">
        <el-form size="small" :inline="true">
          <el-form-item label="玩家编号"><el-input v-model="sessionQuery.memberId" clearable placeholder="玩家编号" style="width: 130px" /></el-form-item>
          <el-form-item label="状态">
            <el-select v-model="sessionQuery.status" clearable placeholder="全部" style="width: 110px">
              <el-option label="进行中" :value="0" />
              <el-option label="已结算" :value="1" />
            </el-select>
          </el-form-item>
          <el-form-item label="开始日期">
            <el-date-picker v-model="sessionQuery.range" type="daterange" value-format="yyyy-MM-dd" range-separator="至" start-placeholder="起" end-placeholder="止" style="width: 240px" />
          </el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" @click="load">筛选</el-button></el-form-item>
        </el-form>
        <el-row v-if="sessions.totals" :gutter="12" class="roam-ops-metrics">
          <el-col :span="4"><cy-metric-card label="会话" :value="sessions.totals.sessions" /></el-col>
          <el-col :span="4"><cy-metric-card label="玩家" :value="sessions.totals.members" /></el-col>
          <el-col :span="4"><cy-metric-card label="进行中" :value="sessions.totals.open" /></el-col>
          <el-col :span="4"><cy-metric-card label="里程（公里）" :value="km(sessions.totals.distanceM)" /></el-col>
          <el-col :span="4"><cy-metric-card label="新点亮格子" :value="sessions.totals.tilesRevealed" /></el-col>
          <el-col :span="4"><cy-metric-card label="发现据点" :value="sessions.totals.poisFound" /></el-col>
        </el-row>
        <cy-resource-state v-if="error" kind="error" title="数据暂时无法读取" retry-text="重新读取" :retrying="loading" @retry="load" />
        <el-table v-else v-loading="loading" :data="sessions.items" size="small" border>
          <el-table-column label="会话" prop="id" width="90" />
          <el-table-column label="玩家" width="110"><template slot-scope="s">#{{ s.row.memberId }}</template></el-table-column>
          <el-table-column label="开始" prop="startedAt" width="160" />
          <el-table-column label="结束" prop="finishedAt" width="160" />
          <el-table-column label="里程（米）" prop="distanceM" width="100" align="right" />
          <el-table-column label="新点亮格子" prop="tilesRevealed" width="100" align="right" />
          <el-table-column label="发现据点" prop="poisFound" width="90" align="right" />
          <el-table-column label="探索值" prop="xpAwarded" width="80" align="right" />
          <el-table-column label="状态" width="80" align="center"><template slot-scope="s">{{ s.row.status === 1 ? '已结算' : '进行中' }}</template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="揭雾" name="fog">
        <el-form size="small" :inline="true">
          <el-form-item label="玩家编号"><el-input v-model="fogQuery.memberId" clearable placeholder="玩家编号" style="width: 130px" /></el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" @click="load">筛选</el-button></el-form-item>
        </el-form>
        <el-row v-if="fog.totals" :gutter="12" class="roam-ops-metrics">
          <el-col :span="6"><cy-metric-card label="已揭开格子" :value="fog.totals.revealedCells" /></el-col>
          <el-col :span="6"><cy-metric-card label="有揭雾的玩家" :value="fog.totals.members" /></el-col>
          <el-col :span="6"><cy-metric-card label="近 7 天有更新的格子" :value="fog.totals.touchedLast7Days" /></el-col>
        </el-row>
        <el-table v-loading="loading" :data="fog.items" size="small" border>
          <el-table-column label="玩家" width="120"><template slot-scope="s">#{{ s.row.memberId }}</template></el-table-column>
          <el-table-column label="已揭开格子" prop="revealedCells" width="120" align="right" />
          <el-table-column label="首次揭开" prop="firstRevealedAt" width="170" />
          <el-table-column label="最近揭开" prop="lastRevealedAt" width="170" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="队伍" name="teams">
        <el-form size="small" :inline="true">
          <el-form-item label="归属">
            <el-select v-model="teamQuery.ownerType" clearable placeholder="全部" style="width: 110px">
              <el-option label="主题" :value="1" />
              <el-option label="活动" :value="2" />
            </el-select>
          </el-form-item>
          <el-form-item label="归属编号"><el-input v-model="teamQuery.ownerId" clearable placeholder="主题或活动编号" style="width: 140px" /></el-form-item>
          <el-form-item label="状态">
            <el-select v-model="teamQuery.status" clearable placeholder="全部" style="width: 110px">
              <el-option v-for="(t, k) in teamStatus" :key="k" :label="t" :value="Number(k)" />
            </el-select>
          </el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" @click="load">筛选</el-button></el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="teams" size="small" border>
          <el-table-column label="队伍" min-width="160"><template slot-scope="s">{{ s.row.title || '未命名队伍' }} #{{ s.row.id }}</template></el-table-column>
          <el-table-column label="归属" width="130"><template slot-scope="s">{{ s.row.ownerType === 2 ? '活动' : '主题' }} #{{ s.row.ownerId }}</template></el-table-column>
          <el-table-column label="队长" width="110"><template slot-scope="s">#{{ s.row.leaderMemberId }}</template></el-table-column>
          <el-table-column label="人数" width="110" align="center"><template slot-scope="s">{{ s.row.activeMembers }} / {{ s.row.maxMembers }}</template></el-table-column>
          <el-table-column label="待审核" prop="pendingMembers" width="80" align="center" />
          <el-table-column label="加入方式" width="90"><template slot-scope="s">{{ s.row.joinMode === 2 ? '公开申请' : '仅邀请' }}</template></el-table-column>
          <el-table-column label="状态" width="90" align="center"><template slot-scope="s">{{ teamStatus[s.row.status] || '—' }}</template></el-table-column>
          <el-table-column label="创建" prop="createTime" width="160" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="游玩局" name="runs">
        <el-form size="small" :inline="true">
          <el-form-item label="主题编号"><el-input v-model="runQuery.topicId" clearable placeholder="主题编号" style="width: 120px" /></el-form-item>
          <el-form-item label="玩家编号"><el-input v-model="runQuery.memberId" clearable placeholder="玩家编号" style="width: 120px" /></el-form-item>
          <el-form-item label="进度">
            <el-select v-model="runQuery.runState" clearable placeholder="全部" style="width: 110px">
              <el-option label="可继续" value="PAUSED" />
              <el-option label="已结束" value="ENDED" />
            </el-select>
          </el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" @click="load">筛选</el-button></el-form-item>
        </el-form>
        <h4>玩家主题进度</h4>
        <el-table v-loading="loading" :data="runs.playerRuns" size="small" border>
          <el-table-column label="玩家" width="110"><template slot-scope="s">#{{ s.row.memberId }}</template></el-table-column>
          <el-table-column label="主题" width="110"><template slot-scope="s">#{{ s.row.topicId }}</template></el-table-column>
          <el-table-column label="场次" width="110"><template slot-scope="s">{{ s.row.activityId ? '#' + s.row.activityId : '自玩' }}</template></el-table-column>
          <el-table-column label="进度" width="90"><template slot-scope="s">{{ s.row.runState === 'ENDED' ? '已结束' : '可继续' }}</template></el-table-column>
          <el-table-column label="已用时" width="100"><template slot-scope="s">{{ minutes(s.row.elapsedSeconds) }}</template></el-table-column>
          <el-table-column label="更新" prop="updateTime" width="160" />
        </el-table>
        <h4>节点玩法局</h4>
        <el-table v-loading="loading" :data="runs.gameRuns" size="small" border>
          <el-table-column label="局" prop="id" width="90" />
          <el-table-column label="主题 / 节点" width="160"><template slot-scope="s">#{{ s.row.topicId }} / #{{ s.row.nodeId }}</template></el-table-column>
          <el-table-column label="参与方" width="130"><template slot-scope="s">{{ s.row.ownerType === 2 ? '队伍' : '玩家' }} #{{ s.row.ownerId }}</template></el-table-column>
          <el-table-column label="得分" prop="score" width="80" align="right" />
          <el-table-column label="完成单元" prop="completedUnits" width="90" align="right" />
          <el-table-column label="状态" width="80"><template slot-scope="s">{{ runStatus[s.row.status] || '—' }}</template></el-table-column>
          <el-table-column label="开始" prop="startedAt" width="160" />
          <el-table-column label="完成" prop="completedAt" width="160" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import { roamOpsSessions, roamOpsFog, roamOpsTeams, roamOpsRuns } from '@/api/business/roamOps'
import CyMetricCard from '@/components/CyMetricCard'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

export default {
  name: 'RoamOps',
  components: { CyMetricCard, CyPageHeader, CyResourceState },
  data() {
    return {
      tab: 'sessions',
      loading: false,
      error: false,
      sessions: { totals: null, items: [] },
      fog: { totals: null, items: [] },
      teams: [],
      runs: { playerRuns: [], gameRuns: [] },
      sessionQuery: { memberId: '', status: null, range: [] },
      fogQuery: { memberId: '' },
      teamQuery: { ownerType: null, ownerId: '', status: null },
      runQuery: { topicId: '', memberId: '', runState: '' },
      teamStatus: { 0: '招募中', 1: '已满员', 2: '进行中', 3: '已结束', 4: '已解散' },
      runStatus: { 0: '进行中', 1: '完成', 2: '失败', 3: '超时' }
    }
  },
  created() {
    this.load()
  },
  methods: {
    clean(o) {
      const out = {}
      Object.keys(o).forEach(k => { if (o[k] !== '' && o[k] !== null && o[k] !== undefined) out[k] = o[k] })
      return out
    },
    load() {
      const range = this.sessionQuery.range || []
      const calls = {
        sessions: () => roamOpsSessions(this.clean({ memberId: this.sessionQuery.memberId, status: this.sessionQuery.status, since: range[0], until: range[1] ? this.nextDay(range[1]) : '' }))
          .then(r => { this.sessions = r.data || { totals: null, items: [] } }),
        fog: () => roamOpsFog(this.clean(this.fogQuery)).then(r => { this.fog = r.data || { totals: null, items: [] } }),
        teams: () => roamOpsTeams(this.clean(this.teamQuery)).then(r => { this.teams = r.data || [] }),
        runs: () => roamOpsRuns(this.clean(this.runQuery)).then(r => { this.runs = r.data || { playerRuns: [], gameRuns: [] } })
      }
      this.loading = true
      this.error = false
      calls[this.tab]().catch(() => { this.error = true }).finally(() => { this.loading = false })
    },
    nextDay(day) {
      const d = new Date(day + 'T00:00:00')
      d.setDate(d.getDate() + 1)
      return this.parseTime(d, '{y}-{m}-{d}')
    },
    km(m) {
      return m == null ? '—' : (Number(m) / 1000).toFixed(1)
    },
    minutes(sec) {
      return sec == null ? '—' : Math.round(Number(sec) / 60) + ' 分钟'
    }
  }
}
</script>

<style scoped>
.roam-ops-metrics { margin-bottom: 12px; }
</style>
