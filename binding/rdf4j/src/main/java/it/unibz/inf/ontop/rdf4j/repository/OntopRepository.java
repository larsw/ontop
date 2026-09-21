package it.unibz.inf.ontop.rdf4j.repository;

import com.google.common.collect.ImmutableMultimap;
import it.unibz.inf.ontop.answering.cache.HTTPCacheHeaders;
import it.unibz.inf.ontop.injection.OntopSystemConfiguration;
import it.unibz.inf.ontop.rdf4j.repository.impl.OntopVirtualRepository;
import org.eclipse.rdf4j.repository.RepositoryException;

/**
 * Ontop RDF4J repository
 */
public interface OntopRepository extends org.eclipse.rdf4j.repository.Repository, AutoCloseable {

    HTTPCacheHeaders getHttpCacheHeaders();

    @Override
    OntopRepositoryConnection getConnection() throws RepositoryException;

    /**
     * Returns a connection for a request whose HTTP headers are already known.
     *
     * Needed because the connection is opened before the query is prepared, while a data source
     * that authenticates the end user has to be told who the caller is at connection time. The
     * default ignores the headers, which is what every Ontop connection has always done.
     */
    default OntopRepositoryConnection getConnection(ImmutableMultimap<String, String> httpHeaders)
            throws RepositoryException {
        return getConnection();
    }

    static OntopVirtualRepository defaultRepository(OntopSystemConfiguration configuration) {
        return new OntopVirtualRepository(configuration);
    }
}
