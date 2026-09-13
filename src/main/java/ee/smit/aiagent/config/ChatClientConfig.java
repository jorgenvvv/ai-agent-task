package ee.smit.aiagent.config;

import ee.smit.aiagent.knowledge.KnowledgeTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;

@Configuration
public class ChatClientConfig {

    @Bean
    @Primary
    ChatClient chatClient(ChatClient.Builder builder, KnowledgeTools knowledgeTools,
                          @Value("classpath:prompts/system-prompt.md") Resource systemPrompt) {
        return builder
                .defaultSystem(systemPrompt)
                .defaultTools(knowledgeTools)
                .build();
    }

    @Bean
    @Qualifier("groundingChatClient")
    ChatClient groundingChatClient(
            ChatModel chatModel,
            @Value("classpath:prompts/grounding-judge-prompt.md") Resource judgePrompt) {
        return ChatClient.builder(chatModel)
                .defaultSystem(judgePrompt)
                .build();
    }
}
