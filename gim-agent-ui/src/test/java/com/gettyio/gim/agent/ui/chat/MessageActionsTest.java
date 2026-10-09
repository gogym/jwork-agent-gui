package com.gettyio.gim.agent.ui.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MessageActions#formatTime(LocalDateTime)} 单测：悬停操作条内消息时间的显示契约——
 * 固定为分钟精度 {@code yyyy-MM-dd HH:mm}；空值回退空串（{@code setTime} 据此隐藏时间行）。
 * 方法为纯格式化，不依赖 JavaFX 工具包。
 */
class MessageActionsTest {

    @Test
    @DisplayName("分钟精度格式：秒被截断")
    void formatTimeMinutePrecision() {
        assertEquals("2026-09-28 16:44",
                MessageActions.formatTime(LocalDateTime.of(2026, 9, 28, 16, 44, 59)));
    }

    @Test
    @DisplayName("空值：返回空串")
    void formatTimeNullIsEmpty() {
        assertEquals("", MessageActions.formatTime(null));
    }
}
