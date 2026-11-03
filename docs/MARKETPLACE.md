# Marketplace listing draft

- **Name:** Tibero Database Tools
- **Vendor:** kergosdyr
- **Price:** Free
- **License:** Apache License 2.0
- **Source/support:** https://github.com/kergosdyr/tibero-database-tools

## Description

Tibero Database Tools integrates Tibero with the IDE's built-in Database Tools interface.

- Browse private and public database links and open reconstructed CREATE DATABASE LINK definitions.
- Complete link names, mapped remote tables and columns, and navigate to their definitions.
- Restore link catalogues and mappings across IDE restarts.
- Inspect stored procedures, functions and packages with argument and return-type metadata.
- View estimated and actual execution plans using the IDE's native tree and diagram.
- Use Tibero DBMS_OUTPUT and compilation-error lookup in SQL consoles.

Remote table and column completion requires an explicitly mapped, introspected remote data source. JDBC drivers are supplied by the user. Database link passwords cannot be recovered; generated DDL contains a required-password placeholder.

This release candidate was tested in IntelliJ IDEA 2026.1.5 and 2026.2.3 using offline/model tests and an in-process mock JDBC server. Live Tibero 6/7 server validation is still pending. It is not yet a stable Marketplace release.

Independently developed; not affiliated with or endorsed by TmaxTibero or JetBrains.

## Screenshots

Use `docs/images/link-resolved.png` and `docs/images/double-click-ddl.png` with captions identifying the Mock JDBC test environment. The DDL screenshot includes a password placeholder, not a recovered password or executable recreation script.

## Change notes

0.7.8-rc.1 resolves DB link names in SQL, fixes double-click navigation and the latest IDE navigation request API, and declares the extracted Grid Core dependency. Persistent remote mappings and native SQL table/column completion remain available after restart.

## Submission state

Prepared for owner review. No Marketplace plugin entry, signing identity or upload token is assumed to exist. Do not claim real Tibero compatibility or publish as stable before completing `RELEASING.md`.
