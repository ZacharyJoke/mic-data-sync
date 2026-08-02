<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useSessionStore } from '@/stores/session'

const router = useRouter()
const route = useRoute()
const session = useSessionStore()

const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMessage = ref('')

onMounted(async () => {
  // 登录前先获取 CSRF Token（写入 Cookie），保证登录 POST 可通过校验
  try {
    await session.ensureCsrfToken()
  } catch {
    // 后端不可达时忽略，登录时会再次报错
  }
})

async function handleLogin() {
  const name = username.value.trim()
  if (!name || !password.value) {
    errorMessage.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    await session.login(name, password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    void router.push(redirect)
  } catch {
    errorMessage.value = '用户名或密码错误'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-view" data-test="login-view">
    <form class="login-view__form" @submit.prevent="handleLogin">
      <h1 class="login-view__title">MIC 数据同步</h1>
      <p class="login-view__subtitle">请登录后继续使用</p>
      <label class="login-view__field">
        用户名
        <input v-model="username" type="text" data-test="username" autocomplete="username" />
      </label>
      <label class="login-view__field">
        密码
        <input v-model="password" type="password" data-test="password" autocomplete="current-password" />
      </label>
      <p v-if="errorMessage" class="login-view__error" data-test="login-error">{{ errorMessage }}</p>
      <button type="submit" data-test="login-submit" :disabled="loading">
        {{ loading ? '登录中…' : '登 录' }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.login-view {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}

.login-view__form {
  width: 320px;
  padding: 32px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.login-view__title {
  margin: 0;
  font-size: 20px;
  text-align: center;
  color: #303133;
}

.login-view__subtitle {
  margin: 0 0 8px;
  text-align: center;
  color: #909399;
  font-size: 13px;
}

.login-view__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: #606266;
}

.login-view__field input {
  height: 36px;
  padding: 0 12px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 14px;
}

.login-view__form button {
  height: 38px;
  border: none;
  border-radius: 4px;
  background: #409eff;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}

.login-view__form button:disabled {
  background: #a0cfff;
  cursor: not-allowed;
}

.login-view__error {
  margin: 0;
  color: #f56c6c;
  font-size: 13px;
}
</style>
