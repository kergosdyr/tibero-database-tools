# 배포 가이드

Tibero Database Tools의 버전 관리, 배포 파일 생성, 호환성 검사 및 GitHub Releases·JetBrains Marketplace 게시 절차를 설명합니다.

## 1. 버전과 배포 구성

### 플러그인 식별자

플러그인 ID는 다음 값을 유지합니다.

```text
local.tibero.offline-poc
```

기존 설치본과 업데이트 호환성을 유지하기 위한 식별자입니다.

플러그인 버전과 IDE 호환성 범위는 다음 파일에서 관리합니다.

```text
resources/META-INF/plugin.xml
```

0.7.8의 설정은 다음과 같습니다.

```xml
<version>0.7.8</version>
<idea-version since-build="261.27258.48" until-build="262.*"/>
```

### 배포 파일

빌드 결과물은 용도에 따라 구분합니다.

| 파일 | 용도 |
|---|---|
| `build/distributions/tibero-database-tools-<version>.zip` | 사용자 설치용 배포 파일 |
| `build/tibero-intellij-poc.jar` | 사용자 설치용 플러그인 JAR |
| `build/tibero-intellij-verification.jar` | IDE 검사 전용 결과물 |

사용자에게는 설치용 ZIP 또는 JAR를 제공합니다.

검사용 JAR와 Mock JDBC 드라이버는 배포 파일에 포함하지 않습니다.

## 2. 릴리스 준비

### 버전 및 변경 기록

릴리스를 만들 때 다음 파일을 확인합니다.

- `resources/META-INF/plugin.xml`: 버전과 IDE 호환성
- `CHANGELOG.md`: 변경된 기능 및 수정 사항
- `README.md`: 영문 설치와 주요 기능 안내
- `README.ko.md`: 한국어 설치와 주요 기능 안내
- `docs/USAGE.ko.md`: 사용자 가이드
- `docs/MARKETPLACE.md`: 영문 제품 소개 및 변경 사항
- `RELEASE-NOTES.md`: GitHub 릴리스 설명

기능 및 호환성 표기는 해당 릴리스에서 실제 제공하는 범위와 일치해야 합니다.

### 배포용 빌드

지원하는 가장 낮은 IDE SDK를 지정하여 빌드합니다.

```sh
export TIBERO_POC_IDE='/path/to/idea-2026.1.5/Contents'
export TIBERO_POC_DRIVER='/path/to/licensed/tibero6-jdbc.jar'

python3 build.py
```

JDBC 회귀 검사를 포함하려면 라이선스를 보유한 드라이버를 지정합니다.

빌드 결과물의 위치는 [개발 가이드](DEVELOPMENT.md)를 참고하세요.

## 3. 릴리스 검사

### 기본 검사

배포 전에 다음 항목을 확인합니다.

1. 오프라인 빌드 및 테스트
2. JDBC 메타데이터 회귀 검사
3. 지원 IDE별 런타임 검사
4. 별도 IDE 프로세스를 이용한 설정 저장·복원
5. 실제 SQL 콘솔에서의 DB 링크 자동완성 및 탐색
6. 최종 배포 파일에 대한 Plugin Verifier 검사

테스트에 사용한 IDE와 실제 배포 대상 바이너리가 일치하도록 관리합니다.

### 실제 Tibero 연결 검사

실제 Tibero 서버에서 호환성을 확인할 때는 지원 범위에 포함할 서버 및 JDBC 드라이버 버전별로 검사합니다.

주요 확인 항목:

- Tibero JDBC 연결
- 스키마 선택 및 Refresh
- 프로시저·함수·패키지 메타데이터
- 객체 소스 열기
- DB 링크 목록 및 DDL
- 원격 데이터 소스 매핑
- 원격 테이블·컬럼 자동완성
- SQL 참조 해석 및 정의 이동
- IDE 재시작 후 설정 유지
- SQL 실행 이후 콘솔 문맥 유지

실행 기능은 변경 가능한 시험용 DB에서 별도로 확인합니다.

