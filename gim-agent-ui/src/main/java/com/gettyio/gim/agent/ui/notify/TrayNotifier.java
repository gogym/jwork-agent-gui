package com.gettyio.gim.agent.ui.notify;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * 系统托盘通知（M5）：基于 AWT {@link SystemTray} 气球消息（Windows 原生支持），
 * 不阻塞 JavaFX 应用线程；平台不支持时 {@link #isAvailable()} 返回 false，由调用方回退到弹窗。
 */
@Component
public class TrayNotifier {

    private static final Logger log = LoggerFactory.getLogger(TrayNotifier.class);
    private static final int MAX_MESSAGE_LENGTH = 120;

    private volatile TrayIcon trayIcon;

    @PostConstruct
    public void init() {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.info("当前平台不支持系统托盘，通知将回退为窗口弹窗");
            return;
        }
        try {
            TrayIcon icon = new TrayIcon(createIconImage(), "Gim Agent");
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

    @PreDestroy
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
