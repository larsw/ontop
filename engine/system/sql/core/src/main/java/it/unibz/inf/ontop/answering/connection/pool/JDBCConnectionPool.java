package it.unibz.inf.ontop.answering.connection.pool;


import it.unibz.inf.ontop.evaluator.QueryContext;

import java.sql.Connection;
import java.sql.SQLException;

public interface JDBCConnectionPool extends AutoCloseable {

    @Override
    void close();

    Connection getConnection() throws SQLException;

    /**
     * Gets a connection for a request whose context is already known.
     *
     * Pools that derive connection parameters from the caller -- their credentials, their trace id
     * -- override this. The default ignores the context, which is what a pool sharing one set of
     * credentials between every caller has always done.
     */
    default Connection getConnection(QueryContext queryContext) throws SQLException {
        return getConnection();
    }
}
