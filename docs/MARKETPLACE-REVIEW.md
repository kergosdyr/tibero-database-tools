# Marketplace Technical Review — Tibero Database Tools

## Plugin Information

- **Plugin:** Tibero Database Tools
- **Plugin ID:** `io.github.kergosdyr.tibero.database.tools`
- **Marketplace ID:** `35762`
- **Source:** https://github.com/kergosdyr/tibero-database-tools
- **Source commit:** `5543919`
- **Implementation:** [TiberoIntrospector.java](https://github.com/kergosdyr/tibero-database-tools/blob/5543919/src/poc/TiberoIntrospector.java)

| Version | Marketplace Update | Supported IDE Builds |
|---|---|---|
| 0.7.8 | 1211217 | 261.27258.48–262.10968.63 |
| 0.7.9 | 1211218 | 262.11879.100–262.* |

## Purpose of the Introspection Extension

Tibero Database Tools extends standard JDBC introspection to enrich the IDE's database model with Tibero-specific packages, routines, overloaded signatures, sequences, object sources, and database links.

The extension participates in schema refresh, preserves existing objects when catalogue retrieval fails, reconciles successful snapshots, and maintains package and routine object identities. Database link catalogues and mappings are persisted separately.

## Remaining Internal API Dependencies

Six of the original 15 Internal API usages have been replaced with public APIs.

Nine Plugin Verifier warnings remain, associated with the Internal `com.intellij.database.introspector` extension point:

- `DBIntrospector.Factory`
- `createIntrospector`
- `isSupported`
- `getVersion`
- `isOutdatedCheckSupported`
- `isNative`
- `isIncremental`
- `supportsMultilevelIntrospection`
- `supportsFragmentIntrospection`

The Marketplace review is awaiting a resolution of these Internal API dependencies.

## Public API Alternatives Investigated

- **`jdbcMetadataWrapper`:** Supports JDBC metadata row adjustments but does not replace the complete introspection lifecycle.
- **`jdbcSourceLoader`:** Supports object source loading but does not provide equivalent schema model reconciliation.
- **`DatabaseModelLoader.Listener.finished`:** Provides a public lifecycle notification. In the current SDK, it runs after database connection detachment and source-session release, making it unsuitable as a direct in-place replacement.

A functionally equivalent public API approach has not yet been established.

Plugin Verifier reports binary compatibility across three tested IDE builds, and runtime checks pass. These results are separate from Internal API policy compliance and Marketplace approval.

## Request for JetBrains Guidance

We would appreciate guidance on:

1. A supported public extension point or API combination for Tibero-specific schema introspection and model reconciliation.
2. Whether public API support for this use case is planned.
3. The official process for requesting API exposure or an exception, if applicable.

We intend to preserve the current functionality while moving to supported APIs.

## References

- [DataGrip Extension Point List](https://plugins.jetbrains.com/docs/intellij/data-grip-extension-point-list.html)
- [Internal API Migration](https://plugins.jetbrains.com/docs/intellij/api-internal.html)
