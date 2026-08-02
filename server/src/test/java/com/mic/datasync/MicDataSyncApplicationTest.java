package com.mic.datasync;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用上下文启动测试。
 *
 * <p>验证 Spring Context 在没有外部数据库（未配置数据源）时也能正常启动，
 * 用于保证脚手架阶段依赖配置的正确性。</p>
 */
@SpringBootTest
class MicDataSyncApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        // 上下文已注入即说明应用可以正常启动
        assertThat(applicationContext).isNotNull();
    }
}
