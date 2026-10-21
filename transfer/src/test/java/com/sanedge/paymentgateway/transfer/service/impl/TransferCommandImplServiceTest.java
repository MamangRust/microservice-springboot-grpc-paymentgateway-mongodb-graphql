package com.sanedge.paymentgateway.transfer.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.sanedge.paymentgateway.transfer.domain.requests.CreateTransferRequest;
import com.sanedge.paymentgateway.transfer.models.Transfer;
import com.sanedge.paymentgateway.transfer.repository.TransferCommandRepository;
import com.sanedge.paymentgateway.transfer.repository.TransferQueryRepository;

import io.grpc.StatusRuntimeException;

@ExtendWith(MockitoExtension.class)
class TransferCommandImplServiceTest {

    @Mock
    private TransferCommandRepository transferCommandRepository;
    @Mock
    private TransferQueryRepository transferQueryRepository;
    @Mock
    private CardPort cardPort;
    @Mock
    private SaldoPort saldoPort;
    @Mock
    private FraudPort fraudPort;
    @Mock
    private OutboxRepository outboxRepository;

    private TransferCommandImplService service;

    private static final String FROM = "4111111111111111";
    private static final String TO = "4222222222222222";

    private static TracingMetrics tracingMetrics() {
        return new TracingMetrics(
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(io.opentelemetry.api.OpenTelemetry.class),
                "test");
    }

    @BeforeEach
    void setUp() {
        service = new TransferCommandImplService(
                transferCommandRepository,
                transferQueryRepository,
                cardPort,
                saldoPort,
                fraudPort,
                tracingMetrics(),
                outboxRepository);
    }

    private CreateTransferRequest request() {
        CreateTransferRequest req = new CreateTransferRequest();
        req.setTransferFrom(FROM);
        req.setTransferTo(TO);
        req.setTransferAmount(25000L);
        return req;
    }

    private void stubHappyPath() {
        lenient().when(cardPort.findCardByCardNumber(anyString()))
                .thenReturn(new Card("c-1", "9", FROM, "CREDIT", "2030-01", "123", "VISA", null, null, null));
        lenient().when(saldoPort.findByCardNumber(anyString()))
                .thenReturn(new Saldo("s-1", FROM, 500000, null, null, null, null));
        lenient().when(transferCommandRepository.save(any(Transfer.class))).thenAnswer(inv -> {
            Transfer t = inv.getArgument(0);
            t.setTransferId("t-1");
            return t;
        });
        lenient().when(transferCommandRepository.updateTransferStatus(anyString(), anyString())).thenAnswer(inv -> {
            Transfer t = new Transfer();
            t.setTransferId(inv.getArgument(0));
            t.setTransferFrom(FROM);
            t.setTransferTo(TO);
            t.setTransferAmount(25000L);
            t.setStatus(Status.SUCCESS);
            return t;
        });
    }

    @Test
    void create_deniedBySecurityPolicy_andDoesNotTransfer() {
        stubHappyPath();
        when(fraudPort.verifySecurity(eq(SecurityDomain.TRANSFER), anyString(), anyDouble()))
                .thenReturn(SecurityResponse.newBuilder()
                        .setIsSafe(false)
                        .setRiskScore(0.88)
                        .setReason("velocity breach")
                        .setAction("DENY")
                        .build());

        var response = service.create(request());

        assertThat(response.getStatus()).isEqualTo("error");
        assertThat(response.getData()).isNull();
        verify(saldoPort, never()).updateSaldoBalance(any());
        verify(transferCommandRepository, never()).save(any(Transfer.class));
    }

    @Test
    void create_failsOpenWhenSecurityUnavailable() {
        stubHappyPath();
        when(fraudPort.verifySecurity(eq(SecurityDomain.TRANSFER), anyString(), anyDouble()))
                .thenThrow(new StatusRuntimeException(io.grpc.Status.UNAVAILABLE));

        var response = service.create(request());

        assertThat(response.getStatus()).isEqualTo("success");
        assertThat(response.getData()).isNotNull();
        // Sender + receiver balances are moved despite the security service being down.
        verify(saldoPort, org.mockito.Mockito.times(2)).updateSaldoBalance(any());
        verify(transferCommandRepository).save(any(Transfer.class));
    }
}
