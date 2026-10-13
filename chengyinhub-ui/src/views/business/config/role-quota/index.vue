<template>
  <div class="app-container cy-domain-page role-quota-config">
    <cy-page-header
      eyebrow="数据与配置"
      title="角色配额"
      description="按角色设置主题、活动和优惠券的创建上限及基础发布能力；配置作用于整个角色，不是单个用户。"
    />

    <p class="role-quota-config__boundary">
      当前接口只返回角色级配置，不包含当前使用量；本页不会推算或伪造占用统计。
    </p>

    <cy-resource-state
      v-if="loadError"
      kind="error"
      title="角色配额暂时无法读取"
      description="没有改动任何配置。请重试；若仍失败，请核对角色配额读取权限和服务状态。"
      retry-text="重新读取"
      :retrying="loading"
      @retry="getList"
    />

    <template v-else>
      <cy-empty-state
        v-if="!loading && permissionList.length === 0"
        title="尚未配置角色配额"
        description="角色能力不会自动补全。请先核对生产配置数据后再处理。"
      />

      <el-row v-else v-loading="loading" :gutter="16" class="role-quota-config__grid">
        <el-col v-for="row in permissionList" :key="row.id || row.role" :xs="24" :md="12" :xl="8">
          <section class="role-quota-config__card">
            <header class="role-quota-config__card-header">
              <div>
                <p class="role-quota-config__eyebrow">影响角色</p>
                <h2>{{ roleLabel(row.role) }}</h2>
                <p>{{ roleDescription(row.role) }}</p>
              </div>
              <span class="role-quota-config__role">{{ row.role || '未命名' }}</span>
            </header>

            <dl class="role-quota-config__metrics">
              <div>
                <dt>主题上限</dt>
                <dd>{{ quotaLabel(row.maxThemes) }}</dd>
              </div>
              <div>
                <dt>活动上限</dt>
                <dd>{{ quotaLabel(row.maxEvents) }}</dd>
              </div>
              <div>
                <dt>优惠券上限</dt>
                <dd>{{ quotaLabel(row.maxCoupons) }}</dd>
              </div>
            </dl>

            <div class="role-quota-config__capabilities" aria-label="基础能力">
              <span :class="capabilityClass(row.canCreateTheme)"><i aria-hidden="true" />创建主题：{{ capabilityLabel(row.canCreateTheme) }}</span>
              <span :class="capabilityClass(row.canCreateEvent)"><i aria-hidden="true" />创建活动：{{ capabilityLabel(row.canCreateEvent) }}</span>
              <span :class="capabilityClass(row.canPublishCoupon)"><i aria-hidden="true" />发布优惠券：{{ capabilityLabel(row.canPublishCoupon) }}</span>
            </div>

            <footer>
              <el-button
                type="text"
                size="small"
                icon="el-icon-edit-outline"
                v-hasPermi="['business:userpermission:edit']"
                @click="openEdit(row)"
              >调整配额</el-button>
            </footer>
          </section>
        </el-col>
      </el-row>
    </template>

    <el-dialog
      :title="`调整${roleLabel(editForm.role)}配额`"
      :visible.sync="editOpen"
      width="520px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form ref="quotaForm" :model="editForm" :rules="quotaRules" label-width="108px">
        <el-form-item label="影响角色">
          <span>{{ roleLabel(editForm.role) }}</span>
        </el-form-item>
        <el-form-item label="主题上限" prop="maxThemes">
          <el-input-number v-model="editForm.maxThemes" :min="0" :max="99" :precision="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="活动上限" prop="maxEvents">
          <el-input-number v-model="editForm.maxEvents" :min="0" :max="99" :precision="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="优惠券上限" prop="maxCoupons">
          <el-input-number v-model="editForm.maxCoupons" :min="0" :max="99" :precision="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="允许创建主题">
          <el-switch v-model="editForm.canCreateTheme" active-text="允许" inactive-text="不允许" />
        </el-form-item>
        <el-form-item label="允许创建活动">
          <el-switch v-model="editForm.canCreateEvent" active-text="允许" inactive-text="不允许" />
        </el-form-item>
        <el-form-item label="允许发布优惠券">
          <el-switch v-model="editForm.canPublishCoupon" active-text="允许" inactive-text="不允许" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button :disabled="saving" @click="editOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePermission">保存配额</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listPermission, updatePermission } from '@/api/business/userpermission'
