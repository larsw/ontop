package it.unibz.inf.ontop.answering;


import it.unibz.inf.ontop.answering.reformulation.QueryReformulator;
import it.unibz.inf.ontop.evaluator.QueryContext;
import it.unibz.inf.ontop.exception.OntopConnectionException;
import it.unibz.inf.ontop.answering.connection.OntopConnection;

public interface OntopQueryEngine extends AutoCloseable {

    /**
     * Initialization method
     */
    boolean connect() throws OntopConnectionException;

    void close() throws OntopConnectionException;

    /**
     * Gets a OntopConnection usually coming from a connection pool.
     */
    OntopConnection getConnection() throws OntopConnectionException;

    /**
     * Gets a OntopConnection for a request whose context is already known.
     *
     * @see it.unibz.inf.ontop.answering.connection.DBConnector#getConnection(QueryContext)
     */
    default OntopConnection getConnection(QueryContext queryContext) throws OntopConnectionException {
        return getConnection();
    }

    /**
     * Not for regular usage
     */
    QueryReformulator getQueryReformulator();
}
