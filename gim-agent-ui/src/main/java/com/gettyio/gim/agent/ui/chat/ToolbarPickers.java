package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.domain.entity.ModelConfig;
import com.gettyio.gim.agent.domain.entity.ModelProviderConfig;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.config.ProviderAdminService;
import com.gettyio.gim.agent.ui.config.SecurityAdminService;
import io.agentscope.core.permission.PermissionMode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 输入框工具条上的三个下拉：齿轮（主题/语言/关于）、模型选择、审批档位。
 * <p>各自复用单一 {@link ContextMenu} 实例并做开关切换，避免反复点击累积弹出。持有当前选中的
 * 模型与审批档位，供发送流程读取。由 {@code MainController} 在 FXML 注入后构造并委托。</p>
 */
public class ToolbarPickers {

    private final Button themeMenuButton;
    private final Button modelButton;
    private final Button approvalButton;
    private final ProviderAdminService providerAdminService;
    private final SecurityAdminService securityAdminService;
    private final ChatHost host;

    /** 当前审批档位（权限模式 + 是否自动通过）；由审批按钮弹菜单选择，仅影响下一条要发的消息 */
    private ApprovalOption approval = approvalOptions().get(0);
    /** 规划模式开关（独立于审批档位）：勾选后下一条消息先走只读计划轮，产出 plans/PLAN.md 供确认 */
    private boolean planModeRequested;
    /** 当前选中的模型（工具条模型菜单选择）；null 或跟随默认项表示不覆盖 */
    private ModelOption selectedModel;
    /** 审批选择菜单：复用单一实例 */
    private ContextMenu approvalMenu;
    /** 审批菜单最近一次关闭的时间戳（毫秒） */
    private long approvalMenuHiddenAt;
    /** 模型选择菜单：复用单一实例 */
    private ContextMenu modelMenu;
    /** 模型菜单最近一次关闭的时间戳（毫秒） */
    private long modelMenuHiddenAt;
    /** 齿轮设置菜单：复用单一实例 */
    private ContextMenu themeMenu;
    /** 齿轮菜单最近一次关闭的时间戳（毫秒） */
    private long themeMenuHiddenAt;

    public ToolbarPickers(Button themeMenuButton, Button modelButton, Button approvalButton,
                          ProviderAdminService providerAdminService, SecurityAdminService securityAdminService,
                          ChatHost host) {
        this.themeMenuButton = themeMenuButton;
        this.modelButton = modelButton;
        this.approvalButton = approvalButton;
        this.providerAdminService = providerAdminService;
        this.securityAdminService = securityAdminService;
        this.host = host;
    }

    // ===================== 对外状态访问（供发送流程读取） =====================

    public String selectedProviderCode() {
        return selectedModel == null ? null : selectedModel.providerCode();
    }

    public String selectedModelCode() {
        return selectedModel == null ? null : selectedModel.modelCode();
    }

    public PermissionMode approvalMode() {
        return approval.mode();
    }

    public boolean approvalAutoApprove() {
        return approval.autoApprove();
    }

    /** 规划模式是否被勾选：为真时发送流程把本轮标记为只读计划轮（进入/退出包在流两端）。 */
    public boolean planModeRequested() {
        return planModeRequested;
    }

    /** 审批初始档：读设置「安全」页持久化的全局默认（未设置回退询问）。 */
    public void initApprovalFromDefault() {
        approval = approvalForDefault(securityAdminService.getApprovalDefault());
    }

    // ===================== 齿轮：设置/外观/语言/关于 =====================

