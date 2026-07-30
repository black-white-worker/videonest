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
  height: 72px;
  border-bottom: 1px solid #cbd5e1;
  background: rgb(255 255 255 / 98%);
  backdrop-filter: blur(18px) saturate(150%);
}

.site-header.elevated {
  position: sticky;
  top: 0;
  box-shadow: 0 3px 14px rgb(15 23 42 / 10%);
}

.site-header__inner {
  width: min(var(--header-max-width), calc(100% - 48px));
  height: 100%;
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: 28px;
}

.site-brand {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
  padding: 5px 8px;
  border-radius: 12px;
  border: 0;
  background: transparent;
  color: var(--vn-text);
  cursor: pointer;
}

.site-brand__mark {
  width: 40px;
  height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: linear-gradient(135deg, #0284c7, #38bdf8);
  box-shadow: 0 5px 14px rgb(2 132 199 / 30%);
  color: #fff;
  font-size: 0;
}

.site-brand__mark::before {
  content: '';
  width: 0;
  height: 0;
  margin-left: 3px;
  border-top: 8px solid transparent;
  border-bottom: 8px solid transparent;
  border-left: 12px solid #fff;
}

.site-brand__name {
  font-size: 22px;
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
  padding: 9px 14px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: #f8fafc;
  color: #1e293b;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  transition: color .2s, background .2s;
}

:slotted(.site-nav-link:hover) {
  border-color: #7dd3fc;
  background: #e0f2fe;
  color: #075985;
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
    height: 62px;
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
