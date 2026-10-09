package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.core.chat.FileAttachment;
import com.gettyio.gim.agent.core.chat.ImageAttachment;
import com.gettyio.gim.agent.core.chat.StoredAttachment;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.window.GimDialog;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 输入框附件条：待发的图片（Base64 多模态）与文件/文件夹引用（路径引用块）的采集、芯片渲染与清理，
 * 以及回形针菜单（文件 / 文件夹）的弹出。由 {@code MainController} 在 FXML 注入后构造并委托。
 */
public class AttachmentBar {

    private static final Logger log = LoggerFactory.getLogger(AttachmentBar.class);

    /** 单张附件图片的软上限：超过则不加入待发列表（8MB） */
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    /** 文件夹附件目录树的最大展开深度 */
    private static final int MAX_TREE_DEPTH = 3;
    /** 文件夹附件目录树最多列出的条目数：避免超大目录撑爆上下文 */
    private static final int MAX_TREE_ENTRIES = 300;
    /** 「文件」附件可选的文本/代码类扩展名（对齐 Qoder：喂给模型的是文本上下文，不含 PDF/Office 二进制） */
    private static final String[] CODE_EXTENSIONS = {
            "*.java", "*.kt", "*.groovy", "*.scala", "*.py", "*.js", "*.jsx", "*.ts", "*.tsx", "*.vue",
            "*.html", "*.htm", "*.css", "*.scss", "*.less", "*.c", "*.h", "*.cpp", "*.hpp", "*.cc",
            "*.cs", "*.go", "*.rs", "*.rb", "*.php", "*.swift", "*.m", "*.mm", "*.sql", "*.sh",
            "*.bat", "*.ps1", "*.cmd", "*.json", "*.xml", "*.yml", "*.yaml", "*.toml", "*.ini",
            "*.properties", "*.md", "*.markdown", "*.txt", "*.csv", "*.log", "*.rst"
    };

    /** 文档附件（服务端抽取正文喂模型）可选扩展名。 */
    private static final String[] DOC_EXTENSIONS = {
            "*.pdf", "*.docx", "*.xlsx", "*.txt", "*.md", "*.markdown", "*.csv"
    };
    /** 单个文档附件字节上限（10MB）：与 ChatService 口径一致。 */
    private static final long MAX_DOC_BYTES = 10L * 1024 * 1024;

    private final FlowPane attachChipsBox;
    private final TextArea inputField;
    private final Button attachButton;
    private final GimPaths gimPaths;
    private final ChatHost host;

    /** 待发送的图片附件（与 attachChipsBox 中的图片芯片一一对应） */
    private final List<ImageAttachment> pendingImages = new ArrayList<>();
    /** 待发送的文件/文件夹引用（与 attachChipsBox 中的引用芯片一一对应）；发送时拼进正文 */
    private final List<FileRef> pendingRefs = new ArrayList<>();
    /** 待发送的文档附件（pdf/docx/xlsx/txt/md/csv）；发送时交服务端抽取正文并落盘原件 */
    private final List<FileAttachment> pendingDocs = new ArrayList<>();
    /** 附件选择菜单：复用单一实例，避免每次 new 导致旧弹出窗阴影/落点累积漂移 */
    private ContextMenu attachMenu;
    /** 附件菜单最近一次关闭的时间戳（毫秒）：把「再点按钮」识别为关闭手势 */
    private long attachMenuHiddenAt;

    public AttachmentBar(FlowPane attachChipsBox, TextArea inputField, Button attachButton,
                         GimPaths gimPaths, ChatHost host) {
        this.attachChipsBox = attachChipsBox;
        this.inputField = inputField;
        this.attachButton = attachButton;
        this.gimPaths = gimPaths;
        this.host = host;
    }

    // ===================== 对外状态访问（供控制器发送流程读取） =====================

    public boolean hasImages() {
        return !pendingImages.isEmpty();
    }

    public boolean hasRefs() {
        return !pendingRefs.isEmpty();
    }

    /** 是否有待发文档附件。 */
    public boolean hasDocs() {
        return !pendingDocs.isEmpty();
    }

