package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.core.chat.StreamEvent;
import com.gettyio.gim.agent.domain.enums.StreamEventType;
import com.gettyio.gim.agent.ui.I18n;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息列表渲染器：负责对话区气泡容器的构建、追加、帧合并刷新与滚动跟随。
 * <p>由 {@code MainController} 在 FXML 注入后构造并委托调用，自身不持有业务状态。助手消息以
 * {@link AssistantBubble} 句柄承载：流式期间按帧追加纯文本，收尾（或历史重放）经
 * {@link MarkdownRenderer} 整体渲染为富节点。</p>
 * <p>气泡操作：渲染器跟踪「最后一条助手」「最后一条用户」两个气泡，把「重新生成」按钮只亮在最后一条
 * 助手上、「编辑重发」只亮在最后一条用户上；复制/删除对所有气泡可用。回调统一交给
 * {@link MessageActionListener}（由控制器实现）。</p>
 */
public class MessageListRenderer {

    /** 气泡最大宽度（与 CSS .bubble-* 视觉约定一致） */
    private static final double BUBBLE_MAX_WIDTH = 680;

    private final ScrollPane scrollPane;
    private final VBox messagesContainer;
    private final MessageActionListener listener;

    private MessageActions lastAssistantActions;
    private MessageActions lastUserActions;

    public MessageListRenderer(ScrollPane scrollPane, VBox messagesContainer, MessageActionListener listener) {
        this.scrollPane = scrollPane;
        this.messagesContainer = messagesContainer;
        this.listener = listener;
        // 滚动到顶回调（向上补页）：触发与否由回调方防抖，这里只负责探测
        scrollPane.vvalueProperty().addListener((obs, was, now) -> {
            if (onTopReached != null && now.doubleValue() <= TOP_REACH_THRESHOLD) {
                onTopReached.run();
            }
        });
    }

    /** 滚动到顶阈值：vvalue 低于该值视为到达顶部 */
    private static final double TOP_REACH_THRESHOLD = 0.02;

    private Runnable onTopReached;

    /** 安装「滚动到顶」回调（向上补页入口）；传 null 取消。 */
    public void setOnTopReached(Runnable onTopReached) {
        this.onTopReached = onTopReached;
    }

    /** 消息容器子节点列表：供控制器绑定欢迎页显隐等只读用途。 */
    public ObservableList<Node> messages() {
        return messagesContainer.getChildren();
    }

    /** 清空消息区（同时复位「最后一条」跟踪）。 */
    public void clear() {
        messagesContainer.getChildren().clear();
        lastAssistantActions = null;
        lastUserActions = null;
    }

    /** 追加一条空的助手气泡（流式起点），返回可增量追加、收尾渲染 Markdown 的句柄。 */
    public AssistantBubble appendAssistantBubble() {
        AssistantBubble bubble = new AssistantBubble(BUBBLE_MAX_WIDTH, listener);
        messagesContainer.getChildren().add(bubble.node());
        promoteAssistant(bubble.actions());
        scrollToBottom();
        return bubble;
    }

    /** 追加一条助手气泡并立即以 Markdown 渲染（历史重放用）；{@code toolCallsJson} 为 {@code tool_calls} 列原文，可空；{@code thinking} 为思考过程全文，可空；{@code createdAt} 为落盘时间（悬停操作条内显示），可空。 */
    public void appendAssistantMarkdown(String content, long conversationId, String toolCallsJson, String thinking, LocalDateTime createdAt) {
        AssistantBubble bubble = new AssistantBubble(BUBBLE_MAX_WIDTH, listener);
        bubble.renderContent(content, thinking,
                com.gettyio.gim.agent.core.chat.ToolCallRecordCodec.decode(toolCallsJson));
        bubble.actions().setSpeakShown(true);
        bubble.setConversationId(conversationId);
        bubble.setTime(createdAt);
        Node node = bubble.node();
        node.setUserData(conversationId);
        messagesContainer.getChildren().add(node);
        promoteAssistant(bubble.actions());
        scrollToBottom();
    }

    /** 追加一条用户气泡（文本 + 文件行 + 图片缩略图，若有）；返回句柄供收尾后回填主键。 */
    public UserBubble appendUserBubble(String text, List<ImageView> thumbs, List<Node> fileRows, long conversationId) {
        UserBubble bubble = new UserBubble(text, thumbs, fileRows, BUBBLE_MAX_WIDTH, listener);
        bubble.setConversationId(conversationId);
        Node node = bubble.node();
        node.setUserData(conversationId);
        messagesContainer.getChildren().add(node);
        promoteUser(bubble.actions());
        scrollToBottom();
        return bubble;
    }

    /** 追加任意节点到消息区底部并滚动跟随（多模型对比卡片用）。 */
    public void appendNode(Node node) {
        messagesContainer.getChildren().add(node);
        scrollToBottom();
    }

