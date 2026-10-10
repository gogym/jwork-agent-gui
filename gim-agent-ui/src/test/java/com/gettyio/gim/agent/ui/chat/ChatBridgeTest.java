package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.ui.controller.ChatViewEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ChatBridge} 单测：JS 侧事件的入参兜底与转发路径——
 * 气泡动作（copy/speak/regenerate/edit）转 {@link MessageActionListener}，
 * 其余（就绪/右键/滚动/回收/链接/图片/代码/空态）转 {@link ChatViewEvents}。
 * <p>桥的构造为包私有，故测试与桥同类同包；纯接口替身，不触碰 JavaFX 工具包。</p>
 */
class ChatBridgeTest {

    private final MessageActionListener actions = mock(MessageActionListener.class);
    private final ChatViewEvents events = mock(ChatViewEvents.class);
    private final ChatBridge bridge = new ChatBridge(actions, events);

    @Test
    @DisplayName("onAction copy：非空原文转 onCopy，空/null 原文静默忽略")
    void actionCopyForwardsNonEmptyRaw() {
        bridge.onAction("copy", 7, "hello");
        bridge.onAction("copy", 7, "");
        bridge.onAction("copy", 7, null);

        verify(actions, times(1)).onCopy("hello");
    }

    @Test
    @DisplayName("onAction speak：非空白原文转 onSpeak（附结束回调），空白/null 忽略")
    void actionSpeakRequiresNonBlankRaw() {
        bridge.onAction("speak", 3, "读我");
        bridge.onAction("speak", 3, "   ");
        bridge.onAction("speak", 3, null);

        verify(actions, times(1)).onSpeak(eq("读我"), any(Runnable.class));
    }

    @Test
    @DisplayName("onAction regenerate：无条件转 onRegenerate")
    void actionRegenerateAlwaysForwards() {
        bridge.onAction("regenerate", 0, null);

        verify(actions).onRegenerate();
    }

    @Test
    @DisplayName("onAction edit：id>0 转 onEditResend，id<=0 忽略")
    void actionEditRequiresPositiveId() {
        bridge.onAction("edit", 5, "新文本");
        bridge.onAction("edit", 0, "新文本");
        bridge.onAction("edit", -1, "新文本");

        verify(actions).onEditResend(5L, "新文本");
        verify(actions, times(1)).onEditResend(anyLong(), anyString());
    }

    @Test
    @DisplayName("onAction 未知或空 kind：静默忽略，不触碰任何回调")
    void actionUnknownIgnored() {
        bridge.onAction("bogus", 1, "x");
        bridge.onAction(null, 1, "x");

        verifyNoInteractions(actions);
    }

    @Test
    @DisplayName("事件转发：就绪/右键/滚动/回收/链接/图片/代码/空态全量映射到 ChatViewEvents")
    void eventsForwarded() {
        bridge.onReady();
        verify(events).chatReady();

        bridge.onContext(3, 1.5, 2.5);
        verify(events).chatContext(3L, 1.5, 2.5);

        bridge.onScrollTop();
        verify(events).chatScrollTop();

        bridge.onScrollState(true, false);
        verify(events).chatScrollState(true, false);

        bridge.onTrimmed(42);
        verify(events).chatTrimmed(42L);

        bridge.onLink("https://example.com");
        verify(events).chatLink("https://example.com");

        bridge.onImage("C:/tmp/a.png");
        verify(events).chatImage("C:/tmp/a.png");

        bridge.onCopyCode("int a = 1;");
        verify(events).chatCopyCode("int a = 1;");

        bridge.onPreviewCode("<svg/>", "svg");
        verify(events).chatPreviewCode("<svg/>", "svg");

        bridge.onEmpty(true);
        verify(events).chatEmpty(true);
    }
}
