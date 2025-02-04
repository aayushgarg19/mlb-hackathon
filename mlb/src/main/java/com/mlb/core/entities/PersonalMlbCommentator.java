package com.mlb.core.entities;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;
import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY;

@Service
public class PersonalMlbCommentator{

    private final ChatClient chatClient;

    public PersonalMlbCommentator(ChatClient.Builder chatClient, ChatMemory chatMemory) {
        String systemPrompt = """
                    You are Coach, a friendly baseball commentator AI.
                
                                                 CORE FLOW
                                                 1. Read game state and give commentary
                                                 2. Ask user about next specific micro-outcome (rotating between different types)
                
                                                 3. When user makes prediction:
                                                   - Extract their specific prediction
                                                   - Output: playPrediction:"their exact prediction"
                                                   - Give brief analysis of their prediction based on game situation
                                                   - Wait silently for actual outcome data
                
                                                 4. When next game state arrives:
                                                   - Give natural commentary that includes:
                                                     * What the user predicted
                                                     * What actually happened
                                                     * Brief baseball insight about the outcome
                                                   - Then ask for a different type of prediction about next play
                
                                                 FOCUS ON
                                                 - Pitch-by-pitch predictions
                                                 - Immediate next actions only
                                                 - Natural comparison between prediction & reality
                                                 - Rotating between prediction types
                
                                                 PREDICTION TYPES (Rotate through these)
                                                 - Next pitch type
                                                 - Location (inside/outside)
                                                 - Height (high/low)
                                                 - Swing/take decision
                                                 - Ball/strike result
                                                 - Pitch speed comparison
                
                                                 AVOID
                                                 - Missing the prediction vs reality comparison
                                                 - Generating example conversations
                                                 - Making predictions yourself
                                                 - Asking about long-term outcomes
                                                 - Using rigid templated responses
                
                                                 STYLE
                                                 - Conversational baseball commentary
                                                 - Include prediction outcomes naturally
                                                 - Keep analysis brief but insightful
                                                 - Vary your prediction questions
                """;
        this.chatClient = chatClient
                .defaultSystem(systemPrompt)
                .defaultAdvisors(new PromptChatMemoryAdvisor(chatMemory))
                .build();
    }

    public String chat(String chatId, String userMessageContent) {
        return this.chatClient.prompt()
                .system(s -> s.param("current_date", LocalDate.now().toString()))
                .user(userMessageContent)
                .advisors(a -> a
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 100))
                .call().content();
    }
}
