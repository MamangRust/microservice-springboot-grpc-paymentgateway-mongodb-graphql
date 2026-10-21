package com.sanedge.paymentgateway.common.adapter.fraud;

import com.sanedge.paymentgateway.pb.ai_security.FraudRequest;
import com.sanedge.paymentgateway.pb.ai_security.FraudResponse;
import com.sanedge.paymentgateway.pb.ai_security.SecurityDomain;
import com.sanedge.paymentgateway.pb.ai_security.SecurityResponse;

/**
 * Port for the {@code pb.ai_security.AISecurityService} service: fraud
 * detection and per-domain security verification, replacing direct
 * gRPC usage in consumer services.
 */
public interface FraudPort {

    FraudResponse detectFraud(FraudRequest request);

    SecurityResponse verifySecurity(SecurityDomain domain, String entityId, double amount);
}
