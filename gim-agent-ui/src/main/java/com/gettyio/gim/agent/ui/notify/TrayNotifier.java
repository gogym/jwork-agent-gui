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

package com.gettyio.gim.agent.ui.notify;

import com.gettyio.gim.agent.ui.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * 系统托盘通知（M5）：基于 AWT {@link SystemTray} 气球消息（Windows 原生支持），
 * 不阻塞 JavaFX 应用线程；平台不支持时 {@link #isAvailable()} 返回 false，由调用方回退到弹窗。
 *
 * @author gogym
 */
public class TrayNotifier {

    private static final Logger log = LoggerFactory.getLogger(TrayNotifier.class);
    private static final int MAX_MESSAGE_LENGTH = 120;

    private volatile TrayIcon trayIcon;

    /** 初始化托盘图标（由组合根在启动期调用；平台不支持时静默降级）。 */
    public void init() {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.info("当前平台不支持系统托盘，通知将回退为窗口弹窗");
            return;
        }
        try {
            TrayIcon icon = new TrayIcon(createIconImage(), "Jwork");
            icon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(icon);
            this.trayIcon = icon;
            log.info("系统托盘已就绪");
        } catch (Exception e) {
            log.warn("系统托盘初始化失败，通知将回退为窗口弹窗", e);
        }
    }

    public boolean isAvailable() {
        return trayIcon != null;
    }

    /**
     * 为托盘图标装配「打开主窗 / 退出」右键菜单与双击唤回。
     * <p>由 {@code AppLifecycle} 在主窗就绪后调用；托盘不可用时静默。菜单文案按调用时语言取，
     * 后续语言切换不重建托盘（桌面量级可接受）。</p>
     */
    public void addMainWindowActions(Runnable onOpen, Runnable onExit) {
        TrayIcon icon = this.trayIcon;
        if (icon == null) {
            return;
        }
        java.awt.EventQueue.invokeLater(() -> {
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem(I18n.t("tray.open"));
            open.addActionListener(e -> onOpen.run());
            MenuItem quit = new MenuItem(I18n.t("tray.exit"));
            quit.addActionListener(e -> onExit.run());
            menu.add(open);
            menu.add(quit);
            icon.setPopupMenu(menu);
            icon.addMouseListener(new MouseAdapter() {
                /** 双击左键唤回主窗（Windows 托盘惯例）；其余点击忽略。 */
                @Override
                public void mouseClicked(MouseEvent e) {
                    // 双击主键：唤回主窗（Windows 托盘惯例）
                    if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1) {
                        onOpen.run();
                    }
                }
            });
        });
    }

    /** 气球通知；托盘不可用时静默（调用方负责回退） */
    public void notify(String title, String message) {
        TrayIcon icon = this.trayIcon;
        if (icon == null) {
            return;
        }
        String text = message == null ? "" : message;
        if (text.length() > MAX_MESSAGE_LENGTH) {
            text = text.substring(0, MAX_MESSAGE_LENGTH) + "…";
        }
        final String finalText = text;
        // AWT 事件派发线程发送，避免跨线程问题
        java.awt.EventQueue.invokeLater(
                () -> icon.displayMessage(title, finalText, TrayIcon.MessageType.INFO));
    }

    /** 移除托盘图标（应用退出时由组合根调用）。 */
    public void dispose() {
        TrayIcon icon = this.trayIcon;
        if (icon != null) {
            try {
                SystemTray.getSystemTray().remove(icon);
            } catch (Exception ignored) {
                // 退出阶段忽略
            }
            this.trayIcon = null;
        }
    }

    /** 生成 16x16 托盘图标：深蓝圆底 + 白色 "G" */
    private static Image createIconImage() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(37, 99, 235));
        g.fillOval(0, 0, 15, 15);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 11));
        g.drawString("G", 4, 12);
        g.dispose();
        return image;
    }
}
