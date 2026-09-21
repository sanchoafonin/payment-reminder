package com.paymentreminder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentreminder.payment.entity.Payment;
import com.paymentreminder.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRestControllerTests extends PostgresIntegrationTest {

    private static final String BASE = "/api/payments";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE payments RESTART IDENTITY CASCADE");
    }

    @Test
    void createReturnsCreatedPaymentSortedRemindersAndLocation() throws Exception {
        ResponseEntity<String> response = post(createBody(Map.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        JsonNode body = read(response);
        assertThat(body.get("id").asLong()).isPositive();
        assertThat(response.getHeaders().getLocation().getPath()).isEqualTo(BASE + "/" + body.get("id").asLong());
        assertThat(body.get("name").asText()).isEqualTo("Ипотека");
        assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("35300.00");
        assertThat(body.get("currency").asText()).isEqualTo("RUB");
        assertThat(body.get("recurrence").asText()).isEqualTo("MONTHLY");
        assertThat(body.get("nextPaymentDate").asText()).isEqualTo("2026-09-25");
        assertThat(body.get("active").asBoolean()).isTrue();
        assertThat(reminders(body)).containsExactly(1, 3, 5);
        assertThat(body.has("version")).isFalse();
        assertThat(body.get("createdAt").asText()).isNotBlank();
    }

    @Test
    void createWithoutRemindersReturnsEmptyList() throws Exception {
        ResponseEntity<String> response = post(createBody(Map.of("reminders", List.of())));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reminders(read(response))).isEmpty();
    }

    @Test
    void createRejectsInvalidPayloads() {
        assertThat(post(createBody(Map.of("amount", 0))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(createBody(Map.of("name", "   "))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(createBody(Map.of("currency", "GBP"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(createBody(Map.of("recurrence", "WEEKLY"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(createBody(Map.of("reminders", List.of(3, 3)))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(createBody(Map.of("reminders", List.of(-1)))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createValidationResponseContainsFieldErrors() throws Exception {
        ResponseEntity<String> response = post(createBody(Map.of("name", "")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        JsonNode body = read(response);
        assertThat(body.get("title").asText()).isEqualTo("Validation failed");
        assertThat(body.get("errors")).isNotEmpty();
    }

    @Test
    void getReturnsPaymentOrNotFound() throws Exception {
        long id = createPayment();

        ResponseEntity<String> found = rest.getForEntity(BASE + "/" + id, String.class);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(found).get("id").asLong()).isEqualTo(id);

        ResponseEntity<String> missing = rest.getForEntity(BASE + "/999999", String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(read(missing).get("title").asText()).isEqualTo("Payment not found");
    }

    @Test
    void listReturnsOnlyActivePaymentsByDefaultWithPagination() throws Exception {
        long activeId = createPayment();
        long inactiveId = createPayment();
        deactivate(inactiveId);

        ResponseEntity<String> defaultList = rest.getForEntity(BASE, String.class);
        JsonNode page = read(defaultList);
        assertThat(defaultList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.get("page").get("totalElements").asLong()).isEqualTo(1);
        assertThat(ids(page)).containsExactly(activeId);

        JsonNode all = read(rest.getForEntity(BASE + "?active=false", String.class));
        assertThat(all.get("page").get("totalElements").asLong()).isEqualTo(2);

        JsonNode firstPage = read(rest.getForEntity(BASE + "?active=false&size=1&page=0", String.class));
        assertThat(ids(firstPage)).hasSize(1);
        assertThat(firstPage.get("page").get("totalElements").asLong()).isEqualTo(2);
        assertThat(firstPage.get("page").get("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    void updateReplacesEditableFieldsAndRemindersKeepingPeriod() throws Exception {
        long id = createPayment();
        UUID periodId = paymentRepository.findById(id).orElseThrow().getCurrentPeriodId();

        Map<String, Object> request = Map.of(
                "name", "Мобильная связь",
                "amount", new BigDecimal("999.99"),
                "currency", "EUR",
                "recurrence", "MONTHLY",
                "nextPaymentDate", "2026-09-25",
                "reminders", List.of(7, 2),
                "active", true);
        ResponseEntity<String> response = put(id, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = read(response);
        assertThat(body.get("name").asText()).isEqualTo("Мобильная связь");
        assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("999.99");
        assertThat(body.get("currency").asText()).isEqualTo("EUR");
        assertThat(reminders(body)).containsExactly(2, 7);
        assertThat(paymentRepository.findById(id).orElseThrow().getCurrentPeriodId()).isEqualTo(periodId);
    }

    @Test
    void updateWithNewDateStartsNewPeriod() throws Exception {
        long id = createPayment();
        UUID periodId = paymentRepository.findById(id).orElseThrow().getCurrentPeriodId();

        Map<String, Object> request = Map.of(
                "name", "Ипотека",
                "amount", new BigDecimal("35300.00"),
                "currency", "RUB",
                "recurrence", "MONTHLY",
                "nextPaymentDate", "2026-10-25",
                "reminders", List.of(3),
                "active", true);
        ResponseEntity<String> response = put(id, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Payment payment = paymentRepository.findById(id).orElseThrow();
        assertThat(payment.getCurrentPeriodId()).isNotEqualTo(periodId);
        assertThat(payment.getNextPaymentDate()).isEqualTo(LocalDate.of(2026, 10, 25));
    }

    @Test
    void deactivateReturnsNoContentAndKeepsRecord() throws Exception {
        long id = createPayment();

        ResponseEntity<Void> response = rest.exchange(BASE + "/" + id + "/deactivate", HttpMethod.PATCH,
                new HttpEntity<>(new HttpHeaders()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(paymentRepository.findById(id).orElseThrow().isActive()).isFalse();
        assertThat(read(rest.getForEntity(BASE + "/" + id, String.class)).get("active").asBoolean()).isFalse();
    }

    @Test
    void reactivateViaUpdate() throws Exception {
        long id = createPayment();
        deactivate(id);

        Map<String, Object> request = Map.of(
                "name", "Ипотека",
                "amount", new BigDecimal("35300.00"),
                "currency", "RUB",
                "recurrence", "MONTHLY",
                "nextPaymentDate", "2026-09-25",
                "reminders", List.of(),
                "active", true);
        ResponseEntity<String> response = put(id, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(response).get("active").asBoolean()).isTrue();
    }

    @Test
    void payCreatesHistoryAdvancesPlannedDateAndStartsNewPeriod() throws Exception {
        long id = createPayment();
        UUID periodId = paymentRepository.findById(id).orElseThrow().getCurrentPeriodId();

        ResponseEntity<String> response = payWithoutBody(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = read(response);
        JsonNode payment = body.get("payment");
        JsonNode history = body.get("history");
        assertThat(payment.get("currentPeriodId").asText()).isNotEqualTo(periodId.toString());
        assertThat(payment.get("nextPaymentDate").asText()).isEqualTo("2026-10-25");
        assertThat(history.get("paymentId").asLong()).isEqualTo(id);
        assertThat(history.get("periodId").asText()).isEqualTo(periodId.toString());
        assertThat(history.get("scheduledDate").asText()).isEqualTo("2026-09-25");
        assertThat(history.get("amount").decimalValue()).isEqualByComparingTo("35300.00");
        assertThat(history.get("currency").asText()).isEqualTo("RUB");
        assertThat(history.get("paidAt").asText()).isNotBlank();

        Payment persisted = paymentRepository.findById(id).orElseThrow();
        assertThat(persisted.getNextPaymentDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(persisted.getCurrentPeriodId()).isNotEqualTo(periodId);
    }

    @Test
    void payWithMatchingExpectedPeriodIsIdempotent() throws Exception {
        long id = createPayment();
        UUID periodId = paymentRepository.findById(id).orElseThrow().getCurrentPeriodId();

        ResponseEntity<String> first = pay(id, Map.of("expectedPeriodId", periodId.toString()));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        long historyId = read(first).get("history").get("id").asLong();
        LocalDate advanced = paymentRepository.findById(id).orElseThrow().getNextPaymentDate();

        ResponseEntity<String> second = pay(id, Map.of("expectedPeriodId", periodId.toString()));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(second).get("history").get("id").asLong()).isEqualTo(historyId);
        assertThat(paymentRepository.findById(id).orElseThrow().getNextPaymentDate()).isEqualTo(advanced);
        assertThat(historyCount(id)).isEqualTo(1);
    }

    @Test
    void payWithStalePeriodReturnsConflict() throws Exception {
        long id = createPayment();

        ResponseEntity<String> response = pay(id, Map.of("expectedPeriodId", UUID.randomUUID().toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(read(response).get("title").asText()).isEqualTo("Stale payment period");
        assertThat(historyCount(id)).isZero();
    }

    @Test
    void payInactiveReturnsConflict() throws Exception {
        long id = createPayment();
        deactivate(id);

        ResponseEntity<String> response = payWithoutBody(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(read(response).get("title").asText()).isEqualTo("Payment is not active");
        assertThat(historyCount(id)).isZero();
    }

    @Test
    void payMissingReturnsNotFound() throws Exception {
        ResponseEntity<String> response = payWithoutBody(999999);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(read(response).get("title").asText()).isEqualTo("Payment not found");
    }

    @Test
    void historyReturnsNewestFirstAndMissingPaymentNotFound() throws Exception {
        long id = createPayment();
        payWithoutBody(id);
        payWithoutBody(id);

        ResponseEntity<String> response = rest.getForEntity(BASE + "/" + id + "/history", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = read(response);
        assertThat(page.get("page").get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.get("content").get(0).get("scheduledDate").asText()).isEqualTo("2026-10-25");
        assertThat(page.get("content").get(1).get("scheduledDate").asText()).isEqualTo("2026-09-25");

        ResponseEntity<String> missing = rest.getForEntity(BASE + "/999999/history", String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void payOfOverduePaymentAdvancesExactlyOnePeriod() throws Exception {
        ResponseEntity<String> created = post(createBody(Map.of("nextPaymentDate", "2025-01-31")));
        long id = read(created).get("id").asLong();

        ResponseEntity<String> response = payWithoutBody(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = read(response);
        assertThat(body.get("history").get("scheduledDate").asText()).isEqualTo("2025-01-31");
        assertThat(body.get("payment").get("nextPaymentDate").asText()).isEqualTo("2025-02-28");
        assertThat(body.get("payment").get("active").asBoolean()).isTrue();
    }

    private long createPayment() throws Exception {
        ResponseEntity<String> response = post(createBody(Map.of()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return read(response).get("id").asLong();
    }

    private void deactivate(long id) {
        ResponseEntity<Void> response = rest.exchange(BASE + "/" + id + "/deactivate", HttpMethod.PATCH,
                new HttpEntity<>(new HttpHeaders()), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private ResponseEntity<String> pay(long id, Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(BASE + "/" + id + "/pay", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
    }

    private ResponseEntity<String> payWithoutBody(long id) {
        return rest.exchange(BASE + "/" + id + "/pay", HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()), String.class);
    }

    private long historyCount(long id) {
        Long count = jdbc.queryForObject(
                "select count(*) from payment_history where payment_id = ?", Long.class, id);
        return count == null ? 0 : count;
    }

    private String createBody(Map<String, Object> overrides) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "name", "Ипотека",
                "amount", new BigDecimal("35300.00"),
                "currency", "RUB",
                "recurrence", "MONTHLY",
                "nextPaymentDate", "2026-09-25",
                "reminders", List.of(5, 3, 1)));
        body.putAll(overrides);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ResponseEntity<String> post(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(BASE, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> put(long id, Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(BASE + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
    }

    private JsonNode read(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private List<Integer> reminders(JsonNode payment) {
        List<Integer> result = new java.util.ArrayList<>();
        payment.get("reminders").forEach(node -> result.add(node.asInt()));
        return result;
    }

    private List<Long> ids(JsonNode page) {
        List<Long> result = new java.util.ArrayList<>();
        page.get("content").forEach(node -> result.add(node.get("id").asLong()));
        return result;
    }
}
