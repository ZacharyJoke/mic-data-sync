import { beforeEach } from 'vitest'

class MemoryStorage implements Storage {
  private readonly entries = new Map<string, string>()

  get length() {
    return this.entries.size
  }

  clear() {
    this.entries.clear()
  }

  getItem(key: string) {
    return this.entries.get(key) ?? null
  }

  key(index: number) {
    return [...this.entries.keys()][index] ?? null
  }

  removeItem(key: string) {
    this.entries.delete(key)
  }

  setItem(key: string, value: string) {
    this.entries.set(String(key), String(value))
  }
}

// Node 26 的实验性 WebStorage getter 会遮蔽 jsdom 存储，仅在 Vitest 中补齐浏览器接口。
Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  value: new MemoryStorage(),
})

Object.defineProperty(globalThis, 'sessionStorage', {
  configurable: true,
  value: new MemoryStorage(),
})

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
})
