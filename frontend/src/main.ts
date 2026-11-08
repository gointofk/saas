import { createApp } from 'vue'
import App from './App.vue'
import PlatformApp from './PlatformApp.vue'
import { loadStorefront } from './lib/storefront'
import './styles.css'

function showStartupError(root: HTMLElement) {
  root.className = 'startup-state startup-error'
  root.setAttribute('aria-busy', 'false')

  const card = document.createElement('section')
  card.className = 'startup-card'
  card.setAttribute('role', 'alert')

  const mark = document.createElement('div')
  mark.className = 'startup-mark'
  mark.setAttribute('aria-hidden', 'true')
  mark.innerHTML = '<svg viewBox="0 0 24 24"><path d="M12 8v4m0 4h.01" /><circle cx="12" cy="12" r="9" /></svg>'

  const title = document.createElement('h1')
  title.textContent = 'Unable to connect to your store'

  const message = document.createElement('p')
  message.textContent = 'Please check your connection and try again.'

  const retry = document.createElement('button')
  retry.className = 'startup-retry'
  retry.type = 'button'
  retry.textContent = 'Try again'
  retry.addEventListener('click', () => location.reload())

  card.append(mark, title, message, retry)
  root.replaceChildren(card)
}

async function start() {
  const root = document.querySelector<HTMLElement>('#app')
  if (!root) return

  const platform=/^\/platform(?:\/|$)/.test(location.pathname)
  const admin = /^\/admin(?:\/|$)/.test(location.pathname)
  if (admin) {
    // A merchant hostname can brand the seller login page. Shared admin hosts
    // may not resolve to a storefront, so this lookup must remain optional.
    void loadStorefront().catch(() => {})
  } else if (!platform) {
    try { await loadStorefront() }
    catch {
      showStartupError(root)
      return
    }
  }

  root.classList.remove('startup-state')
  root.removeAttribute('aria-busy')
  createApp(platform ? PlatformApp : App).mount(root)
}
void start()
