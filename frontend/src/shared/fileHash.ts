import { sha256 } from '@noble/hashes/sha2.js'
import { bytesToHex } from '@noble/hashes/utils.js'

const HASH_CHUNK_SIZE = 4 * 1024 * 1024

async function updateHash(
  hasher: ReturnType<typeof sha256.create>,
  file: File,
  offset: number,
): Promise<void> {
  if (offset >= file.size) return
  const bytes = new Uint8Array(await file.slice(offset, offset + HASH_CHUNK_SIZE).arrayBuffer())
  hasher.update(bytes)
  await updateHash(hasher, file, offset + HASH_CHUNK_SIZE)
}

export async function calculateFileSha256(file: File): Promise<string> {
  const hasher = sha256.create()
  await updateHash(hasher, file, 0)
  return bytesToHex(hasher.digest())
}