    /** 当前待发文档附件的快照（发送时取一份不可变副本，交给 {@code ChatRequest.files}）。 */
    public List<FileAttachment> snapshotDocs() {
        return List.copyOf(pendingDocs);
    }

    /** 当前待发文档的“预览引用”（路径未定，仅供上屏即时渲染文件行；完成后从库重放补齐真实路径）。 */
    public List<StoredAttachment> previewDocRefs() {
        List<StoredAttachment> out = new ArrayList<>();
        for (FileAttachment d : pendingDocs) {
            out.add(new StoredAttachment(d.fileName(), d.mediaType(), null,
                    StoredAttachment.KIND_FILE, d.sizeBytes()));
        }
        return out;
    }

    /** 当前待发图片的快照（发送时取一份不可变副本）。 */
    public List<ImageAttachment> snapshotImages() {
        return List.copyOf(pendingImages);
    }

    /** 把当前待发文件/文件夹引用渲染成发给模型的引用块文本。 */
    public String renderRefBlock() {
        return renderRefBlock(List.copyOf(pendingRefs));
    }

    /** 发送后清空待发附件与芯片行。 */
    public void clearPending() {
        pendingImages.clear();
        pendingRefs.clear();
        pendingDocs.clear();
        attachChipsBox.getChildren().clear();
    }

