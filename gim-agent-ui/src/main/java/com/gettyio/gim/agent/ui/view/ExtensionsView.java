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

package com.gettyio.gim.agent.ui.view;

import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.config.McpAdminService;
import com.gettyio.gim.agent.ui.market.MarketService;
import javafx.geometry.Insets;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.StackPane;

/**
 * 拓展整页：内容区左上角三个 Tab —— 「技能」「知识」与「MCP 服务」。
 * <p>技能 Tab 复用 {@link SkillsView}，知识 Tab 复用 {@link KnowledgeView}，MCP Tab 复用自包含组件
 * {@link McpView}（与「设置」中的 MCP 服务共用同一实现，各自独立实例）。语言切换时刷新 Tab 文案。</p>
 *
 * @author gogym
 */
public class ExtensionsView {

    private final SkillsView skillsView;
    private final KnowledgeView knowledgeView;
    private final McpAdminService mcpAdminService;
    /** 在线市场服务：MCP Tab 内的「市场」入口复用 */
    private final MarketService marketService;

    private final StackPane root = new StackPane();
    private final TabPane tabPane = new TabPane();
    private final Tab skillsTab = new Tab();
    private final Tab knowledgeTab = new Tab();
    private final Tab mcpTab = new Tab();
    private McpView mcpView;
    private boolean built;

    /** 装配拓展页（Tab 内容惰性构建）：绑定子视图与服务，注册语言切换刷新。 */
    public ExtensionsView(SkillsView skillsView, KnowledgeView knowledgeView,
                          McpAdminService mcpAdminService, MarketService marketService) {
        this.skillsView = skillsView;
        this.knowledgeView = knowledgeView;
        this.mcpAdminService = mcpAdminService;
        this.marketService = marketService;
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
        // MCP 视图首次访问时惰性构建
        mcpView = new McpView(mcpAdminService, marketService);
        // 拓展页内隐藏一级标题（与 Tab 名重复）；设置页的 MCP 仍保留标题
        skillsView.showHeaderTitle(false);
        knowledgeView.showHeaderTitle(false);
        mcpView.showHeaderTitle(false);

        skillsTab.setClosable(false);
        knowledgeTab.setClosable(false);
        mcpTab.setClosable(false);
        skillsTab.setContent(skillsView.getNode());
        knowledgeTab.setContent(knowledgeView.getNode());
        mcpTab.setContent(mcpView.getNode());
        tabPane.getTabs().setAll(skillsTab, knowledgeTab, mcpTab);
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
        knowledgeTab.setText(I18n.t("knowledge.title"));
        mcpTab.setText(I18n.t("settings.menu.mcp"));
    }

    /** 切页时刷新当前选中 Tab 的内容。 */
    public void reload() {
        ensureBuilt();
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        if (selected == mcpTab) {
            mcpView.reload();
        } else if (selected == knowledgeTab) {
            knowledgeView.showHeaderTitle(false);
            knowledgeView.reload();
        } else {
            // 设置页可能把共享单例的一级标题改回显示，Tab 内需重新隐去（与 Tab 名重复）
            skillsView.showHeaderTitle(false);
            skillsView.reload();
        }
    }
}
