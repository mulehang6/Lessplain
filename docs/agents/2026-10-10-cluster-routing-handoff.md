# 多 cluster 布线打磨交接

## 本轮决定与实现

用户决定保留多 cluster，继续处理截图中跨组回边与分组边框重合的问题。没有删除现有两张 Lessplain 运作机制测试图，也没有清空其分组。

新增 `diagram/ClusterBorderClearance.kt`，为跨组边检查所有分组边框。平行段与边框保留 12 个逻辑像素间距；大于 8 个逻辑像素的贴边重叠属于缺陷。垂直穿框仍可通过，组内边不受这一规则影响。标签不能覆盖边框。约束接入初次修复、后续通道优化及最终质量检查，候选通道同时纳入边框坐标。没有隐藏连线或改变关系、节点位置协议。

复现截图的 8 节点双组回边样例，以及随机多入口、汇合、平行边、循环样例均通过横向和纵向检查。实际观感仍需用户加载新版插件后验收。

## 真实 Agent 压力图

只读分析 `mulehang-agent` 的发送、Koog 模型执行、Shell 审批、状态快照、工具日志与检查点恢复。通过语义搜索定位，再用 IDEA 调用层级核对核心调用；依赖注入、模型选工具、事件流和用户继续动作等必要间接关系注明推断。

整理了 39 个带完整函数锚点的节点、66 条关系、8 个 cluster，包含 34 条跨组关系。工具函数锚点包含注解；全部文件哈希按 UTF-8、LF 计算。未修改 `mulehang-agent`，未运行它的应用或声称取得真实执行 Trace。

几何夹具：`src/test/resources/diagram/mulehang-agent-run-clusters.json`。聚焦回归：`ClusterBorderRoutingTest`、`ClusterBorderClearanceTest`。压力样例两个方向均无已定义的内容、边框和通道冲突，全部节点及连线保留。

完整图已通过 MCP 保存到开发 IDE 中的 `mulehang-agent`，记录为 `6a760d5a-688e-4598-b7fc-49c66346c190`、revision 1，标题为 `[压力测试] Agent 审批与检查点恢复`。39 个锚点均已通过保存时的在线校验。载荷仍暂存在被忽略的 `build/reports/cluster-routing/mulehang-agent-pending-payload.json`；文件名中的 pending 不表示尚未保存，不要重复提交根图。

后续用户授权的界面检查已直接查看该 IDE 原图，并复现文字截断、分组跨度及写不安全错误。之后的阶段布局、路线重选和反馈清理见 [阅读交互交接](2026-10-10-reading-ux-handoff.md)。开发 IDE 未自动重启，新构建需要用户加载后验收。

路由器调用已验证形式为 `present_understanding --schemaVersion '1.4' --requestId '...' --question '...' --graph '<原始 JSON>' --placement 'root' --contextToken '...' --displayTitle '...'`，目标工程放外层 `projectPath`。JSON 内的单引号转成 `\u0027` 再包裹单引号；不要用双层 JSON.stringify 给 router 参数加引号。

## 已验证

- JDK 21，完整 `test`：303 项通过，无失败或跳过。
- `buildPlugin` 成功；安装包在 `build/distributions/Lessplain-1.0.0-SNAPSHOT.zip`。
- `verifyPlugin --offline`：IDEA 2026.1.5、2026.2.3 均 Compatible，保留既有弃用及实验 API 提示。
- 受影响核心文件 IDEA 检查无错误；`git diff --check` 无空白错误。
- 未修改 skill、README 或执行提交；保留此前未提交工作。

几何检查不把孤立的必要交叉判为失败。实际用户验收重点是长回边与边框距离、审批回路、多个入口箭头、缩放后的可辨认程度。新包未自动加载进运行中的测试 IDE。
