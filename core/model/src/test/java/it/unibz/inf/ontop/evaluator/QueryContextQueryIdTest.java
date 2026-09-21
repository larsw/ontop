package it.unibz.inf.ontop.evaluator;

import com.google.common.collect.ImmutableMap;
import it.unibz.inf.ontop.injection.OntopModelConfiguration;
import it.unibz.inf.ontop.injection.OntopModelSettings;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The query id is what ties Ontop's own query log to whatever the caller is already tracing with.
 */
public class QueryContextQueryIdTest {

    private static QueryContext.Factory factoryWith(String... properties) {
        Properties p = new Properties();
        for (int i = 0; i < properties.length; i += 2)
            p.setProperty(properties[i], properties[i + 1]);

        return OntopModelConfiguration.defaultBuilder()
                .properties(p)
                .build()
                .getInjector()
                .getInstance(QueryContext.Factory.class);
    }

    @Test
    public void adoptsTheCorrelationIdTheCallerSupplied() {
        UUID correlationId = UUID.randomUUID();
        QueryContext context = factoryWith(OntopModelSettings.QUERY_ID_HTTP_HEADER, "x-correlation-id")
                .create(ImmutableMap.of("x-correlation-id", correlationId.toString()));

        assertEquals(correlationId, context.getQueryId());
    }

    @Test
    public void keepsItAcrossADuplicateForTheSameRequest() {
        UUID correlationId = UUID.randomUUID();
        QueryContext context = factoryWith(OntopModelSettings.QUERY_ID_HTTP_HEADER, "x-correlation-id")
                .create(ImmutableMap.of("x-correlation-id", correlationId.toString()));

        assertEquals(correlationId, context.duplicateForNewQueryWithSameSalt().getQueryId());
    }

    @Test
    public void mintsOneWhenTheHeaderIsAbsent() {
        QueryContext context = factoryWith(OntopModelSettings.QUERY_ID_HTTP_HEADER, "x-correlation-id")
                .create(ImmutableMap.of());

        assertNotNull(context.getQueryId());
    }

    /**
     * A trace id we cannot parse is not worth failing a query over: it stays readable through
     * getHttpHeaders(), and the query id falls back to a fresh one.
     */
    @Test
    public void mintsOneWhenTheHeaderIsNotAUuid() {
        QueryContext context = factoryWith(OntopModelSettings.QUERY_ID_HTTP_HEADER, "x-correlation-id")
                .create(ImmutableMap.of("x-correlation-id", "abcdef0123456789"));

        assertNotNull(context.getQueryId());
        assertEquals("abcdef0123456789", context.getHttpHeaders().get("x-correlation-id"));
    }

    /** Unconfigured, the header means nothing -- the historical behaviour. */
    @Test
    public void ignoresTheHeaderUnlessConfigured() {
        UUID correlationId = UUID.randomUUID();
        QueryContext context = factoryWith()
                .create(ImmutableMap.of("x-correlation-id", correlationId.toString()));

        assertNotEquals(correlationId, context.getQueryId());
    }
}
