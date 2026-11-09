# 从用户消息新建会话：逐点试用材料

以下内容已通过开发实例 MCP 整理进原有六份正文，共 18 个讲解点，六篇均为 revision 2，已保存并读回核对。原图保持 revision 4，阅读路线也已更新至 revision 2；未新建竞争根图或正文。源码路径为验收工程中的 `desktopApp/src/main/kotlin/com/agent/app/chat/state/ConversationTreeController.kt`，入口为 `createConversationFromUserEntry`。

保存前读当前图、每篇正文、笔记与追问。保留用户修改，用 `source_resolve` 解析下面的关注表达式，在原正文加入稳定讲解点标记。先通过 IDE 解析 `window` 的类型声明，再为 `ChatWindowState` 链接声明处；不要凭名称猜文件。每点正文与主锚点如下。

## 检查会话与所选用户消息

- `window_background`，关注首个 `window`：window 是控制器创建时传入的 ChatWindowState 对象，提供当前聊天窗口的会话状态和操作入口。这里先知道它负责提供原会话即可；类型名可点击查看声明。
- `lookup_conversation`，关注 `window.findConversationOrNull(conversationId)`：按传入的会话 ID 查找原会话，找到的结果后面叫 source。这个函数内部暂时不用展开。
- `missing_conversation`，关注首个 `?: return failure("会话不存在。")`：左边为空才执行右边，return 使整个操作在此结束。通过后才继续检查消息。
- `selected_message`，关注 `source.entries.firstOrNull { it.id == userEntryId } as? ConversationEntry.Message`：先按条目 ID 找消息，再安全转换为消息类型，失败同样提前结束。格式版本和用户角色两项判断按字面简述，不单独拆页。

## 停运行，准备路径和草稿

- `prepare_inputs`，关注准备阶段三行：先请求取消属于原会话的运行，再准备历史 copied 与草稿 restoredDraft。这段没有等待取消结束的动作。
- `copy_boundary`，关注 `selected.parentId`：假设原路径为 U1 → A1 → U2 → A2，选中 U2 时复制到它的父条目 A1 为止。新历史是独立条目 U1′ → A1′，U2 留作输入框草稿。
- `fresh_entry_ids`，关注 `::newId`：把生成 ID 的动作交给复制函数，需要创建条目时再调用。它与现在就求值一次的 newId() 不同。
- `restore_input`，关注 `selected.inputParts`：取选中消息保存的原输入，恢复文本和附件。恢复不是发送。

## 继承配置并装配子会话

- `new_base`，关注本次 `newConversation(...)`：创建独立会话 ID 的基础对象，沿用工作区及模型等设置。
- `conversation_lineage`，关注 `parentConversationId` 与 `forkedFromEntryId` 赋值：前者指原会话，后者记录选中的 U2。这里的会话父级与上一点决定复制边界的条目父级是两种关系。
- `entry_path`，关注 entries、activeEntryId、headEntryId 三项赋值：放入复制历史，将当前路径与末端都指向复制后的 A1′。
- `projection`，关注 `withEntryProjection()`：依据条目路径生成相应的展示状态。此时 child 只是准备好的新对象，还要加入窗口列表并显示。

## 加入列表并请求输入焦点

- `publish_child`，关注 `listOf(child) + window.ui.tasks`：新会话排在最前，原会话列表仍保留。外层 copy 形成新界面状态，再赋回 window.ui。
- `request_focus`，关注 `composerFocusRequestId` 的加一表达式：递增编号表示一次新的输入焦点请求，不表示界面已经响应完毕。

## 切换会话并放回草稿

- `show_child`，关注 `window.showExistingConversation(...)`：显示 child.id 指定的新会话，并应用给定草稿。
- `draft_fields`，关注 `ComposerDraft(...)`：三个参数分别是文本、末尾光标位置和附件。此时新历史止于 A1′，输入框里是可修改的 U2；没有调用发送。

## 安排保存并返回成功

- `schedule_save`，关注 `window.persistenceCoordinator?.schedule(window.ui.tasks)`：协调者存在才安排保存，否则跳过。这里只安排，没有等待落盘。
- `operation_result`，关注 `return success()`：返回操作成功结果，不能将它当作磁盘已经写完的证明。

## 保存与验收

每篇保持原 tutorialId 和现有点 ID，采用当前 revision 的精确编辑。不要丢用户笔记、追问与正文修改。仅在保存并重新读取成功后记录完成状态。安装新版后从原图进入，验证源码联动、声明跳转、轮滚、返回主图和编辑恢复；不自动启动 IDE 或截图。
