package com.gettyio.gim.agent.ui;

import javafx.application.Preloader;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * 启动画面（Preloader）：在 Spring 容器引导（{@code init()}）与主窗 FXML 加载期间先行展示，
 * 消除冷启动「无窗口无响应」的感知。经 {@code javafx.preloader} 系统属性挂接（见 {@code Launcher}）。
 * <p>主窗 {@code start()} 就绪后框架发 {@code StateChangeNotification(BEFORE_START)}，此处自动隐藏；
 * 启动阶段文案由主应用经 {@code notifyPreloader} 推送 {@link SplashProgress} 更新（init 线程安全）。</p>
 */
public class SplashPreloader extends Preloader {

    /** 主应用 → Preloader 的启动阶段文案通知。 */
    public record SplashProgress(String text) implements PreloaderNotification {
    }

    private Stage stage;
    private Label status;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Label title = new Label("Jwork");
        title.getStyleClass().add("splash-title");
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setPrefSize(36, 36);
        status = new Label(I18n.t("splash.starting"));
        status.getStyleClass().add("splash-status");

        VBox card = new VBox(14, title, indicator, status);
        card.setAlignment(Pos.CENTER);
        card.setPrefSize(320, 170);
        card.getStyleClass().add("splash-root");
        // 外层透明边距：投影落在 Scene 内，同时不遮挡卡片圆角
        StackPane shell = new StackPane(card);
        shell.getStyleClass().add("splash-shell");

        // TRANSPARENT + 透明 Scene 填充：否则 Scene 默认白底会在四角露出直角背景，吃掉卡片圆角
        stage.initStyle(StageStyle.TRANSPARENT);
        Scene scene = new Scene(shell, Color.TRANSPARENT);
        UiTheme.apply(scene);
        stage.setScene(scene);
        stage.centerOnScreen();
        stage.show();
    }

    @Override
    public void handleApplicationNotification(PreloaderNotification n) {
        if (n instanceof SplashProgress p && status != null) {
            status.setText(p.text());
        }
    }

    @Override
    public void handleStateChangeNotification(StateChangeNotification n) {
        if (n.getType() == StateChangeNotification.Type.BEFORE_START && stage != null) {
            stage.hide();
        }
    }
}
