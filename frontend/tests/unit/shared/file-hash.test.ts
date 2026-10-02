/// <reference types="node" />

import { File as NodeFile } from 'node:buffer'

import { describe, expect, it } from 'vitest'

import { calculateFileSha256 } from '@/shared/fileHash'

describe('文件摘要', () => {
  it('按文件内容计算标准 SHA-256', async () => {
    const file = new NodeFile(['abc'], 'test.zip', { type: 'application/zip' })
    await expect(calculateFileSha256(file as unknown as File)).resolves.toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    )
  })
})
