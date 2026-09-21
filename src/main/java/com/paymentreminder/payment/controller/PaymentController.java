package com.paymentreminder.payment.controller;

import java.net.URI;

import com.paymentreminder.history.dto.PaymentHistoryResponse;
import com.paymentreminder.history.service.PaymentHistoryService;
import com.paymentreminder.payment.dto.PaymentCreateRequest;
import com.paymentreminder.payment.dto.PaymentPayRequest;
import com.paymentreminder.payment.dto.PaymentPayResponse;
import com.paymentreminder.payment.dto.PaymentResponse;
import com.paymentreminder.payment.dto.PaymentUpdateRequest;
import com.paymentreminder.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentHistoryService historyService;

    public PaymentController(PaymentService paymentService, PaymentHistoryService historyService) {
        this.paymentService = paymentService;
        this.historyService = historyService;
    }

    @PostMapping
    ResponseEntity<PaymentResponse> create(@Valid @RequestBody PaymentCreateRequest request) {
        PaymentResponse created = paymentService.create(request);
        return ResponseEntity.created(URI.create("/api/payments/" + created.id())).body(created);
    }

    @GetMapping
    Page<PaymentResponse> list(@RequestParam(defaultValue = "true") boolean active,
            @PageableDefault(sort = "id") Pageable pageable) {
        return paymentService.list(active, pageable);
    }

    @GetMapping("/{id}")
    PaymentResponse get(@PathVariable Long id) {
        return paymentService.get(id);
    }

    @PutMapping("/{id}")
    PaymentResponse update(@PathVariable Long id, @Valid @RequestBody PaymentUpdateRequest request) {
        return paymentService.update(id, request);
    }

    @PatchMapping("/{id}/deactivate")
    ResponseEntity<Void> deactivate(@PathVariable Long id) {
        paymentService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/pay")
    PaymentPayResponse pay(@PathVariable Long id,
            @RequestBody(required = false) PaymentPayRequest request) {
        return paymentService.pay(id, request == null ? null : request.expectedPeriodId());
    }

    @GetMapping("/{id}/history")
    Page<PaymentHistoryResponse> history(@PathVariable Long id, @PageableDefault Pageable pageable) {
        return historyService.history(id, pageable);
    }
}
