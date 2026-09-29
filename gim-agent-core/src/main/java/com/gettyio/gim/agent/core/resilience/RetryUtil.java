package com.gettyio.gim.agent.core.resilience;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * 重试机制工具类（自 MVP 平移）
 * 支持指数退避策略和自定义重试条件
 */
@Slf4j
public class RetryUtil {

    /**
     * 执行带重试的操作
     *
     * @param operation      要执行的操作
     * @param maxRetries     最大重试次数
     * @param initialDelayMs 初始延迟（毫秒）
     * @param maxDelayMs     最大延迟（毫秒）
     * @param retryOn        重试条件（返回 true 表示需要重试）
     * @param <T>            返回值类型
     * @return 操作结果
     * @throws Exception 最后一次重试仍然失败时抛出异常
     */
    public static <T> T executeWithRetry(
            Callable<T> operation,
            int maxRetries,
            long initialDelayMs,
            long maxDelayMs,
            Predicate<Exception> retryOn
    ) throws Exception {
        Exception lastException = null;
        long delay = initialDelayMs;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    log.info("重试操作: attempt={}/{}, delay={}ms", attempt, maxRetries, delay);
                    Thread.sleep(delay);
                    // 指数退避：延迟翻倍
                    delay = Math.min(delay * 2, maxDelayMs);
                }

                return operation.call();

            } catch (Exception e) {
                lastException = e;

                // 检查是否应该重试
                if (attempt < maxRetries && retryOn.test(e)) {
                    log.warn("操作失败，将重试: attempt={}/{}, error={}",
                            attempt + 1, maxRetries, e.getMessage());
                } else {
                    // 不满足重试条件或达到最大重试次数
                    if (attempt >= maxRetries) {
                        log.error("操作失败，已达最大重试次数: maxRetries={}", maxRetries);
                    }
                    throw e;
                }
            }
        }

        throw lastException;
    }

    /**
     * 执行带重试的操作（使用默认配置）
     */
    public static <T> T executeWithRetry(Callable<T> operation, int maxRetries) throws Exception {
        return executeWithRetry(
                operation,
                maxRetries,
                1000,    // 初始延迟 1 秒
                10000,   // 最大延迟 10 秒
                e -> true // 默认对所有异常重试
        );
    }

    /**
     * 执行带重试的操作（无返回值）
     */
    public static void executeWithRetryVoid(
            Runnable operation,
            int maxRetries,
            long initialDelayMs,
            long maxDelayMs,
            Predicate<Exception> retryOn
    ) throws Exception {
        executeWithRetry(
                () -> {
                    operation.run();
                    return null;
                },
                maxRetries,
                initialDelayMs,
                maxDelayMs,
                retryOn
        );
    }

    /**
     * 重试配置构建器
     */
    public static class RetryConfig {
        private int maxRetries = 3;
        private long initialDelayMs = 1000;
        private long maxDelayMs = 10000;
        private Predicate<Exception> retryOn = e -> true;

        public static RetryConfig builder() {
            return new RetryConfig();
        }

        public RetryConfig maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public RetryConfig initialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
            return this;
        }

        public RetryConfig maxDelayMs(long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
            return this;
        }

        public RetryConfig retryOn(Predicate<Exception> retryOn) {
            this.retryOn = retryOn;
            return this;
        }

        public <T> T execute(Callable<T> operation) throws Exception {
            return RetryUtil.executeWithRetry(
                    operation,
                    maxRetries,
                    initialDelayMs,
                    maxDelayMs,
                    retryOn
            );
        }

        public void executeVoid(Runnable operation) throws Exception {
            RetryUtil.executeWithRetryVoid(
                    operation,
                    maxRetries,
                    initialDelayMs,
                    maxDelayMs,
                    retryOn
            );
        }
    }
}
