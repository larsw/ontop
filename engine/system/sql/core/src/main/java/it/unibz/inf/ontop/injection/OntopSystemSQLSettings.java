package it.unibz.inf.ontop.injection;


import java.util.Optional;

public interface OntopSystemSQLSettings extends OntopSystemSettings, OntopReformulationSQLSettings,
        OntopSQLCredentialSettings {

    //--------------------------
    // Connection configuration
    //--------------------------

    boolean isKeepAliveEnabled();
    boolean isRemoveAbandonedEnabled();
    int getConnectionTimeout();
    int getConnectionPoolInitialSize();
    int getConnectionPoolMaxSize();

    int getFetchSize();

    //--------------------------------------------------------------
    // Per-request connections (ContextPropagatingJDBCConnectionPool)
    //--------------------------------------------------------------

    /**
     * JDBC URL template expanded against the QueryContext of each request, so the connection
     * carries the caller's own credentials and trace id rather than a shared service account's.
     *
     * Only read by a connection pool that opens a connection per request. When absent, such a pool
     * falls back to {@code jdbc.url}, which is then used verbatim.
     */
    default Optional<String> getContextualJdbcUrlTemplate() {
        return getProperty(CONTEXTUAL_URL_TEMPLATE).filter(t -> !t.trim().isEmpty());
    }

    /** Same, for the JDBC user. When absent, {@code jdbc.user} is used. */
    default Optional<String> getContextualJdbcUserTemplate() {
        return getProperty(CONTEXTUAL_USER_TEMPLATE).filter(t -> !t.trim().isEmpty());
    }

    /** Same, for the JDBC password. When absent, {@code jdbc.password} is used. */
    default Optional<String> getContextualJdbcPasswordTemplate() {
        return getProperty(CONTEXTUAL_PASSWORD_TEMPLATE).filter(t -> !t.trim().isEmpty());
    }

    //--------------------------
    // Keys
    //--------------------------

    String MAX_POOL_SIZE = "jdbc.pool.maxSize";
    String INIT_POOL_SIZE = "jdbc.pool.initialSize";
    String REMOVE_ABANDONED = "jdbc.pool.removeAbandoned";
    // Connection timeout (in ms)
    String CONNECTION_TIMEOUT = "jdbc.pool.connectionTimeout";
    String KEEP_ALIVE = "jdbc.pool.keepAlive";

    /*
     * If <= 0, the fetch size is ignored
     */
    String FETCH_SIZE = "jdbc.fetchSize";

    String CONTEXTUAL_URL_TEMPLATE = "jdbc.contextual.urlTemplate";
    String CONTEXTUAL_USER_TEMPLATE = "jdbc.contextual.userTemplate";
    String CONTEXTUAL_PASSWORD_TEMPLATE = "jdbc.contextual.passwordTemplate";
}
