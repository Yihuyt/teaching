/**
 * 积木创作台编辑器 iframe 的通信桥客户端。
 * 协议与 scratch-editor 仓库 src/lib/teaching-bridge/protocol.js 同构，两侧同步修改。
 * 同源部署（编辑器经 nginx 反代在 /scratch/ 路径），双向严格校验 origin。
 */

const CHANNEL = 'teaching-scratch-bridge'
const VERSION = 1
const REQUEST_TIMEOUT_MS = 10_000
const EXPORT_TIMEOUT_MS = 30_000

export interface SpriteContext {
  name: string
  isStage: boolean
  costumes: string[]
  sounds: string[]
  localVariables: string[]
  localLists: string[]
}

export interface ProcedureSignature {
  proccode: string
  argumentnames: string[]
  argumentids: string[]
  argumentdefaults: string[]
}

interface OverlayRect {
  x: number
  y: number
  width: number
  height: number
}

export interface DraggedStack {
  sprite: string
  blockId: string
  xml: string
  x: number
  y: number
}

interface BlockDragHandlers {
  outside: (outside: boolean, x: number, y: number) => void
  draggedOut: (stack: DraggedStack) => void
}

export interface HarvestResult {
  sprites: SpriteContext[]
  globalVariables: string[]
  globalLists: string[]
  broadcasts: string[]
  procedures: Record<string, ProcedureSignature[]>
  currentSprite: string | null
  workspaceXml: Record<string, string>
}

export interface InsertVariable {
  name: string
  type: 'var' | 'list' | 'broadcast'
  scope: 'global' | 'local'
}

interface InsertRequest {
  xml: string
  variables: InsertVariable[]
  replaceProcedures: string[]
  targetSprite?: string | undefined
}

interface InsertResult {
  insertedStacks: number
  /** 插入后各段脚本的顶层积木 id(与 xml 里的顶层 block 一一对应) */
  topBlockIds: string[]
  createdVariables: InsertVariable[]
}

/** 变量 / 列表的归属:sprite 为 null 是全局的 */
interface VariableRequest {
  sprite: string | null
  name: string
  list: boolean
}

interface RemoveRequest {
  targetSprite?: string | undefined
  blockIds: string[]
}

interface Envelope {
  channel: string
  v: number
  id?: string
  kind: 'req' | 'res' | 'event'
  type: string
  payload?: unknown
  error?: { code: string; message: string }
}

export class ScratchBridgeError extends Error {
  readonly code: string

  constructor(code: string, message: string) {
    super(message)
    this.name = 'ScratchBridgeError'
    this.code = code
  }
}

type PendingRequest = {
  resolve: (payload: unknown) => void
  reject: (error: Error) => void
  timer: number
}

export class ScratchBridge {
  private frame: HTMLIFrameElement | null = null
  private readonly pending = new Map<string, PendingRequest>()
  private seq = 0
  private ready = false
  private readyWaiters: Array<() => void> = []
  private dirtyHandler: (() => void) | undefined
  private dragHandlers: BlockDragHandlers | null = null
  private readonly onMessage = (event: MessageEvent) => this.handleMessage(event)

  /** 用户把积木拖出 / 拖回工作区(outside),以及拖出后松手(draggedOut,积木已弹回原位);坐标是编辑器视口里的 */
  onBlockDrag(handlers: BlockDragHandlers | null): void {
    this.dragHandlers = handlers
  }

  attach(frame: HTMLIFrameElement, onDirty?: () => void): void {
    this.frame = frame
    this.dirtyHandler = onDirty
    window.addEventListener('message', this.onMessage)
  }

  detach(): void {
    window.removeEventListener('message', this.onMessage)
    for (const request of this.pending.values()) {
      window.clearTimeout(request.timer)
      request.reject(new ScratchBridgeError('detached', '编辑器已关闭'))
    }
    this.pending.clear()
    this.frame = null
    this.ready = false
  }

  waitReady(timeoutMs = 60_000): Promise<void> {
    if (this.ready) {
      return Promise.resolve()
    }
    return new Promise((resolve, reject) => {
      const timer = window.setTimeout(
        () => reject(new ScratchBridgeError('timeout', '编辑器加载超时，请刷新页面')),
        timeoutMs,
      )
      this.readyWaiters.push(() => {
        window.clearTimeout(timer)
        resolve()
      })
    })
  }

  async harvest(): Promise<HarvestResult> {
    return (await this.request('context/harvest', undefined)) as HarvestResult
  }

  async insertBlocks(request: InsertRequest): Promise<InsertResult> {
    return (await this.request('blocks/insert', request)) as InsertResult
  }