- Explain Plan
- Explain Analyse 및 실제 통계 수집
- DBMS_OUTPUT
- 소스 편집과 Submit
- 컴파일 오류 조회
- 루틴 실행문 생성

Tibero 6과 Tibero 7의 지원 여부를 안내할 때는 각 서버 및 JDBC 드라이버 조합에서 확인한 범위를 구분합니다.

### IDE 호환성

다음 명령으로 지원 IDE의 바이너리 호환성을 검사할 수 있습니다.

```sh
python3 check-compatibility.py \
  --ide '/path/to/idea-2026.1.5/Contents' \
  --ide '/path/to/idea-2026.2.3/Contents' \
  --verifier '/path/to/verifier-all.jar'
```

[JetBrains Plugin Verifier](https://github.com/JetBrains/intellij-plugin-verifier)를 사용합니다.

바이너리 호환성 오류와 내부·실험·폐기 예정 API 경고를 구분하여 확인합니다. 내부 API는 [공식 교체 및 예외 목록](https://plugins.jetbrains.com/docs/intellij/api-internal.html)을 참고합니다.

IDE 2026.2에서 분리된 Grid Core 의존성도 검사 대상에 포함합니다.

지원 IDE의 빌드 범위는 [JetBrains Build Number Ranges](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)를 참고합니다.

## 4. 배포 파일 점검

### 포함 파일

배포용 JAR와 ZIP에 다음 내용이 포함되었는지 확인합니다.

- 플러그인 메타데이터
- 실제 플러그인 구현 클래스
- 필요한 리소스 및 아이콘
- 선언된 플러그인 의존성 정보

다음 내용은 포함하지 않습니다.

- `VerifyStarter`
- 검사 전용 `*Verification` 클래스
- Mock JDBC 클래스 및 JAR
- Tibero JDBC 드라이버
- IntelliJ IDEA SDK 및 IDE 클래스

### 체크섬

배포할 파일의 SHA-256을 기록합니다.

macOS:

```sh
shasum -a 256 build/distributions/*.zip
shasum -a 256 build/tibero-intellij-poc.jar
```

Linux:

```sh
sha256sum build/distributions/*.zip
sha256sum build/tibero-intellij-poc.jar
```

릴리스에는 해당 배포 파일과 일치하는 체크섬 정보를 첨부합니다.

## 5. GitHub Releases 게시

1. `plugin.xml`과 `CHANGELOG.md`의 버전을 일치시킵니다.
2. 최종 빌드 및 호환성 검사를 완료합니다.
3. 배포 ZIP과 JAR의 체크섬을 기록합니다.
4. Git 태그를 생성합니다.
5. GitHub Releases에 해당 버전의 릴리스를 작성합니다.
6. ZIP, JAR 및 체크섬 파일을 첨부합니다.
7. `RELEASE-NOTES.md`의 내용을 릴리스 본문에 반영합니다.

버전 `0.7.8`의 Git 태그 형식은 다음과 같습니다.

```text
v0.7.8
```

릴리스 본문에는 사용자가 필요한 정보를 바로 찾을 수 있도록 주요 변경 사항, 설치 방법, 지원 IDE 범위 및 사용 가이드 링크를 배치합니다.

## 6. JetBrains Marketplace 게시

Tibero Database Tools를 JetBrains Marketplace에 게시하기 위한 준비 사항과 제출 절차입니다.

### 게시 정보

| 항목 | 내용 |
|---|---|
| Plugin name | Tibero Database Tools |
| Vendor | `kergosdyr` (게시자 프로필에서 확정) |
| Price | Free |
| License | Apache License 2.0 |
| Source code | https://github.com/kergosdyr/tibero-database-tools |

### 게시 자료

- **영문 소개:** `docs/MARKETPLACE.md` 및 플러그인 설명 HTML
- **프로젝트 문서:** 영문 `README.md`, 한국어 `README.ko.md`
- **이미지:** 플러그인 아이콘과 `docs/images/`의 실제 IDE 스크린샷
- **릴리스 정보:** 변경 사항, 버전, 지원 IDE 범위 및 설치 요구 사항
- **배포 파일:** 최종 서명된 플러그인 ZIP

### 최초 게시 절차

1. **JetBrains 계정 로그인:** [JetBrains Marketplace](https://plugins.jetbrains.com/)에 개인 JetBrains 계정으로 로그인합니다.
2. **Vendor 프로필 선택·생성:** 게시자 표시명을 `kergosdyr`로 설정하고 유효한 연락 이메일과 웹사이트를 등록합니다. Vendor ID는 생성 후 변경할 수 없으므로 신중하게 지정합니다. 개인의 실제 활동·사업 상황에 맞게 `trader` 또는 `non-trader` 상태를 신고하며, 무료 배포 여부만으로 판단하지 않습니다. [Vendor 프로필 안내](https://plugins.jetbrains.com/docs/marketplace/organizations.html)
3. **개발자 약관 동의:** 최초 플러그인 게시 과정에서 JetBrains Marketplace Developer Agreement에 동의합니다.
4. **제출 전 최종 확인:** 최종 ZIP에 소개문·버전·호환성 정보가 반영되었는지 확인하고 Plugin Verifier로 대상 IDE의 바이너리 호환성을 검사합니다. 플러그인 서명과 서명 확인에 관한 사항은 [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)을 참고합니다.
5. **최초 플러그인 수동 업로드:** 계정 메뉴의 **Upload plugin**에서 Vendor를 선택하고 최종 서명 ZIP을 제출합니다. 영문이 먼저 나오는 제품 설명, 적절한 태그, 오픈소스 라이선스와 소스 링크, 아이콘·스크린샷, 변경 사항 및 지원 IDE 정보를 입력합니다. 최초 등록은 수동으로 진행합니다. [신규 플러그인 업로드](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)
6. **심사 및 공개:** 제출된 플러그인은 자동 검사와 JetBrains Marketplace 팀의 수동 심사를 거치며, 승인 후 설정된 공개 상태에 따라 게시됩니다. [Marketplace 승인 기준](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)

### 공개 시점과 후속 업데이트

- **Hidden:** 공개 전에 심사를 받고 공개 시점을 별도로 정하려면 업로드 시 Hidden 옵션을 선택합니다.
- **Custom Release Channel:** 테스트 또는 사전 배포용 버전은 별도 릴리스 채널로 관리할 수 있습니다. [업로드 옵션 안내](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)
- **심사 일정:** 심사 완료 시점은 보장되지 않습니다. 업로드 후 **3~4영업일** 동안 응답이 없으면 `marketplace@jetbrains.com`으로 문의합니다. [승인 절차](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)
- **버전 업데이트:** 최초 등록 이후에는 Marketplace에서 수동으로 새 버전을 올리거나 게시 토큰을 이용한 자동화 방식으로 업데이트할 수 있습니다. [플러그인 게시 가이드](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)

서명 키와 게시 토큰은 저장소에 커밋하지 않고 별도로 관리합니다.

## 7. 보안 및 라이선스

배포 과정에서는 다음 사항을 준수합니다.

- Apache License 2.0의 LICENSE와 NOTICE를 유지합니다.
- Tibero JDBC 드라이버를 재배포 파일에 포함하지 않습니다.
- IntelliJ IDEA 바이너리와 SDK 클래스를 배포하지 않습니다.
- 서명 키, Marketplace 인증 토큰 및 DB 비밀번호를 저장소에 커밋하지 않습니다.
- 개인 IDE 설정과 데이터 소스 접속 정보를 배포 파일에 포함하지 않습니다.

플러그인의 DB 링크 매핑 설정에는 HOST, USERNAME 및 데이터 소스 식별자가 포함될 수 있으므로 실제 사용자 설정 파일을 예제나 배포 자료로 사용하지 않습니다.

새 버전을 배포할 때는 [개발 가이드](DEVELOPMENT.md)의 테스트 절차와 이 문서의 릴리스 검사를 함께 적용합니다.
