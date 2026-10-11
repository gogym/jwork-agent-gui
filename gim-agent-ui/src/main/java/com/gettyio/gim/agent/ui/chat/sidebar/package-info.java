/*
 * Copyright 2026 gogym
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * 会话侧栏：会话列表渲染、折叠开关与宿主窄接口。
 * <p>{@link SessionSidebar} 渲染按当前智能体过滤的会话列表（新建 / 选择 / 重命名 / 删除等交互），
 * 列表数据与动作编排均交回宿主执行、自身不持有业务状态；{@link SessionSidebarHost} 是侧栏与
 * 主控制器之间的窄回调接口；{@link SidebarToggle} 为侧栏折叠 / 展开控件。</p>
 */
package com.gettyio.gim.agent.ui.chat.sidebar;
