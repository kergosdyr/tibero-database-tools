# Changelog

## 0.7.8-rc.1

- Expose persisted DB link definitions as SQL PSI elements: known `@LINK` tokens resolve and navigate to reconstructed DDL.
- Prefer source navigation on DB link tree leaves, retain expansion on groups, and handle the IDE's default navigation request API.
- Exercise actual link tokens, the native double-click decision, and current navigation requests in regression tests.
- Add mock JDBC remote tables and private/public links for console tests without synthetic PSI data-source injection.
- Declare the extracted Grid Core dependency for newer IDEs.
- Package an installable distribution ZIP and remove machine-specific build configuration from public instructions.
- Narrow the advertised IDE range to 261.27258.48–262.*; retain the existing plugin ID for upgrades.

## 0.7.7

- Persist DB link catalogues and remote data-source mappings in IDE settings.
- Resolve mapped remote tables/columns; complete link/table names and use native SQL column completion.
- Open reconstructed CREATE DATABASE LINK through Jump to Source.
- Known defects corrected in 0.7.8-rc.1: unresolved link token and tree double-click navigation.

## 0.7.6 and earlier

- Native DB link tree presentation; stored objects, routine metadata, source editing and execution plans.
- Earlier IDE compatibility claims apply only to the exact older artifacts, not to the current release candidate.
