package io.audd.internal;

/**
 * Cost-aware retry classification. See design spec §7.1.
 *
 * <ul>
 *   <li>READ — idempotent reads (streams.list, streams.getCallbackUrl):
 *       retry on 408/429/5xx + any connection error.</li>
 *   <li>RECOGNITION — recognize / recognizeEnterprise / advanced.findLyrics:
 *       retry on pre-upload connection failures + 5xx. DO NOT retry on
 *       read-timeout-after-upload (cost protection).</li>
 *   <li>MUTATING — streams.add / set_url / delete / setCallbackUrl /
 *       custom_catalog.add: retry only on pre-upload connection failures.
 *       DO NOT retry on 5xx (the side effect may have happened).</li>
 * </ul>
 */
public enum RetryClass {
    READ, RECOGNITION, MUTATING
}
