# Tibero Database Tools 0.7.8-rc.1

Tibero Database Tools는 IntelliJ IDEA의 Database Tools에서 Tibero 객체 탐색, PL/SQL 개발, SQL 자동완성, 실행 계획 분석을 지원하는 무료 오픈소스 플러그인입니다.

이번 버전에서는 DB 링크의 SQL 참조 해석과 정의 이동을 개선하고, IntelliJ IDEA 2026.1.5~2026.2.x 환경을 위한 호환성 변경 사항을 반영했습니다.

## 주요 변경 사항

- **DB 링크 SQL 참조 해석:** 수집된 `@SALES_LINK` 등의 링크 이름을 SQL에서 해석하고 생성 DDL로 이동하도록 개선했습니다.
- **DB 링크 트리 탐색:** 링크 더블클릭·Enter로 DDL을 여는 동작을 개선했습니다.
- **IDE 탐색 API 대응:** 최신 IntelliJ IDEA의 탐색 요청 API에 맞게 수정했습니다.
- **IDE 호환성:** IntelliJ IDEA 2026.2의 Grid Core 의존성 분리를 반영했습니다.
- **배포 구성:** IDE에서 설치할 수 있는 ZIP 배포 파일을 추가했습니다.

## 주요 제공 기능

이번 릴리스에서도 다음 Tibero 개발 기능을 함께 사용할 수 있습니다.

### 객체 탐색과 PL/SQL 개발

- 프로시저·함수·패키지 및 패키지 멤버 탐색, 인자·반환 타입과 오버로드 시그니처 자동완성
- 저장 객체 소스 보기·편집 및 IDEA 2026.2의 Submit 연동
- <strong>Tibero → Execute Routine…</strong>을 통한 PL/SQL 실행 블록 생성
- DBMS_OUTPUT 수집과 `ALL_ERRORS` 기반 컴파일 오류 조회

### 실행 계획과 카탈로그 조회

- **Explain Plan:** 트리·표·다이어그램, 객체·비용·예상 행 수·ACCESS/FILTER 조건 확인
- **Explain Analyse:** SELECT/WITH를 실제 실행하고 전체 fetch 후 실제 행 수·실행 시간·Starts·Buffer Gets 확인
- **Tibero 카탈로그 메뉴:** 객체 정의, 루틴 인자, 의존 객체, 트리거, 시노님, 시퀀스, 권한 등을 조회할 SQL을 새 콘솔에 준비

### DB 링크와 SQL 작성

- private/PUBLIC DB 링크 탐색 및 재구성된 생성 DDL 열기
- DB 링크 이름, 매핑된 원격 테이블·컬럼 자동완성과 정의 이동
- 원격 데이터 소스 매핑 및 IDE 재시작 후 설정 유지
- Oracle SQL/PLSQL dialect, 시노님 참조 해석과 Tibero JDBC 메타데이터 보정

## 설치

**지원 IDE:** IntelliJ IDEA 2026.1.5~2026.2.x<br>
**필수 플러그인:** Database Tools and SQL, Diagrams

1. 이 릴리스의 Assets에서 `tibero-database-tools-0.7.8-rc.1.zip` 또는 플러그인 JAR를 다운로드합니다.
2. <strong>Settings → Plugins → ⚙ → Install Plugin from Disk…</strong>에서 파일을 설치합니다.
3. IDE를 재시작하고 Tibero 데이터 소스에 별도 JDBC 드라이버 JAR를 등록합니다.
4. 연결을 테스트한 뒤 필요한 스키마를 Refresh합니다.

원격 SQL 자동완성은 실제 DB 링크 대상과 같은 원격 데이터 소스를 IDE에 등록하고, <strong>Map DB Link for Completion…</strong>에서 대상 소스와 기본 스키마를 순서대로 선택하여 사용합니다.

## 문서

- [프로젝트 소개 및 설치](https://github.com/kergosdyr/tibero-database-tools/blob/main/README.md)
- [상세 사용 가이드](https://github.com/kergosdyr/tibero-database-tools/blob/main/docs/USAGE.ko.md)
- [변경 기록](https://github.com/kergosdyr/tibero-database-tools/blob/main/CHANGELOG.md)

**License:** Apache 2.0 · [GitHub Repository](https://github.com/kergosdyr/tibero-database-tools)
