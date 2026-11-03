# Tibero Database Tools 0.7.8 / 0.7.9

Tibero Database Tools extends IntelliJ IDEA's built-in Database Tools with Tibero-specific object exploration, PL/SQL development, SQL completion, and execution plan analysis.

This release improves database link navigation, IDE compatibility, and integration with public IntelliJ Platform APIs. It also introduces English-first documentation and provides a signed distribution package.

## What's New

### Database Link Navigation

- Resolve known `@LINK` references in SQL and navigate to reconstructed database link DDL.
- Open database link definitions by double-clicking a link or pressing Enter in the Database tool window.
- Improve navigation behavior with the current IntelliJ IDEA navigation API.
- Preserve database link catalogues and remote data source mappings across IDE restarts.

### IDE Compatibility and Integration

- Replace six internal API usages with public alternatives for type metadata, application startup events, and Database tool window access.
- Update the Grid Core dependency for IntelliJ IDEA 2026.2.
- Maintain binary compatibility with supported IntelliJ IDEA 2026.1 and 2026.2 builds, as checked by Plugin Verifier.

### Documentation and Distribution

- Make English the primary README language while retaining the Korean documentation in `README.ko.md`.
- Update product descriptions and installation instructions.
- Provide the signed distribution package, `tibero-database-tools-0.7.8-signed.zip`, for release.

## Features

| Feature | Description |
|---|---|
| Database Object Exploration | Browse procedures, functions, packages, package members, sequences, parameters, and overloaded signatures. |
| Stored Object Sources | View and edit procedure, function, package, and view sources. Submit integration is available in IDEA 2026.2. |
| PL/SQL Development | Generate routine execution blocks, collect DBMS_OUTPUT, and inspect compilation errors. |
| Execution Plans | Explore estimated plans with tree, table, and diagram views. Explain Analyse executes SELECT/WITH queries and displays actual execution statistics. |
| Database Links | Browse private/PUBLIC links, open reconstructed DDL, and complete mapped remote tables and columns. |
| Catalogue Queries | Prepare SQL to inspect definitions, dependencies, triggers, synonyms, sequences, and privileges. |
| SQL and JDBC Integration | Oracle SQL/PLSQL dialect integration, synonym resolution, and Tibero-specific metadata corrections. |

## Installation and Getting Started

**Supported IDE:** IntelliJ IDEA 2026.1.5–2026.2.x<br>
**Required plugins:** Database Tools and SQL, Diagrams<br>
**Plugin ID:** `io.github.kergosdyr.tibero.database.tools`

### Choose the Right Version

Tibero Database Tools provides the same features in two distributions to accommodate JDBC API changes in IntelliJ IDEA 2026.2.4.

| Plugin Version | Supported IntelliJ IDEA | Build Range | Distribution |
|---|---|---|---|
| **0.7.8** | 2026.1.5–2026.2.3 | `261.27258.48`–`262.10968.63` | `tibero-database-tools-0.7.8-signed.zip` |
| **0.7.9** | 2026.2.4–2026.2.x | `262.11879.100`–`262.*` | `tibero-database-tools-0.7.9-signed.zip` |

Both versions provide identical functionality and use the same plugin ID. When installing manually, choose the distribution matching your IDE version. JetBrains Marketplace selects the compatible plugin version for your IDE.

### Installation Steps

1. Enable **Database Tools and SQL** and **Diagrams** in IntelliJ IDEA.
2. Download the ZIP matching your IDE version from [GitHub Releases](https://github.com/kergosdyr/tibero-database-tools/releases).
3. If you have an earlier preview installed under `local.tibero.offline-poc`, uninstall it before installing the new version. Existing DB link catalogues and mappings are preserved.
4. Open <strong>Settings → Plugins → ⚙ → Install Plugin from Disk…</strong>, select the downloaded ZIP, and restart the IDE.
5. Configure a Tibero data source in the Database tool window, register a separately supplied **Tibero JDBC driver JAR** appropriate for your server, and test the connection.
6. Select the schemas you want to work with and click **Refresh** to load their metadata.

You can invoke basic code completion in SQL consoles with `Ctrl+Space`. The shortcut may vary depending on your IDE keymap.

## Documentation

- [README — English](https://github.com/kergosdyr/tibero-database-tools/blob/main/README.md)
- [README — 한국어](https://github.com/kergosdyr/tibero-database-tools/blob/main/README.ko.md)
- [User Guide — 한국어](https://github.com/kergosdyr/tibero-database-tools/blob/main/docs/USAGE.ko.md)
- [API Compatibility Review](https://github.com/kergosdyr/tibero-database-tools/blob/main/docs/MARKETPLACE-REVIEW.md)
- [Source Code and Issues](https://github.com/kergosdyr/tibero-database-tools)

**License:** Apache License 2.0
