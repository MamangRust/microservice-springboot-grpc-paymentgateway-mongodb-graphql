package com.sanedge.paymentgateway.withdraw.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sanedge.paymentgateway.common.adapter.card.CardPort;
import com.sanedge.paymentgateway.common.adapter.fraud.FraudPort;
import com.sanedge.paymentgateway.common.adapter.model.Card;
import com.sanedge.paymentgateway.common.adapter.model.Saldo;
import com.sanedge.paymentgateway.common.adapter.saldo.SaldoPort;
import com.sanedge.paymentgateway.common.enums.Status;
import com.sanedge.paymentgateway.common.observability.TracingMetrics;
import com.sanedge.paymentgateway.common.outbox.OutboxRepository;
import com.sanedge.paymentgateway.pb.ai_security.SecurityDomain;
import com.sanedge.paymentgateway.pb.ai_security.SecurityResponse;
import com.sanedge.paymentgateway.withdraw.domain.requests.CreateWithdrawRequest;
import com.sanedge.paymentgateway.withdraw.models.Withdraw;
import com.sanedge.paymentgateway.withdraw.repository.WithdrawCommandRepository;
import com.sanedge.paymentgateway.withdraw.repository.WithdrawQueryRepository;

import io.grpc.StatusRuntimeException;
import jakarta.validation.Validator;

@ExtendWith(MockitoExtension.class)
class WithdrawCommandImplServiceTest {

    @Mock
    private WithdrawQueryRepository withdrawQueryRepository;
    @Mock
    private WithdrawCommandRepository withdrawCommandRepository;
    @Mock
    private CardPort cardPort;
    @Mock
    private SaldoPort saldoPort;
    @Mock
    private FraudPort fraudPort;
    @Mock
    private Validator validator;
    @Mock
    private OutboxRepository outboxRepository;

    private WithdrawCommandImplService service;

    private static final String CARD_NUMBER = "4111111111111111";

    private static TracingMetrics tracingMetrics() {
        return new TracingMetrics(
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(io.opentelemetry.api.OpenTelemetry.class),
                "test");
    }

    @BeforeEach
    void setUp() {
        service = new WithdrawCommandImplService(
                withdrawQueryRepository,
                withdrawCommandRepository,
                cardPort,
                saldoPort,
                fraudPort,
                validator,
                tracingMetrics(),
                outboxRepository);
    }

    private CreateWithdrawRequest request() {
        CreateWithdrawRequest req = new CreateWithdrawRequest();
        req.setCardNumber(CARD_NUMBER);
        req.setWithdrawAmount(50000L);
        return req;
    }

    private void stubHappyPath() {
        lenient().when(validator.validate(any())).thenReturn(java.util.Set.of());
        lenient().when(cardPort.findCardByCardNumber(CARD_NUMBER))
                .thenReturn(new Card("c-1", "9", CARD_NUMBER, "CREDIT", "2030-01", "123", "VISA", null, null, null));
        lenient().when(saldoPort.findByCardNumber(anyString()))
                .thenReturn(new Saldo("s-1", CARD_NUMBER, 500000, null, null, null, null));
        lenient().when(withdrawCommandRepository.save(any(Withdraw.class))).thenAnswer(inv -> {
            Withdraw w = inv.getArgument(0);
            w.setWithdrawId("w-1");
            return w;
        });
        lenient().when(withdrawCommandRepository.updateStatus(anyString(), anyString())).thenAnswer(inv -> {
            Withdraw w = new Withdraw();
            w.setWithdrawId(inv.getArgument(0));
            w.setWithdrawNo("wn-" + inv.getArgument(0));
            w.setCardNumber(CARD_NUMBER);
            w.setWithdrawAmount(50000L);
            w.setStatus(Status.SUCCESS);
            return w;
        });
    }

    @Test
    void create_deniedBySecurityPolicy_andDoesNotWithdraw() {
        stubHappyPath();
        when(fraudPort.verifySecurity(eq(SecurityDomain.WITHDRAW), anyString(), anyDouble()))
                .thenReturn(SecurityResponse.newBuilder()
                        .setIsSafe(false)
                        .setRiskScore(0.92)
                        .setReason("suspicious destination")
                        .setAction("DENY")
                        .build());

        var response = service.create(request());

        assertThat(response.getStatus()).isEqualTo("error");
        assertThat(response.getData()).isNull();
        verify(saldoPort, never()).updateSaldoWithdraw(any());
        verify(withdrawCommandRepository, never()).save(any(Withdraw.class));
    }

    @Test
    void create_failsOpenWhenSecurityUnavailable() {
        stubHappyPath();
        when(fraudPort.verifySecurity(eq(SecurityDomain.WITHDRAW), anyString(), anyDouble()))
                .thenThrow(new StatusRuntimeException(io.grpc.Status.UNAVAILABLE));

        var response = service.create(request());

        assertThat(response.getStatus()).isEqualTo("success");
        assertThat(response.getData()).isNotNull();
        verify(saldoPort).updateSaldoWithdraw(any());
        verify(withdrawCommandRepository).save(any(Withdraw.class));
    }
}
