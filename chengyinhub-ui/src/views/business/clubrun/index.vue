<template>
  <div class="app-container">
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" icon="el-icon-refresh" size="mini" @click="getList">刷新</el-button>
      </el-col>
      <el-col :span="4"><el-input v-model="query.topicId" placeholder="主题 id" size="mini" clearable @change="applyFilters" /></el-col>
      <el-col :span="4"><el-input v-model="query.clubId" placeholder="俱乐部 id" size="mini" clearable @change="applyFilters" /></el-col>
      <el-col :span="4">
        <el-select v-model="query.status" placeholder="场次状态" size="mini" clearable @change="getList">
          <el-option label="集合中" :value="1" />
          <el-option label="行进中" :value="2" />
          <el-option label="已结算" :value="3" />
        </el-select>
      </el-col>
    </el-row>

    <div v-loading="loading">
      <div v-if="runs.length === 0" style="text-align:center;color:#909399;padding:40px 0">暂无带队场次</div>

      <!-- 场次卡片(参考 web 后台范式:卡片 + meta chips + 状态点) -->
      <el-row :gutter="14">
        <el-col v-for="run in runs" :key="run.stateId" :span="8">
          <div class="run-card">
            <div class="run-title">
              <span class="run-topic">{{ run.topicName || ('主题#' + run.topicId) }}</span>
              <el-tag size="mini" :type="statusTagType(run.runStatus)">
                <span class="status-dot" :class="'dot-' + run.runStatus" />{{ statusLabel(run.runStatus) }}
              </el-tag>
            </div>
            <div class="run-activity">{{ run.activityName || ('活动#' + run.activityId) }}</div>
            <div class="run-chips">
              <el-tag size="mini" type="info" effect="plain">{{ formatDate(run.startDate) }}</el-tag>
              <el-tag v-if="run.addressName" size="mini" type="info" effect="plain">集合:{{ run.addressName }}</el-tag>
              <el-tag v-if="run.currentChapterId" size="mini" type="info" effect="plain">进行到章节 #{{ run.currentChapterId }}</el-tag>
            </div>
            <div class="run-people">
              <span>{{ run.clubName || ('俱乐部#' + run.clubId) }}</span>
              <span class="run-leader">领队 {{ run.leaderName || '-' }} (#{{ run.leaderMemberId }})</span>
            </div>
            <div class="run-footer">
              <span class="run-counts">已付报名 {{ run.regCount }} · 到场 {{ run.checkinCount }}</span>
              <el-button type="primary" size="mini" plain @click="openManifest(run)">名册与商家</el-button>
            </div>
          </div>
        </el-col>
      </el-row>
    </div>

    <el-drawer :title="manifest.title" :visible.sync="manifest.visible" size="640px" append-to-body>
      <div v-loading="manifest.loading" class="manifest-body">
        <div class="manifest-section">出席名册({{ manifest.roster.length }})</div>
        <el-table :data="manifest.roster" size="mini" border empty-text="该场暂无报名">
          <el-table-column label="玩家" min-width="150">
            <template slot-scope="s">{{ s.row.nickname || '-' }} <span style="color:#909399">(#{{ s.row.memberId }})</span></template>
          </el-table-column>
          <el-table-column label="人数" prop="orderNum" width="60" align="center" />
          <el-table-column label="支付" width="80" align="center">
            <template slot-scope="s">
              <el-tag size="mini" :type="s.row.paymentStatus === 2 ? 'success' : 'info'">{{ payLabel(s.row.paymentStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="核销" width="80" align="center">
            <template slot-scope="s">
              <el-tag size="mini" :type="s.row.verificationStatus === 1 ? 'success' : 'info'">{{ s.row.verificationStatus === 1 ? '已核销' : '未核销' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="到场" width="150" align="center">
            <template slot-scope="s">
              <span v-if="s.row.arrivedAt">{{ s.row.arrivedAt }}</span>
              <el-tag v-else-if="isNoShow(s.row)" size="mini" type="danger">no-show(已付未到)</el-tag>
              <span v-else style="color:#909399">—</span>
            </template>
          </el-table-column>
          <el-table-column label="证据" width="90" align="center">
            <template slot-scope="s">
              <el-button type="text" size="mini" @click="goEvidence(s.row)">核销流水</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="manifest-section" style="margin-top:18px">流程经过的商家(按章节 · 生效供给)</div>
        <div v-if="manifest.merchants.length === 0" style="color:#909399;font-size:13px">该主题当前无生效商家供给</div>
        <div v-for="(group, chapterId) in merchantsByChapter" :key="chapterId" class="chapter-merchants">
          <span class="chapter-name">{{ group[0].chapterName || ('章节#' + chapterId) }}</span>
          <el-tag
            v-for="mc in group" :key="mc.merchantId + '-' + chapterId"
            size="mini" :type="termsTagType(mc.termsMode)" effect="plain" style="margin-left:6px"
          >{{ mc.merchantName || ('#' + mc.merchantId) }} · {{ termsLabel(mc.termsMode) }}</el-tag>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script>
import { runList, runManifest } from "@/api/business/clubRun";

export default {
  name: "ClubRunConsole",
  data() {
    return {
      loading: false,
      runs: [],
      query: { topicId: "", clubId: "", status: null },
      manifest: { visible: false, loading: false, title: "", roster: [], merchants: [] }
    };
  },
  computed: {
    merchantsByChapter() {
      const map = {};
      (this.manifest.merchants || []).forEach(m => {
        const key = m.chapterId || 0;
        (map[key] = map[key] || []).push(m);
      });
      return map;
    }
  },
  created() {
    this.ensureCanonicalRoute();
  },
  watch: {
    '$route'() {
      this.ensureCanonicalRoute();
    }
  },
  methods: {
    applyRouteQuery() {
      const q = this.$route.query || {};
      this.query.topicId = q.topicId || "";
      this.query.clubId = q.clubId || "";
    },
    applyFilters() {
      const query = Object.assign({}, this.$route.query);
      if (this.query.topicId) query.topicId = String(this.query.topicId);
      else delete query.topicId;
      if (this.query.clubId) query.clubId = String(this.query.clubId);
      else delete query.clubId;
      const current = this.$route.query || {};
      if (String(this.$route.path || '').replace(/\/+$/, '') === '/club/lead-run' && current.topicId === query.topicId && current.clubId === query.clubId) {
        return this.getList();
      }
      return this.$router.replace({ name: 'clubLeadRun', query });
    },
    ensureCanonicalRoute() {
      if (String(this.$route.path || '').replace(/\/+$/, '') !== '/club/lead-run') {
        this.$router.replace({ name: 'clubLeadRun', query: Object.assign({}, this.$route.query) });
        return;
      }
      this.applyRouteQuery();
      this.getList();
    },
    numOrOmit(v) {
      return v !== "" && v != null && !isNaN(Number(v)) ? Number(v) : undefined;
    },
    getList() {
      this.loading = true;
      const params = { topicId: this.numOrOmit(this.query.topicId), clubId: this.numOrOmit(this.query.clubId) };
      if ([1, 2, 3].indexOf(this.query.status) > -1) params.status = this.query.status;
      runList(params).then(res => {
        this.runs = res.data || [];
        this.loading = false;
      }).catch(() => { this.loading = false; });
    },
    statusLabel(s) {
      return ({ 1: "集合中", 2: "行进中", 3: "已结算" })[s] || ("状态" + s);
    },
    statusTagType(s) {
      return ({ 1: "warning", 2: "primary", 3: "success" })[s] || "info";
    },
    payLabel(s) {
      return ({ 0: "待支付", 1: "支付中", 2: "已支付", 3: "支付失败", 4: "已退款" })[s] || ("状态" + s);
    },
    termsLabel(mode) {
      return ({ PERK: "权益档", REVSHARE: "计酬档", TRAFFIC: "引流档" })[mode] || mode;
    },
    termsTagType(mode) {
      return ({ PERK: "success", REVSHARE: "danger", TRAFFIC: "info" })[mode] || "info";
    },
    formatDate(d) {
      if (!d) return "未定";
      return String(d).slice(0, 10);
    },
    // no-show 判据:已支付 ∧ 未核销 ∧ 无到场打卡(付了钱又没有任何到场证据)
    isNoShow(row) {
      return row.paymentStatus === 2 && row.verificationStatus !== 1 && !row.arrivedAt;
    },
    // 穿透(§4.5):场次名册 → 同一份核销证据(路由 = 九域 audit-center 目录 + 菜单 path)
    goEvidence(row) {
      this.$router.push({ path: "/audit-center/verifyevidence", query: { registrationId: String(row.registrationId) } });
    },
    openManifest(run) {
      this.manifest = {
        visible: true,
        loading: true,
        title: (run.topicName || "主题#" + run.topicId) + " · " + (run.activityName || "场次#" + run.activityId),
        roster: [],
        merchants: []
      };
      runManifest(run.activityId).then(res => {
        const d = res.data || {};
        this.manifest.roster = d.roster || [];
        this.manifest.merchants = d.merchants || [];
        this.manifest.loading = false;
      }).catch(() => { this.manifest.loading = false; });
    }
  }
};
</script>

<style scoped>
.run-card { background: #f8f9fc; border-radius: var(--cy-radius-dialog); padding: var(--cy-space-4); margin-bottom: var(--cy-space-2-5); }
.run-title { display: flex; justify-content: space-between; align-items: center; }
.run-topic { font-weight: 700; color: #303133; font-size: 14px; }
.run-activity { color: #606266; font-size: 13px; margin: var(--cy-space-0-5) 0 var(--cy-space-2); }
.run-chips .el-tag { margin-right: var(--cy-space-0-5); margin-bottom: var(--cy-space-1); }
.run-people { color: #606266; font-size: 12px; margin: var(--cy-space-2) 0; }
.run-leader { margin-left: var(--cy-space-1-5); color: #909399; }
.run-footer { display: flex; justify-content: space-between; align-items: center; margin-top: var(--cy-space-2); }
.run-counts { color: #303133; font-size: 12px; font-weight: 600; }
.status-dot { display: inline-block; width: var(--cy-space-0-5); height: var(--cy-space-0-5); border-radius: 50%; margin-right: var(--cy-space-1); vertical-align: middle; }
.dot-1 { background: #e6a23c; }
.dot-2 { background: #409eff; }
.dot-3 { background: #67c23a; }
.manifest-body { padding: 0 var(--cy-space-5) var(--cy-space-5); }
.manifest-section { font-weight: 600; color: #303133; margin-bottom: var(--cy-space-1-5); }
.chapter-merchants { margin: var(--cy-space-0-5) 0; }
.chapter-name { color: #606266; font-size: 13px; }
</style>
