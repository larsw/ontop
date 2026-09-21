# Carrying the caller's identity and trace id to the data source

Ontop answers SPARQL by compiling it to SQL and running that SQL over JDBC. Which caller asked is
something Ontop knows: `QueryContext` has carried the request's HTTP headers, and the user, roles
and groups derived from them, since authorization was introduced. What it could not do is let the
data source know: the JDBC connection comes from a process-wide pool built once from
`jdbc.url` / `jdbc.user` / `jdbc.password`, so every caller reaches the database as the same
account, and nothing the caller sent — not their token, not their correlation id — travels with the
query.

That is fine when the database is a private back end and Ontop is the only place access is decided.
It is not fine when the data source authenticates and authorizes end users itself: a lakehouse
behind an OIDC-aware query engine, a warehouse with per-user OAuth, anything that vends
per-identity storage credentials. There, routing every caller through one service account throws
away the whole point of the data source's own access control, and makes the request untraceable
past Ontop's front door.

This branch closes that gap. It adds no dependency on any particular identity system: Ontop still
does not validate a token, decide an authorization question, or know what OIDC is. It just stops
dropping what the caller sent.

## What changed

**The caller's context reaches connection acquisition.**

`DBConnector`, `OntopQueryEngine` and `JDBCConnectionPool` each gained a `getConnection(QueryContext)`
overload, defaulting to the existing no-argument one, so nothing that implements them has to change.
`OntopRepository.getConnection(ImmutableMultimap<String, String> httpHeaders)` is the entry point the
SPARQL endpoint now uses, because the connection is opened before the query is prepared and the
headers are the only thing available at that moment.

The context built there is then the one the query is evaluated under: `QuestStatement` takes an
optional *connection* query context and prefers it over minting a second one. Without that, the
connection would belong to one context and the query log to another, with different query ids and a
different bnode salt.

**A connection pool that builds its connections from that context.**

`ContextPropagatingJDBCConnectionPool` opens one connection per request from a template:

```properties
it.unibz.inf.ontop.answering.connection.pool.JDBCConnectionPool = \
  it.unibz.inf.ontop.answering.connection.pool.impl.ContextPropagatingJDBCConnectionPool

jdbc.contextual.urlTemplate = jdbc:sc://spark:15002/;user_id={claim:sub};\
  x-user-token={bearer};x-correlation-id={queryId}
```

Placeholders:

| Placeholder | Resolves to |
|---|---|
| `{header:<name>}` | an HTTP header of the request, matched case-insensitively |
| `{bearer}` | the credentials of `Authorization: Bearer ...` |
| `{claim:<name>}` | a top-level claim of that bearer token, read without verifying it |
| `{queryId}` | the query id, which is the caller's correlation id when `ontop.queryIdHttpHeader` is set |
| `{user}`, `{roles}`, `{groups}` | the authorization information Ontop already derives |

`jdbc.contextual.userTemplate` and `jdbc.contextual.passwordTemplate` do the same for the JDBC user
and password, for data sources that take the caller's credentials that way.

Two refusals are deliberate. A placeholder that resolves to nothing fails the request rather than
expanding to an empty string — a URL whose job is to carry the caller's credentials must not quietly
become one that carries none. And a value containing a separator or whitespace is rejected rather
than escaped, so a header cannot add parameters of its own to the URL.

Reading a claim without verifying the signature is not an authorization decision. It addresses the
connection: it tells the data source which caller the session is for, and the data source verifies
the very token it was read from. A forged one buys nothing, because the decision is made downstream.

**It does not pool.** A pooled connection carries the credentials of whoever opened it, so handing
it to the next caller would hand over their access — exactly the confusion this exists to remove.
Connections are opened per request and closed with it. Deployments that do not need per-caller
credentials should keep using the shared pool.

`jdbc.url` is still used, and still needed, for the work that has no request behind it: extracting
the database metadata at start-up. Point it at an account that may read the schema.

**The caller's correlation id becomes the query id.**

```properties
ontop.queryIdHttpHeader = x-correlation-id
```

When set, and the incoming request carries that header with a UUID value, `QueryContext` adopts it
as its query id instead of minting one. Ontop's query log, `ontop:queryId` in a mapping, and the
connection opened for the request then all carry the id the caller is already tracing with, so one
value can be grepped across the caller, Ontop and everything behind it. Unset — the default — the
header means nothing and a fresh id is minted, as before.

A value that is not a UUID is left alone rather than failing the query; it is still readable through
`QueryContext.getHttpHeaders()`.

## Known gap

The predefined-query engine (`OntopRDF4JPredefinedQueryEngineImpl`) still takes its connection
without a context, because the context is not threaded as far as `executeConstructQuery`. Against a
context-aware pool its queries therefore run on whatever `jdbc.url` names, not on the caller's
credentials. Deployments using both should keep that in mind until it is threaded through too.

## What did not change

- No new required configuration. Every addition is opt-in and every interface change is a default
  method.
- The shared connection pools (`TomcatConnectionPool`, `HikariConnectionPool`,
  `DummyJDBCConnectionPool`) are untouched and remain the default.
- Ontop authenticates nothing and authorizes nothing it did not before.
