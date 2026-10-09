package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.ui.I18n;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 把一段 Markdown 文本渲染成 JavaFX 节点列表（供助手气泡与历史重放、导出复用）。
 * <p>覆盖：标题、段落（粗体/斜体/行内代码/链接）、有序/无序列表、围栏代码块（只读 + 复制按钮）、
 * 引用块、分隔线、GFM 表格。样式类挂在各节点上，由 {@code gim-agent.css} / {@code gim-agent-dark.css}
 * 明暗两套提供，本类不含任何颜色常量。解析失败或空文本返回空列表，交回上层按纯文本兜底。</p>
 * <p>约定：commonmark 的 {@link Node}/{@link Text} 用简单名（AST 遍历），JavaFX 的节点类型一律全限定，
 * 以免 {@code Node}/{@code Text} 两处同名歧义。</p>
 */
public final class MarkdownRenderer {

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    private MarkdownRenderer() {
    }

    /** 渲染入口：返回一组块级节点；markdown 为空时返回空列表。 */
    public static List<javafx.scene.Node> render(String markdown) {
        List<javafx.scene.Node> out = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return out;
        }
        Node doc = PARSER.parse(markdown);
        for (Node child = doc.getFirstChild(); child != null; child = child.getNext()) {
            out.addAll(renderBlock(child));
        }
        return out;
    }

    private static List<javafx.scene.Node> renderBlock(Node node) {
        List<javafx.scene.Node> out = new ArrayList<>();
        if (node instanceof Heading h) {
            Label label = new Label(plainInline(h));
            label.setWrapText(true);
            label.getStyleClass().add("md-h" + Math.min(Math.max(h.getLevel(), 1), 4));
            out.add(label);
        } else if (node instanceof Paragraph p) {
            out.addAll(paragraph(p));
        } else if (node instanceof FencedCodeBlock f) {
            String info = f.getInfo() == null ? "" : f.getInfo().trim();
            String literal = f.getLiteral() == null ? "" : f.getLiteral().stripTrailing();
            if ("mermaid".equalsIgnoreCase(info)) {
                // 图表源码交 Mermaid 离线渲染；引擎缺失（资源未打包）时回退普通代码块
                MermaidView mermaid = MermaidView.tryCreate(literal);
                if (mermaid != null) {
                    out.add(mermaid.node());
                    return out;
                }
            }
            out.add(codeBlock(literal, info));
        } else if (node instanceof IndentedCodeBlock ic) {
            out.add(codeBlock(ic.getLiteral() == null ? "" : ic.getLiteral().stripTrailing(), null));
        } else if (node instanceof ListBlock list) {
            out.add(listBlock(list));
        } else if (node instanceof BlockQuote bq) {
            VBox box = new VBox(6);
            box.getStyleClass().add("md-quote");
            for (Node c = bq.getFirstChild(); c != null; c = c.getNext()) {
                box.getChildren().addAll(renderBlock(c));
            }
            out.add(box);
        } else if (node instanceof ThematicBreak) {
            Separator sep = new Separator();
            sep.getStyleClass().add("md-rule");
            out.add(sep);
        } else if (node instanceof org.commonmark.ext.gfm.tables.TableBlock table) {
            out.add(tableNode(table));
        } else if (node instanceof HtmlBlock html) {
            // 不解析内嵌 HTML，按纯文本呈现，避免注入未定义节点
            javafx.scene.text.TextFlow flow =
                    new javafx.scene.text.TextFlow(mdText(html.getLiteral() == null ? "" : html.getLiteral()));
            flow.getStyleClass().add("md-p");
            out.add(flow);
        }
        return out;
    }

    // ===================== 本地图片（AI 生成图 / 绝对路径引用） =====================

    /** 气泡内本地图片的最大展示宽度（px）：超宽图按比例缩小，小图不放大。 */
    private static final double MAX_IMAGE_WIDTH = 420;

    /**
     * 段落：含「本地存在的图片」子节点时按顺序拆成 TextFlow 与图片块交替；否则单 TextFlow 原样返回。
     * <p>仅当图片路径可解析为本地真实文件时才拆出独立图片块——远程 URL、相对路径与不存在的文件
     * 继续走 {@link #appendInline} 的 alt 文本兑底，避免加载任意网络资源。</p>
     */
    private static List<javafx.scene.Node> paragraph(Paragraph p) {
        if (!hasLocalImage(p)) {
            return List.of(textFlow(inlineNodes(p)));
        }
        List<javafx.scene.Node> out = new ArrayList<>();
        List<javafx.scene.Node> inline = new ArrayList<>();
        for (Node c = p.getFirstChild(); c != null; c = c.getNext()) {
            javafx.scene.Node image = c instanceof Image img ? imageBlock(img) : null;
            if (image != null) {
                if (!inline.isEmpty()) {
                    out.add(textFlow(inline));
                    inline = new ArrayList<>();
                }
                out.add(image);
            } else {
                appendInline(c, inline, false, false, false);
            }
        }
        if (!inline.isEmpty()) {
            out.add(textFlow(inline));
        }
        return out;
    }

    /** 把一组内联节点收成带 {@code md-p} 样式类的 TextFlow。 */
    private static javafx.scene.text.TextFlow textFlow(List<javafx.scene.Node> inline) {
        javafx.scene.text.TextFlow flow =
                new javafx.scene.text.TextFlow(inline.toArray(new javafx.scene.Node[0]));
        flow.getStyleClass().add("md-p");
        return flow;
    }

    /** 段落里是否存在「本地可渲染的图片」子节点（存在才走拆分路径）。 */
    private static boolean hasLocalImage(Node parent) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Image img && localImagePath(img.getDestination()) != null) {
                return true;
            }
        }
        return false;
    }

    /** 本地图片节点：同步加载、限宽展示、悬停看绝对路径、点击用系统程序打开；加载失败返回 null（回落 alt 文本）。 */
    private static javafx.scene.Node imageBlock(Image img) {
        Path path = localImagePath(img.getDestination());
        if (path == null) {
            return null;
        }
        javafx.scene.image.Image image = new javafx.scene.image.Image(path.toUri().toString(), false);
        if (image.isError()) {
            return null;
        }
        javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(image);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        double w = image.getWidth();
        view.setFitWidth(w > 0 ? Math.min(MAX_IMAGE_WIDTH, w) : MAX_IMAGE_WIDTH);
        view.getStyleClass().add("md-image");
        Tooltip.install(view, new Tooltip(path.toAbsolutePath().toString()));
        view.setOnMouseClicked(e -> openExternally(path));
        return view;
    }

    /** 点击图片用系统默认程序打开；无桌面支持时静默（悬停提示里已有路径可自行打开）。 */
    private static void openExternally(Path path) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(path.toFile());
            }
        } catch (Exception e) {
            // 打不开就保持静默：绝对路径已在悬停提示中给出
        }
    }

    /**
     * 判定 Markdown 图片目标是否为「本地可读文件」：支持 {@code file://} URI、Windows 盘符绝对路径
     * （{@code C:/…} 或 {@code C:\…}）、Unix 绝对路径与 UNC 路径；其余（http(s)、相对路径）返回
     * {@code null} 交由 alt 文本兑底。渲染层与导出层（HTML 内嵌图片）共用同一判定，公开以便复用。
     */
    public static Path localImagePath(String destination) {
        if (destination == null || destination.isBlank()) {
            return null;
        }
        String d = destination.trim();
        try {
            if (d.regionMatches(true, 0, "file:", 0, 5)) {
                Path p = Path.of(URI.create(d));
                return Files.isRegularFile(p) ? p : null;
            }
            boolean windowsAbs = d.length() > 2 && Character.isLetter(d.charAt(0)) && d.charAt(1) == ':'
                    && (d.charAt(2) == '/' || d.charAt(2) == '\\');
            boolean unixAbs = d.startsWith("/");
            boolean unc = d.startsWith("\\\\");
            if (windowsAbs || unixAbs || unc) {
                Path p = Path.of(d);
                return Files.isRegularFile(p) ? p : null;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** 列表：逐项前置符号（• 或 数字.），子块递归渲染。 */
    private static VBox listBlock(ListBlock list) {
        VBox box = new VBox(3);
        box.getStyleClass().add("md-list");
        boolean ordered = list instanceof OrderedList;
        int index = ordered ? ((OrderedList) list).getStartNumber() : 1;
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem li)) {
                continue;
            }
            String marker = ordered ? (index++) + "." : "\u2022";
            box.getChildren().add(listItem(marker, li, ordered));
        }
        return box;
    }

    private static HBox listItem(String marker, ListItem item, boolean ordered) {
        Label bullet = new Label(marker);
        bullet.getStyleClass().add("md-bullet");
        bullet.setMinWidth(ordered ? 28 : 16);
        VBox content = new VBox(3);
        List<javafx.scene.Node> blocks = new ArrayList<>();
        for (Node c = item.getFirstChild(); c != null; c = c.getNext()) {
            blocks.addAll(renderBlock(c));
        }
        if (blocks.isEmpty()) {
            javafx.scene.text.TextFlow empty = new javafx.scene.text.TextFlow(mdText(""));
            empty.getStyleClass().add("md-p");
            blocks.add(empty);
        }
        content.getChildren().addAll(blocks);
        HBox.setHgrow(content, Priority.ALWAYS);
        HBox row = new HBox(6, bullet, content);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    /**
     * 代码块：着色文本流（关键字/字符串/数字/注释四类）+ 右上角操作钮（HTML/SVG 为预览 + 复制，其余仅复制）。
     * <p>着色由 {@link CodeHighlighter} 切段，样式类 {@code md-code-*} 由明暗两套 CSS 提供色值。
     * 外层 ScrollPane 承担横向滚动（代码不折行），纵向高度由内容自然撑开、交给消息区统一滚动。</p>
     */
    private static Region codeBlock(String code, String language) {
        javafx.scene.text.TextFlow flow = new javafx.scene.text.TextFlow();
        flow.getStyleClass().add("md-code-flow");
        for (CodeHighlighter.Segment seg : CodeHighlighter.tokenize(code, language)) {
            javafx.scene.text.Text text = new javafx.scene.text.Text(seg.text());
            text.getStyleClass().add(seg.styleClass() == null ? "md-code-txt" : seg.styleClass());
            flow.getChildren().add(text);
        }
        // 不折行：宽高自然撑开，横向溢出交给 ScrollPane
        javafx.scene.control.ScrollPane scroller = new javafx.scene.control.ScrollPane(flow);
        scroller.getStyleClass().add("md-code-scroll");
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);

        Label copyIcon = new Label("\uE8C8");
        copyIcon.getStyleClass().add("md-copy-icon");
        Button copy = new Button();
        copy.setGraphic(copyIcon);
        copy.getStyleClass().add("md-copy-btn");
        copy.setFocusTraversable(false);
        Tooltip tip = new Tooltip(I18n.t("chat.artifact.copy"));
        copy.setTooltip(tip);
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(code);
            Clipboard.getSystemClipboard().setContent(content);
            copyIcon.setText("\uE73E");
            tip.setText(I18n.t("chat.artifact.copied"));
            PauseTransition reset = new PauseTransition(Duration.millis(1200));
            reset.setOnFinished(f -> {
                copyIcon.setText("\uE8C8");
                tip.setText(I18n.t("chat.artifact.copy"));
            });
            reset.play();
        });

        HBox codeActions = new HBox(4);
        // 锁内容尺寸：StackPane 会把可伸缩子节点拉满，不锁则操作钮被 HBox 左对齐后落在左上角
        codeActions.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        if (ArtifactPreviewWindow.isPreviewable(language)) {
            Label previewIcon = new Label("\uE7B3");
            previewIcon.getStyleClass().add("md-copy-icon");
            Button preview = new Button();
            preview.setGraphic(previewIcon);
            preview.getStyleClass().add("md-copy-btn");
            preview.setFocusTraversable(false);
            preview.setTooltip(new Tooltip(I18n.t("chat.artifact.preview")));
            preview.setOnAction(e -> ArtifactPreviewWindow.preview(
                    preview.getScene() == null ? null : preview.getScene().getWindow(), code, language));
            codeActions.getChildren().add(preview);
        }
        codeActions.getChildren().add(copy);

        StackPane pane = new StackPane(scroller, codeActions);
        StackPane.setAlignment(codeActions, Pos.TOP_RIGHT);
        StackPane.setMargin(codeActions, new Insets(6, 8, 0, 0));
        pane.getStyleClass().add("md-code-block");
        return pane;
    }

    /** GFM 表格：GridPane 简化渲染，首行为表头。 */
    private static javafx.scene.Node tableNode(org.commonmark.ext.gfm.tables.TableBlock table) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("md-table");
        grid.setHgap(1);
        grid.setVgap(1);
        int colCount = 0;
        int row = 0;
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            if (!(section instanceof TableHead) && !(section instanceof TableBody)) {
                continue;
            }
            boolean header = section instanceof TableHead;
            for (Node tr = section.getFirstChild(); tr != null; tr = tr.getNext()) {
                if (!(tr instanceof TableRow)) {
                    continue;
                }
                int col = 0;
                for (Node cellNode = tr.getFirstChild(); cellNode != null; cellNode = cellNode.getNext()) {
                    if (!(cellNode instanceof TableCell)) {
                        continue;
                    }
                    Label cell = new Label(plainInline(cellNode));
                    cell.setWrapText(true);
                    cell.getStyleClass().add(header ? "md-th" : "md-td");
                    grid.add(cell, col, row);
                    col++;
                }
                colCount = Math.max(colCount, col);
                row++;
            }
        }
        ColumnConstraints cc = new ColumnConstraints();
        cc.setHgrow(Priority.SOMETIMES);
        cc.setFillWidth(true);
        for (int i = 0; i < colCount; i++) {
            grid.getColumnConstraints().add(cc);
        }
        return grid;
    }

    // ===================== 行内渲染 =====================

    /** 段落内联：产出带样式类的 Text 节点序列。 */
    private static List<javafx.scene.Node> inlineNodes(Node parent) {
        List<javafx.scene.Node> out = new ArrayList<>();
        for (Node c = parent.getFirstChild(); c != null; c = c.getNext()) {
            appendInline(c, out, false, false, false);
        }
        return out;
    }

    private static void appendInline(Node node, List<javafx.scene.Node> out, boolean bold, boolean italic, boolean link) {
        if (node instanceof Text t) {
            javafx.scene.text.Text text = mdText(t.getLiteral());
            if (bold) {
                text.getStyleClass().add("md-bold");
            }
            if (italic) {
                text.getStyleClass().add("md-italic");
            }
            if (link) {
                text.getStyleClass().add("md-link");
            }
            out.add(text);
        } else if (node instanceof Code code) {
            // 行内码内嵌 Label 才能带底色/边框（Text 继承自 Shape，没有背景能力）
            Label chip = new Label(code.getLiteral());
            chip.getStyleClass().add("md-inline-code");
            if (bold) {
                chip.getStyleClass().add("md-bold");
            }
            out.add(chip);
        } else if (node instanceof StrongEmphasis) {
            for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
                appendInline(c, out, true, italic, link);
            }
        } else if (node instanceof Emphasis em) {
            for (Node c = em.getFirstChild(); c != null; c = c.getNext()) {
                appendInline(c, out, bold, true, link);
            }
        } else if (node instanceof Link) {
            for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
                appendInline(c, out, bold, italic, true);
            }
        } else if (node instanceof Image img) {
            String alt = plainInline(img);
            javafx.scene.text.Text text = mdText(alt.isBlank() ? "[\u56fe\u7247]" : alt);
            text.getStyleClass().add("md-image-alt");
            out.add(text);
        } else if (node instanceof SoftLineBreak) {
            out.add(new javafx.scene.text.Text(" "));
        } else if (node instanceof HardLineBreak) {
            out.add(new javafx.scene.text.Text("\n"));
        }
    }

    /** 创建行内基础文本：挂 {@code md-text} 基类承载默认填充色——JavaFX 原生 {@code Text}
        不带 "text" 样式类，CSS 选择器必须落到自定义类上才能命中。 */
    private static javafx.scene.text.Text mdText(String content) {
        javafx.scene.text.Text text = new javafx.scene.text.Text(content);
        text.getStyleClass().add("md-text");
        return text;
    }

    /** 取节点下所有文本内容（标题、表格单元、图片 alt 等用），内联标记按纯文本拼接。 */
    private static String plainInline(Node parent) {
        StringBuilder sb = new StringBuilder();
        collectText(parent, sb);
        return sb.toString();
    }

    private static void collectText(Node node, StringBuilder sb) {
        for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Text t) {
                sb.append(t.getLiteral());
            } else if (c instanceof Code code) {
                sb.append(code.getLiteral());
            } else if (c instanceof SoftLineBreak) {
                sb.append(' ');
            } else if (c instanceof HardLineBreak) {
                sb.append('\n');
            } else {
                collectText(c, sb);
            }
        }
    }
}
