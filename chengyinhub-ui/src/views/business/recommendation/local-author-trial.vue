<template>
  <el-card shadow="never" class="local-author-trial">
    <div slot="header">本地新作者曝光试验 · 只读合同</div>
    <el-alert title="当前仅展示待确认条件，未执行开通、分流、停止或回滚。实际运行状态尚未核验。" type="info" :closable="false" />
    <el-descriptions :column="1" border>
      <el-descriptions-item label="候选资格">沿用当前内容公开与可推荐资格；试点城市、新作者定义、作者上限和访客参与方式待确认。</el-descriptions-item>
      <el-descriptions-item label="样本与窗口">最低样本量、观察起止时间、归因窗口待确认；样本不足时不得判断有效或扩大试验。</el-descriptions-item>
      <el-descriptions-item label="质量与停止条件">合格曝光、不同作者数、阅读质量、参加结果分别观察；负向门槛与停止负责人待确认。</el-descriptions-item>
      <el-descriptions-item label="实验记录">实验编号、固定分组、曝光记录与指标的对应关系尚未回读。</el-descriptions-item>
      <el-descriptions-item label="停止与回滚">须保留停止时间、原因、操作人及原策略版本，并独立核对实际生效版本；当前未取得停止或回滚回执。</el-descriptions-item>
      <el-descriptions-item label="评分证据边界">本页评分查询结果不能证明本试验已运行、流量已生效或策略已回退。</el-descriptions-item>
    </el-descriptions>
  </el-card>
</template>
<script>
export default { name: 'LocalAuthorTrial' }
</script>
<style scoped>
.local-author-trial { margin-top: 20px; }
.el-descriptions { margin-top: 16px; }
</style>