    /** 底部齿轮：弹出设置菜单（设置 / 外观▶ / 语言▶ / 关于），对标 Qoder 交互 */
    public void buildThemeMenu() {
        if (themeMenu == null) {
            themeMenu = new ContextMenu();
            themeMenu.setOnHiding(e -> themeMenuHiddenAt = System.currentTimeMillis());
        } else {
            themeMenu.getItems().clear();
        }
        ContextMenu menu = themeMenu;

        MenuItem settings = new MenuItem(I18n.t("menu.settings"), UiTheme.icon("\uE713"));
        settings.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.CONTROL_DOWN));
        settings.setOnAction(e -> host.openSettings(null));

        Menu appearance = new Menu(I18n.t("menu.appearance"), UiTheme.icon("\uE790"));

        Menu modeMenu = new Menu(I18n.t("menu.mode"), UiTheme.icon("\uE706"));
        ToggleGroup modeGroup = new ToggleGroup();
        RadioMenuItem lightItem = new RadioMenuItem(I18n.t("menu.mode.light"));
        RadioMenuItem darkItem = new RadioMenuItem(I18n.t("menu.mode.dark"));
        lightItem.setToggleGroup(modeGroup);
        darkItem.setToggleGroup(modeGroup);
        (UiTheme.mode() == UiTheme.Mode.LIGHT ? lightItem : darkItem).setSelected(true);
        lightItem.setOnAction(e -> UiTheme.setMode(UiTheme.Mode.LIGHT));
        darkItem.setOnAction(e -> UiTheme.setMode(UiTheme.Mode.DARK));
        modeMenu.getItems().addAll(lightItem, darkItem);

        Menu fontMenu = new Menu(I18n.t("menu.font"), UiTheme.icon("\uE8D2"));
        ToggleGroup fontGroup = new ToggleGroup();
        RadioMenuItem smallItem = new RadioMenuItem(I18n.t("menu.font.small"));
        RadioMenuItem mediumItem = new RadioMenuItem(I18n.t("menu.font.medium"));
        RadioMenuItem largeItem = new RadioMenuItem(I18n.t("menu.font.large"));
        smallItem.setToggleGroup(fontGroup);
        mediumItem.setToggleGroup(fontGroup);
        largeItem.setToggleGroup(fontGroup);
        switch (UiTheme.fontSize()) {
            case SMALL -> smallItem.setSelected(true);
            case LARGE -> largeItem.setSelected(true);
            default -> mediumItem.setSelected(true);
        }
        smallItem.setOnAction(e -> UiTheme.setFontSize(UiTheme.FontSize.SMALL));
        mediumItem.setOnAction(e -> UiTheme.setFontSize(UiTheme.FontSize.MEDIUM));
        largeItem.setOnAction(e -> UiTheme.setFontSize(UiTheme.FontSize.LARGE));
        fontMenu.getItems().addAll(smallItem, mediumItem, largeItem);

        appearance.getItems().addAll(modeMenu, fontMenu);

        Menu langMenu = new Menu(I18n.t("menu.language"), UiTheme.icon("\uF2B7"));
        ToggleGroup langGroup = new ToggleGroup();
        for (I18n.Language lang : I18n.Language.values()) {
            RadioMenuItem item = new RadioMenuItem(lang.nativeName());
            item.setToggleGroup(langGroup);
            if (lang == I18n.current()) {
                item.setSelected(true);
            }
            item.setOnAction(e -> I18n.setLanguage(lang));
            langMenu.getItems().add(item);
        }

        MenuItem about = new MenuItem(I18n.t("menu.about"), UiTheme.icon("\uE946"));
        about.setOnAction(e -> host.showAbout());

        menu.getItems().addAll(settings, new SeparatorMenuItem(),
                appearance, langMenu, new SeparatorMenuItem(), about);
        // 左键/右键统一弹出菜单，固定锚定在按钮左上角；超出屏幕时 JavaFX 自动调整
        themeMenuButton.setOnAction(e -> showThemeMenu());
        themeMenuButton.setOnContextMenuRequested(e -> {
            e.consume();
            showThemeMenu();
        });

        // 全局快捷键 Ctrl+,（菜单未展开时也生效）
        Platform.runLater(() -> {
            Scene scene = host.currentScene();
            if (scene != null) {
                scene.getAccelerators().put(
                        new KeyCodeCombination(KeyCode.COMMA, KeyCombination.CONTROL_DOWN),
                        () -> host.openSettings(null));
            }
        });
    }

    /** 弹出齿轮设置菜单：开关切换 + 防重复点击 */
    private void showThemeMenu() {
        if (themeMenu == null) {
            return;
        }
        if (themeMenu.isShowing()) {
            themeMenu.hide();  // 已弹出再点按钮：收起，做成开关切换
            return;
        }
        // 弹窗在本次按钮点击前已被系统自动收起：视为「关闭」手势，不要立刻重开
        if (MenuPopup.inHideDebounce(themeMenuHiddenAt)) {
            return;
        }
        javafx.geometry.Bounds b = themeMenuButton.localToScreen(themeMenuButton.getBoundsInLocal());
        if (b == null) {
            return;
        }
        // 先临时弹出以量取真实尺寸，再把菜单右上角精确对齐按钮右上角（左上角视图）
        themeMenu.show(themeMenuButton, b.getMaxX(), b.getMinY());
        Parent skin = (Parent) themeMenu.getSkin().getNode();
        skin.applyCss();
        skin.layout();
        double w = skin.getBoundsInLocal().getWidth();
        double h = skin.getBoundsInLocal().getHeight();
        if (w > 0) {
            themeMenu.setX(b.getMaxX() - w);
        }
        if (h > 0) {
            themeMenu.setY(b.getMinY() - h);
        }
    }

    // ===================== 模型选择 =====================

    /** 模型菜单可选项：首项「跟随默认」（不覆盖），其余为已启用且连通测试可用的模型；文案随语言解析。 */
    private List<ModelOption> modelOptions() {
        List<ModelOption> out = new ArrayList<>();
        out.add(new ModelOption(null, null, I18n.t("model.followDefault"),
                "\uEC92", I18n.t("model.followDefault.desc"), false));
        Map<String, String> providerNames = new LinkedHashMap<>();
        for (ModelProviderConfig p : providerAdminService.listProviders()) {
            providerNames.put(p.getProviderCode(), p.getProviderName());
        }
        for (ModelConfig m : providerAdminService.listModels()) {
            if (!EnabledStatus.isEnabled(m.getEnabled())) {
                continue;
            }
            // 只列可用模型：未测试/不可用的先去设置里测通连接再来选，避免选了打不通的模型
            if (!ProviderAdminService.MODEL_STATUS_VALID.equalsIgnoreCase(m.getStatus())) {
                continue;
            }
            if (m.getProviderCode() == null || m.getProviderCode().isBlank()) {
                continue;
            }
            String label = m.getModelName() == null || m.getModelName().isBlank()
                    ? m.getModelCode() : m.getModelName();
            String provider = providerNames.get(m.getProviderCode());
            String desc = provider == null || provider.isBlank() ? m.getProviderCode() : provider;
            out.add(new ModelOption(m.getProviderCode(), m.getModelCode(), label, "\uE99A", desc,
                    Integer.valueOf(1).equals(m.getSupportsVision())));
        }
        return out;
    }

    /** 模型按钮闭合态：图标 + 模型名 + 小箭头。 */
    public void renderModelButton() {
        if (modelButton == null) {
            return;
        }
        List<ModelOption> options = modelOptions();
        String keepCode = selectedModel == null ? null : selectedModel.modelCode();
        selectedModel = options.stream()
                .filter(o -> keepCode == null ? o.followDefault() : keepCode.equals(o.modelCode()))
                .findFirst().orElse(options.get(0));
        Label icon = UiTheme.icon(selectedModel.icon());
        icon.getStyleClass().add("approval-icon");
        Label title = new Label(selectedModel.label());
        title.getStyleClass().add("approval-title-compact");
        title.setMaxWidth(180);
        title.setEllipsisString("\u2026");
        Label caret = UiTheme.icon("\uE70D");
        caret.getStyleClass().add("approval-caret");
        HBox box = selectedModel.vision()
                ? new HBox(6, icon, title, visionMark(), caret)
                : new HBox(6, icon, title, caret);
        box.setAlignment(Pos.CENTER_LEFT);
        modelButton.setGraphic(box);
        modelButton.setText("");
    }

    /** 点模型按钮：向上弹出模型选择卡（图标 + 名称/提供商两行，当前项右侧带勾，底部「模型管理」入口）。 */
    public void showModelMenu() {
        if (modelMenu == null) {
            modelMenu = new ContextMenu();
            modelMenu.getStyleClass().add("rich-menu");
            modelMenu.setOnHiding(e -> modelMenuHiddenAt = System.currentTimeMillis());
        }
        if (modelMenu.isShowing()) {
            modelMenu.hide();
            return;
        }
        if (MenuPopup.inHideDebounce(modelMenuHiddenAt)) {
            return;
        }
        List<ModelOption> options = modelOptions();
        modelMenu.getItems().setAll(
                options.stream().map(opt -> modelMenuItem(opt, opt.equals(selectedModel))).toList());
        modelMenu.getItems().add(new SeparatorMenuItem());
        // 「模型管理」也画进 graphic（MenuItem 不设 text），避免 text 列拉高 maxLabelWidth 把上方富行的勾挤离右缘
        Label manageIcon = UiTheme.icon("\uE713");
        manageIcon.getStyleClass().add("approval-icon");
        Label manageText = new Label(I18n.t("model.manage"));
        HBox manageBox = new HBox(10, manageIcon, manageText);
        manageBox.setAlignment(Pos.CENTER_LEFT);
        manageBox.setMinWidth(200);
        manageBox.setPrefWidth(200);
        manageBox.setMaxWidth(200);
        MenuItem manage = new MenuItem();
        manage.setGraphic(manageBox);
        manage.getStyleClass().add("model-manage");
        manage.setOnAction(e -> host.openSettings(I18n.t("settings.menu.models")));
        modelMenu.getItems().add(manage);
        MenuPopup.showAbove(modelMenu, modelButton);
    }

    /** 模型菜单项：图标 + 名称/提供商两行，支持多模态的名称右侧加图片小标，当前选中右侧带勾。 */
    private MenuItem modelMenuItem(ModelOption opt, boolean checked) {
        Label icon = UiTheme.icon(opt.icon());
        icon.getStyleClass().add("approval-icon");
        Label title = new Label(opt.label());
        title.getStyleClass().add("approval-title");
        Label desc = new Label(opt.desc());
        desc.getStyleClass().add("approval-desc");
        // 说明保持单行（不换行）；超长时由 StackPane 布局压缩截断，不挤压右侧勾选
        return twoLineMenuItem(icon, title, opt.vision() ? visionMark() : null, desc, checked, 200, () -> {
            selectedModel = opt;
            renderModelButton();
        });
    }

    /** 多模态小图标：E8B9 图片字形（蓝色），紧跟模型名称右侧，悬停提示「支持」。 */
    private Label visionMark() {
        Label mark = UiTheme.icon("\uE8B9");
        mark.getStyleClass().add("model-vision");
        mark.setMinWidth(Region.USE_PREF_SIZE);
        mark.setTooltip(new Tooltip(I18n.t("settings.model.vision.supported")));
        return mark;
    }

    // ===================== 审批档位 =====================

    /** 审批三档：询问(ACCEPT_EDITS)/自动通过(ACCEPT_EDITS+autoApprove)/只读(EXPLORE)；文案随语言解析。 */
    private List<ApprovalOption> approvalOptions() {
        return List.of(
                new ApprovalOption(PermissionMode.ACCEPT_EDITS, false, I18n.t("approval.ask"),
                        "\uEA18", I18n.t("approval.ask.desc"), false),
                new ApprovalOption(PermissionMode.ACCEPT_EDITS, true, I18n.t("approval.auto"),
                        "\uE946", I18n.t("approval.auto.desc"), false),
                new ApprovalOption(PermissionMode.EXPLORE, false, I18n.t("approval.readonly"),
                        "\uE7BA", I18n.t("approval.readonly.desc"), false));
    }

    /** 按持久化的默认档字符串取对应选项；未知值回退首项（询问）。 */
    private ApprovalOption approvalForDefault(String mode) {
        List<ApprovalOption> options = approvalOptions();
        int index = switch (mode == null ? "" : mode) {
            case SecurityAdminService.APPROVAL_AUTO -> 1;
            case SecurityAdminService.APPROVAL_READONLY -> 2;
            default -> 0;
        };
        return options.get(Math.min(index, options.size() - 1));
    }

    /** 当前选择对应的选项对象（档位切换后旧对象不在新列表里，按字段匹配）。 */
    private ApprovalOption currentApproval(ApprovalOption fallback) {
        return approvalOptions().stream()
                .filter(o -> o.equalsOption(approval))
                .findFirst()
                .orElse(fallback);
    }

    /** 审批按钮闭合态：图标 + 标题 + 小箭头；危险档图标与标题标红。 */
    public void renderApprovalButton() {
        if (approvalButton == null) {
            return;
        }
        ApprovalOption opt = currentApproval(approvalOptions().get(0));
        Label icon = UiTheme.icon(opt.icon());
        icon.getStyleClass().add("approval-icon");
        Label title = new Label(opt.label());
        title.getStyleClass().add("approval-title-compact");
        if (opt.danger()) {
            icon.getStyleClass().add("approval-icon-danger");
            title.getStyleClass().add("approval-title-danger");
        }
        Label caret = UiTheme.icon("\uE70D");
        caret.getStyleClass().add("approval-caret");
        HBox box = new HBox(6, icon, title, caret);
        box.setAlignment(Pos.CENTER_LEFT);
        approvalButton.setGraphic(box);
        approvalButton.setText("");
    }

    /** 点审批按钮：向上弹出右键菜单式的选择卡（图标 + 标题/说明，当前档右侧带勾）。 */
    public void showApprovalMenu() {
        if (approvalMenu == null) {
            approvalMenu = new ContextMenu();
            approvalMenu.getStyleClass().add("rich-menu");
            // 无论自动还是主动 hide，都记下关闭时刻
            approvalMenu.setOnHiding(e -> approvalMenuHiddenAt = System.currentTimeMillis());
        }
        if (approvalMenu.isShowing()) {
            approvalMenu.hide();   // 已弹出再点按钮：收起，做成开关切换
            return;
        }
        // 弹窗在本次按钮点击前已被系统自动收起（isShowing 已为 false）：视为「关闭」手势，不要立刻重开
        if (MenuPopup.inHideDebounce(approvalMenuHiddenAt)) {
            return;
        }
        // 每次重灌选项：保证「当前档打勾」与语言随点随新
        approvalMenu.getItems().setAll(
                approvalOptions().stream()
                        .map(opt -> approvalMenuItem(opt, opt.equalsOption(approval)))
                        .toList());
        // 规划模式：独立于三档审批的开关项，勾选后下一条消息先跑只读计划轮
        approvalMenu.getItems().add(new SeparatorMenuItem());
        CheckMenuItem planItem = new CheckMenuItem(I18n.t("approval.planMode"));
        planItem.setSelected(planModeRequested);
        Label planTitle = new Label(I18n.t("approval.planMode"));
        planTitle.getStyleClass().add("approval-title");
        Label planDesc = new Label(I18n.t("approval.planMode.desc"));
        planDesc.getStyleClass().add("approval-desc");
        planDesc.setMaxWidth(360 - 74);
        planDesc.setEllipsisString("\u2026");
        Label planIcon = UiTheme.icon("\uE9D2");
        planIcon.getStyleClass().add("approval-icon");
        VBox planCol = new VBox(2, planTitle, planDesc);
        planCol.setAlignment(Pos.CENTER_LEFT);
        HBox planRow = new HBox(10, planIcon, planCol);
        planRow.setAlignment(Pos.CENTER_LEFT);
        planItem.setGraphic(planRow);
        planItem.setText(""); // 文案交给 graphic，避免与 CheckMenuItem 左侧勾位冲突
        planItem.setOnAction(e -> planModeRequested = planItem.isSelected());
        approvalMenu.getItems().add(planItem);
        MenuPopup.showAbove(approvalMenu, approvalButton);
    }

    /** 审批菜单项：图标 + 标题/说明两行，勾选固定右侧独立对齐；危险项标红。 */
    private MenuItem approvalMenuItem(ApprovalOption opt, boolean checked) {
        Label icon = UiTheme.icon(opt.icon());
        icon.getStyleClass().add("approval-icon");
        Label title = new Label(opt.label());
        title.getStyleClass().add("approval-title");
        Label desc = new Label(opt.desc());
        desc.getStyleClass().add("approval-desc");
        // 说明保持单行（不换行）；勾选由 twoLineMenuItem 固定在右侧，文字超长先截断不顶勾
        if (opt.danger()) {
            icon.getStyleClass().add("approval-icon-danger");
            title.getStyleClass().add("approval-title-danger");
        }
        return twoLineMenuItem(icon, title, desc, checked, 360, () -> {
            approval = opt;
            renderApprovalButton();
        });
    }

    // ===================== 菜单布局工具 =====================

    /** 两行富菜单项通用布局（无标题尾随节点）。 */
    private MenuItem twoLineMenuItem(Label icon, Label title, Label desc, boolean checked, double width,
                                     Runnable onPick) {
        return twoLineMenuItem(icon, title, null, desc, checked, width, onPick);
    }

    /** 两行富菜单项通用布局：图标 + 标题/说明占满左侧，勾选独立叠层固定右对齐，与文字不共享宽度。
     * {@code titleExtra} 非空时紧跟标题右侧同行（如多模态小标），超长时先截标题、尾随不缩。 */
    private MenuItem twoLineMenuItem(Label icon, Label title, Node titleExtra, Label desc, boolean checked, double width,
                                     Runnable onPick) {
        // 标题/说明钉最大宽度并启用省略号：超长先截断，不把行撑宽、不顶勾
        title.setMaxWidth(width - 74);
        title.setEllipsisString("\u2026");
        desc.setMaxWidth(width - 74);
        desc.setEllipsisString("\u2026");
        Node firstLine = title;
        if (titleExtra != null) {
            // 尾随图标不参与压缩：允许标题压到 0 宽先截断，保证小标始终紧跟名称且不顶勾
            title.setMinWidth(0);
            HBox titleRow = new HBox(6, title, titleExtra);
            titleRow.setAlignment(Pos.CENTER_LEFT);
            titleRow.setMaxWidth(width - 74);
            firstLine = titleRow;
        }
        VBox col = new VBox(2, firstLine, desc);
        col.setAlignment(Pos.CENTER_LEFT);
        col.setPadding(new Insets(0, 26, 0, 0)); // 右侧预留勾选位
        Label check = new Label("\u2713");
        check.getStyleClass().add("approval-check");
        check.setVisible(checked);
        check.setManaged(checked);
        StackPane textZone = new StackPane(col, check);
        StackPane.setAlignment(col, Pos.CENTER_LEFT);    // 文字钉左，撑满后不被居中右移
        StackPane.setAlignment(check, Pos.CENTER_RIGHT); // 勾选贴行右缘
        HBox.setHgrow(textZone, Priority.ALWAYS);        // textZone 撑满整行，把勾推到右缘（行已钉宽，不会被长内容撑开）
        HBox row = new HBox(10, icon, textZone);
        row.setAlignment(Pos.CENTER_LEFT);
        // min=pref=max 钉死宽度：所有行等宽，勾才能统一贴到菜单右缘（仅设 prefWidth 会被长内容撑开）
        row.setMinWidth(width);
        row.setPrefWidth(width);
        row.setMaxWidth(width);
        row.getStyleClass().add("approval-menu");
        MenuItem item = new MenuItem();
        item.setGraphic(row);
        item.setOnAction(e -> onPick.run());
        return item;
    }

    /** 模型菜单条目：providerCode/modelCode 同时为空表示「跟随默认」（不覆盖）。 */
    private record ModelOption(String providerCode, String modelCode, String label, String icon, String desc,
                               boolean vision) {
        boolean followDefault() {
            return providerCode == null;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 审批下拉条目：映射到 {@link PermissionMode}，携带图标、说明与危险标记。 */
    private record ApprovalOption(PermissionMode mode, boolean autoApprove, String label, String icon, String desc,
                                  boolean danger) {

        /** 档位同一性：权限模式 + 自动通过标志（询问/自动同为 ACCEPT_EDITS，靠 autoApprove 区分）。 */
        boolean equalsOption(ApprovalOption other) {
            return other != null && mode == other.mode && autoApprove == other.autoApprove;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
