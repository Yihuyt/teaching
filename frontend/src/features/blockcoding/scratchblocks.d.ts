declare module 'scratchblocks/browser.es.js' {
  interface ScratchblocksOptions {
    languages?: string[]
    style?: string
    scale?: number
  }

  interface SbInput {
    shape: string
    value: string
    hasArrow: boolean
    label: unknown
  }

  interface SbBlock {
    info: { id: string | null }
    children: unknown[]
  }

  interface SbScript {
    blocks: unknown[]
  }

  interface SbDocument {
    scripts: SbScript[]
    /** 把积木文字换成另一种语言(下拉的值不翻) */
    translate(language: unknown): void
    stringify(): string
  }

  interface Scratchblocks {
    parse(code: string, options?: ScratchblocksOptions): SbDocument
    render(doc: SbDocument, options?: ScratchblocksOptions): SVGElement
    appendStyles(): void
    loadLanguages(languages: Record<string, unknown>): void
    readonly allLanguages: Record<string, unknown>
    Label: new (value: string, cls?: string) => unknown
    Input: new (shape: string, value: string, menu?: string | null) => SbInput
    Block: new (info: unknown, children: unknown[], comment?: unknown) => SbBlock
    Script: new (blocks: unknown[]) => SbScript
  }

  const scratchblocks: Scratchblocks
  export default scratchblocks
}

declare module 'scratchblocks/locales/zh-cn.json' {
  const language: Record<string, unknown>
  export default language
}
