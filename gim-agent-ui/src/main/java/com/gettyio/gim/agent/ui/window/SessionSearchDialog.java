package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 会话搜索命令面板：无边框圆角弹层，盖在磨玻璃背景上，实时过滤当前智能体的会话。
 * <p>顶部搜索框（按标题 / 会话 ID 子串、忽略大小写匹配），下方结果列表；↑↓ 移动选中、
 * Enter 或点击行打开、Esc 关闭。整个弹层铺满宿主窗口作透明遮罩（{@link FrostedBackdrop} 磨玻璃），
 * 点卡片以外的遮罩即关闭；卡片本身圆角 + 投影。</p>
 */
public final class SessionSearchDialog {

    /** 结果行右侧日期：跟随系统区域的中等日期格式，多语言下自适应。 */
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);

    /** 行高（px）：固定单元格高度以便按行数精确预算列表偏好高（行内容均单行，不会裁剪）。 */
    private static final int ROW_HEIGHT = 40;
    /** ListView 上下内边距（与 CSS .palette-list 的 padding 6 上下各一致）。 */
    private static final double LIST_VPAD = 12;
    /** 卡片宽度（px）：与 CSS/FXML 的 prefWidth 一致，作为「接近正方形」的高度目标基准。 */
    private static final double CARD_WIDTH = 620;
    /** 顶部搜索行 + 提示行的纵向占位估计（px），用于从卡片总高中扣除非列表部分。 */
    private static final double CHROME = 96;
    /** 卡片底部与宿主窗口下缘预留的间距（px）。 */
    private static final double BOTTOM_GAP = 40;

    private SessionSearchDialog() {
    }

    /**
     * 弹出搜索面板。
     *
     * @param owner    宿主主窗口（用于居中与磨玻璃遮罩）
     * @param sessions 参与搜索的会话（已按当前智能体过滤、按最近更新排序）
     * @param onPick   选中某条会话时的回调（面板随即关闭）
     */
    public static void show(Window owner, List<ChatSession> sessions, Consumer<ChatSession> onPick) {
        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        } else {
            stage.initModality(Modality.APPLICATION_MODAL);
        }

        ObservableList<ChatSession> all = FXCollections.observableArrayList(sessions);
        FilteredList<ChatSession> filtered = new FilteredList<>(all, s -> true);

        TextField field = new TextField();
        field.getStyleClass().add("palette-search");
        field.setPromptText(I18n.t("search.palette.prompt"));
        field.setMaxWidth(Double.MAX_VALUE);

        ListView<ChatSession> list = new ListView<>(filtered);
        list.getStyleClass().add("palette-list");
        // 键盘统一走搜索框，避免焦点在输入框与列表间跳动
        list.setFocusTraversable(false);
        list.setCellFactory(v -> new SessionCell());
        Label empty = new Label(I18n.t("search.palette.empty"));
        empty.getStyleClass().add("palette-empty");
        list.setPlaceholder(empty);

        // 固定为接近正方形的高度：不随会话条数伸缩（条数少时列表区留白，条数多时 ListView 自动出滚动条）。
        // 目标行数取两个约束的较小值：① 以卡片宽为高的「接近正方形」；② 宿主可用高度（避免窗口偏矮时溢出裁切）。
        list.setFixedCellSize(ROW_HEIGHT);
        // 把最小高也绑到偏好高，使列表固定占据该高度（不会因默认 minHeight 变化而伸缩）
        list.setMinHeight(Region.USE_PREF_SIZE);
        double ownerH = owner != null ? owner.getHeight() : 720;
        double topMargin = Math.max(48, ownerH * 0.14);   // 与 coverOwner 的卡片顶部间距一致
        double available = ownerH - topMargin - BOTTOM_GAP;
        int squareRows = (int) Math.floor((CARD_WIDTH - CHROME - LIST_VPAD) / ROW_HEIGHT);
        int heightRows = (int) Math.floor((available - CHROME - LIST_VPAD) / ROW_HEIGHT);
        int rows = Math.max(4, Math.min(squareRows, heightRows));
        list.setPrefHeight(rows * ROW_HEIGHT + LIST_VPAD);

        Runnable openSelected = () -> {
            ChatSession pick = list.getSelectionModel().getSelectedItem();
            if (pick != null) {
                stage.close();
                onPick.accept(pick);
            }
        };

        // 计数标签：随过滤结果实时更新
        Label count = new Label();
        count.getStyleClass().add("palette-hint-count");
        Runnable refreshCount = () -> count.setText(I18n.t("search.palette.count", filtered.size()));

        // 实时过滤：命中标题或会话 ID（忽略大小写）；有结果时选中首行，Enter 即可直开
        field.textProperty().addListener((obs, old, text) -> {
            String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            filtered.setPredicate(s -> needle.isEmpty()
                    || (s.getTitle() != null && s.getTitle().toLowerCase(Locale.ROOT).contains(needle))
                    || (s.getSessionId() != null && s.getSessionId().toLowerCase(Locale.ROOT).contains(needle)));
            if (!filtered.isEmpty()) {
                list.getSelectionModel().select(0);
            }
            refreshCount.run();
        });

        // ↑↓ 移动选中、Enter 打开：焦点始终留在搜索框，便于继续编辑关键词
        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) {
                move(list, filtered, 1);
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                move(list, filtered, -1);
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                openSelected.run();
                e.consume();
            }
        });
        // 点击结果行直接打开
        list.setOnMouseClicked(e -> {
            if (list.getSelectionModel().getSelectedItem() != null) {
                openSelected.run();
            }
        });
        if (!filtered.isEmpty()) {
            list.getSelectionModel().select(0);
        }

        HBox top = new HBox(10, UiTheme.icon("\uE721"), field);
        top.getStyleClass().add("palette-top");
        top.setAlignment(Pos.CENTER_LEFT);

        // 输入框下的提示行：左「全部会话」，右「↑ ↓ 选择 · Enter 打开 · N 个」
        Label group = new Label(I18n.t("search.palette.all"));
        group.getStyleClass().add("palette-hint-group");
        HBox hints = new HBox(6,
                keyChip("\u2191"), keyChip("\u2193"),
                hintWord(I18n.t("search.palette.select")),
                keyChip("Enter"), hintWord(I18n.t("search.palette.open")), count);
        hints.setAlignment(Pos.CENTER_RIGHT);
        Region hintSpacer = new Region();
        HBox.setHgrow(hintSpacer, Priority.ALWAYS);
        HBox hintBar = new HBox(8, group, hintSpacer, hints);
        hintBar.getStyleClass().add("palette-hint-bar");
        hintBar.setAlignment(Pos.CENTER_LEFT);
        refreshCount.run();

        VBox card = new VBox(top, hintBar, list);
        card.getStyleClass().add("palette-card");
        card.setPrefWidth(620);
        card.setMaxWidth(620);
        // 卡片高度不写死：由 top + 提示行 + 列表(按行预算的 prefHeight) 自然决定，随会话数伸缩。
        // 仍锁 maxHeight 为偏好值，否则 StackPane 会把卡片拉伸铺满整窗（Region.maxHeight 默认无限），
        // 那样点外面又会落在卡片内、自适应也失效。
        card.setMaxHeight(Region.USE_PREF_SIZE);
        // 卡片贴合内容，不给 list 额外的纵向增长空间（高度由 prefHeight 控制）
        VBox.setVgrow(list, Priority.NEVER);

        // 弹层根：铺满宿主窗口作遮罩，卡片顶部居中。
        // 关闭判定按「点击坐标是否在卡片矩形内」而非 target==root：圆角处的角像素、单元格空白、
        // dropshadow 外扩等都可能在 target 上落回 root，按坐标判定可确保点到弹窗本体（卡片包围盒）一律不关。
        StackPane root = new StackPane(card);
        root.getStyleClass().add("palette-root");
        // 整块遮罩区域都参与拾取（即使某些子像素未绘制），保证卡片外点击能收到事件
        root.setPickOnBounds(true);
        StackPane.setAlignment(card, Pos.TOP_CENTER);
        root.setOnMouseClicked(e -> {
            // 事件会从卡片子树冒泡到 root；把点击点转换到卡片本地坐标，落在卡片外才关闭
            if (!card.contains(card.sceneToLocal(e.getSceneX(), e.getSceneY()))) {
                stage.close();
            }
        });

        Scene scene = new Scene(root, Color.TRANSPARENT);
        UiTheme.apply(scene);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), stage::close);
        stage.setScene(scene);

        // 铺满宿主窗口，让「其它区域」的点击落在本弹层内从而可关闭（模态下宿主不会移动/缩放）
        coverOwner(stage, owner, card);
        bindAndShow(stage, owner, field, card);
    }

    /** 把弹层窗口铺满宿主窗口，并按高度比例给卡片留顶部间距（命令面板常见落点）。 */
    private static void coverOwner(Stage stage, Window owner, VBox card) {
        if (owner == null) {
            stage.centerOnScreen();
            return;
        }
        stage.setX(owner.getX());
        stage.setY(owner.getY());
        stage.setWidth(owner.getWidth());
        stage.setHeight(owner.getHeight());
        StackPane.setMargin(card, new Insets(Math.max(48, owner.getHeight() * 0.14), 0, 0, 0));
    }

    /** 上下移动选中项，到端点后环绕。 */
    private static void move(ListView<ChatSession> list, FilteredList<ChatSession> filtered, int delta) {
        int size = filtered.size();
        if (size == 0) {
            return;
        }
        int idx = list.getSelectionModel().getSelectedIndex();
        int next = Math.floorMod((idx < 0 ? 0 : idx) + delta, size);
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }

    /** 显示：卡片先置为不可见、弹窗展示时淡入（避免先闪全显再动画）；叠磨玻璃背景并把焦点给搜索框；关闭时撤除遮罩。 */
    private static void bindAndShow(Stage stage, Window owner, TextField field, VBox card) {
        Runnable[] frost = new Runnable[1];
        // 预置透明：show 后、onShown 前不会先闪一下全显的卡片
        card.setOpacity(0);
        stage.setOnShown(e -> {
            if (frost[0] != null) {
                frost[0].run();
            }
            frost[0] = FrostedBackdrop.enter(owner);
            // 淡入：弹窗本体（卡片）由透明逐帧变为不透明，EASE_OUT 使尾部减速、观感更柔和
            FadeTransition fadeIn = new FadeTransition(Duration.millis(170), card);
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);
            fadeIn.setInterpolator(Interpolator.EASE_OUT);
            fadeIn.play();
            field.requestFocus();
        });
        stage.setOnHidden(e -> {
            if (frost[0] != null) {
                frost[0].run();
                frost[0] = null;
            }
        });
        stage.show();
    }

    /** 提示行里的按键小方片（↑ / ↓ / Enter）。 */
    private static Label keyChip(String text) {
        Label chip = new Label(text);
        chip.getStyleClass().add("palette-key");
        return chip;
    }

    /** 提示行里紧跟按键的说明文字（选择 / 打开）。 */
    private static Label hintWord(String text) {
        Label word = new Label(text);
        word.getStyleClass().add("palette-hint-word");
        return word;
    }

    /** 结果行单元格：标题（可截断，占满剩余宽）+ 右侧更新日期。 */
    private static final class SessionCell extends ListCell<ChatSession> {
        private final Label title = new Label();
        private final Label date = new Label();
        private final HBox row;

        SessionCell() {
            title.getStyleClass().add("palette-row-title");
            title.setMaxWidth(Double.MAX_VALUE);
            title.setEllipsisString("\u2026");
            HBox.setHgrow(title, Priority.ALWAYS);
            date.getStyleClass().add("palette-row-date");
            row = new HBox(12, title, date);
            row.getStyleClass().add("palette-row");
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(ChatSession item, boolean emptyCell) {
            super.updateItem(item, emptyCell);
            if (emptyCell || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            String name = item.getTitle();
            title.setText(name == null || name.isBlank() ? item.getSessionId() : name);
            LocalDateTime t = item.getUpdatedAt() != null ? item.getUpdatedAt() : item.getCreatedAt();
            date.setText(t == null ? "" : DATE.format(t));
            setText(null);
            setGraphic(row);
        }
    }
}
