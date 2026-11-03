# Tibero Database Tools

**English** | [한국어](README.ko.md)

**A free, open-source plugin that extends IntelliJ IDEA's Database Tools with features for working with Tibero databases.**

Explore procedures and packages, write and execute PL/SQL, and analyze query execution plans using the familiar Database tool window and SQL consoles. The plugin also provides remote table and column completion through database links, along with catalogue query shortcuts that help you explore database objects and their relationships without leaving the IDE.

![IntelliJ IDEA showing Tibero packages, routines, database links, and a SQL console](docs/images/link-resolved.jpg)

*Explore packages, routines, and database links in the Database tool window while working with SQL in the editor.*

## Features

### 1. Database Object Exploration and Code Completion

Browse Tibero database objects, including procedures, functions, packages, package members, and sequences, directly in the IDE.

The plugin enriches metadata for packages and stored routines, making it easier to work with them while writing SQL.

- Inspect procedure and function parameters and return types.
- View `IN`, `OUT`, and `IN OUT` parameter information.
- Distinguish overloaded routines by their signatures.
- See parameter lists and return types in package member completion suggestions.

When calling a function from a package or working with a procedure that has many parameters, you can find the information you need directly in the SQL editor.

### 2. View and Edit Stored Object Sources

Open and edit the source code of procedures, functions, package specifications and bodies, and views.

In IntelliJ IDEA 2026.2, source editing integrates with **Submit**, allowing you to apply `CREATE OR REPLACE` changes to the associated data source.

Submit executes DDL against the database, so verify the target object and connection before applying changes.

### 3. PL/SQL Execution and Development Tools

The SQL console provides tools for executing stored routines and inspecting information useful for debugging PL/SQL.

- **Execute Routine:** Use <strong>Tibero → Execute Routine…</strong> on an object to generate a PL/SQL execution block that accounts for parameters, OUT variables, and function return values.
- **DBMS_OUTPUT:** Collect messages produced by PL/SQL through a toggle in the SQL console.
- **Compilation Errors:** After `CREATE OR REPLACE`, retrieve error line numbers, column positions, and messages from `ALL_ERRORS` and display them in the console.

Execute Routine prepares an editable execution block in a new console. Fill in the input parameter values, then execute it.

### 4. Execution Plan Analysis

Explore how Tibero accesses data and estimates query execution costs using the IDE's execution plan views.

**Explain Plan**

- View execution plans in the IDE's native tree, table, and diagram views.
- Inspect accessed objects, costs, and estimated row counts for each operation.
- Examine `ACCESS` and `FILTER` predicates.
- View raw execution plans.

**Explain Analyse**

- Execute a `SELECT`/`WITH` query and fetch the complete result set.
- Inspect actual row counts, execution time, Starts, and Buffer Gets statistics.
- Examine estimated plans alongside actual execution information to better understand query behavior.

Explain Analyse executes the query against the database. Consider the database workload when analyzing expensive queries.

![Execution plan tree showing operations, costs, and row counts](docs/images/explain-plan.jpg)

![Execution plan diagram](docs/images/explain-plan-diagram.jpg)

### 5. Database Link Exploration and Remote SQL Completion

Browse private and PUBLIC database links under the `dblinks` group in each schema. Double-click a link or press Enter to open its reconstructed creation DDL. You can also navigate to a link definition from an `@link_name` reference in SQL.

By mapping a database link to a remote data source, you can complete remote table and column names in SQL and navigate to their definitions.

```sql
SELECT o.ID, o.AMOUNT, o.CREATED_AT
FROM ORDERS@SALES_LINK o;
```

To enable remote completion, register the actual database link target as a separate IDE data source, refresh its schema, and map the link to that data source and schema. Database link catalogues and mappings are preserved across IDE restarts.

See [Database Link Mapping in the User Guide (Korean)](docs/USAGE.ko.md#4-원격-데이터-소스-매핑하기) for setup instructions.

![Reconstructed database link creation DDL opened from the Database tool window](docs/images/double-click-ddl.jpg)

*Open a database link's creation DDL directly from the Database tool window.*

### 6. Catalogue Query Shortcuts

Right-click a schema or object in the Database tool window and open the <strong>Tibero</strong> menu to prepare a catalogue query in a new SQL console.

These shortcuts help you inspect object definitions, dependencies, privileges, and other database metadata without writing each catalogue query from scratch.

| Category | Menu Actions |
|---|---|
| Schema objects | `Schema Stored Objects`, `Schema Synonyms`, `Schema Sequences` |
| Object definitions and metadata | `View Definition`, `Column Types`, `Routine Arguments`, `Object Source`, `Package Body` |
| Errors and dependencies | `Compilation Errors`, `Dependencies`, `Dependent Objects` |
| Triggers and privileges | `Table Triggers`, `Object Privileges` |

The plugin prepares SQL for the selected schema or object in a new console. Review the generated query, adjust it if needed, and execute it to retrieve the information.

### 7. Tibero SQL and JDBC Metadata Integration

The plugin adapts Tibero SQL and JDBC metadata behavior to make object references and type information more useful in the IDE.

- Apply the Oracle SQL/PLSQL dialect to Tibero connections using the default GenericSQL dialect.
- Resolve SQL references through the current schema and PUBLIC synonyms.
- Correct function argument metadata retrieval for Tibero 6.
- Improve the display of selected JDBC types, including TIMESTAMP types.

These integrations help you use the IDE's completion and navigation features when working with package members, function parameters, and SQL references involving synonyms.

## Installation and Getting Started

**Supported IDE:** IntelliJ IDEA 2026.1.5–2026.2.x<br>
**Current version:** `0.7.8`

1. Enable **Database Tools and SQL** and **Diagrams** in IntelliJ IDEA.
2. Download the plugin ZIP or JAR from [GitHub Releases](https://github.com/kergosdyr/tibero-database-tools/releases).
3. Select the downloaded file using <strong>Settings → Plugins → ⚙ → Install Plugin from Disk…</strong>.
4. Restart the IDE and configure a Tibero data source in the Database tool window.
5. Register a separate **Tibero JDBC driver JAR** appropriate for your server and test the connection.
6. Select the schemas you want to work with and click **Refresh** to load their metadata.

You can invoke basic code completion in SQL consoles with `Ctrl+Space`. The shortcut may vary depending on your IDE keymap.

## Documentation

- [User Guide (Korean)](docs/USAGE.ko.md) — Data source setup, remote database link mapping, SQL completion, object sources, and execution plans
- [Development Guide (Korean)](docs/DEVELOPMENT.md) — Building, testing, and IDE compatibility
- [Changelog (Korean)](CHANGELOG.md) — Changes by version

## License

Tibero Database Tools is released under the [Apache License 2.0](LICENSE).

See [LICENSE](LICENSE) and [NOTICE](NOTICE) for details.
