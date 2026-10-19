# 源码理解与生成式 UI 验证

日期：2026-10-19。本轮在既有改动基础上继续实现，不验证原版或决定整个 JCEF 分支的去留。

## 判断标准

先确定用户的困惑与需要建立的职责、关系或状态认识，再选择最小表达。源码节点和连线能讲清时直接使用；生成式 UI 可以提供更合适的空间组织，或让操作揭示条件与结果的联系。它不以控件数量、可点击性或每次产生新交互作为成功标准。少数复杂问题上明显减少理解负担，就足以保留这项按需能力；技术栈的成本与收益另行判断。

lessplain、lessplain-teach、协议、catalog 和 context 已替换同主题默认 Tabs、默认复述完整声明和折叠继承成员的规则。实际源码仍是依据与最终阅读位置，源码快照只用于有价值的对照。

## 本轮样例

- `examples/tag-source-nodes.view.json`：三个同时可见、无连线的源码节点，分别定位 TagDO、ConfigDTO 与 TagSelectDTO。保留原来源 ID，历史问题继续使用冻结来源。旧的多组与页签载荷保留作组件和性能回归材料，不再作为 tag 问题的推荐表达。
- `examples/home-sidebar-cache.view.json`：一个缓存推演，不再重复绘制流程图。数据库当前内容、缓存结果与最近一次返回持续可见，操作附近提供反馈及对应源码入口。使用既有 Demo 和状态保存能力，没有新组件、协议或执行器。

### 缓存源码核验

本轮通过实际验收项目的 IDEA `read_file` 重新读取并核对：

- IndexController 调用 IndexRecommendHelper.buildIndexVo，后者调用注入的 SidebarService.queryHomeSidebarList。
- QuickForumApplication 的 `@EnableCaching` 启用缓存；SidebarServiceImpl.queryHomeSidebarList 的 `@Cacheable` 指定 caffeineCacheManager、home / homeSidebar。
- 缓存对象是整个首页侧栏列表，包含公告、热门文章和条件存在的榜单。样例只突出热门文章部分，不把每篇文章误作独立缓存项。
- hotArticles 取第一页最多八篇，ArticleReadServiceImpl 经 ArticleDao.listHotArticles 调用 ArticleMapper.listArticlesByReadCounts，按阅读数降序查询。
- ForumCoreAutoConfig 配置 `expireAfterWrite(5, TimeUnit.MINUTES)`，不是访问后续期或定时回源。

演示使用虚构文章和阅读数，只推演排序变化；阅读数不冒充实际侧栏缓存字段。假定缓存已启用、没有提前淘汰，也不模拟外部失效或所有生产分支。没有执行项目、读取真实数据库或采集实际请求。

### 用户验收路径

1. 访问侧栏，观察首次查询与缓存写入。
2. 修改模拟数据库，再访问，观察缓存命中及旧返回顺序；查库次数保持不变。
3. 推进到写入后五分钟，观察缓存已过期，但查库次数和最近一次返回都没有自动改变。
4. 再次访问，观察查询重新执行、返回顺序更新，过期时间从此次写入重新计算。
5. 点击缓存注解、查询、组装和过期配置入口，到 IDEA 核对依据。

功能检查通过只说明推演与导航正确。用户是否更容易解释“为何返回旧数据、何时更新、源码依据在哪”，仍需实际体验后评价；本轮没有将其提前标为表达效果已验收。

## 斜向输入与取证

旧回归期望主动丢弃带 Shift 的双轴事件纵向分量。本轮先更新期望，得到失败回归，再修正输入：已有 deltaX 时保留 deltaY；只有 deltaX 为零的 Shift 滚轮才转换为横向。原生滚动判断和画布更新共用同一单位及轴转换，OSR 符号校正仅作用于实际横向通道。

未加入缓动、待完成位移或额外帧队列。滚轮直接中断镜头动画并立即应用视口；没有新证据支持重写 ReactFlow 相机系统。内部模式记录最近最多 500 条、120 秒以内事件，替代仅取页面前 32 个 wheel 样本。wheel 带 eventTime、receivedAt、handledAt、原始双轴与修饰键；route=0 为控件，1 为原生滚动区，2 为画布；画布记录更新前后的 x/y/zoom。既有测量、布局和 Worker 事件可按时间关联。

合成浏览器检查覆盖同任务连续双轴输入、反向、最近样本、有界缓存、原生区域隔离及纯平移不请求布局。真实触摸板的惯性、输入是否在停手后持续，以及用户描述的慢速抽搐，仍需加载新版后取得硬件事件时序；不能以合成输入通过宣称全部修复。

## 验证记录

本轮最终命令、安装包和运行页面回执统一记录在 [交付记录](jcef-react-status.md)。自动化不启动 IDE、不操作桌面、不截图。实际视觉密度、触摸板手感和理解收益留待用户验收。
