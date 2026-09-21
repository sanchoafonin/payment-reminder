package com.paymentreminder.notification.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentreminder.notification.entity.TelegramConversation;
import com.paymentreminder.notification.entity.WizardStep;
import com.paymentreminder.notification.repository.TelegramConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores the current Telegram wizard step and its partially collected draft per chat. */
@Service
public class TelegramConversationService {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final TypeReference<Map<String, String>> DRAFT_TYPE = new TypeReference<>() {
    };

    private final TelegramConversationRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TelegramConversationService(TelegramConversationRepository repository, ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<ActiveConversation> find(long chatId) {
        Instant now = clock.instant();
        return repository.findById(chatId)
                .filter(conversation -> conversation.getUpdatedAt().isAfter(now.minus(TTL)))
                .map(conversation -> new ActiveConversation(conversation.getChatId(), conversation.getStep(),
                        read(conversation.getDraft()), conversation.getPromptMessageId()));
    }

    @Transactional
    public ActiveConversation save(long chatId, WizardStep step, Map<String, String> draft) {
        Instant now = clock.instant();
        Map<String, String> copy = new LinkedHashMap<>(draft);
        TelegramConversation conversation = repository.findById(chatId)
                .orElseGet(() -> TelegramConversation.create(chatId, step, write(copy), now));
        Integer promptMessageId = conversation.getPromptMessageId();
        conversation.update(step, write(copy), now);
        repository.save(conversation);
        return new ActiveConversation(chatId, step, copy, promptMessageId);
    }

    /** Records the prompt message of a freshly started wizard; keeps an existing message pointer. */
    @Transactional
    public void updatePromptIfMissing(long chatId, int messageId) {
        repository.findById(chatId)
                .filter(conversation -> conversation.getPromptMessageId() == null)
                .ifPresent(conversation -> {
                    conversation.setPromptMessageId(messageId);
                    repository.save(conversation);
                });
    }

    /** Repoints the prompt message after the previous one could no longer be edited. */
    @Transactional
    public void repointPrompt(long chatId, int messageId) {
        repository.findById(chatId)
                .ifPresent(conversation -> {
                    conversation.setPromptMessageId(messageId);
                    repository.save(conversation);
                });
    }

    @Transactional
    public void clear(long chatId) {
        repository.deleteById(chatId);
    }

    private Map<String, String> read(String json) {
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, DRAFT_TYPE));
        } catch (JsonProcessingException exception) {
            return new LinkedHashMap<>();
        }
    }

    private String write(Map<String, String> draft) {
        try {
            return objectMapper.writeValueAsString(draft);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize Telegram draft", exception);
        }
    }

    /** Snapshot of the active wizard: its step, the fields collected so far and its prompt message. */
    public record ActiveConversation(long chatId, WizardStep step, Map<String, String> draft,
            Integer promptMessageId) {
    }
}
