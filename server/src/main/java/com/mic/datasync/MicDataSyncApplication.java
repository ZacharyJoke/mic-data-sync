package com.mic.datasync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * mic-data-sync 后端服务入口。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MicDataSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(MicDataSyncApplication.class, args);
    }
}
