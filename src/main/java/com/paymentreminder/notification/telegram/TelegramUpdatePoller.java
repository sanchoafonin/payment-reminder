package com.paymentreminder.notification.telegram;

import java.util.List;

import com.paymentreminder.common.config.ApplicationProperties;
import com.paymentreminder.common.config.TelegramProperties;
import com.paymentreminder.notification.service.TelegramConversationService;
import com.paymentreminder.notification.service.TelegramConversationService.ActiveConversation;
import com.paymentreminder.notification.service.TelegramPollingStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Long-polls Telegram for callback queries and messages and processes them one by one, persisting the
 * polling position after each update so a restart never drops or re-delivers the queue.
 */
@Component
public class TelegramUpdatePoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdatePoller.class);

    private final TelegramClient telegramClient;
    private final TelegramCallbackDispatcher callbackDispatcher;
    private final TelegramCommandHandler commandHandler;
    private final TelegramPollingStateService pollingStateService;
    private final TelegramConversationService conversations;
    private final TelegramProperties telegramProperties;
    private final ApplicationProperties applicationProperties;

    public TelegramUpdatePoller(TelegramClient telegramClient, TelegramCallbackDispatcher callbackDispatcher,
            TelegramCommandHandler commandHandler, TelegramPollingStateService pollingStateService,
            TelegramConversationService conversations, TelegramProperties telegramProperties,
            ApplicationProperties applicationProperties) {
        this.telegramClient = telegramClient;
        this.callbackDispatcher = callbackDispatcher;
        this.commandHandler = commandHandler;
        this.pollingStateService = pollingStateService;
        this.conversations = conversations;
        this.telegramProperties = telegramProperties;
        this.applicationProperties = applicationProperties;
    }

    @Scheduled(initialDelayString = "${app.polling-delay-ms}",
            fixedDelayString = "${app.polling-delay-ms}")
    public void poll() {
        if (!applicationProperties.pollingEnabled() || !telegramProperties.isConfigured()) {
            return;
        }
        long offset = pollingStateService.nextUpdateId();
        List<TelegramUpdate> updates;
        try {
            updates = telegramClient.getUpdates(offset, telegramProperties.pollingTimeout());
        } catch (RuntimeException exception) {
            log.warn("Telegram polling failed: {}", exception.getMessage());
            return;
        }
        for (TelegramUpdate update : updates) {
            process(update);
            pollingStateService.saveNextUpdateId(update.updateId() + 1);
        }
    }

    private void process(TelegramUpdate update) {
        if (update.callbackQuery() != null) {
            processCallback(update.callbackQuery());
        } else if (update.message() != null) {
            processMessage(update.message());
        }
    }

    private void processCallback(TelegramCallbackQuery query) {
        TelegramCallbackResult result;
        try {
            result = callbackDispatcher.handle(query);
        } catch (RuntimeException exception) {
            log.warn("Telegram callback {} failed: {}", query.id(), exception.getMessage());
            result = TelegramCallbackResult.alert("Не удалось выполнить действие");
        }
        answer(query, result);
    }

    private void answer(TelegramCallbackQuery query, TelegramCallbackResult result) {
        try {
            telegramClient.answerCallbackQuery(query.id(), result.message(), result.alert());
            if (result.clearKeyboard() && query.chatId() != null && query.messageId() != null) {
                telegramClient.clearInlineKeyboard(query.chatId(), query.messageId());
            }
            if (result.screen() != null && query.chatId() != null && query.messageId() != null) {
                telegramClient.editMessageText(query.chatId(), query.messageId(), result.screen().text(),
                        result.screen().keyboard());
                conversations.updatePromptIfMissing(query.chatId(), query.messageId());
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to answer Telegram callback {}: {}", query.id(), exception.getMessage());
        }
    }

    private void processMessage(TelegramMessage message) {
        String text = message.text() == null ? "" : message.text().strip();
        Integer promptMessageId = text.startsWith("/")
                ? null
                : conversations.find(message.chatId()).map(ActiveConversation::promptMessageId).orElse(null);

        TelegramScreen screen;
        try {
            screen = commandHandler.handle(message);
        } catch (RuntimeException exception) {
            log.warn("Telegram message from chat {} failed: {}", message.chatId(), exception.getMessage());
            return;
        }
        if (screen == null) {
            return;
        }
        if (promptMessageId != null) {
            try {
                telegramClient.editMessageText(message.chatId(), promptMessageId, screen.text(), screen.keyboard());
                return;
            } catch (RuntimeException exception) {
                log.warn("Failed to edit wizard prompt in chat {}: {}", message.chatId(), exception.getMessage());
            }
        }
        try {
            long sentMessageId = telegramClient.sendMessage(screen.text(), screen.keyboard());
            if (promptMessageId != null) {
                conversations.repointPrompt(message.chatId(), (int) sentMessageId);
            } else {
                conversations.updatePromptIfMissing(message.chatId(), (int) sentMessageId);
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to reply to Telegram chat {}: {}", message.chatId(), exception.getMessage());
        }
    }
}
