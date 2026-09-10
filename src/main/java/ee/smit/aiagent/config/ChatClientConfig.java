package ee.smit.aiagent.config;

import ee.smit.aiagent.knowledge.KnowledgeTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration
public class ChatClientConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder, KnowledgeTools knowledgeTools,
                          @Value("classpath:prompts/system-prompt.md") Resource systemPrompt) {
        return builder
                .defaultSystem(systemPrompt)
                .defaultTools(knowledgeTools)
                .build();
    }
}
