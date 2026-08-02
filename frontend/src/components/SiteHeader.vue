<script setup lang="ts">
import { useRouter } from 'vue-router'

withDefaults(defineProps<{
  maxWidth?: string
  elevated?: boolean
  overlay?: boolean
}>(), {
  maxWidth: '1360px',
  elevated: true,
  overlay: false
})

const router = useRouter()
</script>

<template>
  <header class="site-header" :class="{ elevated, overlay }">
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

.site-header.overlay {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  border-bottom-color: rgb(255 255 255 / 16%);
  background: linear-gradient(180deg, rgb(2 8 23 / 62%), rgb(2 8 23 / 10%));
  box-shadow: none;
  color: #fff;
  backdrop-filter: none;
}

.site-header.overlay .site-brand {
  color: #fff;
  text-shadow: 0 1px 6px rgb(0 0 0 / 35%);
}

.site-header.overlay .site-brand__mark {
  background: linear-gradient(135deg, #22c3f5, #00aeec);
  box-shadow: 0 5px 18px rgb(0 0 0 / 22%);
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

.site-header.overlay :slotted(.site-nav-link) {
  border-color: transparent;
  background: rgb(15 23 42 / 28%);
  color: #fff;
  text-shadow: 0 1px 5px rgb(0 0 0 / 38%);
  backdrop-filter: blur(8px);
}

.site-header.overlay :slotted(.site-nav-link:hover) {
  border-color: rgb(255 255 255 / 32%);
  background: rgb(255 255 255 / 20%);
  color: #fff;
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
