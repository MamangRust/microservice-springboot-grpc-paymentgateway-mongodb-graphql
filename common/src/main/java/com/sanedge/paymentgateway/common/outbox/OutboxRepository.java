package com.sanedge.paymentgateway.common.outbox;

import java.time.Instant;
import java.util.List;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the shared {@code outbox} collection (MongoDB).
 *
 * <p>Port of the blueprint's per-module {@code OutboxRepository}. Unlike the ecommerce outboxes
 * there is no claim/lease — a single publisher instance drains documents, increments
 * {@code attempts}, and moves a document to {@code FAILED} once {@code maxAttempts} is reached.
 *
 * <p>Business write + outbox insert are separate Mongo writes (no cross-collection Mongo
 * transaction is configured); this gives best-effort at-least-once delivery, which the
 * idempotent-ish consumer tolerates. A missed stat is non-critical.
 */
@Repository
public class OutboxRepository {

    private final MongoTemplate mongoTemplate;

    public OutboxRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Persists a new PENDING event; call inside the business method. */
    public void enqueue(Outbox event) {
        mongoTemplate.save(event);
    }

    public List<Outbox> findPending(int limit) {
        Query query = Query.query(Criteria.where("status").is(OutboxStatus.PENDING))
                .with(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.ASC, "createdAt"))
                .limit(Math.max(1, limit));
        return mongoTemplate.find(query, Outbox.class);
    }

    public void markProcessed(String id) {
        Update update = new Update()
                .set("status", OutboxStatus.PROCESSED)
                .set("processedAt", Instant.now())
                .set("lastError", null);
        mongoTemplate.updateFirst(Query.query(Criteria.where("id").is(id)), update, Outbox.class);
    }

    /**
     * Records a delivery failure. {@code attempts} is incremented; once it reaches
     * {@code maxAttempts} the document is parked as {@code FAILED} (dead letter),
     * otherwise it stays {@code PENDING} for the next tick.
     */
    public void markFailed(String id, String error, int maxAttempts) {
        Outbox event = mongoTemplate.findById(id, Outbox.class);
        if (event == null) {
            return;
        }

        int attempts = event.getAttempts() == null ? 1 : event.getAttempts() + 1;
        Update update = new Update()
                .set("attempts", attempts)
                .set("lastError", error);

        if (attempts >= Math.max(1, maxAttempts)) {
            update.set("status", OutboxStatus.FAILED)
                    .set("processedAt", Instant.now());
        }
        mongoTemplate.updateFirst(Query.query(Criteria.where("id").is(id)), update, Outbox.class);
    }

    /** Backlog depth for the backlog gauge / tests. */
    public long countPending() {
        return mongoTemplate.count(Query.query(Criteria.where("status").is(OutboxStatus.PENDING)),
                Outbox.class);
    }
}
