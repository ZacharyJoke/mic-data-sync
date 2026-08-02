<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
// 展示路由 meta 中配置的页面标题；缺省时使用通用文案
const title = typeof route.meta.title === 'string' ? route.meta.title : '功能'
</script>

<template>
  <section class="feature-in-progress" data-test="feature-in-progress">
    <h2>{{ title }}</h2>
    <p class="feature-in-progress__hint">功能建设中，敬请期待。</p>
  </section>
</template>

<style scoped>
.feature-in-progress__hint {
  color: #909399;
}
</style>
