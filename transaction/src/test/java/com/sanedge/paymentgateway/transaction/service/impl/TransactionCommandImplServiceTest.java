package com.sanedge.paymentgateway.transaction.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sanedge.paymentgateway.common.adapter.card.CardPort;
import com.sanedge.paymentgateway.common.adapter.fraud.FraudPort;
import com.sanedge.paymentgateway.common.adapter.merchant.MerchantPort;
import com.sanedge.paymentgateway.common.adapter.model.Card;
import com.sanedge.paymentgateway.common.adapter.model.Merchant;
import com.sanedge.paymentgateway.common.adapter.model.Saldo;
import com.sanedge.paymentgateway.common.adapter.saldo.SaldoPort;
import com.sanedge.paymentgateway.common.enums.Status;
import com.sanedge.paymentgateway.common.observability.TracingMetrics;
import com.sanedge.paymentgateway.common.outbox.OutboxRepository;
import com.sanedge.paymentgateway.pb.ai_security.FraudResponse;
import com.sanedge.paymentgateway.transaction.domain.requests.CreateTransactionRequest;
import com.sanedge.paymentgateway.transaction.domain.responses.TransactionResponse;
import com.sanedge.paymentgateway.transaction.models.Transaction;
import com.sanedge.paymentgateway.transaction.repository.TransactionCommandRepository;
import com.sanedge.paymentgateway.transaction.repository.TransactionQueryRepository;

import io.grpc.StatusRuntimeException;
import jakarta.validation.Validator;

@ExtendWith(MockitoExtension.class)
class TransactionCommandImplServiceTest {

    @Mock
    private TransactionQueryRepository transactionQueryRepository;
    @Mock
    private TransactionCommandRepository transactionCommandRepository;
    @Mock
    private Validator validator;
    @Mock
    private MerchantPort merchantPort;
    @Mock
    private SaldoPort saldoPort;
    @Mock
    private CardPort cardPort;
    @Mock
    private FraudPort fraudPort;
    @Mock
    private OutboxRepository outboxRepository;

    private TransactionCommandImplService service;

    private static final String CARD_NUMBER = "4111111111111111";
    private static final String API_KEY = "api-key-1";
    private static final String MERCHANT_ID = "42";

    @BeforeEach
    void setUp() {
        // Real TracingMetrics backed by the OpenTelemetry noop implementation.
        TracingMetrics tracingMetrics = new TracingMetrics(
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(io.opentelemetry.api.OpenTelemetry.class),
                "test");

        service = new TransactionCommandImplService(
                transactionQueryRepository,
                transactionCommandRepository,
                validator,
                tracingMetrics,
                merchantPort,
                saldoPort,
                cardPort,
                fraudPort,
                outboxRepository);
    }

    private CreateTransactionRequest request() {
        CreateTransactionRequest req = new CreateTransactionRequest();
        req.setCardNumber(CARD_NUMBER);
        req.setAmount(75000L);
        req.setPaymentMethod("CREDIT");
        req.setMerchantId(MERCHANT_ID);
        return req;
    }

    private void stubHappyPath() {
        lenient().when(validator.validate(any())).thenReturn(java.util.Set.of());

        lenient().when(merchantPort.findByApiKey(API_KEY))
                .thenReturn(new Merchant("m-1", "Toko", API_KEY, "ACTIVE", "user-9", null, null));
        lenient().when(cardPort.findCardByCardNumber(CARD_NUMBER))
                .thenReturn(new Card("c-1", "9", CARD_NUMBER, "CREDIT", "2030-01", "123", "VISA", null, null, null));
        lenient().when(saldoPort.findByCardNumber(anyString())).thenReturn(
                new Saldo("s-1", CARD_NUMBER, 500000, null, null, null, null));
        lenient().when(cardPort.findCardByUserId("user-9")).thenReturn(
                new Card("c-2", "user-9", "4222222222222222", "CREDIT", "2030-01", "321", "VISA", null, null, null));

        lenient().when(transactionCommandRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            t.setTransactionId("t-1");
            return t;
        });
        lenient().when(transactionCommandRepository.updateTransactionStatus(anyString(), anyString()))
                .thenAnswer(inv -> {
                    Transaction t = new Transaction();
                    t.setTransactionId(inv.getArgument(0));
                    t.setTransactionNo(UUID.randomUUID().toString());
                    t.setCardNumber(CARD_NUMBER);
                    t.setMerchantId(MERCHANT_ID);
                    t.setAmount(75000L);
                    t.setPaymentMethod("CREDIT");
                    t.setTransactionTime(LocalDateTime.now());
                    t.setStatus(Status.SUCCESS);
                    return t;
                });
    }

    @Test
    void create_rejectsWhenFlaggedFraudulent_andDoesNotDebit() {
        stubHappyPath();
        when(fraudPort.detectFraud(any())).thenReturn(FraudResponse.newBuilder()
                .setTransactionId("ref")
                .setRiskScore(0.97)
                .setIsFraudulent(true)
                .setReason("high risk")
                .build());

        var response = service.create(API_KEY, request());

        assertThat(response.getStatus()).isEqualTo("error");
        assertThat(response.getData()).isNull();
        // Saldo must not be debited and no transaction persisted.
        verify(saldoPort, never()).updateSaldoBalance(any());
        verify(transactionCommandRepository, never()).save(any(Transaction.class));
    }

    @Test
    void create_failsOpenWhenFraudServiceUnavailable() {
        stubHappyPath();
        when(fraudPort.detectFraud(any())).thenThrow(new StatusRuntimeException(io.grpc.Status.UNAVAILABLE));

        var response = service.create(API_KEY, request());

        assertThat(response.getStatus()).isEqualTo("success");
        assertThat(response.getData()).isInstanceOf(TransactionResponse.class);
        // Money moves despite the fraud service being down (user + merchant balances).
        verify(saldoPort, org.mockito.Mockito.times(2)).updateSaldoBalance(any());
        verify(transactionCommandRepository).save(any(Transaction.class));
    }
}
