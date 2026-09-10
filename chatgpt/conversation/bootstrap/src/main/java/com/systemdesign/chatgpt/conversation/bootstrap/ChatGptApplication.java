package com.systemdesign.chatgpt.conversation.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.systemdesign.chatgpt")
public class ChatGptApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatGptApplication.class, args);
    }
}
