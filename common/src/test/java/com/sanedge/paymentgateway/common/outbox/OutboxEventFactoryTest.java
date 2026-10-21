package com.sanedge.paymentgateway.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OutboxEventFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void statsPayment_keepsBaseFieldsAndMergesExtra() throws Exception {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("transaction_id", "T-1");
        extra.put("card_number", "4111111111111111");
        extra.put("status", "success");

        OutboxEventFactory.Envelope env = OutboxEventFactory.statsPayment(
                "stats.payment.transaction.event", "2026-10", 150000d, 7L, 42L, extra);

        JsonNode node = mapper.readTree(env.payload());

        // Base fields the ClickHouse stats_volume sink depends on.
        assertThat(node.path("period").asText()).isEqualTo("2026-10");
        assertThat(node.path("amount").asDouble()).isEqualTo(150000d);
        assertThat(node.path("cardId").asLong()).isEqualTo(7L);
        assertThat(node.path("merchantId").asLong()).isEqualTo(42L);

        // Envelope metadata.
        assertThat(node.path("event_id").asText()).isEqualTo(env.eventId());
        assertThat(node.path("schema_version").asInt()).isEqualTo(1);
        assertThat(node.path("event_type").asText()).isEqualTo("stats.payment.transaction.event");
        assertThat(node.path("occurred_at").asText()).isNotBlank();

        // Rich domain fields from the extra map.
        assertThat(node.path("transaction_id").asText()).isEqualTo("T-1");
        assertThat(node.path("card_number").asText()).isEqualTo("4111111111111111");
        assertThat(node.path("status").asText()).isEqualTo("success");
    }

    @Test
    void stats_delegatesToStatsPaymentWithoutExtraFields() throws Exception {
        OutboxEventFactory.Envelope env = OutboxEventFactory.stats(
                "stats.payment.saldo.event", "2026-10", 500d, 0L, 0L);

        JsonNode node = mapper.readTree(env.payload());

        assertThat(node.path("period").asText()).isEqualTo("2026-10");
        assertThat(node.path("amount").asDouble()).isEqualTo(500d);
        assertThat(node.path("event_type").asText()).isEqualTo("stats.payment.saldo.event");
    }
}
