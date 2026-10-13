<template>
  <div class="app-container">
    <cy-page-header
      eyebrow="商家"
      title="门店 AI 形象"
      description="给门店配一个像素风形象，在店铺公开主页「关于」页顶部静态出场。当前不接对话——形象只展示名字、头像和一句招呼语。"
      :updated-at="updatedAt"
    >
      <el-button
        slot="primary"
        v-hasPermi="['business:merchantNpc:add']"
        size="small"
        type="primary"
        icon="el-icon-plus"
        @click="openCreate"
      >新增形象</el-button>
      <el-button size="small" icon="el-icon-refresh" :loading="loading" @click="getList">刷新列表</el-button>
    </cy-page-header>

    <section class="cy-panel merchant-npc-filter" aria-label="门店形象筛选">
      <div class="cy-panel__heading">
        <div>
          <h2>定位形象</h2>
          <p>筛选只作用于本次读取结果；接口不提供分页，不把当前数量当作全量统计。</p>
        </div>
      </div>
      <el-form ref="queryForm" :model="queryForm" size="small" :inline="true" label-width="72px">
        <cy-filter-bar
          :active-filters="activeFilters"
          @submit="handleQuery"
          @reset="resetQuery"
          @remove-filter="clearFilter"
        >
          <el-form-item label="所属商家" prop="merchantId">
            <el-select
              v-model="queryForm.merchantId"
              filterable
              remote
              clearable
              placeholder="输入商家名搜索"
              :remote-method="searchMerchants"
              :loading="merchantLoading"
              style="width: 220px"
            >
              <el-option
                v-for="m in merchantOptions"
                :key="m.id"
                :label="m.name + '（#' + m.id + '）'"
                :value="m.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="形象名称" prop="name">
            <el-input v-model="queryForm.name" placeholder="按形象名模糊搜索" clearable @keyup.enter.native="handleQuery" />
          </el-form-item>
        </cy-filter-bar>
      </el-form>
    </section>

    <cy-resource-state
      v-if="listError"
      kind="error"
      title="门店形象列表暂时读不出来"
      description="请刷新重试；读取失败不会被伪装成「一个形象都没配」。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <section v-else class="cy-panel" aria-label="门店形象列表">
      <el-table v-loading="loading" :data="list" size="small" empty-text="当前筛选下还没有配过门店形象">
        <el-table-column label="形象" min-width="220">
          <template slot-scope="s">
            <div class="merchant-npc-figure">
              <img v-if="s.row.avatar" class="merchant-npc-avatar" :src="s.row.avatar" :alt="s.row.name">
              <div v-else class="merchant-npc-avatar merchant-npc-avatar--none">无</div>
              <div>
                <strong>{{ s.row.name }}</strong>
                <span class="merchant-npc-greeting">{{ s.row.greeting || '没有招呼语' }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="所属商家" min-width="180">
          <template slot-scope="s">
            <span>{{ s.row.merchantName || '商家已删除' }}</span>
            <span class="merchant-npc-id">#{{ s.row.scopeId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="排序" width="80" prop="sort" />
        <!-- ★ 审核态必须单独一列:只显示 enabled 的话,「待审」与「已上线」长得一模一样,
             审核员根本看不出哪些在等他。商家自助提交的一律落 audit_status=0。 -->
        <el-table-column label="审核" width="150">
          <template slot-scope="s">
            <cy-status-tag
              :label="auditLabel(s.row)"
              :tone="auditTone(s.row)"
            />
            <div v-if="s.row.auditStatus === 2 && s.row.auditReason" class="npc-audit-reason">{{ s.row.auditReason }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template slot-scope="s">
            <cy-status-tag
              :label="s.row.enabled === 1 ? '展示中' : '已停用'"
              :tone="s.row.enabled === 1 ? 'success' : 'neutral'"
            />
          </template>
        </el-table-column>
        <el-table-column label="最后变更" width="170">
          <template slot-scope="s">{{ s.row.updateTime || s.row.createTime || '未记录' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template slot-scope="s">
            <!-- 通过/驳回只在待审时出现:已上线的再点一次审核没有意义,
                 而且后端 WHERE 钉了 audit_status=0,点了也只会返回「已经不是待审状态」。 -->
            <el-button
              v-if="s.row.auditStatus === 0"
              v-hasPermi="['business:merchantNpc:edit']"
              type="text"
              size="mini"
              :loading="pendingActionId === s.row.id"
              @click="onAudit(s.row, 1)"
            >通过</el-button>
            <el-button
              v-if="s.row.auditStatus === 0"
              v-hasPermi="['business:merchantNpc:edit']"
              type="text"
              size="mini"
              :loading="pendingActionId === s.row.id"
              @click="onAudit(s.row, 2)"
            >驳回</el-button>
            <el-button v-hasPermi="['business:merchantNpc:edit']" type="text" size="mini" @click="openEdit(s.row)">编辑</el-button>
            <el-button
              v-hasPermi="['business:merchantNpc:remove']"
              type="text"
              size="mini"
              :loading="pendingActionId === s.row.id"
              @click="onRemove(s.row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog :title="form.id ? '编辑门店形象' : '新增门店形象'" :visible.sync="dialogVisible" width="560px" append-to-body :close-on-click-modal="false">
      <el-form ref="npcForm" :model="form" :rules="rules" size="small" label-width="96px">
        <el-form-item v-if="!form.id" label="所属商家" prop="scopeId">
          <el-select
            v-model="form.scopeId"
            filterable
            remote
            placeholder="输入商家名搜索"
            :remote-method="searchMerchants"
            :loading="merchantLoading"
            style="width: 100%"
          >
            <el-option
              v-for="m in merchantOptions"
              :key="m.id"
              :label="m.name + '（#' + m.id + '）'"
              :value="m.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="所属商家">
          <span class="merchant-npc-readonly">{{ form.merchantName || '商家已删除' }} #{{ form.scopeId }} · 换商家请重新建一条</span>
        </el-form-item>

        <el-form-item label="形象名称" prop="name">
          <el-input v-model.trim="form.name" maxlength="32" show-word-limit placeholder="例如：店长阿喵" />
        </el-form-item>

        <el-form-item label="像素头像" prop="avatar">
          <image-upload v-model="form.avatar" :limit="1" />
          <p class="merchant-npc-note">
            请传像素风素材（建议 64×64 或 128×128 的 PNG，边缘不要抗锯齿）。
            主页会用 image-rendering: pixelated 放大，非像素图放大后会糊。
          </p>
        </el-form-item>

        <el-form-item label="招呼语" prop="greeting">
          <el-input v-model.trim="form.greeting" type="textarea" :rows="2" maxlength="255" show-word-limit placeholder="主页上形象说的那一句，例如：来都来了，坐下喝一杯？" />
        </el-form-item>

        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" :max="999" :precision="0" controls-position="right" />
          <p class="merchant-npc-note">一个门店可配多条，主页只出排序最小的那条。</p>
        </el-form-item>

        <el-form-item label="展示状态" prop="enabled">
          <el-radio-group v-model="form.enabled">
            <el-radio :label="1">展示中</el-radio>
            <el-radio :label="0">已停用</el-radio>
          </el-radio-group>
          <p class="merchant-npc-note">停用后主页整块不渲染，不留空壳卡片。</p>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSubmit">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { addMerchantNpc, auditMerchantNpc, delMerchantNpc, listMerchantNpc, updateMerchantNpc } from '@/api/business/merchantNpc'
import { listMerchant } from '@/api/business/merchant'
import CyFilterBar from '@/components/CyFilterBar'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'
import CyStatusTag from '@/components/CyStatusTag'

const createQuery = () => ({ merchantId: null, name: '' })
const createForm = () => ({ id: null, scopeId: null, merchantName: '', name: '', avatar: '', greeting: '', sort: 0, enabled: 1 })

export default {
  name: 'MerchantNpc',
  components: { CyFilterBar, CyPageHeader, CyResourceState, CyStatusTag },
  data() {
    return {
      loading: false,
      listError: false,
      saving: false,
      pendingActionId: null,
      updatedAt: '',
      list: [],
      queryForm: createQuery(),
      queryParams: createQuery(),
      dialogVisible: false,
      form: createForm(),
      merchantOptions: [],
      merchantLoading: false,
      rules: {
        scopeId: [{ required: true, message: '请选择所属商家', trigger: 'change' }],
        name: [{ required: true, message: '请填写形象名称', trigger: 'blur' }],
        avatar: [{ required: true, message: '请上传像素头像', trigger: 'change' }]
      }
    }
  },
  computed: {
    activeFilters() {
      return [
        this.queryParams.merchantId && { key: 'merchantId', label: '所属商家', value: '#' + this.queryParams.merchantId },
        this.queryParams.name && { key: 'name', label: '形象名称', value: this.queryParams.name }
      ].filter(Boolean)
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      this.listError = false
      listMerchantNpc({ merchantId: this.queryParams.merchantId, name: this.queryParams.name || undefined })
        .then(res => {
          // 读失败绝不回退成空数组:那是拿「没查到」冒充「一个都没配」。
          // 200 但 body 不是数组同样算没读到 —— 静默当成空列表就是上面那句禁止的事。
          if (!Array.isArray(res.data)) { this.listError = true; return }
          this.list = res.data
          this.updatedAt = this.parseTime(new Date(), '{y}-{m}-{d} {h}:{i}')
        })
        .catch(() => { this.listError = true })
        .finally(() => { this.loading = false })
    },
    handleQuery() {
      this.queryParams = Object.assign({}, this.queryForm)
      this.getList()
    },
    resetQuery() {
      this.queryForm = createQuery()
      this.handleQuery()
    },
    clearFilter(key) {
      this.queryForm[key] = createQuery()[key]
      this.handleQuery()
    },
    searchMerchants(keyword) {
      if (!keyword) { this.merchantOptions = []; return }
      this.merchantLoading = true
      listMerchant({ name: keyword, pageNum: 1, pageSize: 20 })
        .then(res => { this.merchantOptions = res.rows || [] })
        .catch(() => {
          // 同理:搜商家失败不能渲染成「没有这个商家」,否则运营会以为商家没入驻
          this.merchantOptions = []
          this.$modal.msgError('商家搜索失败，请重试')
        })
        .finally(() => { this.merchantLoading = false })
    },
    openCreate() {
      this.form = createForm()
      this.merchantOptions = []
      this.dialogVisible = true
      this.$nextTick(() => this.$refs.npcForm && this.$refs.npcForm.clearValidate())
    },
    openEdit(row) {
      this.form = Object.assign(createForm(), {
        id: row.id,
        scopeId: row.scopeId,
        merchantName: row.merchantName,
        name: row.name,
        avatar: row.avatar,
        greeting: row.greeting || '',
        sort: row.sort == null ? 0 : row.sort,
        enabled: row.enabled === 0 ? 0 : 1
      })
      this.dialogVisible = true
      this.$nextTick(() => this.$refs.npcForm && this.$refs.npcForm.clearValidate())
    },
    onSubmit() {
      this.$refs.npcForm.validate(valid => {
        if (!valid) return
        this.saving = true
        const payload = {
          name: this.form.name,
          avatar: this.form.avatar,
          greeting: this.form.greeting,
          sort: this.form.sort,
          enabled: this.form.enabled
        }
        const req = this.form.id
          ? updateMerchantNpc(Object.assign({ id: this.form.id }, payload))
          : addMerchantNpc(Object.assign({ scopeId: this.form.scopeId }, payload))
        req.then(() => {
          this.$modal.msgSuccess(this.form.id ? '已保存' : '已新增')
          this.dialogVisible = false
          this.getList()
        }).finally(() => { this.saving = false })
      })
    },
    onRemove(row) {
      this.$modal.confirm('删除后「' + row.name + '」不再出现在店铺主页，确认删除？').then(() => {
        this.pendingActionId = row.id
        return delMerchantNpc(row.id)
      }).then(() => {
        this.$modal.msgSuccess('已删除')
        this.getList()
      }).catch(() => {}).finally(() => { this.pendingActionId = null })
    },

    // ★ 审核态必须能一眼看出来:只显示 enabled 的话,「待审」与「已上线」长得一样,
    //   审核员看不出哪些在等他。商家自助提交的一律落 auditStatus=0。
    auditLabel(row) {
      if (row.auditStatus === 1) return '已过审'
      if (row.auditStatus === 2) return '已驳回'
      // 认不出的值按「待审」——宁可多看一眼,也不要把未知状态当成已过审放出去。
      return '待审核'
    },

    auditTone(row) {
      if (row.auditStatus === 1) return 'success'
      if (row.auditStatus === 2) return 'danger'
      return 'warning'
    },

    // status: 1=通过 2=驳回。
    // ★★ 没有这个动作,商家提交的形象永远停在待审、永远不上线,而且零报错零告警。
    onAudit(row, status) {
      if (status === 2) {
        // 驳回必须写原因:不说为什么,商家只会把同一份原样再交一遍 —— 一个死循环。
        // 后端也拦一道(空原因的驳回直接拒),这里只是不必等一次往返。
        this.$prompt('驳回「' + row.name + '」的原因（会原样回传给商家）', '驳回', {
          inputValidator: (v) => (v && v.trim() ? true : '必须写明原因'),
          confirmButtonText: '确认驳回',
          cancelButtonText: '取消'
        }).then(({ value }) => {
          this.pendingActionId = row.id
          return auditMerchantNpc({ id: row.id, auditStatus: 2, auditReason: value.trim() })
        }).then(() => {
          this.$modal.msgSuccess('已驳回,原因已回传给商家')
          this.getList()
        }).catch(() => {}).finally(() => { this.pendingActionId = null })
        return
      }
      this.$modal.confirm('通过后「' + row.name + '」会出现在店铺主页与漫游地图，确认通过？').then(() => {
        this.pendingActionId = row.id
        return auditMerchantNpc({ id: row.id, auditStatus: 1 })
      }).then(() => {
        this.$modal.msgSuccess('已通过')
        this.getList()
      }).catch(() => {}).finally(() => { this.pendingActionId = null })
    }
  }
}
</script>

<style scoped>
.merchant-npc-figure { display: flex; align-items: center; gap: 12px; }
.npc-audit-reason { margin-top: 4px; font-size: 12px; line-height: 1.4; color: #909399; word-break: break-all; }
.merchant-npc-figure strong { display: block; }
/* 后台列表也按像素放大,免得运营在这看着清楚、上线后玩家看到的是糊的 */
.merchant-npc-avatar {
  width: 48px;
  height: 48px;
  border-radius: 6px;
  object-fit: contain;
  background: #f5f7fa;
  image-rendering: pixelated;
}
.merchant-npc-avatar--none {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c0c4cc;
  font-size: 12px;
}
.merchant-npc-greeting { color: #909399; font-size: 12px; }
.merchant-npc-id { color: #c0c4cc; margin-left: 6px; }
.merchant-npc-readonly { color: #606266; }
.merchant-npc-note { margin: 4px 0 0; color: #909399; font-size: 12px; line-height: 1.5; }
</style>
