package com.gettyio.gim.agent.core.resilience;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 熔断器实现（自 MVP 平移）
 * 三种状态：CLOSED（关闭）、OPEN（打开）、HALF_OPEN（半开）
 *
 * 状态转换：
 * - CLOSED → OPEN：失败次数超过阈值
 * - OPEN → HALF_OPEN：冷却时间到期
 * - HALF_OPEN → CLOSED：成功一次
 * - HALF_OPEN → OPEN：失败一次
 */
@Slf4j
public class CircuitBreaker {

    public enum State {
        CLOSED,     // 关闭状态：正常执行
        OPEN,       // 打开状态：快速失败
        HALF_OPEN   // 半开状态：允许一次试探
    }

    private final String name;
    private final int failureThreshold;
    private final Duration cooldownDuration;
    private final int halfOpenMaxAttempts;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicReference<Instant> lastFailureTime = new AtomicReference<>(Instant.now());

    public CircuitBreaker(String name, int failureThreshold, Duration cooldownDuration, int halfOpenMaxAttempts) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.cooldownDuration = cooldownDuration;
        this.halfOpenMaxAttempts = halfOpenMaxAttempts;
    }

    /**
     * 创建默认配置的熔断器
     */
    public static CircuitBreaker ofDefaults(String name) {
        return new CircuitBreaker(name, 5, Duration.ofSeconds(30), 1);
    }

    /**
     * 执行带熔断保护的操作
     */
    public <T> T execute(Callable<T> operation) throws Exception {
        checkState();

        try {
            T result = operation.call();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            throw e;
        }
    }

    /**
     * 检查当前状态是否允许执行
     */
    private void checkState() {
        State currentState = state.get();

        switch (currentState) {
            case CLOSED:
                // 允许执行
                break;

            case OPEN:
                // 检查冷却时间是否到期
                Instant lastFailure = lastFailureTime.get();
                Duration timeSinceLastFailure = Duration.between(lastFailure, Instant.now());

                if (timeSinceLastFailure.compareTo(cooldownDuration) >= 0) {
                    // 冷却时间到期，切换到半开状态
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        log.info("熔断器 {} 从 OPEN 切换到 HALF_OPEN", name);
                        successCount.set(0);
                    }
                } else {
                    // 仍在冷却期，快速失败
                    throw new CircuitBreakerOpenException(
                            String.format("熔断器 %s 处于 OPEN 状态，拒绝执行。冷却时间还剩 %d 秒",
                                    name,
                                    cooldownDuration.minus(timeSinceLastFailure).getSeconds())
                    );
                }
                break;

            case HALF_OPEN:
                // 允许执行，但限制次数
                if (successCount.get() >= halfOpenMaxAttempts) {
                    throw new CircuitBreakerOpenException(
                            String.format("熔断器 %s 处于 HALF_OPEN 状态，已达最大尝试次数", name)
                    );
                }
                break;
            default:
                break;
        }
    }

    /**
     * 操作成功时的回调
     */
    private void onSuccess() {
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            // 半开状态成功，切换到关闭状态
            if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                log.info("熔断器 {} 从 HALF_OPEN 切换到 CLOSED（恢复正常）", name);
                failureCount.set(0);
                successCount.set(0);
            }
        } else if (currentState == State.CLOSED) {
            // 关闭状态成功，重置失败计数
            failureCount.set(0);
        }
    }

    /**
     * 操作失败时的回调
     */
    private void onFailure() {
        failureCount.incrementAndGet();
        lastFailureTime.set(Instant.now());

        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            // 半开状态失败，切换回打开状态
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                log.warn("熔断器 {} 从 HALF_OPEN 切换到 OPEN（试探失败）", name);
            }
        } else if (currentState == State.CLOSED) {
            // 关闭状态，检查是否达到阈值
            if (failureCount.get() >= failureThreshold) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    log.warn("熔断器 {} 从 CLOSED 切换到 OPEN（失败次数={}）", name, failureCount.get());
                }
            }
        }
    }

    public State getState() {
        return state.get();
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    /**
     * 手动重置熔断器
     */
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        successCount.set(0);
        lastFailureTime.set(Instant.now());
        log.info("熔断器 {} 已手动重置", name);
    }

    /**
     * 手动打开熔断器
     */
    public void open() {
        state.set(State.OPEN);
        lastFailureTime.set(Instant.now());
        log.warn("熔断器 {} 已手动打开", name);
    }

    /**
     * 熔断器打开异常
     */
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }

    /**
     * 获取熔断器状态信息
     */
    public String getStatusInfo() {
        return String.format("CircuitBreaker{name='%s', state=%s, failureCount=%d, cooldownRemaining=%ds}",
                name,
                state.get(),
                failureCount.get(),
                getCooldownRemainingSeconds());
    }

    private long getCooldownRemainingSeconds() {
        if (state.get() != State.OPEN) {
            return 0;
        }
        Duration elapsed = Duration.between(lastFailureTime.get(), Instant.now());
        return Math.max(0, cooldownDuration.minus(elapsed).getSeconds());
    }
}
