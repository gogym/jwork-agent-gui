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

import com.gettyio.gim.agent.core.chat.MultiAgentGateway;
import com.gettyio.gim.agent.core.model.AgentScopeModelFactory;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.config.ProxyManager;
import com.gettyio.gim.agent.ui.config.SecurityAdminService;
import com.gettyio.gim.agent.ui.control.ToggleSwitch;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络设置子面板（独立菜单页，抽自 {@link GeneralSettingsPanel}）：全局网络代理
 * （启用开关与地址/端口/认证/绕过列表）与联网搜索服务（提供方与 API Key，自安全页
 * 「联网」Tab 并入——联网服务与网络代理同域）。
 * <p>代理失焦/回车/开关切换即保存并应用到进程；设置变化后失效模型缓存与全部运行体，
 * 下一轮对话按新出口重建（WebDAV 等按系统属性实时生效）。</p>
 *
 * @author gogym
 */
final class NetworkSettingsPanel {

    private final SettingsWindow host;
    /** 全局网络代理：代理卡片读写（保存即应用到进程）。 */
    private final ProxyManager proxyManager;
    /** 模型实例缓存：代理出口变更后清空，使下一轮对话按新传输重建模型。 */
    private final AgentScopeModelFactory modelFactory;
    /** 多智能体网关：代理出口变更后失效全部运行体，使下一轮对话按新出口重新装配。 */
    private final MultiAgentGateway agentGateway;
    /** 安全管控服务：联网搜索提供方与 API Key 的加密读写。 */
    private final SecurityAdminService securityAdminService;

    NetworkSettingsPanel(SettingsWindow host, ProxyManager proxyManager,
                         AgentScopeModelFactory modelFactory, MultiAgentGateway agentGateway,
                         SecurityAdminService securityAdminService) {
        this.host = host;
        this.proxyManager = proxyManager;
        this.modelFactory = modelFactory;
        this.agentGateway = agentGateway;
        this.securityAdminService = securityAdminService;
    }

