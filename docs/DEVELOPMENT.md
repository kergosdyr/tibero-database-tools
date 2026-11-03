# 개발 및 테스트 가이드

Tibero Database Tools의 빌드, 테스트, IDE 호환성 검사와 개발용 JDBC 환경을 설명합니다.

프로젝트는 IntelliJ IDEA SDK를 사용하여 플러그인을 빌드하며, 실제 IDE 환경에서 확장 기능을 검사할 수 있는 도구를 포함합니다.

## 1. 개발 환경

### 요구 사항

- Python 3.10 이상
- 지원 범위의 IntelliJ IDEA SDK
- SDK에 포함된 Database Tools and SQL, Diagrams
- IDE에 포함된 JetBrains Runtime(JBR)
- Tibero JDBC 드라이버: JDBC 메타데이터 회귀 검사 및 Mock JDBC 테스트에 사용
- H2 2.2.224: Mock JDBC 테스트에 사용

컴파일에는 IntelliJ IDEA SDK의 `javac`를 사용합니다.

IDE 또는 JDBC 구현 클래스를 플러그인에 복사하여 포함하지 않습니다.

### 환경 변수

macOS에서 IntelliJ IDEA를 사용하는 경우:

```sh
export TIBERO_POC_IDE='/path/to/idea/Contents'
```

Linux에서는 압축을 해제한 IDE SDK 루트를 지정합니다.

라이선스를 보유한 Tibero 6 JDBC 드라이버가 있다면 다음 환경 변수를 설정할 수 있습니다.

```sh
export TIBERO_POC_DRIVER='/path/to/licensed/tibero6-jdbc.jar'
```

이 변수는 JDBC 메타데이터 회귀 검사에 사용됩니다.

## 2. 빌드

저장소 루트에서 실행합니다.

```sh
python3 build.py
```

`build.py`는 SDK 빌드 번호에 따라 `release-profiles.json`의 `legacy`(0.7.8) 또는 `current`(0.7.9) 프로필을 선택합니다. IDE 2026.2.4부터 `com.intellij.database.jdbc`로 이동한 API를 원본 소스에서 사용하고, 이전 SDK로 빌드할 때는 `build/legacy-sources`에 이전 패키지 경로를 적용한 소스를 생성합니다. 두 배포본의 기능 구현은 동일합니다.

`TIBERO_POC_BUILD`로 결과물 경로를 분리할 수 있습니다. 지원 범위 밖의 SDK는 빌드 시작 시 거부합니다. 각 프로필의 상세 범위와 빌드 명령은 [배포 가이드](RELEASING.md)를 참고하세요.

빌드 과정에서 오프라인 검사가 실행되며 다음 결과물이 생성됩니다.

| 결과물 | 용도 |
|---|---|
| `build/tibero-intellij-poc.jar` | 실제 설치용 플러그인 JAR |
| `build/distributions/tibero-database-tools-<version>.zip` | IDE에 설치할 수 있는 배포용 ZIP |
| `build/tibero-intellij-verification.jar` | 독립적인 IDE 테스트 실행용 JAR |

`poc`가 포함된 기존 JAR 파일 이름은 빌드 결과물의 이름으로 유지됩니다.

`TIBERO_POC_DRIVER`가 지정되지 않은 경우 라이선스가 필요한 JDBC 회귀 검사는 `SKIP`으로 표시되며, 나머지 오프라인 검사는 계속 실행됩니다.

공개 CI에서도 JDBC 드라이버를 재배포하지 않고 해당 검사만 건너뛰는 방식으로 실행할 수 있습니다.

검사용 `tibero-intellij-verification.jar`는 사용자 설치 및 배포 대상에 포함하지 않습니다.

## 3. IDE 런타임 검사

IntelliJ IDEA에 실제로 플러그인을 로드하여 확장 기능을 검사하려면 다음 스크립트를 실행합니다.

```sh
python3 verify-ide.py
```

스크립트는 IDE의 `product-info.json`을 확인하고 해당 SDK의 JBR로 테스트 IDE를 실행합니다.

일반 개발 IDE의 설정과 충돌하지 않도록 테스트용 설정, 시스템 및 플러그인 디렉터리를 별도로 사용합니다.

### 독립적인 테스트 환경 지정

```sh
export TIBERO_POC_SANDBOX='/tmp/tibero-verify-262'
python3 verify-ide.py
```

이미 빌드된 검사용 JAR를 다른 IDE SDK에서 실행하려면 다음과 같이 지정합니다.

```sh
TIBERO_POC_SKIP_BUILD=1 \
TIBERO_POC_ARTIFACT=/path/to/verification.jar \
TIBERO_POC_SANDBOX=/tmp/tibero-verify-262 \
python3 verify-ide.py
```

