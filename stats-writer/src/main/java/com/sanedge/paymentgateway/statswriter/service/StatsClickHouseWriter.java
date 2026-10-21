package com.sanedge.paymentgateway.statswriter.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes analytics events into the ClickHouse {@code stats_volume} table.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class StatsClickHouseWriter {

    private final JdbcTemplate clickHouseJdbcTemplate;

    public void ensureTable() {
        clickHouseJdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS stats_volume ("
                        + "period String, "
                        + "volume Float64, "
                        + "card_id Int64 DEFAULT 0, "
                        + "merchant_id Int64 DEFAULT 0, "
                        + "metric String DEFAULT 'txn'"
                        + ") ENGINE = MergeTree() ORDER BY (metric, period)");
    }

    public void write(String period, double volume, long cardId, long merchantId) {
        clickHouseJdbcTemplate.update(
                "INSERT INTO stats_volume (period, volume, card_id, merchant_id, metric) VALUES (?, ?, ?, ?, 'txn')",
                period, volume, cardId, merchantId);
    }
}
