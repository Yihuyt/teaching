import { MAX_PROBLEM_PACKAGE_BYTES, validateProblemPackageFile } from '@/features/programming/problemPackage'

function file(name: string, size: number): File {
  return { name, size } as File
}

describe('validateProblemPackageFile', () => {
  it('只接受 .zip / .kpp', () => {
    expect(validateProblemPackageFile(file('sumtwo.tar', 10))).toBe('sumtwo.tar：题目包必须是 .zip 或 .kpp')
    expect(validateProblemPackageFile(file('sumtwo.KPP', 10))).toBeNull()
  })

  it('大小在 1 字节到 256 MiB 之间', () => {
    expect(validateProblemPackageFile(file('a.zip', 0))).toBe('a.zip：题目包大小必须在 1 字节到 256 MiB 之间')
    expect(validateProblemPackageFile(file('a.zip', MAX_PROBLEM_PACKAGE_BYTES + 1))).toContain('256 MiB')
    expect(validateProblemPackageFile(file('a.zip', MAX_PROBLEM_PACKAGE_BYTES))).toBeNull()
  })
})