  /** 按顶层积木 id 删整段脚本;id 已不存在的跳过 */
  async removeBlocks(request: RemoveRequest): Promise<{ removed: number }> {
    return (await this.request('blocks/remove', request)) as { removed: number }
  }

  async exportSb3(): Promise<ArrayBuffer> {
    return (await this.request('project/export-sb3', undefined, EXPORT_TIMEOUT_MS)) as ArrayBuffer
  }

  async loadSb3(buffer: ArrayBuffer): Promise<void> {
    await this.request('project/load-sb3', { buffer }, EXPORT_TIMEOUT_MS, [buffer])
  }

  async newProject(): Promise<void> {
    await this.request('project/new', undefined, EXPORT_TIMEOUT_MS)
  }

  /** 新建角色(复制第一个角色的造型后改名,脚本清空);已存在则幂等 */
  async createSprite(name: string): Promise<{ created: boolean; name: string }> {
    return (await this.request('sprite/create', { name })) as { created: boolean; name: string }
  }

  /** 删角色连同它的脚本、造型、声音;不存在则幂等 */
  async deleteSprite(name: string): Promise<{ deleted: boolean; name: string }> {
    return (await this.request('sprite/delete', { name })) as { deleted: boolean; name: string }
  }

  /** 删变量 / 列表;sprite 为 null 是全局的;不存在则幂等 */
  async deleteVariable(request: VariableRequest): Promise<{ deleted: boolean; name: string }> {
    return (await this.request('variable/delete', request)) as { deleted: boolean; name: string }
  }

  /** 建变量 / 列表(回退时把删掉的建回来);已存在则幂等 */
  async createVariable(request: VariableRequest): Promise<{ created: boolean; name: string }> {
    return (await this.request('variable/create', request)) as { created: boolean; name: string }
  }

  /** 隐藏/恢复全部角色,只留画笔层与背景(评测截图用) */
  async setSpritesHidden(hidden: boolean): Promise<void> {
    await this.request('stage/sprites-hidden', { hidden })
  }

  /**
   * 告诉编辑器宿主面板盖住了它哪块(编辑器视口坐标):积木拖到那块上算拖出工作区,松手才会有 dragged-out。
   * 编辑器只按积木区矩形判断"拖出",不知道上面浮着面板;传 null 表示没有面板盖着。
   */
  async setHostOverlay(rect: OverlayRect | null): Promise<void> {
    await this.request('host/overlay', { rect })
  }

  private request(
    type: string,
    payload: unknown,
    timeoutMs = REQUEST_TIMEOUT_MS,
    transfer?: Transferable[],
  ): Promise<unknown> {
    const frameWindow = this.frame?.contentWindow
    if (!frameWindow) {
      return Promise.reject(new ScratchBridgeError('detached', '编辑器尚未加载'))
    }
    const id = `req-${++this.seq}`
    return new Promise((resolve, reject) => {
      const timer = window.setTimeout(() => {
        this.pending.delete(id)
        reject(new ScratchBridgeError('timeout', '编辑器响应超时，请重试'))
      }, timeoutMs)
      this.pending.set(id, { resolve, reject, timer })
      const envelope: Envelope = { channel: CHANNEL, v: VERSION, id, kind: 'req', type, payload }
      frameWindow.postMessage(envelope, window.location.origin, transfer ?? [])
    })
  }

  private handleMessage(event: MessageEvent): void {
    if (event.origin !== window.location.origin || event.source !== this.frame?.contentWindow) {
      return
    }
    const data = event.data as Envelope | undefined
    if (!data || data.channel !== CHANNEL || data.v !== VERSION) {
      return
    }
    if (data.kind === 'event') {
      if (data.type === 'bridge/ready') {
        // 编辑器可能比宿主先就绪并重播 ready；ack 幂等
        void this.request('bridge/ack', undefined).catch(() => undefined)
        if (!this.ready) {
          this.ready = true
          this.readyWaiters.forEach((waiter) => waiter())
          this.readyWaiters = []
        }
      } else if (data.type === 'project/dirty') {
        this.dirtyHandler?.()
      } else if (data.type === 'blocks/drag') {
        const p = data.payload as { outside: boolean; x: number; y: number }
        this.dragHandlers?.outside(p.outside, p.x, p.y)
      } else if (data.type === 'blocks/dragged-out') {
        this.dragHandlers?.draggedOut(data.payload as DraggedStack)
      }
      return
    }
    if (data.kind === 'res' && data.id) {
      const pending = this.pending.get(data.id)
      if (!pending) {
        return
      }
      this.pending.delete(data.id)
      window.clearTimeout(pending.timer)
      if (data.error) {
        pending.reject(new ScratchBridgeError(data.error.code, data.error.message))
      } else {
        pending.resolve(data.payload)
      }
    }
  }
}
