<template>
  <div class="template-content-fields">
    <el-alert
      type="info"
      :closable="false"
      title="完整游戏不止基础题型；玩法配置、互动规则、资源槽和素材一起保存在原模板。"
      description="此处维护已有内容。可视化制作请由原作者在小程序「我的模板」续编；这里不会以后台身份代替原作者登录。保存仍走原审核，不能据此认定所有渠道或设备均支持。"
    />
    <p class="template-content-fields__summary">{{ summary }}</p>
    <el-collapse v-model="opened">
      <el-collapse-item v-for="group in groups" :key="group.key" :name="group.key" :title="group.label">
        <template v-for="field in group.fields">
          <div v-if="readOnly" :key="field.key" class="template-content-fields__fact">
            <strong>{{ field.label }}</strong>
            <pre v-if="field.json || field.multiline">{{ text(value[field.key]) || '未配置' }}</pre>
            <p v-else>{{ text(value[field.key]) || '未配置' }}</p>
            <p v-if="errors[field.key]" class="template-content-fields__error" role="alert">{{ errors[field.key] }}；原文已保留。</p>
          </div>
          <el-form-item v-else :key="field.key" :label="field.label" :error="errors[field.key]">
            <el-input-number v-if="field.number" :value="value[field.key]" :precision="0" controls-position="right" :disabled="disabled" @change="setField(field.key, $event)" />
            <el-input
              v-else
              :value="text(value[field.key])"
              :type="field.json || field.multiline ? 'textarea' : 'text'"
              :rows="field.json ? 7 : 3"
              :disabled="disabled"
              :spellcheck="false"
              @input="setField(field.key, $event)"
            />
            <p v-if="field.help" class="template-content-fields__help">{{ field.help }}</p>
            <p v-if="field.json" class="template-content-fields__help">原 JSON 文本完整保存，不自动修复或改写。清空属于显式修改；保存前请核对，最终合法性由原服务端验证。</p>
          </el-form-item>
        </template>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script>
import { contentGroups, contentErrors, contentSummary, contentText } from './template-content'

export default {
  name: 'TemplateContentFields',
  props: {
    value: { type: Object, required: true },
    readOnly: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false }
  },
  data() { return { opened: ['game'] } },
  computed: {
    groups() { return contentGroups },
    errors() { return contentErrors(this.value) },
    summary() { return contentSummary(this.value) }
  },
  methods: {
    text: contentText,
    setField(key, value) {
      if (this.readOnly || this.disabled) return
      if (!contentGroups.some(group => group.fields.some(field => field.key === key))) return
      this.$emit('input', Object.assign({}, this.value, { [key]: value }))
    }
  }
}
</script>

<style lang="scss" scoped>
.template-content-fields { margin-top: var(--cy-space-4); }
.template-content-fields__summary, .template-content-fields__help { color: var(--cy-color-text-secondary); line-height: 1.6; overflow-wrap: anywhere; }
.template-content-fields__help { margin: var(--cy-space-1) 0 0; font-size: 12px; }
.template-content-fields__fact { margin: 0 0 var(--cy-space-4); }
.template-content-fields__fact pre, .template-content-fields__fact p { margin: var(--cy-space-1) 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.template-content-fields__error { color: var(--cy-color-status-danger); }
</style>
