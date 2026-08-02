package com.mic.datasync.transport;

import org.springframework.stereotype.Component;

/**
 * Sink 响应分类器。
 *
 * <ul>
 *   <li>2xx → {@link Outcome#CONFIRMED}（进入确认流程）；</li>
 *   <li>明确 4xx → {@link Outcome#BUSINESS_ERROR}（业务错误，暂停）；</li>
 *   <li>连接中断、超时、响应丢失、5xx → {@link Outcome#UNKNOWN}（需查询回执）。</li>
 * </ul>
 */
@Component
public class SinkResponseClassifier {

    /** 发送结果分类。 */
    public enum Outcome {
        /** 明确成功（2xx）。 */
        CONFIRMED,
        /** 明确业务错误（4xx），需要暂停。 */
        BUSINESS_ERROR,
        /** 无法确定（连接中断/超时/5xx），需要查询回执。 */
        UNKNOWN
    }

    /** 按 HTTP 状态码分类。 */
    public Outcome classify(int httpStatus) {
        if (httpStatus >= 200 && httpStatus < 300) {
            return Outcome.CONFIRMED;
        }
        if (httpStatus >= 400 && httpStatus < 500) {
            return Outcome.BUSINESS_ERROR;
        }
        return Outcome.UNKNOWN;
    }
}
