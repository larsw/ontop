package it.unibz.inf.ontop.answering.connection.pool;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import it.unibz.inf.ontop.answering.connection.pool.impl.QueryContextTemplate;
import it.unibz.inf.ontop.evaluator.QueryContext;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.*;

public class QueryContextTemplateTest {

    private static final UUID QUERY_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** header.payload.signature, where only the payload is ever read. */
    private static String jwtWith(String jsonPayload) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(jsonPayload.getBytes(StandardCharsets.UTF_8))
                + ".not-a-real-signature";
    }

    private static QueryContext contextWith(ImmutableMap<String, String> headers) {
        return new QueryContext() {
            @Override public Optional<String> getUsername() { return Optional.of("alice"); }
            @Override public ImmutableSet<String> getRolesOrGroups() { return getRoles(); }
            @Override public ImmutableSet<String> getRoles() { return ImmutableSet.of("analyst"); }
            @Override public ImmutableSet<String> getGroups() { return ImmutableSet.of(); }
            @Override public UUID getSalt() { return QUERY_ID; }
            @Override public ImmutableMap<String, String> getHttpHeaders() { return headers; }
            @Override public QueryContext duplicateForNewQueryWithSameSalt() { return this; }
            @Override public UUID getQueryId() { return QUERY_ID; }
        };
    }

    @Test
    public void expandsBearerClaimAndQueryId() throws Exception {
        String token = jwtWith("{\"sub\":\"abc-123\",\"preferred_username\":\"alice\"}");
        QueryContext context = contextWith(ImmutableMap.of("authorization", "Bearer " + token));

        String expanded = new QueryContextTemplate(
                "jdbc:sc://spark:15002/;user_id={claim:sub};x-user-token={bearer};x-correlation-id={queryId}")
                .expand(context);

        assertEquals("jdbc:sc://spark:15002/;user_id=abc-123;x-user-token=" + token
                + ";x-correlation-id=" + QUERY_ID, expanded);
    }

    @Test
    public void readsAnArbitraryHeaderCaseInsensitively() throws Exception {
        QueryContext context = contextWith(ImmutableMap.of("x-tenant", "acme"));
        assertEquals("tenant=acme",
                new QueryContextTemplate("tenant={header:X-Tenant}").expand(context));
    }

    @Test
    public void expandsTheAuthorizationInformationOntopAlreadyDerives() throws Exception {
        QueryContext context = contextWith(ImmutableMap.of());
        assertEquals("alice/analyst",
                new QueryContextTemplate("{user}/{roles}").expand(context));
    }

    /**
     * The point of failing rather than substituting nothing: a URL that is supposed to carry the
     * caller's credentials must not quietly become one that carries none.
     */
    @Test
    public void refusesToExpandWhenTheCallerSuppliedNoToken() {
        QueryContext context = contextWith(ImmutableMap.of());
        assertThrows(QueryContextTemplate.UnresolvedPlaceholderException.class,
                () -> new QueryContextTemplate("token={bearer}").expand(context));
    }

    @Test
    public void refusesANonBearerAuthorizationHeader() {
        QueryContext context = contextWith(ImmutableMap.of("authorization", "Basic YWxpY2U6cHc="));
        assertThrows(QueryContextTemplate.UnresolvedPlaceholderException.class,
                () -> new QueryContextTemplate("token={bearer}").expand(context));
    }

    @Test
    public void refusesAClaimThatIsNotThere() {
        QueryContext context = contextWith(ImmutableMap.of(
                "authorization", "Bearer " + jwtWith("{\"preferred_username\":\"alice\"}")));
        assertThrows(QueryContextTemplate.UnresolvedPlaceholderException.class,
                () -> new QueryContextTemplate("user_id={claim:sub}").expand(context));
    }

    @Test
    public void refusesATokenItCannotRead() {
        QueryContext context = contextWith(ImmutableMap.of("authorization", "Bearer nonsense"));
        assertThrows(QueryContextTemplate.UnresolvedPlaceholderException.class,
                () -> new QueryContextTemplate("user_id={claim:sub}").expand(context));
    }

    /** A header value must not be able to add parameters of its own to the URL. */
    @Test
    public void refusesAValueThatWouldEscapeIntoTheUrl() {
        QueryContext context = contextWith(ImmutableMap.of("x-tenant", "acme;user_id=root"));
        assertThrows(QueryContextTemplate.UnresolvedPlaceholderException.class,
                () -> new QueryContextTemplate("jdbc:sc://h:1/;tenant={header:x-tenant}").expand(context));
    }

    @Test
    public void aTemplateWithoutPlaceholdersIsConstant() {
        assertTrue(new QueryContextTemplate("jdbc:h2:mem:test").isConstant());
        assertFalse(new QueryContextTemplate("jdbc:h2:mem:{user}").isConstant());
    }

    /** Nothing that prints a template may print the token that would be in it. */
    @Test
    public void redactsEveryPlaceholder() {
        assertEquals("jdbc:sc://spark:15002/;user_id=<claim>;x-user-token=<bearer>",
                new QueryContextTemplate(
                        "jdbc:sc://spark:15002/;user_id={claim:sub};x-user-token={bearer}").redacted());
    }
}
