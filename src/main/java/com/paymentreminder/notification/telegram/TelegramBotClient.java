package com.paymentreminder.notification.telegram;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentreminder.common.config.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Telegram Bot API client built on {@link RestClient}. The bot token is part of the base URL and is
 * never included in error messages or logs.
 */
@Component
public class TelegramBotClient implements TelegramClient {

    private static final String BASE_URL = "https://api.telegram.org";

    private static final Logger log = LoggerFactory.getLogger(TelegramBotClient.class);

    private final TelegramProperties properties;
    private final RestClient restClient;

    public TelegramBotClient(TelegramProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = restClientBuilder.clone()
                .requestFactory(requestFactory)
                .baseUrl(BASE_URL + "/bot" + properties.botToken())
                .build();
        if (properties.isConfigured()) {
            log.info("Telegram is configured: message delivery and callback polling are enabled");
        } else {
            log.info("Telegram is not configured (TELEGRAM_BOT_TOKEN/TELEGRAM_CHAT_ID are empty): "
                    + "message delivery and callback polling are disabled");
        }
    }

    @Override
    public long sendMessage(String text, TelegramKeyboard keyboard) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", properties.chatId());
        body.put("text", text);
        if (keyboard != null) {
            body.put("reply_markup", replyMarkup(keyboard));
        }
        return call("/sendMessage", body).path("result").path("message_id").asLong();
    }

    @Override
    public void answerCallbackQuery(String callbackQueryId, String text, boolean showAlert) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("callback_query_id", callbackQueryId);
        if (text != null && !text.isBlank()) {
            body.put("text", text);
        }
        body.put("show_alert", showAlert);
        call("/answerCallbackQuery", body);
    }

    @Override
    public void clearInlineKeyboard(long chatId, int messageId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("reply_markup", Map.of("inline_keyboard", List.of()));
        call("/editMessageReplyMarkup", body);
    }

    @Override
    public void editMessageText(long chatId, int messageId, String text, TelegramKeyboard keyboard) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", text);
        body.put("reply_markup", keyboard == null
                ? Map.of("inline_keyboard", List.of())
                : replyMarkup(keyboard));
        call("/editMessageText", body);
    }

    @Override
    public List<TelegramUpdate> getUpdates(long offset, Duration timeout) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offset", offset);
        body.put("timeout", timeout.toSeconds());
        body.put("allowed_updates", List.of("callback_query", "message"));
        JsonNode result = call("/getUpdates", body).path("result");
        List<TelegramUpdate> updates = new ArrayList<>();
        for (JsonNode node : result) {
            updates.add(new TelegramUpdate(node.path("update_id").asLong(),
                    parseCallback(node.path("callback_query")), parseMessage(node.path("message"))));
        }
        return updates;
    }

    private TelegramCallbackQuery parseCallback(JsonNode callback) {
        if (callback.isMissingNode() || callback.isNull()) {
            return null;
        }
        JsonNode chatId = callback.path("message").path("chat").path("id");
        JsonNode messageId = callback.path("message").path("message_id");
        return new TelegramCallbackQuery(
                callback.path("id").asText(),
                callback.path("data").asText(null),
                chatId.isNumber() ? chatId.asLong() : null,
                messageId.isNumber() ? messageId.asInt() : null);
    }

    private TelegramMessage parseMessage(JsonNode message) {
        if (message.isMissingNode() || message.isNull() || !message.path("text").isTextual()) {
            return null;
        }
        JsonNode chatId = message.path("chat").path("id");
        JsonNode fromId = message.path("from").path("id");
        return new TelegramMessage(
                chatId.asLong(),
                message.path("message_id").asInt(),
                fromId.isNumber() ? fromId.asLong() : null,
                message.path("text").asText());
    }

    private JsonNode call(String path, Map<String, Object> body) {
        if (!properties.isConfigured()) {
            throw new TelegramDeliveryException("Telegram is not configured", false);
        }
        try {
            JsonNode response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("ok").asBoolean()) {
                throw new TelegramDeliveryException("Telegram API rejected the request", false);
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw new TelegramDeliveryException(
                    "Telegram API responded with status " + exception.getStatusCode().value(),
                    isRetryable(exception.getStatusCode().value()));
        } catch (ResourceAccessException exception) {
            if (wasDefinitelyNotSent(exception)) {
                throw new TelegramDeliveryException("Telegram is unreachable", true);
            }
            throw new TelegramUncertainException("Telegram API outcome is unknown");
        }
    }

    private Map<String, Object> replyMarkup(TelegramKeyboard keyboard) {
        List<List<Map<String, String>>> rows = keyboard.rows().stream()
                .map(row -> row.stream()
                        .map(button -> Map.of("text", button.text(), "callback_data", button.callbackData()))
                        .toList())
                .toList();
        return Map.of("inline_keyboard", rows);
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private static boolean wasDefinitelyNotSent(ResourceAccessException exception) {
        Throwable cause = exception.getCause();
        return cause instanceof ConnectException
                || cause instanceof UnknownHostException
                || cause instanceof NoRouteToHostException;
    }
}
