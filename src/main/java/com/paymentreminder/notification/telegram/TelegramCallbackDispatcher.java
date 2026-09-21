package com.paymentreminder.notification.telegram;

import com.paymentreminder.common.config.TelegramProperties;
import org.springframework.stereotype.Component;

/** Routes a callback query to notification actions, menu navigation or the add/edit wizard. */
@Component
public class TelegramCallbackDispatcher {

    private final TelegramCallbackHandler notificationHandler;
    private final TelegramMenuHandler menuHandler;
    private final TelegramWizardHandler wizardHandler;
    private final TelegramProperties telegramProperties;

    public TelegramCallbackDispatcher(TelegramCallbackHandler notificationHandler, TelegramMenuHandler menuHandler,
            TelegramWizardHandler wizardHandler, TelegramProperties telegramProperties) {
        this.notificationHandler = notificationHandler;
        this.menuHandler = menuHandler;
        this.wizardHandler = wizardHandler;
        this.telegramProperties = telegramProperties;
    }

    public TelegramCallbackResult handle(TelegramCallbackQuery query) {
        if (!isAllowed(query.chatId())) {
            return TelegramCallbackResult.alert("Действие недоступно");
        }
        if (TelegramCallbackData.parse(query.data()).isPresent()) {
            return notificationHandler.handle(query);
        }
        String data = query.data() == null ? "" : query.data();
        if (data.startsWith("w:")) {
            return wizardHandler.handleCallback(query);
        }
        if (data.startsWith("m:") || data.startsWith("p:")) {
            return menuHandler.handleCallback(query);
        }
        return TelegramCallbackResult.alert("Неизвестная команда");
    }

    private boolean isAllowed(Long chatId) {
        String configured = telegramProperties.chatId();
        return chatId != null && configured != null && configured.equals(Long.toString(chatId));
    }
}
