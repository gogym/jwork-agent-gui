package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.core.chat.FileTextExtractor;
import com.gettyio.gim.agent.ui.I18n;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import com.gettyio.gim.agent.ui.jcef.JcefOsrView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.nio.file.DirectoryStream;

/**
 * 工作区右侧栏：把 {@code ~/.jwork/workspace} 下的产物/文件以「文件树 + 就地阅读」的形式挂进聊天页右侧分栏。
 * 文件树首层两个入口：「当前会话」指向 {@code <workspace>/<会话ID>/}——智能体全部文件工具都经 Harness
 * 命名空间隔离（RuntimeContext 只传 sessionId）落在该目录，产物即此处所见，入口随 {@link #followSession}
 * 跟随会话切换、展开面板与每轮收尾时就地刷新（{@link #refreshCurrent()}）；「共享工作区」指向 workspace 根，
 * agents/attachments/memory/skills 等共享内容不受会话影响。
 * <p>外层横向 {@link SplitPane}（左聊天、右本面板），与聊天并存、可拖拽调宽；收起仅摘除节点（{@link #hide()}），
 * 树与已读内容状态保留。面板内部再分「阅读区」与「文件树」两块，默认单面板（一次只显示其一），
 * 头部按钮可扩为三列（阅读区 + 文件树并排）。</p>
 * <p>阅读区按扩展名分派：Markdown 复用 {@link MarkdownRenderer}、HTML/SVG 走 {@link JcefOsrView}、
 * 图片走 {@link ImageView}、文档（pdf/docx/xlsx）经 {@link FileTextExtractor} 抽文本、其余按纯文本。
 * 读盘在后台线程进行，回 FX 线程填充。</p>
 * <p>安全：文件树以工作区根为界，选中路径经 {@link #isWithin} 归一化校验，杜绝 {@code ../} 与符号链接越界；
 * 超大或二进制文件不内联预览，回退「用系统程序打开」。</p>
 */
public final class WorkspacePanel {

    private static final Logger log = LoggerFactory.getLogger(WorkspacePanel.class);

    /** 文本/文档/HTML 预览字节上限：超出只给「文件过大」提示，避免读爆内存。 */
    static final long MAX_PREVIEW_BYTES = 2L * 1024 * 1024;
    /** 图片预览字节上限：截图常超 2MB，单独放宽。 */
    static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;
    /** 二进制探测取样前缀字节数。 */
    private static final int BINARY_SNIFF = 8000;
    /** 图片阅读区最大展示宽度。 */
    private static final double MAX_IMAGE_WIDTH = 900;

    /** 阅读区分派类型；{@link #classify} 依据扩展名产出。 */
    enum RenderKind { MARKDOWN, HTML, IMAGE, TEXT, DOC, BINARY, TOO_LARGE, MISSING }

    private final SplitPane workspaceSplit;
    private final Path workspaceRoot;
    /** 展开/收起前的宿主回调（可空）：宿主据此校准窗口宽度，保证聊天视口不因分栏挤压（见 MainController）。 */
    private java.util.function.Consumer<Boolean> visibilityGuard;

    /** 当前跟随的会话 ID（可空=尚无会话）：文件树「当前会话」入口指向它。 */
    private String sessionId;
    /** 当前会话的专属工作区目录（= 智能体实际工作目录，可空）：{@code <workspaceRoot>/<sessionId>}。 */
    private Path sessionDir;
    /** 「当前会话」入口树项（可空）；刷新时整体替换该子树，摆脱一次性懒加载的陈旧内容。 */
    private TreeItem<FileNode> sessionItem;
    /** 「共享工作区」入口树项：workspace 根，不受会话切换影响。 */
    private TreeItem<FileNode> sharedItem;

