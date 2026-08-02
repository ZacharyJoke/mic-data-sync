<script setup lang="ts">
import { SwitchButton } from '@element-plus/icons-vue'
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { navigationItems, type NavigationItem } from '@/app/navigation'
import { useSessionStore } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const pageTitle = computed(() =>
  typeof route.meta.title === 'string' ? route.meta.title : 'MIC 数据同步',
)

function isNavigationItemActive(item: NavigationItem): boolean {
  if (item.exact) {
    return route.path === item.to
  }
  return route.path === item.to || route.path.startsWith(`${item.to}/`)
}

/** 退出登录：清空会话并跳转登录页。 */
function handleLogout() {
  session.clear()
  void router.push({ path: '/login' })
}
</script>

<template>
  <div class="main-layout" data-test="main-layout">
    <a class="main-layout__skip-link" href="#main-content">跳到主内容</a>

    <aside class="main-layout__sidebar">
      <div class="main-layout__brand">
        <span class="main-layout__brand-mark" aria-hidden="true">M</span>
        <span class="main-layout__brand-name">MIC 数据同步</span>
      </div>

      <nav class="main-layout__nav" aria-label="主导航">
        <router-link
          v-for="item in navigationItems"
          :key="item.to"
          v-slot="{ href, navigate }"
          :to="item.to"
          custom
        >
          <a
            :href="href"
            class="main-layout__nav-item"
            :class="{
              'main-layout__nav-item--active': isNavigationItemActive(item),
            }"
            :aria-current="isNavigationItemActive(item) ? 'page' : undefined"
            :aria-label="item.label"
            :title="item.label"
            data-test="primary-nav-item"
            @click="navigate"
          >
            <component :is="item.icon" class="main-layout__nav-icon" aria-hidden="true" />
            <span class="main-layout__nav-label">{{ item.label }}</span>
          </a>
        </router-link>
      </nav>
    </aside>

    <div class="main-layout__workspace">
      <header class="main-layout__topbar">
        <div class="main-layout__mobile-brand">
          <span class="main-layout__brand-mark" aria-hidden="true">M</span>
          <span>MIC 数据同步</span>
        </div>
        <strong class="main-layout__page-title">{{ pageTitle }}</strong>
        <div class="main-layout__user">
          <span class="main-layout__username">
            {{ session.user?.displayName ?? session.user?.username }}
          </span>
          <button
            type="button"
            class="main-layout__logout"
            aria-label="退出登录"
            title="退出登录"
            data-test="logout-button"
            @click="handleLogout"
          >
            <SwitchButton aria-hidden="true" />
            <span>退出</span>
          </button>
        </div>
      </header>

      <nav class="main-layout__mobile-nav" aria-label="主导航">
        <router-link
          v-for="item in navigationItems"
          :key="item.to"
          v-slot="{ href, navigate }"
          :to="item.to"
          custom
        >
          <a
            :href="href"
            class="main-layout__mobile-nav-item"
            :class="{
              'main-layout__mobile-nav-item--active': isNavigationItemActive(item),
            }"
            :aria-current="isNavigationItemActive(item) ? 'page' : undefined"
            :aria-label="item.label"
            @click="navigate"
          >
            <component :is="item.icon" aria-hidden="true" />
            <span>{{ item.label }}</span>
          </a>
        </router-link>
      </nav>

      <main id="main-content" class="main-layout__content" tabindex="-1">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.main-layout {
  display: flex;
  min-height: 100vh;
  background: var(--mic-page);
}

.main-layout__skip-link {
  position: fixed;
  z-index: 100;
  top: 8px;
  left: 8px;
  padding: 9px 12px;
  border-radius: var(--mic-radius);
  color: var(--mic-nav-text);
  background: var(--mic-primary);
  font-weight: 600;
  text-decoration: none;
  transform: translateY(-160%);
  transition: transform var(--mic-transition);
}

.main-layout__skip-link:focus {
  transform: translateY(0);
}

.main-layout__sidebar {
  position: fixed;
  z-index: 20;
  inset: 0 auto 0 0;
  display: flex;
  width: 224px;
  flex-direction: column;
  color: var(--mic-page);
  background: var(--mic-nav);
}

.main-layout__brand {
  display: flex;
  height: 64px;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  border-bottom: 1px solid rgb(255 255 255 / 10%);
  font-weight: 650;
}

.main-layout__brand-mark {
  display: inline-flex;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 32%);
  border-radius: var(--mic-radius);
  color: var(--mic-nav-text);
  background: var(--mic-primary);
  font-size: 15px;
  font-weight: 750;
}

.main-layout__brand-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-layout__nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 10px;
}

.main-layout__nav-item {
  position: relative;
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 12px;
  padding: 0 12px;
  border-radius: var(--mic-radius);
  color: var(--mic-nav-text-muted);
  text-decoration: none;
  transition:
    color var(--mic-transition),
    background-color var(--mic-transition);
}

