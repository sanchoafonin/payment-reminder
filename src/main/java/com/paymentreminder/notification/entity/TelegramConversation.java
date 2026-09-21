package com.paymentreminder.notification.entity;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Durable Telegram wizard state, so an uncompleted add/edit survives a restart. */
@Entity
@Table(name = "telegram_conversations")
public class TelegramConversation {

    @Id
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(nullable = false, length = 32)
    private String step;

    @Column(nullable = false, columnDefinition = "text")
    private String draft;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "prompt_message_id")
    private Integer promptMessageId;

    protected TelegramConversation() {
        // Required by JPA.
    }

    public static TelegramConversation create(long chatId, WizardStep step, String draft, Instant now) {
        TelegramConversation conversation = new TelegramConversation();
        conversation.chatId = chatId;
        conversation.update(step, draft, now);
        return conversation;
    }

    public void update(WizardStep step, String draft, Instant now) {
        this.step = Objects.requireNonNull(step, "step is required").name();
        this.draft = Objects.requireNonNull(draft, "draft is required");
        this.updatedAt = Objects.requireNonNull(now, "now is required");
    }

    public Long getChatId() {
        return chatId;
    }

    public WizardStep getStep() {
        return WizardStep.valueOf(step);
    }

    public String getDraft() {
        return draft;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Integer getPromptMessageId() {
        return promptMessageId;
    }

    public void setPromptMessageId(Integer promptMessageId) {
        this.promptMessageId = promptMessageId;
    }
}
