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

package com.gettyio.gim.agent.ui.window.settings;

import com.gettyio.gim.agent.core.chat.HarnessAgentGateway;
import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.core.memory.MemoryService;
import com.gettyio.gim.agent.core.memory.MemoryService.MemoryFile;
import com.gettyio.gim.agent.core.memory.MemoryService.MemoryScope;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.ui.control.CardKit;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.theme.UiTheme;
import com.gettyio.gim.agent.ui.control.ToggleSwitch;
import com.gettyio.gim.agent.ui.window.dialog.GimDialog;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 记忆设置子面板（抽自 {@link SettingsWindow}）：长期记忆总开关与自动沉淀档位，
 * 并按作用域（全局记忆 = 默认智能体共享工作区 / 各智能体专属工作区）以文件夹卡片浏览。
 * <p>详情页分三区：<b>核心记忆</b>（框架精炼的 {@code MEMORY.md}，每轮注入上下文）、
 * <b>记忆文件</b>（{@code memory/} 下按主题新建/编辑/删除的分类 md，智能体可搜索、
 * 变更随沉淀自动合并进核心记忆）与<b>每日台账</b>（框架自动写入的日记式记录）。</p>
 * <p>开关与档位写 AppSetting（{@link HarnessAgentGateway#SETTING_MEMORY_ENABLED} 等）后
 * 失效全部运行体，下一轮对话按新配置重新装配即生效；文件读写直连 {@link MemoryService}——
 * 即 Harness 运行时实际维护的同一批文件。</p>
 *
 * @author gogym
 */
final class MemorySettingsPanel {

    /** 沉淀档位编码：与 AppSetting {@code agent.memory.flushMode} 的取值逐一对应。 */
    private static final String[] FLUSH_MODES = {"always", "throttled", "never"};

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final SettingsWindow host;
    private final MemoryService memoryService;
    private final GimDatabase database;
    private final MultiAgentGateway agentGateway;

    /** 列表视图 / 详情视图的交换容器（构建于 {@link #build()}）。 */
    private VBox pageHost;

    MemorySettingsPanel(SettingsWindow host, MemoryService memoryService,
                        GimDatabase database, MultiAgentGateway agentGateway) {
        this.host = host;
        this.memoryService = memoryService;
        this.database = database;
        this.agentGateway = agentGateway;
    }

    VBox build() {
        pageHost = new VBox();
        pageHost.setMaxWidth(Double.MAX_VALUE);
        showList();
        return pageHost;
    }

    // ---- 列表视图：行为卡片 + 全局/智能体作用域卡片 ----

    private void showList() {
        pageHost.getChildren().setAll(buildListPage());
    }

    private VBox buildListPage() {
        VBox page = SettingsLayout.page(I18n.t("settings.menu.memory"), I18n.t("settings.memory.subtitle"));
        page.getChildren().add(behaviorCard());

        List<MemoryScope> scopes = memoryService.listScopes();
        List<Node> shared = new ArrayList<>();
        List<Node> agents = new ArrayList<>();
        for (MemoryScope scope : scopes) {
            (scope.shared() ? shared : agents).add(scopeRow(scope));
        }
        page.getChildren().add(SettingsLayout.groupCard(I18n.t("settings.memory.scope.global"), shared));
        if (agents.isEmpty()) {
            agents.add(SettingsLayout.emptyHint(I18n.t("settings.memory.scope.none")));
        }
        page.getChildren().add(SettingsLayout.groupCard(I18n.t("settings.memory.scope.agents"), agents));
        return page;
    }

    /** 记忆行为卡片：长期记忆总开关 + 自动沉淀档位（关闭总开关时档位置灰）。 */
    private VBox behaviorCard() {
        ToggleSwitch enabled = new ToggleSwitch();
        enabled.setSelected(readEnabled());
        ComboBox<String> flush = SettingsLayout.pillCombo(flushLabels());
        flush.setValue(flushLabels()[flushIndex(readFlushMode())]);
        flush.setDisable(!enabled.isSelected());
        enabled.selectedProperty().addListener((obs, o, v) -> {
            saveEnabled(v);
            flush.setDisable(!v);
        });
        flush.setOnAction(e -> {
            int idx = flush.getSelectionModel().getSelectedIndex();
            if (idx >= 0 && idx < FLUSH_MODES.length) {
                saveFlushMode(FLUSH_MODES[idx]);
            }
        });
        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.settingRow(I18n.t("settings.memory.enabled"),
                I18n.t("settings.memory.enabled.desc"), enabled));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.memory.flush"),
                I18n.t("settings.memory.flush.desc"), flush));
        return SettingsLayout.groupCard(I18n.t("settings.memory.behavior"), rows);
    }

    /** 作用域行：文件夹图标 + 名称/统计 + 查看·打开位置·删除。 */
    private HBox scopeRow(MemoryScope scope) {
        Label icon = UiTheme.icon("\uE8B7");
        icon.getStyleClass().add("memory-scope-icon");

        Label title = new Label(scopeTitle(scope));
        title.getStyleClass().add("row-title");
        title.setTooltip(new Tooltip(scope.dir().toString()));
        Label meta = new Label(scopeMeta(scope));
        meta.getStyleClass().add("row-desc");
        VBox text = new VBox(2, title, meta);
        text.getStyleClass().add("row-text");
        HBox.setHgrow(text, Priority.ALWAYS);

        Button view = iconButton("\uE70F", "settings.memory.scope.view", () -> showDetail(scope));
        Button open = iconButton("\uE838", "settings.memory.scope.open", () -> revealDir(scope.dir()));
        Button delete = iconButton("\uE74D", "settings.memory.scope.delete", () -> deleteScope(scope));
        delete.getStyleClass().add("icon-btn-danger");
        HBox controls = new HBox(2, view, open, delete);
        controls.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(10, icon, text, controls);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        return row;
    }

    /** 作用域标题：共享卡固定「全局记忆」，专属卡取智能体显示名（缺失回退目录名）。 */
    private static String scopeTitle(MemoryScope scope) {
        if (scope.shared()) {
            return I18n.t("settings.memory.scope.global");
        }
        if (!scope.agentNames().isEmpty()) {
            return scope.agentNames().get(0);
        }
        Path name = scope.dir().getFileName();
        return name == null ? scope.dir().toString() : name.toString();
    }

    /** 统计行：文件数 · 最近更新（无文件显示空态）；多智能体合用的专属目录追加共用数。 */
    private static String scopeMeta(MemoryScope scope) {
        String meta = scope.fileCount() == 0
                ? I18n.t("settings.memory.scope.empty")
                : I18n.t("settings.memory.scope.meta",
                        String.valueOf(scope.fileCount()), formatTime(scope.lastModified()));
        if (!scope.shared() && scope.agentNames().size() > 1) {
            meta += " · " + I18n.t("settings.memory.scope.shared",
                    String.valueOf(scope.agentNames().size()));
        }
        return meta;
    }

    // ---- 详情视图：核心记忆 + 记忆文件（分类） + 每日台账 ----

    private void showDetail(MemoryScope scope) {
        pageHost.getChildren().setAll(buildDetailPage(scope));
    }

    private VBox buildDetailPage(MemoryScope scope) {
        VBox page = SettingsLayout.page(scopeTitle(scope), scope.dir().toString());
        Button back = CardKit.iconTextButton("\uE72B", I18n.t("settings.memory.back"), this::showList);
        back.getStyleClass().add("link-btn");
        HBox backRow = new HBox(back);
        backRow.setAlignment(Pos.CENTER_LEFT);
        page.getChildren().add(backRow);

        page.getChildren().addAll(coreSection(scope), new Separator(),
                categoryHead(scope), categoryCard(scope), new Separator(),
                sectionLabel("settings.memory.daily"), dailyCard(scope));
        return page;
    }

    /** 核心记忆区：MEMORY.md 编辑器（框架自动精炼层，每轮对话注入上下文），直接落在页面底色上不加卡片。 */
    private VBox coreSection(MemoryScope scope) {
        Label title = sectionLabel("settings.memory.core");
        Button save = new Button(I18n.t("settings.memory.save"));
        save.getStyleClass().add("btn-primary");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(8, title, spacer, save);
        head.setAlignment(Pos.CENTER_LEFT);
        Label desc = new Label(I18n.t("settings.memory.core.desc"));
        desc.getStyleClass().add("row-desc");
        desc.setWrapText(true);
        TextArea editor = new TextArea(memoryService.readLongTerm(scope.key()));
        editor.getStyleClass().add("memory-editor");
        editor.setWrapText(true);
        editor.setPromptText(I18n.t("settings.memory.placeholder"));
        save.setOnAction(e -> {
            try {
                memoryService.writeLongTerm(scope.key(), editor.getText());
                host.notify("settings.menu.memory", I18n.t("settings.memory.saved"));
            } catch (RuntimeException ex) {
                host.notify("settings.menu.memory", String.valueOf(ex.getMessage()));
            }
        });
        return new VBox(8, head, desc, editor);
    }

    /** 记忆文件区头部：区块标题 + 新建入口。 */
    private HBox categoryHead(MemoryScope scope) {
        Label title = sectionLabel("settings.memory.category");
        Button add = CardKit.iconTextButton("\uE710", I18n.t("settings.memory.category.new"),
                () -> createCategoryFile(scope));
        add.getStyleClass().add("link-btn");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(8, title, spacer, add);
        head.setAlignment(Pos.CENTER_LEFT);
        return head;
    }

    /** 记忆文件列表卡片：无分类文件显示空态提示。 */
    private VBox categoryCard(MemoryScope scope) {
        VBox list = new VBox();
        list.getStyleClass().add("settings-group");
        List<MemoryFile> files = memoryService.listCategoryFiles(scope.key());
        if (files.isEmpty()) {
            list.getChildren().add(SettingsLayout.emptyHint(I18n.t("settings.memory.category.empty")));
            return list;
        }
        for (int i = 0; i < files.size(); i++) {
            HBox row = categoryRow(scope, files.get(i));
            if (i > 0) {
                row.getStyleClass().add("settings-row-divider");
            }
            list.getChildren().add(row);
        }
        return list;
    }

    /** 分类文件行：文件名 + 更新时间 + 编辑/删除。 */
    private HBox categoryRow(MemoryScope scope, MemoryFile file) {
        Label nameLabel = new Label(file.name());
        nameLabel.getStyleClass().add("row-title");
        Label meta = new Label(I18n.t("settings.memory.category.updated", formatTime(file.lastModified())));
        meta.getStyleClass().add("row-desc");
        VBox text = new VBox(2, nameLabel, meta);
        text.getStyleClass().add("row-text");
        HBox.setHgrow(text, Priority.ALWAYS);

        Button edit = new Button(I18n.t("settings.memory.category.edit"));
        edit.getStyleClass().add("link-btn");
        edit.setOnAction(e -> editCategoryFile(scope, file.name()));
        Button delete = new Button(I18n.t("settings.memory.category.delete"));
        delete.getStyleClass().addAll("link-btn", "link-btn-danger");
        delete.setOnAction(e -> deleteCategoryFile(scope, file.name()));
        HBox controls = new HBox(8, edit, delete);
        controls.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(10, text, controls);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        return row;
    }

    /** 新建分类记忆文件：输入名字（自动清洗）→ 建空文件 → 直接打开编辑。 */
    private void createCategoryFile(MemoryScope scope) {
        Optional<String> input = GimDialog.promptText(host.hostWindow(),
                I18n.t("settings.memory.category.new"), I18n.t("settings.memory.category.name"),
                "", 60);
        if (input.isEmpty()) {
            return;
        }
        String finalName;
        try {
            finalName = memoryService.createCategoryFile(scope.key(), input.get(), "");
        } catch (IllegalArgumentException e) {
            GimDialog.message(host.hostWindow(), I18n.t("settings.memory.category.new"),
                    null, I18n.t("settings.memory.category.invalidName"));
            return;
        } catch (IllegalStateException e) {
            GimDialog.message(host.hostWindow(), I18n.t("settings.memory.category.new"),
                    null, I18n.t("settings.memory.category.duplicate", input.get()));
            return;
        }
        showDetail(scope);
        editCategoryFile(scope, finalName);
    }

    /** 编辑分类记忆文件：独立弹窗编辑器，保存后覆盖写回并刷新列表。 */
    private void editCategoryFile(MemoryScope scope, String name) {
        GimDialog.Shell shell = GimDialog.shell(host.hostWindow(), name);
        TextArea content = new TextArea(memoryService.readMemoryFile(scope.key(), name));
        content.setWrapText(true);
        content.setPrefWidth(600);
        content.setPrefHeight(420);
        content.setStyle("-fx-font-family: \"Cascadia Mono\", \"Consolas\", monospace; -fx-font-size: 12px;");
        Button save = new Button(I18n.t("settings.memory.save"));
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            try {
                memoryService.writeMemoryFile(scope.key(), name, content.getText());
                host.notify("settings.menu.memory", I18n.t("settings.memory.saved"));
                shell.stage().close();
                showDetail(scope);
            } catch (RuntimeException ex) {
                host.notify("settings.menu.memory", String.valueOf(ex.getMessage()));
            }
        });
        Button close = new Button(I18n.t("common.close"));
        close.setOnAction(e -> shell.stage().close());
        HBox buttons = new HBox(8, close, save);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        shell.body().getChildren().addAll(content, buttons);
        shell.show();
    }

    /** 删除分类记忆文件（确认后删除并刷新列表）。 */
    private void deleteCategoryFile(MemoryScope scope, String name) {
        if (!GimDialog.confirm(host.hostWindow(), I18n.t("settings.memory.category.delete"),
                I18n.t("settings.memory.category.delete.confirm", name))) {
            return;
        }
        try {
            memoryService.deleteMemoryFile(scope.key(), name);
        } catch (RuntimeException e) {
            host.notify("settings.menu.memory", String.valueOf(e.getMessage()));
            return;
        }
        showDetail(scope);
    }

    /** 每日台账列表卡片：无台账显示空态提示。 */
    private VBox dailyCard(MemoryScope scope) {
        VBox list = new VBox();
        list.getStyleClass().add("settings-group");
        List<String> files = memoryService.listDailyFiles(scope.key());
        if (files.isEmpty()) {
            list.getChildren().add(SettingsLayout.emptyHint(I18n.t("settings.memory.daily.empty")));
            return list;
        }
        for (int i = 0; i < files.size(); i++) {
            HBox row = dailyRow(scope, files.get(i));
            if (i > 0) {
                row.getStyleClass().add("settings-row-divider");
            }
            list.getChildren().add(row);
        }
        return list;
    }

    /** 台账行：文件名 + 查看/删除（删除红字）。 */
    private HBox dailyRow(MemoryScope scope, String name) {
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("row-title");
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        Button view = new Button(I18n.t("settings.memory.daily.view"));
        view.getStyleClass().add("link-btn");
        view.setOnAction(e -> openDaily(scope, name));
        Button delete = new Button(I18n.t("settings.memory.daily.delete"));
        delete.getStyleClass().addAll("link-btn", "link-btn-danger");
        delete.setOnAction(e -> deleteDaily(scope, name));
        HBox controls = new HBox(8, view, delete);
        controls.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(10, nameLabel, controls);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        return row;
    }

    /** 以独立弹窗查看台账全文（只读，等宽字体）。 */
    private void openDaily(MemoryScope scope, String name) {
        GimDialog.Shell shell = GimDialog.shell(host.hostWindow(), name);
        TextArea content = new TextArea(memoryService.readMemoryFile(scope.key(), name));
        content.setEditable(false);
        content.setWrapText(true);
        content.setPrefWidth(600);
        content.setPrefHeight(420);
        content.setStyle("-fx-font-family: \"Cascadia Mono\", \"Consolas\", monospace; -fx-font-size: 12px;");
        Button close = new Button(I18n.t("common.close"));
        close.setOnAction(e -> shell.stage().close());
        HBox buttons = new HBox(8, close);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        shell.body().getChildren().addAll(content, buttons);
        shell.show();
    }

    private void deleteDaily(MemoryScope scope, String name) {
        if (!GimDialog.confirm(host.hostWindow(), I18n.t("settings.memory.daily.delete"),
                I18n.t("settings.memory.daily.delete.confirm", name))) {
            return;
        }
        try {
            memoryService.deleteMemoryFile(scope.key(), name);
        } catch (RuntimeException e) {
            host.notify("settings.menu.memory", String.valueOf(e.getMessage()));
            return;
        }
        showDetail(scope);
    }

    /** 删除整个作用域的记忆：确认后 MEMORY.md 与全部台账（含归档）一并移除，落点下次沉淀时重建。 */
    private void deleteScope(MemoryScope scope) {
        if (!GimDialog.confirm(host.hostWindow(), I18n.t("settings.memory.scope.delete"),
                I18n.t("settings.memory.delete.confirm", scopeTitle(scope)))) {
            return;
        }
        try {
            memoryService.deleteAll(scope.key());
        } catch (RuntimeException e) {
            host.notify("settings.menu.memory", String.valueOf(e.getMessage()));
            return;
        }
        showList();
    }

    /** 图标按钮 + tooltip。 */
    private Button iconButton(String glyph, String tooltipKey, Runnable action) {
        Button b = new Button();
        b.getStyleClass().add("icon-btn");
        b.setGraphic(UiTheme.icon(glyph));
        b.setTooltip(new Tooltip(I18n.t(tooltipKey)));
        b.setOnAction(e -> action.run());
        return b;
    }

    /** 区块标题（section-title + 记忆页扁平变体：无内衬边距，与内容左对齐、右侧按钮与文字中线对齐）。 */
    private static Label sectionLabel(String key) {
        Label label = new Label(I18n.t(key));
        label.getStyleClass().addAll("section-title", "memory-section-title");
        return label;
    }

    /** 用系统文件管理器打开作用域目录；不支持时状态提示。 */
    private void revealDir(Path dir) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir.toFile());
            } else {
                host.notify("settings.menu.memory", I18n.t("settings.memory.openFail", "Desktop unsupported"));
            }
        } catch (Exception e) {
            host.notify("settings.menu.memory", I18n.t("settings.memory.openFail", e.getMessage()));
        }
    }

    // ---- 设置读写 ----

    /** 长期记忆开关：缺省/非法为开（与后端装配口径一致：仅 "false" 为关）。 */
    private boolean readEnabled() {
        return database.settingDao().getValue(HarnessAgentGateway.SETTING_MEMORY_ENABLED)
                .map(v -> !"false".equalsIgnoreCase(v.trim()))
                .orElse(true);
    }

    /** 自动沉淀档位：缺省/未知回落 always。 */
    private String readFlushMode() {
        String mode = database.settingDao().getValue(HarnessAgentGateway.SETTING_MEMORY_FLUSH_MODE)
                .map(v -> v.trim().toLowerCase(Locale.ROOT))
                .orElse(FLUSH_MODES[0]);
        return switch (mode) {
            case "never", "throttled" -> mode;
            default -> FLUSH_MODES[0];
        };
    }

    private void saveEnabled(boolean enabled) {
        database.settingDao().upsert(HarnessAgentGateway.SETTING_MEMORY_ENABLED,
                String.valueOf(enabled), "长期记忆总开关（关闭后不读不写记忆文件）");
        agentGateway.invalidateAll();
    }

    private void saveFlushMode(String mode) {
        database.settingDao().upsert(HarnessAgentGateway.SETTING_MEMORY_FLUSH_MODE,
                mode, "记忆自动沉淀档位（always/throttled/never）");
        agentGateway.invalidateAll();
    }

    private static String[] flushLabels() {
        return new String[]{
                I18n.t("settings.memory.flush.always"),
                I18n.t("settings.memory.flush.throttled"),
                I18n.t("settings.memory.flush.never"),
        };
    }

    private static int flushIndex(String mode) {
        for (int i = 0; i < FLUSH_MODES.length; i++) {
            if (FLUSH_MODES[i].equals(mode)) {
                return i;
            }
        }
        return 0;
    }

    private static String formatTime(long millis) {
        return TIME_FMT.format(Instant.ofEpochMilli(millis));
    }
}
