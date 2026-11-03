# Tibero Database Tools

**Vendor:** kergosdyr<br>
**Price:** Free<br>
**License:** Apache License 2.0<br>
**Source and support:** https://github.com/kergosdyr/tibero-database-tools

## Overview

Tibero Database Tools extends IntelliJ IDEA's Database Tools with Tibero-specific object browsing, remote SQL completion, source navigation, and execution plans. Work in the familiar Database tool window and SQL consoles while exploring database links, stored programs, and query behavior.

## Features

- **Database links:** Browse private and PUBLIC links under your schemas. Double-click a link or navigate from its SQL reference to open a reconstructed `CREATE DATABASE LINK` statement.
- **Remote completion:** Complete link names, mapped remote tables, and columns. Navigate from SQL references to their definitions using metadata collected by IntelliJ IDEA.
- **Persistent mappings:** Keep link catalogues and remote data source mappings across IDE restarts. Refresh remote schemas to pick up changes.
- **Stored objects:** Explore procedures, functions, packages, package members, arguments, return types, and sources. Tibero-specific handling improves function argument metadata and selected JDBC type displays.
- **Source and console tools:** Open procedure, function, package, and view sources. IntelliJ IDEA 2026.2 supports source editing with Submit. Generate routine execution blocks and use Tibero DBMS_OUTPUT and compilation error lookup.
- **Execution plans:** Inspect estimated plans in tree, table, or diagram views, open raw plans, and use Explain Analyse to collect execution statistics.

For example, after mapping `SALES_LINK` to a remote data source containing `HR.ORDERS`, column completion can produce:

```sql
SELECT o.CREATED_AT
FROM ORDERS@SALES_LINK o;
```

Link DDL is reconstructed from catalogue metadata. When executing the generated statement, replace `<PASSWORD_REQUIRED>` with the appropriate password. Explain Analyse actually executes a SELECT/WITH query and fetches its results; consider the query's cost and required database permissions.

## Getting Started

1. Download the ZIP or JAR from [GitHub Releases](https://github.com/kergosdyr/tibero-database-tools/releases).
2. Choose **Settings → Plugins → Install Plugin from Disk…** in IntelliJ IDEA, install the file, and restart.
3. Register the Tibero JDBC driver JAR, connect to your Tibero database, and refresh the required schemas.
4. For remote completion, register the actual link target as a separate data source and refresh its schema.
5. Choose **Map DB Link for Completion…** on the link. Select the remote data source and confirm, then select its default schema and confirm.
6. In the local Tibero SQL console, invoke completion (**Ctrl+Space** by default, depending on keymap), select an item, and press **Enter**.

The [User Guide](https://github.com/kergosdyr/tibero-database-tools/blob/main/docs/USAGE.ko.md) walks through setup and SQL completion with IDE screenshots.

## Requirements

IntelliJ IDEA **2026.1.5–2026.2.x**, with **Database Tools and SQL** and **Diagrams** enabled, and a Tibero JDBC driver JAR appropriate for the server.

## Source and License

The project is open source under the **Apache License 2.0**. Explore the code, documentation, releases, and issues on [GitHub](https://github.com/kergosdyr/tibero-database-tools).
