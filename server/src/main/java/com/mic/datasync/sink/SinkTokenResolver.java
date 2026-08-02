package com.mic.datasync.sink;

/**
 * Source 端访问 Sink 使用的令牌解析器。
 */
public interface SinkTokenResolver {

    String resolve();

    /** 按 Sink 端解析访问令牌；未配置该端专用令牌时回退到全局令牌。 */
    default String resolveForEndpoint(String sinkEndpointId) {
        return resolve();
    }
}
