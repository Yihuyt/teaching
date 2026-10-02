/** 舞台在 scratch-vm 里叫 Stage,中文界面里叫舞台;脚本记录里存的是 Stage,给人看时换成界面上的叫法 */
export function spriteLabel(sprite: string): string {
  return sprite === 'Stage' ? '舞台' : sprite
}
