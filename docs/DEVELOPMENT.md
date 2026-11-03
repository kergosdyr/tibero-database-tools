# Build and verification

Python 3.10+ and an extracted IntelliJ IDEA SDK with Database Tools, Diagrams and the bundled JBR are required. Compilation uses the IDE's own `javac`; no IDE or JDBC classes are copied into the plugin.

```sh
export TIBERO_POC_IDE='/path/to/idea/Contents' # macOS; use the SDK root on Linux
# Optional for the proprietary JDBC regression, required for release validation:
export TIBERO_POC_DRIVER='/path/to/licensed/tibero6-jdbc.jar'
python3 build.py
python3 verify-ide.py
```

`build.py` runs offline checks and creates:

- `build/tibero-intellij-poc.jar`: production plugin (existing artifact name retained).
- `build/distributions/tibero-database-tools-<version>.zip`: installable distribution.
- `build/tibero-intellij-verification.jar`: isolated test application starter; do not distribute/install as the production plugin.

Without `TIBERO_POC_DRIVER`, the proprietary driver regression explicitly prints `SKIP`. Other offline/model tests still run. Public CI takes this path because JDBC redistribution is not authorized.

`verify-ide.py` uses a separate config/system/plugins directory. To avoid colliding with a visible development IDE, set a unique `TIBERO_POC_SANDBOX`. Use `TIBERO_POC_SKIP_BUILD=1` and `TIBERO_POC_ARTIFACT=/path/to/verification.jar` to run the exact compiled artifact on another SDK. The headless launcher reads `product-info.json` and starts the IDE's JBR directly.

```sh
TIBERO_POC_SKIP_BUILD=1 \
TIBERO_POC_ARTIFACT=/path/to/verification.jar \
TIBERO_POC_SANDBOX=/tmp/tibero-verify-262 \
python3 verify-ide.py
```

The IDE suite exercises real extension registration, native tree layers, the double-click navigation policy, the modern `Navigatable` request, SQL PSI for the link/table/column, completion contributors, source editing, plan models, and settings serialization. Contributor-level tests are supplemented by the interactive JDBC-console procedure below.

## Compatibility

Build once against the oldest supported SDK and test that artifact on every supported target. Download the official [JetBrains Plugin Verifier](https://github.com/JetBrains/intellij-plugin-verifier) separately.

```sh
python3 check-compatibility.py \
  --ide '/path/to/idea-2026.1.5/Contents' \
  --ide '/path/to/idea-2026.2.3/Contents' \
  --verifier '/path/to/verifier-all.jar'
```

The script stops on compatibility errors. Internal, experimental, deprecated and scheduled-removal API warnings must be reviewed. Grid Core is an optional dependency because its classes moved to a separate bundled plugin in 262. Omitting this declaration produces an unresolved `GridDataRequest.GridDataRequestOwner` report; the class exists in Grid Core.

## Two-process persistence check

Use one sandbox across the write/read phases with `TIBERO_POC_PERSISTENCE_PHASE=write` and then `read`. Each invocation starts a fresh IDE JVM. Run with `TIBERO_POC_SKIP_BUILD=1` and the verification JAR. This verifies persisted catalogues and mappings, independently of UI-model fixtures.

## Interactive JDBC check

The test-only driver is under `mock-tibero/`. It uses H2 and reads metadata SQL from a locally supplied Tibero 6 JDBC JAR. Obtain H2 2.2.224 from its official Maven Central distribution, then:

```sh
export MOCK_TIBERO_H2='/path/to/h2-2.2.224.jar'
python3 mock-tibero/build.py
```

In a separate IDE sandbox, install the production plugin. Clone the IDE's Tibero driver and set:

- Driver class: `mock.tibero.MockTiberoDriver`; dialect: Oracle; DBMS: Tibero.
- Driver files: the mock JAR, H2 JAR and your Tibero 6 JDBC JAR.
- URL: `jdbc:tibero:thin:@localhost:8629:tibero`.
- Local source user: `DEMO`; remote source user: `HR`; passwords: empty.

Refresh DEMO and HR through the IDE. The mock seeds `DEMO.SALES_LINK` and `PUBLIC.PUBLIC_SALES`, and HR.ORDERS/HR.CUSTOMERS. Map SALES_LINK to the HR source/schema through the plugin action. Open a real query console attached to DEMO.

1. Complete a local table, `@SALES_LINK`, `OR|@SALES_LINK`, and `o.|` in a remote-table query; verify the selected text is inserted.
2. Verify `SELECT o.AMOUNT FROM ORDERS@SALES_LINK o` has no unresolved link/table/column warning.
3. Verify missing links/tables/columns remain unresolved and do not resolve to a same-named local object.
4. Navigate from the SQL link token to DDL; close it and double-click the tree link to reopen it.
5. Execute `SELECT COUNT(*) FROM EMP` (5 seeded rows), then repeat remote column completion to check the connected console context.
6. Restart the IDE with no fixture injecting models. Repeat completion and DDL navigation using the IDE's saved metadata and plugin settings.

The mock does not implement remote SQL execution over a DB link. This procedure verifies the IDE/JDBC integration, not a real Tibero server. Keep the mock JAR and verification starter out of release ZIPs.
