package com.gettyio.gim.agent.ui.view;

import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.config.McpAdminService;
import javafx.geometry.Insets;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.StackPane;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 拓展整页：内容区左上角两个 Tab —— 「技能」与「MCP 服务」。
 * <p>技能 Tab 复用 {@link SkillsView}，MCP Tab 复用自包含组件 {@link McpView}
 * （与「设置」中的 MCP 服务共用同一实现，各自独立实例）。语言切换时刷新 Tab 文案。</p>
 */
@Component
public class ExtensionsView {

    @Autowired
    private SkillsView skillsView;

    @Autowired
    private McpAdminService mcpAdminService;

    private final StackPane root = new StackPane();
    private final TabPane tabPane = new TabPane();
    private final Tab skillsTab = new Tab();
    private final Tab mcpTab = new Tab();
    private McpView mcpView;
    private boolean built;

    public ExtensionsView() {
        I18n.onChange(lang -> {
            if (built) {
                applyI18n();
            }
        });
    }

    public StackPane getNode() {
        ensureBuilt();
        return root;
    }

    private void ensureBuilt() {
        if (built) {
            return;
        }
        // MCP 视图需在字段注入完成后创建，首次访问时构建
        mcpView = new McpView(mcpAdminService);
        // 拓展页内隐藏一级标题（与 Tab 名重复）；设置页的 MCP 仍保留标题
        skillsView.showHeaderTitle(false);
        mcpView.showHeaderTitle(false);

        skillsTab.setClosable(false);
        mcpTab.setClosable(false);
        skillsTab.setContent(skillsView.getNode());
        mcpTab.setContent(mcpView.getNode());
        tabPane.getTabs().setAll(skillsTab, mcpTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getStyleClass().add("extensions-tabs");

        root.getStyleClass().add("extensions-root");
        root.setPadding(new Insets(0));
        root.getChildren().add(tabPane);

        applyI18n();
        built = true;
    }

    private void applyI18n() {
        skillsTab.setText(I18n.t("skills.title"));
        mcpTab.setText(I18n.t("settings.menu.mcp"));
    }

    /** 切页时刷新当前选中 Tab 的内容。 */
    public void reload() {
        ensureBuilt();
        if (tabPane.getSelectionModel().getSelectedItem() == mcpTab) {
            mcpView.reload();
        } else {
            // 设置页可能把共享单例的一级标题改回显示，Tab 内需重新隐去（与 Tab 名重复）
            skillsView.showHeaderTitle(false);
            skillsView.reload();
        }
    }
}
