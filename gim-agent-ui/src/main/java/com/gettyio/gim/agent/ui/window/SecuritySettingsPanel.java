package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.entity.ToolPermissionRule;
import com.gettyio.gim.agent.tools.guard.DangerousRule;
import com.gettyio.gim.agent.tools.sandbox.DockerAvailabilityProbe;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.config.SecurityAdminService;
import com.gettyio.gim.agent.ui.control.ToggleSwitch;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 安全设置子面板：工具防护（审批档位 + 危险命令黑名单 + Shell 程序白名单 + Docker 沙箱）、
 * 文件防护（授权目录）、授权管理（逐条撤销 HITL「总是允许」）。
 * <p>原「账号与登录」页内 Tab 已提升为独立的 {@link AccountSettingsPanel}（账号身份管理与
 * 安全管控不同类）；原「联网」Tab 已并入 {@link NetworkSettingsPanel}（联网服务与网络代理同域）。
 * 本面板为从 {@link SettingsWindow} 抽出的独立子面板，持有本页专属的动态容器字段与
 * {@link SecurityAdminService}；共享布局走 {@link SettingsLayout}，弹窗归属/提示/整表刷新
 * 回调宿主 {@link SettingsWindow}。宿主在语言切换重建时复用同一实例，容器内容随之重绘。</p>
 */
final class SecuritySettingsPanel {

    private final SettingsWindow host;
    private final SecurityAdminService securityAdminService;
    private final GimPaths gimPaths;
    /** 执行路径同源的 Docker 握手探针（带 TTL 缓存）：初始状态检测复用其结果，避免重复付出进程成本。 */
    private final DockerAvailabilityProbe dockerAvailabilityProbe;

    /** 安全页动态容器：危险命令规则 / Shell 白名单 / 文件授权目录 / 授权管理，reloadSecurity 时重绘。 */
    private final VBox dangerousRulesBox = new VBox(8);
    private final VBox shellAllowBox = new VBox(0);
    private final VBox pathRootsBox = new VBox(0);
    private final VBox grantBox = new VBox(0);

    SecuritySettingsPanel(SettingsWindow host, SecurityAdminService securityAdminService, GimPaths gimPaths,
                          DockerAvailabilityProbe dockerAvailabilityProbe) {
        this.host = host;
        this.securityAdminService = securityAdminService;
        this.gimPaths = gimPaths;
        this.dockerAvailabilityProbe = dockerAvailabilityProbe == null
                ? DockerAvailabilityProbe.onPath() : dockerAvailabilityProbe;
    }

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.security"), I18n.t("settings.security.subtitle"));

        ToggleGroup subGroup = new ToggleGroup();
        HBox tabBar = new HBox(4);
        tabBar.getStyleClass().add("settings-subtabs");
        StackPane subContent = new StackPane();
        subContent.getStyleClass().add("settings-subtab-content");

        addSubTab(subGroup, tabBar, subContent, I18n.t("settings.security.tab.tools"), buildToolGuardTab());
        addSubTab(subGroup, tabBar, subContent, I18n.t("settings.security.tab.file"), buildFileGuardTab());
        addSubTab(subGroup, tabBar, subContent, I18n.t("settings.security.tab.grant"), buildGrantTab());
        if (!subGroup.getToggles().isEmpty()) {
            ((ToggleButton) subGroup.getToggles().get(0)).setSelected(true);
        }

