package com.paymentreminder.notification.telegram;

import java.time.Duration;
import java.util.List;

/**
 * Sends messages to and receives callbacks from the single configured Telegram chat.
 *
 * <p>Implementations must distinguish a known failure ({@link TelegramDeliveryException}) from an
 * uncertain outcome ({@link TelegramUncertainException}), because only the former may be retried.
 */
public interface TelegramClient {

    /**
     * @return the Telegram message id assigned to the delivered message. A {@code null} keyboard
     *     sends the message without inline buttons.
     */
    long sendMessage(String text, TelegramKeyboard keyboard);

    /** Answers a callback query, optionally showing the text as an alert. */
    void answerCallbackQuery(String callbackQueryId, String text, boolean showAlert);

    /** Best-effort removal of the inline keyboard of an already delivered message. */
    void clearInlineKeyboard(long chatId, int messageId);

    /**
     * Replaces the text and keyboard of an already delivered message. Implementations that do not
     * support menu navigation may leave the default implementation.
     */
    default void editMessageText(long chatId, int messageId, String text, TelegramKeyboard keyboard) {
        throw new UnsupportedOperationException("editMessageText is not supported");
    }

    /**
     * Long-polls for updates starting at {@code offset}; blocks server side for up to {@code timeout}.
     */
    List<TelegramUpdate> getUpdates(long offset, Duration timeout);
}
