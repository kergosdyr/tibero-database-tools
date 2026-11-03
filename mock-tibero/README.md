# Mock Tibero 6 JDBC driver (test only)

In-process mock of a Tibero 6 server for testing the IntelliJ plugin without a real server.
See the [interactive JDBC check](../docs/DEVELOPMENT.md#interactive-jdbc-check) for behaviour, setup and verification limits.

```bash
python3 build.py            # build/mock-tibero.jar, then run MockSmoke
python3 build.py --no-test  # build only
```

Runtime classpath: `mock-tibero.jar`, H2 2.2.224 (`MOCK_TIBERO_H2`), and the real `tibero6-jdbc.jar`
(`TIBERO_POC_DRIVER`), whose metadata SQL is read at runtime. Driver class `mock.tibero.MockTiberoDriver`,
URL `jdbc:tibero:thin:@localhost:8629:tibero`, users `DEMO`/`HR`.
