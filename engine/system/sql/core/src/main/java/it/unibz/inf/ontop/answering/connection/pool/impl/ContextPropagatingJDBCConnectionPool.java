package it.unibz.inf.ontop.answering.connection.pool.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import it.unibz.inf.ontop.answering.connection.pool.JDBCConnectionPool;
import it.unibz.inf.ontop.evaluator.QueryContext;
import it.unibz.inf.ontop.injection.OntopSystemSQLSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.Properties;

/**
 * Opens a connection per request, built from the caller's own context rather than from one set of
 * credentials shared by everybody.
 *
 * <p>The JDBC URL, user and password are templates (see {@link QueryContextTemplate}) expanded
 * against the {@link QueryContext} of the request:
 *
 * <pre>
 * it.unibz.inf.ontop.answering.connection.pool.JDBCConnectionPool = \
 *     it.unibz.inf.ontop.answering.connection.pool.impl.ContextPropagatingJDBCConnectionPool
 * jdbc.contextual.urlTemplate = jdbc:sc://spark:15002/;user_id={claim:sub};\
 *     x-user-token={bearer};x-correlation-id={queryId}
 * </pre>
 *
 * <p>This is what lets a SPARQL endpoint sit in front of a data source that authenticates and
 * authorizes the <em>end user</em>: the caller's token reaches the data source, the data source
 * decides what that user may read, and the caller's correlation id travels with the query so one
 * id can be grepped across both services. Without it, every SPARQL caller reaches the data source
 * as the one service account in the configuration file, and Ontop is the only place that could
 * ever tell them apart.
 *
 * <p><strong>It does not pool.</strong> A pooled connection carries the credentials of whoever
 * opened it, so handing it to the next caller would hand over their access too -- precisely the
 * confusion this class exists to remove. Connections are opened per request and closed with it,
 * which costs a round trip (and, against a data source that creates a session per connection, a
 * session). Configure a shared pool for workloads that do not need per-caller credentials.
 *
 * <p>An unresolvable placeholder fails the request. A URL whose job is to carry the caller's
 * credentials must not quietly become one that carries none: silently falling back would send an
 * anonymous, or worse a previous caller's, query to the data source.
 */
@Singleton
public class ContextPropagatingJDBCConnectionPool implements JDBCConnectionPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContextPropagatingJDBCConnectionPool.class);

    private final OntopSystemSQLSettings settings;
    private final QueryContextTemplate urlTemplate;
    @Nullable
    private final QueryContextTemplate userTemplate;
    @Nullable
    private final QueryContextTemplate passwordTemplate;

    @Inject
    private ContextPropagatingJDBCConnectionPool(OntopSystemSQLSettings settings) {
        this.settings = settings;
        this.urlTemplate = new QueryContextTemplate(
                settings.getContextualJdbcUrlTemplate().orElseGet(settings::getJdbcUrl));
        this.userTemplate = settings.getContextualJdbcUserTemplate()
                .map(QueryContextTemplate::new)
                .orElse(null);
        this.passwordTemplate = settings.getContextualJdbcPasswordTemplate()
                .map(QueryContextTemplate::new)
                .orElse(null);

        LOGGER.info("Per-request JDBC connections, built from the query context: {}",
                urlTemplate.redacted());
    }

    @Override
    public void close() {
        // Nothing is held: every connection belongs to the request that opened it.
    }

    /**
     * Used for the work that has no request behind it -- extracting the database metadata at
     * start-up, above all. The template is expanded against an empty context, so a deployment whose
     * URL needs the caller's credentials has to supply {@code jdbc.url} as well, pointing at an
     * account that may read the schema.
     */
    @Override
    public Connection getConnection() throws SQLException {
        Properties info = baseProperties();
        settings.getJdbcUser().ifPresent(u -> info.put("user", u));
        settings.getJdbcPassword().ifPresent(p -> info.put("password", p));
        return open(settings.getJdbcUrl(), info);
    }

    @Override
    public Connection getConnection(QueryContext queryContext) throws SQLException {
        String url;
        Properties info = baseProperties();
        try {
            url = urlTemplate.expand(queryContext);
            if (userTemplate != null)
                info.put("user", userTemplate.expand(queryContext));
            else
                settings.getJdbcUser().ifPresent(u -> info.put("user", u));

            if (passwordTemplate != null)
                info.put("password", passwordTemplate.expand(queryContext));
            else
                settings.getJdbcPassword().ifPresent(p -> info.put("password", p));
        }
        catch (QueryContextTemplate.UnresolvedPlaceholderException e) {
            // Deliberately not a fallback to the static URL: see the class comment.
            throw new SQLException("Cannot open a connection for this request. " + e.getMessage(), e);
        }

        LOGGER.debug("Opening a connection for query {}", queryContext.getQueryId());
        return open(url, info);
    }

    private Properties baseProperties() {
        Properties info = new Properties();
        info.putAll(settings.getAdditionalJDBCProperties());
        return info;
    }

    /**
     * Mirrors {@code LocalJDBCConnectionUtils}, including its retry after explicitly loading the
     * driver, which some containers need. Not reused directly because that class takes its URL
     * from the settings, and the whole point here is that the URL is per request.
     */
    private Connection open(String url, Properties info) throws SQLException {
        Connection connection;
        try {
            connection = DriverManager.getConnection(url, info);
        }
        catch (SQLException ex) {
            try {
                Class.forName(settings.getJdbcDriver());
            }
            catch (ClassNotFoundException e) {
                // Report the original failure: it says why the connection was refused, where this
                // one only says the driver was not preloaded.
                throw ex;
            }
            connection = DriverManager.getConnection(url, info);
        }

        String initScript = settings.initScript();
        if (!initScript.isEmpty()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(initScript);
            }
            catch (SQLException e) {
                connection.close();
                throw new SQLException("Cannot execute the initialization script: " + e.getMessage(), e);
            }
        }
        return connection;
    }

    /** Visible for tests. */
    Optional<String> describeUrlTemplate() {
        return Optional.of(urlTemplate.redacted());
    }
}
