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

package com.gettyio.gim.agent.ui.window.dialog;

import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.domain.entity.MessageHit;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.theme.UiTheme;
import com.gettyio.gim.agent.ui.window.FrostedBackdrop;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 搜索命令面板：无边框圆角弹层，盖在磨玻璃背景上，实时检索当前智能体的会话与消息内容。
 * <p>顶部搜索框：命中会话标题 / 会话 ID 归入「会话」分组，命中消息正文归入「消息内容」分组（正文行
 * 展示会话标题 + 关键词命中片段）。↑↓ 移动选中（跳过分组标题）、Enter 或点击行打开、Esc 关闭。</p>
 * <p>标题分组在 FX 线程即时刷新（纯内存比较），正文分组要查库，故合并 200ms 内的连续按键、只检索
 * 最后一次，并在池线程执行；结果带回 FX 时按发起序号校验，慢的那次不会盖掉新的。</p>
 *
 * @author gogym
 */
public final class SessionSearchDialog {

    private static final Logger log = LoggerFactory.getLogger(SessionSearchDialog.class);

    /** 结果行右侧日期：跟随系统区域的中等日期格式，多语言下自适应。 */
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);
    /** 消息片段命中位置前后各取的上下文字数。 */
    private static final int SNIPPET_CONTEXT = 30;
    /** 「消息内容」分组单次最多展示条数。 */
    public static final int MESSAGE_LIMIT = 50;
    /**
     * 宿主查库应多取的条数：命中还要按当前智能体过滤，只取展示条数会筛剩半数甚至更少，
     * 面板据此上限截断，故多取 4 倍再筛。
     */
    public static final int MESSAGE_FETCH_LIMIT = MESSAGE_LIMIT * 4;
    /** 关键词停顿多久才检索消息正文：每敲一个字都扫一遍 {@code conversations} 会让输入明显发涩。 */
    private static final Duration SEARCH_DEBOUNCE = Duration.millis(200);
    /** 估算行高（px）：仅用于预算列表偏好高（实际单元格按内容自适应）。 */
    private static final double ROW_EST = 42;
    /** ListView 上下内边距（与 CSS .palette-list 的 padding 一致）。 */
    private static final double LIST_VPAD = 12;
    /** 卡片宽度（px）：与 CSS/FXML 的 prefWidth 一致，作为「接近正方形」的高度目标基准。 */
    private static final double CARD_WIDTH = 620;
    /** 顶部搜索行 + 提示行的纵向占位估计（px）。 */
    private static final double CHROME = 96;
    /** 卡片底部与宿主窗口下缘预留的间距（px）。 */
    private static final double BOTTOM_GAP = 40;

    private SessionSearchDialog() {
    }

    /** 面板行模型：分组标题、会话行、消息行三态。 */
    private sealed interface Row permits Header, SessionRow, MessageRow {
    }

    private record Header(String text) implements Row {
    }

    private record SessionRow(ChatSession session) implements Row {
    }

    private record MessageRow(MessageHit hit, String snippet) implements Row {
    }

    /**
     * 弹出搜索面板。
     *
     * @param owner          宿主主窗口（用于居中与磨玻璃遮罩）
     * @param sessions       参与标题检索的会话（已按当前智能体过滤、按最近更新排序）
     * @param messageFinder  按关键词检索消息正文（返回命中行；由服务层查库）
     * @param onPickSession  选中会话时的回调
     * @param onPickMessage  选中消息时的回调（面板据此打开其所属会话并滚动到该条）
     */
    public static void show(Window owner, List<ChatSession> sessions,
                            Function<String, List<MessageHit>> messageFinder,
                            Consumer<ChatSession> onPickSession, Consumer<MessageHit> onPickMessage) {
        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        } else {
            stage.initModality(Modality.APPLICATION_MODAL);
        }

        ObservableList<Row> items = FXCollections.observableArrayList();

        TextField field = new TextField();
        field.getStyleClass().add("palette-search");
        field.setPromptText(I18n.t("search.palette.prompt"));
        field.setMaxWidth(Double.MAX_VALUE);

        ListView<Row> list = new ListView<>(items);
        list.getStyleClass().add("palette-list");
        list.setFocusTraversable(false);
        list.setCellFactory(v -> new RowCell());
        Label empty = new Label(I18n.t("search.palette.empty"));
        empty.getStyleClass().add("palette-empty");
        list.setPlaceholder(empty);

        list.setMinHeight(Region.USE_PREF_SIZE);
        double ownerH = owner != null ? owner.getHeight() : 720;
        double topMargin = Math.max(48, ownerH * 0.14);
        double available = ownerH - topMargin - BOTTOM_GAP;
        int squareRows = (int) Math.floor((CARD_WIDTH - CHROME - LIST_VPAD) / ROW_EST);
        int heightRows = (int) Math.floor((available - CHROME - LIST_VPAD) / ROW_EST);
        int rows = Math.max(4, Math.min(squareRows, heightRows));
        list.setPrefHeight(rows * ROW_EST + LIST_VPAD);

        // 计数标签：随过滤结果实时更新（只数可选行，不含分组标题）
        Label count = new Label();
        count.getStyleClass().add("palette-hint-count");
        Runnable refreshCount = () -> count.setText(I18n.t("search.palette.count", selectableCount(items)));

        // 消息检索的发起序号：每次改动关键词自增，池线程结果只在序号未变时才并入列表
        long[] searchSeq = new long[1];
        PauseTransition debounce = new PauseTransition(SEARCH_DEBOUNCE);
        Runnable rebuild = () -> {
            String kw = field.getText() == null ? "" : field.getText().trim();
            searchSeq[0]++;
            items.setAll(sessionRows(sessions, kw.toLowerCase(Locale.ROOT)));
            selectFirstSelectable(list);
            refreshCount.run();
            if (!kw.isEmpty()) {
                debounce.playFromStart();
            }
        };

        debounce.setOnFinished(e -> {
            String kw = field.getText() == null ? "" : field.getText().trim();
            if (kw.isEmpty()) {
                return;
            }
            long seq = ++searchSeq[0];
            Mono.fromCallable(() -> messageRows(messageFinder.apply(kw), kw))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(picked -> Platform.runLater(() -> {
                        // 慢的那次不能盖掉新的：期间只要又敲过字（序号变了）就整批丢弃
                        if (seq != searchSeq[0] || picked.isEmpty()) {
                            return;
                        }
                        items.add(new Header(I18n.t("search.palette.group.messages")));
                        items.addAll(picked);
                        // 会话标题一条没中时列表还没有选中行，补上后要让 Enter 直接可打开首条
                        if (list.getSelectionModel().getSelectedIndex() < 0) {
                            selectFirstSelectable(list);
                        }
                        refreshCount.run();
                    }), err -> {
                        // 检索失败只少了「消息内容」分组，标题命中的会话仍可选择
                        log.warn("消息正文检索失败: {}", kw, err);
                    });
        });

        field.textProperty().addListener((obs, old, text) -> rebuild.run());

        // 打开当前选中行
        Runnable openSelected = () -> {
            Row pick = list.getSelectionModel().getSelectedItem();
            if (pick instanceof SessionRow sr) {
                stage.close();
                onPickSession.accept(sr.session());
            } else if (pick instanceof MessageRow mr) {
                stage.close();
                onPickMessage.accept(mr.hit());
            }
        };

        // ↑↓ 移动选中（跳过分组标题）、Enter 打开；焦点始终留在搜索框
        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) {
                move(list, 1);
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                move(list, -1);
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                openSelected.run();
                e.consume();
            }
        });
        list.setOnMouseClicked(e -> {
            if (list.getSelectionModel().getSelectedItem() != null) {
                openSelected.run();
            }
        });

        HBox top = new HBox(10, UiTheme.icon("\uE721"), field);
        top.getStyleClass().add("palette-top");
        top.setAlignment(Pos.CENTER_LEFT);

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

        VBox card = new VBox(top, hintBar, list);
        card.getStyleClass().add("palette-card");
        card.setPrefWidth(620);
        card.setMaxWidth(620);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        VBox.setVgrow(list, Priority.NEVER);

        StackPane root = new StackPane(card);
        root.getStyleClass().add("palette-root");
        root.setPickOnBounds(true);
        StackPane.setAlignment(card, Pos.TOP_CENTER);
        root.setOnMouseClicked(e -> {
            if (!card.contains(card.sceneToLocal(e.getSceneX(), e.getSceneY()))) {
                stage.close();
            }
        });

        Scene scene = new Scene(root, Color.TRANSPARENT);
        UiTheme.apply(scene);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), stage::close);
        stage.setScene(scene);

        // 初次填充（关键词为空 → 只列全部会话）
        rebuild.run();

        coverOwner(stage, owner, card);
        bindAndShow(stage, owner, field, card, () -> {
            debounce.stop();
            // 在途那一轮的序号作废，回来也不再并入已关闭的面板
            searchSeq[0]++;
        });
    }

    /** 会话分组：标题 / 会话 ID 子串命中（纯内存，随敲键即时刷新）。空关键词列全部、不带分组标题。 */
    private static List<Row> sessionRows(List<ChatSession> sessions, String needle) {
        List<Row> rows = new ArrayList<>();
        List<ChatSession> matched = new ArrayList<>();
        for (ChatSession s : sessions) {
            if (needle.isEmpty()
                    || (s.getTitle() != null && s.getTitle().toLowerCase(Locale.ROOT).contains(needle))
                    || (s.getSessionId() != null && s.getSessionId().toLowerCase(Locale.ROOT).contains(needle))) {
                matched.add(s);
            }
        }
        if (needle.isEmpty()) {
            for (ChatSession s : matched) {
                rows.add(new SessionRow(s));
            }
            return rows;
        }
        if (!matched.isEmpty()) {
            rows.add(new Header(I18n.t("search.palette.group.sessions")));
            for (ChatSession s : matched) {
                rows.add(new SessionRow(s));
            }
        }
        return rows;
    }

    /**
     * 消息分组行：在池线程上组装（整段正文的空白折叠与定位也在这一段，别留在 FX 线程），
     * 分组标题要查 {@link I18n}，仍由 FX 线程在并入列表时补。
     */
    private static List<MessageRow> messageRows(List<MessageHit> hits, String kw) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        int n = Math.min(hits.size(), MESSAGE_LIMIT);
        List<MessageRow> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            MessageHit h = hits.get(i);
            rows.add(new MessageRow(h, snippet(h.content(), kw)));
        }
        return rows;
    }

    /** 取关键词首次命中处的上下文片段（折叠换行，前后加省略号）。 */
    private static String snippet(String content, String keyword) {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        if (keyword == null || keyword.isBlank()) {
            return abbreviate(flat);
        }
        int idx = flat.toLowerCase(Locale.ROOT).indexOf(keyword.trim().toLowerCase(Locale.ROOT));
        if (idx < 0) {
            return abbreviate(flat);
        }
        int start = Math.max(0, idx - SNIPPET_CONTEXT);
        int end = Math.min(flat.length(), idx + keyword.trim().length() + SNIPPET_CONTEXT);
        String out = flat.substring(start, end).trim();
        if (start > 0) {
            out = "\u2026" + out;
        }
        if (end < flat.length()) {
            out = out + "\u2026";
        }
        return out;
    }

    private static String abbreviate(String s) {
        return s.length() <= SNIPPET_CONTEXT * 2 ? s : s.substring(0, SNIPPET_CONTEXT * 2) + "\u2026";
    }

    private static int selectableCount(ObservableList<Row> items) {
        int n = 0;
        for (Row r : items) {
            if (!(r instanceof Header)) {
                n++;
            }
        }
        return n;
    }

    /** 选中首个非标题行。 */
    private static void selectFirstSelectable(ListView<Row> list) {
        for (int i = 0; i < list.getItems().size(); i++) {
            if (!(list.getItems().get(i) instanceof Header)) {
                list.getSelectionModel().select(i);
                list.scrollTo(i);
                return;
            }
        }
        list.getSelectionModel().clearSelection();
    }

    /** 上下移动选中项，跳过分组标题，到端点后环绕。 */
    private static void move(ListView<Row> list, int delta) {
        ObservableList<Row> items = list.getItems();
        int size = items.size();
        if (size == 0) {
            return;
        }
        int idx = list.getSelectionModel().getSelectedIndex();
        int cur = idx < 0 ? (delta > 0 ? -1 : 0) : idx;
        int next = cur;
        for (int step = 0; step < size; step++) {
            next = Math.floorMod(next + delta, size);
            if (!(items.get(next) instanceof Header)) {
                break;
            }
        }
        if (!(items.get(next) instanceof Header)) {
            list.getSelectionModel().select(next);
            list.scrollTo(next);
        }
    }

    /** 把弹层窗口铺满宿主窗口，并按高度比例给卡片留顶部间距。 */
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

    /** 显示：卡片先置为不可见，弹窗展示时淡入；叠磨玻璃背景并把焦点给搜索框；关闭时撤除遮罩、停掉未发的检索。 */
    private static void bindAndShow(Stage stage, Window owner, TextField field, VBox card, Runnable cancelSearch) {
        Runnable[] frost = new Runnable[1];
        card.setOpacity(0);
        stage.setOnShown(e -> {
            if (frost[0] != null) {
                frost[0].run();
            }
            frost[0] = FrostedBackdrop.enter(owner);
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
            cancelSearch.run();
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

    /** 三态行单元格：分组标题、会话行、消息行各一套图。 */
    private static final class RowCell extends ListCell<Row> {
        private final Label headerLabel = new Label();
        private final HBox headerRow;

        private final Label sTitle = new Label();
        private final Label sDate = new Label();
        private final HBox sessionRow;

        private final Label mTitle = new Label();
        private final Label mDate = new Label();
        private final Label mSnippet = new Label();
        private final VBox messageRow;

        RowCell() {
            headerLabel.getStyleClass().add("palette-header");
            headerRow = new HBox(headerLabel);
            headerRow.getStyleClass().add("palette-header-row");
            headerRow.setAlignment(Pos.CENTER_LEFT);

            sTitle.getStyleClass().add("palette-row-title");
            sTitle.setMaxWidth(Double.MAX_VALUE);
            sTitle.setEllipsisString("\u2026");
            HBox.setHgrow(sTitle, Priority.ALWAYS);
            sDate.getStyleClass().add("palette-row-date");
            sessionRow = new HBox(12, sTitle, sDate);
            sessionRow.getStyleClass().add("palette-row");
            sessionRow.setAlignment(Pos.CENTER_LEFT);

            mTitle.getStyleClass().add("palette-row-title");
            mDate.getStyleClass().add("palette-row-date");
            HBox titleLine = new HBox(12, mTitle, mDate);
            titleLine.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(mTitle, Priority.ALWAYS);
            mSnippet.getStyleClass().add("palette-msg-snippet");
            mSnippet.setWrapText(false);
            mSnippet.setMaxWidth(Double.MAX_VALUE);
            mSnippet.setEllipsisString("");
            messageRow = new VBox(2, titleLine, mSnippet);
            messageRow.getStyleClass().add("palette-msg-row");
            messageRow.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Row item, boolean emptyCell) {
            super.updateItem(item, emptyCell);
            setText(null);
            if (emptyCell || item == null) {
                setGraphic(null);
                setMouseTransparent(false);
                return;
            }
            if (item instanceof Header h) {
                headerLabel.setText(h.text());
                setGraphic(headerRow);
                setMouseTransparent(true);
                setDisable(true);
                return;
            }
            setDisable(false);
            setMouseTransparent(false);
            if (item instanceof SessionRow sr) {
                ChatSession s = sr.session();
                String name = s.getTitle();
                sTitle.setText(name == null || name.isBlank() ? s.getSessionId() : name);
                sDate.setText(formatDate(s.getUpdatedAt() != null ? s.getUpdatedAt() : s.getCreatedAt()));
                setGraphic(sessionRow);
            } else if (item instanceof MessageRow mr) {
                String t = mr.hit().sessionTitle();
                mTitle.setText(t == null || t.isBlank() ? mr.hit().sessionId() : t);
                mDate.setText(formatDate(mr.hit().createdAt()));
                mSnippet.setText(mr.snippet());
                setGraphic(messageRow);
            }
        }
    }

    private static String formatDate(LocalDateTime t) {
        return t == null ? "" : DATE.format(t);
    }
}
