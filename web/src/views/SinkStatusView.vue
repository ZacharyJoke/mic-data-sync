<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <section class="sink-status" data-test="sink-status">
    <div class="sink-status__card">
      <h2>Sink 端总览已移至工作台</h2>
      <p>Sink 访问令牌在「端管理」维护，数据源在「数据源」维护。</p>
      <button type="button" data-test="goto-dashboard" @click="router.push('/')">前往工作台</button>
    </div>
  </section>
</template>

<style scoped>
.sink-status {
  max-width: 560px;
}

.sink-status__card {
  margin-top: 16px;
  padding: 18px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
}

.sink-status__card h2 {
  margin: 0 0 8px;
  font-size: 16px;
}

.sink-status__card p {
  margin: 0 0 14px;
  color: #606266;
}

.sink-status__card button {
  height: 32px;
  padding: 0 14px;
  border: none;
  border-radius: 4px;
  background: #409eff;
  color: #fff;
  cursor: pointer;
}
</style>