`TIBERO_POC_SKIP_BUILD=1`은 재빌드를 생략하고 지정한 결과물을 사용합니다.

### 검사 대상

IDE 런타임 검사에는 다음 항목이 포함됩니다.

- 플러그인 확장 기능 등록
- Database 도구 창의 네이티브 객체 트리 구성
- DB 링크 그룹 및 링크 노드 탐색
- 링크 더블클릭과 Enter의 탐색 동작
- IDE의 `Navigatable` 탐색 요청
- DB 링크 및 원격 테이블·컬럼의 SQL PSI 참조 해석
- DB 링크·테이블 자동완성 및 컬럼 완성 연계
- 저장 객체 소스 편집
- 실행 계획 모델
- 링크 목록과 매핑 설정의 직렬화

SQL 콘솔에서의 실제 완성 선택·삽입 및 JDBC 연결 문맥은 별도의 대화형 JDBC 검사로 확인합니다.

## 4. IDE 호환성 검사

| 플러그인 버전 | 지원 IDE | 빌드 범위 |
|---|---|---|
| 0.7.8 | 2026.1.5–2026.2.3 | `261.27258.48`–`262.10968.63` |
| 0.7.9 | 2026.2.4–2026.2.x | `262.11879.100`–`262.*` |

플러그인 ID는 `io.github.kergosdyr.tibero.database.tools`입니다. 각 API 프로필의 가장 낮은 SDK로 빌드한 결과물을 해당 범위의 IDE에서 확인합니다. 서로 다른 프로필의 SDK는 검사 명령을 나누어 실행합니다.

