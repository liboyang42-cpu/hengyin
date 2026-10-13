<template>
  <div class="app-container">
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" icon="el-icon-refresh" size="mini" @click="getQueue">刷新</el-button>
      </el-col>
      <el-col :span="6">
        <el-input v-model="topicKeyword" placeholder="按主题名筛选" size="mini" clearable />
      </el-col>
      <el-col :span="4">
        <el-select v-model="statusFilter" placeholder="供给状态" size="mini" clearable @change="getQueue">
          <el-option label="仅生效" :value="1" />
          <el-option label="仅已停用" :value="0" />
        </el-select>
      </el-col>
    </el-row>

    <div v-loading="loading">
      <div v-if="filteredTree.length === 0" style="text-align:center;color:#909399;padding:40px 0">暂无商家承接供给</div>

      <el-collapse v-model="activeTopics">
        <el-collapse-item
          v-for="topic in filteredTree"
          :key="topic.topicId"
          :name="String(topic.topicId)"
        >
          <template slot="title">
            <span style="font-weight:600;font-size:15px">{{ topic.topicName || ('主题#' + topic.topicId) }}</span>
            <el-tag v-if="topic.topicLifecycle >= 3" size="mini" type="warning" style="margin-left:10px">已锁价 · 条款冻结</el-tag>
          </template>

          <div v-for="ch in topic.chapters" :key="ch.chapterId" class="chapter-block">
            <div class="chapter-title">
              {{ ch.chapterName || ('章节#' + ch.chapterId) }}
              <el-tag :type="termsModeTagType(ch.termsMode)" size="mini" style="margin-left:8px">{{ termsModeLabel(ch.termsMode) }}(章节口径)</el-tag>
              <span v-if="modeOf(ch) === 'REVSHARE' && ch.maxPerHeadFee > 0" class="chapter-meta">人头费上限 {{ ch.maxPerHeadFee }} 元/人次</span>
              <!-- 计酬章没配上限时定参恒被拒(fail-closed),不亮出来运营看不懂「为什么改不了」 -->
              <el-tag v-if="modeOf(ch) === 'REVSHARE' && !(ch.maxPerHeadFee > 0)" type="danger" size="mini" style="margin-left:6px">未配人头费上限 · 定参会被拒</el-tag>
              <span v-if="modeOf(ch) === 'PERK' && ch.perkMinValue" class="chapter-meta">权益门槛 ≥{{ ch.perkMinValue }} 元</span>
              <span v-if="ch.maxMerchant > 0" class="chapter-meta">· 限 {{ ch.maxMerchant }} 家</span>
            </div>

            <el-table :data="ch.offers" size="mini" border>
              <el-table-column label="商家" width="200">
                <template slot-scope="s">
                  <el-button type="text" style="padding:0" @click="openQual(s.row)">{{ s.row.mmsName || s.row.merchantName || ('#' + s.row.merchantId) }}</el-button>
                  <span style="color:#909399"> (#{{ s.row.merchantId }})</span>
                </template>
              </el-table-column>
              <el-table-column label="历史履约" width="150">
                <template slot-scope="s">
                  <div>权益 {{ perkFulfillment(s.row) }}</div>
                  <div style="color:#909399">计酬 {{ s.row.merchantRevshareCount || 0 }} 笔</div>
                </template>
              </el-table-column>
              <el-table-column label="档位(快照)" width="130" align="center">
                <template slot-scope="s">
                  <el-tag :type="termsModeTagType(s.row.termsMode)" size="mini">{{ termsModeLabel(s.row.termsMode) }}</el-tag>
                  <el-tooltip v-if="s.row.termsMode !== modeOf(ch)" content="与章节口径不一致(章节改档后的存量快照),用「定档定参」对齐" placement="top">
                    <i class="el-icon-warning-outline" style="color:#e6a23c;margin-left:4px" />
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column label="条款">
                <template slot-scope="s">
                  <span v-if="s.row.termsMode === 'REVSHARE'">人头费 {{ s.row.perHeadFee }} 元/人次(按核销计)</span>
                  <span v-else-if="s.row.termsMode === 'PERK'">
                    {{ s.row.perkName || ('权益#' + s.row.perkId) }}
                    · 零售 {{ s.row.perkRetailValue != null ? s.row.perkRetailValue + ' 元' : '未填' }}
                    · 接待 {{ s.row.quotaUsed || 0 }}/{{ s.row.quotaTotal }}
                  </span>
                  <span v-else>接待打卡 · 零承诺零结算</span>
                </template>
              </el-table-column>
              <el-table-column label="有效期" width="150" align="center">
                <template slot-scope="s">
                  <span v-if="!s.row.validEnd">不限</span>
                  <span v-else :style="expired(s.row) ? 'color:#f56c6c' : ''">{{ s.row.validEnd }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90" align="center">
                <template slot-scope="s">
                  <el-tag size="mini" :type="s.row.status === 1 ? (expired(s.row) ? 'warning' : 'success') : 'info'">
                    {{ s.row.status === 1 ? (expired(s.row) ? '已过期' : '生效') : '已停用' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="180" align="center">
                <template slot-scope="s">
                  <el-button
                    v-hasPermi="['business:chapterOffer:edit']"
                    type="primary" size="mini"
                    :disabled="topic.topicLifecycle >= 3 || s.row.status !== 1"
                    @click="openAdjust(topic, ch, s.row)"
                  >定档定参</el-button>
                  <el-button
                    v-hasPermi="['business:chapterOffer:edit']"
                    type="danger" size="mini"
                    :disabled="s.row.status !== 1"
                    @click="onDeactivate(s.row)"
                  >停用</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>

    <el-dialog title="定档定参" :visible.sync="adjust.visible" width="560px" append-to-body :close-on-click-modal="false">
      <el-form label-width="110px" size="small">
        <el-form-item label="章节口径">
          <el-tag :type="termsModeTagType(adjust.chapterMode)" size="mini">{{ termsModeLabel(adjust.chapterMode) }}</el-tag>
          <span style="color:#909399;margin-left:8px">定档须对齐章节口径;要换档先到主题详情改章节的供给条款</span>
        </el-form-item>
        <el-form-item label="档位">
          <el-radio-group v-model="adjust.form.termsMode">
            <el-radio label="TRAFFIC" :disabled="adjust.chapterMode !== 'TRAFFIC'">引流档</el-radio>
            <el-radio label="REVSHARE" :disabled="adjust.chapterMode !== 'REVSHARE'">计酬档</el-radio>
            <el-radio label="PERK" :disabled="adjust.chapterMode !== 'PERK'">权益档</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="adjust.form.termsMode === 'REVSHARE'">
          <el-form-item label="人头费(元/人次)">
            <el-input-number v-model="adjust.form.perHeadFee" :min="0" :precision="2" :step="1" />
            <span style="color:#909399;margin-left:8px">
              {{ adjust.maxPerHeadFee > 0 ? ('不得超过章节上限 ' + adjust.maxPerHeadFee + ' 元') : '本章节未配上限,提交会被拒' }}
            </span>
          </el-form-item>
        </template>

        <template v-if="adjust.form.termsMode === 'PERK'">
          <el-form-item label="权益ID">
            <el-input-number v-model="adjust.form.perkId" :min="1" :controls="false" />
            <span style="color:#909399;margin-left:8px">只能填该商家自己申报的权益(coop_perk)</span>
          </el-form-item>
          <el-form-item label="接待上限(人次)">
            <el-input-number v-model="adjust.form.quotaTotal" :min="Math.max(1, adjust.quotaUsed)" :step="1" />
            <span style="color:#909399;margin-left:8px">已核销 {{ adjust.quotaUsed }} 人次,上限不能低于它</span>
          </el-form-item>
        </template>

        <el-form-item label="有效期至">
          <el-date-picker
            v-model="adjust.form.validEnd"
            type="datetime"
            value-format="yyyy-MM-dd HH:mm:ss"
            placeholder="留空 = 长期有效"
            clearable
          />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="adjust.visible = false">取 消</el-button>
        <el-button size="small" type="primary" :loading="adjust.saving" @click="submitAdjust">保 存</el-button>
      </div>
    </el-dialog>

    <el-drawer title="商家资质" :visible.sync="qual.visible" size="420px" append-to-body>
      <div v-if="qual.row" class="qual-body">
        <cy-entity-header
          eyebrow="商家资质"
          :title="qual.row.mmsName || qual.row.merchantName || '未入驻商家档案'"
          :status="qualHeaderStatus"
          :facts="qualHeaderFacts"
        />
        <p class="qual-address">地址：{{ qual.row.mmsAddress || '未填写' }}</p>
        <cy-evidence-panel
          eyebrow="准入材料"
          title="营业执照"
          :items="qualEvidenceItems"
          empty-text="未上传营业执照，无法验证接待资质"
        />
      </div>
    </el-drawer>
  </div>
</template>

<script>
import { offerQueue, adjustOffer, deactivateOffer } from "@/api/business/chapterOffer";
import { toTimestamp } from '@/utils/ruoyi'
import CyEntityHeader from '@/components/CyEntityHeader'
import CyEvidencePanel from '@/components/CyEvidencePanel'

export default {
  name: "ChapterOfferAudit",
  components: { CyEntityHeader, CyEvidencePanel },
  dicts: ["sys_merchant_status"],
  data() {
    return {
      loading: false,
      tree: [],
      activeTopics: [],
      topicKeyword: "",
      statusFilter: null,
      qual: { visible: false, row: null },
      adjust: {
        visible: false,
        saving: false,
        chapterMode: "PERK",
        maxPerHeadFee: null,
        quotaUsed: 0,
        form: { id: null, termsMode: null, perHeadFee: null, perkId: null, quotaTotal: null, validEnd: null }
      }
    };
  },
  computed: {
    filteredTree() {
      if (!this.topicKeyword) return this.tree;
      const kw = this.topicKeyword.trim();
      return this.tree.filter(t => (t.topicName || "").indexOf(kw) > -1);
    },
    qualHeaderStatus() {
      const row = this.qual.row;
      if (!row || row.mmsStatus === null || row.mmsStatus === undefined) return { label: '未建档', tone: 'neutral' };
      return {
        0: { label: '待审核', tone: 'warning' },
        1: { label: '已通过', tone: 'success' },
        2: { label: '未通过', tone: 'danger' }
      }[row.mmsStatus] || { label: '状态未知', tone: 'neutral' };
    },
    qualHeaderFacts() {
      const row = this.qual.row;
      if (!row) return [];
      return [
        { label: '商家 ID', value: row.merchantId !== null && row.merchantId !== undefined ? `#${row.merchantId}` : '—' },
        { label: '联系电话', value: row.mmsPhone || '未填写' },
        { label: '接待能力', value: row.mmsCapacity !== null && row.mmsCapacity !== undefined ? `${row.mmsCapacity} 人` : '未填写' },
        { label: '历史履约', value: `权益 ${this.perkFulfillment(row)} · 计酬 ${row.merchantRevshareCount || 0} 笔` }
      ];
    },
    qualEvidenceItems() {
      const row = this.qual.row;
      if (!row || !row.businessLicense) return [];
      return [{ kind: 'image', src: row.businessLicense, alt: `商家 #${row.merchantId} 的营业执照` }];
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
    ensureCanonicalRoute() {
      if (String(this.$route.path || '').replace(/\/+$/, '') !== '/merchant/chapter-offer') {
        this.$router.replace({ name: 'merchantChapterOffer', query: Object.assign({}, this.$route.query) });
        return;
      }
      this.getQueue();
    },
    getQueue() {
      this.loading = true;
      // 清空筛选时别把空串发给后端(Integer 参数吃不下)
      const params = {};
      if (this.statusFilter === 0 || this.statusFilter === 1) params.status = this.statusFilter;
      offerQueue(params).then(res => {
        this.tree = res.data || [];
        this.activeTopics = this.tree.map(t => String(t.topicId));
        this.loading = false;
      }).catch(() => { this.loading = false; });
    },
    // 章节口径:null 按 PERK(与后端 DDL DEFAULT 'PERK' 同口径)
    modeOf(ch) {
      return ch.termsMode || "PERK";
    },
    termsModeLabel(mode) {
      const map = { PERK: "权益档", REVSHARE: "计酬档", TRAFFIC: "引流档" };
      return map[mode || "PERK"] || mode;
    },
    termsModeTagType(mode) {
      // 计酬档是唯一要平台掏钱的档,给它不同颜色,扫一眼看出哪些在花钱(与主题详情同款)
      const map = { PERK: "success", REVSHARE: "danger", TRAFFIC: "info" };
      return map[mode || "PERK"] || "info";
    },
    expired(row) {
      const ts = toTimestamp(row.validEnd);
      return Number.isFinite(ts) && ts <= Date.now();
    },
    // 拍板口径(2026-07-21):权益档核销完成率 = 该商家全平台 Σquota_used/Σquota_total,含过期/停用
    perkFulfillment(row) {
      const total = row.merchantPerkTotal || 0;
      if (!(total > 0)) return "—";
      const used = row.merchantPerkUsed || 0;
      return used + "/" + total + "(" + Math.round((used * 100) / total) + "%)";
    },
    openQual(row) {
      this.qual = { visible: true, row: row };
    },
    openAdjust(topic, ch, row) {
      const chapterMode = this.modeOf(ch);
      this.adjust = {
        visible: true,
        saving: false,
        chapterMode: chapterMode,
        maxPerHeadFee: ch.maxPerHeadFee,
        quotaUsed: row.quotaUsed || 0,
        form: {
          id: row.id,
          // 档位预填章节口径(唯一合法值);参数带出该行现值,快照偏离口径时由运营重定参
          termsMode: chapterMode,
          perHeadFee: row.perHeadFee,
          perkId: row.perkId,
          quotaTotal: row.quotaTotal || null,
          validEnd: row.validEnd
        }
      };
    },
    submitAdjust() {
      const f = this.adjust.form;
      if (f.termsMode === "REVSHARE" && (f.perHeadFee == null)) {
        this.$modal.msgError("请填写每人次的计酬金额");
        return;
      }
      if (f.termsMode === "PERK" && !f.perkId) {
        this.$modal.msgError("请填写权益ID");
        return;
      }
      if (f.termsMode === "PERK" && !(f.quotaTotal >= 1)) {
        this.$modal.msgError("请填写可接待人次(至少 1)");
        return;
      }
      // 非该档的参数不带上:后端形状闸「引流档不谈人头费」等会拒掉多余字段
      const payload = { id: f.id, termsMode: f.termsMode, validEnd: f.validEnd || null };
      if (f.termsMode === "REVSHARE") payload.perHeadFee = f.perHeadFee;
      if (f.termsMode === "PERK") { payload.perkId = f.perkId; payload.quotaTotal = f.quotaTotal; }
      this.adjust.saving = true;
      adjustOffer(payload).then(res => {
        this.$modal.msgSuccess(res.msg || "条款已更新");
        this.adjust.visible = false;
        this.adjust.saving = false;
        this.getQueue();
      }).catch(() => { this.adjust.saving = false; });
    },
    onDeactivate(row) {
      this.$modal.confirm("停用后该商家在本章节的供给立即失效(不删行,核销流水保留);必选章的最后一家被停用会让在售票出履约缺口。确认停用？").then(() => {
        return deactivateOffer(row.id);
      }).then(() => {
        this.$modal.msgSuccess("已停用");
        this.getQueue();
      }).catch(() => {});
    }
  }
};
</script>

<style scoped>
.chapter-block { margin: 8px 0 16px; }
.chapter-title { font-weight: 600; margin-bottom: 8px; color: #303133; }
.chapter-meta { margin-left: var(--cy-space-0-5); color: #909399; font-weight: 400; font-size: 12px; }
.qual-body { padding: 0 20px 20px; }
.qual-address { margin: 16px 0 0; color: var(--cy-color-text-secondary); font-size: 13px; line-height: 1.6; }
</style>
