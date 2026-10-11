package com.gettyio.gim.agent.ui.view;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import com.techsenger.jeditermfx.core.CursorShape;
import com.techsenger.jeditermfx.core.Color;
import com.techsenger.jeditermfx.core.ProcessTtyConnector;
import com.techsenger.jeditermfx.core.TerminalColor;
import com.techsenger.jeditermfx.core.TextStyle;
import com.techsenger.jeditermfx.core.TtyConnector;
import com.techsenger.jeditermfx.core.emulator.ColorPalette;
import com.techsenger.jeditermfx.core.util.Platform;
import com.techsenger.jeditermfx.ui.DefaultHyperlinkFilter;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import com.techsenger.jeditermfx.ui.settings.DefaultSettingsProvider;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import jakarta.annotation.PreDestroy;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 底部内嵌终端抽屉（方案 C：JediTermFX 真 PTY 终端）。
 * <p>{@link JediTermFxWidget} 基于 JediTerm 的 JavaFX 移植，走 pty4j 分配伪终端、支持 xterm 256 色，
 * 因此可正常显示彩色输出并运行全屏 TUI 程序，无需 SwingNode 桥接。</p>
 * <p>懒启动：首次展开时才拉起 shell 进程，之后收起仅从场景图摘除、进程与历史保留；
 * 重开复用同一 widget。应用退出时 {@link #shutdown()} 统一销毁进程。</p>
 */
@Component
public class TerminalView {

    private static final Logger logger = LoggerFactory.getLogger(TerminalView.class);

    @Autowired
    private GimPaths gimPaths;

    /** 抽屉整体：顶部小工具条（标题 + 关闭）+ 终端内容区。 */
    private final VBox root = new VBox();
    /** 终端内容宿主：widget.getPane() 挂在这里。 */
    private final StackPane host = new StackPane();
    private Label titleLabel;
    private Button themeButton;
    private Button closeButton;

    /** 当前终端 widget；未启动为 null，进程退出后重置为 null。 */
    private JediTermFxWidget widget;
    /** 当前 widget 的设置提供者；其配色可变，切换主题时就地改色 + 重绘，无需重建终端。 */
    private GimSettingsProvider provider;
    /** 终端内部配色开关：null 表示跟随应用主题，用户手动切换后仅影响终端画布（不动全局主题）。 */
    private Boolean terminalDark;
    /** shell 进程退出时通知宿主收起抽屉。 */
    private Runnable onCloseRequest = () -> {
    };

    public TerminalView() {
        buildUi();
        I18n.onChange(lang -> applyI18n());
    }

    public Node getNode() {
        return root;
    }

    public void setOnCloseRequest(Runnable onCloseRequest) {
        this.onCloseRequest = onCloseRequest == null ? () -> {
        } : onCloseRequest;
    }

    /** 首次展开时懒启动 PTY；已启动则复用（把终端面板交回宿主）。 */
    public void ensureStarted() {
        if (widget != null) {
            if (!host.getChildren().contains(widget.getPane())) {
                host.getChildren().setAll(widget.getPane());
            }
            focusTerminal();
            return;
        }
        try {
            startTerminal();
        } catch (Exception e) {
            logger.error("终端启动失败", e);
            Label err = new Label(I18n.t("chat.terminal.startFailed"));
            err.getStyleClass().add("terminal-error");
            host.getChildren().setAll(err);
        }
    }

    private void startTerminal() throws Exception {
        String[] command = resolveShell();
        List<String> commandLine = List.of(command);
        Map<String, String> envs = new HashMap<>(System.getenv());
        envs.put("TERM", "xterm-256color");

        PtyProcess process = new PtyProcessBuilder()
                .setCommand(command)
                .setDirectory(gimPaths.workspaceDir().toString())
                .setEnvironment(envs)
                .start();

        provider = new GimSettingsProvider(isTerminalDark());
        widget = new JediTermFxWidget(provider);
        widget.setTtyConnector(new PtyConnector(process, StandardCharsets.UTF_8, commandLine));
        widget.addHyperlinkFilter(new DefaultHyperlinkFilter());
        // shell 退出（所有会话结束）：销毁资源、复位状态、收起抽屉
        widget.addListener(source -> {
            // 仅处理当前仍在使用的 widget；旧的/已替换的 widget 回调（source != 当前 widget）忽略，避免误收起抽屉
            if (source != widget) {
                return;
            }
            JediTermFxWidget current = widget;
            widget = null;
            current.close();
            javafx.application.Platform.runLater(onCloseRequest);
        });
        widget.start();
        // 默认用竖线光标（现代终端常见，且不受块状光标反色渲染分支影响）
        widget.getTerminalPanel().setCursorShape(CursorShape.BLINK_VERTICAL_BAR);
        host.getChildren().setAll(widget.getPane());
        applyTerminalChrome();
        focusTerminal();
    }

    /** 光标闪烁仅在 canvas 聚焦时进行；面板刚挂进场景时同步 requestFocus 常失效，故延后聚焦到 canvas 本身。 */
    private void focusTerminal() {
        JediTermFxWidget w = widget;
        if (w == null) {
            return;
        }
        javafx.application.Platform.runLater(() -> w.getTerminalPanel().getCanvas().requestFocus());
    }

    /** 优先 PowerShell（含 -NoLogo 去横幅），探测不到回退 cmd.exe。 */
    private static String[] resolveShell() {
        if (Platform.isWindows()) {
            String windir = System.getenv("SystemRoot");
            File ps = new File(windir == null ? "C:\\Windows" : windir,
                    "System32\\WindowsPowerShell\\v1.0\\powershell.exe");
            if (ps.isFile()) {
                return new String[]{ps.getAbsolutePath(), "-NoLogo"};
            }
            return new String[]{"cmd.exe", "/K"};
        }
        String shell = System.getenv("SHELL");
        return new String[]{shell == null || shell.isBlank() ? "/bin/bash" : shell, "--login"};
    }

    /** 应用退出：销毁终端进程与线程池资源（Spring 关闭容器时在 FX 线程回调）。 */
    @PreDestroy
    public void shutdown() {
        JediTermFxWidget current = widget;
        widget = null;
        if (current != null) {
            TtyConnector connector = current.getTtyConnector();
            current.close();
            if (connector != null) {
                connector.close();
            }
        }
    }

    private void buildUi() {
        root.getStyleClass().add("terminal-drawer");

        // 左侧标签式标题：终端图标 + 文本，包成圆角小胶囊（参考 Qoder 底部终端的 “>_ output” 页签）
        Label tabIcon = UiTheme.icon("\uE756");
        tabIcon.getStyleClass().add("terminal-tab-icon");
        titleLabel = new Label();
        titleLabel.getStyleClass().add("terminal-tab-text");
        HBox tab = new HBox(6, tabIcon, titleLabel);
        tab.getStyleClass().add("terminal-tab");
        tab.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        closeButton = new Button();
        closeButton.getStyleClass().add("terminal-drawer-close");
        closeButton.setGraphic(UiTheme.icon("\uE894"));
        closeButton.setOnAction(e -> onCloseRequest.run());

        themeButton = new Button();
        themeButton.getStyleClass().add("terminal-drawer-theme");
        themeButton.setOnAction(e -> toggleTheme());
        updateThemeIcon();

        HBox bar = new HBox(8, tab, spacer, themeButton, closeButton);
        bar.getStyleClass().add("terminal-drawer-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        host.getStyleClass().add("terminal-host");
        VBox.setVgrow(host, Priority.ALWAYS);

        root.getChildren().addAll(bar, host);
        applyI18n();
    }

    private void applyI18n() {
        titleLabel.setText(I18n.t("chat.terminal.title"));
        themeButton.setTooltip(new Tooltip(I18n.t("chat.terminal.theme")));
        closeButton.setTooltip(new Tooltip(I18n.t("chat.terminal.close")));
    }

    /** 终端当前是否采用暗色画布：用户手动切过就用其选择，否则跟随应用主题。 */
    private boolean isTerminalDark() {
        return terminalDark != null ? terminalDark : UiTheme.mode() == UiTheme.Mode.DARK;
    }

    /** 终端主题切换：仅就地改终端画布配色并触发重绘（不重启 shell、不影响全局主题）。 */
    private void toggleTheme() {
        terminalDark = !isTerminalDark();
        updateThemeIcon();
        if (provider != null) {
            provider.setDark(isTerminalDark());
        }
        applyTerminalChrome();
        if (widget != null) {
            widget.getTerminalPanel().repaint();
        }
    }

    /** 让宿主内边距区域与终端画布底色一致，避免深色终端外围露出浅色应用底。 */
    private void applyTerminalChrome() {
        host.setStyle("-fx-background-color: " + (isTerminalDark() ? "#1e1e1d" : "#ffffff") + ";");
    }

    /** 根据终端当前配色更新按钮图标：图标代指“点击后要去”的主题——浅色显月亮（转暗），深色显太阳（转亮）。 */
    private void updateThemeIcon() {
        if (themeButton != null) {
            themeButton.setGraphic(UiTheme.icon(isTerminalDark() ? "\uE706" : "\uE708"));
        }
    }

    /** 深色/浅色主题下的前景与背景色（覆盖 {@link DefaultSettingsProvider} 的亮色默认值）。 */
    private static final class GimSettingsProvider extends DefaultSettingsProvider {
        private boolean dark;
        /** ANSI 调色板缓存；getPalette 每帧每字都会取，只在主题切换时重建。 */
        private ColorPalette paletteCache;

        GimSettingsProvider(boolean dark) {
            this.dark = dark;
        }

        void setDark(boolean dark) {
            if (this.dark != dark) {
                this.dark = dark;
                this.paletteCache = null;
            }
        }

        @Override
        public float getTerminalFontSize() {
            // 库默认 14，略调小以与聊天区文字观感更协调
            return 13f;
        }

        @Override
        public ColorPalette getTerminalColorPalette() {
            ColorPalette cache = paletteCache;
            if (cache == null) {
                cache = dark ? super.getTerminalColorPalette() : buildLightPalette();
                paletteCache = cache;
            }
            return cache;
        }

        /** 浅色主题下把在白底上太亮的 ANSI 色（黄/绿/青/白等）压深，保证命令文字可读。 */
        private ColorPalette buildLightPalette() {
            ColorPalette base = super.getTerminalColorPalette();
            Color[] c = new Color[16];
            for (int i = 0; i < 16; i++) {
                c[i] = base.getForeground(TerminalColor.index(i));
            }
            c[3] = new Color(0x7f7f00);   // Yellow（原橄榄色，略压深）
            c[7] = new Color(0x6a6a6a);   // White→中灰，避免白字贴白底
            c[10] = new Color(0x009000);  // Bright Green
            c[11] = new Color(0xb58900);  // Bright Yellow→琥珀色，修复黄字看不清
            c[13] = new Color(0xa000a0);  // Bright Magenta
            c[14] = new Color(0x008a8a);  // Bright Cyan
            c[15] = new Color(0x545454);  // Bright White→深灰
            return new ArrayColorPalette(c);
        }

        @Override
        public TextStyle getDefaultStyle() {
            // 返回带具体颜色的默认样式：模拟机在 SGR 重置时会写入无色的 TextStyle，故单元格颜色仍走实时窗口色（改色不需重建）；
            // 而光标/反色路径会 requireNonNull(默认前景色)，必须非 null 才不会抛 NPE 导致光标画不出来
            return new TextStyle(getDefaultForeground(), getDefaultBackground());
        }

        @Override
        public TerminalColor getDefaultForeground() {
            // 对齐应用正文色（浅色 #262626 / 深色 #dededa），避免纯黑字配纯白底过于刺眼
            return dark ? TerminalColor.rgb(222, 222, 218) : TerminalColor.rgb(38, 38, 38);
        }

        @Override
        public TerminalColor getDefaultBackground() {
            // 对齐应用背景色（浅色 #ffffff / 深色 #1e1e1d），消除终端与抽屉底色的接缝
            return dark ? TerminalColor.rgb(30, 30, 29) : TerminalColor.rgb(255, 255, 255);
        }

        @Override
        public boolean useInverseSelectionColor() {
            // 默认样式改为无色后，反色选区会去取 StyleState 默认色（为 null）导致 NPE，改用固定选区配色
            return false;
        }
    }

    /** 以 16 色调色板驱动的 {@link ColorPalette} 实现（{@code ColorPaletteImpl} 构造器不公开，自建）。 */
    private static final class ArrayColorPalette extends ColorPalette {
        private final Color[] colors;

        ArrayColorPalette(Color[] colors) {
            this.colors = colors;
        }

        @Override
        protected Color getForegroundByColorIndex(int colorIndex) {
            return colors[colorIndex];
        }

        @Override
        protected Color getBackgroundByColorIndex(int colorIndex) {
            return colors[colorIndex];
        }
    }

    /** {@link ProcessTtyConnector} 的最小具体实现（库内该抽象类未提供公开的本地 shell 子类）。 */
    private static final class PtyConnector extends ProcessTtyConnector {
        PtyConnector(PtyProcess process, java.nio.charset.Charset charset, List<String> commandLine) {
            super(process, charset, commandLine);
        }

        @Override
        public String getName() {
            return "Gim Terminal";
        }
    }
}
