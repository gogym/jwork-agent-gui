package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import javafx.concurrent.Worker;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天区整页 WebView 宿主：内联一段 HTML 骨架（引用 classpath 里的 chat.css / marked.min.js / purify.min.js / chat.js），
 * 装载完成后把 {@link ChatBridge} 挂到 {@code window.javaBridge}，并以 {@code engine.executeScript("ChatAPI.xxx(...)")}
 * 驱动前端 {@code chat.js} 渲染历史、流式增量、对比卡与主题切换。
 * <p>对外暴露与旧 JavaFX 气泡列表渲染器等价的 Java API（renderHistory/prependOlder/stream*…），控制器只与本类交互。</p>
 * <p>页面脚本装载是异步的：装载完成前的调用先入 {@link #pending} 队列，就绪后按序重放，杜绝时序竞争。</p>
 */
public final class ChatWebView {

    private static final Logger log = LoggerFactory.getLogger(ChatWebView.class);

    private static final String MARKED_JS = "/js/marked.min.js";
    private static final String PURIFY_JS = "/js/purify.min.js";
    private static final String CHAT_JS = "/web/chat.js";
    private static final String CHAT_CSS = "/web/chat.css";

    private final WebView webView = new WebView();
    private final WebEngine engine = webView.getEngine();
    private final ChatBridge bridge;

    private volatile boolean ready;
    /** 就绪前的待执行脚本队列（FX 线程读写）。 */
    private final Deque<String> pending = new ArrayDeque<>();

    /**
     * @param actions 气泡动作回调（复制/朗读/重生成/编辑重发…），由控制器实现
     * @param events  运行时事件回调（滚动/链接/图片/代码/右键/空态/就绪），由控制器实现
     */
    public ChatWebView(MessageActionListener actions, ChatViewEventsHolder events) {
        this.bridge = new ChatBridge(actions, events);
        // 关闭 WebView 默认右键菜单（改由桥 onContext 提供原生菜单）
        webView.setContextMenuEnabled(false);
        engine.getLoadWorker().stateProperty().addListener((obs, o, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                installBridge();
            } else if (state == Worker.State.FAILED) {
                log.warn("聊天 WebView 装载失败: {}", engine.getLoadWorker().getException());
            }
        });
        engine.loadContent(buildShell(), "text/html");
        // 主题/字号/语言联动：注册监听即时同步到 WebView
        UiTheme.addThemeListener(this::refreshTheme);
        I18n.onChange(lang -> refreshTheme());
    }

    /** 宿主节点：控制器把它放进 chatHost 并撑满。 */
    public StackPane node() {
        StackPane host = new StackPane(webView);
        host.getStyleClass().add("chat-web-host");
        return host;
    }

    /** 是否已完成装载并就绪。 */
    public boolean isReady() {
        return ready;
    }

    // ---------- 装载与桥接 ----------

    private void installBridge() {
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember("javaBridge", bridge);
            ready = true;
            // 补发就绪前的调用
            String script;
            while ((script = pending.pollFirst()) != null) {
                engine.executeScript(script);
            }
        } catch (Throwable t) {
            log.warn("聊天桥接安装失败", t);
        }
    }

    /** 组装内联 HTML 骨架：样式与脚本一律取 classpath 资源的外部 URL（jar: 子资源可加载，MermaidView 已佐证）。 */
    private String buildShell() {
        String css = resUrl(CHAT_CSS);
        String marked = resUrl(MARKED_JS);
        String purify = resUrl(PURIFY_JS);
        String chat = resUrl(CHAT_JS);
        return """
                <!DOCTYPE html>
                <html lang="zh" data-theme="light" data-font="medium">
                <head>
                <meta charset="utf-8">
                <link rel="stylesheet" href="%s">
                </head>
                <body>
                <div id="list"></div>
                <script src="%s"></script>
                <script src="%s"></script>
                <script src="%s"></script>
                </body>
                </html>
                """.formatted(css, marked, purify, chat);
    }

    private static String resUrl(String path) {
        var r = ChatWebView.class.getResource(path);
        return r == null ? path : r.toExternalForm();
    }

    // ---------- 主题 ----------

    /** 依当前 UiTheme + I18n 语言刷新前端主题与本地化文案。 */
    public void refreshTheme() {
        String mode = UiTheme.mode() == UiTheme.Mode.DARK ? "dark" : "light";
        String font = UiTheme.fontSize().name().toLowerCase(java.util.Locale.ROOT);
        String lang = I18n.current().locale().toString();
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("copy", I18n.t("chat.msg.copy"));
        labels.put("copied", I18n.t("chat.msg.copied"));
        labels.put("preview", I18n.t("chat.artifact.preview"));
        labels.put("speak", I18n.t("chat.msg.speak"));
        labels.put("regenerate", I18n.t("chat.msg.regenerate"));
        labels.put("editResend", I18n.t("chat.msg.editResend"));
        labels.put("aiGenerated", I18n.t("chat.msg.aiGenerated"));
        labels.put("thinking", I18n.t("chat.thinking"));
        labels.put("thinkingDone", I18n.t("chat.thinking.done"));
        labels.put("toolCount", I18n.t("chat.tool.group.count", "{0}", "{1}"));
        labels.put("toolFailed", I18n.t("chat.tool.group.failed", "{0}", "{1}"));
        labels.put("toolInput", I18n.t("chat.tool.params"));
        labels.put("toolOutput", I18n.t("chat.tool.output"));
        labels.put("delegate", I18n.t("chat.tool.subagent.delegate", "{0}"));
        String script = "ChatAPI.setTheme(" + ChatMessagePayload.jsString(mode) + ","
                + ChatMessagePayload.jsString(font) + ","
                + ChatMessagePayload.jsString(lang) + ","
                + ChatMessagePayload.jsValue(labels) + ");";
        run(script);
    }

    // ---------- 渲染 API ----------

    /** 整页重放历史（{@code json} 为 {@link ChatMessagePayload#toJson} 产物）。 */
    public void renderHistory(String json) {
        run("ChatAPI.renderHistory(" + json + ");");
    }

    /** 头部补页（保持视觉位置不跳）。 */
    public void prependOlder(String json) {
        run("ChatAPI.prependOlder(" + json + ");");
    }

    /** 追加单条消息（发送即时上屏的用户气泡）。 */
    public void appendMessage(Map<String, Object> msg) {
        run("ChatAPI.appendMessage(" + ChatMessagePayload.jsValue(msg) + ");");
    }

    /** 清空消息区。 */
    public void clear() {
        run("ChatAPI.clear();");
    }

    /** 流式起点：建一条临时助手气泡（{@code id} 为临时负数主键）。 */
    public void streamStart(long id) {
        run("ChatAPI.streamStart(" + id + ");");
    }

    /** 正文增量。 */
    public void streamDelta(long id, String text) {
        run("ChatAPI.streamDelta(" + id + "," + ChatMessagePayload.jsString(text) + ");");
    }

    /** 思考增量。 */
    public void streamThinking(long id, String text) {
        run("ChatAPI.streamThinking(" + id + "," + ChatMessagePayload.jsString(text) + ");");
    }

    /** 工具/确认提示行（Java 侧已本地化，以明文追加）。 */
    public void streamTool(long id, String note) {
        run("ChatAPI.streamTool(" + id + "," + ChatMessagePayload.jsString(note) + ");");
    }

    /** 流式收尾：以 Markdown 定格临时气泡（随后控制器整页重放补齐真实主键）。 */
    public void streamEnd(long id) {
        run("ChatAPI.streamEnd(" + id + ");");
    }

    /** 标记最后一条助手/用户：仅它们分别亮「重新生成」「编辑重发」。 */
    public void setFlags(long lastAssistantId, long lastUserId) {
        run("ChatAPI.setFlags(" + lastAssistantId + "," + lastUserId + ");");
    }

    /** 滚动定位到指定主键并高亮（搜索命中）。 */
    public void reveal(long conversationId) {
        run("ChatAPI.reveal(" + conversationId + ");");
    }

    /** 平滑滚动到底部（「回到底部」按钮）。 */
    public void scrollToBottom() {
        run("ChatAPI.scrollToBottom(true);");
    }

    // ---------- 对比卡 ----------

    public void compareStart(List<Map<String, Object>> columns) {
        run("ChatAPI.compareStart(" + ChatMessagePayload.jsValue(columns) + ");");
    }

    public void compareDelta(int index, String text) {
        run("ChatAPI.compareDelta(" + index + "," + ChatMessagePayload.jsString(text) + ");");
    }

    public void compareNote(int index, String note) {
        run("ChatAPI.compareNote(" + index + "," + ChatMessagePayload.jsString(note) + ");");
    }

    public void compareEnd(int index, String state, Map<String, Object> foot) {
        run("ChatAPI.compareEnd(" + index + "," + ChatMessagePayload.jsString(state) + ","
                + ChatMessagePayload.jsValue(foot) + ");");
    }

    public void compareClear() {
        run("ChatAPI.compareClear();");
    }

    // ---------- 队列 ----------

    /** 就绪则立即执行，否则入队等装载完成后重放。 */
    private void run(String script) {
        if (ready) {
            try {
                engine.executeScript(script);
            } catch (Throwable t) {
                log.warn("聊天脚本执行失败: {}", abbrev(script), t);
            }
        } else {
            pending.addLast(script);
        }
    }

    private static String abbrev(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    /**
     * 控制器实现本接口即可作为桥的运行时事件出口（与 {@link com.gettyio.gim.agent.ui.controller.ChatViewEvents} 同形，
     * 置于 chat 包内避免 chat 反向依赖 controller 包的具体类型暴露）。
     */
    public interface ChatViewEventsHolder extends com.gettyio.gim.agent.ui.controller.ChatViewEvents {
    }
}
