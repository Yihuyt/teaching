import { describe, expect, it } from 'vitest'

import { drainSseBuffer } from '@/api/sse'

describe('drainSseBuffer', () => {
  it('解析完整块并保留残缺尾部', () => {
    const { payloads, rest } = drainSseBuffer(
      'data: {"type":"stage_start","stage":"outline"}\n\ndata: {"type":"scene_start"',
    )
    expect(payloads).toEqual(['{"type":"stage_start","stage":"outline"}'])
    expect(rest).toBe('data: {"type":"scene_start"')
  })

  it('一个块里的多行 data 以换行拼接', () => {
    const { payloads } = drainSseBuffer('data: 第一行\ndata: 第二行\n\n')
    expect(payloads).toEqual(['第一行\n第二行'])
  })

  it('忽略注释行与空块', () => {
    const { payloads, rest } = drainSseBuffer(':heartbeat\n\ndata: {"a":1}\n\n')
    expect(payloads).toEqual(['{"a":1}'])
    expect(rest).toBe('')
  })

  it('连续多个块按序解析', () => {
    const { payloads } = drainSseBuffer('data: 1\n\ndata: 2\n\ndata: 3\n\n')
    expect(payloads).toEqual(['1', '2', '3'])
  })
})
