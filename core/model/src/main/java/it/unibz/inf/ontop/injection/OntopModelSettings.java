package it.unibz.inf.ontop.injection;

import java.util.Optional;

/**
 * TODO: explain
 */
public interface OntopModelSettings {


    CardinalityPreservationMode getCardinalityPreservationMode();

    boolean isTestModeEnabled();

    /**
     * If true, most limit optimizations are disabled.
     */
    boolean isLimitOptimizationDisabled();

    /**
     * If false, user information is not extracted.
     */
    boolean isAuthorizationEnabled();

    /**
     * Name of the HTTP header carrying a caller-supplied correlation (trace) id, lower-cased.
     *
     * When set and the incoming request carries that header with a UUID value, the QueryContext
     * adopts it as its query id instead of minting a fresh one. That makes Ontop's own query log
     * and everything it derives from the QueryContext -- including, with a context-aware
     * connection pool, the connection opened against the data source -- share the trace id the
     * caller already uses for the rest of its call chain.
     *
     * Empty by default, which preserves the historical behaviour of always minting a query id.
     */
    default Optional<String> getQueryIdHttpHeader() {
        return getProperty(QUERY_ID_HTTP_HEADER)
                .map(String::trim)
                .filter(h -> !h.isEmpty())
                .map(h -> h.toLowerCase());
    }

    /**
     * Not for end-users!
     * Please avoid using that class.
     */
    Optional<String> getProperty(String key);

    boolean contains(Object key);

    enum CardinalityPreservationMode {
        /**
         * Cardinality is not important and may not be respected
         * (allows to optimize more)
         */
        LOOSE,
//        /**
//         * Cardinality is preserved in case a cardinality-sensitive
//         * aggregation function is detected.
//         */
//        STRICT_FOR_AGGREGATION,
        /**
         * Cardinality is strictly preserved
         */
        STRICT
    }

    //-------
    // Keys
    //-------

    String CARDINALITY_MODE = "ontop.cardinalityMode";
    String TEST_MODE = "ontop.testMode";
    String DISABLE_LIMIT_OPTIMIZATION = "ontop.disableLimitOptimization";
    String AUTHORIZATION = "ontop.authorization";
    String QUERY_ID_HTTP_HEADER = "ontop.queryIdHttpHeader";
}
