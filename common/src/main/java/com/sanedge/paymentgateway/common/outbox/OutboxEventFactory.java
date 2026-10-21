package com.sanedge.paymentgateway.common.outbox;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Builds the JSON envelope every email event must carry.
 *
 * <p>Identical contract to the ecommerce/pointofsale factory: the consumer
 * ({@code EmailService.hasValidEnvelope}) requires {@code event_id},
 * {@code schema_version == 1}, {@code event_type}, {@code email},
 * {@code subject}, {@code body}, plus {@code occurred_at} for observability.
 */
public final class OutboxEventFactory {

    /** Must match {@code EmailService.EVENT_SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OutboxEventFactory() {
    }

    /**
     * Builds the email envelope for {@code topic}.
     *
     * @return the serialized payload plus the generated {@code event_id}
     */
    public static Envelope email(String topic, String email, String subject, String body) {
        String eventId = UUID.randomUUID().toString();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("email", email);
        payload.put("subject", subject);
        payload.put("body", body);
        payload.put("event_id", eventId);
        payload.put("schema_version", SCHEMA_VERSION);
        payload.put("event_type", topic);
        payload.put("occurred_at", Instant.now().toString());

        try {
            return new Envelope(eventId, MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize outbox payload for topic " + topic, e);
        }
    }

    /** The generated event id plus its serialized JSON payload. */
    public record Envelope(String eventId, String payload) {
    }

    /**
     * Best-effort conversion of a Mongo/domain String id into the numeric dimension id the
     * ClickHouse {@code stats_volume} table expects. Ids are ObjectIds (non-numeric), so an
     * unparseable id yields {@code 0} rather than failing the enqueue.
     */
    public static long numericId(String id) {
        if (id == null) {
            return 0L;
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Builds the base analytics envelope published to {@code stats.payment.<domain>.event}.
     *
     * <p>Convenience overload for events that carry no domain-specific fields;
     * see {@link #statsPayment(String, String, double, long, long, Map)}.
     *
     * @param topic      destination topic; also used as {@code event_type}
     * @param period     accounting period, formatted {@code YYYY-MM}
     * @param amount     revenue / volume for the period
     * @param cardId     card dimension id (0 if not applicable)
     * @param merchantId merchant dimension id (0 if not applicable)
     * @return the serialized payload plus the generated {@code event_id}
     */
    public static Envelope stats(String topic, String period, double amount, long cardId, long merchantId) {
        return statsPayment(topic, period, amount, cardId, merchantId, Map.of());
    }

    /**
     * Builds the analytics envelope published to {@code stats.payment.<domain>.event}.
     *
     * <p>Consumed by {@code stats-writer} ({@code StatsWriterConsumer}) and
     * {@code ai-security}'s Kafka consumer. The base fields — {@code period},
     * {@code amount}, {@code cardId}, {@code merchantId} — are always present so
     * the ClickHouse {@code stats_volume} sink keeps working unchanged; the
     * {@code extra} map adds the richer domain fields (e.g. {@code transaction_id},
     * {@code card_number}, {@code status}) the security/analytics consumers read.
     *
     * @param topic      destination topic; also used as {@code event_type}
     * @param period     accounting period, formatted {@code YYYY-MM}
     * @param amount     revenue / volume for the period
     * @param cardId     card dimension id (0 if not applicable)
     * @param merchantId merchant dimension id (0 if not applicable)
     * @param extra      additional domain fields to merge into the payload
     * @return the serialized payload plus the generated {@code event_id}
     */
    public static Envelope statsPayment(String topic, String period, double amount, long cardId, long merchantId,
            Map<String, Object> extra) {
        String eventId = UUID.randomUUID().toString();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("period", period);
        payload.put("amount", amount);
        payload.put("cardId", cardId);
        payload.put("merchantId", merchantId);
        if (extra != null) {
            payload.putAll(extra);
        }
        payload.put("event_id", eventId);
        payload.put("schema_version", SCHEMA_VERSION);
        payload.put("event_type", topic);
        payload.put("occurred_at", Instant.now().toString());

        try {
            return new Envelope(eventId, MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize stats payload for topic " + topic, e);
        }
    }
}
