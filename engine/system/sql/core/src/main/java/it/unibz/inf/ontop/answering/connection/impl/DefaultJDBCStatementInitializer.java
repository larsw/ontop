package it.unibz.inf.ontop.answering.connection.impl;

import com.google.inject.Inject;
import it.unibz.inf.ontop.answering.connection.JDBCStatementInitializer;
import it.unibz.inf.ontop.injection.OntopSystemSQLSettings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;

public class DefaultJDBCStatementInitializer implements JDBCStatementInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultJDBCStatementInitializer.class);

    protected final OntopSystemSQLSettings settings;

    @Inject
    protected DefaultJDBCStatementInitializer(OntopSystemSQLSettings settings) {
        this.settings = settings;
    }

    @Override
    public Statement createAndInitStatement(Connection connection) throws SQLException {
        return init(create(connection));
    }

    @Override
    public void closeStatement(Statement statement) throws SQLException {
        statement.close();
    }

    protected Statement create(Connection connection) throws SQLException {
        try {
            return connection.createStatement(java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY);
        }
        catch (SQLFeatureNotSupportedException e) {
            // Forward-only and read-only are the JDBC defaults, so a driver that refuses to be
            // asked for them still provides them. Minimal drivers do refuse: Spark's own Connect
            // driver implements only the no-argument form.
            LOGGER.debug("The JDBC driver does not accept a result set type or concurrency; "
                    + "using its defaults, which are the ones being asked for");
            return connection.createStatement();
        }
    }

    protected Statement init(Statement statement) throws SQLException {
        int fetchSize = settings.getFetchSize();
        if (fetchSize > 0) {
            try {
                statement.setFetchSize(fetchSize);
            }
            catch (SQLFeatureNotSupportedException e) {
                // A hint about how much to buffer, never a requirement. A driver that ignores it
                // returns the same rows.
                LOGGER.debug("The JDBC driver does not support a fetch size; ignoring jdbc.fetchSize");
            }
        }
        return statement;
    }
}
