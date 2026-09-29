package com.gettyio.gim.agent.domain.enums;

/**
 * 启用状态，数据库中以 0/1 存储
 */
public final class EnabledStatus {

    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    private EnabledStatus() {
    }

    public static boolean isEnabled(Integer value) {
        return value != null && value == ENABLED;
    }
}
