# 출시 준비

현재 배포 방식은 **무료·소스 공개, Apache 2.0**입니다. `0.7.8-rc.1`은 검증용 출시 후보입니다. Marketplace 정식 업로드는 아직 하지 않았습니다.

## 준비한 항목

- 독립 저장소용 소스, Apache 2.0 LICENSE/NOTICE, 설치·사용·개발 문서.
- 사용자용 ZIP/JAR와 검사 전용 JAR의 분리, 플러그인 아이콘·영문 소개·변경 기록.
- DB 링크의 SQL 참조 해석, 원격 모델 매핑, 더블클릭 및 최신 IDE 탐색 호출 수정.
- 원격 테이블/컬럼 후보의 실제 삽입, SQL 정의 이동, JDBC 조회 후 문맥, 재시작 복원 UI 검사.
- 261/262 런타임 검사 및 공식 Plugin Verifier 검사 절차.

플러그인 ID `local.tibero.offline-poc`는 기존 설치본의 업데이트를 위해 유지합니다. 파일 이름의 `poc` 역시 기존 JAR 설치 경로 호환을 위한 이름이며, 배포 ZIP 이름은 `tibero-database-tools-<version>.zip`입니다.

## 정식 출시 전에 남은 조건

1. **실제 Tibero 서버 검증:** 6/7 중 지원을 광고할 버전별로 정상 JDBC 연결, 선택 스키마 Refresh, 링크 목록/DDL, 원격 모델 매핑, 테이블·컬럼 완성, 재시작, 연결 후 문맥을 확인합니다. Tibero 7 JDBC도 별도로 확인합니다.
2. **실제 실행 기능:** Explain Plan/Analyse, DBMS_OUTPUT, 소스 Submit·컴파일 오류를 시험용 DB에서 확인합니다. 미검증 기능을 검증 완료로 소개하지 않습니다.
3. **최종 호환성:** 배포할 정확한 ZIP/JAR를 Plugin Verifier와 지원 IDE에 통과시킵니다. 내부/실험/폐기 예정 API 경고는 유지보수 위험으로 공개합니다. 미검증 DataGrip·구버전 IDEA는 지원 범위에 추가하지 않습니다.
4. **Marketplace 계정·서명·최초 제출:** 게시자 JetBrains 계정, 소개·스크린샷·라이선스 정보를 확정하고 서명 및 최초 수동 업로드를 수행합니다. 게시자 약관 수락은 계정 소유자가 진행합니다.

[JetBrains 게시 절차](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)는 최초 플러그인을 수동으로 업로드하도록 안내합니다. [호환성 기준](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)에 따라 광고하는 빌드 범위를 검사합니다. [의존성 선언](https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html)에는 최신 IDE의 Grid Core 분리를 반영합니다.

## 기능 범위 결정

첫 출시 전에 필수로 추가할 새 기능은 없습니다. DB 링크 원격 메타데이터 자동 수집, 생성/수정 대화상자, 전체 Oracle 모델 전환, PL/SQL 디버거는 범위를 크게 넓힙니다. 이번 버전은 명시적 원격 모델 매핑을 제품 동작으로 문서화합니다. 모델이 없는 객체의 경고를 숨기는 기능은 넣지 않습니다.

실서버 검증은 새 기능과 별개인 출시 조건입니다. 해당 검증을 통과하기 전에는 RC/실험 버전으로 배포합니다.

## 배포 절차

1. 버전 및 change-notes를 갱신하고 최소 SDK로 빌드합니다.
2. 오프라인(라이선스 있는 JDBC 포함), 261/262 런타임, 두 프로세스 저장/복원, UI 검사를 실행합니다.
3. 정확한 최종 JAR/ZIP에 대해 Plugin Verifier를 실행하고 SHA-256을 기록합니다.
4. JAR 안에 `VerifyStarter`, `*Verification`, mock/JDBC/IDE 클래스가 없는지 확인합니다.
5. Git 태그와 GitHub 사전 릴리스에 ZIP/JAR·체크섬을 첨부합니다.
6. 실서버 검증이 끝나면 정식 버전을 만들고 Marketplace 제출을 진행합니다.

서명 키·게시 토큰·DB 암호·개인 IDE 설정을 저장소에 넣지 않습니다. 플러그인은 자체 원격 분석/텔레메트리 전송 기능을 갖지 않습니다. IDE 자체의 기능과 설정은 별도입니다.