    VBox build() {
        VBox box = SettingsLayout.page(I18n.t("settings.menu.network"), I18n.t("settings.network.subtitle"));

        ProxyManager.Settings current = proxyManager.settings();

        ToggleSwitch enabled = new ToggleSwitch();
        enabled.setSelected(current.enabled());

        TextField hostField = new TextField(current.host());
        hostField.setPromptText(I18n.t("settings.proxy.host.prompt"));
        hostField.setMaxWidth(240);
        TextField portField = new TextField(current.port() > 0 ? String.valueOf(current.port()) : "");
        portField.setPromptText(I18n.t("settings.proxy.port.prompt"));
        portField.setMaxWidth(120);
        TextField userField = new TextField(current.username());
        userField.setPromptText(I18n.t("settings.proxy.username.prompt"));
        userField.setMaxWidth(200);
        PasswordField pwdField = new PasswordField();
        pwdField.setText(current.password());
        pwdField.setMaxWidth(200);
        TextField bypassField = new TextField(current.bypass());
        bypassField.setPromptText(I18n.t("settings.proxy.bypass.prompt"));
        bypassField.setMaxWidth(320);

        Runnable save = () -> saveProxy(enabled, hostField, portField, userField, pwdField, bypassField);
        for (TextField f : List.of(hostField, portField, userField, pwdField, bypassField)) {
            f.setOnAction(e -> save.run());
            f.focusedProperty().addListener((obs, was, now) -> {
                if (!now) {
                    save.run();
                }
            });
        }
        // 开关仅在目标状态与库中不一致时触发保存：校验失败的回滚不会递归
        enabled.selectedProperty().addListener((obs, was, sel) -> {
            if (sel != proxyManager.settings().enabled()) {
                save.run();
            }
        });

        HBox authBox = new HBox(8, userField, pwdField);
        List<Node> rows = new ArrayList<>();
        rows.add(SettingsLayout.settingRow(I18n.t("settings.proxy.enabled"), I18n.t("settings.proxy.enabled.desc"), enabled));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.proxy.host"), I18n.t("settings.proxy.host.desc"), hostField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.proxy.port"), I18n.t("settings.proxy.port.desc"), portField));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.proxy.auth"), I18n.t("settings.proxy.auth.desc"), authBox));
        rows.add(SettingsLayout.settingRow(I18n.t("settings.proxy.bypass"), I18n.t("settings.proxy.bypass.desc"), bypassField));
        box.getChildren().add(SettingsLayout.groupCard(I18n.t("settings.proxy.group"), rows));
        box.getChildren().add(webSearchCard());
        return box;
    }

    /** 联网搜索卡（自安全页「联网」Tab 并入）：提供方 + 其所需配置（Tavily/博查 看 Key，SearXNG 看实例地址），供 web_search / web_fetch 实时取用。 */
    private VBox webSearchCard() {
        ComboBox<String> providerCombo = SettingsLayout.pillCombo(
                I18n.t("settings.network.web.provider.tavily"),
                I18n.t("settings.network.web.provider.bocha"),
                I18n.t("settings.network.web.provider.searxng"));
        providerCombo.setValue(providerLabel(securityAdminService.getWebSearchProvider()));

        PasswordField keyField = new PasswordField();
        keyField.setText(securityAdminService.getWebSearchApiKeyPlain());
        keyField.setPromptText(I18n.t("settings.network.web.key.prompt"));
        keyField.setMaxWidth(360);
        Runnable saveKey = () -> securityAdminService.setWebSearchApiKey(keyField.getText());
        keyField.setOnAction(e -> saveKey.run());
        keyField.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                saveKey.run();
            }
        });

        TextField instanceField = new TextField(securityAdminService.getWebSearchBaseUrl());
        instanceField.setPromptText(I18n.t("settings.network.web.baseurl.prompt"));
        instanceField.setMaxWidth(360);
        Runnable saveInstance = () -> securityAdminService.setWebSearchBaseUrl(instanceField.getText());
        instanceField.setOnAction(e -> saveInstance.run());
        instanceField.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                saveInstance.run();
            }
        });

        Node keyRow = SettingsLayout.settingRow(I18n.t("settings.network.web.key"),
                I18n.t("settings.network.web.key.desc"), keyField);
        Node baseUrlRow = SettingsLayout.settingRow(I18n.t("settings.network.web.baseurl"),
                I18n.t("settings.network.web.baseurl.desc"), instanceField);

        // 切换提供方：仅显示其所需配置（SearXNG 免 Key 只看实例地址），并回填该提供方已存的 Key
        Runnable refresh = () -> {
            boolean searxng = SecurityAdminService.WEB_PROVIDER_SEARXNG.equals(providerCode(providerCombo.getValue()));
            showRow(keyRow, !searxng);
            showRow(baseUrlRow, searxng);
        };
        providerCombo.setOnAction(e -> {
            securityAdminService.setWebSearchProvider(providerCode(providerCombo.getValue()));
            keyField.setText(securityAdminService.getWebSearchApiKeyPlain());
            refresh.run();
        });
        refresh.run();

        VBox card = SettingsLayout.groupCard(I18n.t("settings.network.web.group"), List.of(
                SettingsLayout.settingRow(I18n.t("settings.network.web.provider"),
                        I18n.t("settings.network.web.provider.desc"), providerCombo),
                keyRow, baseUrlRow));

        Label hint = new Label(I18n.t("settings.network.web.hint"));
        hint.getStyleClass().add("row-desc");
        hint.setWrapText(true);
        return new VBox(12, card, hint);
    }

    /** 行级显隐：隐藏时同时让出布局空间（不占位）。 */
    private static void showRow(Node row, boolean visible) {
        row.setVisible(visible);
        row.setManaged(visible);
    }

    /** 提供方编码 → 下拉显示名；未识别回退 Tavily。 */
    private static String providerLabel(String code) {
        if (SecurityAdminService.WEB_PROVIDER_BOCHA.equals(code)) {
            return I18n.t("settings.network.web.provider.bocha");
        }
        if (SecurityAdminService.WEB_PROVIDER_SEARXNG.equals(code)) {
            return I18n.t("settings.network.web.provider.searxng");
        }
        return I18n.t("settings.network.web.provider.tavily");
    }

    /** 下拉显示名 → 提供方编码；未识别回退 Tavily。 */
    private static String providerCode(String label) {
        if (I18n.t("settings.network.web.provider.bocha").equals(label)) {
            return SecurityAdminService.WEB_PROVIDER_BOCHA;
        }
        if (I18n.t("settings.network.web.provider.searxng").equals(label)) {
            return SecurityAdminService.WEB_PROVIDER_SEARXNG;
        }
        return SecurityAdminService.WEB_PROVIDER_TAVILY;
    }

    /** 校验并保存代理设置：启用时要求主机与 1-65535 端口齐备，否则回滚并提示；仅设置变化时失效运行体。 */
    private void saveProxy(ToggleSwitch enabled, TextField hostField, TextField portField,
                           TextField userField, PasswordField pwdField, TextField bypassField) {
        String proxyHost = hostField.getText() == null ? "" : hostField.getText().trim();
        int port = parseProxyPort(portField.getText());
        boolean on = enabled.isSelected();
        if (on && (proxyHost.isEmpty() || port <= 0)) {
            ProxyManager.Settings saved = proxyManager.settings();
            enabled.setSelected(saved.enabled());
            hostField.setText(saved.host());
            portField.setText(saved.port() > 0 ? String.valueOf(saved.port()) : "");
            host.notify("settings.proxy.group", I18n.t("settings.proxy.invalid"));
            return;
        }
        ProxyManager.Settings next = new ProxyManager.Settings(on, proxyHost, port,
                userField.getText() == null ? "" : userField.getText().trim(),
                pwdField.getText() == null ? "" : pwdField.getText(),
                bypassField.getText() == null ? "" : bypassField.getText().trim());
        if (next.equals(proxyManager.settings())) {
            return;
        }
        proxyManager.save(next);
        // 已装配的模型实例与运行体持有旧出口：失效后下一轮对话按新传输重建
        modelFactory.invalidate();
        agentGateway.invalidateAll();
    }

    /** 解析端口输入：非法或越界返回 0（视为未填）。 */
    private static int parseProxyPort(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            return port > 0 && port <= 65535 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
