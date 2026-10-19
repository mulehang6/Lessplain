/** 每种明确折叠状态有自己的高水位；缓存随 companion 内容替换销毁。 */
export class ReservedSpace {
  private readonly heights = new Map<string, number>();
  measure(key: string, naturalHeight: number): number {
    const height = Math.max(this.heights.get(key) ?? 0, naturalHeight);
    this.heights.delete(key);
    this.heights.set(key, height);
    if (this.heights.size > 32) this.heights.delete(this.heights.keys().next().value!);
    return height;
  }
}
