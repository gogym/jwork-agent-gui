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

package com.gettyio.jwork.agent.ui.chat.workspace;

import com.gettyio.jwork.agent.core.JworkPaths;
import com.gettyio.jwork.agent.core.chat.FileTextExtractor;
import com.gettyio.jwork.agent.core.chat.SessionArtifactDelivery;
import com.gettyio.jwork.agent.core.doc.TabularInspector;
import com.gettyio.jwork.agent.core.fs.CanonicalPath;
import com.gettyio.jwork.agent.domain.util.FileKinds;
import com.gettyio.jwork.agent.domain.util.OsPlatform;
import com.gettyio.jwork.agent.ui.chat.render.MarkdownRenderer;
import com.gettyio.jwork.agent.ui.i18n.I18n;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
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
import com.gettyio.jwork.agent.ui.jcef.JcefOsrView;
import com.gettyio.jwork.agent.ui.theme.UiTheme;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.nio.file.DirectoryStream;

/**
 * 工作区右侧栏：把 {@code ~/.jwork/workspace} 下的产物/文件以「文件树 + 就地阅读」的形式挂进聊天页右侧分栏。
 * 文件树入口随会话与智能体跟随（{@link #followSession}）：宿主按运行时装配同一口径解析出智能体工作区后传入，
 * 「当前会话」指向 {@code <智能体工作区>/<会话ID>/}——智能体文件工具经 Harness 命名空间隔离（RuntimeContext
 * 只传 sessionId）落在该目录；「智能体工作区」（仅非默认智能体，与共享根重合时不重复出现）指向其工作区根，
 * 外壳脚本等直接产物常落在此层；「共享工作区」指向 workspace 根。入口在会话切换、展开面板与每轮收尾时
 * 就地刷新（{@link #refreshCurrent()}）。
 * <p>默认「产物视图」（{@link #artifactsOnly}）：用户只关心产物、不关心智能体工作目录——「当前会话」入口
 * 合并拍平该会话交付件（{@code generated/sessions/<会话ID>/}）与 {@code outputs/}，「共享工作区」入口只挂
 * {@code generated/}（生图与按会话标题分组的历史交付件）；记忆/人格/技能/会话工作目录等内部内容
 * 经头部过滤开关切回全量树才可见。</p>
 * <p>外层横向 {@link SplitPane}（左聊天、右本面板），与聊天并存、可拖拽调宽；收起仅摘除节点（{@link #hide()}），
 * 树与已读内容状态保留。面板内部再分「阅读区」与「文件树」两块，默认单面板（一次只显示其一），
 * 头部按钮可扩为三列（阅读区 + 文件树并排）。</p>
 * <p>阅读区按扩展名分派：Markdown 复用 {@link MarkdownRenderer}、HTML/SVG 走 {@link JcefOsrView}、
 * 图片走 {@link ImageView}、文档（pdf/docx/xlsx）经 {@link FileTextExtractor} 抽文本、其余按纯文本。
 * 读盘在后台线程进行，回 FX 线程填充。</p>
 * <p>安全：文件树以工作区根为界，选中路径经 {@link #isWithin} 归一化校验，杜绝 {@code ../} 与符号链接越界；
 * 超大或二进制文件不内联预览，回退「用系统程序打开」。</p>
 *
 * @author gogym
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
    /** 产物空态提示行的单元格样式类：灰字无图标、无悬停/选中底色。 */
    private static final String HINT_CELL_CLASS = "workspace-tree-hint-cell";

    /** 阅读区分派类型；{@link #classify} 依据扩展名产出。 */
    enum RenderKind { MARKDOWN, HTML, IMAGE, TEXT, DOC, TABLE, BINARY, TOO_LARGE, MISSING }

    private final SplitPane workspaceSplit;
    private final Path workspaceRoot;
    /** 生图等共享产物根：产物视图「共享工作区」入口挂它。 */
    private final Path generatedDir;
    /** 交付产物根 {@code generated/sessions/}：历史交付按会话目录收纳。 */
    private final Path deliveriesDir;
    /** 展开/收起前的宿主回调（可空）：宿主据此校准窗口宽度，保证聊天视口不因分栏挤压（见 MainController）。 */
    private java.util.function.Consumer<Boolean> visibilityGuard;

    /** 当前跟随的会话 ID（可空=尚无会话）：文件树「当前会话」入口指向它。 */
    private String sessionId;
    /** 当前智能体的工作区根（恒在共享根内，默认智能体即共享根）：会话入口与「智能体工作区」入口的基准。 */
    private Path agentRoot;
    /** 当前会话的专属工作区目录（= 智能体实际工作目录，可空）：{@code <agentRoot>/<sessionId>}。 */
    private Path sessionDir;
    /** 「当前会话」入口树项（可空）；刷新时整体替换该子树，摆脱一次性懒加载的陈旧内容。 */
    private TreeItem<FileNode> sessionItem;
    /** 「智能体工作区」入口树项（可空=与共享根重合的默认智能体，不重复出现）；刷新时同样整体替换。 */
    private TreeItem<FileNode> agentItem;
    /** 「共享工作区」入口树项：全量视图挂 workspace 根、产物视图挂 generated/，不受会话切换影响。 */
    private TreeItem<FileNode> sharedItem;
    /** 产物视图（默认开）：只展示交付件/outputs/generated，内部目录经头部开关切全量树才可见。 */
    private boolean artifactsOnly = true;
    /** 会话标题解析器（可空）：历史交付目录重命名用，宿主接会话列表；缺失回退会话 ID。 */
    private java.util.function.Function<String, String> sessionTitleResolver;

    private final BorderPane root = new BorderPane();
    private final SplitPane inner = new SplitPane();
    private final StackPane viewerPane = new StackPane();
    private final VBox treePane = new VBox();
    private final TreeView<FileNode> tree = new TreeView<>();
    private final Label crumb = new Label();
    private final Button columnsBtn = new Button();
    private final Button filterBtn = new Button();
    private final Button backBtn = new Button();
    /** 外部打开菜单按钮：默认关联一键开 + 「选择其他程序」走系统选择器（见 fillOpenWithMenu）。 */
    private final MenuButton openSysBtn = new MenuButton();
    private final Button closeBtn = new Button();
    /** 当前预览的真实文件（可空=内联产物/未预览）：外部打开按钮据此显隐与取路径。 */
    private Path previewPath;
    /** 当前阅读区是否为表格（非空即表格载荷）：主题/语言切换时据此重渲染。 */
    private List<TabularInspector.SheetTable> tableSheets;
    /** HTML/SVG 阅读复用单个 JCEF OSR 视图，首次渲染时创建。 */
    private JcefOsrView htmlView;
    /** 三列模式：阅读区与文件树并排；否则单面板只显示其一。 */
    private boolean threeColumn;
    /** 单面板模式下当前显示的是阅读区（true）还是文件树（false）。 */
    private boolean showingViewer;

    public WorkspacePanel(SplitPane workspaceSplit, JworkPaths jworkPaths) {
        this.workspaceSplit = workspaceSplit;
        this.workspaceRoot = jworkPaths.workspaceDir().toAbsolutePath().normalize();
        this.generatedDir = jworkPaths.generatedDir().toAbsolutePath().normalize();
        this.deliveriesDir = jworkPaths.deliveriesDir().toAbsolutePath().normalize();
        this.agentRoot = this.workspaceRoot;
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

    /** 注入会话标题解析器：产物视图把历史交付目录的 UUID 重命名为会话标题（无标题回退 ID）。 */
    public void setSessionTitleResolver(java.util.function.Function<String, String> resolver) {
        this.sessionTitleResolver = resolver;
    }

    // ===================== 显隐（仿 TerminalDrawer：收起仅摘节点） =====================

    /** 面板是否已挂入右侧分栏。 */
    public boolean visible() {
        return workspaceSplit != null && workspaceSplit.getItems().contains(root);
    }

    /**
     * 展开：未挂则先经宿主守护校准窗口（必要时扩窗），再挂入并把分栏位压到约 0.62；
     * 每次展开复位到文件列表（与头部返回按钮同口径的双返回路径）并刷新会话入口。
     */
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
            // 单面板模式的返回路径：收起再展开回到文件列表（openFile/showArtifact 随后会再切到阅读区）
            showingViewer = false;
            applyInnerLayout();
            // 面板收起期间智能体可能又落了新产物：展开即重建会话/智能体入口，所见即所得
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
     * 跟随会话：文件树入口切到 {@code agentWorkspace} 语境——「当前会话」= {@code <agentWorkspace>/<sessionId>/}
     * （智能体文件工具经命名空间隔离的实际落点），「智能体工作区」= agentWorkspace 根（非默认智能体专属）。
     * <p>{@code agentWorkspace} 由宿主按运行时装配同一口径解析（MultiAgentGateway#workspaceForAgent）；
     * 为空或越出共享根时回退共享根。整树重建，目录已在时自动展开；{@code sessionId} 为空（新任务空态）时
     * 只显示智能体/共享工作区入口。同一会话与工作区重复调用保持现状（保留展开状态）。</p>
     */
    public void followSession(String sessionId, Path agentWorkspace) {
        String sid = sessionId == null || sessionId.isBlank() ? null : sessionId.trim();
        Path root = agentRootOf(workspaceRoot, agentWorkspace);
        if (Objects.equals(sid, this.sessionId) && root.equals(this.agentRoot) && sharedItem != null) {
            return;
        }
        this.sessionId = sid;
        this.agentRoot = root;
        this.sessionDir = sessionDirOf(root, sid);
        rebuildTree();
    }

    /**
     * 就地刷新「当前会话」与「智能体工作区」入口：整体替换为未加载的新子树（下次展开重列目录），
     * 清掉一次性懒加载缓存的陈旧内容。面板展开、每轮流式收尾后由宿主调用；两者皆无时为空操作。
     *
     * @param sinceMillis 轮次起始时刻（{@code System.currentTimeMillis()}）；该时刻之后新建或改动的
     *                    会话文件标记为「本轮新增」。交付出去的文件在聊天里有产物卡，没交付的落盘结果
     *                    只能靠工作区树看见，故给它们一个可辨认的记号。传 0 表示不标记。
     * @return 本轮新增/改动的文件数
     */
    public int refreshCurrent(long sinceMillis) {
        if (tree.getRoot() == null) {
            return 0;
        }
        this.fresh = sinceMillis > 0 ? freshOf(sessionDir, sessionDeliveryDir(), sinceMillis) : Set.of();
        int index = 0;
        if (sessionDir != null) {
            sessionItem = dirItem(new FileNode(sessionDir, true, I18n.t("chat.workspace.currentSession")));
            tree.getRoot().getChildren().set(index++, sessionItem);
            // 目录已在则自动展开：expandedProperty 监听同步懒列子项，免得用户再点一次
            if (sessionExpandable()) {
                sessionItem.setExpanded(true);
            }
        }
        if (agentItem != null) {
            agentItem = dirItem(new FileNode(agentRoot, true, I18n.t("chat.workspace.agentWorkspace")));
            tree.getRoot().getChildren().set(index++, agentItem);
            if (Files.isDirectory(agentRoot)) {
                agentItem.setExpanded(true);
            }
        }
        if (artifactsOnly) {
            // 产物视图的共享入口挂 generated/：本轮生图/交付也要就地刷新，不能等会话切换
            sharedItem = dirItem(new FileNode(generatedDir, true, I18n.t("chat.workspace.shared")));
            tree.getRoot().getChildren().set(index, sharedItem);
            if (Files.isDirectory(generatedDir)) {
                sharedItem.setExpanded(true);
            }
        }
        return fresh.size();
    }

    /** 「当前会话」入口是否值得自动展开：全量看会话目录已在；产物视图交付目录/outputs 任一已在也算。 */
    private boolean sessionExpandable() {
        if (Files.isDirectory(sessionDir)) {
            return true;
        }
        return artifactsOnly && (isDirectory(sessionDeliveryDir()) || isDirectory(sessionOutputsDir()));
    }

    /** 无轮次基线的刷新（面板展开时）：只重建入口，不清「本轮新增」标记之外的状态。 */
    public void refreshCurrent() {
        refreshCurrent(0L);
    }

    /** 收集 {@code sinceMillis} 之后落笔的会话文件（深度有限、数量有上限，纯 UI 侧提示不做全库扫描）。 */
    private static Set<Path> collectFresh(Path dir, long sinceMillis) {
        if (dir == null || !Files.isDirectory(dir)) {
            return Set.of();
        }
        List<Path> found = new ArrayList<>();
        try (var walk = Files.walk(dir, FRESH_SCAN_DEPTH)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() >= sinceMillis) {
                        found.add(p.toAbsolutePath().normalize());
                    }
                } catch (IOException | SecurityException ignored) {
                    // 单个文件取不到时间不影响整体提示
                }
            });
        } catch (IOException | SecurityException e) {
            log.debug("扫描本轮新增文件失败: {}", dir, e);
            return Set.of();
        }
        return Set.copyOf(found.size() > FRESH_MAX ? found.subList(0, FRESH_MAX) : found);
    }

    /** 「本轮新增」扫描深度与数量上限：会话目录通常一两层，再深的多是中间产物。 */
    private static final int FRESH_SCAN_DEPTH = 3;
    private static final int FRESH_MAX = 200;

    /** 本轮新增/改动的文件集合（文件树据此加标记；切换会话或无基线时为空）。 */
    private Set<Path> fresh = Set.of();

    /** 本轮新增扫描覆盖会话目录与交付目录：交付件落在 generated/sessions 下，不在会话目录内。 */
    private static Set<Path> freshOf(Path sessionDir, Path deliveryDir, long sinceMillis) {
        Set<Path> base = collectFresh(sessionDir, sinceMillis);
        Set<Path> delivered = collectFresh(deliveryDir, sinceMillis);
        if (delivered.isEmpty()) {
            return base;
        }
        Set<Path> all = new HashSet<>(base);
        all.addAll(delivered);
        return Set.copyOf(all);
    }

    /** 当前会话交付目录（与产物卡反查同口径）；无会话时 {@code null}。 */
    private Path sessionDeliveryDir() {
        return sessionId == null ? null : SessionArtifactDelivery.dirOf(deliveriesDir, sessionId);
    }

    /** 会话内 Harness 输出目录；无会话时 {@code null}。 */
    private Path sessionOutputsDir() {
        return sessionDir == null ? null : sessionDir.resolve("outputs");
    }

    /** 会话标题（历史交付目录重命名用）：解析器缺失或无标题回退会话 ID。 */
    private String sessionTitle(String sid) {
        String title = sessionTitleResolver == null ? null : sessionTitleResolver.apply(sid);
        return title == null || title.isBlank() ? sid : title;
    }

    /** 语言切换：各入口标签重取文案（子节点是文件名，与语言无关）。 */
    public void applyI18n() {
        if (sessionItem != null && sessionItem.getValue() != null) {
            sessionItem.setValue(new FileNode(sessionDir, true, I18n.t("chat.workspace.currentSession")));
        }
        if (agentItem != null && agentItem.getValue() != null) {
            agentItem.setValue(new FileNode(agentRoot, true, I18n.t("chat.workspace.agentWorkspace")));
        }
        if (sharedItem != null && sharedItem.getValue() != null) {
            sharedItem.setValue(new FileNode(sharedItem.getValue().path(), true, I18n.t("chat.workspace.shared")));
        }
        applyToolTips();
        if (tableSheets != null) {
            // 表格内的截断/空态提示是本地化文案：语言切换重渲染
            renderTable(tableSheets);
        }
        if (artifactsOnly) {
            // 产物视图子项含本地化标签（会话交付/空态提示）：整树重建顺带刷新
            rebuildTree();
        }
    }

    /** 重建整树：合成根（不渲染）下依次挂「当前会话」（若有）、「智能体工作区」（非默认智能体）与「共享工作区」。 */
    private void rebuildTree() {
        TreeItem<FileNode> syntheticRoot = new TreeItem<>();
        sessionItem = sessionDir == null ? null
                : dirItem(new FileNode(sessionDir, true, I18n.t("chat.workspace.currentSession")));
        if (sessionItem != null) {
            syntheticRoot.getChildren().add(sessionItem);
        }
        if (artifactsOnly) {
            // 产物视图：智能体工作区入口无产物语义（交付件统一落 generated/sessions），不出现
            agentItem = null;
            sharedItem = dirItem(new FileNode(generatedDir, true, I18n.t("chat.workspace.shared")));
        } else {
            // 默认智能体的工作区即共享根：无需重复入口
            agentItem = agentRoot.equals(workspaceRoot) ? null
                    : dirItem(new FileNode(agentRoot, true, I18n.t("chat.workspace.agentWorkspace")));
            if (agentItem != null) {
                syntheticRoot.getChildren().add(agentItem);
            }
            sharedItem = dirItem(new FileNode(workspaceRoot, true, I18n.t("chat.workspace.shared")));
        }
        syntheticRoot.getChildren().add(sharedItem);
        tree.setRoot(syntheticRoot);
        // 目录已在则自动展开：expandedProperty 监听同步懒列子项，免得用户再点一次
        if (sessionItem != null && sessionExpandable()) {
            sessionItem.setExpanded(true);
        }
        if (agentItem != null && Files.isDirectory(agentRoot)) {
            agentItem.setExpanded(true);
        }
        if (artifactsOnly && Files.isDirectory(generatedDir)) {
            sharedItem.setExpanded(true);
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
        previewPath = null;
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
        previewPath = target;
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
        // 表格预览走 web 渲染：主题切换时按新色板重渲染（其余阅读类型是原生控件自动跟随）
        UiTheme.addThemeListener(() -> Platform.runLater(() -> {
            if (tableSheets != null) {
                renderTable(tableSheets);
            }
        }));
    }

    private Node buildHeader() {
        crumb.getStyleClass().add("workspace-crumb");
        crumb.setMaxWidth(Region.USE_PREF_SIZE);
        crumb.setEllipsisString("\u2026");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        configureTool(backBtn, "\uE72B", "chat.workspace.back", this::closePreview);
        // GRAPHIC_ONLY：文案走菜单条目，按钮本体只留图标 + 箭头（与其余工具按钮同形）
        openSysBtn.setGraphic(UiTheme.icon("\uE8A7"));
        openSysBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        openSysBtn.getStyleClass().add("workspace-tool");
        openSysBtn.setFocusTraversable(false);
        openSysBtn.setTooltip(new javafx.scene.control.Tooltip(I18n.t("chat.workspace.openWith")));
        fillOpenWithMenu(openSysBtn, () -> previewPath);
        configureTool(filterBtn, "\uE71C", "chat.workspace.filter.all", () -> {
            artifactsOnly = !artifactsOnly;
            applyToolTips();
            rebuildTree();
        });
        configureTool(columnsBtn, "\uE8A1", "chat.workspace.columns.expand", () -> {
            threeColumn = !threeColumn;
            applyToolTips();
            applyInnerLayout();
        });
        configureTool(closeBtn, "\uE711", "chat.workspace.close", this::hide);

        HBox header = new HBox(4, backBtn, crumb, spacer, openSysBtn, filterBtn, columnsBtn, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("workspace-header");
        applyToolTips();
        refreshHeaderState();
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
        // 合成根不渲染：首层直接是「当前会话 / 智能体工作区 / 共享工作区」入口（见 rebuildTree）
        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<>());
        tree.setCellFactory(v -> new FileCell());
        // 展开懒加载触发在各目录节点的 expandedProperty 监听上（见 dirItem）：TreeItem 事件派发链
        // 只经 TreeItem 父链、不经 TreeView 控件，注册在控件上的 branchExpandedEvent 收不到事件
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
        refreshHeaderState();
    }

    /** 头部按钮显隐：返回箭头仅阅读区激活时；外部打开仅预览真实文件时（内联产物无文件可开）。 */
    private void refreshHeaderState() {
        setShown(backBtn, showingViewer);
        setShown(openSysBtn, showingViewer && previewPath != null);
    }

    private static void setShown(ButtonBase btn, boolean on) {
        btn.setVisible(on);
        btn.setManaged(on);
    }

    /** 关闭预览：单面板回文件列表、三列清阅读区为空态；清树选中保证再点同一行能重新打开。 */
    private void closePreview() {
        showingViewer = false;
        previewPath = null;
        tableSheets = null;
        crumb.setText("");
        tree.getSelectionModel().clearSelection();
        if (threeColumn) {
            renderPlaceholder(I18n.t("chat.workspace.pickFile"));
        }
        applyInnerLayout();
    }

    private static void detach(SplitPane sp, Node n) {
        sp.getItems().remove(n);
    }

    private void applyToolTips() {
        setTip(columnsBtn, threeColumn ? "chat.workspace.columns.collapse" : "chat.workspace.columns.expand");
        setTip(filterBtn, artifactsOnly ? "chat.workspace.filter.all" : "chat.workspace.filter.artifacts");
        setTip(backBtn, "chat.workspace.back");
        setTip(openSysBtn, "chat.workspace.openWith");
        // 菜单条目文案随语言重灌（复用 MenuButton 内置弹出实例，只换 items 不新建菜单）
        fillOpenWithMenu(openSysBtn, () -> previewPath);
        filterBtn.getStyleClass().remove("workspace-tool-on");
        if (artifactsOnly) {
            filterBtn.getStyleClass().add("workspace-tool-on");
        }
    }

    private void setTip(ButtonBase btn, String key) {
        if (btn.getTooltip() != null) {
            btn.getTooltip().setText(I18n.t(key));
        }
    }

    // ===================== 阅读区渲染 =====================

    private void renderPlaceholder(String text) {
        tableSheets = null;
        viewerPane.getChildren().setAll(centeredLabel(text));
    }

    private void renderPayload(Path path, PreviewPayload payload) {
        if (payload.kind() != RenderKind.TABLE) {
            tableSheets = null;
        }
        switch (payload.kind()) {
            case MARKDOWN -> renderMarkdown(payload.text());
            case HTML -> renderHtml(payload.text(), extOf(path));
            case IMAGE -> renderImage(payload.bytes());
            case TABLE -> renderTable(payload.sheets());
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
        tableSheets = null;
        if (htmlView == null) {
            htmlView = new JcefOsrView("jworkQuery", "jworkCancel", null);
        }
        viewerPane.getChildren().setAll(htmlView.node());
        htmlView.loadData(ArtifactPreviewWindow.buildHtml(code, language));
    }

    /** 表格预览：流式解析结果交 web 表格渲染（页签/吸顶表头/数字右对齐），明暗随 UiTheme。 */
    private void renderTable(List<TabularInspector.SheetTable> sheets) {
        tableSheets = sheets;
        if (htmlView == null) {
            htmlView = new JcefOsrView("jworkQuery", "jworkCancel", null);
        }
        viewerPane.getChildren().setAll(htmlView.node());
        htmlView.loadData(TablePreviewHtml.render(sheets, UiTheme.mode() == UiTheme.Mode.DARK));
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
        tableSheets = null;
        String msg = switch (kind) {
            case TOO_LARGE -> I18n.t("chat.workspace.tooLarge");
            case BINARY -> I18n.t("chat.workspace.binary");
            default -> I18n.t("chat.workspace.missing");
        };
        VBox box = new VBox(12, centeredLabel(msg));
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("workspace-empty");
        if (path != null && Files.isRegularFile(path)) {
            MenuButton open = new MenuButton(I18n.t("chat.workspace.openWith"));
            open.getStyleClass().add("workspace-open-sys");
            open.setFocusTraversable(false);
            fillOpenWithMenu(open, () -> path);
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
            return new PreviewPayload(RenderKind.MISSING, null, null, null);
        }
        RenderKind kind = classify(path.getFileName().toString());
        if (kind == RenderKind.TABLE) {
            // 表格走 SAX 流式解析（不整份读进内存），故不受文本预览字节上限约束
            List<TabularInspector.SheetTable> sheets = TabularInspector.preview(path);
            return sheets.isEmpty()
                    ? new PreviewPayload(RenderKind.MISSING, null, null, null)
                    : new PreviewPayload(RenderKind.TABLE, null, null, sheets);
        }
        try {
            long size = Files.size(path);
            long cap = kind == RenderKind.IMAGE ? MAX_IMAGE_BYTES : MAX_PREVIEW_BYTES;
            if (tooLarge(size, cap)) {
                return new PreviewPayload(RenderKind.TOO_LARGE, null, null, null);
            }
            byte[] bytes = Files.readAllBytes(path);
            if (kind == RenderKind.IMAGE) {
                return new PreviewPayload(RenderKind.IMAGE, null, bytes, null);
            }
            if (kind == RenderKind.DOC) {
                String text = FileTextExtractor.extract(path.getFileName().toString(), null, bytes);
                return new PreviewPayload(RenderKind.DOC, text, null, null);
            }
            if (looksBinary(bytes)) {
                return new PreviewPayload(RenderKind.BINARY, null, null, null);
            }
            return new PreviewPayload(kind, new String(bytes, StandardCharsets.UTF_8), null, null);
        } catch (IOException e) {
            log.warn("读取预览文件失败: {}", path, e);
            return new PreviewPayload(RenderKind.MISSING, null, null, null);
        }
    }

    private void openWithSystem(Path path) {
        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception e) {
            log.warn("系统程序打开失败: {}", path, e);
        }
    }

    /** 外部打开菜单条目：默认关联 / 系统「打开方式」选择器；路径取点击时的实时值（头部预览可切换）。 */
    private void fillOpenWithMenu(MenuButton menu, java.util.function.Supplier<Path> path) {
        MenuItem def = new MenuItem(I18n.t("chat.workspace.openWith.default"), UiTheme.icon("\uE8A7"));
        def.setOnAction(e -> {
            Path p = path.get();
            if (p != null) {
                openWithSystem(p);
            }
        });
        MenuItem choose = new MenuItem(I18n.t("chat.workspace.openWith.choose"), UiTheme.icon("\uE8D7"));
        choose.setOnAction(e -> {
            Path p = path.get();
            if (p != null) {
                openWithChooser(p);
            }
        });
        menu.getItems().setAll(def, choose);
    }

    /** 「选择其他程序」：Windows shell 的「打开方式」对话框；非 Windows 或拉起失败降级默认关联。 */
    private void openWithChooser(Path path) {
        List<String> cmd = chooserCommand(path, OsPlatform.current());
        if (cmd == null) {
            openWithSystem(path);
            return;
        }
        try {
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            log.warn("打开方式对话框拉起失败: {}", path, e);
            openWithSystem(path);
        }
    }

    /** 纯函数：Windows「打开方式」选择器命令（rundll32 OpenAs_RunDLL）；非 Windows 返回 null。包私有便于单测。 */
    static List<String> chooserCommand(Path path, OsPlatform os) {
        if (path == null || os != OsPlatform.WINDOWS) {
            return null;
        }
        return List.of("rundll32.exe", "shell32.dll,OpenAs_RunDLL", path.toAbsolutePath().toString());
    }

    // ===================== 纯函数（可单测，不触 FX） =====================

    /** 按扩展名分派阅读类型；未知一律按纯文本尝试（二进制在读取阶段兜底）。包私有便于单测。 */
    static RenderKind classify(String fileName) {
        if (FileKinds.isMarkdown(fileName)) {
            return RenderKind.MARKDOWN;
        }
        if (ArtifactPreviewWindow.isPreviewableFile(fileName)) {
            return RenderKind.HTML;
        }
        if (FileKinds.isImage(fileName)) {
            return RenderKind.IMAGE;
        }
        if (FileKinds.isBinaryDocument(fileName)) {
            return RenderKind.DOC;
        }
        if (TabularInspector.isTabular(fileName)) {
            return RenderKind.TABLE;
        }
        return RenderKind.TEXT;
    }

    /**
     * 目标是否落在工作区根内。
     * <p>注释里原先写着「杜绝符号链接越界」，实现却只做字符串归一：{@code workspace\link} 指向根外时
     * 前缀比较一路放过。现在走 {@link CanonicalPath}，逐级跟随链接后再比。</p>
     */
    static boolean isWithin(Path root, Path target) {
        return CanonicalPath.within(target, root);
    }

    /** 会话专属工作区 = {@code <root>/<sessionId>}；空会话 ID 或归一化后越出根（含分隔符/..）返回 {@code null}。 */
    static Path sessionDirOf(Path root, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Path dir = root.resolve(sessionId).toAbsolutePath().normalize();
        return isWithin(root, dir) ? dir : null;
    }

    /** 智能体工作区根归一化：候选为空或越出共享根（运行时解析恒在共享根内，此处防御非法值）时回退共享根。 */
    static Path agentRootOf(Path sharedRoot, Path candidate) {
        if (candidate == null) {
            return sharedRoot;
        }
        Path root = candidate.toAbsolutePath().normalize();
        return isWithin(sharedRoot, root) ? root : sharedRoot;
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

    /** 面包屑：「当前会话 › 相对子路径」/「智能体工作区 › …」/「共享工作区 › …」，按目标所在层取头。 */
    private String crumbFor(Path target) {
        Path base = workspaceRoot;
        String head = I18n.t("chat.workspace.shared");
        if (artifactsOnly) {
            // 产物视图：交付件读作「当前会话 › 文件名」，不再露 generated/sessions/UUID 一串内部路径
            Path cur = sessionDeliveryDir();
            if (cur != null && isWithin(cur, target)) {
                base = cur;
                head = I18n.t("chat.workspace.currentSession");
            } else if (isWithin(deliveriesDir, target)) {
                base = deliveriesDir;
                head = I18n.t("chat.workspace.shared") + " \u203A " + I18n.t("chat.workspace.deliveries");
            } else if (sessionDir != null && isWithin(sessionDir, target)) {
                base = sessionDir;
                head = I18n.t("chat.workspace.currentSession");
            } else if (isWithin(generatedDir, target)) {
                base = generatedDir;
                head = I18n.t("chat.workspace.shared");
            }
        } else if (sessionDir != null && isWithin(sessionDir, target)) {
            base = sessionDir;
            head = I18n.t("chat.workspace.currentSession");
        } else if (!agentRoot.equals(workspaceRoot) && isWithin(agentRoot, target)) {
            base = agentRoot;
            head = I18n.t("chat.workspace.agentWorkspace");
        }
        List<String> parts = new ArrayList<>();
        parts.add(head);
        Path rel = base.relativize(target);
        boolean inSessions = false;
        for (Path seg : rel) {
            String name = seg.toString();
            // 与树同口径：交付目录段重命名为会话标题、generated 下的 sessions 段用本地化标签
            if (artifactsOnly && base.equals(generatedDir)) {
                if (!inSessions && "sessions".equals(name)) {
                    name = I18n.t("chat.workspace.deliveries");
                    inSessions = true;
                } else if (inSessions) {
                    name = sessionTitle(name);
                    inSessions = false;
                }
            } else if (artifactsOnly && base.equals(deliveriesDir) && parts.size() == 1) {
                name = sessionTitle(name);
            }
            parts.add(name);
        }
        return String.join(" \u203A ", parts);
    }

    // ===================== 文件树数据与单元 =====================

    /** 树节点值：路径 + 是否目录 + 显示名覆写（可空，用于「当前会话/智能体工作区/共享工作区」入口）。 */
    record FileNode(Path path, boolean directory, String label) {
        FileNode(Path path, boolean directory) {
            this(path, directory, null);
        }

        /** 单元格显示名：覆写优先，否则取路径末段。 */
        String displayName() {
            return label != null ? label : path.getFileName().toString();
        }
    }

    /** 读盘结果载荷：类型 + 文本（md/html/text/doc）+ 字节（image）+ 工作表（table）。 */
    private record PreviewPayload(RenderKind kind, String text, byte[] bytes,
                                  List<TabularInspector.SheetTable> sheets) {
    }

    /** 目录节点：挂一个空值占位子项以出现展开箭头，真正子项在展开时懒列。 */
    private TreeItem<FileNode> dirItem(Path p) {
        return dirItem(new FileNode(p, true));
    }

    /** 目录节点（带显示名覆写）：「当前会话/智能体工作区/共享工作区」入口节点复用。 */
    private TreeItem<FileNode> dirItem(FileNode node) {
        TreeItem<FileNode> item = new TreeItem<>(node);
        item.getChildren().add(new TreeItem<>());
        // 展开属性变化即懒列（程序化自动展开与点击展开都走属性变化，不依赖 TreeItem 事件派发）
        item.expandedProperty().addListener((obs, was, is) -> {
            if (Boolean.TRUE.equals(is)) {
                onExpand(item);
            }
        });
        return item;
    }

    /** 展开目录时把占位子项替换为真实列表（仅首次，子项仍为占位则再懒列；已加载目录的陈旧内容由入口重建刷新）。 */
    private void onExpand(TreeItem<FileNode> item) {
        if (item == null || item.getValue() == null || !item.getValue().directory()) {
            return;
        }
        ObservableList<TreeItem<FileNode>> children = item.getChildren();
        boolean notLoaded = children.size() == 1 && children.get(0).getValue() == null;
        if (!notLoaded) {
            return;
        }
        if (artifactsOnly && item == sessionItem) {
            children.setAll(sessionArtifactItems());
            return;
        }
        children.setAll(listChildren(item.getValue().path()));
    }

    /** 产物视图「当前会话」子项：交付件 + outputs 合并拍平；皆空给一行空态提示（无图标不可点）。 */
    private ObservableList<TreeItem<FileNode>> sessionArtifactItems() {
        List<Path> entries = sessionArtifactPaths(sessionDeliveryDir(), sessionOutputsDir(), workspaceRoot);
        ObservableList<TreeItem<FileNode>> list = FXCollections.observableArrayList();
        if (entries.isEmpty()) {
            list.add(new TreeItem<>(new FileNode(null, true, I18n.t("chat.workspace.noArtifacts"))));
            return list;
        }
        for (Path p : entries) {
            list.add(isDirectory(p) ? dirItem(p) : new TreeItem<>(new FileNode(p, false)));
        }
        return list;
    }

    /** 列一个目录的直接子项：过滤工作区外、目录优先、按名排序；产物视图附加交付目录去重/重命名。 */
    private ObservableList<TreeItem<FileNode>> listChildren(Path dir) {
        List<Path> entries = childPathsOf(dir, workspaceRoot);
        sortEntries(entries);
        ObservableList<TreeItem<FileNode>> list = FXCollections.observableArrayList();
        for (Path p : entries) {
            boolean directory = isDirectory(p);
            String label = null;
            if (artifactsOnly && directory && dir.equals(deliveriesDir)) {
                label = deliveryLabel(p, sessionDeliveryDir(), this::sessionTitle);
                if (label == null) {
                    continue; // 当前会话交付件已提顶到「当前会话」入口，共享区去重
                }
            } else if (artifactsOnly && directory && dir.equals(generatedDir)
                    && "sessions".equals(p.getFileName().toString())) {
                label = I18n.t("chat.workspace.deliveries");
            }
            if (!directory) {
                list.add(new TreeItem<>(new FileNode(p, false)));
            } else {
                list.add(label == null ? dirItem(p) : dirItem(new FileNode(p, true, label)));
            }
        }
        return list;
    }

    /** 目录直接子项（工作区内、未排序）：不可读/不存在返回空表，不抛出。 */
    static List<Path> childPathsOf(Path dir, Path root) {
        List<Path> entries = new ArrayList<>();
        if (!isDirectory(dir)) {
            return entries;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                Path abs = p.toAbsolutePath().normalize();
                if (isWithin(root, abs)) {
                    entries.add(abs);
                }
            }
        } catch (IOException | SecurityException e) {
            // 目录不可读：返回空列表（展开时显示无子项），不抛出
            log.debug("列目录失败: {}", dir, e);
        }
        return entries;
    }

    /** 目录优先、按名忽略大小写排序（就地）。 */
    static void sortEntries(List<Path> entries) {
        entries.sort(Comparator.comparing((Path p) -> !isDirectory(p))
                .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
    }

    /** 产物视图「当前会话」子项来源：交付目录 + outputs 的直接子项合并排序；目录缺失贡献空表。 */
    static List<Path> sessionArtifactPaths(Path deliveryDir, Path outputsDir, Path root) {
        List<Path> all = childPathsOf(deliveryDir, root);
        all.addAll(childPathsOf(outputsDir, root));
        sortEntries(all);
        return all;
    }

    /** 历史交付目录显示名：当前会话返回 {@code null}（调用方去重），其余会话标题优先、无标题回退目录名。 */
    static String deliveryLabel(Path dir, Path currentDeliveryDir, java.util.function.Function<String, String> titles) {
        if (dir.equals(currentDeliveryDir)) {
            return null;
        }
        String name = dir.getFileName().toString();
        String title = titles == null ? null : titles.apply(name);
        return title == null || title.isBlank() ? name : title;
    }

    private static boolean isDirectory(Path p) {
        try {
            return p != null && Files.isDirectory(p);
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
                getStyleClass().remove(HINT_CELL_CLASS);
                return;
            }
            getStyleClass().remove(HINT_CELL_CLASS);
            if (item.path() == null) {
                // 空态提示行：无图标灰字；目录标记防选中触发 openFile，样式去掉悬停/选中底色
                setGraphic(null);
                setText(item.displayName());
                getStyleClass().add(HINT_CELL_CLASS);
                return;
            }
            Label icon = new Label(item.directory() ? "\uE8B7" : "\uE7C3");
            icon.getStyleClass().add("workspace-tree-icon");
            setGraphic(icon);
            setText(freshMark(item));
        }

        /** 本轮新增/改动的文件带上「本轮新增」尾巴：交付件在聊天里有产物卡，未交付的落盘只能靠文件树辨认。 */
        private String freshMark(FileNode item) {
            String name = item.displayName();
            if (item.directory() || !fresh.contains(item.path().toAbsolutePath().normalize())) {
                return name;
            }
            return name + " · " + I18n.t("chat.workspace.fresh");
        }
    }
}
