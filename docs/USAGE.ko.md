# 사용 가이드

## 연결과 메타데이터

IDE의 Tibero 데이터 소스를 사용합니다. 드라이버 표시 이름만 바꾼 Generic 연결은 Tibero 확장 대상이 아닐 수 있습니다. 기본 Tibero 드라이버의 GenericSQL dialect는 시작 시 Oracle로 보정합니다. 사용자가 별도로 선택한 dialect는 유지합니다.

스키마 선택 범위와 객체 필터를 확인하고 Refresh합니다. 플러그인은 방문한 스키마의 `ALL_OBJECTS`, `ALL_PROCEDURES`, `ALL_ARGUMENTS`, `ALL_SOURCE`, `ALL_DB_LINKS`, `ALL_SEQUENCES`, 시노님 정보를 이용합니다. 조회 권한이 없으면 객체가 표시되지 않거나 이전 성공 목록이 유지될 수 있습니다.

## DB 링크

`dblinks`에서 링크를 더블클릭하거나 Enter/Jump to Source를 사용하면 생성 DDL을 엽니다. SQL의 `@SALES_LINK`에 정의 이동을 호출해도 같은 DDL을 엽니다. 트리 확장 화살표는 IDE의 추가 노드 구조상 표시될 수 있습니다. 링크 이름 더블클릭은 DDL 이동을 우선합니다. 그룹의 더블클릭은 계속 펼침 동작입니다.

`Map DB Link for Completion…`에서 대상 데이터 소스와 기본 스키마를 지정하면 원격 테이블·컬럼 완성 및 정의 이동이 활성화됩니다. 스키마가 명시된 `HR.ORDERS@SALES_LINK`는 해당 원격 스키마를 사용합니다. 대상 소스는 실제 링크의 접속 대상과 일치하도록 사용자가 지정해야 합니다. 플러그인이 HOST 문자열로 대상 서버를 추측하지 않습니다.

매핑 화면에서 `Remove mapping`을 선택하면 매핑을 제거합니다. 대상 소스를 삭제하거나 스키마를 수집하지 않은 경우 원격 완성은 제공되지 않습니다. 링크의 USERNAME/HOST가 바뀌면 이전 매핑이 무효화되므로 다시 지정합니다.

링크 목록과 매핑은 IDE 설정의 `options/tiberoDbLinks.xml`에 저장합니다. 비밀번호는 저장하지 않습니다. HOST, USERNAME 및 데이터 소스 ID는 포함되며 IDE 설정 동기화에서는 제외합니다. 원격 객체 모델은 IDE의 데이터 소스 저장소가 관리합니다. 원격 모델을 플러그인 메모리에만 저장하지 않습니다.

현재 스키마의 private 링크가 동명 PUBLIC 링크보다 우선합니다. 성공한 빈 목록은 링크 삭제로 반영하고, 조회 실패는 이전 목록을 유지합니다. 실패 시 마지막 목록이 최신이라고 보장하지 않습니다. PUBLIC 목록은 데이터 소스 단위로 공유합니다.

| 증상 | 확인할 사항 |
|---|---|
| `dblinks`가 없다 | 로컬 스키마 Refresh, 스키마 범위, `ALL_DB_LINKS` 권한/조회 결과 |
| `@링크명` 후보가 없다 | Tibero 콘솔 연결, Oracle dialect, 현재 스키마, 링크 Refresh |
| 링크는 보이고 원격 테이블/컬럼 후보가 없다 | 링크 매핑, 대상 소스 존재, 원격 스키마 Refresh |
| `ORDERS@LINK`가 빨갛다 | 실제 대상 모델에 ORDERS 존재 여부, 기본/명시 스키마 |
| 존재하는 `LINK`만 빨갛다 | 0.7.8-rc.1 이상 설치·재시작 확인; 이전 버전은 링크 PSI가 빠져 있었다 |
| 재시작 후 안 보인다 | 같은 프로젝트/데이터 소스 ID인지, 설정 디렉터리·원격 모델이 유지되는지 |
| DDL의 비밀번호 부분이 빨갛다 | `<PASSWORD_REQUIRED>`는 비밀번호 복원 불가를 나타내는 실행 불가 자리 표시자 |

존재하지 않는 링크/원격 객체의 경고를 일괄 숨기지 않습니다. 이름이 같은 로컬 테이블로 잘못 연결하지 않습니다.

## 저장 객체와 콘솔

프로시저·함수·패키지·뷰의 소스를 엽니다. 소스 편집/Submit 통합은 262에서 제공하며, 261은 IDE의 기본 Generic scripting 동작을 유지합니다. Submit은 DB에 DDL을 실행하는 동작입니다. 패키지 명세와 본문은 각각의 소스를 확인합니다. 원격 DB 링크 DDL 문서는 재구성된 일반 SQL 문서입니다.

패키지 멤버 자동완성은 수집한 모델을 사용합니다. `Tibero → Execute Routine…`은 IN/OUT/반환값을 위한 PL/SQL 실행 블록을 새 콘솔에 준비합니다. 실제 값을 채운 다음 사용자가 실행합니다.

콘솔의 Tibero DBMS_OUTPUT 토글은 세션 요청에 ENABLE/GET_LINE/DISABLE 호출을 연결합니다. 해당 패키지/권한이 없는 세션에서는 출력을 수집할 수 없습니다. `CREATE OR REPLACE` 이후 컴파일 오류는 `ALL_ERRORS`에서 조회합니다. SYS 패키지와 기본 인자 처리 등 Oracle 분석기의 일부 동작은 제한됩니다.

## 실행 계획

일반 Explain Plan은 요청별 고유 STATEMENT_ID로 PLAN_TABLE을 사용하고 해당 요청의 행만 정리합니다. PLAN_TABLE과 EXPLAIN/SELECT/DELETE 권한이 필요합니다. Raw 경로에는 계획 행이 남을 수 있습니다. 플러그인이 PLAN_TABLE을 자동 생성하거나 commit/rollback/auto-commit 설정을 변경하지 않습니다.

Explain Analyse는 SELECT/WITH 한 문장을 실제 실행하고 끝까지 fetch합니다. `V$SQLTEXT_WITH_NEWLINES`, `V$SQL_PLAN`, `V$SQL_PLAN_STATISTICS` 조회 권한과 세션의 실행 통계 수집 설정이 필요합니다. 실제 서버의 지원 여부를 확인한 뒤 사용합니다.

```sql
ALTER SESSION SET GATHER_SQL_PLAN_STAT = Y;
ALTER SESSION SET GATHER_SQL_EXEC_TIME = Y;
```

통계를 얻지 못하면 성공한 실제 계획으로 표시하지 않습니다. 이 시점에는 조회 SQL이 이미 실행되었을 수 있습니다. SELECT 안에서 호출하는 사용자 함수의 부작용도 해당 DB 동작을 따릅니다.
