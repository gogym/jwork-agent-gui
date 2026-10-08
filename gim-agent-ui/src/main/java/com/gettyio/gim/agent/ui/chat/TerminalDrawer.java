package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.ui.view.TerminalView;
import javafx.scene.control.SplitPane;

/**
 * 底部终端抽屉：把 {@link TerminalView} 挂进聊天页 SplitPane 的展开/收起逻辑。
 * <p>收起只摘除节点，终端进程与历史保留；从 {@code MainController} 抽出的独立组件。</p>
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
