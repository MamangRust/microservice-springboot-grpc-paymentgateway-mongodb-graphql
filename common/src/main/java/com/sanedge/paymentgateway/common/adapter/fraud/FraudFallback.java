package com.sanedge.paymentgateway.common.adapter.fraud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sanedge.paymentgateway.pb.ai_security.FraudRequest;
import com.sanedge.paymentgateway.pb.ai_security.FraudResponse;
import com.sanedge.paymentgateway.pb.ai_security.SecurityDomain;
import com.sanedge.paymentgateway.pb.ai_security.SecurityResponse;

/**
 * Fail-open policy for the ai-security service.
 *
 * <p>If the service is unreachable or times out we log a warning and let the
 * business operation continue (default "safe" result). When the service does
 * answer, its verdict is honored by the caller. Centralised here so the
 * transaction/withdraw/transfer flows behave identically.
 */
public final class FraudFallback {

    private static final Logger log = LoggerFactory.getLogger(FraudFallback.class);

    private static final double FALLBACK_RISK = 0.1;

    private FraudFallback() {
    }

    /**
     * Calls {@link FraudPort#detectFraud(FraudRequest)}; on any failure returns
     * a non-fraudulent fallback so the transaction is not blocked.
     */
    public static FraudResponse detectFraud(FraudPort port, FraudRequest request) {
        try {
            return port.detectFraud(request);
        } catch (Exception e) {
            log.warn("⚠️ ai-security DetectFraud unavailable, failing open | transactionId={}: {}",
                    request.getTransactionId(), e.getMessage());
            return FraudResponse.newBuilder()
                    .setTransactionId(request.getTransactionId())
                    .setRiskScore(FALLBACK_RISK)
                    .setIsFraudulent(false)
                    .setReason("Fallback: ai-security unavailable")
                    .build();
        }
    }

    /**
     * Calls {@link FraudPort#verifySecurity}; on any failure returns an
     * {@code ALLOW} fallback so the operation is not blocked.
     */
    public static SecurityResponse verifySecurity(FraudPort port, SecurityDomain domain, String entityId, double amount) {
        try {
            return port.verifySecurity(domain, entityId, amount);
        } catch (Exception e) {
            log.warn("⚠️ ai-security VerifySecurity unavailable, failing open | domain={} entityId={}: {}",
                    domain, entityId, e.getMessage());
            return SecurityResponse.newBuilder()
                    .setIsSafe(true)
                    .setRiskScore(FALLBACK_RISK)
                    .setReason("Fallback: ai-security unavailable")
                    .setAction("ALLOW")
                    .build();
        }
    }
}
