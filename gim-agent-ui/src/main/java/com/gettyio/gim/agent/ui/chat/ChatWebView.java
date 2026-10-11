package com.gettyio.gim.agent.ui.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.jcef.JcefOsrView;
import com.gettyio.gim.agent.ui.jcef.JcefResources;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天区整页 JCEF(Chromium OSR) 宿主：把 classpath 里的 {@code chat.css / marked.min.js / purify.min.js / chat.js}
 * 提取到临时目录并以 {@code file://} 装载内联 HTML 骨架；页面就绪后由 {@code bridge-shim.js} 定义的
 * {@code window.javaBridge} 经 {@code CefMessageRouter} 把 JS→Java 事件回抛给 {@link ChatBridge}，
 * Java→JS 则经 {@code browser.executeScript("ChatAPI.xxx(...)")} 驱动前端 {@code chat.js} 渲染。
 * <p>对外暴露与旧 WebView 宿主完全等价的 Java API（renderHistory/prependOlder/stream*…），控制器只与本类交互。</p>
 * <p>Chromium 冷启动较慢：装载完成前的调用先入 {@link #pending} 队列，就绪后按序重放，杜绝时序竞争。</p>
 */
public final class ChatWebView {

    private static final Logger log = LoggerFactory.getLogger(ChatWebView.class);

    /** {@code CefMessageRouter} 注入 JS 的请求/取消函数名（须与 {@link JcefResources} 垫片一致）。 */
    private static final String QUERY_FN = "gimQuery";
    private static final String CANCEL_FN = "gimCancel";

    private final ChatBridge bridge;
    private final JcefOsrView view;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile boolean ready;
    /** 页面最近一次上报的贴底状态（OSR 过渡帧垂直锚定用；默认贴底）。 */
    private volatile boolean atBottom = true;
    /** 就绪前的待执行脚本队列（FX 线程读写）。 */
    private final Deque<String> pending = new ArrayDeque<>();

    /**
     * @param actions 气泡动作回调（复制/朗读/重生成/编辑重发…），由控制器实现
     * @param events  运行时事件回调（滚动/链接/图片/代码/右键/空态/就绪），由控制器实现
     */
    public ChatWebView(MessageActionListener actions, ChatViewEventsHolder events) {
        this.bridge = new ChatBridge(actions, events);
        this.view = new JcefOsrView(QUERY_FN, CANCEL_FN, this::dispatch);
        // 贴底状态回传 OSR：垂直尺寸变化时过渡帧据此锚定（贴底→帧底对齐，否则→顶端对齐），
        // 与页面侧 resize 补滚一致，消除上下开合/缩放收尾的跳变。
        this.view.setBottomPinnedSupplier(() -> atBottom);
        this.view.setOnLoadEnd(this::onPageReady);
        try {
            this.view.loadUrl(JcefResources.chatShell(QUERY_FN));
        } catch (Throwable t) {
            log.error("聊天 JCEF 页面装载失败", t);
        }
        // 主题/字号/语言联动：注册监听即时同步到前端
        UiTheme.addThemeListener(this::refreshTheme);
        I18n.onChange(lang -> refreshTheme());
    }

    /** 宿主节点：控制器把它放进 chatHost 并撑满。 */
    public StackPane node() {
        StackPane host = view.node();
        host.getStyleClass().add("chat-web-host");
        return host;
    }

    /** 是否已完成装载并就绪。 */
    public boolean isReady() {
        return ready;
    }

    // ---------- 装载与桥接 ----------

    /** 页面主框架装载完成：置就绪并按序重放挂起脚本（对齐旧 {@code installBridge} 语义）。 */
    private void onPageReady() {
        ready = true;
        int n = pending.size();
        String script;
        while ((script = pending.pollFirst()) != null) {
            view.executeScript(script);
        }
        log.info("聊天页面 load 完成，重放挂起脚本 {} 条", n);
    }

    /**
     * JS→Java 派发：解析垫片送来的 {@code {fn,args}} JSON，转调 {@link ChatBridge} 对应方法。
     * <p>已在 FX 线程回调（{@link JcefOsrView} 的 {@code onQuery} 经 {@code Platform.runLater} 派发），
     * 方法体可直接操作 JavaFX。任一分支异常均被吞掉，绝不由单个脏事件中断渲染。</p>
     */
    private void dispatch(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            String fn = root.path("fn").asText("");
            JsonNode a = root.path("args");
            switch (fn) {
                case "onReady" -> {
                    log.info("收到 JS onReady（CefMessageRouter 桥已连通）");
                    bridge.onReady();
                }
                case "onAction" -> bridge.onAction(str(a, 0), lng(a, 1), str(a, 2));
                case "onContext" -> bridge.onContext(lng(a, 0), dbl(a, 1), dbl(a, 2));
                case "onScrollTop" -> bridge.onScrollTop();
                case "onScrollState" -> {
                    // 先记录贴底状态供 OSR 过渡帧锚定，再转控制器（回到底部按钮显隐）
                    atBottom = bool(a, 0);
                    bridge.onScrollState(atBottom, bool(a, 1));
                }
                case "onTrimmed" -> bridge.onTrimmed(lng(a, 0));
                case "onLink" -> bridge.onLink(str(a, 0));
                case "onImage" -> bridge.onImage(str(a, 0));
                case "onCopyCode" -> bridge.onCopyCode(str(a, 0));
                case "onPreviewCode" -> bridge.onPreviewCode(str(a, 0), str(a, 1));
                case "onDismissMenu" -> bridge.onDismissMenu();
                case "onEmpty" -> {
                    boolean empty = bool(a, 0);
                    log.info("收到 JS onEmpty={}", empty);
                    bridge.onEmpty(empty);
                }
                default -> log.debug("忽略未知聊天桥方法: {}", fn);
            }
        } catch (Throwable t) {
            log.warn("聊天桥事件处理失败: {}", abbrev(json), t);
        }
    }

    private static String str(JsonNode args, int i) {
        return args.isArray() && i < args.size() ? args.get(i).asText("") : "";
    }

    private static long lng(JsonNode args, int i) {
        return args.isArray() && i < args.size() ? args.get(i).asLong(0) : 0L;
    }

    private static double dbl(JsonNode args, int i) {
        return args.isArray() && i < args.size() ? args.get(i).asDouble(0) : 0d;
    }

    private static boolean bool(JsonNode args, int i) {
        return args.isArray() && i < args.size() && args.get(i).asBoolean(false);
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

    // ---------- 键盘 ----------

    /**
     * Scene 级快捷键转发（Ctrl+C 复制等）：聊天页选区由 Chromium 维护，把按键合成 AWT
     * 事件直送聊天 Chromium（与输入框的键盘转发同链），由浏览器执行对应编辑命令。
     * 转发前确保浏览器持有焦点：编辑命令只作用于有焦点浏览器的选区。
     */
    public void forwardShortcut(KeyEvent e, int awtEventId) {
        try {
            view.browser().setFocus(true);
        } catch (Throwable ignored) {
            // 浏览器未就绪时忽略：此时也无选区可复制
        }
        view.forwardKeyEvent(e, awtEventId, e.getCode().getCode(), (char) 0);
    }

    // ---------- 队列 ----------

    /** 就绪则立即执行，否则入队等装载完成后重放。 */
    private void run(String script) {
        if (ready) {
            try {
                view.executeScript(script);
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
