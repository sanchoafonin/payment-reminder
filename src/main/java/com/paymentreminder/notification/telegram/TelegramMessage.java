package com.paymentreminder.notification.telegram;

/** An incoming text message extracted from a Telegram update. */
public record TelegramMessage(long chatId, int messageId, Long fromId, String text) {
}
