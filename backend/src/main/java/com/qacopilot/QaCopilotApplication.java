package com.qacopilot;

import com.qacopilot.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RagProperties.class)
public class QaCopilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(QaCopilotApplication.class, args);
    }
}