import CyEmptyState from '@/components/CyEmptyState'
import CyPageHeader from '@/components/CyPageHeader'
import CyResourceState from '@/components/CyResourceState'

export default {
  name: 'RoleQuotaConfig',
  components: { CyEmptyState, CyPageHeader, CyResourceState },
  data() {
    const validateQuota = (rule, value, callback) => {
      if (value == null) {
        callback()
        return
      }
      if (!Number.isInteger(value) || value < 0 || value > 99) {
        callback(new Error('请输入 0 到 99 的整数'))
        return
      }
      callback()
    }

    return {
      loading: false,
      loadError: false,
      saving: false,
      editOpen: false,
      requestToken: 0,
      permissionList: [],
      editForm: this.emptyForm(),
      quotaRules: {
        maxThemes: [{ validator: validateQuota, trigger: 'change' }],
        maxEvents: [{ validator: validateQuota, trigger: 'change' }],
        maxCoupons: [{ validator: validateQuota, trigger: 'change' }]
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    emptyForm() {
      return {
        id: null,
        role: '',
        maxThemes: 0,
        maxEvents: 0,
        maxCoupons: 0,
        canCreateTheme: false,
        canCreateEvent: false,
        canPublishCoupon: false
      }
    },
    getList() {
      const requestToken = ++this.requestToken
      this.loading = true
      this.loadError = false
      return listPermission().then(response => {
        if (requestToken !== this.requestToken) return null
        this.permissionList = (response.data || []).map(row => this.normalizeRow(row))
        return true
      }).catch(() => {
        if (requestToken !== this.requestToken) return null
        this.permissionList = []
        this.loadError = true
        return false
      }).then(result => {
        if (requestToken === this.requestToken) this.loading = false
        return result
      })
    },
    normalizeRow(row) {
      return Object.assign({}, row, {
        canCreateTheme: this.toBoolean(row.canCreateTheme),
        canCreateEvent: this.toBoolean(row.canCreateEvent),
        canPublishCoupon: this.toBoolean(row.canPublishCoupon)
      })
    },
    toBoolean(value) {
      return value === true || value === 1 || value === '1'
    },
    openEdit(row) {
      this.editForm = {
        id: row.id,
        role: row.role || '',
        maxThemes: this.quotaFormValue(row.maxThemes),
        maxEvents: this.quotaFormValue(row.maxEvents),
        maxCoupons: this.quotaFormValue(row.maxCoupons),
        canCreateTheme: this.toBoolean(row.canCreateTheme),
        canCreateEvent: this.toBoolean(row.canCreateEvent),
        canPublishCoupon: this.toBoolean(row.canPublishCoupon)
      }
      this.editOpen = true
      this.$nextTick(() => {
        if (this.$refs.quotaForm) this.$refs.quotaForm.clearValidate()
      })
    },
    quotaFormValue(value) {
      return value == null || value === '' ? null : Number(value)
    },
    toUpdatePayload(form) {
      return {
        id: form.id,
        maxThemes: form.maxThemes,
        maxEvents: form.maxEvents,
        maxCoupons: form.maxCoupons,
        canCreateTheme: form.canCreateTheme,
        canCreateEvent: form.canCreateEvent,
        canPublishCoupon: form.canPublishCoupon
      }
    },
    savePermission() {
      this.$refs.quotaForm.validate(valid => {
        if (!valid || this.saving) return
        this.saving = true
        updatePermission(this.toUpdatePayload(this.editForm))
          .then(() => {
            this.$modal.msgSuccess('角色配额已保存')
            this.editOpen = false
            return this.getList()
          })
          .then(reloaded => {
            if (reloaded === false) this.$modal.msgWarning('角色配额已保存，但列表刷新失败，请稍后重新读取')
          })
          .catch(() => {
            this.$modal.msgError('保存失败，请核对配额后重试')
          })
          .then(() => {
            this.saving = false
          })
      })
    },
    roleLabel(role) {
      return { player: '玩家', club: '俱乐部', merchant: '商家' }[role] || role || '未命名角色'
    },
    roleDescription(role) {
      return {
        player: '玩家的基础创建与发布能力',
        club: '俱乐部的组织与运营能力',
        merchant: '商家的供给与优惠券能力'
      }[role] || '当前角色级配置'
    },
    quotaLabel(value) {
      return value == null ? '不限' : `${value} 个`
    },
    capabilityLabel(value) {
      return this.toBoolean(value) ? '允许' : '不允许'
    },
    capabilityClass(value) {
      return this.toBoolean(value)
        ? 'role-quota-config__capability role-quota-config__capability--enabled'
        : 'role-quota-config__capability'
    }
  }
}
</script>

<style lang="scss" scoped>
.role-quota-config__boundary { margin: -var(--cy-space-1-5) 0 20px; color: var(--cy-color-text-secondary); font-size: 13px; line-height: 1.65; }
.role-quota-config__grid { margin-top: 4px; }
.role-quota-config__grid > [class*='el-col'] { margin-bottom: 16px; }
.role-quota-config__card { min-height: 298px; padding: 20px; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); box-shadow: var(--cy-shadow-card); }
.role-quota-config__card-header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cy-space-2-5); }
.role-quota-config__eyebrow { margin: 0 0 var(--cy-legacy-5); color: var(--cy-color-text-tertiary); font-size: 12px; font-weight: 700; letter-spacing: .04em; }
.role-quota-config__card h2 { margin: 0; color: var(--cy-color-text-primary); font-size: 19px; line-height: 1.4; }
.role-quota-config__card-header > div > p:last-child { margin: var(--cy-legacy-5) 0 0; color: var(--cy-color-text-secondary); font-size: 13px; line-height: 1.55; }
.role-quota-config__role { flex: none; padding: var(--cy-legacy-3) 8px; border: 1px solid var(--cy-color-border-subtle); border-radius: 999px; color: var(--cy-color-text-secondary); font-size: 12px; line-height: 1.4; }
.role-quota-config__metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin: 20px 0; }
.role-quota-config__metrics > div { padding: var(--cy-space-1-5); border-radius: var(--cy-radius-control); background: var(--cy-color-bg-subtle); }
.role-quota-config__metrics dt { color: var(--cy-color-text-tertiary); font-size: 12px; line-height: 1.4; }
.role-quota-config__metrics dd { margin: var(--cy-legacy-5) 0 0; color: var(--cy-color-text-primary); font-size: 17px; font-weight: 700; line-height: 1.3; }
.role-quota-config__capabilities { display: grid; gap: 7px; }
.role-quota-config__capability { display: flex; align-items: center; color: var(--cy-color-text-secondary); font-size: 13px; line-height: 1.45; }
.role-quota-config__capability i { width: 7px; height: 7px; margin-right: 8px; border: 1px solid var(--cy-color-text-tertiary); border-radius: 50%; }
.role-quota-config__capability--enabled { color: var(--cy-color-text-primary); }
.role-quota-config__capability--enabled i { border-color: var(--cy-color-text-primary); background: var(--cy-color-text-primary); }
.role-quota-config__card footer { display: flex; justify-content: flex-end; margin-top: var(--cy-space-3-5); }

@media (max-width: 540px) {
  .role-quota-config__card { min-height: 0; }
  .role-quota-config__metrics { gap: var(--cy-space-0-5); }
  .role-quota-config__metrics > div { padding: 8px; }
}
</style>
