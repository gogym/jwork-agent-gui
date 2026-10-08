package com.gettyio.gim.agent.ui.window;

import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.config.AgentAdminService;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

import java.util.List;

/**
 * 智能体设置子面板：卡片式列出全部智能体（含内置默认），支持新增/编辑（复用 {@link AgentDialog}）与删除。
 * 抽自 {@link SettingsWindow}，持有本页专属的 {@code agentCardsBox}；渲染由宿主整表刷新驱动。
 */
final class AgentSettingsPanel {

    private final SettingsWindow host;
    private final AgentAdminService agentAdminService;
    private final FlowPane agentCardsBox = SettingsLayout.cardsBox();

    AgentSettingsPanel(SettingsWindow host, AgentAdminService agentAdminService) {
        this.host = host;
        this.agentAdminService = agentAdminService;
    }

    VBox build() {
        VBox box = new VBox();
        box.getStyleClass().add("settings-page");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);

        VBox headText = new VBox(4);
        Label title = new Label(I18n.t("settings.menu.agents"));
        title.getStyleClass().add("page-title");
        Label subtitle = new Label(I18n.t("settings.agents.subtitle"));
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setWrapText(true);
        headText.getChildren().addAll(title, subtitle);

        Button addBtn = new Button(I18n.t("settings.agent.add"));
        addBtn.getStyleClass().add("btn-primary");
        Label addIcon = new Label("\uE710");
        addIcon.getStyleClass().add("icon");
        addBtn.setGraphic(addIcon);
        addBtn.setOnAction(e -> openAgentDialog(null));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(headText, spacer, addBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        agentCardsBox.getStyleClass().add("provider-cards");
        VBox.setVgrow(agentCardsBox, Priority.ALWAYS);
        box.getChildren().addAll(header, agentCardsBox);
        return box;
    }

    /** 依据库中数据重绘智能体信息卡片（宿主整表刷新时调用）。 */
    void reload() {
        agentCardsBox.getChildren().clear();
        List<AgentDefinition> agents = agentAdminService.list();
        if (agents.isEmpty()) {
            agentCardsBox.setAlignment(Pos.CENTER);
            agentCardsBox.getChildren().add(agentEmptyState());
            return;
        }
        agentCardsBox.setAlignment(Pos.TOP_LEFT);
        for (AgentDefinition a : agents) {
            agentCardsBox.getChildren().add(agentCard(a));
        }
    }

    private VBox agentEmptyState() {
        VBox box = new VBox();
        box.getStyleClass().add("empty-state");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFillWidth(false);
        Label icon = new Label("\uE99A");
        icon.getStyleClass().add("empty-icon");
        Label t = new Label(I18n.t("settings.agent.empty"));
        t.getStyleClass().add("empty-title");
        t.setTextAlignment(TextAlignment.CENTER);
        Label d = new Label(I18n.t("settings.agent.emptyDesc"));
        d.getStyleClass().add("empty-desc");
        d.setWrapText(true);
        d.setTextAlignment(TextAlignment.CENTER);
        d.setMaxWidth(460);
        box.getChildren().addAll(icon, t, d);
        return box;
    }

