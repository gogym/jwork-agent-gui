package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.domain.entity.ChatSession;

import java.util.List;

/**
 * {@link SessionSidebar} 回调宿主的窄接口：侧栏渲染只读取会话列表/当前会话/当前智能体，
 * 并把「打开会话、刷新列表、切智能体、回到空态」等编排动作交回宿主（{@code MainController}）执行。
 * <p>窗口/场景/设置/关于等基础能力沿用 {@link ChatHost}。</p>
 */
public interface SessionSidebarHost extends ChatHost {

    /** 全部会话（已按最近更新排序，侧栏渲染时再按当前智能体过滤）。 */
    List<ChatSession> sessions();

    /** 当前打开的会话 ID，新任务空态为 null。 */
    String currentSessionId();

    /** 新建会话归属、且决定侧栏列表展示份的智能体编码（已做删除回退解析）。 */
    String currentAgentCode();

    /** 打开某会话：选中对话页并切换到该会话。 */
    void openSessionById(String sessionId);

    /** 从库重载会话并刷新侧栏。 */
    void refreshSessions();

    /** 回到新任务空态。 */
    void showBlankConversation();

    /** 切换到某智能体：写回偏好、重画卡片与会话列表。 */
    void selectAgent(String agentCode);

    /** 新建/编辑智能体后即接管它。 */
    void adoptAgent(String agentCode);

    /** 导出会话为长图（PNG）：宿主弹保存对话框并就地渲染（含成功/失败反馈）。 */
    void exportSessionImage(String sessionId);

    /** 导出会话为独立 HTML（话题卡片式、本地图片内嵌）文件。 */
    void exportSessionHtml(String sessionId);
}
