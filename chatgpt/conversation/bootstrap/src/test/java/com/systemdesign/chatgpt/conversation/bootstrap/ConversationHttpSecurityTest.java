package com.systemdesign.chatgpt.conversation.bootstrap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@Import(ConversationHttpSecurityTest.JwtTestConfiguration.class)
class ConversationHttpSecurityTest {
    private static final Pattern CONVERSATION_ID = Pattern.compile("\\\"conversationId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void requiresAuthenticationUsesJwtSubjectAndHidesOtherUsersResources() throws Exception {
        mvc.perform(get("/v1/conversations"))
                .andExpect(status().isUnauthorized());

        String created = mvc.perform(post("/v1/conversations").with(jwt().jwt(token -> token.subject("user-1"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Matcher matcher = CONVERSATION_ID.matcher(created);
        assertThat(matcher.find()).as("create response contains conversationId").isTrue();
        String conversationId = matcher.group(1);

        mvc.perform(get("/v1/conversations").with(jwt().jwt(token -> token.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations[0].conversationId").value(conversationId))
                .andExpect(jsonPath("$.conversations[0].userId").value("user-1"));

        mvc.perform(get("/v1/conversations").with(jwt().jwt(token -> token.subject("user-2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations").isEmpty());

        mvc.perform(get("/v1/conversations/{conversationId}", conversationId)
                        .with(jwt().jwt(token -> token.subject("user-2"))))
                .andExpect(status().isNotFound());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtTestConfiguration {
        @Bean JwtDecoder jwtDecoder() {
            return token -> { throw new UnsupportedOperationException("JWT test post-processor supplies authentication directly"); };
        }
    }
}
