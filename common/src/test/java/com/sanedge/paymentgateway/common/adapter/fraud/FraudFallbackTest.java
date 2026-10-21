package com.sanedge.paymentgateway.common.adapter.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sanedge.paymentgateway.pb.ai_security.FraudRequest;
import com.sanedge.paymentgateway.pb.ai_security.FraudResponse;
import com.sanedge.paymentgateway.pb.ai_security.SecurityDomain;
import com.sanedge.paymentgateway.pb.ai_security.SecurityResponse;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@ExtendWith(MockitoExtension.class)
class FraudFallbackTest {

    @Mock
    private FraudPort port;

    @Test
    void detectFraud_delegatesToPortWhenAvailable() {
        FraudResponse response = FraudResponse.newBuilder()
                .setTransactionId("tx-1")
                .setRiskScore(0.9)
                .setIsFraudulent(true)
                .setReason("suspicious")
                .build();
        when(port.detectFraud(any())).thenReturn(response);

        FraudResponse result = FraudFallback.detectFraud(port, FraudRequest.newBuilder().setTransactionId("tx-1").build());

        assertThat(result.getIsFraudulent()).isTrue();
        assertThat(result.getRiskScore()).isEqualTo(0.9);
        assertThat(result.getReason()).isEqualTo("suspicious");
    }

    @Test
    void detectFraud_failsOpenWhenPortThrows() {
        when(port.detectFraud(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        FraudResponse result = FraudFallback.detectFraud(port, FraudRequest.newBuilder().setTransactionId("tx-2").build());

        assertThat(result.getIsFraudulent()).isFalse();
        assertThat(result.getTransactionId()).isEqualTo("tx-2");
        assertThat(result.getReason()).contains("Fallback");
    }

    @Test
    void verifySecurity_delegatesToPortWhenAvailable() {
        SecurityResponse response = SecurityResponse.newBuilder()
                .setIsSafe(false)
                .setRiskScore(0.8)
                .setReason("risky")
                .setAction("DENY")
                .build();
        when(port.verifySecurity(any(), any(), anyDouble())).thenReturn(response);

        SecurityResponse result = FraudFallback.verifySecurity(port, SecurityDomain.WITHDRAW, "user-1", 100d);

        assertThat(result.getAction()).isEqualTo("DENY");
    }

    @Test
    void verifySecurity_failsOpenWhenPortThrows() {
        when(port.verifySecurity(any(), any(), anyDouble()))
                .thenThrow(new StatusRuntimeException(Status.DEADLINE_EXCEEDED));

        SecurityResponse result = FraudFallback.verifySecurity(port, SecurityDomain.TRANSFER, "user-2", 250d);

        assertThat(result.getIsSafe()).isTrue();
        assertThat(result.getAction()).isEqualTo("ALLOW");
        assertThat(result.getReason()).contains("Fallback");
    }
}