    private final BorderPane root = new BorderPane();
    private final SplitPane inner = new SplitPane();
    private final StackPane viewerPane = new StackPane();
    private final VBox treePane = new VBox();
    private final TreeView<FileNode> tree = new TreeView<>();
    private final Label crumb = new Label();
    private final Button backBtn = new Button();
    private final Button columnsBtn = new Button();
    private final Button closeBtn = new Button();
    /** HTML/SVG 阅读复用单个 JCEF OSR 视图，首次渲染时创建。 */
    private JcefOsrView htmlView;
    /** 三列模式：阅读区与文件树并排；否则单面板只显示其一。 */
    private boolean threeColumn;
    /** 单面板模式下当前显示的是阅读区（true）还是文件树（false）。 */
    private boolean showingViewer;

    public WorkspacePanel(SplitPane workspaceSplit, GimPaths gimPaths) {
        this.workspaceSplit = workspaceSplit;
        this.workspaceRoot = gimPaths.workspaceDir().toAbsolutePath().normalize();
        build();
    }

    /** 面板根节点：供 {@link SplitPane} 挂入。 */
    public Node getNode() {
        return root;
    }

    /** 注入展开/收起时的宿主守护回调：true=挂入前、false=摘除后；宿主据此校准窗口宽度保聊天视口。 */
    public void setVisibilityGuard(java.util.function.Consumer<Boolean> guard) {
        this.visibilityGuard = guard;
    }

    // ===================== 显隐（仿 TerminalDrawer：收起仅摘节点） =====================

    /** 面板是否已挂入右侧分栏。 */
    public boolean visible() {
        return workspaceSplit != null && workspaceSplit.getItems().contains(root);
    }

    /** 展开：未挂则先经宿主守护校准窗口（必要时扩窗），再挂入并把分栏位压到约 0.62；每次展开刷新会话入口。 */
    public void show() {
        if (workspaceSplit == null) {
            return;
        }
        if (!visible()) {
            if (visibilityGuard != null) {
                visibilityGuard.accept(Boolean.TRUE);
            }
            workspaceSplit.getItems().add(root);
            workspaceSplit.setDividerPositions(0.62);
            // 面板收起期间智能体可能又落了新产物：展开即重建会话入口，所见即所得
            refreshCurrent();
        }
    }

