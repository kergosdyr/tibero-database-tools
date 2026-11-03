# Mock Tibero JDBC

Tibero Database Tools 개발 및 테스트를 위한 Mock JDBC 드라이버입니다.

실제 Tibero 서버 없이 IntelliJ IDEA의 Database Tools 연동과 메타데이터 조회, DB 링크 탐색 및 원격 자동완성을 확인하는 데 사용합니다.

H2와 별도로 준비한 Tibero 6 JDBC 드라이버를 이용하여 테스트 환경을 구성합니다.

## 준비 사항

- Python 3.10 이상
- H2 2.2.224 JAR
- 라이선스를 보유한 Tibero 6 JDBC JAR

환경 변수를 설정합니다.

```sh
export MOCK_TIBERO_H2='/path/to/h2-2.2.224.jar'
export TIBERO_POC_DRIVER='/path/to/licensed/tibero6-jdbc.jar'
```

## 빌드

`mock-tibero/` 디렉터리에서 실행합니다.

```sh
python3 build.py
```

빌드 후 테스트를 생략하려면:

```sh
python3 build.py --no-test
```

생성되는 JAR는 다음과 같습니다.

```text
build/mock-tibero.jar
```

기본 빌드에서는 MockSmoke 검사도 실행합니다.

## IntelliJ IDEA 연결 설정

IDE에서 Tibero 드라이버 정의를 복제하고 다음 값을 설정합니다.

| 항목 | 값 |
|---|---|
| Driver class | `mock.tibero.MockTiberoDriver` |
| JDBC URL | `jdbc:tibero:thin:@localhost:8629:tibero` |
| SQL dialect | Oracle |
| DBMS | Tibero |
| 로컬 계정 | `DEMO` |
| 원격 계정 | `HR` |
| 비밀번호 | 빈 값 |

드라이버 파일에는 다음 세 가지 JAR가 필요합니다.

- `mock-tibero.jar`
- H2 2.2.224 JAR
- Tibero 6 JDBC JAR

## 테스트 데이터

테스트 환경에는 다음 객체가 준비됩니다.

- `DEMO.SALES_LINK`: private DB 링크
- `PUBLIC.PUBLIC_SALES`: PUBLIC DB 링크
- `HR.ORDERS`, `HR.CUSTOMERS`: 원격 테이블
- `EMP`: 5개 행이 있는 콘솔 테스트용 테이블

IDE에서 `DEMO`와 `HR` 데이터 소스를 등록하고 필요한 스키마를 Refresh합니다.

`SALES_LINK`를 `HR` 데이터 소스와 스키마에 매핑하면 원격 테이블·컬럼 자동완성을 확인할 수 있습니다.

Mock JDBC는 DB 링크를 통한 원격 SQL 실행을 구현하지 않습니다.

자세한 IDE 테스트 절차는 [개발 가이드의 Mock JDBC 환경](../docs/DEVELOPMENT.md#6-mock-jdbc-환경)을 참고하세요.