    /** 头部插入一条助手气泡并立即以 Markdown 渲染（向上补页用）；{@code thinking} 为思考过程全文，可空；{@code createdAt} 为落盘时间（悬停操作条内显示），可空；不触发 promote 与滚动。 */
    public void prependAssistantMarkdown(String content, long conversationId, String toolCallsJson, String thinking, LocalDateTime createdAt) {
        AssistantBubble bubble = new AssistantBubble(BUBBLE_MAX_WIDTH, listener);
        bubble.renderContent(content, thinking,
                com.gettyio.gim.agent.core.chat.ToolCallRecordCodec.decode(toolCallsJson));
        bubble.actions().setSpeakShown(true);
        bubble.setConversationId(conversationId);
        bubble.setTime(createdAt);
        Node node = bubble.node();
        node.setUserData(conversationId);
        messagesContainer.getChildren().add(0, node);
    }

    /** 头部插入一条用户气泡（向上补页用）；不触发 promote 与滚动。 */
    public void prependUserBubble(String text, List<ImageView> thumbs, List<Node> fileRows, long conversationId) {
        UserBubble bubble = new UserBubble(text, thumbs, fileRows, BUBBLE_MAX_WIDTH, listener);
        bubble.setConversationId(conversationId);
        Node node = bubble.node();
        node.setUserData(conversationId);
        messagesContainer.getChildren().add(0, node);
    }

    /**
     * 滚动到指定主键的消息行并短暂高亮（搜索命中跳转用）；找不到或主键为空则不动。
     * <p>按目标行在内容高度中的占比设 {@code vvalue}，1.6s 后移除 {@code bubble-hit} 高亮样式。</p>
     */
    public void revealAndHighlight(long conversationId) {
        if (conversationId <= 0) {
            return;
        }
        Node target = null;
        for (Node c : messagesContainer.getChildren()) {
            if (Long.valueOf(conversationId).equals(c.getUserData())) {
                target = c;
                break;
            }
        }
        if (target == null) {
            return;
        }
        scrollPane.layout();
        double contentH = messagesContainer.getHeight();
        double viewH = scrollPane.getViewportBounds().getHeight();
        if (contentH > viewH && contentH > 0) {
            double ratio = target.getBoundsInParent().getMinY() / (contentH - viewH);
            scrollPane.setVvalue(Math.max(0.0, Math.min(1.0, ratio)));
        } else {
            scrollToBottom();
        }
        target.getStyleClass().add("bubble-hit");
        PauseTransition reset = new PauseTransition(Duration.millis(1600));
        final Node hit = target;
        reset.setOnFinished(e -> hit.getStyleClass().remove("bubble-hit"));
        reset.play();
    }

    /** 把一帧窗口内的事件合并：思考/正文增量与工具/确认提示都即时追加到气泡（正文另累加进 {@code acc} 供出错收尾）。 */
    public void applyBatch(List<StreamEvent> batch, AssistantBubble bubble, StringBuilder acc) {
        StringBuilder frame = new StringBuilder();
        boolean thinkingGrew = false;
        for (StreamEvent e : batch) {
            if (e.getType() == StreamEventType.TEXT_DELTA && e.getText() != null) {
                frame.append(e.getText());
            } else if (e.getType() == StreamEventType.THINKING_DELTA && e.getText() != null) {
                bubble.appendThinking(e.getText());
                thinkingGrew = true;
            } else if (e.getType() == StreamEventType.TOOL_START && e.getToolName() != null) {
                frame.append("\n").append(I18n.t("chat.toolCall", e.getToolName()));
            } else if (e.getType() == StreamEventType.CONFIRM_REQUEST && e.getToolName() != null) {
                frame.append("\n").append(I18n.t("chat.toolConfirm", e.getToolName()));
            }
        }
        if (frame.length() > 0) {
            acc.append(frame);
            bubble.appendPlain(frame.toString());
            scrollToBottom();
        } else if (thinkingGrew) {
            scrollToBottom();
        }
    }

    /** 让最新助手气泡成为唯一带「重新生成」的气泡。 */
    private void promoteAssistant(MessageActions actions) {
        if (lastAssistantActions != null && lastAssistantActions != actions) {
            lastAssistantActions.setRegenerateShown(false);
        }
        lastAssistantActions = actions;
        actions.setRegenerateShown(true);
    }

    /** 让最新用户气泡成为唯一带「编辑重发」的气泡。 */
    private void promoteUser(MessageActions actions) {
        if (lastUserActions != null && lastUserActions != actions) {
            lastUserActions.setEditShown(false);
        }
        lastUserActions = actions;
        actions.setEditShown(true);
    }

    private void scrollToBottom() {
        scrollPane.applyCss();
        scrollPane.layout();
        scrollPane.setVvalue(1.0);
        // 兜底：气泡换行高度与滚动条极值常要到下一个 pulse 才结算，
        // 同帧 setVvalue(1.0) 会被旧极值截断而停在半截，故延一帧再拉到底。
        Platform.runLater(() -> {
            scrollPane.layout();
            scrollPane.setVvalue(1.0);
        });
    }
}
