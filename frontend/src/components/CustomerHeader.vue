<script setup lang="ts">
defineProps<{
  activePage: string
  cartCount: number
  language: string
  t: (text: string) => string
}>()

const emit = defineEmits<{
  navigate: [page: string]
  signOut: []
  changeLanguage: []
  'update:language': [language: string]
}>()
</script>

<template>
  <header class="customer-header">
    <button class="brand brand-button" type="button" @click="emit('navigate', 'products')">
      <img src="/ytpeps-logo.svg" alt="" class="brand-logo">
      <span class="brand-name">YTPEPS</span>
    </button>
    <nav class="customer-nav" :aria-label="t('Customer navigation')">
      <button type="button" :class="{ active: activePage === 'products' }" :aria-current="activePage === 'products' ? 'page' : undefined" @click="emit('navigate', 'products')">
        <svg class="nav-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>{{ t('Product') }}
      </button>
      <button type="button" :class="{ active: activePage === 'certificates' }" :aria-current="activePage === 'certificates' ? 'page' : undefined" @click="emit('navigate', 'certificates')">
        <svg class="nav-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M6 3.5h8l4 4V20.5H6z"/><path d="M14 3.5v4h4M9 12h6M9 16h6"/></svg>{{ t('View COAs') }}
      </button>
      <button type="button" :class="{ active: activePage === 'account' }" :aria-current="activePage === 'account' ? 'page' : undefined" @click="emit('navigate', 'account')">
        <svg class="nav-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="12" cy="8" r="3.5"/><path d="M5 20c.8-3.3 3.1-5 7-5s6.2 1.7 7 5"/></svg>{{ t('My Account') }}
      </button>
      <button type="button" :class="{ active: activePage === 'cart' }" :aria-current="activePage === 'cart' ? 'page' : undefined" @click="emit('navigate', 'cart')">
        <svg class="nav-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M4 5h2l1.5 10.5h10L20 8H7"/><circle cx="9.5" cy="19" r="1"/><circle cx="17" cy="19" r="1"/></svg>{{ t('Cart') }}<span v-if="cartCount" class="nav-count">{{ cartCount }}</span>
      </button>
      <button type="button" :class="{ active: activePage === 'orders' }" :aria-current="activePage === 'orders' ? 'page' : undefined" @click="emit('navigate', 'orders')">
        <svg class="nav-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="12" cy="12" r="8.5"/><path d="M12 7v5l3 2"/></svg>{{ t('Order History') }}
      </button>
      <button class="signout-btn" type="button" @click="emit('signOut')">
        <svg class="nav-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M10 4H5v16h5M14 8l4 4-4 4M9 12h9"/></svg>{{ t('Sign out') }}
      </button>
      <label class="language-picker">
        <span class="sr-only">{{ t('Language') }}</span>
        <svg class="language-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="12" cy="12" r="8.5"/><path d="M3.8 12h16.4M12 3.5c2.1 2.3 3.1 5.1 3.1 8.5s-1 6.2-3.1 8.5M12 3.5c-2.1 2.3-3.1 5.1-3.1 8.5s1 6.2 3.1 8.5"/></svg>
        <select autocomplete="off" :value="language" @change="emit('update:language', ($event.target as HTMLSelectElement).value); emit('changeLanguage')">
          <option value="en">EN</option>
          <option value="zh-CN">中文</option>
        </select>
        <svg class="language-chevron" viewBox="0 0 16 16" aria-hidden="true"><path d="m4 6 4 4 4-4"/></svg>
      </label>
    </nav>
  </header>
</template>
