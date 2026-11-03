# Tibero Database Tools 0.7.8-rc.1

IntelliJ IDEA의 Database Tools에서 Tibero DB 링크와 원격 객체를 탐색하고, SQL 자동완성·저장 객체 관리·실행 계획 분석을 사용할 수 있는 플러그인입니다.

## 이번 버전의 변경 사항

- SQL에서 수집된 `@SALES_LINK`와 같은 **DB 링크 이름을 해석**하고 생성 DDL로 이동하도록 개선했습니다.
- Database 도구 창에서 링크 **더블클릭·Enter**로 DDL을 여는 동작을 개선했습니다.
- 최신 IntelliJ IDEA의 탐색 요청 API에 맞게 이동 기능을 수정했습니다.
- IntelliJ IDEA 2026.2의 **Grid Core 의존성 변경**을 반영했습니다.
- **설치용 ZIP 배포 파일**을 추가했습니다.

기존의 링크별 원격 데이터 소스 매핑과 원격 테이블·컬럼 자동완성, 재시작 후 설정 복원 기능을 함께 사용할 수 있습니다.

## 설치

**지원 IDE:** IntelliJ IDEA 2026.1.5~2026.2.x<br>
**필수 플러그인:** Database Tools and SQL, Diagrams

1. 이 릴리스의 Assets에서 `tibero-database-tools-0.7.8-rc.1.zip` 또는 플러그인 JAR를 받습니다.
2. **Settings → Plugins → ⚙ → Install Plugin from Disk…**에서 파일을 선택합니다.
3. IDE를 재시작하고 Tibero 데이터 소스에 **JDBC 드라이버 JAR**를 등록합니다.
4. 연결을 테스트하고 필요한 스키마를 **Refresh**합니다.

## 원격 DB 링크 자동완성

1. 로컬 스키마를 Refresh하여 `dblinks` 목록을 확인합니다.
2. 실제 링크 대상과 같은 원격 데이터 소스를 별도로 등록하고 원격 스키마를 Refresh합니다.
3. 링크에서 **Map DB Link for Completion…**을 실행합니다.
4. 첫 대화상자에서 원격 데이터 소스를 선택하고 **OK**, 다음 대화상자에서 기본 스키마를 선택하고 **OK**를 누릅니다.
5. SQL 콘솔에서 자동완성(**Ctrl+Space**, 키맵에 따라 다름)을 호출한 뒤 항목을 선택하고 **Enter**로 입력합니다.

```sql
SELECT o.CREATED_AT FROM ORDERS@SALES_LINK o;
```

## 문서

- [프로젝트 소개 및 설치](https://github.com/kergosdyr/tibero-database-tools/blob/main/README.md)
- [상세 사용 가이드](https://github.com/kergosdyr/tibero-database-tools/blob/main/docs/USAGE.ko.md)
- [변경 기록](https://github.com/kergosdyr/tibero-database-tools/blob/main/CHANGELOG.md)

Apache License 2.0 · [소스 코드 및 이슈](https://github.com/kergosdyr/tibero-database-tools)
