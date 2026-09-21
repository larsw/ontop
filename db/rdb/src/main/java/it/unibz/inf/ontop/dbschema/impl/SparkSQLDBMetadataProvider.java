package it.unibz.inf.ontop.dbschema.impl;

import com.google.common.collect.ImmutableList;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import it.unibz.inf.ontop.dbschema.MetadataLookup;
import it.unibz.inf.ontop.dbschema.NamedRelationDefinition;
import it.unibz.inf.ontop.dbschema.QuotedID;
import it.unibz.inf.ontop.dbschema.RelationID;
import it.unibz.inf.ontop.exception.MetadataExtractionException;
import it.unibz.inf.ontop.exception.RelationNotFoundInMetadataException;
import it.unibz.inf.ontop.injection.CoreSingletons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Objects;

import static it.unibz.inf.ontop.dbschema.RelationID.TABLE_INDEX;

public class SparkSQLDBMetadataProvider extends AbstractDBMetadataProvider {

    protected static final Logger LOGGER = LoggerFactory.getLogger(SparkSQLDBMetadataProvider.class);

    protected static final int SCHEMA_INDEX = 1;
    protected static final int CATALOG_INDEX = 2;

    @Nullable
    private final QuotedID defaultCatalog;
    private final QuotedID defaultSchema;

    @AssistedInject
    SparkSQLDBMetadataProvider(@Assisted Connection connection, CoreSingletons coreSingletons) throws MetadataExtractionException {
        super(connection, metadata -> new SparkSQLQuotedIDFactory(), coreSingletons);
        try {
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();
            LOGGER.info("OBTAINED FROM SPARK JDBC DRIVER: " + catalog + ", " + schema);
            if (catalog.isEmpty())
                catalog = null;

            String[] defaultRelationComponents = new String[] { catalog, schema, "DUMMY" };
            if (defaultRelationComponents[SCHEMA_INDEX] == null)
                throw new MetadataExtractionException("Unable to obtain the default schema: make sure the connection URL is complete " + Arrays.toString(defaultRelationComponents));

            RelationID id = rawIdFactory.createRelationID(defaultRelationComponents);
            defaultCatalog = getCatalogID(id);
            defaultSchema = id.getComponents().get(SCHEMA_INDEX);
        }
        catch (SQLException e) {
            throw new MetadataExtractionException(e);
        }
    }


    @Override
    protected RelationID getCanonicalRelationId(RelationID id) {
        switch (id.getComponents().size()) {
            case CATALOG_INDEX:
                return getRelationIDWithDefaultCatalog(id.getComponents().get(TABLE_INDEX), id.getComponents().get(SCHEMA_INDEX));
            case SCHEMA_INDEX:
                return getRelationIDWithDefaultCatalog(id.getComponents().get(TABLE_INDEX), defaultSchema);
            default:
                return id;
        }
    }

    private RelationID getRelationIDWithDefaultCatalog(QuotedID table, QuotedID schema) {
        return defaultCatalog == null
                ? new RelationIDImpl(ImmutableList.of(table, schema))
                : new RelationIDImpl(ImmutableList.of(table, schema, defaultCatalog));
    }

    @Override
    protected ResultSet getRelationIDsResultSet() throws SQLException {
        if(getSettings().exposeSystemTables()) {
            try {
                Statement stmt = connection.createStatement();
                stmt.closeOnCompletion();
                // Obtain the relational objects (i.e., tables and views)
                return stmt.executeQuery("SELECT table_catalog as TABLE_CAT, table_schema as TABLE_SCHEM, TABLE_NAME " +
                        "from system.information_schema.tables " +
                        "WHERE table_type in ('TABLE', 'VIEW', 'EXTERNAL')");
            } catch (SQLException e) {
                LOGGER.warn("Unable to load system tables due to SQLException: " + e.getMessage());
            }
        }
        // TABLE and VIEW only. Spark has no SYSTEM relations, and its own Connect JDBC driver
        // validates the argument rather than ignoring what it does not recognise:
        // "The requested table types contains unsupported items: SYSTEM."
        return metadata.getTables(null, null, null, new String[] { "TABLE", "VIEW" });
    }

    @Override
    protected ImmutableList<RelationID> getAllIDs(RelationID id) {
        if (Objects.equals(defaultCatalog, getCatalogID(id))) {
            RelationID schemaTableId = new RelationIDImpl(id.getComponents().subList(TABLE_INDEX, SCHEMA_INDEX + 1));

            if (defaultSchema.equals(id.getComponents().get(SCHEMA_INDEX)))
                return ImmutableList.of(id.getTableOnlyID(), schemaTableId, id);

            return ImmutableList.of(schemaTableId, id);
        }
        return ImmutableList.of(id);
    }

    private QuotedID getCatalogID(RelationID id) {
        return (id.getComponents().size() < CATALOG_INDEX + 1)
                ? null
                : id.getComponents().get(CATALOG_INDEX);
    }

    @Override
    protected String getRelationCatalog(RelationID id) {
        QuotedID catalog = getCatalogID(id);
        if (catalog == null)
            return null;

        return catalog.getName();
    }

    @Override
    protected String getRelationSchema(RelationID id) { return id.getComponents().get(SCHEMA_INDEX).getName(); }

    @Override
    protected String getRelationName(RelationID id) { return id.getComponents().get(TABLE_INDEX).getName(); }

    @Override
    protected RelationID getRelationID(ResultSet rs, String catalogNameColumn, String schemaNameColumn, String tableNameColumn) throws SQLException {
        return rawIdFactory.createRelationID(rs.getString(catalogNameColumn), rs.getString(schemaNameColumn), rs.getString(tableNameColumn));
    }

    @Override
    public NamedRelationDefinition getRelation(RelationID id) throws MetadataExtractionException {
        try {
            return super.getRelation(id);
        }
        catch (RelationNotFoundInMetadataException e) {
            return extractFileBasedTableByConnectingToDB(id);
        }
    }

    @Override
    public void insertIntegrityConstraints(NamedRelationDefinition relation, MetadataLookup metadataLookup) throws MetadataExtractionException {
        if (relation instanceof FileBasedNamedRelationDefinition)
            return;

        try {
            super.insertIntegrityConstraints(relation, metadataLookup);
        }
        catch (MetadataExtractionException e) {
            // Spark SQL has no primary keys, unique constraints or foreign keys, and the drivers
            // disagree on how to say so: the Hive one answers with empty result sets, while
            // Spark's own Connect driver throws SQLFeatureNotSupportedException from
            // getPrimaryKeys, getIndexInfo and getImportedKeys alike. Nothing is lost by carrying
            // on -- there were no constraints to find.
            if (!isUnsupportedFeature(e))
                throw e;

            LOGGER.debug("The JDBC driver does not report integrity constraints; none extracted for {}",
                    relation.getID());
        }
    }

    private static boolean isUnsupportedFeature(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLFeatureNotSupportedException)
                return true;
            if (cause.getCause() == cause)
                break;
        }
        return false;
    }
}
