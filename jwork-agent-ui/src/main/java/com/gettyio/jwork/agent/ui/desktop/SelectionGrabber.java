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

package com.gettyio.jwork.agent.ui.desktop;

import com.gettyio.jwork.agent.domain.util.OsPlatform;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;

/**
 * 划词抓取：向当前前台窗口注入 Ctrl+C，把「选中文本」复制进剪贴板后取回，随后恢复原剪贴板。
 * <p>实现要点：先把剪贴板写成一次性哨兵值，再发 Ctrl+C——若存在选区，剪贴板会被新内容替换；
 * 超时仍是哨兵（无选中或窗口不响应）则返回 null。故本类会瞬时改写系统剪贴板（读取后立即还原）。
 * 抓取须先于操作条弹出执行，此时前台窗口仍是用户正在操作的应用；再由调用方负责在后台线程使用。</p>
 * <p>仅 Windows 生效；副作用：在对 Ctrl+C 敏感的应用（如终端）中可能触发其中断行为，
 * 属划词类工具的共性妥协。</p>
 *
 * @author gogym
 */
final class SelectionGrabber {

    private static final Logger log = LoggerFactory.getLogger(SelectionGrabber.class);

    private static final int VK_CONTROL = 0x11;
    private static final int VK_C = 0x43;
    /** Ctrl+C 后等待剪贴板更新的轮询步长与总上限（毫秒） */
    private static final int POLL_STEP_MS = 30;
    private static final int POLL_TIMEOUT_MS = 420;

    private SelectionGrabber() {
    }

    /** 当前平台是否支持划词抓取（仅 Windows）。 */
    static boolean isSupported() {
        return OsPlatform.current().isWindows();
    }

    /**
     * 抓取当前选中文本；无选中、非文本内容或失败返回 null（剪贴板会恢复原状）。
     * <p>含轮询等待，须在后台线程调用。</p>
     */
    static String grab() {
        if (!isSupported()) {
            return null;
        }
        Clipboard clipboard;
        try {
            clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        } catch (RuntimeException e) {
            log.warn("系统剪贴板不可用，划词抓取跳过", e);
            return null;
        }
        Transferable backup = safeContents(clipboard);
        String marker = "\u0000jwork-sel-" + System.nanoTime();
        try {
            clipboard.setContents(new StringSelection(marker), null);
        } catch (RuntimeException e) {
            log.warn("写入剪贴板哨兵失败，划词抓取跳过", e);
            return null;
        }
        try {
            sendCtrlC();
            String text = pollSelection(clipboard, marker);
            if (text == null || text.isBlank()) {
                log.debug("未取到选中文本（无选区或窗口未响应）");
                return null;
            }
            return text;
        } finally {
            restore(clipboard, backup);
        }
    }

    /** 轮询剪贴板等待 Ctrl+C 写入的新文本；超时或内容非文本返回 null。 */
    private static String pollSelection(Clipboard clipboard, String marker) {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_STEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            Transferable t = safeContents(clipboard);
            if (t == null || !t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                continue;
            }
            try {
                Object data = t.getTransferData(DataFlavor.stringFlavor);
                String text = data == null ? null : data.toString();
                if (text != null && !text.equals(marker)) {
                    return text;
                }
            } catch (Exception e) {
                // 剪贴板正被写入（数据尚未就绪）：下一轮再试
            }
        }
        return null;
    }

    /** 还原原剪贴板内容；原本为空则写回空文本，失败仅记日志。 */
    private static void restore(Clipboard clipboard, Transferable backup) {
        try {
            clipboard.setContents(backup != null ? backup : new StringSelection(""), null);
        } catch (RuntimeException e) {
            log.debug("恢复原剪贴板失败", e);
        }
    }

    private static Transferable safeContents(Clipboard clipboard) {
        try {
            return clipboard.getContents(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** SendInput 依次注入 Ctrl↓ C↓ C↑ Ctrl↑（相邻无延迟，等效一次真实按键）。 */
    private static void sendCtrlC() {
        try {
            sendKey(VK_CONTROL, false);
            sendKey(VK_C, false);
            sendKey(VK_C, true);
            sendKey(VK_CONTROL, true);
        } catch (RuntimeException | Error e) {
            log.warn("模拟 Ctrl+C 失败", e);
        }
    }

    private static void sendKey(int vk, boolean up) {
        WinUser.INPUT input = new WinUser.INPUT();
        input.type = new WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        input.input.setType("ki");
        input.input.ki.wVk = new WinDef.WORD(vk);
        input.input.ki.wScan = new WinDef.WORD(0);
        input.input.ki.time = new WinDef.DWORD(0);
        input.input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
        input.input.ki.dwFlags = new WinDef.DWORD(up ? WinUser.KEYBDINPUT.KEYEVENTF_KEYUP : 0);
        User32.INSTANCE.SendInput(new WinDef.DWORD(1), new WinUser.INPUT[]{input}, input.size());
    }
}
