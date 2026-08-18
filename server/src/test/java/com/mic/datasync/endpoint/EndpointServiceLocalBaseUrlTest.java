package com.mic.datasync.endpoint;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地默认访问地址拼接规则单元测试。
 *
 * <p>应用整体挂在 context-path（默认 /mic-data-sync）下，self-sink 探活回写
 * 与批次认证兜底地址必须带上该前缀，否则批次发送会打到 404。</p>
 */
class EndpointServiceLocalBaseUrlTest {

    @Test
    void appendsContextPathWithLeadingSlash() {
        assertThat(EndpointService.localBaseUrl(19090, "/mic-data-sync"))
                .isEqualTo("http://127.0.0.1:19090/mic-data-sync");
    }

    @Test
    void normalizesContextPathWithoutLeadingSlash() {
        assertThat(EndpointService.localBaseUrl(19090, "mic-data-sync"))
                .isEqualTo("http://127.0.0.1:19090/mic-data-sync");
    }

    @Test
    void stripsTrailingSlashFromContextPath() {
        assertThat(EndpointService.localBaseUrl(19090, "/mic-data-sync/"))
                .isEqualTo("http://127.0.0.1:19090/mic-data-sync");
    }

    @Test
    void trimsBlankAroundContextPath() {
        assertThat(EndpointService.localBaseUrl(19090, " /mic-data-sync "))
                .isEqualTo("http://127.0.0.1:19090/mic-data-sync");
    }

    @Test
    void omitsSuffixWhenContextPathBlank() {
        assertThat(EndpointService.localBaseUrl(19090, "")).isEqualTo("http://127.0.0.1:19090");
        assertThat(EndpointService.localBaseUrl(19090, null)).isEqualTo("http://127.0.0.1:19090");
        assertThat(EndpointService.localBaseUrl(19090, "   ")).isEqualTo("http://127.0.0.1:19090");
    }

    @Test
    void omitsSuffixWhenContextPathIsRoot() {
        assertThat(EndpointService.localBaseUrl(19090, "/")).isEqualTo("http://127.0.0.1:19090");
    }
}