.main-layout__nav-item:hover {
  color: var(--mic-nav-text);
  background: var(--mic-nav-hover);
}

.main-layout__nav-item--active {
  color: var(--mic-nav-text);
  background: var(--mic-primary);
  font-weight: 600;
}

.main-layout__nav-item--active::before {
  position: absolute;
  inset: 10px auto 10px -10px;
  width: 3px;
  background: var(--mic-nav-accent);
  content: "";
}

.main-layout__nav-icon {
  width: 19px;
  height: 19px;
  flex: 0 0 19px;
}

.main-layout__workspace {
  display: flex;
  min-width: 0;
  min-height: 100vh;
  flex: 1;
  flex-direction: column;
  margin-left: 224px;
}

.main-layout__topbar {
  position: sticky;
  z-index: 10;
  top: 0;
  display: flex;
  height: 64px;
  flex: 0 0 64px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 24px;
  border-bottom: 1px solid var(--mic-border);
  background: rgb(255 255 255 / 96%);
  box-shadow: var(--mic-shadow);
}

.main-layout__page-title {
  min-width: 0;
  overflow: hidden;
  font-size: 15px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-layout__mobile-brand,
.main-layout__mobile-nav {
  display: none;
}

.main-layout__user {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.main-layout__username {
  max-width: 180px;
  overflow: hidden;
  color: var(--mic-text-secondary);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-layout__logout {
  display: inline-flex;
  min-width: 44px;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 10px;
  border: 1px solid transparent;
  border-radius: var(--mic-radius);
  color: var(--mic-text-secondary);
  background: transparent;
  cursor: pointer;
  transition:
    color var(--mic-transition),
    border-color var(--mic-transition),
    background-color var(--mic-transition);
}

.main-layout__logout svg {
  width: 17px;
  height: 17px;
}

.main-layout__logout:hover {
  border-color: var(--mic-border);
  color: var(--mic-danger);
  background: var(--mic-danger-soft);
}

.main-layout__content {
  width: 100%;
  min-width: 0;
  flex: 1;
  padding: 20px 24px 28px;
}

@media (max-width: 1023px) and (min-width: 768px) {
  .main-layout__sidebar {
    width: 72px;
  }

  .main-layout__brand {
    justify-content: center;
    padding: 0;
  }

  .main-layout__brand-name,
  .main-layout__nav-label {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
    clip-path: inset(50%);
  }

  .main-layout__nav {
    padding: 12px 8px;
  }

  .main-layout__nav-item {
    justify-content: center;
    padding: 0;
  }

  .main-layout__nav-item--active::before {
    left: -8px;
  }

  .main-layout__workspace {
    margin-left: 72px;
  }

  .main-layout__topbar {
    padding: 0 20px;
  }

  .main-layout__content {
    padding: 18px 20px 24px;
  }
}

@media (max-width: 767px) {
  .main-layout {
    display: block;
  }

  .main-layout__sidebar,
  .main-layout__page-title {
    display: none;
  }

  .main-layout__workspace {
    min-height: 100vh;
    margin-left: 0;
  }

  .main-layout__topbar {
    height: 56px;
    flex-basis: 56px;
    padding: 0 12px;
  }

  .main-layout__mobile-brand {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    font-weight: 650;
    white-space: nowrap;
  }

  .main-layout__mobile-brand .main-layout__brand-mark {
    width: 28px;
    height: 28px;
    flex-basis: 28px;
  }

  .main-layout__username {
    display: none;
  }

  .main-layout__logout {
    padding: 0;
  }

  .main-layout__logout span {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
    clip-path: inset(50%);
  }

  .main-layout__mobile-nav {
    position: sticky;
    z-index: 9;
    top: 56px;
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    border-bottom: 1px solid var(--mic-border);
    background: var(--mic-surface);
    box-shadow: var(--mic-shadow);
  }

  .main-layout__mobile-nav-item {
    position: relative;
    display: flex;
    min-width: 0;
    min-height: 56px;
    align-items: center;
    justify-content: center;
    flex-direction: column;
    gap: 3px;
    padding: 5px 2px;
    color: var(--mic-text-secondary);
    font-size: 11px;
    line-height: 1.2;
    text-align: center;
    text-decoration: none;
    transition:
      color var(--mic-transition),
      background-color var(--mic-transition);
  }

  .main-layout__mobile-nav-item svg {
    width: 18px;
    height: 18px;
    flex: 0 0 18px;
  }

  .main-layout__mobile-nav-item:hover,
  .main-layout__mobile-nav-item--active {
    color: var(--mic-primary);
    background: var(--mic-primary-soft);
  }

  .main-layout__mobile-nav-item--active::after {
    position: absolute;
    inset: auto 8px 0;
    height: 3px;
    background: var(--mic-primary);
    content: "";
  }

  .main-layout__content {
    padding: 14px 12px 20px;
  }
}
</style>
