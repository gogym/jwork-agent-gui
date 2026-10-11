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

package com.gettyio.gim.agent.ui.jcef;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gettyio.gim.agent.ui.theme.UiTheme;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.InputMethodRequests;
import javafx.scene.input.InputMethodTextRun;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.EventQueue;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * JCEF(Chromium OSR) 输入框组件：contenteditable 编辑区 + JavaFX 混合输入。
 *
 * <p>替换旧 {@code TextArea}（JavaFX 文本管线只渲单色/灰阶字形，彩色 emoji 无法呈现）。
 * 界面主体是 {@link JcefOsrView} 装载的 {@code composer.html}（CSS/JS 全内联），对外暴露与
 * {@code TextArea} 使用面等价的 API（getText/setText/clear/insertText/positionCaret/
 * getCaretPosition/getLength/requestFocus/setPromptText/textProperty）。</p>
 *
 * <h3>输入分工（绕开 OSR 中文 IME 上游缺陷）</h3>
 * <ul>
 *   <li><b>字符输入</b>：JavaFX KEY_TYPED 经 {@code ComposerAPI.insertText}（execCommand）注入 DOM——
 *       彩色 emoji / 增补平面字符天然支持（executeScript 有 ASCII 转义护栏）；</li>
 *   <li><b>编辑键与剪贴板命令</b>（Backspace/Delete、Ctrl+C/V/X/A）：本地处理——删除经
 *       {@code ComposerAPI.deleteText}（选区整体删 / 按字符词粒度删）；粘贴读系统剪贴板经
 *       {@code ComposerAPI.insertText} 注入；复制、剪切把 DOM 选区镜像直写系统剪贴板
 *       （剪切随后注入删除）；全选经 {@code ComposerAPI.selectAll} 注入。合成 AWT 键盘转发
 *       在键盘经 JavaFX 路由时不产生 Chromium 编辑效果（同 Shift+Enter 软换行的处理教训），
 *       剪贴板命令属同类，一律不赌转发链；</li>
 *   <li><b>其余功能键</b>（方向/Home/End 等）：合成 AWT KeyEvent 转发
 *       （{@link JcefOsrView#forwardKeyEvent}），Chromium 原生处理；</li>
 *   <li><b>中文 IME</b>：系统输入法事件由 JavaFX 层接管（{@link InputMethodRequests} +
 *       {@code onInputMethodTextChanged}，焦点节点两者齐备才会启用 IME 事件）——组合文本经桥渲染为
 *       {@code span.composing} 预编辑串，候选窗经 {@code getTextLocation} 定位到 caret 处。
 *       不让 Chromium 处理 IME，规避 CEF#4070（OSR+Windows 候选窗错位）与屏幕外宿主窗口问题。</li>
 * </ul>
 *
 * <h3>文本一致性</h3>
 * <p>DOM 是唯一真相源：JS 每次文本/选择变化上报 {@code onState}（text/caret/selected/caretRect），
 * Java 侧维护镜像（{@link #textProperty()}）；发送取文本走 {@link #requestSend}（经 JS 读取 DOM
 * 最新全文回传），杜绝「键盘转发异步链路」与「同步读镜像」之间的竞态。</p>
 *
 * <h3>顺序性</h3>
 * <p>所有 Java→JS 脚本经 AWT EDT 队列下发（{@link #execJs}）：键盘转发的合成 AWT 事件同走该队列，
 * 保证「字符插入/光标操作/取文本」相对键盘动作的全局先后顺序。</p>
 *
 * @author gogym
 */
public final class JcefComposer {

    private static final Logger log = LoggerFactory.getLogger(JcefComposer.class);

    /** {@code CefMessageRouter} 注入 JS 的请求/取消函数名（与 composer.js 内的 Q 一致）。 */
    private static final String QUERY_FN = "gimComposerQuery";
    private static final String CANCEL_FN = "gimComposerCancel";

    private final JcefOsrView view;
    private final StackPane host;
    private final ObjectMapper mapper = new ObjectMapper();

    // ---------- 镜像状态（FX 线程读写；JS onState 上报驱动） ----------

    /** 镜像文本（组合态文本不计入）；JS input/selectionchange 上报后同步。 */
    private final StringProperty mirror = new SimpleStringProperty("");
    /** 镜像 caret（UTF-16 码元偏移）。 */
    private int mirrorCaret;
    /** 镜像选中文本（IME 替换语义用）。 */
    private String mirrorSelected = "";
    /** caret 矩形（相对编辑页 viewport）：IME 候选窗定位用，由 JS 随状态上报。 */
    private double caretX;
    private double caretY;
    private double caretH = 18;

    // ---------- 装载与待发脚本 ----------

    private volatile boolean ready;
    /** 页面就绪前的待执行脚本（FX 线程读写；就绪后按序经 EDT 重放）。 */
    private final Deque<String> pending = new ArrayDeque<>();

    // ---------- 外部回调 ----------

    /** Enter（无 Shift）提交回调（由控制器接线到发送流程）。 */
    private Runnable onSubmit;
    /** 在途的「取最新文本」请求回调（sendNow 应答消费，单槽：快速连按时旧请求让位新请求）。 */
    private Consumer<String> pendingSend;
    /** 选区变化回调（composer.js selectionchange 上报驱动）：告知宿主「最近一次选区交互在输入框」。 */
    private Runnable onSelectionChanged;

    /** IME 组合态：组合期间键盘事件一律不转发（按键归输入法消化，Enter 不触发发送）。 */
    private volatile boolean imeComposing;

    /** 构建输入框：装配 OSR 视图、键盘/IME 事件链、主题监听并装载 composer 页面。 */
    public JcefComposer() {
        this.view = new JcefOsrView(QUERY_FN, CANCEL_FN, this::dispatch);
        this.host = view.node();
        this.host.setFocusTraversable(true);

        // 鼠标按下即抢 JavaFX 焦点：IME 事件只路由给焦点节点；鼠标转发由 JcefOsrView 内部完成
        host.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> host.requestFocus());

        // 键盘：编辑键/字符输入走 JS 注入；其余功能键转发到 Chromium；Enter 拦截为提交
        host.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        host.addEventFilter(KeyEvent.KEY_RELEASED, this::onKeyReleased);
        host.addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);

        // IME：焦点节点同时设置 inputMethodRequests + onInputMethodTextChanged 才会启用系统 IME 事件
        // （Scene.focusOwner invalidated 时按此条件调 peer.enableInputMethodEvents）
        host.setInputMethodRequests(new ComposerImeRequests());
        host.setOnInputMethodTextChanged(this::onImeEvent);

        // JavaFX 焦点变化联动 Chromium 焦点（失焦停闪 caret）
        host.focusedProperty().addListener((obs, o, n) -> {
            try {
                view.browser().setFocus(n);
            } catch (Throwable ignored) {
            }
        });

        // 主题联动（注册即刷新一次由 onPageReady 兜底）
        UiTheme.addThemeListener(this::refreshTheme);

        view.setOnLoadEnd(this::onPageReady);
        try {
            view.loadUrl(JcefResources.composerShell(QUERY_FN));
        } catch (Throwable t) {
            log.error("输入框 JCEF 页面装载失败", t);
        }
    }

    // ===================== 对外 API（对齐 TextArea 使用面） =====================

    /** 宿主节点：放入布局（替代原 TextArea 的槽位）。 */
    public StackPane node() {
        return host;
    }

    /** 镜像文本（最近一次 JS 上报的 DOM 全文；组合中文本不计入）。 */
    public String getText() {
        return mirror.get();
    }

    /** 设置全文（镜像即时更新 + JS 异步同步）；光标自动置于文末。 */
    public void setText(String text) {
        String t = text == null ? "" : text;
        mirror.set(t);
        mirrorCaret = t.length();
        execJs("ComposerAPI.setText(" + jsString(t) + ");");
    }

    /** 清空（发送后/新任务共用）。 */
    public void clear() {
        mirror.set("");
        mirrorCaret = 0;
        mirrorSelected = "";
        execJs("ComposerAPI.clear();");
    }

    /**
     * 在 {@code at} 处插入文本（对齐 {@code TextArea.insertText} 语义：不移动 caret）。
     * <p>实际用例（语音转写/模板插入）的 {@code at} 均为「当前光标处」，JS 侧在当前 DOM caret
     * 插入；真实 caret 由 execCommand 后移（体验更好），镜像 caret 保持原位，随后由 onState 自愈校准。</p>
     */
    public void insertText(int at, String text) {
        String t = text == null ? "" : text;
        if (t.isEmpty()) {
            return;
        }
        String cur = mirror.get();
        int pos = Math.max(0, Math.min(at, cur.length()));
        execJs("ComposerAPI.insertText(" + jsString(t) + ");");
        mirror.set(cur.substring(0, pos) + t + cur.substring(pos));
    }

    /** 光标移到指定文本偏移（JS 定位 + 镜像同步）。 */
    public void positionCaret(int pos) {
        int p = Math.max(0, pos);
        mirrorCaret = p;
        execJs("ComposerAPI.setCaret(" + p + ");");
    }

    /** 镜像 caret（UTF-16 偏移）。 */
    public int getCaretPosition() {
        return mirrorCaret;
    }

    /** 输入框当前是否有选中文本（随 selectionchange 上行；Ctrl+C 链路据此让位）。 */
    public boolean hasSelection() {
        return mirrorSelected != null && !mirrorSelected.isEmpty();
    }

    /** 镜像文本长度。 */
    public int getLength() {
        return mirror.get().length();
    }

    /** 镜像文本属性（上下文占用条监听文本变化用）。 */
    public StringProperty textProperty() {
        return mirror;
    }

    /** 焦点：JavaFX 焦点（IME 路由）+ Chromium 焦点（caret 闪烁）+ DOM 焦点。 */
    public void requestFocus() {
        host.requestFocus();
        try {
            view.browser().setFocus(true);
        } catch (Throwable ignored) {
        }
        execJs("ComposerAPI.focus();");
    }

    /** 占位提示文本（对齐 TextArea.setPromptText）。 */
    public void setPromptText(String text) {
        execJs("ComposerAPI.setPlaceholder(" + jsString(text == null ? "" : text) + ");");
    }

    /** 宿主所在场景（对齐 {@code inputField.getScene()}，ownerWindow 取窗用）。 */
    public Scene getScene() {
        return host.getScene();
    }

    /** 接线 Enter（无 Shift）提交动作。 */
    public void setOnSubmit(Runnable onSubmit) {
        this.onSubmit = onSubmit;
    }

    /** 接线选区变化通知（选中文本每次变化即回调）：Ctrl+C 让位判断的「最近交互在输入框」信号。 */
    public void setOnSelectionChanged(Runnable cb) {
        this.onSelectionChanged = cb;
    }

    /**
     * 取 DOM 最新全文并经 FX 线程回调（发送路径专用）。
     * <p>经 EDT 队列向 JS 发 askSend（与键盘转发同一队列，FIFO 保证此前字符已入 DOM），
     * JS 读全文经桥回传。页面未就绪时直接回退镜像（保证功能不挂起）。</p>
     */
    public void requestSend(Consumer<String> onText) {
        if (onText == null) {
            return;
        }
        if (!ready) {
            onText.accept(mirror.get());
            return;
        }
        pendingSend = onText;
        execJs("ComposerAPI.askSend();");
    }

    // ===================== 键盘处理 =====================

    private void onKeyPressed(KeyEvent e) {
        if (imeComposing) {
            return; // 组合中：按键归输入法（确认/翻页/取消），不转发、不提交
        }
        KeyCode code = e.getCode();
        if (code == KeyCode.ENTER) {
            e.consume();
            if (e.isShiftDown()) {
                // 软换行本地注入：与字符输入同一 JS 链路（不依赖合成 AWT 键盘转发——
                // 键盘经 JavaFX 路由时该链路不产生 Chromium 换行）
                execJs("ComposerAPI.insertLineBreak();");
            } else if (onSubmit != null) {
                onSubmit.run();
            }
            return;
        }
        if (code == KeyCode.BACK_SPACE || code == KeyCode.DELETE) {
            // 删除键本地注入：不依赖合成 AWT 键盘转发（键盘经 JavaFX 路由时该链路
            // 不产生 Chromium 编辑效果——同 Shift+Enter 换行的处理）；Ctrl 修饰按词删
            e.consume();
            execJs("ComposerAPI.deleteText(" + (code == KeyCode.DELETE) + ", " + e.isControlDown() + ");");
            return;
        }
        if (e.isShortcutDown() && code == KeyCode.V) {
            // 粘贴本地注入：读系统剪贴板纯文本经 ComposerAPI.insertText 写入 DOM（有选区即
            // 替换、多行原样插入，与 composer.js 的 DOM paste 监听同语义）——剪贴板命令与
            // 删除/换行同类，走合成 AWT 转发链不产生 Chromium 编辑效果
            e.consume();
            String text = Clipboard.getSystemClipboard().getString();
            if (text != null && !text.isEmpty()) {
                execJs("ComposerAPI.insertText(" + jsString(text) + ");");
            }
            return;
        }
        if (e.isShortcutDown() && code == KeyCode.C) {
            // 复制本地注入：DOM 选区镜像直写系统剪贴板；无选区不动作（Scene 级快捷键过滤器
            // 仅在输入框有选区时放行到本链，其余场合由聊天页复制接住）
            e.consume();
            if (hasSelection()) {
                ClipboardContent content = new ClipboardContent();
                content.putString(mirrorSelected);
                Clipboard.getSystemClipboard().setContent(content);
            }
            return;
        }
        if (e.isShortcutDown() && code == KeyCode.X) {
            // 剪切本地注入：先写剪贴板（镜像选区），再注入删除（非折叠选区整体删除）；
            // 无选区不动作（同原生语义）
            e.consume();
            if (hasSelection()) {
                ClipboardContent content = new ClipboardContent();
                content.putString(mirrorSelected);
                Clipboard.getSystemClipboard().setContent(content);
                execJs("ComposerAPI.deleteText(false, false);");
            }
            return;
        }
        if (e.isShortcutDown() && code == KeyCode.A) {
            // 全选本地注入：DOM Range 精确选中编辑区内容（execCommand('selectAll') 会
            // 波及编辑区之外，不用）
            e.consume();
            execJs("ComposerAPI.selectAll();");
            return;
        }
        if (isPassthroughKey(code)) {
            return;
        }
        view.forwardKeyEvent(e, java.awt.event.KeyEvent.KEY_PRESSED, code.getCode(), (char) 0);
    }

    private void onKeyReleased(KeyEvent e) {
        if (imeComposing) {
            return;
        }
        KeyCode code = e.getCode();
        if (isPassthroughKey(code)) {
            return;
        }
        view.forwardKeyEvent(e, java.awt.event.KeyEvent.KEY_RELEASED, code.getCode(), (char) 0);
    }

    private void onKeyTyped(KeyEvent e) {
        if (imeComposing) {
            return;
        }
        String ch = e.getCharacter();
        if (ch == null || ch.isEmpty() || ch.codePointCount(0, ch.length()) != 1) {
            return;
        }
        int cp = ch.codePointAt(0);
        if (cp < 0x20 || cp == 0x7F) {
            return; // 控制字符（Enter/Tab/退格的 KEY_TYPED 残余）不注入
        }
        // 字符插入走 JS（execCommand insertText）：不依赖 AWT KEY_TYPED 的 char 通道
        // （后者为 BMP 单 char，承载不了增补平面 emoji；且 CESU-8 JNI 桥对代理对不安全）
        execJs("ComposerAPI.insertText(" + jsString(ch) + ");");
    }

    /** 不转发的键：修饰键自身（组合态由修饰符位携带）、Tab（维持 JavaFX 焦点遍历现状）。 */
    private static boolean isPassthroughKey(KeyCode code) {
        return switch (code) {
            case SHIFT, CONTROL, ALT, META, CAPS, NUM_LOCK, SCROLL_LOCK, WINDOWS, CONTEXT_MENU, TAB -> true;
            default -> false;
        };
    }

    // ===================== IME（JavaFX 输入法事件 → JS 组合渲染） =====================

    /**
     * IME 文本事件（组合更新/提交）：组合串渲染为预编辑 span，提交串插入 DOM。
     * <p>事件序列：组合中每次按键 → composed 非空 committed 空（更新预编辑）；
     * 选字确认 → committed 非空（先清预编辑再插入提交文本）。</p>
     */
    private void onImeEvent(InputMethodEvent e) {
        List<InputMethodTextRun> runs = e.getComposed();
        StringBuilder composed = new StringBuilder();
        if (runs != null) {
            for (InputMethodTextRun run : runs) {
                if (run.getText() != null) {
                    composed.append(run.getText());
                }
            }
        }
        String committed = e.getCommitted() == null ? "" : e.getCommitted();
        imeComposing = composed.length() > 0;
        if (imeComposing) {
            execJs("ComposerAPI.setComposition(" + jsString(composed.toString()) + ");");
        } else {
            execJs("ComposerAPI.clearComposition();");
        }
        if (!committed.isEmpty()) {
            execJs("ComposerAPI.commitText(" + jsString(committed) + ");");
        }
    }

    /**
     * IME 环境请求：候选窗定位 / 偏移换算 / 选中查询。
     * <p>均已由 JavaFX 在 FX 线程回调（Glass IME 桥经 Scene 的 InputMethodRequestsDelegate 转发）。</p>
     */
    private final class ComposerImeRequests implements InputMethodRequests {

        /** IME 候选窗锚点：caret 矩形由页面坐标换算到宿主本地再转屏幕坐标（采不到时兜底宿主底缘）。 */
        @Override
        public Point2D getTextLocation(int offset) {
            // 候选窗锚点：caret 矩形（页面坐标）换算到宿主本地 → 屏幕坐标
            Insets pad = host.getPadding() == null ? Insets.EMPTY : host.getPadding();
            double lx = pad.getLeft() + caretX;
            double ly = pad.getTop() + caretY + caretH + 2;
            Point2D p = host.localToScreen(lx, ly);
            if (p == null) {
                p = host.localToScreen(0, Math.max(0, host.getHeight()));
            }
            return p == null ? new Point2D(0, 0) : p;
        }

        @Override
        public int getLocationOffset(int x, int y) {
            return mirrorCaret;
        }

        @Override
        public void cancelLatestCommittedText() {
            // 无撤销锚点：少数输入法的「撤销最近提交」不可用（可接受）
        }

        @Override
        public String getSelectedText() {
            return mirrorSelected == null || mirrorSelected.isEmpty() ? null : mirrorSelected;
        }
    }

    // ===================== JS→Java 桥 =====================

    private void dispatch(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            String fn = root.path("fn").asText("");
            JsonNode a = root.path("args");
            switch (fn) {
                case "onState" -> {
                    String text = str(a, 0);
                    if (!text.equals(mirror.get())) {
                        mirror.set(text);
                    }
                    mirrorCaret = (int) lng(a, 1);
                    String selected = str(a, 2);
                    boolean selectionChanged = !selected.equals(mirrorSelected == null ? "" : mirrorSelected);
                    mirrorSelected = selected;
                    caretX = dbl(a, 3);
                    caretY = dbl(a, 4);
                    caretH = dbl(a, 6);
                    if (selectionChanged && onSelectionChanged != null) {
                        onSelectionChanged.run();
                    }
                }
                case "sendNow" -> {
                    String text = str(a, 0);
                    Consumer<String> cb = pendingSend;
                    pendingSend = null;
                    if (cb != null) {
                        cb.accept(text);
                    }
                }
                case "enterSend" -> {
                    // JS 键盘兜底（composer.js keydown）：Enter 未走 JavaFX 键盘链
                    // （键盘焦点被屏幕外 OSR 宿主窗口取得等平台差异）时由页面转交提交
                    log.info("Enter 由 JS 兜底路径转交提交（键盘未经过 JavaFX）");
                    if (onSubmit != null) {
                        onSubmit.run();
                    }
                }
                default -> log.debug("忽略未知输入框桥方法: {}", fn);
            }
        } catch (Throwable t) {
            log.warn("输入框桥事件处理失败: {}", abbrev(json), t);
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

    // ===================== 装载与脚本下发 =====================

    /** 页面主框架装载完成：置就绪、按序重放挂起脚本、刷主题。 */
    private void onPageReady() {
        ready = true;
        int n = pending.size();
        String script;
        while ((script = pending.pollFirst()) != null) {
            final String s = script;
            EventQueue.invokeLater(() -> view.executeScript(s));
        }
        log.info("输入框页面 load 完成，重放挂起脚本 {} 条", n);
        refreshTheme();
    }

    /** 主题同步（浅/深变量切换）。 */
    private void refreshTheme() {
        String mode = UiTheme.mode() == UiTheme.Mode.DARK ? "dark" : "light";
        execJs("ComposerAPI.setTheme('" + mode + "');");
    }

    /**
     * Java→JS 脚本下发：未就绪入队；就绪后统一经 AWT EDT 派发。
     * <p>EDT 是副本进程内键盘转发（合成 AWT 事件）的同一队列，FIFO 保证脚本动作
     * 与键盘动作的到达顺序，是 {@link #requestSend} 正确性的基础。</p>
     */
    private void execJs(String script) {
        if (!ready) {
            pending.addLast(script);
            return;
        }
        EventQueue.invokeLater(() -> view.executeScript(script));
    }

    /** JS 字符串字面量转义（单引号包围；非 ASCII 由 {@link JcefOsrView#asciiEscape} 统一处理）。 */
    private static String jsString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\u2028' -> sb.append("\\u2028");
                case '\u2029' -> sb.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u").append(hex4(c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    private static String hex4(char c) {
        String h = Integer.toHexString(c);
        return "0".repeat(4 - h.length()) + h;
    }

    private static String abbrev(String s) {
        return s == null ? "null" : (s.length() <= 120 ? s : s.substring(0, 120) + "…");
    }
}
