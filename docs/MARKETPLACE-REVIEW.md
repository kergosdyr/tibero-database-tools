# Marketplace Technical Review — Public API Integration

## Overview

Tibero Database Tools has migrated its database introspection and model integration to public IntelliJ Platform APIs.

The migration addresses both the previously reported Internal introspector dependencies and an additional Internal hierarchy descriptor dependency.

## Technical Changes

### Metadata Introspection

The previous `DBIntrospector.Factory` implementation, its eight overridden methods, and the `com.intellij.database.introspector` extension registration have been removed.

The new `TiberoMetadataRefresh` implementation combines:

- `jdbcMetadataWrapper` for JDBC metadata processing.
- `DatabaseModelLoader.Listener` for refresh completion and catalogue reconciliation.

Complete catalogue snapshots are collected while JDBC connections are active. Successful refreshes, including those completed with warnings, apply the collected results.

Failed or cancelled collection preserves existing catalogue data. A successful empty snapshot removes obsolete entries.

### Dedicated Tibero Model

The Internal `DvFamilyHierarchyDescriptor` dependency has been removed.

A dedicated Tibero model instance is created using public `ModelFacade` APIs and the schema and `DB_LINK` model contracts of `OraMetaModel`.

The integration does not modify global Generic or Oracle models.

Database links are native Database tree objects, including PUBLIC links under the PUBLIC schema.

### Cache Migration

The public `DataSourceModelStorage.Listener` is used to migrate existing Generic caches.

Migration preserves tables, columns, packages, routines, and selected schemas. Original caches are retained as `.tibero-generic-backup`.

Existing persistent database link catalogues and remote mappings are reused.

Package and routine identities, signatures, sources, DDL, sequences, and remote completion behavior are maintained.

## Verification

The implementation is evaluated through three complementary checks:

1. **Official Plugin Verifier:** Binary compatibility and Internal API usage across the supported IDE builds. The previously reported nine introspector warnings have been eliminated.
2. **IDE runtime and SDK/JDBC regression tests:** Metadata collection, model reconciliation, cache conversion, object pruning, and source Submit behavior. Runtime and cache conversion tests passed on IntelliJ IDEA 2026.1.5, 2026.2.3, and 2026.2.4.
3. **Separate IDE process persistence tests:** Catalogue and mapping restoration across IDE restarts.

## Compatibility

| Plugin Version | Supported IDE Builds |
|---|---|
| 0.7.10 | 261.27258.48–262.10968.63 |
| 0.7.11 | 262.11879.100–262.* |

Both versions share the plugin ID `io.github.kergosdyr.tibero.database.tools`.

Repository: https://github.com/kergosdyr/tibero-database-tools

Submitted updates require JetBrains review.
