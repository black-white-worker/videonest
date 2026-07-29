<script setup lang="ts">
import { useRouter } from 'vue-router'

withDefaults(defineProps<{
  maxWidth?: string
  elevated?: boolean
}>(), {
  maxWidth: '1360px',
  elevated: true
})

const router = useRouter()
</script>

<template>
  <header class="site-header" :class="{ elevated }">
    <div class="site-header__inner" :style="{ '--header-max-width': maxWidth }">
      <button class="site-brand" aria-label="返回首页" @click="router.push('/')">
        <span class="site-brand__mark">▶</span>
        <span class="site-brand__name">VideoNest</span>
      </button>

      <nav v-if="$slots.nav" class="site-header__nav" aria-label="主导航">
        <slot name="nav" />
      </nav>

      <div v-if="$slots.search" class="site-header__search">
        <slot name="search" />
      </div>

      <div class="site-header__actions">
        <slot name="actions" />
      </div>
    </div>
  </header>
</template>

<style scoped>
.site-header {
  position: relative;
  z-index: 30;
  height: 64px;
  border-bottom: 1px solid rgb(0 0 0 / 6%);
  background: rgb(255 255 255 / 92%);
  backdrop-filter: blur(18px) saturate(150%);
}

.site-header.elevated {
  position: sticky;
  top: 0;
  box-shadow: 0 1px 8px rgb(0 0 0 / 4%);
}

.site-header__inner {
  width: min(var(--header-max-width), calc(100% - 48px));
  height: 100%;
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: 24px;
}

.site-brand {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 9px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--vn-text);
  cursor: pointer;
}

.site-brand__mark {
  width: 34px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 11px;
  background: linear-gradient(135deg, var(--vn-primary), #5ed8ff);
  box-shadow: 0 6px 16px rgb(0 174 236 / 24%);
  color: #fff;
  font-size: 13px;
}

.site-brand__name {
  font-size: 20px;
  font-weight: 800;
  letter-spacing: -.5px;
}

.site-header__nav {
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.site-header__search {
  min-width: 180px;
  max-width: 520px;
  flex: 1 1 420px;
  margin: 0 auto;
}

.site-header__actions {
  min-width: 0;
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

:slotted(.site-nav-link) {
  padding: 8px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--vn-text-secondary);
  font-size: 14px;
  cursor: pointer;
  transition: color .2s, background .2s;
}

:slotted(.site-nav-link:hover) {
  background: var(--vn-primary-soft);
  color: var(--vn-primary-dark);
}

@media (max-width: 900px) {
  .site-header__inner {
    width: min(var(--header-max-width), calc(100% - 28px));
    gap: 12px;
  }

  .site-header__nav {
    display: none;
  }
}

@media (max-width: 620px) {
  .site-header {
    height: 58px;
  }

  .site-brand__name {
    display: none;
  }

  .site-header__search {
    min-width: 0;
    flex-basis: auto;
  }

  .site-header__actions {
    gap: 4px;
  }
}
</style>
