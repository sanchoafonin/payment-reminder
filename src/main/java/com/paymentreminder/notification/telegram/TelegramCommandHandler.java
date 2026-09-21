package com.paymentreminder.notification.telegram;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.paymentreminder.common.config.TelegramProperties;
import com.paymentreminder.notification.entity.WizardStep;
import com.paymentreminder.notification.service.TelegramConversationService;
import com.paymentreminder.notification.service.TelegramConversationService.ActiveConversation;
import org.springframework.stereotype.Component;

/** Handles incoming text: slash commands and wizard answers. */
@Component
public class TelegramCommandHandler {

    private final TelegramMenuFormatter formatter;
    private final TelegramMenuHandler menuHandler;
    private final TelegramWizardHandler wizardHandler;
    private final TelegramConversationService conversations;
    private final TelegramProperties telegramProperties;

    public TelegramCommandHandler(TelegramMenuFormatter formatter, TelegramMenuHandler menuHandler,
            TelegramWizardHandler wizardHandler, TelegramConversationService conversations,
            TelegramProperties telegramProperties) {
        this.formatter = formatter;
        this.menuHandler = menuHandler;
        this.wizardHandler = wizardHandler;
        this.conversations = conversations;
        this.telegramProperties = telegramProperties;
    }

    /** Returns the screen to send, or {@code null} when the chat is not allowed to manage payments. */
    public TelegramScreen handle(TelegramMessage message) {
        if (!isAllowed(message.chatId())) {
            return null;
        }
        long chatId = message.chatId();
        String text = message.text() == null ? "" : message.text().strip();

        if (text.startsWith("/")) {
            String command = text.split("\\s+")[0].toLowerCase();
            return switch (command) {
                case "/start", "/menu" -> {
                    conversations.clear(chatId);
                    yield formatter.home();
                }
                case "/cancel" -> {
                    conversations.clear(chatId);
                    yield formatter.home();
                }
                case "/list" -> menuHandler.unpaidScreen();
                case "/add" -> {
                    conversations.clear(chatId);
                    Map<String, String> draft = new LinkedHashMap<>();
                    conversations.save(chatId, WizardStep.NAME, draft);
                    yield formatter.wizardStep(WizardStep.NAME, draft);
                }
                case "/help" -> formatter.help();
                default -> formatter.error("Неизвестная команда. Отправьте /help");
            };
        }

        Optional<ActiveConversation> active = conversations.find(chatId);
        if (active.isPresent()) {
            return wizardHandler.handleText(active.get(), message);
        }
        return formatter.home();
    }

    private boolean isAllowed(long chatId) {
        String configured = telegramProperties.chatId();
        return configured != null && configured.equals(Long.toString(chatId));
    }
}
