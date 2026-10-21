package com.sanedge.paymentgateway.common.outbox;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Transactional outbox document (collection {@code outbox} in each service's own Mongo DB).
 *
 * <p>Port of the blueprint's per-module {@code Outbox} entity. All modules write the same
 * collection and are distinguished by {@code aggregateType}; the shared {@link OutboxPublisher}
 * drains PENDING documents regardless of owner. Stored as a Mongo document (the project is
 * MongoDB — no JPA/datasource is configured), so {@code id} is a String ObjectId and timestamps
 * are {@link Instant}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "outbox")
public class Outbox {

    @Id
    private String id;

    /** Logical producer, e.g. {@code USER} or {@code TRANSACTION}. */
    private String aggregateType;

    /** Kafka record key. */
    private String aggregateId;

    private String topic;

    private String payload;

    private OutboxStatus status = OutboxStatus.PENDING;

    private Integer attempts = 0;

    private Instant createdAt = Instant.now();

    private Instant processedAt;

    private String lastError;
}