공식 [JetBrains Plugin Verifier](https://github.com/JetBrains/intellij-plugin-verifier)를 별도로 다운로드한 뒤 다음 명령을 사용할 수 있습니다.

```sh
python3 check-compatibility.py \
  --ide '/path/to/idea-2026.1.5/Contents' \
  --ide '/path/to/idea-2026.2.3/Contents' \
  --verifier '/path/to/verifier-all.jar'
```

호환성 오류가 발견되면 검사가 실패합니다.

내부 API, 실험 API, deprecated API 및 제거 예정 API에 관한 경고도 확인해야 합니다. 플러그인은 Database Tools의 내부 인터페이스를 사용하므로 IDE 버전 변경 시 관련 API의 호환성에 주의해야 합니다.

### Grid Core 의존성

IDE 2026.2에서는 일부 Grid 관련 클래스가 별도의 bundled plugin으로 분리되었습니다.

이에 따라 `plugin.xml`은 `intellij.grid.core.plugin`을 선택적 의존성으로 선언합니다.

관련 선언은 다음 파일에서 확인할 수 있습니다.

```text
resources/META-INF/plugin.xml
```

Grid Core의 분리로 인해 `GridDataRequest.GridDataRequestOwner` 등 관련 클래스의 의존성을 올바르게 선언해야 합니다.

## 5. 재시작 후 설정 복원 검사

DB 링크 목록과 원격 데이터 소스 매핑은 IDE 설정에 저장됩니다.

설정 저장과 복원을 서로 다른 IDE 프로세스에서 검사할 수 있습니다.

먼저 동일한 테스트용 sandbox를 지정합니다.

```sh
export TIBERO_POC_SKIP_BUILD=1
export TIBERO_POC_ARTIFACT="$PWD/build/tibero-intellij-verification.jar"
export TIBERO_POC_SANDBOX='/tmp/tibero-persistence-262'
```

첫 번째 IDE 프로세스에서 저장 단계를 실행합니다.

```sh
TIBERO_POC_PERSISTENCE_PHASE=write python3 verify-ide.py
```

이어서 새로운 IDE 프로세스에서 읽기 단계를 실행합니다.

```sh
TIBERO_POC_PERSISTENCE_PHASE=read python3 verify-ide.py
```

두 단계는 같은 sandbox를 사용해야 합니다.

이 검사는 IDE 프로세스 간에 다음 정보가 유지되는지 확인하기 위한 것입니다.

- 수집된 DB 링크 목록
- 링크별 원격 데이터 소스 매핑
- 저장된 링크 관련 설정

대화형 IDE 검사는 실제 SQL 콘솔에서 저장된 메타데이터를 사용하는 동작까지 별도로 확인합니다.

## 6. Mock JDBC 환경

실제 Tibero 서버 없이 IDE와 JDBC 통합 동작을 테스트할 수 있도록 개발용 Mock JDBC 드라이버가 포함되어 있습니다.

관련 코드는 `mock-tibero/`에 있습니다.

Mock 환경은 H2와 별도로 준비한 Tibero 6 JDBC JAR를 이용하며, Tibero JDBC 메타데이터 조회에 필요한 SQL을 처리합니다.

### Mock JDBC 빌드

H2 2.2.224 JAR를 공식 Maven Central 배포본에서 준비합니다.

```sh
export MOCK_TIBERO_H2='/path/to/h2-2.2.224.jar'
export TIBERO_POC_DRIVER='/path/to/licensed/tibero6-jdbc.jar'

python3 mock-tibero/build.py
```

자세한 사항은 [Mock JDBC 안내](../mock-tibero/README.md)를 참고하세요.

### IDE에 Mock 드라이버 등록

일반 개발 환경과 구분되는 IDE sandbox를 사용하고, 실제 설치용 플러그인을 설치합니다.

IDE의 기존 Tibero 드라이버 정의를 복제한 뒤 다음과 같이 설정합니다.

| 설정 | 값 |
|---|---|
| Driver class | `mock.tibero.MockTiberoDriver` |
| Dialect | Oracle |
| DBMS | Tibero |
| JDBC URL | `jdbc:tibero:thin:@localhost:8629:tibero` |
| 로컬 사용자 | `DEMO` |
| 원격 사용자 | `HR` |
| 비밀번호 | 빈 값 |

드라이버 파일에는 다음 JAR를 등록합니다.

1. Mock JDBC JAR
2. H2 2.2.224 JAR
3. Tibero 6 JDBC JAR

### 테스트 데이터

Mock 데이터에는 다음 객체가 준비됩니다.

| 객체 | 설명 |
|---|---|
| `DEMO.SALES_LINK` | private DB 링크 |
| `PUBLIC.PUBLIC_SALES` | PUBLIC DB 링크 |
| `HR.ORDERS` | 원격 테이블 |
| `HR.CUSTOMERS` | 원격 테이블 |
| `EMP` | 콘솔 쿼리 확인에 사용하는 테이블 |

`EMP`에는 5개의 테스트 행이 있습니다.

### SQL 콘솔 검사 순서

1. `DEMO`와 `HR` 데이터 소스를 등록합니다.
2. 각 데이터 소스에서 필요한 스키마를 Refresh합니다.
3. `DEMO`의 `SALES_LINK`를 `HR` 데이터 소스 및 스키마에 매핑합니다.
4. `DEMO`에 연결된 SQL 콘솔을 엽니다.
5. 로컬 테이블과 `@SALES_LINK` 자동완성을 확인합니다.
6. `ORDERS@SALES_LINK`의 테이블 및 컬럼 자동완성을 확인합니다.
7. 자동완성 결과를 선택했을 때 SQL에 올바르게 삽입되는지 확인합니다.
8. 링크·원격 테이블·컬럼의 정의 이동을 확인합니다.
9. 링크 트리 노드를 더블클릭하여 생성 DDL이 열리는지 확인합니다.

사용할 SQL 예제:

```sql
SELECT *
FROM ORDERS@SALES_LINK;

SELECT o.AMOUNT
FROM ORDERS@SALES_LINK o;
```

로컬 JDBC 콘솔의 쿼리 실행 문맥도 확인합니다.

```sql
SELECT COUNT(*)
FROM EMP;
```

정상 실행 후에도 원격 컬럼 자동완성이 동작하는지 확인합니다.

다음 항목도 함께 확인합니다.

- 존재하지 않는 링크·테이블·컬럼은 미해결 참조로 유지되는지
- 동일한 이름의 로컬 객체로 잘못 연결되지 않는지
- SQL 링크 이름에서 DDL로 이동하는지
- DDL 문서를 닫은 뒤 트리 더블클릭으로 다시 열리는지
- IDE 재시작 후에도 원격 매핑과 자동완성이 유지되는지

재시작 검사는 모델을 새로 주입하지 않고 IDE가 저장한 메타데이터와 플러그인 설정을 사용합니다.

Mock JDBC 환경은 링크를 통한 원격 SQL 실행까지 구현하지 않습니다. 이 환경의 목적은 IDE와 JDBC 메타데이터 통합 기능을 확인하는 것입니다.

## 7. 개발 시 유의사항

- 사용자 배포물에는 Mock JDBC JAR를 포함하지 않습니다.
- 테스트 실행용 `VerifyStarter` 및 `*Verification` 클래스를 배포용 JAR에 포함하지 않습니다.
- Tibero JDBC 드라이버 및 IntelliJ IDEA 바이너리를 배포물에 포함하지 않습니다.
- IDE 버전을 변경할 때는 런타임 검사와 Plugin Verifier 검사를 함께 수행합니다.
- 사용자에게 배포할 ZIP/JAR와 검사용 JAR를 명확히 구분합니다.

릴리스 파일 준비 및 게시 절차는 [배포 가이드](RELEASING.md)를 참고하세요.
