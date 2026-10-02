/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<object, object, unknown>
  export default component
}

declare module 'katex/contrib/auto-render' {
  import type { KatexOptions } from 'katex'

  interface AutoRenderDelimiter {
    left: string
    right: string
    display: boolean
  }

  interface AutoRenderOptions extends KatexOptions {
    delimiters?: AutoRenderDelimiter[]
    ignoredTags?: string[]
    ignoredClasses?: string[]
    errorCallback?: (message: string, error: Error) => void
  }

  export default function renderMathInElement(element: HTMLElement, options?: AutoRenderOptions): void
}
