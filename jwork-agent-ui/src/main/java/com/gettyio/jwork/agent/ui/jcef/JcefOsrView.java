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

package com.gettyio.jwork.agent.ui.jcef;

import com.gettyio.jwork.agent.ui.i18n.I18n;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefRequestHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.event.InputEvent;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * JCEF OSR 浏览器 + JavaFX Canvas 复合视图。
 *
 * <p>渲染链路：Chromium 离屏渲染出 BGRA 帧 → {@code onPaint} 回调 → 拷进 {@link WritableImage}
 * （{@link PixelFormat#getByteBgraPreInstance()}）→ 画到 FX {@link Canvas}。圆角 clip / 动画 / 布局
 * 全部仍由 JavaFX 负责，与旧 WebView 宿主一致。</p>
 *
 * <p>OSR 产帧前提：JCEF 内置 {@code CefBrowserOsr} 需其 {@code GLCanvas} 被 realize 到 displayable
 * 容器才会同步 native 尺寸并派发 {@code OnPaint}。这里用一个屏幕外的可见 {@link Frame} 承载画布
 * （真桌面会话可正常 realize；无桌面的 CI/远程会话无法产帧，属环境限制而非框架缺陷）。</p>
 *
 * <p>线程：{@code onPaint}、{@code onQuery} 均在 Chromium/AWT 线程回调，凡触碰 JavaFX 一律
 * {@link Platform#runLater} 派发回 FX 线程。JS→Java 经 {@link CefMessageRouter}（页面注入
 * {@code window.<queryFn>}），由 {@code requestHandler} 收 JSON 载荷。</p>
 *
 * @author gogym
 */
public final class JcefOsrView {

    private static final Logger log = LoggerFactory.getLogger(JcefOsrView.class);

    /**
     * 在场视图登记：主窗聊天、Mermaid 卡片、输入框、预览窗都是常驻实例，没人调 {@link #dispose()}。
     * <p>停机时先逐个关掉浏览器，{@code CefApp.dispose()} 才不会面对仍开着的 CefBrowser——那时
     * 渲染子进程继续占着 cache 目录句柄，下一次启动就被「文件被占用」卡在解包/引导阶段。</p>
     */
    private static final Set<JcefOsrView> LIVE = ConcurrentHashMap.newKeySet();

    /**
     * 释放全部在场视图（浏览器、消息路由、屏幕外宿主 Frame）。
     * <p>须在 JavaFX 应用线程调用：{@link #dispose()} 要停 FX 动画计时器。由应用停机路径
     * （{@code JworkAgentApplication.stop()}）在关闭组合根之前调用。</p>
     */
    public static void disposeAll() {
        for (JcefOsrView v : LIVE.toArray(new JcefOsrView[0])) {
            try {
                v.dispose();
            } catch (Throwable t) {
                log.warn("JCEF 视图释放失败", t);
            }
        }
        LIVE.clear();
    }

    private final Canvas canvas = new Canvas();
    private final StackPane host = new StackPane(canvas);
    /** CEF 三件套：后台引导就绪后在 FX 线程装配（{@link #attachCef}）；就绪前为 null，消费点均须判空。 */
    private volatile CefClient client;
    private volatile CefBrowser browser;
    private volatile CefMessageRouter router;
    /** JS 桥参数（构造时给出，装配时使用）。 */
    private final String queryFn;
    private final String cancelFn;
    private final Consumer<String> requestHandler;
    /** 是否挂引导占位层：输入框槽位传 false，引导期全窗只留聊天区一处居中提示。 */
    private final boolean bootOverlayEnabled;
    /** 视图已释放：迟到的引导回调不得再往死视图上装配浏览器。 */
    private volatile boolean disposed;
    /** CEF 装配前到达的脚本暂存（调用方可能在 EDT），浏览器创建后按序补发。 */
    private final Queue<String> pendingScripts = new ConcurrentLinkedQueue<>();
    /** 引导占位层（FX 线程）：CefApp 就绪前显示；失败换错误文案 + 重试按钮；装配成功后移除。 */
    private VBox bootOverlay;
    private ProgressIndicator bootSpinner;
    private Label bootLabel;
    private Button bootRetry;
    private volatile Frame realize;
    private volatile Runnable loadEnd;
    /** native browser 创建前到达的装载请求暂存（native 在 GLCanvas 首次 paint 时才真正创建）。 */
    private String pendingUrl;
    /**
     * 本页唯一可信的文档目标：由 Java 侧 {@link #loadUrl(String)} 显式给出（聊天骨架、Mermaid 页、
     * 预览报表各为其自身的那一个）。页面内容属不可信输入——Markdown 出自模型、预览 HTML 出自工具，
     * 若允许它们把顶层框架导航到远端/data 文档，那份文档就能直接调用本视图注入的 JS 桥，
     * 桥的载荷又直通 Java 业务链路，等于把「渲染一层」升级成「执行任意本机调用」。
     * 导航闸门与桥闸门都以它为准。
     */
    private volatile String trustedUrl;
    /** native browser 是否已创建：首个主框架 {@code onLoadStart} 即为其存在的确证。 */
    private boolean nativeCreated;
    /** {@link #pendingUrl}/{@link #nativeCreated} 的并发门闩（loadUrl 在 FX 线程、装载事件在 CEF 线程）。 */
    private final Object loadGate = new Object();
    /** 最近一次 {@code onPaint} 产出的位图（供离屏快照/导出直接读取，无需 FX 场景）。 */
    private volatile WritableImage lastFrame;
    /** 最近一帧「可信完整」帧：非重排期到达的帧（或重排平静后上屏的那帧）。拖动过渡重绘与
     *  常规上屏只用它——重排窗口期内 lastFrame 会被「半新半旧」的分块半成品刷新，若拖动重绘
     *  直接读取，会把拼贴画面画上屏（实测：快速连续拉大时出现上半旧布局+下半新布局的错位画面）。 */
    private WritableImage stableFrame;
    /** OSR 帧双缓冲槽（仅 FX 线程读写）：写入总是选「非 stableFrame」的槽，使 stableFrame 的
     *  像素在下次更新前保持冻结（setPixels 会立刻改写缓冲，单缓冲复用会让「稳定帧」被覆写）。 */
    private WritableImage frameBufA;
    private WritableImage frameBufB;
    /** 首帧诊断日志只打一次。 */
    private volatile boolean firstFrameLogged;
    /** syncSize 首次日志只打一次。 */
    private volatile boolean syncLogged;
    /** syncSize 最新目标尺寸（窗口拖动 resize 高频触发，合并去重只保留最新值，防 AWT 队列积压）。 */
    private volatile int pendingSizeW = -1;
    private volatile int pendingSizeH = -1;
    /** 是否已有 setSize 任务在 AWT 队列中等待（保证队列中至多一个 resize 任务）。 */
    private final java.util.concurrent.atomic.AtomicBoolean sizeTaskQueued =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** 最近一次实际下发的组件尺寸（相同则跳过 setSize，免无谓的 wasResized 全页重排）。 */
    private volatile int lastSetSizeW = -1;
    private volatile int lastSetSizeH = -1;
    /** 最近一次按下且未释放的按钮（AWT 码）：拖动修饰符的兜底来源（FX 线程读写）。 */
    private int pressedButton = java.awt.event.MouseEvent.NOBUTTON;
    /** resize 停止后的 trailing 下发器：保证最终尺寸一定送达 CEF（仅 FX 线程）。 */
    private javafx.animation.PauseTransition sizeFlushDelay;
    /** 最近一次尺寸变化事件时刻（ns）：resize 观测日志据此度量「静止了多久才下发」（FX 写、AWT 读）。 */
    private volatile long lastSizeEventNs = 0L;
    /** 页面「贴底跟随」状态提供者（聊天页报 atBottom）：过渡帧垂直锚定据此贴底/顶对齐；
     *  未设置（null）视为非贴底——文档流页面顶端对齐（重排保留 scrollTop，视口顶端不动）。 */
    private volatile java.util.function.BooleanSupplier bottomPinnedSupplier;
    /** 「重排敏感期」截止（ns）：CEF 收到新尺寸后，在新布局光栅化完成前会持续输出「旧场景+新场景」
     *  的分块拼贴半成品帧（实测：快速拉大时出现上半旧布局+下半新布局的错位画面；CEF 官方
     *  issue #4166 确认 OnPaint 会间歇给出「全幅但视觉不完整的中间合成帧」且无就绪信号）。
     *  期内帧一律不上屏，待「连续两帧内容一致」或超时兜底后收尾（见 handleFrameInResizeSensitive）。
     *  写于 AWT(setSize) 侧与 FX 侧。 */
    private volatile long resizeGuardUntilNs = 0L;
    /** 本次重排敏感期起始（ns，写于 AWT setSize 侧）：连续帧流的兜底基准 {@link #RESIZE_HARD_MAX_MS}。 */
    private volatile long resizeGuardStartNs = 0L;
    /** 本代际（本轮 setSize 后）是否已收到过帧：false 时任何帧都不得即时上屏（防首帧晚到绕开敏感期）。 */
    private volatile boolean resizeFirstFrameSeen = true;
    /** 最近一张完整帧（stableFrame）的锐度基准（FX 线程）：敏感期 settle 的锐度闸门据此辨别
     *  拉伸/未光栅完成的模糊帧（其相邻像素差峰值显著低于原生渲染帧）。-1 = 尚未采样。 */
    private int stableSharpness = -1;
    /** 本代际是否已打印过「settle 被闸门拒绝」日志（连续拒绝只记首条，防帧流刷屏）。 */
    private boolean settleRejectLogged;
    /** 敏感期内上一帧的采样签名（FX 线程）：连续两帧签名一致 = 画面已稳定（重排完成）。 */
    private byte[] prevFrameSig;
    private int prevFrameSigW = -1;
    private int prevFrameSigH = -1;
    /** 本轮验证已请求的重绘次数（FX 线程）：未达成「连续帧一致」或耗尽前，用 invalidate 主动取帧比对。 */
    private int invalidateRetries = 0;
    /** CefBrowser_N#invalidate 的反射句柄（无公共 API）：请求一次强制重绘用于收尾验证。 */
    private java.lang.reflect.Method invalidateMethod;
    /** 重排收尾的「帧流平静」定时器（仅 FX 线程）：每帧续期；平静后发起 invalidate 取验证帧。 */
    private javafx.animation.PauseTransition resizeHold;
    /** 重排收尾平静阈值（ms）：跨过 Chromium 分块光栅化的批次间隙（间隙内的半成品帧不得上屏）。
     *  220ms：聊天页光栅化批次间隙为几十 ms 量级，220 已覆盖并留余量；原 500ms 使「单帧流」
     *  收尾（平静→invalidate→比对）多等约 280ms。若偶发提前收尾（半成品上屏），后续修正帧
     *  会在冷却期内走比对路径重新 settle 覆盖，兜底自愈。 */
    private static final long RESIZE_QUIET_MS = 220;
    /** 尺寸静止后下发 CEF 的静默阈值（ms）：resize 事件流每来一次即重置计时，只有真正静止
     *  才下发最终尺寸。80ms 足以合并一次动作的多批布局 pass（脉冲间隔 16-33ms），同时把终端
     *  开合/最大化等一次性变化的等待从 150ms 缩短——画布跟手与锚定重绘已保证过渡视觉，
     *  多余的等待纯属滞后。 */
    private static final long SIZE_FLUSH_MS = 80;
    /** 后续验证轮次的平静间隔（ms）：首轮用 {@link #RESIZE_QUIET_MS} 跨批次间隙；后续轮次画面
     *  已临近完成，缩短等待加速收尾（每轮省 120ms）。 */
    private static final long RESIZE_QUIET_TAIL_MS = 100;
    /** 锐度闸门采样线数：均匀取水平线段，统计相邻像素 |ΔG| 峰值的 P90 作为帧锐度。 */
    private static final int SHARPNESS_LINES = 64;
    /** 每条采样线的连续像素数（产生 31 个 1px 相邻对）。 */
    private static final int SHARPNESS_SEG = 32;
    /** 锐度闸门启用下限：基准锐度低于此值（低对比/空白页面）时放弃检测，不误拒。 */
    private static final int SHARPNESS_BASELINE_MIN = 40;
    /** 锐度闸门比率：帧锐度 < 基准×该值 → 判为拉伸/未光栅完成的模糊帧，不允许收敛上屏。 */
    private static final double SHARPNESS_MIN_RATIO = 0.5;
    /** 方向结构闸门：十字采样臂的峰值一方达「强」、另一方低于「弱」→ 记一个失衡点。 */
    private static final int AXIS_STRONG = 60;
    private static final int AXIS_WEAK = 12;
    /** 失衡点占比超过该比例（21/64）→ 判为压缩/错位中间态帧，不允许收敛上屏。 */
    private static final int AXIS_MISMATCH_MAX = 64 / 3;
    /** 敏感期/冷却长度（ms）：setSize 后及收尾后各延长一次——防拖后滚动帧误即时上屏、防收尾过早。 */
    private static final long RESIZE_GUARD_MS = 1200;
    /** 连续帧流兜底（ms）：帧流不断（页面动画/流式叠加拖动）时放弃攒帧保护回到即时，防画面冻结。 */
    private static final long RESIZE_HARD_MAX_MS = 3000;
    /** 收尾验证最多请求的重绘帧数：每次「平静→invalidate→比对」为一轮，最多 3 轮。 */
    private static final int RESIZE_VERIFY_MAX = 3;

    // ===== 合成拖动兜底（远程桌面等环境：按住拖动期间移动事件不进入 JavaFX） =====
    /** 最近一次真实指针事件（move/drag/press）时间戳（ns）：兜底轮询据此避免与真实事件重复。 */
    private volatile long lastRealPointerNs = 0L;
    /** 兜底轮询器（仅 FX 线程操作）。 */
    private javafx.animation.Timeline dragPoll;
    /** AWT 屏幕坐标 → JavaFX 屏幕坐标 校准偏移（press 时刻双源采样）。 */
    private double screenCalX = Double.NaN;
    private double screenCalY = Double.NaN;
    /** 上一次轮询到的 JavaFX 屏幕坐标。 */
    private double pollLastX = Double.NaN;
    private double pollLastY = Double.NaN;
    /** 兜底轮询启动时间（防幽灵拖动的总时长上限）。 */
    private long pollStartNs = 0L;
    /** JNA 可用性探测结果（null=未探测）。 */
    private static volatile Boolean jnaOk = null;

    /** user32 的 GetAsyncKeyState：读取全局左键物理状态（兜底轮询判定「真实松开」）。 */
    private interface User32 extends com.sun.jna.Library {
        short GetAsyncKeyState(int vKey);
        User32 INSTANCE = com.sun.jna.Native.load("user32", User32.class);
    }
    /** 滚轮一格（JavaFX 的 deltaY=±40）对应 AWT wheelRotation=±1。 */
    private static final double WHEEL_NOTCH = 40.0;
    /**
     * CEF 世界的滚轮“一格”= WHEEL_DELTA = 120（Qt‑CEF 等主流集成的标准值）。
     * JCEF 的 JNI（CefBrowser_N.cpp SendMouseWheelEvent）把 {@code getUnitsToScroll()}
     * （= scrollAmount × wheelRotation）原样作为 delta 传给 CEF、无额外放大，
     * 故这里把 scrollAmount 定为 120，使 JavaFX 一格 → delta=120，与原生窗口滚轮同速。
     */
    private static final int WHEEL_DELTA_UNITS = 120;
    /** 帧右缘「滚动条搬运带」宽度（px）：拖动过渡期间把该带复制到画布右缘实现滚动条贴边。 */
    private static final int EDGE_BAR = 20;

    /**
     * @param queryFn       JS 侧发起请求的函数名（注入到 {@code window.<queryFn>}）
     * @param cancelFn      JS 侧取消请求的函数名（注入到 {@code window.<cancelFn>}）
     * @param requestHandler 收到 JS→Java 请求（JSON 字符串）时的处理，将在 FX 线程回调；可为 null
     */
    public JcefOsrView(String queryFn, String cancelFn, Consumer<String> requestHandler) {
        this(queryFn, cancelFn, requestHandler, true);
    }

    /**
     * @param bootOverlayEnabled 是否显示引导占位层：输入框槽位传 false——引导期只保留聊天区
     *                           一处居中提示，避免聊天区与输入框双指示；失败时的错误与重试
     *                           也由聊天区占位层承担（重试为全局重绑，本视图随同恢复）。
     */
    public JcefOsrView(String queryFn, String cancelFn, Consumer<String> requestHandler, boolean bootOverlayEnabled) {
        this.queryFn = queryFn;
        this.cancelFn = cancelFn;
        this.requestHandler = requestHandler;
        this.bootOverlayEnabled = bootOverlayEnabled;
        // 尺寸完全由 CEF 帧驱动，脱离父布局计算：Canvas 属非 resizable 节点，其宽高会被 StackPane
        // 计入 min/pref 尺寸——窗口拉宽后 canvas 变大并把宿主的最小宽度撑大，窗口缩小时布局被卡住
        // 缩不回（表现为内容不缩回、画布被居中偏移）。取消托管后宿主尺寸完全随容器，画布定位固定左上。
        canvas.setManaged(false);
        LIVE.add(this);
        installBootOverlay();

        // 宿主尺寸变化 → 同步 OSR native 尺寸，令 Chromium 按真实视口重排重绘
        host.widthProperty().addListener((obs, o, n) -> syncSize(n.doubleValue(), host.getHeight()));
        host.heightProperty().addListener((obs, o, n) -> syncSize(host.getWidth(), n.doubleValue()));

        // OSR 下浏览器组件（GLCanvas）藏在屏幕外帧里，收不到任何真实输入；把 JavaFX 画布上的鼠标
        // 事件合成为 AWT 事件并 dispatch 给浏览器组件（CefBrowserOsr 已在其中注册 Mouse/Wheel 监听器，
        // 会转成 sendMouse*Event 送达 Chromium）——否则滚轮、拖动滚动条、点击、悬停全部失效。
        installInputForwarding();

        // CEF 装配延迟到后台引导就绪：首启/数据目录丢失后 natives 可能需要解包/下载（数百 MB），
        // 同步装配会冻住 FX 线程——主窗与登录门永远轮不到。就绪前由占位层接管显示；
        // loadUrl/executeScript 各有暂存机制，天然兼容延迟装配。
        subscribeBoot();
    }

    // ---------- 引导订阅与延迟装配 ----------

    /** 占位层：转圈 + 状态文案 + 重试按钮（初始隐藏）；pickOnBounds 拦住鼠标穿透到画布。 */
    private void installBootOverlay() {
        if (!bootOverlayEnabled) {
            return;
        }
        bootSpinner = new ProgressIndicator();
        bootSpinner.setPrefSize(28, 28);
        bootLabel = new Label(I18n.t("jcef.preparing"));
        bootLabel.getStyleClass().add("splash-status");
        bootLabel.setWrapText(true);
        bootLabel.setMaxWidth(360);
        bootRetry = new Button(I18n.t("jcef.retry"));
        bootRetry.setVisible(false);
        bootRetry.setManaged(false);
        bootRetry.setOnAction(e -> rebindAll());
        bootOverlay = new VBox(10, bootSpinner, bootLabel, bootRetry);
        bootOverlay.setAlignment(Pos.CENTER);
        bootOverlay.setPickOnBounds(true);
        host.getChildren().add(bootOverlay);
    }

    /**
     * 重试 = 全局重绑：引导失败后各视图持有的是各自那次失败的未来，只重绑本视图会让其余视图
     * （尤其无占位层的输入框）永停旧未来。重建引导后全体在场视图重新订阅同一新未来；
     * 已装配视图的 attachCef 有 browser 判空守卫，重订阅对其无副作用。FX 线程调用。
     */
    private static void rebindAll() {
        for (JcefOsrView v : LIVE.toArray(new JcefOsrView[0])) {
            v.subscribeBoot();
        }
    }

    /** 订阅进程级引导未来：就绪在 FX 线程装配；失败显示错误 + 重试（重订阅即重新引导）。 */
    private void subscribeBoot() {
        showBootPreparing();
        JcefRuntime.get().appAsync().whenComplete((app, err) -> Platform.runLater(() -> {
            if (disposed) {
                return;
            }
            if (err != null || app == null) {
                showBootError(err);
                return;
            }
            try {
                attachCef(app);
            } catch (Throwable t) {
                log.error("JCEF 视图装配失败", t);
                showBootError(t);
            }
        }));
    }

    private void showBootPreparing() {
        if (bootOverlay == null) {
            return;
        }
        bootSpinner.setVisible(true);
        bootSpinner.setManaged(true);
        bootLabel.setText(I18n.t("jcef.preparing"));
        bootRetry.setVisible(false);
        bootRetry.setManaged(false);
    }

    private void showBootError(Throwable err) {
        log.error("JCEF 引导失败，视图保持占位", err);
        if (bootOverlay == null) {
            return;
        }
        Throwable cause = err instanceof java.util.concurrent.CompletionException && err.getCause() != null
                ? err.getCause() : err;
        String msg = cause == null ? "unknown" : String.valueOf(cause.getMessage());
        bootSpinner.setVisible(false);
        bootSpinner.setManaged(false);
        bootLabel.setText(I18n.t("jcef.bootFail", msg));
        bootRetry.setVisible(true);
        bootRetry.setManaged(true);
    }

    private void hideBootOverlay() {
        if (bootOverlay != null) {
            host.getChildren().remove(bootOverlay);
            bootOverlay = null;
        }
    }

    /**
     * CefApp 就绪（FX 线程）：装配 client/消息路由/浏览器/渲染链，补发暂存脚本、下发当前尺寸
     * 并 realize 屏幕外宿主。装配前的各消费点（输入转发/尺寸下发/脚本）均判空容忍。
     */
    private void attachCef(CefApp app) {
        if (disposed || browser != null) {
            return;
        }
        this.client = app.createClient();

        CefMessageRouter.CefMessageRouterConfig cfg = new CefMessageRouter.CefMessageRouterConfig();
        cfg.jsQueryFunction = queryFn;
        cfg.jsCancelFunction = cancelFn;
        this.router = CefMessageRouter.create(cfg, new CefMessageRouterHandlerAdapter() {
            /**
             * JS 桥查询入口（{@code queryFn}）：转到 FX 线程交给 requestHandler，异常回 fail 而非抛给 CEF。
             *
             * <p>入口先验文档可信性：消息路由注入到<b>所有</b>框架（含 iframe 与导航后的远端文档），
             * 因此必须拒绝非主框架、以及主框架文档已不是 {@link #trustedUrl} 的那一次调用——
             * 否则页面里任何一份外来文档都能借桥打进 Java 侧。</p>
             */
            @Override
            public boolean onQuery(CefBrowser b, CefFrame f, long qid,
                                   String request, boolean persistent, CefQueryCallback cb) {
                String doc = f == null ? null : f.getURL();
                if (f == null || !f.isMain() || !isTrustedDocument(doc)) {
                    log.warn("JCEF 桥调用被拒绝（非可信文档）: main={} url={}",
                            f != null && f.isMain(), abbrev(doc));
                    if (cb != null) {
                        cb.failure(-403, "untrusted document");
                    }
                    return true;
                }
                if (requestHandler != null) {
                    Platform.runLater(() -> {
                        try {
                            requestHandler.accept(request);
                            if (cb != null) {
                                cb.success("");
                            }
                        } catch (Throwable t) {
                            log.warn("JCEF onQuery 处理失败", t);
                            if (cb != null) {
                                cb.failure(-1, String.valueOf(t.getMessage()));
                            }
                        }
                    });
                } else if (cb != null) {
                    cb.success("");
                }
                return true;
            }
        });
        this.client.addMessageRouter(router);

        this.client.addLoadHandler(new CefLoadHandlerAdapter() {
            /** 主框架开始装载：记录 native browser 已创建，并补发此前暂存（过早调用被丢弃）的 loadUrl。 */
            @Override
            public void onLoadStart(CefBrowser b, CefFrame f, org.cef.network.CefRequest.TransitionType tt) {
                if (f != null && f.isMain()) {
                    log.info("JCEF 主框架开始装载: {}", abbrev(f.getURL()));
                    // 首个主框架装载事件 = GLCanvas 首次 paint 已触发、native browser 已创建。
                    // 此前 loadUrl 若被过早调用（native 未创建时 JCEF 会静默丢弃 loadURL），此刻补发。
                    String p = null;
                    synchronized (loadGate) {
                        if (!nativeCreated) {
                            nativeCreated = true;
                            p = pendingUrl;
                            pendingUrl = null;
                        }
                    }
                    if (p != null) {
                        log.info("JCEF native browser 就绪，补发生效此前暂存的装载请求: {}", abbrev(p));
                        b.loadURL(p);
                    }
                }
            }

            /** 主框架装载完成：{@code about:*} 空白页不触发就绪回调，真实页面才回调 loadEnd。 */
            @Override
            public void onLoadEnd(CefBrowser b, CefFrame f, int httpStatusCode) {
                if (f != null && f.isMain()) {
                    String url = f.getURL();
                    log.info("JCEF 主框架装载完成: url={} http={}", abbrev(url), httpStatusCode);
                    // 初始 about:blank（或被补发导航中断的它）不是真实页面：不得用它触发就绪/快照回调，
                    // 否则挂起脚本会在空白页上下文重放（ChatAPI 未定义）。
                    if (loadEnd != null && url != null && !url.startsWith("about:")) {
                        Runnable r = loadEnd;
                        Platform.runLater(r);
                    }
                }
            }

            /** 主框架装载失败：初始空白页被补发导航中断属正常（debug），其余记 error 日志。 */
            @Override
            public void onLoadError(CefBrowser b, CefFrame f,
                                    org.cef.handler.CefLoadHandler.ErrorCode ec, String text, String url) {
                if (f != null && f.isMain()) {
                    if (url != null && url.startsWith("about:")) {
                        log.debug("JCEF 初始空白页装载被补发导航中断（正常）: {}", abbrev(url));
                        return;
                    }
                    log.error("JCEF 主框架装载失败: {} {} url={}", ec, text, abbrev(url));
                }
            }
        });

        // 页面 JS 的 console 输出与未捕获错误转进应用日志：诊断「不出气泡/桥未连通」时可直接看到 JWORK_* 标记。
        this.client.addDisplayHandler(new CefDisplayHandlerAdapter() {
            /** 页面 console 输出：仅错误/警告级或含 {@code JWORK_} 标记的消息并入应用日志，其余忽略。 */
            @Override
            public boolean onConsoleMessage(CefBrowser b, CefSettings.LogSeverity level,
                                            String message, String source, int line) {
                boolean notable = level == CefSettings.LogSeverity.LOGSEVERITY_ERROR
                        || level == CefSettings.LogSeverity.LOGSEVERITY_WARNING
                        || level == CefSettings.LogSeverity.LOGSEVERITY_FATAL
                        || (message != null && message.contains("JWORK_"));
                if (notable) {
                    log.info("JS console [{}] {} ({}:{})", level, message, source, line);
                }
                return false;
            }
        });

        // 导航闸门：只放行 Java 侧显式装载的那一个目标（忽略 #片段，页内锚点照常工作），
        // 内容自己发起的跳转（<a> 未被拦截、location.assign、表单提交、meta refresh）一律取消。
        this.client.addRequestHandler(new CefRequestHandlerAdapter() {
            @Override
            public boolean onBeforeBrowse(CefBrowser b, CefFrame f, org.cef.network.CefRequest request,
                                          boolean userGesture, boolean isRedirect) {
                String url = request == null ? null : request.getURL();
                if (isAllowedNavigation(url)) {
                    return false;
                }
                log.warn("JCEF 页面导航被拒绝（非可信目标）: main={} url={}",
                        f != null && f.isMain(), abbrev(url));
                return true;
            }

            /** 中间键/Ctrl+左键等"开新标签"式导航：可信目标照常装载，其余取消。 */
            @Override
            public boolean onOpenURLFromTab(CefBrowser b, CefFrame f, String targetUrl, boolean userGesture) {
                if (isAllowedNavigation(targetUrl)) {
                    return false;
                }
                log.warn("JCEF 页外导航被拒绝: {}", abbrev(targetUrl));
                return true;
            }
        });

        // 本视图是常驻离屏画布，没有"再开一个浏览器"的形态：新窗口/弹窗一律不创建。
        // 正文里的外链由页面 JS 拦下 click 后交给 Java 的系统浏览器打开。
        this.client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public boolean onBeforePopup(CefBrowser b, CefFrame f, String targetUrl, String frameName) {
                log.info("JCEF 弹窗被拒绝: {}", abbrev(targetUrl));
                return true;
            }
        });

        this.browser = client.createBrowser("about:blank", true, false);
        this.browser.setWindowlessFrameRate(30);

        // OSR 出帧回调必须注册到「浏览器的渲染处理器」上：CefClient.setOnPaintListener 在本 build 里是
        // 空实现（no-op），真正的帧监听在 CefBrowserOsr（实现 CefRenderHandler）内，经 browser.getRenderHandler() 取。
        CefRenderHandler renderHandler = browser.getRenderHandler();
        if (renderHandler == null) {
            log.error("browser.getRenderHandler() 为空，无法注册 OSR 帧监听，本视图不会产帧");
        } else {
            renderHandler.setOnPaintListener(evt -> {
                ByteBuffer bf = evt.getRenderedFrame();
                final int w = evt.getWidth(), h = evt.getHeight();
                if (!firstFrameLogged) {
                    firstFrameLogged = true;
                    log.info("OSR onPaint 首次回调: buffer={}, {}x{}", (bf == null ? "null" : "ok"), w, h);
                }
                if (bf == null || w <= 0 || h <= 0) {
                    return;
                }
                final byte[] arr = new byte[w * h * 4];
                bf.rewind();
                bf.get(arr, 0, Math.min(arr.length, bf.remaining()));
                final int stride = w * 4;
                Platform.runLater(() -> {
                    // 双缓冲轮换写入「非 stableFrame」的槽：稳定帧像素在下次更新前保持冻结；
                    // 同尺寸重复使用，避免每帧新建全幅缓冲（4MB/帧 × 30fps）拖累 GC。
                    final WritableImage img;
                    if (stableFrame == frameBufA) {
                        if (frameBufB == null || frameBufB.getWidth() != w || frameBufB.getHeight() != h) {
                            frameBufB = new WritableImage(w, h);
                        }
                        img = frameBufB;
                    } else {
                        if (frameBufA == null || frameBufA.getWidth() != w || frameBufA.getHeight() != h) {
                            frameBufA = new WritableImage(w, h);
                        }
                        img = frameBufA;
                    }
                    img.getPixelWriter().setPixels(0, 0, w, h,
                            PixelFormat.getByteBgraPreInstance(), arr, 0, stride);
                    lastFrame = img;
                    // 帧锐度（相邻像素差峰值 P90）：拉伸放大/未光栅完成的帧高频能量显著偏低，
                    // 敏感期 settle 用其辨别「旧纹理拉伸」的半成品（见 handleFrameInResizeSensitive）。
                    final int sharp = estimateSharpness(arr, w, h, stride);
                    // 方向失衡采样：内容被压缩/错位的中间态（如卡片文字挤成一排竖线）锐度反而极高，
                    // 但其「一侧强结构、另一侧近乎无」的采样点占比远超正常渲染——第三道闸门据此拦截。
                    final int mismatch = estimateAxisMismatch(arr, w, h, stride);
                    // 重排敏感期（或本代际首帧尚未到达）内的帧多为「旧场景+新场景」的拼贴半成品，
                    // 一律不上屏——保持稳定帧过渡绘制，直至验证出稳定（见 handleFrameInResizeSensitive）。
                    final long nowNs = System.nanoTime();
                    if (nowNs < resizeGuardUntilNs || !resizeFirstFrameSeen) {
                        handleFrameInResizeSensitive(img, w, h, sharp, mismatch, nowNs);
                    } else {
                        stableFrame = img; // 非重排期帧 = 完整帧，同步为「拖动重绘可用的可信帧」
                        stableSharpness = sharp;
                        paintFrameNow();
                    }
                });
            });
        }

        // 装配前到达的脚本（主题刷新等）按序补发；页面装载门控的脚本仍由 loadEnd 驱动
        String js;
        while ((js = pendingScripts.poll()) != null) {
            try {
                browser.executeJavaScript(asciiEscape(js), "about:blank", 0);
            } catch (Throwable t) {
                log.warn("JCEF 暂存脚本补发失败", t);
            }
        }
        hideBootOverlay();
        // 占位期间的尺寸下发因 browser 为 null 被跳过：用最新目标尺寸重新触发一次。
        // 在屏视图取宿主布局尺寸；离屏视图（导出等）无宿主布局，取此前显式给定的目标。
        if (host.getWidth() >= 2 && host.getHeight() >= 2) {
            syncSize(host.getWidth(), host.getHeight());
        } else if (pendingSizeW > 0 && pendingSizeH > 0) {
            syncSize(pendingSizeW, pendingSizeH);
        }
        EventQueue.invokeLater(this::realizeOffscreen);
    }

    // ---------- 输入转发（JavaFX → 合成 AWT 事件 → CefBrowserOsr 监听器 → Chromium） ----------

    /** 在画布上安装鼠标/滚轮转发（事件坐标取画布本地坐标，与 OSR 位图 1:1 对齐）。 */
    private void installInputForwarding() {
        canvas.setPickOnBounds(true);
        // 平台对「按住移动」的事件类型上报存在差异：部分设备/驱动（触摸板手势拖动等）在按住
        // 拖动期间只发 MOUSE_MOVED 而非 MOUSE_DRAGGED（实测日志：按住拖动 160px 期间零 DRAGGED）。
        // 若按普通移动合成，Chromium 收不到按钮位，滚动条/选区拖动状态被打断——表现为「滚轮能滚、
        // 按住拖不动」。故此处依据「事件按钮标志 或 本地跟踪的按下未释放按钮」把移动升级为拖动；
        // 无按钮的纯悬停仍走 MOUSE_MOVED（此时 pressedButton 为空，不影响正常悬停）。
        canvas.setOnMouseMoved(e -> {
            final boolean buttonHeld = e.isPrimaryButtonDown() || e.isMiddleButtonDown()
                    || e.isSecondaryButtonDown()
                    || pressedButton != java.awt.event.MouseEvent.NOBUTTON;
            forwardMouse(e, buttonHeld
                    ? java.awt.event.MouseEvent.MOUSE_DRAGGED
                    : java.awt.event.MouseEvent.MOUSE_MOVED);
        });
        canvas.setOnMouseDragged(e -> forwardMouse(e, java.awt.event.MouseEvent.MOUSE_DRAGGED));
        canvas.setOnMousePressed(e -> forwardMouse(e, java.awt.event.MouseEvent.MOUSE_PRESSED));
        canvas.setOnMouseReleased(e -> forwardMouse(e, java.awt.event.MouseEvent.MOUSE_RELEASED));
        canvas.setOnMouseExited(e -> forwardMouse(e, java.awt.event.MouseEvent.MOUSE_EXITED));
        canvas.setOnScroll(this::forwardScroll);
    }

    private void forwardMouse(javafx.scene.input.MouseEvent e, int awtId) {
        final CefBrowser b = browser;
        if (b == null) {
            return; // CEF 未装配（占位期）：输入无处可送
        }
        // 记录真实指针事件时刻：合成拖动兕底轮询据此判断真实事件是否活跃，避免重复合成
        lastRealPointerNs = System.nanoTime();
        // 除 Shift/Ctrl/Alt/Meta 外还必须合成「按钮按下」位：JCEF 的 JNI（N_SendMouseEvent →
        // GetCefModifiers）仅从 getModifiersEx() 提取 BUTTONx_DOWN_MASK 转成 CEF 的
        // EVENTFLAG_LEFT/MIDDLE/RIGHT_MOUSE_BUTTON；MOUSE_DRAGGED 走 SendMouseMoveEvent 时
        // 不再携带 getButton()，Chromium 完全依赖该位维持滚动条/选区拖动——缺了它，
        // 滚轮能滚但拖动滚动条、拖选文本全部失效。JavaFX 的 isXxxButtonDown 是事件发生时
        // 各键的物理按下状态（press 起 true、release 后 false），与真实 AWT 修饰符语义一致。
        final int button = awtButton(e.getButton());
        if (awtId == java.awt.event.MouseEvent.MOUSE_PRESSED) {
            pressedButton = button;
            startDragFallbackPoll(e);
        }
        int buttonBits = buttonDownMask(e);
        if (buttonBits == 0 && awtId == java.awt.event.MouseEvent.MOUSE_DRAGGED) {
            // 兜底：个别平台/版本未在拖动事件回填按钮状态时，用最后一次按下的按钮补齐
            buttonBits = buttonDownMaskOf(pressedButton);
        }
        final int mods = awtModifiers(e.isShiftDown(), e.isControlDown(), e.isAltDown(), e.isMetaDown())
                | buttonBits;
        // Windows 习惯：右键释放才是弹出触发（与真实 AWT 事件一致）
        final boolean popup = button == java.awt.event.MouseEvent.BUTTON3
                && awtId == java.awt.event.MouseEvent.MOUSE_RELEASED;
        final java.awt.event.MouseEvent ev = new java.awt.event.MouseEvent(
                b.getUIComponent(), awtId, System.currentTimeMillis(), mods,
                (int) e.getX(), (int) e.getY(), Math.max(1, e.getClickCount()), popup, button);
        dispatchToBrowser(ev);
        if (awtId == java.awt.event.MouseEvent.MOUSE_RELEASED) {
            pressedButton = java.awt.event.MouseEvent.NOBUTTON;
            stopDragFallbackPoll();
        }
    }

    // ---------- 合成拖动兜底实现 ----------

    /**
     * 真实 PRESS 到达画布后启动兜底轮询：以「全局光标位置 + 全局左键状态」为信号源，
     * 在「真实移动事件持续缺席」（远程桌面输入路径在某些组合下整段吞掉按住移动，实测日志：
     * 按住 518ms 位移 115px 期间 scene/canvas 零事件）时，合成 MOUSE_DRAGGED/MOUSE_RELEASED
     * 送达 Chromium，恢复滚动条拖动。普通环境下真实事件照常到达——轮询因「真实事件新鲜」
     * 而从不合成，只做无害监听。
     */
    private void startDragFallbackPoll(javafx.scene.input.MouseEvent e) {
        // 双源校准：JavaFX 屏幕坐标（事件自带）与 AWT 屏幕坐标（MouseInfo）的差值，
        // 用于把轮询到的 AWT 坐标换算回 JavaFX 屏幕坐标系（同一会话内偏移稳定）。
        try {
            java.awt.PointerInfo pi = java.awt.MouseInfo.getPointerInfo();
            if (pi == null) {
                return;
            }
            java.awt.Point awt = pi.getLocation();
            screenCalX = awt.getX() - e.getScreenX();
            screenCalY = awt.getY() - e.getScreenY();
        } catch (Throwable t) {
            log.warn("合成拖动兜底：屏幕坐标校准失败，本设备不启用兜底: {}", t.toString());
            screenCalX = Double.NaN;
            return;
        }
        pollLastX = Double.NaN;
        pollLastY = Double.NaN;
        pollStartNs = System.nanoTime();
        if (dragPoll == null) {
            dragPoll = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(javafx.util.Duration.millis(25), ev -> dragFallbackTick()));
            dragPoll.setCycleCount(javafx.animation.Animation.INDEFINITE);
        }
        dragPoll.playFromStart();
        log.debug("合成拖动兜底: 已启动（普通环境因真实事件新鲜自动跳过合成，无感）");
    }

    /** 停止兜底轮询（真实 RELEASE、全局左键已松开或超时）。 */
    private void stopDragFallbackPoll() {
        if (dragPoll != null) {
            dragPoll.stop();
        }
    }

    /** 兜底轮询单次执行（FX 线程）：读全局光标/左键，按需合成拖动事件。 */
    private void dragFallbackTick() {
        if (pressedButton == java.awt.event.MouseEvent.NOBUTTON) {
            stopDragFallbackPoll();
            return;
        }
        // 总时长上限 120s：极端环境下若「真实松开检测」失效，防止永久轮询
        if (System.nanoTime() - pollStartNs > 120_000_000_000L) {
            log.info("合成拖动兜底: 超过时长上限，自动停止");
            stopDragFallbackPoll();
            return;
        }
        java.awt.Point awt;
        try {
            java.awt.PointerInfo pi = java.awt.MouseInfo.getPointerInfo();
            if (pi == null) {
                return;
            }
            awt = pi.getLocation();
        } catch (Throwable t) {
            return; // 读取失败：下一帧再试
        }
        final boolean leftDown = globalLeftButtonDown();
        final double jx = awt.getX() - screenCalX;
        final double jy = awt.getY() - screenCalY;
        final boolean moved = Double.isNaN(pollLastX)
                || Math.abs(jx - pollLastX) > 0.5 || Math.abs(jy - pollLastY) > 0.5;
        if (moved) {
            // 真实移动事件在 60ms 内出现过 → 正常输入链路健在，轮询不合成（避免事件重复）
            final boolean realFlow = System.nanoTime() - lastRealPointerNs < 60_000_000L;
            if (!realFlow) {
                final javafx.geometry.Point2D local = canvas.screenToLocal(jx, jy);
                if (local != null) {
                    log.debug("合成拖动兜底: 合成 DRAG x={} y={}（全局光标 {},{}）",
                            (int) local.getX(), (int) local.getY(), awt.x, awt.y);
                    synthDragToBrowser((int) local.getX(), (int) local.getY());
                }
            }
            pollLastX = jx;
            pollLastY = jy;
        }
        if (!leftDown) {
            // 真实松开：远程桌面下 RELEASE 可能未送达，这里补发一次再收尾
            if (!Double.isNaN(pollLastX)) {
                final javafx.geometry.Point2D local = canvas.screenToLocal(pollLastX, pollLastY);
                if (local != null) {
                    log.debug("合成拖动兜底: 全局左键已松开，补发 RELEASE x={} y={}",
                            (int) local.getX(), (int) local.getY());
                    synthReleaseToBrowser((int) local.getX(), (int) local.getY());
                }
            }
            pressedButton = java.awt.event.MouseEvent.NOBUTTON;
            stopDragFallbackPoll();
        }
    }

    /** 合成 MOUSE_DRAGGED 事件（按钮位=当前按下按钮）送达浏览器组件。 */
    private void synthDragToBrowser(int x, int y) {
        final CefBrowser b = browser;
        if (b == null) {
            return;
        }
        final int mods = buttonDownMaskOf(pressedButton);
        final java.awt.event.MouseEvent ev = new java.awt.event.MouseEvent(
                b.getUIComponent(), java.awt.event.MouseEvent.MOUSE_DRAGGED,
                System.currentTimeMillis(), mods, x, y, 1, false, java.awt.event.MouseEvent.NOBUTTON);
        dispatchToBrowser(ev);
    }

    /** 合成 MOUSE_RELEASED 事件送达浏览器组件。 */
    private void synthReleaseToBrowser(int x, int y) {
        final CefBrowser b = browser;
        if (b == null) {
            return;
        }
        final java.awt.event.MouseEvent ev = new java.awt.event.MouseEvent(
                b.getUIComponent(), java.awt.event.MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), 0, x, y, 1, false, pressedButton);
        dispatchToBrowser(ev);
    }

    /** 全局左键物理状态（JNA GetAsyncKeyState）；读取不可用时假定按住（保守不误断）。 */
    private static boolean globalLeftButtonDown() {
        try {
            if (jnaOk == null) {
                try {
                    User32.INSTANCE.GetAsyncKeyState(0x01);
                    jnaOk = Boolean.TRUE;
                } catch (Throwable t) {
                    jnaOk = Boolean.FALSE;
                    log.warn("GetAsyncKeyState 不可用（JNA 加载失败），兜底轮询不做松开合成: {}", t.toString());
                }
            }
            if (jnaOk) {
                return (User32.INSTANCE.GetAsyncKeyState(0x01) & 0x8000) != 0;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    /** 当下按下中的按钮集合 → AWT DOWN_MASK 位（JNI 不读这个就认不出拖动）。 */
    private static int buttonDownMask(javafx.scene.input.MouseEvent e) {
        int m = 0;
        if (e.isPrimaryButtonDown()) {
            m |= InputEvent.BUTTON1_DOWN_MASK;
        }
        if (e.isMiddleButtonDown()) {
            m |= InputEvent.BUTTON2_DOWN_MASK;
        }
        if (e.isSecondaryButtonDown()) {
            m |= InputEvent.BUTTON3_DOWN_MASK;
        }
        return m;
    }

    /** AWT 按钮码 → 对应 DOWN_MASK 位。 */
    private static int buttonDownMaskOf(int button) {
        if (button == java.awt.event.MouseEvent.BUTTON1) {
            return InputEvent.BUTTON1_DOWN_MASK;
        }
        if (button == java.awt.event.MouseEvent.BUTTON2) {
            return InputEvent.BUTTON2_DOWN_MASK;
        }
        if (button == java.awt.event.MouseEvent.BUTTON3) {
            return InputEvent.BUTTON3_DOWN_MASK;
        }
        return 0;
    }

    private void forwardScroll(javafx.scene.input.ScrollEvent e) {
        final CefBrowser b = browser;
        if (b == null) {
            return;
        }
        // JavaFX +Y = 向上；AWT 向上为负 rotation，故取反。
        final double notches = -e.getDeltaY() / WHEEL_NOTCH;
        final int rotation;
        if (Math.abs(notches) >= 1.0) {
            rotation = (int) Math.round(notches);
        } else if (notches != 0) {
            rotation = notches > 0 ? 1 : -1; // 触控板平滑小值：每事件按一格
        } else {
            return; // 纯水平滚动事件（AWT 无对应语义）
        }
        final int mods = awtModifiers(e.isShiftDown(), e.isControlDown(), e.isAltDown(), e.isMetaDown());
        final java.awt.event.MouseWheelEvent ev = new java.awt.event.MouseWheelEvent(
                b.getUIComponent(), java.awt.event.MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), mods, (int) e.getX(), (int) e.getY(), 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, WHEEL_DELTA_UNITS, rotation);
        dispatchToBrowser(ev);
    }

    // ---------- 键盘转发（JavaFX → 合成 AWT KeyEvent → CefBrowserOsr 的 KeyListener → Chromium） ----------

    /**
     * 键盘事件转发：供可输入视图（如 {@code JcefComposer}）的平台键盘事件直转 Chromium。
     *
     * <p>路径与鼠标转发一致：合成 AWT {@link java.awt.event.KeyEvent} 派发给浏览器组件，
     * {@code CefBrowserOsr} 已在其画布上注册 KeyListener（keyTyped/keyPressed/keyReleased →
     * {@code sendKeyEvent}），JNI 会把 AWT 事件转为 CEF KeyEvent 送达 Chromium。</p>
     *
     * <p>调用方在 FX 线程调用；合成事件经 AWT EDT 派发（与鼠标转发同一队列），
     * 天然保证「同一批键盘动作」的先后顺序。</p>
     *
     * @param e         JavaFX 键盘事件（提供修饰键状态）
     * @param awtEventId {@link java.awt.event.KeyEvent#KEY_PRESSED}/{@code KEY_TYPED}/{@code KEY_RELEASED}
     * @param awtKeyCode AWT 键码（Windows 平台码＝VK 码，JavaFX {@code KeyCode.getCode()} 可直接使用）
     * @param keyChar    按键字符（KEY_TYPED 用；其余传 0）
     */
    public void forwardKeyEvent(javafx.scene.input.KeyEvent e, int awtEventId, int awtKeyCode, char keyChar) {
        final CefBrowser b = browser;
        if (b == null) {
            return;
        }
        final int mods = awtModifiers(e.isShiftDown(), e.isControlDown(), e.isAltDown(), e.isMetaDown());
        final java.awt.event.KeyEvent ev = new java.awt.event.KeyEvent(
                b.getUIComponent(), awtEventId, System.currentTimeMillis(), mods, awtKeyCode, keyChar);
        dispatchToBrowser(ev);
    }

    private static int awtModifiers(boolean shift, boolean ctrl, boolean alt, boolean meta) {
        int m = 0;
        if (shift) {
            m |= InputEvent.SHIFT_DOWN_MASK;
        }
        if (ctrl) {
            m |= InputEvent.CTRL_DOWN_MASK;
        }
        if (alt) {
            m |= InputEvent.ALT_DOWN_MASK;
        }
        if (meta) {
            m |= InputEvent.META_DOWN_MASK;
        }
        return m;
    }

    private static int awtButton(javafx.scene.input.MouseButton b) {
        if (b == javafx.scene.input.MouseButton.SECONDARY) {
            return java.awt.event.MouseEvent.BUTTON3;
        }
        if (b == javafx.scene.input.MouseButton.MIDDLE) {
            return java.awt.event.MouseEvent.BUTTON2;
        }
        if (b == javafx.scene.input.MouseButton.NONE) {
            return java.awt.event.MouseEvent.NOBUTTON;
        }
        return java.awt.event.MouseEvent.BUTTON1;
    }

    /** 合成事件在 AWT EDT 上派发给浏览器组件（其监听器继续送往 Chromium）。 */
    private void dispatchToBrowser(java.awt.event.InputEvent ev) {
        EventQueue.invokeLater(() -> {
            final CefBrowser b = browser;
            if (b == null) {
                return;
            }
            try {
                b.getUIComponent().dispatchEvent(ev);
            } catch (Throwable t) {
                log.debug("输入事件转发失败: {}", t.toString());
            }
        });
    }

    /** 屏幕外可见 Frame 承载 OSR 画布：让 AWT 判为 showing，触发 CefBrowserOsr 尺寸同步与 OnPaint。 */
    private void realizeOffscreen() {
        try {
            // 本方法跑在 AWT EDT 上：给当前线程挂一个专属未捕获异常处理器，捕获 realize 之后在 EDT
            // 后续事件（GL 上下文创建/绘制）里抛出的异常并写入日志（带完整堆栈）。
            Thread.currentThread().setUncaughtExceptionHandler(
                    (th, e) -> log.error("AWT EDT 后续事件未捕获异常（OSR 渲染链可能已中断）", e));
            Component ui = browser.getUIComponent();
            ui.setSize(new Dimension(1, 1));
            Frame f = new Frame("jcef-osr-realizer");
            // UTILITY 工具窗口：不占任务栏/Alt-Tab（NORMAL 类型即使位于屏幕外也会出现条目）；
            // 它仅为让 GLCanvas 进入 showing 态而存在，不影响 GL 上下文与出帧。
            f.setType(java.awt.Window.Type.UTILITY);
            f.setUndecorated(true);
            // 纯承载窗口不参与焦点竞争（关键）：AWT 窗口首次 setVisible 会抢前台键盘焦点，
            // 一旦键盘流向屏幕外的 Chromium，JavaFX 侧收不到按键——Enter 提交、IME、字符注入
            // 整体失效（表现为打字正常但 Enter 不发送）；禁自动抢焦 + 禁可聚焦状态双保险。
            f.setAutoRequestFocus(false);
            f.setFocusableWindowState(false);
            f.setLayout(new BorderLayout());
            f.add(ui, BorderLayout.CENTER);
            f.setSize(1, 1);
            f.setLocation(-32000, -32000);
            f.setVisible(true);
            realize = f;
            browser.setWindowVisibility(true);
            browser.setFocus(true);
            log.info("JCEF OSR realize 成功，等待 onPaint 首帧");
        } catch (Throwable t) {
            log.error("JCEF OSR realize 失败，本视图不会产帧（JDK16+ 跑 OSR 必须带 JOGL 模块开放参数："
                    + "--add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED "
                    + "--add-exports java.desktop/sun.java2d=ALL-UNNAMED，否则 GLCanvas 无法确定 GraphicsConfiguration）", t);
        }
    }

    /** 注册主框架装载完成回调（在 FX 线程触发），用于驱动就绪后重放挂起脚本；重复调用以最后一次为准。 */
    public void setOnLoadEnd(Runnable runnable) {
        this.loadEnd = runnable;
    }

    /** 设置页面「贴底跟随」状态提供者（如聊天页的 atBottom 上报）：过渡帧垂直锚定在贴底时机
     *  选择帧底贴画布底——配合页面侧 resize 补滚保持贴底，视口高度变化收尾零跳变。 */
    public void setBottomPinnedSupplier(java.util.function.BooleanSupplier supplier) {
        this.bottomPinnedSupplier = supplier;
    }

    /** 宿主节点：控制器把它放进容器并撑满（对齐旧 ChatWebView.node() 语义）。 */
    public StackPane node() {
        return host;
    }

    /** 最近一次 {@code onPaint} 的位图；尚未出帧时为 {@code null}。 */
    public WritableImage lastImage() {
        return lastFrame;
    }

    /** 浏览器实例；CEF 尚未装配（引导占位期）时为 {@code null}，调用方须判空。 */
    public CefBrowser browser() {
        return browser;
    }

    /**
     * Java→JS：在页面上下文执行脚本（对齐旧 engine.executeScript）。
     *
     * <p>脚本先经 {@link #asciiEscape(String)} 转为纯 ASCII 再下发：CEF 的 JNI 桥
     * （jni_util.cpp {@code GetJNIString}）用 {@code GetStringUTFChars} 读取 jstring
     * 得到 Modified UTF-8（CESU-8）——仅 BMP 字符与标准 UTF-8 一致；增补平面字符
     * （emoji，UTF-16 代理对）会被拆成两个三字节序列，CEF 侧按标准 UTF-8 解码失败，
     * 渲染成 notdef 乱码。转义后 JS 源码纯 ASCII，两种编码下字节完全一致，绕开该缺陷。</p>
     */
    public void executeScript(String js) {
        final CefBrowser b = browser;
        if (b == null) {
            // CEF 未装配：暂存待 attachCef 后按序补发（调用方可能在 EDT，队列须线程安全）
            pendingScripts.add(js);
            return;
        }
        try {
            b.executeJavaScript(asciiEscape(js), "about:blank", 0);
        } catch (Throwable t) {
            log.warn("JCEF executeScript 失败", t);
        }
    }

    /**
     * 将字符串中所有非 ASCII 字符转义为 <code>&#92;uXXXX</code> 序列。
     *
     * <p>背景：JCEF 的 JNI（{@code GetJNIString} → {@code GetStringUTFChars}）产生
     * Modified UTF-8/CESU-8，而 CEF 按标准 UTF-8 解码；ASCII 字符在两种编码下字节一致，
     * 故让 JS 源码保持纯 ASCII 穿越桥路，由 JS 引擎在解析时还原转义序列
     * （代理对写成两个 <code>&#92;uXXXX</code>，JS 字符串同为 UTF-16 码元序列，语义完全等价）。</p>
     */
    public static String asciiEscape(String js) {
        int n = js.length();
        StringBuilder sb = null;
        for (int i = 0; i < n; i++) {
            char c = js.charAt(i);
            if (c <= 0x7F) {
                if (sb != null) {
                    sb.append(c);
                }
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(n + 32);
                sb.append(js, 0, i);
            }
            sb.append('\\').append('u')
                    .append(Character.forDigit((c >> 12) & 0xF, 16))
                    .append(Character.forDigit((c >> 8) & 0xF, 16))
                    .append(Character.forDigit((c >> 4) & 0xF, 16))
                    .append(Character.forDigit(c & 0xF, 16));
        }
        return sb == null ? js : sb.toString();
    }

    /** 以 data: URL 装载内联 HTML（JCEF 不认 jar: URL；纯 ASCII 源，UTF-8 编码）。 */
    public void loadData(String html) {
        String url = "data:text/html;charset=utf-8,"
                + URLEncoder.encode(html, StandardCharsets.UTF_8).replace("+", "%20");
        loadUrl(url);
    }

    /**
     * 装载任意 URL（http/file/data）。
     *
     * <p>native browser 直到 GLCanvas 首次 paint（{@code realizeOffscreen} 之后）才真正创建，
     * 在那之前 JCEF 的 {@code browser.loadURL} 会被静默丢弃（浏览器停留在初始 {@code about:blank}）。
     * 因此此处把过早到达的请求暂存，等首个主框架装载事件（native 已创建的信号）到达时补发。</p>
     */
    public void loadUrl(String url) {
        // 先立可信目标再导航：onBeforeBrowse 在导航派发的瞬间就会回读它，晚一步会被自己的闸门拦下
        trustedUrl = url;
        synchronized (loadGate) {
            if (!nativeCreated) {
                pendingUrl = url;
                log.info("JCEF native browser 尚未创建，暂存装载请求待补发: {}", abbrev(url));
                return;
            }
        }
        browser.loadURL(url);
    }

    /** 该导航目标是否就是 Java 侧装载的那一个文档（{@code about:*} 为初始空白页，始终放行）。 */
    private boolean isAllowedNavigation(String url) {
        if (url == null) {
            return false;
        }
        if (url.startsWith("about:")) {
            return true; // 每个视图创建时的空骨架页，以及被装载请求中断掉的那次导航
        }
        String t = trustedUrl;
        return t != null && sameDocument(t, url);
    }

    /** 桥调用来源是否可信：主框架当前文档须即 {@link #trustedUrl}。 */
    private boolean isTrustedDocument(String url) {
        if (url == null || url.startsWith("about:")) {
            return true; // 空白骨架页里尚无桥，走到这里也只可能是 CEF 自身的中断导航
        }
        String t = trustedUrl;
        if (t == null) {
            return false;
        }
        if (sameDocument(t, url)) {
            return true;
        }
        // data: 文档的 URL 由 Chromium 规范化回报（内联整页 HTML，可达数兆且未必逐字节回显），
        // 无法可靠等值比对；跨目标导航已被 onBeforeBrowse 拦住，这里退化为同方案判定。
        return "data".equalsIgnoreCase(schemeOf(t)) && "data".equalsIgnoreCase(schemeOf(url));
    }

    /** 去掉 {@code #片段}（锚点跳转不应被当成异源导航）。 */
    private static String trimFragment(String url) {
        if (url == null) {
            return "";
        }
        int hash = url.indexOf('#');
        return hash < 0 ? url : url.substring(0, hash);
    }

    /** 还原百分号编码；非法序列（裸 {@code %}）按原样返回。 */
    private static String decode(String url) {
        try {
            return java.net.URLDecoder.decode(url, java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return url;
        }
    }

    /** 用于日志/比对的文档地址：去片段并还原编码。 */
    static String documentBase(String url) {
        return decode(trimFragment(url));
    }

    /**
     * 两个地址是否指向同一文档（忽略 {@code #片段} 与百分号编码差异，大小写不敏感）。
     *
     * <p>先走原串快路径：{@code data:} 内联页的整页 HTML 可达数兆，逐次解码纯属浪费。</p>
     */
    static boolean sameDocument(String a, String b) {
        String sa = trimFragment(a);
        String sb = trimFragment(b);
        return sa.equalsIgnoreCase(sb) || decode(sa).equalsIgnoreCase(decode(sb));
    }

    /**
     * URL 方案（小写、不含冒号）；无方案返回空串。
     *
     * <p>单字母前缀一律判为「无方案」：真实 URI 方案不存在单字母的，而 {@code C:/x.html} 这种
     * 裸盘符路径在本项目里确实会作为 URL 片段出现，不能把它误读成方案 {@code c:}。</p>
     */
    static String schemeOf(String url) {
        if (url == null || url.length() < 2) {
            return "";
        }
        char first = url.charAt(0);
        if (!((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z'))) {
            return "";
        }
        for (int i = 1; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == ':') {
                return i < 2 ? "" : url.substring(0, i).toLowerCase();
            }
            boolean schemeChar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.';
            if (!schemeChar) {
                return "";
            }
        }
        return "";
    }

    /** 日志用 URL 截断（data: URL 可达数兆，直接打印会淹没日志）。 */
    private static String abbrev(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 96 ? s : s.substring(0, 96) + "…(len=" + s.length() + ")";
    }

    /**
     * 尺寸同步：驱动 OSR native resize，使 CEF 按新视口重排重绘。
     *
     * <p>「视觉跟手」与「CEF 重排」解耦：画布立即对齐新尺寸并把现有帧按锚定规则原尺寸重绘（拖动
     * 即刻跟手且内容零变形，不等 CEF 大图重排）；真实重排只在尺寸停止变化 {@link #SIZE_FLUSH_MS}ms
     * 后（trailing）一次性下发——拖动期间不让 CEF 对中间尺寸重排（其分块中间态帧会造成内容短暂
     * 错位/漂移）。</p>
     */
    public void syncSize(double w, double h) {
        if (w < 2 || h < 2) {
            return; // 极小/零尺寸（布局未定、最小化等瞬态）不参与同步，避免 1×1 视口污染
        }
        final int iw = (int) Math.max(1, Math.floor(w));
        final int ih = (int) Math.max(1, Math.floor(h));
        if (!syncLogged) {
            syncLogged = true;
            log.info("OSR syncSize 首次: 入参 {}x{} → 下发 {}x{}", w, h, iw, ih);
        }
        pendingSizeW = iw;
        pendingSizeH = ih;
        // 画布操作与定时器必须在 FX 线程；调用方可能来自任意线程（导出流程等）
        if (Platform.isFxApplicationThread()) {
            onSyncSizeFx();
        } else {
            Platform.runLater(this::onSyncSizeFx);
        }
    }

    /** syncSize 的 FX 侧主体：画布跟手 + 真实尺寸节流下发 + trailing 兜底定时器。 */
    private void onSyncSizeFx() {
        final int iw = pendingSizeW;
        final int ih = pendingSizeH;
        if (iw <= 0 || ih <= 0) {
            return;
        }
        // 1) 视觉跟手：画布立即对齐槽位尺寸，现有帧原尺寸重绘（水平居中 + 垂直贴滚动语义，不等 CEF 重排）
        applyCanvasLayout(iw, ih);
        // 2) 真实重排仅 trailing：停止变化 SIZE_FLUSH_MS 后一次性下发最终尺寸。拖动期间绝不下发——
        //    CEF 对中间尺寸的分块重排会产出「半新半旧」的布局中间态帧，实测快速缩小时
        //    内容错位/漂移；拖动期间画面完全由现有稳定帧 + 留白伸缩表现（内容静止）。
        lastSizeEventNs = System.nanoTime();
        if (sizeFlushDelay == null) {
            sizeFlushDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(SIZE_FLUSH_MS));
            sizeFlushDelay.setOnFinished(e -> flushSizeToCef());
        }
        sizeFlushDelay.playFromStart();
    }

    /** 把最新目标尺寸真实下发 CEF（AWT 侧执行时读取最新 pending；与上次相同则跳过重排）。 */
    private void flushSizeToCef() {
        if (sizeTaskQueued.compareAndSet(false, true)) {
            EventQueue.invokeLater(() -> {
                sizeTaskQueued.set(false);
                final CefBrowser b = browser;
                final int tw = pendingSizeW;
                final int th = pendingSizeH;
                // browser 为 null（未装配）时跳过且不记 lastSetSize——装配后 syncSize 会同尺寸重新下发
                if (b == null || tw <= 0 || th <= 0 || (tw == lastSetSizeW && th == lastSetSizeH)) {
                    return;
                }
                lastSetSizeW = tw;
                lastSetSizeH = th;
                log.debug("OSR resize 下发: {}x{}（尺寸变化静默 {}ms）", tw, th,
                        lastSizeEventNs == 0 ? -1 : (System.nanoTime() - lastSizeEventNs) / 1_000_000L);
                // 进入「重排敏感期」：期内 CEF 输出的是「旧+新」分块拼贴半成品，由 onPaint 攒住
                // 不上屏（保持稳定帧过渡绘制），验证稳定后收尾上屏。每轮 setSize 重置敏感期与
                // 首帧标记（首帧无论多晚到达都必须走敏感路径，防其在保护窗外被当完整帧上屏）。
                resizeFirstFrameSeen = false;
                settleRejectLogged = false;
                resizeGuardStartNs = System.nanoTime();
                resizeGuardUntilNs = resizeGuardStartNs + RESIZE_GUARD_MS * 1_000_000L;
                try {
                    b.getUIComponent().setSize(new Dimension(tw, th));
                } catch (Throwable ignored) {}
            });
        }
    }

    /**
     * 画布即时跟手：尺寸对齐槽位，并把现有帧按「原始像素尺寸」重绘（水平居中 + 垂直贴滚动语义）。
     * <p>不缩放不拉伸（内容零变形）；锚点与 CEF 重排后的最终位置一致（居中列 / scrollTop 保持 / 贴底），
     * 过渡零跳变。帧小于画布时空缺以帧边缘色（页面背景）填充，避免露出异构底色。</p>
     */
    private void applyCanvasLayout(final int w, final int h) {
        final Runnable r = () -> {
            if (canvas.getWidth() == w && canvas.getHeight() == h) {
                return;
            }
            canvas.setWidth(w);
            canvas.setHeight(h);
            drawFrameCentered(w, h);
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /**
     * 把稳定帧以原始像素尺寸绘制到画布：绝不缩放拉伸（内容零变形）；目标坐标取整，
     * 避免半像素偏移触发双线性重采样（整幅发虚）。水平居中——与 CEF 重排后的「居中列」
     * 布局几何一致（列在视口居中），拖动全程内容渐进滑向最终位置、收尾零跳变；垂直贴合
     * 页面滚动语义——默认顶端对齐（重排保留 scrollTop，视口顶端内容不动，底部裁剪/延展），
     * 页面贴底跟随时底部对齐（帧底贴画布底，顶部裁剪）——两者均与重排后的最终位置一致，
     * 收尾零跳变。帧超出画布的部分自然裁掉（minWidth 已保证居中列不超画布，水平裁到的
     * 只是页边留白）。帧四周留白以「对应边缘 1px 列/行」延伸填充；另做滚动条搬运：
     * 帧右缘 {@link #EDGE_BAR}px 带（滚动条所在）复制到画布右缘——帧右缘仍在画布内时原位
     * 以边距背景列覆盖。全程不做 clear：各带/角/主帧几何全域覆盖且帧不透明。
     */
    private void drawFrameCentered(final double cw, final double ch) {
        // 只用「稳定帧」：重排期 lastFrame 可能是分块半成品，画上屏即拼贴（首次帧尚无稳定帧时回退）
        final WritableImage img = stableFrame != null ? stableFrame : lastFrame;
        if (img == null || cw <= 0 || ch <= 0) {
            return;
        }
        final GraphicsContext gc = canvas.getGraphicsContext2D();
        final double iw = img.getWidth();
        final double ih = img.getHeight();
        // 水平居中（帧心对齐画布心）：与重排后的「居中列」布局几何一致，拖动全程内容是
        // 「向中心汇拢/向外扩展」的连续渐变，收尾上屏零跳变（此前缩小走锚左——内容左端
        // 静止而收尾瞬间跳回居中位；那是 minWidth 保底前的防裁切方案，现已不需要）。取整保证
        // 像素对齐。
        final double left = Math.round((cw - iw) / 2.0);
        // 垂直贴合滚动语义（不同于水平居中）：默认顶对齐——重排保留 scrollTop，视口顶端内容
        // 不动；贴底跟随时底对齐——帧底贴画布底（配合页面侧 resize 补滚，两处锚定一致）。取整保证像素对齐。
        final double top = bottomPinned() ? Math.round(ch - ih) : 0;
        final double right = left + iw;
        final double bottom = top + ih;
        // 滚动条左侧的边距列（背景色源）：避开滚动条带本身
        final double edgeX = iw - Math.min(EDGE_BAR, iw / 4.0) - 1;
        if (left > 0) {
            gc.drawImage(img, 0, 0, 1, ih, 0, top, left, ih);              // 左带：左缘 1px 列延伸
        }
        if (right < cw) {
            gc.drawImage(img, edgeX, 0, 1, ih, right, top, cw - right, ih); // 右带：边距列延伸（背景色）
        }
        if (top > 0) {
            gc.drawImage(img, 0, 0, iw, 1, left, 0, iw, top);              // 上带：顶缘 1px 行延伸
        }
        if (bottom < ch) {
            gc.drawImage(img, 0, ih - 1, iw, 1, left, bottom, iw, ch - bottom); // 下带：底缘 1px 行延伸
        }
        if (left > 0 && top > 0) {
            gc.drawImage(img, 0, 0, 1, 1, 0, 0, left, top);                // 左上角
        }
        if (right < cw && top > 0) {
            gc.drawImage(img, edgeX, 0, 1, 1, right, 0, cw - right, top);  // 右上角
        }
        if (left > 0 && bottom < ch) {
            gc.drawImage(img, 0, ih - 1, 1, 1, 0, bottom, left, ch - bottom); // 左下角
        }
        if (right < cw && bottom < ch) {
            gc.drawImage(img, edgeX, ih - 1, 1, 1, right, bottom, cw - right, ch - bottom); // 右下角
        }
        gc.drawImage(img, left, top); // 主帧：原始像素 1:1，零变形零模糊
        // 滚动条搬运：帧右缘带 → 画布右缘（帧右缘在画布内时先涂掉原位，防双滚动条；
        // 缩小时帧右缘在画布外，原位不可见、只搬运即可——滚动条始终视觉贴画布右缘）
        final double bar = Math.min(EDGE_BAR, iw / 4.0);
        if (bar >= 2) {
            if (right <= cw) {
                gc.drawImage(img, edgeX, 0, 1, ih, iw - bar + left, top, bar, ih); // 覆盖原滚动条位置
            }
            gc.drawImage(img, iw - bar, 0, bar, ih, cw - bar, top, bar, ih);   // 搬至画布右缘
        }
    }

    /** 页面当前是否贴底跟随（提供者未设置或取值异常时按否处理）。 */
    private boolean bottomPinned() {
        final java.util.function.BooleanSupplier s = bottomPinnedSupplier;
        if (s == null) {
            return false;
        }
        try {
            return s.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 用稳定帧按当前画布尺寸上屏：帧与画布一致 → 1:1 绘制（稳态/最终清晰帧）；不一致 = resize
     * 过渡中（画布已先行跟手放大/缩小），把本帧原尺寸居中绘制（绝不缩放变形，与重排后的居中
     * 布局对齐），后续匹配帧自然恢复 1:1。仅 FX 线程。
     */
    private void paintFrameNow() {
        final WritableImage img = stableFrame != null ? stableFrame : lastFrame;
        if (img == null) {
            return;
        }
        final GraphicsContext gc = canvas.getGraphicsContext2D();
        final double cw = canvas.getWidth();
        final double ch = canvas.getHeight();
        final int w = (int) img.getWidth();
        final int h = (int) img.getHeight();
        if ((int) cw == w && (int) ch == h) {
            gc.drawImage(img, 0, 0); // 帧不透明且全幅覆盖，无需 clear（省每帧全幅清除）
        } else if (cw > 0 && ch > 0) {
            // resize 过渡（画布已先行跟手）：原尺寸居中，零变形
            drawFrameCentered(cw, ch);
        }
    }

    /**
     * 重排敏感期内的帧处理：不直接上屏，先与上一帧做采样签名比对——「连续两帧一致」即画面已稳定
     * （重排/光栅化完成的强信号；CEF 无原生就绪事件，官方 issue #4166 的可靠工作法也是「多帧比对
     * 选稳定帧」），此时收尾上屏；不一致则仅续期「帧流平静」（{@link #RESIZE_QUIET_MS}）。平静后
     * 帧流可能只是撞上光栅化批次间隙（间隙前最后一帧仍是半成品），故不立即上屏，而是主动 invalidate
     * 请求一帧来验证（{@link #onResizeHoldElapsed()} 循环，最多 {@link #RESIZE_VERIFY_MAX} 轮）。
     * 兜底：帧流持续不断（页面动画/流式叠加拖动）超过 {@link #RESIZE_HARD_MAX_MS} 时放弃攒帧保护
     * 回到即时上屏，防画面永久冻结。
     *
     * <p>「连续两帧一致」还须过三道闸门才允许 settle：① 帧尺寸 = 最近下发的目标尺寸（防在途旧尺寸帧）；
     * ② 帧锐度达标——CEF 重排过渡期会输出「旧纹理拉伸放大」的合成帧，模糊使 16×16 亮度签名比对
     * 失敏（两帧同模糊极易判一致），但锐度（相邻像素差峰值）显著低于原生渲染，据此拒绝其收敛；
     * ③ 方向结构均衡——另一类中间态（内容被横向/纵向压缩错位，如卡片文字挤成一排竖线）锐度反而
     * 极高，但「水平强结构而纵向近乎无」的失衡采样点占比远超正常渲染（正常文本/卡片两个方向都有
     * 边缘），据此拦截。旧帧/白块过渡保持到真实清晰帧到达（届时签名依旧一致、三道闸门通过）后
     * 一次性直接切清晰。</p>
     */
    private void handleFrameInResizeSensitive(WritableImage img, int w, int h, int sharp, int mismatch, long now) {
        resizeFirstFrameSeen = true;
        if (now - resizeGuardStartNs > RESIZE_HARD_MAX_MS * 1_000_000L) {
            // 帧流持续超时（动画/流式叠加拖动）：无法等到平静，放弃保护回到即时，防画面冻结
            resizeGuardUntilNs = 0L;
            prevFrameSig = null;
            prevFrameSigW = -1;
            prevFrameSigH = -1;
            invalidateRetries = 0;
            stableFrame = img;
            stableSharpness = sharp;
            paintFrameNow();
            return;
        }
        resizeGuardUntilNs = now + RESIZE_GUARD_MS * 1_000_000L; // 帧流续期敏感期
        final byte[] sig = sampleFrameSignature(img, w, h);
        if (prevFrameSig != null && prevFrameSigW == w && prevFrameSigH == h
                && sigDiff(prevFrameSig, sig) <= 2) {
            // 连续两帧一致：画面已停止演进；再过三道闸门（尺寸/锐度/方向结构）确认是「完整帧」才上屏
            if (!frameSizeIsTarget(w, h)) {
                if (!settleRejectLogged) {
                    settleRejectLogged = true;
                    log.info("OSR resize 帧 {}x{} 未达目标 {}x{}，暂不上屏", w, h, lastSetSizeW, lastSetSizeH);
                }
            } else if (!sharpnessOk(sharp)) {
                if (!settleRejectLogged) {
                    settleRejectLogged = true;
                    log.info("OSR resize 帧锐度不足（疑似拉伸/未光栅完成帧）: sharp={} base={}，继续等待",
                            sharp, stableSharpness);
                }
            } else if (!structureOk(mismatch)) {
                if (!settleRejectLogged) {
                    settleRejectLogged = true;
                    log.info("OSR resize 帧方向失衡（疑似压缩/错位中间态）: 失衡采样={}/64，继续等待", mismatch);
                }
            } else {
                log.info("OSR resize 稳定收尾: 帧 {}x{} 连续一致（距 setSize {}ms，sharp={}/{}，失衡={}/64）", w, h,
                        (now - resizeGuardStartNs) / 1_000_000L, sharp, stableSharpness, mismatch);
                settleShow(img);
                return;
            }
        }
        prevFrameSig = sig;
        prevFrameSigW = w;
        prevFrameSigH = h;
        armResizeHold();
    }

    /** 复臂「帧流平静」定时器：首轮用 {@link #RESIZE_QUIET_MS} 跨光栅化批次间隙，后续轮次画面
     *  已临近完成，缩短为 {@link #RESIZE_QUIET_TAIL_MS} 加速收尾。 */
    private void armResizeHold() {
        if (resizeHold == null) {
            resizeHold = new javafx.animation.PauseTransition(javafx.util.Duration.millis(RESIZE_QUIET_MS));
            resizeHold.setOnFinished(e -> onResizeHoldElapsed());
        }
        resizeHold.setDuration(javafx.util.Duration.millis(
                invalidateRetries == 0 ? RESIZE_QUIET_MS : RESIZE_QUIET_TAIL_MS));
        resizeHold.playFromStart();
    }

    /** 帧尺寸是否已等于最近下发的目标尺寸（避免在途旧尺寸帧被判定为完整帧上屏）。 */
    private boolean frameSizeIsTarget(int w, int h) {
        return lastSetSizeW <= 0 || lastSetSizeH <= 0 || (w == lastSetSizeW && h == lastSetSizeH);
    }

    /** 锐度闸门：帧锐度低于基准的一定比率即判为拉伸/未光栅完成的模糊帧；基准过低或不可算时放行。 */
    private boolean sharpnessOk(int sharp) {
        if (sharp < 0 || stableSharpness < SHARPNESS_BASELINE_MIN) {
            return true; // 页面本身低对比/空白：放弃检测，不误拒
        }
        return sharp >= stableSharpness * SHARPNESS_MIN_RATIO;
    }

    /** 方向结构闸门：方向单边（水平强/纵向弱或反向）的失衡采样点占比超 {@link #AXIS_MISMATCH_MAX}
     *  → 疑似压缩/错位中间态；不可算时放行（验证耗尽后的兜底上屏不受闸门限制，不会卡死）。 */
    private boolean structureOk(int mismatchPoints) {
        return mismatchPoints < 0 || mismatchPoints <= AXIS_MISMATCH_MAX;
    }

    /**
     * 帧流平静后的收尾验证（FX 线程）：平静仅代表「这一批」光栅化产出结束，最后一帧仍可能是
     * 批次间隙里的半成品——主动 invalidate 请求一次强制重绘，待其到达后与平静前最后一帧比对
     * （走 {@link #handleFrameInResizeSensitive} 的签名路径）：一致则上屏；不一致说明仍在演进，
     * 继续攒帧/验证。重试 {@link #RESIZE_VERIFY_MAX} 轮后或 invalidate 不可用时，按「尽力而为」
     * 上屏当前帧并进入冷却，绝不让画面卡死。
     */
    private void onResizeHoldElapsed() {
        if (invalidateRetries < RESIZE_VERIFY_MAX && invalidateForVerify()) {
            invalidateRetries++;
            resizeGuardUntilNs = System.nanoTime() + RESIZE_GUARD_MS * 1_000_000L; // 续期敏感期，保证验证帧走比对路径
            armResizeHold(); // 再等一轮平静收验证帧（后续轮次间隔递减）
            return;
        }
        log.info("OSR resize 验证耗尽/不可用，尽力上屏最后帧（距 setSize {}ms）",
                resizeGuardStartNs == 0 ? -1 : (System.nanoTime() - resizeGuardStartNs) / 1_000_000L);
        settleShow(lastFrame); // 验证耗尽/不可用：尽力上屏最后帧（含冷却，防后续批次漏出）
    }

    /** lenient 版锐度采样：相邻像素 |ΔG| 峰值的 P90（64 线 × 31 对，全在原始字节上算，微秒级）。
     *  指标对「线性插值放大」敏感（峰值 ≈ 原值 / 放大率），对内容差异（背景/文本不同）鲁棒。
     *  返回 -1 表示不可算（尺寸过小/缓冲异常）。 */
    private static int estimateSharpness(byte[] bgra, int w, int h, int stride) {
        if (bgra == null || w < SHARPNESS_SEG + 8 || h < 8) {
            return -1;
        }
        final int seg = Math.min(SHARPNESS_SEG, w - 8);
        final int lines = Math.min(SHARPNESS_LINES, h);
        final int[] peaks = new int[lines];
        for (int r = 0; r < lines; r++) {
            final int y = (r * 2 + 1) * h / (lines * 2);
            final int row = y * stride;
            final int x0 = (int) ((long) r * 977 % Math.max(1, w - seg - 1));
            int max = 0;
            for (int x = x0; x < x0 + seg; x++) {
                final int i1 = row + x * 4 + 1;      // G 通道
                final int i2 = i1 + 4;
                int d = (bgra[i1] & 0xFF) - (bgra[i2] & 0xFF);
                if (d < 0) {
                    d = -d;
                }
                if (d > max) {
                    max = d;
                }
            }
            peaks[r] = max;
        }
        java.util.Arrays.sort(peaks);
        return peaks[Math.min(lines - 1, (int) (lines * 0.9))];
    }

    /** 布局失衡采样：8×8 网格取 64 个十字点（横/竖臂各 32px），统计「水平强结构而纵向近乎无」
     *  或反向的失衡点数——「内容被压成竖线/横条」类压缩/错位中间态的失衡点占比远高于正常渲染
     *  （正常文本/卡片点在两个方向都有边缘）。返回失衡点数（0-64），-1 = 不可算。 */
    private static int estimateAxisMismatch(byte[] bgra, int w, int h, int stride) {
        if (bgra == null || w < 40 || h < 40) {
            return -1;
        }
        final int arm = 16;
        int mismatched = 0;
        for (int j = 0; j < 8; j++) {
            final int cy = (j * 2 + 1) * h / 16;
            final int row = cy * stride;
            for (int i = 0; i < 8; i++) {
                final int cx = (i * 2 + 1) * w / 16;
                final int x1 = Math.max(1, cx - arm);
                final int x2 = Math.min(w - 2, cx + arm);
                int hMax = 0;
                for (int x = x1; x < x2; x++) {
                    int d = (bgra[row + x * 4 + 1] & 0xFF) - (bgra[row + (x + 1) * 4 + 1] & 0xFF);
                    if (d < 0) {
                        d = -d;
                    }
                    if (d > hMax) {
                        hMax = d;
                    }
                }
                final int y1 = Math.max(1, cy - arm);
                final int y2 = Math.min(h - 2, cy + arm);
                final int colOff = cx * 4 + 1;
                int vMax = 0;
                for (int y = y1; y < y2; y++) {
                    int d = (bgra[y * stride + colOff] & 0xFF) - (bgra[(y + 1) * stride + colOff] & 0xFF);
                    if (d < 0) {
                        d = -d;
                    }
                    if (d > vMax) {
                        vMax = d;
                    }
                }
                if ((hMax >= AXIS_STRONG && vMax < AXIS_WEAK)
                        || (vMax >= AXIS_STRONG && hMax < AXIS_WEAK)) {
                    mismatched++;
                }
            }
        }
        return mismatched;
    }

    /** 收尾上屏（FX 线程）：设为稳定帧显示，清空比对状态，并延长敏感期作为「冷却」（防收尾过早漏出）。 */
    private void settleShow(WritableImage img) {
        prevFrameSig = null;
        prevFrameSigW = -1;
        prevFrameSigH = -1;
        invalidateRetries = 0;
        stableFrame = img != null ? img : lastFrame;
        paintFrameNow();
        resizeGuardUntilNs = System.nanoTime() + RESIZE_GUARD_MS * 1_000_000L;
        if (resizeHold != null) {
            resizeHold.stop();
        }
    }

    /** 反射调用 CefBrowser_N#invalidate（protected，无公共 API）请求强制重绘；失败返回 false。 */
    private boolean invalidateForVerify() {
        try {
            if (invalidateMethod == null) {
                invalidateMethod = Class.forName("org.cef.browser.CefBrowser_N")
                        .getDeclaredMethod("invalidate");
                invalidateMethod.setAccessible(true);
            }
            invalidateMethod.invoke(browser);
            return true;
        } catch (Throwable t) {
            log.warn("重排验证用的 invalidate 调用不可用，按平静收尾: {}", t.toString());
            return false;
        }
    }

    /** 16×16 网格采样帧的量化亮度构造签名（跨尺寸不可比）：用于「连续两帧是否同一画面」的判定。 */
    private static byte[] sampleFrameSignature(WritableImage img, int w, int h) {
        final byte[] sig = new byte[256];
        final javafx.scene.image.PixelReader pr = img.getPixelReader();
        int k = 0;
        for (int j = 0; j < 16; j++) {
            final int y = Math.min(h - 1, (j * 2 + 1) * h / 32);
            for (int i = 0; i < 16; i++) {
                final int x = Math.min(w - 1, (i * 2 + 1) * w / 32);
                final int argb = pr.getArgb(x, y);
                sig[k++] = (byte) (((argb >> 16 & 0xFF) + (argb >> 8 & 0xFF) + (argb & 0xFF)) / 3);
            }
        }
        return sig;
    }

    /** 两个签名的差异点数（每点亮度差 > 6 记 1）：≤2 点即视作同一画面（容忍重绘的微小抗锯齿差）。 */
    private static int sigDiff(byte[] a, byte[] b) {
        int diff = 0;
        for (int i = 0; i < a.length && i < b.length; i++) {
            if (Math.abs((a[i] & 0xFF) - (b[i] & 0xFF)) > 6) {
                diff++;
            }
        }
        return diff;
    }

    /** 销毁视图：停掉计时器、关闭浏览器/路由/客户端并释放屏幕外宿主窗口（各步容错）。 */
    public void dispose() {
        disposed = true;
        LIVE.remove(this);
        final javafx.animation.PauseTransition fd = sizeFlushDelay;
        if (fd != null) {
            try { fd.stop(); } catch (Throwable ignored) {}
        }
        final javafx.animation.PauseTransition rh = resizeHold;
        if (rh != null) {
            try { rh.stop(); } catch (Throwable ignored) {}
        }
        // 拖动兜底轮询是 INDEFINITE Timeline，只在真实 RELEASE/超时里停；按住鼠标时关窗或切走
        // 视图则没人停它，而它的 tick 会继续读全局光标并向已关闭的 browser 合成事件。
        final javafx.animation.Timeline dp = dragPoll;
        if (dp != null) {
            try { dp.stop(); } catch (Throwable ignored) {}
        }
        final CefBrowser b = browser;
        if (b != null) {
            try { b.close(true); } catch (Throwable ignored) {}
        }
        final CefMessageRouter r = router;
        if (r != null) {
            try { r.dispose(); } catch (Throwable ignored) {}
        }
        final CefClient c = client;
        if (c != null) {
            try { c.dispose(); } catch (Throwable ignored) {}
        }
        final Frame f = realize;
        realize = null;
        if (f != null) {
            EventQueue.invokeLater(() -> {
                try { f.dispose(); } catch (Throwable ignored) {}
            });
        }
    }
}