    /**
     * 注入一张待发图片（截图提问用）：Base64 后加入待发列表并上屏芯片，与手动贴图同一条通道。
     * 超限或空字节则提示并忽略。
     */
    public void addImage(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            GimDialog.message(host.currentWindow(), I18n.t("input.attach"), I18n.t("input.attach"),
                    I18n.t("attach.tooLarge", fileName));
            return;
        }
        ImageAttachment att = new ImageAttachment(fileName, "image/png",
                Base64.getEncoder().encodeToString(bytes));
        pendingImages.add(att);
        attachChipsBox.getChildren().add(buildChip(att, bytes));
    }

    // ===================== 回形针菜单与选择 =====================

    /**
     * 回形针按钮：弹出「文件 / 文件夹 / 生成图片」菜单。
     * <p>图片不单列——它本就是文件的一种：在「文件」选择器里选中图片仍走 Base64 多模态通道，
     * 选中其它文件则只记路径引用，发送时拼进正文由 Agent 用文件工具按需读取；「生成图片」
     * 则是 AI 生图入口，交回宿主（{@link ChatHost#requestImageGeneration()}）编排。</p>
     */
    public void handleAttach() {
        if (attachMenu == null) {
            attachMenu = new ContextMenu();
            attachMenu.setOnHiding(e -> attachMenuHiddenAt = System.currentTimeMillis());
        }
        if (attachMenu.isShowing()) {
            attachMenu.hide();   // 已弹出再点按钮：收起，做成开关切换
            return;
        }
        // 弹窗在本次按钮点击前已被系统自动收起：视为「关闭」手势，不要立刻重开
        if (MenuPopup.inHideDebounce(attachMenuHiddenAt)) {
            return;
        }
        // 每次重灌条目：保证文案随语言即时刷新
        attachMenu.getItems().setAll(
                attachMenuItem(I18n.t("attach.menu.file"), "\uE8A5", this::chooseFiles),
                attachMenuItem(I18n.t("attach.menu.folder"), "\uE8B7", this::chooseFolder),
                attachMenuItem(I18n.t("attach.menu.generate"), "\uE91B", host::requestImageGeneration));
        MenuPopup.showAbove(attachMenu, attachButton);
    }

    /** 附件菜单条目：图标 + 文案，点击执行对应选择动作。 */
    private static MenuItem attachMenuItem(String text, String glyph, Runnable action) {
        MenuItem item = new MenuItem(text, UiTheme.icon(glyph));
        item.setOnAction(e -> action.run());
        return item;
    }

    /**
     * 多选文件：默认「所有文件」（图片与其它文件都能选），另附「图片」「代码/文本」过滤项。
     * <p>按扩展名分流：图片走 {@link #addPendingImage}（Base64→多模态），其它文件走 {@link FileRef}
     * 路径引用（发送时拼进正文，由 Agent 用文件工具读取）。</p>
     */
    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("attach.menu.file"));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.t("attach.filter.all"), "*.*", "*"),
                new FileChooser.ExtensionFilter(I18n.t("attach.filter"), "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"),
                new FileChooser.ExtensionFilter(I18n.t("attach.filter.doc"), DOC_EXTENSIONS),
                new FileChooser.ExtensionFilter(I18n.t("attach.filter.code"), CODE_EXTENSIONS));
        List<File> files = chooser.showOpenMultipleDialog(host.currentWindow());
        if (files == null || files.isEmpty()) {
            return;
        }
        for (File f : files) {
            if (isImage(f.getName())) {
                addPendingImage(f);
            } else if (isDoc(f.getName())) {
                addPendingDoc(f);
            } else {
                addPendingRef(new FileRef("file", f.getName(), f.getAbsolutePath(), null));
            }
        }
        inputField.requestFocus();
    }

    /** 按扩展名判定是否为可抽取正文的文档。 */
    private static boolean isDoc(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".pdf") || n.endsWith(".docx") || n.endsWith(".xlsx")
                || n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".markdown") || n.endsWith(".csv");
    }

    /** 加入一个待发文档附件（读字节 Base64）；超 10MB 拒绝并提示。 */
    private void addPendingDoc(File file) {
        if (file.length() > MAX_DOC_BYTES) {
            GimDialog.message(host.currentWindow(), I18n.t("attach.menu.file"), I18n.t("attach.menu.file"),
                    I18n.t("attach.doc.tooLarge", file.getName()));
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            FileAttachment att = new FileAttachment(file.getName(), docMimeOf(file.getName()),
                    Base64.getEncoder().encodeToString(bytes), bytes.length);
            pendingDocs.add(att);
            attachChipsBox.getChildren().add(buildDocChip(att));
        } catch (IOException e) {
            log.warn("读取文档失败: {}", file, e);
        }
    }

    /** 文档芯片：类型图标 + 名称（截断）+ 大小 + 右上角移除标记。 */
    private HBox buildDocChip(FileAttachment att) {
        Label icon = UiTheme.icon("\uE8A5");
        icon.getStyleClass().add("approval-icon");
        Label name = new Label(att.fileName());
        name.getStyleClass().add("attach-ref-name");
        name.setMaxWidth(160);
        name.setEllipsisString("\u2026");
        name.setTooltip(new Tooltip(att.fileName()));
        Label size = new Label(humanSize(att.sizeBytes()));
        size.getStyleClass().add("attach-doc-size");
        Label remove = new Label("\u00D7");
        remove.getStyleClass().add("attach-remove");
        HBox chip = new HBox(4, icon, name, size, remove);
        chip.getStyleClass().add("attach-chip");
        chip.setAlignment(Pos.CENTER);
        remove.setOnMouseClicked(e -> {
            pendingDocs.remove(att);
            attachChipsBox.getChildren().remove(chip);
        });
        // 右键：将本文档存入当前智能体知识库（长期入库，区别于本轮临时抽取）
        MenuItem toKnowledge = new MenuItem(I18n.t("attach.menu.knowledge"));
        toKnowledge.setOnAction(e -> host.importAttachmentToKnowledge(att));
        ContextMenu docMenu = new ContextMenu(toKnowledge);
        chip.setOnContextMenuRequested(e -> docMenu.show(chip, e.getScreenX(), e.getScreenY()));
        return chip;
    }

    /**
     * 把一组 kind=file 的落盘附件渲染为消息气泡内的“文件行”（图标 + 名 + 大小，点击打开所在目录）。
     * 发送即时预览（路径未定）与历史重放（真实路径）共用；图片类传入将被忽略。
     */
    public List<Node> buildFileRows(List<StoredAttachment> attachments) {
        List<Node> rows = new ArrayList<>();
        for (StoredAttachment a : attachments) {
            if (a == null || !a.isFile()) {
                continue;
            }
            rows.add(buildFileRow(a));
        }
        return rows;
    }

    private Node buildFileRow(StoredAttachment a) {
        Label icon = UiTheme.icon("\uE8A5");
        icon.getStyleClass().add("file-row-icon");
        Label name = new Label(a.name());
        name.getStyleClass().add("file-row-name");
        name.setMaxWidth(260);
        name.setEllipsisString("\u2026");
        Label size = new Label(a.sizeBytes() > 0 ? humanSize(a.sizeBytes()) : "");
        size.getStyleClass().add("file-row-size");
        HBox row = new HBox(6, icon, name, size);
        row.getStyleClass().add("file-row");
        row.setAlignment(Pos.CENTER_LEFT);
        if (a.path() != null && !a.path().isBlank()) {
            Path abs = gimPaths.attachmentsDir().resolve(a.path());
            Tooltip.install(row, new Tooltip(I18n.t("attach.file.reveal")));
            row.setOnMouseClicked(e -> revealInFolder(abs));
        }
        return row;
    }

    /** 在系统文件管理器中打开附件所在目录（原件已清理时回退到附件根目录）。 */
    private void revealInFolder(Path file) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Path dir = Files.exists(file) ? file.getParent() : gimPaths.attachmentsDir();
                if (dir != null) {
                    Desktop.getDesktop().open(dir.toFile());
                }
            }
        } catch (Exception e) {
            log.warn("打开附件目录失败: {}", file, e);
        }
    }

    /** 字节数转人类可读大小。 */
    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
    }

    /** 文档扩展名→MIME，未知回退 application/octet-stream。 */
    private static String docMimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (n.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (n.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (n.endsWith(".csv")) {
            return "text/csv";
        }
        if (n.endsWith(".md") || n.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (n.endsWith(".txt")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    /** 按扩展名判定是否为图片（与 {@link #mimeOf} 支持的格式一致）。 */
    private static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".webp");
    }

    /** 选择单个文件夹：采集目录树文本 + 根路径引用加入待发。 */
    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.t("attach.menu.folder"));
        File dir = chooser.showDialog(host.currentWindow());
        if (dir == null) {
            return;
        }
        addPendingRef(new FileRef("folder", dir.getName(), dir.getAbsolutePath(), buildTree(dir.toPath())));
        inputField.requestFocus();
    }

    /** 加入一个文件/文件夹引用并渲染对应芯片。 */
    private void addPendingRef(FileRef ref) {
        pendingRefs.add(ref);
        attachChipsBox.getChildren().add(buildRefChip(ref));
    }

    /** 引用芯片：类型图标 + 名称（截断，悬停看全路径）+ 右上角移除标记。 */
    private HBox buildRefChip(FileRef ref) {
        Label icon = UiTheme.icon("folder".equals(ref.kind()) ? "\uE8B7" : "\uE8A5");
        icon.getStyleClass().add("approval-icon");
        Label name = new Label(ref.name());
        name.getStyleClass().add("attach-ref-name");
        name.setMaxWidth(160);
        name.setEllipsisString("\u2026");
        name.setTooltip(new Tooltip(ref.path()));
        Label remove = new Label("\u00D7");
        remove.getStyleClass().add("attach-remove");
        HBox chip = new HBox(4, icon, name, remove);
        chip.getStyleClass().add("attach-chip");
        chip.setAlignment(Pos.CENTER);
        remove.setOnMouseClicked(e -> {
            pendingRefs.remove(ref);
            attachChipsBox.getChildren().remove(chip);
        });
        return chip;
    }

    /**
     * 生成发给模型的引用块文本：逐条列出文件绝对路径 / 文件夹根路径与目录树，并提示 Agent 用文件工具读取。
     * <p>不内联文件内容，只给路径——对齐「路径引用 + 工具读取」设计，也能撑住大文件与整个目录。</p>
     */
    private String renderRefBlock(List<FileRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.t("attach.ref.header")).append('\n');
        for (FileRef r : refs) {
            if ("folder".equals(r.kind())) {
                sb.append(I18n.t("attach.ref.folder")).append(": ").append(r.path()).append('\n');
                if (r.tree() != null && !r.tree().isBlank()) {
                    sb.append(I18n.t("attach.ref.tree")).append(":\n").append(r.tree()).append('\n');
                }
            } else {
                sb.append(I18n.t("attach.ref.file")).append(": ").append(r.path()).append('\n');
            }
        }
        sb.append(I18n.t("attach.ref.hint"));
        return sb.toString();
    }

    /** 受限深度/条目数地枚举目录树，返回缩进文本（根目录自身不列出）。 */
    private String buildTree(Path root) {
        StringBuilder sb = new StringBuilder();
        try (var stream = Files.walk(root, MAX_TREE_DEPTH)) {
            stream.filter(p -> !p.equals(root))
                    .sorted()
                    .limit(MAX_TREE_ENTRIES)
                    .forEach(p -> {
                        int depth = root.relativize(p).getNameCount() - 1;
                        if (depth < 0) {
                            depth = 0;
                        }
                        String indent = "  ".repeat(depth);
                        String name = p.getFileName().toString() + (Files.isDirectory(p) ? "/" : "");
                        sb.append(indent).append(name).append('\n');
                    });
        } catch (IOException | RuntimeException e) {
            log.warn("枚举目录树失败: {}", root, e);
        }
        return sb.toString().trim();
    }

    private void addPendingImage(File file) {
        if (file.length() > MAX_IMAGE_BYTES) {
            GimDialog.message(host.currentWindow(), I18n.t("input.attach"), I18n.t("input.attach"),
                    I18n.t("attach.tooLarge", file.getName()));
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            ImageAttachment att = new ImageAttachment(file.getName(), mimeOf(file.getName()),
                    Base64.getEncoder().encodeToString(bytes));
            pendingImages.add(att);
            attachChipsBox.getChildren().add(buildChip(att, bytes));
        } catch (IOException e) {
            log.warn("读取图片失败: {}", file, e);
        }
    }

    /** 待发附件芯片：小缩略图 + 右上角移除标记；点标记即从待发列表与芯片行同时移除。 */
    private HBox buildChip(ImageAttachment att, byte[] bytes) {
        ImageView iv = thumbView(bytes);
        iv.setFitWidth(48);
        iv.setFitHeight(48);
        Label remove = new Label("\u00D7");
        remove.getStyleClass().add("attach-remove");
        HBox chip = new HBox(2, iv, remove);
        chip.getStyleClass().add("attach-chip");
        chip.setAlignment(Pos.CENTER);
        remove.setOnMouseClicked(e -> {
            pendingImages.remove(att);
            attachChipsBox.getChildren().remove(chip);
        });
        return chip;
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        if (n.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    // ===================== 缩略图工具（发送气泡与历史重放共用） =====================

    public List<ImageView> thumbsFromBase64(List<ImageAttachment> images) {
        List<ImageView> out = new ArrayList<>();
        for (ImageAttachment img : images) {
            if (img == null || img.base64Data() == null) {
                continue;
            }
            try {
                out.add(thumbView(Base64.getDecoder().decode(img.base64Data())));
            } catch (IllegalArgumentException e) {
                log.warn("附件 Base64 解码失败，跳过缩略图: {}", img.fileName());
            }
        }
        return out;
    }

    public List<ImageView> thumbsFromStored(List<StoredAttachment> atts) {
        List<ImageView> out = new ArrayList<>();
        for (StoredAttachment a : atts) {
            ImageView v = thumbFromPath(a.path());
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    private static ImageView thumbView(byte[] bytes) {
        ImageView iv = new ImageView(new Image(new ByteArrayInputStream(bytes)));
        iv.setFitWidth(140);
        iv.setFitHeight(140);
        iv.setPreserveRatio(true);
        iv.getStyleClass().add("attach-thumb");
        return iv;
    }

    private ImageView thumbFromPath(String rel) {
        if (rel == null || rel.isBlank()) {
            return null;
        }
        try {
            Path p = gimPaths.attachmentsDir().resolve(rel);
            if (!Files.exists(p)) {
                return null;
            }
            return thumbView(Files.readAllBytes(p));
        } catch (IOException | RuntimeException e) {
            log.warn("读取附件缩略图失败: {}", rel, e);
            return null;
        }
    }

    /** 待发文件/文件夹引用：发送时拼成正文引用块由 Agent 用文件工具读取，UI 侧渲染为图标+名称芯片。 */
    private record FileRef(String kind, String name, String path, String tree) {
    }
}