        box.getChildren().addAll(tabBar, subContent);
        return box;
    }

    /** 页内子 Tab：选中即把对应面板换进内容区（自绘头部，避免 TabPane 在 ScrollPane 内的高度塌陷）。 */
    private void addSubTab(ToggleGroup group, HBox bar, StackPane content, String label, Node pane) {
        ToggleButton tab = new ToggleButton(label);
        tab.setToggleGroup(group);
        tab.getStyleClass().add("settings-subtab");
        tab.setFocusTraversable(false);
        tab.selectedProperty().addListener((obs, o, sel) -> {
            if (sel) {
                content.getChildren().setAll(pane);
            }
        });
        bar.getChildren().add(tab);
    }

    /** 工具防护 Tab：审批档位默认 + 危险命令黑名单 + Shell 程序白名单。 */
    private VBox buildToolGuardTab() {
        VBox tab = new VBox(16);
        tab.getStyleClass().add("settings-subtab-page");
        tab.setMaxWidth(Double.MAX_VALUE);

        String[] opts = {I18n.t("approval.ask"), I18n.t("approval.auto"), I18n.t("approval.readonly")};
        ComboBox<String> approvalCombo = SettingsLayout.pillCombo(opts);
        approvalCombo.setValue(opts[approvalDefaultIndex(securityAdminService.getApprovalDefault())]);
        approvalCombo.setOnAction(e -> {
            int i = Math.max(0, approvalCombo.getSelectionModel().getSelectedIndex());
            securityAdminService.setApprovalDefault(i == 1 ? SecurityAdminService.APPROVAL_AUTO
                    : i == 2 ? SecurityAdminService.APPROVAL_READONLY : SecurityAdminService.APPROVAL_ASK);
        });
        tab.getChildren().add(SettingsLayout.groupCard(I18n.t("settings.security.approval.group"),
                List.of(SettingsLayout.settingRow(I18n.t("settings.security.approval.default"),
                        I18n.t("settings.security.approval.default.desc"), approvalCombo))));

        Button addRule = SettingsLayout.newButton(I18n.t("settings.security.rule.add"), this::handleAddDangerousRule);
        addRule.getStyleClass().add("btn-primary");
        Button resetRules = SettingsLayout.newButton(I18n.t("settings.security.rule.reset"), this::handleResetDangerousRules);
        resetRules.getStyleClass().add("link-btn");
        renderDangerousRules();
        tab.getChildren().add(SettingsLayout.sectionBlock(I18n.t("settings.security.dangerous.group"),
                I18n.t("settings.security.dangerous.desc"), List.of(resetRules, addRule), dangerousRulesBox));

        Button addProg = SettingsLayout.newButton(I18n.t("settings.security.shell.add"), this::handleAddShellProgram);
        addProg.getStyleClass().add("btn-primary");
        renderShellAllowlist();
        tab.getChildren().add(SettingsLayout.sectionBlockCentered(I18n.t("settings.security.shell.group"),
                I18n.t("settings.security.shell.desc"), List.of(addProg), shellAllowBox));
        tab.getChildren().add(buildSandboxSection());
        return tab;
    }

    /** 工具防护 Tab 内的 Docker 沙箱卡：自动通过档隔离开关 + 镜像 + 后台握手状态检测。 */
    private VBox buildSandboxSection() {
        ToggleSwitch toggle = new ToggleSwitch();
        toggle.setSelected(securityAdminService.isSandboxDockerEnabled());
        toggle.selectedProperty().addListener((obs, o, sel) -> securityAdminService.setSandboxDockerEnabled(sel));

        TextField imageField = new TextField();
        imageField.setText(securityAdminService.getSandboxImage());
        imageField.setPromptText(SecurityAdminService.SANDBOX_IMAGE_DEFAULT);
        imageField.setMaxWidth(260);
        Runnable saveImage = () -> securityAdminService.setSandboxImage(imageField.getText());
        imageField.setOnAction(e -> saveImage.run());
        imageField.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                saveImage.run();
            }
        });

        Label statusValue = new Label();
        statusValue.getStyleClass().addAll("row-value", "status-unknown");
        statusValue.setWrapText(true);
        statusValue.setMaxWidth(340);
        Button check = new Button(I18n.t("settings.security.sandbox.check"));
        check.setOnAction(e -> refreshSandboxStatus(statusValue, check, true));
        HBox statusControl = new HBox(12, statusValue, check);
        statusControl.setAlignment(Pos.CENTER_LEFT);

        VBox card = SettingsLayout.groupCard(I18n.t("settings.security.sandbox.group"), List.of(
                SettingsLayout.settingRow(I18n.t("settings.security.sandbox.enable"),
                        I18n.t("settings.security.sandbox.enable.desc"), toggle),
                SettingsLayout.settingRow(I18n.t("settings.security.sandbox.image"),
                        I18n.t("settings.security.sandbox.image.desc"), imageField),
                SettingsLayout.settingRow(I18n.t("settings.security.sandbox.status"), null, statusControl)));
        refreshSandboxStatus(statusValue, check, false);
        return card;
    }

    /**
     * 后台线程握手检测 Docker 连通性并刷新状态标签（探测绝不阻塞 UI 线程，检测期间按钮禁用防重入）。
     * 初始检查复用注入探针（与执行路径共享 TTL 缓存），手动「立即检测」用无缓存探针取即时结果。
     */
    private void refreshSandboxStatus(Label statusValue, Button checkButton, boolean fresh) {
        checkButton.setDisable(true);
        statusValue.setText(I18n.t("settings.security.sandbox.status.checking"));
        setStatusTone(statusValue, "status-unknown");
        DockerAvailabilityProbe probe = fresh
                ? DockerAvailabilityProbe.handshake(DockerAvailabilityProbe.HANDSHAKE_TIMEOUT, Duration.ZERO)
                : dockerAvailabilityProbe;
        Thread worker = new Thread(() -> {
            boolean available = probe.available();
            Platform.runLater(() -> {
                statusValue.setText(I18n.t(available
                        ? "settings.security.sandbox.status.available" : "sandbox.docker.unavailable"));
                setStatusTone(statusValue, available ? "status-ok" : "status-bad");
                checkButton.setDisable(false);
            });
        }, "sandbox-docker-check");
        worker.setDaemon(true);
        worker.start();
    }

    /** 状态标签按语义着色（绿=可用、红=不可用、灰=检测中），保留基础 row-value 样式。 */
    private static void setStatusTone(Label statusValue, String tone) {
        statusValue.getStyleClass().removeAll("status-ok", "status-bad", "status-unknown");
        statusValue.getStyleClass().add(tone);
    }

    /** 文件防护 Tab：文件授权目录（越界仅提示，写操作仍逐次确认）。 */
    private VBox buildFileGuardTab() {
        VBox tab = new VBox(16);
        tab.getStyleClass().add("settings-subtab-page");
        tab.setMaxWidth(Double.MAX_VALUE);

        Button addDir = SettingsLayout.newButton(I18n.t("settings.security.path.add"), this::handleAddPathRoot);
        addDir.getStyleClass().add("btn-primary");
        renderPathRoots();
        tab.getChildren().add(SettingsLayout.sectionBlockCentered(I18n.t("settings.security.path.group"),
                I18n.t("settings.security.path.desc"), List.of(addDir), pathRootsBox));

        Label builtin = new Label(I18n.t("settings.security.path.builtin",
                String.valueOf(gimPaths.workspaceDir())));
        builtin.getStyleClass().add("row-desc");
        builtin.setWrapText(true);
        tab.getChildren().add(builtin);
        return tab;
    }

    /** 授权管理 Tab：逐条内联撤销 HITL「总是允许」授权，另有整体「全部撤销」。 */
    private VBox buildGrantTab() {
        VBox tab = new VBox(16);
        tab.getStyleClass().add("settings-subtab-page");
        tab.setMaxWidth(Double.MAX_VALUE);

        Button clearAll = SettingsLayout.newButton(I18n.t("settings.security.clear"), this::handleClearPermissions);
        clearAll.getStyleClass().add("link-btn");
        renderGrants();
        tab.getChildren().add(SettingsLayout.sectionBlockCentered(I18n.t("settings.security.grant.group"),
                I18n.t("settings.security.hint"), List.of(clearAll), grantBox));
        return tab;
    }

    // ---- 安全页渲染 ----

    /** 危险命令黑名单：按 category 分组，逐条带严重程度徽标、描述/正则、启停开关与删除。 */
    private void renderDangerousRules() {
        dangerousRulesBox.getChildren().clear();
        List<DangerousRule> rules = securityAdminService.listDangerousRules();
        if (rules.isEmpty()) {
            dangerousRulesBox.getChildren().add(SettingsLayout.emptyHint(I18n.t("settings.security.rule.empty")));
            return;
        }
        Map<String, List<DangerousRule>> groups = new LinkedHashMap<>();
        for (DangerousRule r : rules) {
            String cat = r.category() == null || r.category().isBlank() ? "-" : r.category();
            groups.computeIfAbsent(cat, k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<DangerousRule>> g : groups.entrySet()) {
            Label gt = new Label(g.getKey() + "  " + g.getValue().size() + " 条");
            gt.getStyleClass().add("dangerous-category");
            VBox card = new VBox();
            card.getStyleClass().add("settings-group");
            List<DangerousRule> rs = g.getValue();
            for (int i = 0; i < rs.size(); i++) {
                HBox row = dangerousRuleRow(rs.get(i));
                if (i > 0) {
                    row.getStyleClass().add("settings-row-divider");
                }
                card.getChildren().add(row);
            }
            dangerousRulesBox.getChildren().addAll(gt, card);
        }
    }

    private HBox dangerousRuleRow(DangerousRule rule) {
        boolean critical = DangerousRule.SEVERITY_CRITICAL.equalsIgnoreCase(rule.severity());
        Label sev = new Label(I18n.t(critical ? "settings.security.severity.critical" : "settings.security.severity.high"));
        sev.getStyleClass().addAll("badge", critical ? "badge-red" : "badge-blue");
        sev.setMinWidth(Region.USE_PREF_SIZE);

        String desc = rule.description() == null || rule.description().isBlank() ? rule.pattern() : rule.description();
        Label title = new Label(desc);
        title.getStyleClass().add("row-title");
        Label pattern = new Label(rule.pattern());
        pattern.getStyleClass().add("row-desc");
        VBox text = new VBox(2, title, pattern);
        text.getStyleClass().add("row-text");

        ToggleSwitch toggle = new ToggleSwitch();
        toggle.setSelected(rule.enabled());
        toggle.selectedProperty().addListener((obs, o, sel) -> {
            securityAdminService.setDangerousRuleEnabled(rule.id(), sel);
        });

        Button del = SettingsLayout.iconTextButton("\uE74D", I18n.t("common.delete"), () -> {
            securityAdminService.deleteDangerousRule(rule.id());
            renderDangerousRules();
        });
        del.getStyleClass().addAll("link-btn", "link-btn-danger");
        del.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(12, sev, text, toggle, del);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        HBox.setHgrow(text, javafx.scene.layout.Priority.ALWAYS);
        return row;
    }

    private void renderShellAllowlist() {
        shellAllowBox.getChildren().clear();
        List<String> list = securityAdminService.listShellAllowlist();
        if (list.isEmpty()) {
            shellAllowBox.getChildren().add(SettingsLayout.emptyHint(I18n.t("settings.security.shell.empty")));
            return;
        }
        for (String prog : list) {
            shellAllowBox.getChildren().add(SettingsLayout.listRow(prog, null, () -> {
                securityAdminService.removeShellProgram(prog);
                renderShellAllowlist();
            }));
        }
    }

    private void renderPathRoots() {
        pathRootsBox.getChildren().clear();
        List<String> list = securityAdminService.listPathRoots();
        if (list.isEmpty()) {
            pathRootsBox.getChildren().add(SettingsLayout.emptyHint(I18n.t("settings.security.path.empty")));
            return;
        }
        for (String root : list) {
            pathRootsBox.getChildren().add(SettingsLayout.listRow(root, null, () -> {
                securityAdminService.removePathRoot(root);
                renderPathRoots();
            }));
        }
    }

    /** 授权管理：逐条列出 HITL「总是允许」规则，每行内联撤销。 */
    private void renderGrants() {
        grantBox.getChildren().clear();
        List<ToolPermissionRule> list = securityAdminService.list();
        if (list.isEmpty()) {
            grantBox.getChildren().add(SettingsLayout.emptyHint(I18n.t("settings.security.grant.empty")));
            return;
        }
        for (ToolPermissionRule r : list) {
            String scope = r.getRuleContent() == null || r.getRuleContent().isBlank()
                    ? I18n.t("settings.security.wholeTool") : r.getRuleContent();
            String label = r.getToolName() + " · " + scope + " · " + r.getDecision();
            grantBox.getChildren().add(SettingsLayout.listRow(label, null, () -> {
                securityAdminService.delete(r.getId());
                renderGrants();
            }));
        }
    }

    /** 安全页动态列表随数据刷新（宿主整表刷新时调用）。 */
    void reloadSecurity() {
        renderDangerousRules();
        renderShellAllowlist();
        renderPathRoots();
        renderGrants();
    }

    // ---- 安全页辅助 ----

    private static int approvalDefaultIndex(String mode) {
        if (SecurityAdminService.APPROVAL_AUTO.equals(mode)) {
            return 1;
        }
        if (SecurityAdminService.APPROVAL_READONLY.equals(mode)) {
            return 2;
        }
        return 0;
    }

    // ---- 安全页操作 ----

    private void handleAddDangerousRule() {
        GimDialog.Shell shell = GimDialog.shell(host.hostWindow(), I18n.t("settings.security.rule.addTitle"));
        TextField patternField = new TextField();
        patternField.setPromptText(I18n.t("settings.security.rule.pattern.prompt"));
        patternField.setMaxWidth(Double.MAX_VALUE);
        TextField descField = new TextField();
        descField.setPromptText(I18n.t("settings.security.rule.desc.prompt"));
        descField.setMaxWidth(Double.MAX_VALUE);
        VBox form = new VBox(10,
                SettingsLayout.labeledField(I18n.t("settings.security.rule.pattern"), patternField),
                SettingsLayout.labeledField(I18n.t("settings.security.rule.desc"), descField));
        form.setPrefWidth(440);

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        Button save = new Button(I18n.t("common.save"));
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            String p = patternField.getText().trim();
            if (p.isEmpty()) {
                host.notify("settings.security.rule.addTitle", I18n.t("settings.status.rulePatternEmpty"));
                return;
            }
            try {
                java.util.regex.Pattern.compile(p);
            } catch (java.util.regex.PatternSyntaxException ex) {
                host.notify("settings.security.rule.addTitle", I18n.t("settings.status.rulePatternBad", ex.getMessage()));
                return;
            }
            securityAdminService.addDangerousRule(p, descField.getText().trim());
            shell.stage().close();
            renderDangerousRules();
        });
        HBox btns = new HBox(8, cancel, save);
        btns.setAlignment(Pos.CENTER_RIGHT);
        shell.body().getChildren().addAll(form, btns);
        shell.showAndWait();
    }

    private void handleResetDangerousRules() {
        if (!GimDialog.confirm(host.hostWindow(), I18n.t("settings.security.dangerous.group"),
                I18n.t("settings.security.rule.resetConfirm"))) {
            return;
        }
        securityAdminService.resetDangerousRules();
        renderDangerousRules();
    }

    private void handleAddShellProgram() {
        Optional<String> name = GimDialog.promptText(host.hostWindow(),
                I18n.t("settings.security.shell.addTitle"),
                I18n.t("settings.security.shell.program"), "", 64);
        name.ifPresent(n -> {
            securityAdminService.addShellProgram(n);
            renderShellAllowlist();
        });
    }

    private void handleAddPathRoot() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.t("settings.security.path.choose"));
        File dir = chooser.showDialog(host.hostWindow());
        if (dir != null) {
            securityAdminService.addPathRoot(dir.getAbsolutePath());
            renderPathRoots();
        }
    }

    private void handleClearPermissions() {
        securityAdminService.clear();
        host.reloadAll();
    }

}
