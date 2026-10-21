package com.sanedge.paymentgateway.common.adapter.fraud;

import com.sanedge.paymentgateway.pb.ai_security.AISecurityServiceGrpc.AISecurityServiceBlockingStub;
import com.sanedge.paymentgateway.pb.ai_security.FraudRequest;
import com.sanedge.paymentgateway.pb.ai_security.FraudResponse;
import com.sanedge.paymentgateway.pb.ai_security.SecurityDomain;
import com.sanedge.paymentgateway.pb.ai_security.SecurityRequest;
import com.sanedge.paymentgateway.pb.ai_security.SecurityResponse;

/**
 * {@link FraudPort} backed by the Python ai-security gRPC service. No
 * try/catch here: transport failures propagate so {@link FraudFallback}
 * can apply the fail-open policy at the call site.
 */
public class FraudAdapter implements FraudPort {

    private final AISecurityServiceBlockingStub stub;

    public FraudAdapter(AISecurityServiceBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    public FraudResponse detectFraud(FraudRequest request) {
        return stub.detectFraud(request);
    }

    @Override
    public SecurityResponse verifySecurity(SecurityDomain domain, String entityId, double amount) {
        SecurityRequest request = SecurityRequest.newBuilder()
                .setDomain(domain)
                .setEntityId(entityId == null ? "" : entityId)
                .setAmount(amount)
                .build();
        return stub.verifySecurity(request);
    }
}