    private VBox agentCard(AgentDefinition a) {
        boolean builtIn = agentAdminService.isDefault(a.getAgentCode());

        Label name = new Label(a.getAgentName() == null || a.getAgentName().isBlank()
                ? a.getAgentCode() : a.getAgentName());
        name.getStyleClass().add("provider-card-title");
        Label badge = new Label(I18n.t(builtIn ? "settings.agent.builtIn" : "settings.agent.custom"));
        badge.getStyleClass().addAll("badge", builtIn ? "badge-green" : "badge-gray");

        Button edit = SettingsLayout.iconTextButton("\uE70F", I18n.t("common.edit"), () -> openAgentDialog(a));
        edit.getStyleClass().add("link-btn");
        HBox head;
        if (builtIn) {
            // 内置默认智能体不可删：它是所有未指定智能体的会话/任务的回退目标
            head = SettingsLayout.cardHeadRow(name, badge, edit);
        } else {
            Button del = SettingsLayout.iconTextButton("\uE74D", I18n.t("common.delete"), () -> handleDeleteAgent(a));
            del.getStyleClass().addAll("link-btn", "link-btn-danger");
            head = SettingsLayout.cardHeadRow(name, badge, edit, del);
        }

        String model = a.getProviderCode() == null || a.getProviderCode().isBlank()
                ? I18n.t("settings.agent.followDefault")
                : a.getProviderCode() + " / " + a.getModelCode();
        String params = a.getTemperature() == null && a.getMaxTokens() == null
                ? I18n.t("settings.agent.followDefault")
                : I18n.t("settings.agent.paramsValue", SettingsLayout.str(a.getTemperature()), SettingsLayout.str(a.getMaxTokens()));
        VBox card = new VBox(8, head,
                SettingsLayout.kvLine(I18n.t("settings.agent.desc"), a.getDescription()),
                SettingsLayout.kvLine(I18n.t("settings.agent.prompt"), SettingsLayout.preview(a.getSystemPrompt())),
                SettingsLayout.kvLine(I18n.t("settings.agent.model"), model),
                SettingsLayout.kvLine(I18n.t("settings.agent.params"), params),
                SettingsLayout.kvLine(I18n.t("settings.agent.workspace"), a.getWorkspaceDir()),
                SettingsLayout.kvLine(I18n.t("settings.agent.project"), a.getProjectPath()),
                SettingsLayout.kvLine(I18n.t("settings.agent.skills"), capability(agentAdminService.declaredSkillNames(a.getSkillsJson()))),
                SettingsLayout.kvLine(I18n.t("settings.agent.mcp"), capability(agentAdminService.declaredMcpNames(a.getMcpServersJson()))),
                SettingsLayout.kvLine(I18n.t("settings.agent.usage"), I18n.t("settings.agent.usageValue",
                        SettingsLayout.str(agentAdminService.sessionUsage(a.getAgentCode())),
                        SettingsLayout.str(agentAdminService.taskUsage(a.getAgentCode())))));
        card.getStyleClass().add("provider-card");
        card.setPrefWidth(SettingsLayout.CARD_WIDTH);
        card.setMaxWidth(SettingsLayout.CARD_WIDTH);
        return card;
    }

    /**
     * 能力行文本：白名单语义下「没有」必须写明，留白会被误读成「默认全部可用」。
     */
    private String capability(List<String> names) {
        if (names.isEmpty()) {
            return I18n.t("settings.agent.none");
        }
        I18n.Language lang = I18n.current();
        boolean cjk = lang == I18n.Language.ZH_CN || lang == I18n.Language.ZH_TW;
        return String.join(cjk ? "、" : ", ", names);
    }

    /** 弹智能体表单（与侧栏共用 {@link AgentDialog}），保存后刷列表。 */
    private void openAgentDialog(AgentDefinition editing) {
        AgentDialog.edit(host.hostWindow(), agentAdminService, editing).ifPresent(saved -> host.reloadAll());
    }

    private void handleDeleteAgent(AgentDefinition sel) {
        String code = sel.getAgentCode();
        if (agentAdminService.isDefault(code)) {
            host.notify("settings.menu.agents", I18n.t("settings.status.defaultAgentUndeletable"));
            return;
        }
        int sessions = agentAdminService.sessionUsage(code);
        int tasks = agentAdminService.taskUsage(code);
        if (sessions > 0 || tasks > 0) {
            host.notify("settings.menu.agents", I18n.t("settings.status.agentInUse", code, SettingsLayout.str(sessions), SettingsLayout.str(tasks)));
            return;
        }
        if (!GimDialog.confirm(host.hostWindow(), I18n.t("settings.menu.agents"),
                I18n.t("settings.agent.deleteConfirm", sel.getAgentName()))) {
            return;
        }
        agentAdminService.delete(code);
        host.reloadAll();
    }
}
