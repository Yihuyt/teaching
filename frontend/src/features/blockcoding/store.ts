import { defineStore } from 'pinia'

import { api } from '@/api/client'
import type { MessageView, ProjectView, ScriptView, SessionView } from '@/api/generated'

export type SaveState = 'saved' | 'dirty' | 'saving'

/**
 * 助手正在处理的一轮(未入库,done 后由消息列表接管):
 * text = 给学生的回复(final_answer 工具的 text 增量);roundText = 模型在工具之间说的话的当前一轮增量,一轮结束归入 narration(只留最近一轮)
 */
interface StreamingReply {
  text: string
  roundText: string
  narration: string
  steps: { name: string; phase: 'start' | 'end'; error: boolean }[]
  scripts: ScriptView[]
}

interface BlockCodingState {
  project: ProjectView | null
  saveState: SaveState
  courseId: number
  /** 从管理后台的课程页打开的创作台:助手多一个"修改"模式;普通界面对谁都只有讲解 */
  management: boolean
  session: SessionView | null
  messages: MessageView[]
  streaming: StreamingReply | null
}

export const useBlockCodingStore = defineStore('blockcoding', {
  state: (): BlockCodingState => ({
    project: null,
    saveState: 'saved',
    courseId: 0,
    management: false,
    session: null,
    messages: [],
    streaming: null,
  }),
  actions: {
    async loadProject(projectId: number): Promise<void> {
      const response = await api.blockCodingProjectGet(this.courseId, projectId)
      this.project = response.data
      this.saveState = 'saved'
    },
    async loadSession(projectId: number): Promise<void> {
      const response = await api.blockCodingProjectChatSession(this.courseId, projectId)
      this.session = response.data
      this.streaming = null
      await this.refreshMessages()
    },
    async clearSession(projectId: number): Promise<void> {
      if (this.session) {
        await api.blockCodingChatDeleteSession(this.session.id)
      }
      this.session = null
      this.messages = []
      await this.loadSession(projectId)
    },
    async refreshMessages(): Promise<void> {
      if (this.session) {
        const response = await api.blockCodingChatMessages(this.session.id)
        this.messages = response.data
      }
    },
    markDirty(): void {
      if (this.saveState === 'saved') {
        this.saveState = 'dirty'
      }
    },
    reset(): void {
      this.$reset()
    },
  },
})
