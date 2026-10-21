package com.sanedge.paymentgateway.statswriter.service;

import org.bson.Document;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * One-shot backfill: reads aggregated volume from the source MongoDB
 * (financial context, {@code transactions} collection) and writes it into the
 * ClickHouse {@code stats_volume} table. Enabled with
 * {@code app.backfill.enabled=true} (the k8s Job runs the stats-writer image with
 * that flag and {@code spring.main.web-application-type=none}).
 *
 * <p>Idempotent: rows are re-inserted (ClickHouse is append-only), so run once
 * per backfill window.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.backfill.enabled", havingValue = "true")
public class StatsBackfillRunner implements CommandLineRunner {

    private final MongoTemplate mongoTemplate;
    private final StatsClickHouseWriter writer;

    @Override
    public void run(String... args) {
        log.info("stats-backfill starting");
        writer.ensureTable();

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.project("merchant_id", "amount")
                        .and("transaction_time").dateAsFormattedString("%Y-%m").as("period"),
                Aggregation.group("period", "merchant_id").sum("amount").as("volume"));

        AggregationResults<Document> results =
                mongoTemplate.aggregate(aggregation, "transactions", Document.class);

        int count = 0;
        for (Document row : results.getMappedResults()) {
            Document id = (Document) row.get("_id");
            String period = id.getString("period");
            long merchantId = numericId(id.get("merchant_id"));
            double volume = ((Number) row.get("volume")).doubleValue();
            writer.write(period, volume, 0L, merchantId);
            count++;
        }
        log.info("stats-backfill completed: {} rows written to ClickHouse", count);
    }

    private static long numericId(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
