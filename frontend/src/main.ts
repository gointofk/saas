import { createApp } from 'vue'
import App from './App.vue'
import PlatformApp from './PlatformApp.vue'
import { loadStorefront } from './lib/storefront'
import './styles.css'

async function start() {
  const platform=/^\/platform(?:\/|$)/.test(location.pathname)
  if (!platform && !/^\/admin(?:\/|$)/.test(location.pathname)) {
    document.querySelector('#app')!.textContent='正在加载商城…'
    try { await loadStorefront() }
    catch (e) {
      const root=document.querySelector('#app')!
      root.textContent='商城暂不可用。'+(e instanceof Error ? e.message : '')
      const retry=document.createElement('button'); retry.textContent='重新加载'; retry.onclick=()=>location.reload(); root.appendChild(retry)
      return
    }
  }
  createApp(platform ? PlatformApp : App).mount('#app')
}
void start()
