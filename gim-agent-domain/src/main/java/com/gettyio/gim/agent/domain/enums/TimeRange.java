package com.gettyio.gim.agent.domain.enums;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * token 用量统计的时间窗口档位。
 * <p>边界采用「自然日对齐」：{@link #LAST_7_DAYS} 表示含今天在内、往前数 7 个日历天，
 * 下界为 {@code (今天 - 6) 天 00:00:00}；{@link #ALL} 不设下界（整表）。</p>
 * <p>下界由 DB 侧用 {@code datetime()} 归一化后比较，规避 {@code LocalDateTime.toString()}
 * 变长（秒为 0 时省略）导致的裸字符串边界误判。</p>
 */
public enum TimeRange {

    /** 近 7 天（含今天，往前数 7 个自然日）。 */
    LAST_7_DAYS(7),
    /** 近 30 天（含今天，往前数 30 个自然日）。 */
    LAST_30_DAYS(30),
    /** 本自然月：自当月 1 日 00:00 起（预算告警按月对齐用）。 */
    THIS_MONTH(0) {
        @Override
        public Optional<LocalDateTime> since() {
            return Optional.of(LocalDate.now().withDayOfMonth(1).atStartOfDay());
        }
    },
    /** 全部时间，不设下界。 */
    ALL(0);

    private final int days;

    TimeRange(int days) {
        this.days = days;
    }

    /**
     * 窗口下界（含）。
     *
     * @return {@link Optional#empty()} 表示 {@link #ALL}（不加时间过滤）；否则为对应自然日 00:00
     */
    public Optional<LocalDateTime> since() {
        if (days <= 0) {
            return Optional.empty();
        }
        return Optional.of(LocalDate.now().minusDays(days - 1L).atStartOfDay());
    }
}
