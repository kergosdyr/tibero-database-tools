# Tibero Database Tools 0.7.10 / 0.7.11

Tibero Database Tools now integrates Tibero-specific metadata and database objects through public IntelliJ Platform APIs.

This update improves database model integration, metadata refresh, and compatibility with existing IDE data source caches while preserving the plugin's seven core feature groups.

## What's Changed

### Public API Integration

Database metadata refresh now uses public `jdbcMetadataWrapper` and `DatabaseModelLoader.Listener` APIs.

A dedicated Tibero model is created through `ModelFacade`, reusing the public `OraMetaModel` contracts for schemas and database links. Existing Generic and Oracle global models remain unchanged.

### Native Database Link Support

Database links are represented as native objects in the Database tool window.

- Private links appear under their respective schemas.
- PUBLIC links appear under the PUBLIC schema.
- Double-click, Enter, and SQL definition navigation open reconstructed link DDL.
- Database link, remote table, and column completion remain available through mapped remote data sources.

### Metadata Refresh and Cache Migration

Existing Generic data source caches are migrated through the public `DataSourceModelStorage.Listener`.

Migration preserves tables, columns, packages, routines, and selected schemas. The original cache is retained with a `.tibero-generic-backup` suffix.

Existing database link catalogues and remote mappings are reused.

Catalogue refreshes preserve previous data when collection fails or is cancelled. Successfully collected snapshots are reconciled with the database model, including removal of obsolete entries when a successful snapshot is empty.

Package and routine identities, signatures, sources, DDL, and sequences are maintained.

### Source Editing

Source editing and Submit integration are available across the supported IntelliJ IDEA versions, including 2026.1.5, 2026.2.3, and 2026.2.4.

Submit executes DDL against the selected data source.

## Version Compatibility

Both versions provide identical functionality and use the plugin ID `io.github.kergosdyr.tibero.database.tools`.

| Version | IntelliJ IDEA | Build Range |
|---|---|---|
| **0.7.10** | 2026.1.5–2026.2.3 | `261.27258.48`–`262.10968.63` |
| **0.7.11** | 2026.2.4–2026.2.x | `262.11879.100`–`262.*` |

## Installation

Choose the signed distribution matching your IDE version:

- `tibero-database-tools-0.7.10-signed.zip`
- `tibero-database-tools-0.7.11-signed.zip`

[GitHub Releases](https://github.com/kergosdyr/tibero-database-tools/releases)

Install through **Settings → Plugins → Install Plugin from Disk…**, then restart IntelliJ IDEA.

The plugin requires Database Tools and SQL, Diagrams, and a separately supplied Tibero JDBC driver JAR.

Submitted updates require JetBrains review.

---

## 한국어 요약

### 주요 변경 사항

이번 0.7.10 / 0.7.11에서는 Tibero 메타데이터 수집과 객체 모델 구성을 IntelliJ Platform의 공개 API 기반으로 전환했습니다.

- **DB 링크 트리 통합:** DB 링크가 IDE의 기본 Database 트리 객체로 표시되며, PUBLIC 링크는 PUBLIC 스키마 아래에 나타납니다.
- **기존 설정 유지:** Generic 메타데이터 캐시를 변환하여 테이블, 컬럼, 패키지, 루틴과 스키마 선택 정보를 유지합니다. 원본 캐시는 별도로 보관하며 기존 DB 링크 목록과 원격 매핑도 재사용합니다.
- **안정적인 메타데이터 갱신:** 조회 실패나 취소 시 기존 정보를 보존하고, 정상적으로 수집된 결과를 기준으로 객체를 갱신합니다.
- **소스 편집:** IDEA 2026.1.5부터 2026.2.x까지 소스 편집 및 Submit 기능을 제공합니다.

기존 7가지 주요 기능은 그대로 유지됩니다.

### IDE 버전별 설치

| 플러그인 | 지원 IntelliJ IDEA |
|---|---|
| **0.7.10** | 2026.1.5–2026.2.3 |
| **0.7.11** | 2026.2.4–2026.2.x |

[GitHub Releases](https://github.com/kergosdyr/tibero-database-tools/releases)에서 IDE 버전에 맞는 ZIP을 선택해 설치하세요.