    /** 收起：仅摘除节点（树与阅读状态保留），随后通知宿主恢复基础最小宽度。 */
    public void hide() {
        if (workspaceSplit != null && visible()) {
            workspaceSplit.getItems().remove(root);
            if (visibilityGuard != null) {
                visibilityGuard.accept(Boolean.FALSE);
            }
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

    // ===================== 对外入口 =====================

    /**
     * 跟随会话：把文件树「当前会话」入口切到 {@code <workspaceRoot>/<sessionId>/}（智能体实际工作目录）。
     * <p>整树重建，目录已存在时自动展开；{@code sessionId} 为空（新任务空态）时只显示共享工作区。
     * 宿主在启动接线、切换会话、首条消息建会话处调用；同一会话重复调用保持现状（保留展开状态）。</p>
     */
    public void followSession(String sessionId) {
        String sid = sessionId == null || sessionId.isBlank() ? null : sessionId.trim();
        if (sid != null && sid.equals(this.sessionId) && sessionItem != null) {
            return;
        }
        this.sessionId = sid;
        this.sessionDir = sessionDirOf(workspaceRoot, sid);
        rebuildTree();
    }

    /**
     * 就地刷新「当前会话」入口：整体替换为未加载的新子树（下次展开重列目录），
     * 清掉一次性懒加载缓存的陈旧内容。面板展开、每轮流式收尾后由宿主调用；无会话时为空操作。
     */
    public void refreshCurrent() {
        if (sessionDir == null || tree.getRoot() == null) {
            return;
        }
        TreeItem<FileNode> fresh = dirItem(new FileNode(sessionDir, true, I18n.t("chat.workspace.currentSession")));
        sessionItem = fresh;
        tree.getRoot().getChildren().set(0, fresh);
        if (Files.isDirectory(sessionDir)) {
            fresh.setExpanded(true);
        }
    }

    /** 语言切换：两个入口标签重取文案（子节点是文件名，与语言无关）。 */
    public void applyI18n() {
        if (sessionItem != null && sessionItem.getValue() != null) {
            sessionItem.setValue(new FileNode(sessionDir, true, I18n.t("chat.workspace.currentSession")));
        }
        if (sharedItem != null && sharedItem.getValue() != null) {
            sharedItem.setValue(new FileNode(workspaceRoot, true, I18n.t("chat.workspace.shared")));
        }
    }

    /** 重建整树：合成根（不渲染）下挂「当前会话」（若有）与「共享工作区」两个入口。 */
    private void rebuildTree() {
        TreeItem<FileNode> syntheticRoot = new TreeItem<>();
        sessionItem = sessionDir == null ? null
                : dirItem(new FileNode(sessionDir, true, I18n.t("chat.workspace.currentSession")));
        if (sessionItem != null) {
            syntheticRoot.getChildren().add(sessionItem);
        }
        sharedItem = dirItem(new FileNode(workspaceRoot, true, I18n.t("chat.workspace.shared")));
        syntheticRoot.getChildren().add(sharedItem);
        tree.setRoot(syntheticRoot);
        // 挂树后再展开（事件沿根到达树）：目录已在则子项即时上树，免得用户再点一次
        if (sessionItem != null && Files.isDirectory(sessionDir)) {
            sessionItem.setExpanded(true);
        }
    }

    /**
     * 把聊天内联 HTML/SVG 产物导入阅读区（{@link ArtifactPreviewWindow.PreviewSink} 目标）。
     * <p>自动展开面板并切到阅读区；不产生文件，面包屑显示「产物 · 语言」。</p>
     */
    public void showArtifact(String code, String language) {
        if (code == null || code.isBlank()) {
            return;
        }
        show();
        String lang = language == null ? "" : language.trim().toUpperCase(Locale.ROOT);
        crumb.setText(I18n.t("chat.workspace.artifact") + (lang.isEmpty() ? "" : " · " + lang));
        showingViewer = true;
        applyInnerLayout();
        renderHtml(code, language);
    }

    /**
     * 打开工作区文件：校验归属 → 面包屑定位 → 后台读盘 → 回 FX 线程渲染。
     * <p>越出工作区根的路径直接拒绝；自动展开面板并在单面板模式切到阅读区。</p>
     */
    public void openFile(Path abs) {
        if (abs == null) {
            return;
        }
        Path target = abs.toAbsolutePath().normalize();
        if (!isWithin(workspaceRoot, target)) {
            log.warn("拒绝预览工作区外路径: {}", target);
            return;
        }
        show();
        crumb.setText(crumbFor(target));
        showingViewer = true;
        applyInnerLayout();
        renderPlaceholder(I18n.t("chat.workspace.loading"));
        Mono.fromCallable(() -> readForPreview(target))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(payload -> Platform.runLater(() -> renderPayload(target, payload)),
                        err -> {
                            log.warn("预览文件失败: {}", target, err);
                            Platform.runLater(() -> renderFallback(RenderKind.MISSING, target));
                        });
    }

    // ===================== 构建 =====================

    private void build() {
        root.getStyleClass().add("workspace-panel");
        root.setTop(buildHeader());
        inner.getStyleClass().add("workspace-inner");
        inner.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        root.setCenter(inner);
        buildViewer();
        buildTree();
        applyInnerLayout();
    }

    private Node buildHeader() {
        crumb.getStyleClass().add("workspace-crumb");
        crumb.setMaxWidth(Region.USE_PREF_SIZE);
        crumb.setEllipsisString("\u2026");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        configureTool(backBtn, "\uE72B", "chat.workspace.back", () -> {
            showingViewer = false;
            applyInnerLayout();
        });
        configureTool(columnsBtn, "\uE8A1", "chat.workspace.columns.expand", () -> {
            threeColumn = !threeColumn;
            applyToolTips();
            applyInnerLayout();
        });
        configureTool(closeBtn, "\uE711", "chat.workspace.close", this::hide);

        HBox header = new HBox(4, crumb, spacer, backBtn, columnsBtn, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("workspace-header");
        return header;
    }

    private void configureTool(Button btn, String glyph, String tooltipKey, Runnable action) {
        btn.setGraphic(new Label(glyph));
        btnGraphicClass(btn);
        btn.getStyleClass().add("workspace-tool");
        btn.setFocusTraversable(false);
        btn.setTooltip(new javafx.scene.control.Tooltip(I18n.t(tooltipKey)));
        btn.setOnAction(e -> action.run());
    }

    private void btnGraphicClass(Button btn) {
        if (btn.getGraphic() instanceof Label l) {
            l.getStyleClass().add("icon");
        }
    }

    private void buildViewer() {
        viewerPane.getStyleClass().add("workspace-viewer");
    }

    private void buildTree() {
        tree.getStyleClass().add("workspace-tree");
        // 合成根不渲染：首层直接是「当前会话 / 共享工作区」两个入口（见 rebuildTree）
        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<>());
        tree.setCellFactory(v -> new FileCell());
        // 展开目录时懒列子项（TreeItem 无 childrenFactory，用占位子项 + 展开事件实现）
        tree.addEventHandler(TreeItem.<FileNode>branchExpandedEvent(), e -> onExpand(e.getTreeItem()));
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null && sel.getValue() != null && !sel.getValue().directory()) {
                openFile(sel.getValue().path());
            }
        });
        treePane.getChildren().setAll(tree);
        treePane.getStyleClass().add("workspace-tree-pane");
        VBox.setVgrow(tree, Priority.ALWAYS);
    }

    /** 依据三列/单面板模式重排 inner 挂载项（节点单父，先摘后挂）。 */
    private void applyInnerLayout() {
        if (threeColumn) {
            detach(inner, viewerPane);
            detach(inner, treePane);
            inner.getItems().setAll(viewerPane, treePane);
            inner.setDividerPositions(0.62);
        } else {
            Node only = showingViewer ? viewerPane : treePane;
            detach(inner, viewerPane);
            detach(inner, treePane);
            inner.getItems().setAll(only);
        }
    }

    private static void detach(SplitPane sp, Node n) {
        sp.getItems().remove(n);
    }

    private void applyToolTips() {
        setTip(columnsBtn, threeColumn ? "chat.workspace.columns.collapse" : "chat.workspace.columns.expand");
    }

    private void setTip(Button btn, String key) {
        if (btn.getTooltip() != null) {
            btn.getTooltip().setText(I18n.t(key));
        }
    }

    // ===================== 阅读区渲染 =====================

    private void renderPlaceholder(String text) {
        viewerPane.getChildren().setAll(centeredLabel(text));
    }

    private void renderPayload(Path path, PreviewPayload payload) {
        switch (payload.kind()) {
            case MARKDOWN -> renderMarkdown(payload.text());
            case HTML -> renderHtml(payload.text(), extOf(path));
            case IMAGE -> renderImage(payload.bytes());
            case TEXT, DOC -> renderText(payload.text());
            default -> renderFallback(payload.kind(), path);
        }
    }

    private void renderMarkdown(String markdown) {
        VBox box = new VBox(6);
        box.getStyleClass().add("workspace-viewer-body");
        box.getChildren().addAll(MarkdownRenderer.render(markdown == null ? "" : markdown));
        showScrollable(box);
    }

    private void renderText(String text) {
        TextArea area = new TextArea(text == null ? "" : text);
        area.setEditable(false);
        area.setWrapText(true);
        area.getStyleClass().add("workspace-code");
        viewerPane.getChildren().setAll(area);
    }

    private void renderHtml(String code, String language) {
        if (htmlView == null) {
            htmlView = new JcefOsrView("gimQuery", "gimCancel", null);
        }
        viewerPane.getChildren().setAll(htmlView.node());
        htmlView.loadData(ArtifactPreviewWindow.buildHtml(code, language));
    }

    private void renderImage(byte[] bytes) {
        if (bytes == null) {
            renderFallback(RenderKind.MISSING, null);
            return;
        }
        Image image = new Image(new ByteArrayInputStream(bytes));
        if (image.isError()) {
            renderFallback(RenderKind.BINARY, null);
            return;
        }
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        double w = image.getWidth();
        view.setFitWidth(w > 0 ? Math.min(MAX_IMAGE_WIDTH, w) : MAX_IMAGE_WIDTH);
        view.getStyleClass().add("workspace-image");
        showScrollable(view);
    }

    private void renderFallback(RenderKind kind, Path path) {
        String msg = switch (kind) {
            case TOO_LARGE -> I18n.t("chat.workspace.tooLarge");
            case BINARY -> I18n.t("chat.workspace.binary");
            default -> I18n.t("chat.workspace.missing");
        };
        VBox box = new VBox(12, centeredLabel(msg));
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("workspace-empty");
        if (path != null && Files.isRegularFile(path)) {
            Button open = new Button(I18n.t("chat.workspace.openWith"));
            open.getStyleClass().add("workspace-open-sys");
            open.setFocusTraversable(false);
            open.setOnAction(e -> openWithSystem(path));
            box.getChildren().add(open);
        }
        viewerPane.getChildren().setAll(box);
    }

    private void showScrollable(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("workspace-scroll");
        viewerPane.getChildren().setAll(scroll);
    }

    private Label centeredLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("workspace-hint");
        label.setWrapText(true);
        return label;
    }

    // ===================== 读盘（后台线程） =====================

    private PreviewPayload readForPreview(Path path) {
        if (!Files.isRegularFile(path)) {
            return new PreviewPayload(RenderKind.MISSING, null, null);
        }
        RenderKind kind = classify(path.getFileName().toString());
        try {
            long size = Files.size(path);
            long cap = kind == RenderKind.IMAGE ? MAX_IMAGE_BYTES : MAX_PREVIEW_BYTES;
            if (tooLarge(size, cap)) {
                return new PreviewPayload(RenderKind.TOO_LARGE, null, null);
            }
            byte[] bytes = Files.readAllBytes(path);
            if (kind == RenderKind.IMAGE) {
                return new PreviewPayload(RenderKind.IMAGE, null, bytes);
            }
            if (kind == RenderKind.DOC) {
                String text = FileTextExtractor.extract(path.getFileName().toString(), null, bytes);
                return new PreviewPayload(RenderKind.DOC, text, null);
            }
            if (looksBinary(bytes)) {
                return new PreviewPayload(RenderKind.BINARY, null, null);
            }
            return new PreviewPayload(kind, new String(bytes, StandardCharsets.UTF_8), null);
        } catch (IOException e) {
            log.warn("读取预览文件失败: {}", path, e);
            return new PreviewPayload(RenderKind.MISSING, null, null);
        }
    }

    private void openWithSystem(Path path) {
        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception e) {
            log.warn("系统程序打开失败: {}", path, e);
        }
    }

    // ===================== 纯函数（可单测，不触 FX） =====================

    /** 按扩展名分派阅读类型；未知一律按纯文本尝试（二进制在读取阶段兜底）。包私有便于单测。 */
    static RenderKind classify(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".md") || n.endsWith(".markdown")) {
            return RenderKind.MARKDOWN;
        }
        if (n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xhtml") || n.endsWith(".svg")) {
            return RenderKind.HTML;
        }
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif")
                || n.endsWith(".bmp") || n.endsWith(".webp")) {
            return RenderKind.IMAGE;
        }
        if (n.endsWith(".pdf") || n.endsWith(".docx") || n.endsWith(".xlsx")) {
            return RenderKind.DOC;
        }
        return RenderKind.TEXT;
    }

    /** 目标是否落在工作区根内（归一化后前缀判定），杜绝 {@code ../} 与符号链接外逃逸。 */
    static boolean isWithin(Path root, Path target) {
        Path r = root.toAbsolutePath().normalize();
        Path t = target.toAbsolutePath().normalize();
        return t.startsWith(r);
    }

    /** 会话专属工作区 = {@code <root>/<sessionId>}；空会话 ID 或归一化后越出根（含分隔符/..）返回 {@code null}。 */
    static Path sessionDirOf(Path root, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Path dir = root.resolve(sessionId).toAbsolutePath().normalize();
        return isWithin(root, dir) ? dir : null;
    }

    /** 是否超过给定字节上限。 */
    static boolean tooLarge(long size, long cap) {
        return size > cap;
    }

    /** 前缀取样含 NUL 字节即判二进制。 */
    static boolean looksBinary(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        int n = Math.min(bytes.length, BINARY_SNIFF);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String extOf(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    /** 面包屑：会话内文件为「当前会话 › 相对子路径」，共享区仍为「workspace › 相对子路径」。 */
    private String crumbFor(Path target) {
        Path base = workspaceRoot;
        String head = workspaceRoot.getFileName().toString();
        if (sessionDir != null && isWithin(sessionDir, target)) {
            base = sessionDir;
            head = I18n.t("chat.workspace.currentSession");
        }
        List<String> parts = new ArrayList<>();
        parts.add(head);
        Path rel = base.relativize(target);
        for (Path seg : rel) {
            parts.add(seg.toString());
        }
        return String.join(" \u203A ", parts);
    }

    // ===================== 文件树数据与单元 =====================

    /** 树节点值：路径 + 是否目录 + 显示名覆写（可空，用于「当前会话/共享工作区」两个入口）。 */
    record FileNode(Path path, boolean directory, String label) {
        FileNode(Path path, boolean directory) {
            this(path, directory, null);
        }

        /** 单元格显示名：覆写优先，否则取路径末段。 */
        String displayName() {
            return label != null ? label : path.getFileName().toString();
        }
    }

    /** 读盘结果载荷：类型 + 文本（md/html/text/doc）+ 字节（image）。 */
    private record PreviewPayload(RenderKind kind, String text, byte[] bytes) {
    }

    /** 目录节点：挂一个空值占位子项以出现展开箭头，真正子项在展开时懒列。 */
    private TreeItem<FileNode> dirItem(Path p) {
        return dirItem(new FileNode(p, true));
    }

    /** 目录节点（带显示名覆写）：「当前会话/共享工作区」两个入口节点复用。 */
    private TreeItem<FileNode> dirItem(FileNode node) {
        TreeItem<FileNode> item = new TreeItem<>(node);
        item.getChildren().add(new TreeItem<>());
        return item;
    }

    /** 展开目录时把占位子项替换为真实列表（仅首次，子项仍为占位则再懒列；已加载目录的陈旧内容由会话入口重建刷新）。 */
    private void onExpand(TreeItem<FileNode> item) {
        if (item == null || item.getValue() == null || !item.getValue().directory()) {
            return;
        }
        ObservableList<TreeItem<FileNode>> children = item.getChildren();
        boolean notLoaded = children.size() == 1 && children.get(0).getValue() == null;
        if (notLoaded) {
            children.setAll(listChildren(item.getValue().path()));
        }
    }

    /** 列一个目录的直接子项：过滤工作区外、目录优先、按名排序。 */
    private ObservableList<TreeItem<FileNode>> listChildren(Path dir) {
        ObservableList<TreeItem<FileNode>> list = FXCollections.observableArrayList();
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                Path abs = p.toAbsolutePath().normalize();
                if (isWithin(workspaceRoot, abs)) {
                    entries.add(abs);
                }
            }
        } catch (IOException | SecurityException e) {
            // 目录不可读：返回空列表（展开时显示无子项），不抛出
            log.debug("列目录失败: {}", dir, e);
            return list;
        }
        entries.sort(Comparator.comparing((Path p) -> !isDirectory(p))
                .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        for (Path p : entries) {
            list.add(isDirectory(p) ? dirItem(p) : new TreeItem<>(new FileNode(p, false)));
        }
        return list;
    }

    private static boolean isDirectory(Path p) {
        try {
            return Files.isDirectory(p);
        } catch (SecurityException e) {
            return false;
        }
    }

    /** 单元格：目录/文件图标 + 名称。 */
    private final class FileCell extends TreeCell<FileNode> {
        @Override
        protected void updateItem(FileNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label icon = new Label(item.directory() ? "\uE8B7" : "\uE7C3");
            icon.getStyleClass().add("workspace-tree-icon");
            setGraphic(icon);
            setText(item.displayName());
        }
    }
}
