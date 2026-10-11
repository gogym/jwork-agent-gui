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

package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.ui.view.TerminalView;
import javafx.scene.control.SplitPane;

/**
 * 底部终端抽屉：把 {@link TerminalView} 挂进聊天页 SplitPane 的展开/收起逻辑。
 * <p>收起只摘除节点，终端进程与历史保留；从 {@code MainController} 抽出的独立组件。</p>
 *
 * @author gogym
 */
public class TerminalDrawer {

    private final SplitPane chatSplit;
    private final TerminalView terminalView;

    public TerminalDrawer(SplitPane chatSplit, TerminalView terminalView) {
        this.chatSplit = chatSplit;
        this.terminalView = terminalView;
    }

    /** 抽屉是否已展开（除消息区外还有第二个可拖拽项）。 */
    public boolean visible() {
        return chatSplit != null && chatSplit.getItems().size() > 1;
    }

    /** 展开：节点不存在则挂入，启动 shell 并把分栏压到 0.68。 */
    public void show() {
        if (chatSplit == null) {
            return;
        }
        if (!visible()) {
            chatSplit.getItems().add(terminalView.getNode());
        }
        terminalView.ensureStarted();
        chatSplit.setDividerPositions(0.68);
    }

    /** 收起：仅摘除节点（进程与历史保留）。 */
    public void hide() {
        if (chatSplit != null && visible()) {
            chatSplit.getItems().remove(terminalView.getNode());
        }
    }

    /** 展开/收起切换。 */
    public void toggle() {
        if (visible()) {
            hide();
        } else {
            show();
        }
    }
}
