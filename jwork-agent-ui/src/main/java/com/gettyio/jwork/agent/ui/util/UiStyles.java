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

package com.gettyio.jwork.agent.ui.util;

/**
 * JavaFX 样式类名单源（jwork-agent.css 选择器同名）：跨视图复用的样式类不再手写字符串。
 * <p>仅收录两个以上视图共用的类；单视图私有样式仍就地字面量。FXML 侧为声明式配置，保留同名字面量。</p>
 *
 * @author gogym
 */
public final class UiStyles {

    public static final String BTN_PRIMARY = "btn-primary";
    public static final String LINK_BTN = "link-btn";
    public static final String LINK_BTN_DANGER = "link-btn-danger";
    public static final String ICON_BTN = "icon-btn";

    public static final String BADGE_GREEN = "badge-green";
    public static final String BADGE_GRAY = "badge-gray";
    public static final String STATUS_BAD = "status-bad";

    public static final String ROW_DESC = "row-desc";
    public static final String ROW_VALUE = "row-value";

    public static final String EMPTY_STATE = "empty-state";
    public static final String EMPTY_ICON = "empty-icon";
    public static final String EMPTY_TITLE = "empty-title";
    public static final String EMPTY_DESC = "empty-desc";

    public static final String CARD_META = "card-meta";
    public static final String CARD_DESC = "card-desc";
    public static final String PROVIDER_CARD = "provider-card";
    public static final String PROVIDER_CARD_TITLE = "provider-card-title";

    public static final String DIALOG_HEADER = "dialog-header";
    public static final String DIALOG_ACTIONS = "dialog-actions";
    public static final String PAGE_HEADER = "page-header";
    public static final String SETTINGS_PAGE = "settings-page";

    private UiStyles() {
    }
}