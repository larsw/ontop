package it.unibz.inf.ontop.answering.connection.pool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unibz.inf.ontop.evaluator.QueryContext;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands {@code {placeholder}} references in a JDBC URL (or user/password) against the
 * {@link QueryContext} of the request the connection is being opened for.
 *
 * <p>Supported placeholders:
 *
 * <ul>
 *   <li>{@code {header:<name>}} -- an HTTP header of the request, matched case-insensitively
 *   <li>{@code {bearer}} -- the credentials of the {@code Authorization: Bearer ...} header
 *   <li>{@code {claim:<name>}} -- a top-level claim of the bearer token, read <em>without</em>
 *       verifying the signature
 *   <li>{@code {queryId}} -- the query id, which is the caller's correlation id when
 *       {@code ontop.queryIdHttpHeader} is configured and the caller supplied one
 *   <li>{@code {user}}, {@code {roles}}, {@code {groups}} -- the authorization information Ontop
 *       already derives from the request (requires {@code ontop.authorization = true})
 * </ul>
 *
 * <p><strong>On reading claims without verifying the token.</strong> Nothing here is an
 * authorization decision. The claim is used to address the connection -- to tell the data source
 * which caller it is opening a session for -- and the data source verifies the very token that was
 * read from, rejecting it if the two disagree. A forged token therefore buys nothing: it is the
 * downstream verification that decides, not this class.
 *
 * <p>Expansion fails rather than substituting an empty string. A URL whose job is to carry the
 * caller's credentials must not quietly become one that carries none.
 */
public class QueryContextTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)(?::([^}]+))?}");

    private static final String AUTHORIZATION_HEADER = "authorization";
    private static final String BEARER_PREFIX = "bearer ";

    /**
     * Characters that would let an expanded value escape its place in the URL. The JDBC URLs this
     * is written for -- Spark Connect's {@code jdbc:sc://host:port/;k=v;k=v} above all -- are
     * parsed by splitting on separators, so a value containing one would become a parameter of its
     * own. Rejected rather than escaped, because there is no escaping convention to rely on.
     */
    private static final Pattern FORBIDDEN_IN_VALUE = Pattern.compile("[;\\s]");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String template;
    private final List<String> referencedPlaceholders;

    public QueryContextTemplate(String template) {
        this.template = template;
        this.referencedPlaceholders = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find())
            referencedPlaceholders.add(matcher.group());
    }

    /** True when the template has nothing to expand, so it can be used as-is. */
    public boolean isConstant() {
        return referencedPlaceholders.isEmpty();
    }

    public String getTemplate() {
        return template;
    }

    /**
     * @throws UnresolvedPlaceholderException when a placeholder is unknown, or resolves to nothing
     *         in this context
     */
    public String expand(QueryContext queryContext) throws UnresolvedPlaceholderException {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String value = resolve(matcher.group(1), matcher.group(2), queryContext)
                    .orElseThrow(() -> new UnresolvedPlaceholderException(
                            "The placeholder " + matcher.group() + " could not be resolved from the "
                                    + "request: the caller supplied no such value."));

            if (FORBIDDEN_IN_VALUE.matcher(value).find())
                throw new UnresolvedPlaceholderException(
                        "The value of " + matcher.group() + " contains a separator or whitespace "
                                + "and would change the meaning of the JDBC URL. Rejected.");

            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private Optional<String> resolve(String kind, @Nullable String argument, QueryContext queryContext) {
        switch (kind) {
            case "header":
                return argument == null ? Optional.empty() : header(queryContext, argument);
            case "bearer":
                return bearerToken(queryContext);
            case "claim":
                return argument == null ? Optional.empty() : claim(queryContext, argument);
            case "queryId":
                return Optional.of(queryContext.getQueryId().toString());
            case "user":
                return queryContext.getUsername();
            case "roles":
                return joinIfNotEmpty(queryContext.getRoles());
            case "groups":
                return joinIfNotEmpty(queryContext.getGroups());
            default:
                return Optional.empty();
        }
    }

    private static Optional<String> joinIfNotEmpty(Iterable<String> values) {
        String joined = String.join(",", values);
        return joined.isEmpty() ? Optional.empty() : Optional.of(joined);
    }

    private static Optional<String> header(QueryContext queryContext, String name) {
        // QueryContext holds headers already lower-cased, but it is also handed straight to
        // library users, so do not assume it.
        String normalized = name.trim().toLowerCase();
        return queryContext.getHttpHeaders().entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().equals(normalized))
                .map(java.util.Map.Entry::getValue)
                .filter(v -> !v.trim().isEmpty())
                .findFirst();
    }

    private static Optional<String> bearerToken(QueryContext queryContext) {
        return header(queryContext, AUTHORIZATION_HEADER)
                .filter(v -> v.toLowerCase().startsWith(BEARER_PREFIX))
                .map(v -> v.substring(BEARER_PREFIX.length()).trim())
                .filter(v -> !v.isEmpty());
    }

    private static Optional<String> claim(QueryContext queryContext, String claimName) {
        return bearerToken(queryContext)
                .flatMap(token -> readClaim(token, claimName.trim()));
    }

    private static Optional<String> readClaim(String jwt, String claimName) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2)
            return Optional.empty();
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode node = JSON.readTree(new String(payload, StandardCharsets.UTF_8)).get(claimName);
            if (node == null || !node.isValueNode())
                return Optional.empty();
            String value = node.asText();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
        catch (Exception e) {
            // A token we cannot read is a token we cannot address the connection with. The caller
            // turns this into a refusal; it is never a reason to fall back to no credentials.
            return Optional.empty();
        }
    }

    /**
     * Replaces every placeholder with a marker, for log lines and error messages: the expanded
     * form carries the caller's bearer token and must never be logged.
     */
    public String redacted() {
        return PLACEHOLDER.matcher(template).replaceAll("<$1>");
    }

    public static class UnresolvedPlaceholderException extends Exception {
        public UnresolvedPlaceholderException(String message) {
            super(message);
        }
    }
}
