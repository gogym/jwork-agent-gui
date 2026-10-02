package com.gettyio.gim.agent.tools.guard;

/**
 * 危险命令黑名单的单条规则：可持久化、可逐条启停，供设置「安全」页展示与管理。
 * <p>{@code pattern} 是匹配工具输入的 Java 正则；{@code severity}/{@code category} 仅为展示元数据
 * （严重程度徽标与分组标题），不参与匹配逻辑。启用即代表命中即硬拒绝（DENY），无中间档。</p>
 *
 * @param id          稳定标识，用于启停/删除定位；内置规则用语义名，自定义规则生成唯一 id
 * @param pattern     危险命令正则
 * @param description 人类可读描述（规则内容，随语言环境以文本存储，用户可自行编辑）
 * @param severity    严重程度：{@link #SEVERITY_HIGH} 或 {@link #SEVERITY_CRITICAL}
 * @param category    分组标题（如「数据破坏」「系统中断」）
 * @param enabled     是否生效
 */
public record DangerousRule(
        String id,
        String pattern,
        String description,
        String severity,
        String category,
        boolean enabled) {

    public static final String SEVERITY_HIGH = "HIGH";
    public static final String SEVERITY_CRITICAL = "CRITICAL";

    /** 返回仅切换启用态的副本（record 不可变，启停走替换）。 */
    public DangerousRule withEnabled(boolean enabled) {
        return new DangerousRule(id, pattern, description, severity, category, enabled);
    }
}
