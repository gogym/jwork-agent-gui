package com.gettyio.gim.agent.ui.window;

import javafx.stage.Window;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 弹窗遮罩中心：模态对话框弹出时，给宿主主窗口叠一层「磨玻璃背景」，让对话框成为唯一视觉焦点。
 * <p>本类不画法，只路由与计数：宿主窗口自行注册开关（见 {@link AppShell}），
 * {@link #enter} 在弹窗显示前调用并返回撤除句柄，弹窗关闭时执行；同一宿主上叠多个对话框时，
 * 只有最后一个关闭才撤除遮罩。owner 为空或未注册遮罩的窗口拿到空句柄，行为与从前一致。</p>
 */
public final class FrostedBackdrop {

    /** 宿主窗口的遮罩开关：on 叠上磨玻璃，off 复原。 */
    private record Backdrop(Runnable on, Runnable off) {
    }

    private static final Map<Window, Backdrop> BACKDROPS = new WeakHashMap<>();
    private static final Map<Window, Integer> DEPTH = new HashMap<>();

    private FrostedBackdrop() {
    }

    /** 宿主窗口注册遮罩开关（重复注册以最后一次为准）。 */
    public static void register(Window window, Runnable on, Runnable off) {
        if (window != null && on != null && off != null) {
            BACKDROPS.put(window, new Backdrop(on, off));
        }
    }

    /** 弹窗显示前调用，返回关闭时执行的撤除句柄（可重复执行，幂等）。 */
    public static Runnable enter(Window window) {
        Backdrop backdrop = window == null ? null : BACKDROPS.get(window);
        if (backdrop == null) {
            return () -> {
            };
        }
        if (DEPTH.merge(window, 1, Integer::sum) == 1) {
            backdrop.on().run();
        }
        boolean[] done = {false};
        return () -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            if (DEPTH.merge(window, -1, Integer::sum) <= 0) {
                DEPTH.remove(window);
                backdrop.off().run();
            }
        };
    }
}
