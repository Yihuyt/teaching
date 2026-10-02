import { nextAvailableName, validateMaterialName, validateUploadFile } from '@/features/courses/materialUpload'

function file(name: string, size: number, type = 'application/pdf'): File {
  return { name, size, type } as File
}

describe('validateMaterialName', () => {
  it('拒绝空名与超长名', () => {
    expect(validateMaterialName('   ')).toBe('名称不能为空')
    expect(validateMaterialName('a'.repeat(256))).toBe('名称不能超过 255 个字符')
    expect(validateMaterialName('a'.repeat(255))).toBeNull()
  })
})

describe('validateUploadFile', () => {
  it('文件名沿用名称规则', () => {
    expect(validateUploadFile(file('a'.repeat(256), 10))).toBe('名称不能超过 255 个字符')
  })

  it('内容类型与大小边界', () => {
    expect(validateUploadFile(file('x.bin', 10, 'a'.repeat(129)))).toBe('文件内容类型不能超过 128 个字符')
    expect(validateUploadFile(file('x.bin', 0))).toBe('文件大小必须在 1 字节到 5 GiB 之间')
    expect(validateUploadFile(file('x.bin', 5 * 1024 * 1024 * 1024 + 1))).toBe(
      '文件大小必须在 1 字节到 5 GiB 之间',
    )
    expect(validateUploadFile(file('x.bin', 1))).toBeNull()
  })
})

describe('nextAvailableName', () => {
  it('在扩展名前追加序号,跳过已占用的名称', () => {
    expect(nextAvailableName('报告.pdf', ['报告.pdf'])).toBe('报告 (2).pdf')
    expect(nextAvailableName('报告.pdf', ['报告.pdf', '报告 (2).pdf'])).toBe('报告 (3).pdf')
  })

  it('无扩展名直接在末尾追加;比较不区分大小写', () => {
    expect(nextAvailableName('README', ['README'])).toBe('README (2)')
    expect(nextAvailableName('Data.CSV', ['data.csv', 'DATA (2).csv'])).toBe('Data (3).CSV')
  })

  it('隐藏文件的前导点不当作扩展名分隔', () => {
    expect(nextAvailableName('.env', ['.env'])).toBe('.env (2)')
  })

  it('超长名称截短主体,总长不超过 255', () => {
    const name = `${'甲'.repeat(255 - 4)}.pdf`
    const next = nextAvailableName(name, [name])
    expect(next.length).toBeLessThanOrEqual(255)
    expect(next.endsWith(' (2).pdf')).toBe(true)
  })
})
