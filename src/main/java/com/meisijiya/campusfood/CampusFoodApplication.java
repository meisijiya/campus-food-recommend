package com.meisijiya.campusfood;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 校园美食推荐平台后端主类。
 *
 * <p>Spring Boot 3.5.16 + JDK 21(见 ADR-0003);Spring AI 双 Profile 见 CONTEXT §10。
 *
 * @author meisijiya
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
@EnableScheduling
public class CampusFoodApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusFoodApplication.class, args);
    }
}