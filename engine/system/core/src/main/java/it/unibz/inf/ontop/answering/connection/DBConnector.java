package it.unibz.inf.ontop.answering.connection;

import it.unibz.inf.ontop.evaluator.QueryContext;
import it.unibz.inf.ontop.exception.OntopConnectionException;

/**
 * High-level component in charge of abstracting the interaction with the DB.
 * This interface is agnostic regarding the native query language.
 *
 * Guice-enabled interface (see the QuestComponentFactory).
 *
 */
public interface DBConnector extends AutoCloseable {

    boolean connect() throws OntopConnectionException;

    void close() throws OntopConnectionException;

    /**
     * Gets a OntopConnection usually coming from a connection pool.
     */
    OntopConnection getConnection() throws OntopConnectionException;

    /**
     * Gets a OntopConnection for a request whose context is already known.
     *
     * Implementations backed by a context-aware connection pool use it to open a connection that
     * carries the caller's identity and trace id to the data source. The default ignores it, which
     * is the historical behaviour: one pool, one set of credentials, shared by every caller.
     */
    default OntopConnection getConnection(QueryContext queryContext) throws OntopConnectionException {
        return getConnection();
    }

}
