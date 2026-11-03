# Marketplace Review Note — Tibero Database Tools 0.7.8

Tibero Database Tools extends IntelliJ IDEA's database support by enriching JDBC-introspected models for Tibero routines, packages, sequences, and object sources.

The plugin uses the existing `com.intellij.database.introspector` extension point through `DBIntrospector.Factory` and eight related methods. JetBrains documents this extension point as Internal.

For IntelliJ IDEA 2026.1 (261) and 2026.2 (262), Plugin Verifier reports binary compatibility. Internal API warnings have decreased from 15 to 9 after replacing six usages with public APIs: two stored-type operations, three application startup event registrations, and one ToolWindow component access.

The nine remaining warnings concern `DBIntrospector.Factory` and its eight methods.

We retained this extension point to preserve Tibero metadata functionality. All nine warnings remain visible in the verification results.

We have not identified a public replacement or an approved exception.

**Could JetBrains advise whether this dependency is acceptable for Marketplace review, or recommend a supported public replacement?**

References:

- [DataGrip Extension Point List](https://plugins.jetbrains.com/docs/intellij/data-grip-extension-point-list.html)
- [Internal API Migration](https://plugins.jetbrains.com/docs/intellij/api-internal.html)
