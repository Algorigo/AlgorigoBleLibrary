# TODO

`algorigoble2` 라이브러리 정적 분석 결과입니다. (기준 커밋 `6670eb9`, `stash@{0}`의 WIP 변경은 포함하지 않음)
이후 실기기 테스트(SM-F946N, Android 16, 2026-10-14)에서 발견한 항목을 추가했고, 해당 항목에는 **(실기기)** 표시를 붙였습니다.

- 소스 경로는 `algorigoble2/src/main/java/com/algorigo/algorigoble2/` 기준이며, 라인 번호는 위 커밋 기준입니다. 실기기 테스트로 추가한 항목은 추가 시점의 `v2/develop` 기준입니다.
- 태그: `[버그]` 잠재적 버그 · `[구조]` 구조 개선 · `[기능]` 빠진 BLE 기능 · `[배포]` 빌드/배포 · `[샘플앱]` `app` 모듈(샘플 앱) 문제
- 우선순위
  - **P0**: 기능이 아예 동작하지 않거나, 크래시/배포 실패로 이어지는 문제
  - **P1**: 일반적인 사용 중에도 오동작할 가능성이 높은 문제
  - **P2**: 특정 조건에서 발생하는 문제, 중요한 구조 개선, 자주 쓰이는 누락 기능
  - **P3**: 정리, 편의 기능, 장기 과제

---

## P0 — 즉시 수정

- [x] **[버그] `setMtuCompletable()`이 `requestMtu()`를 호출하지 않음** — `impl/BleDeviceEngineImpl.kt:517-526`
  - ✅ 해결 (`feature/fix_set_mtu`)
    - `BleDevice.requestMtuSingle(mtu): Single<Int>`를 추가했습니다. `requestMtu()`를 호출한 뒤 `onMtuChanged`를 기다려서 **협상된 MTU**를 반환합니다.
    - `onMtuChanged`의 status가 실패이면 `BleManager.MtuChangeFailedException(status)`이 나고, `requestMtu()`가 false를 반환하면 기존 작업과 같은 방식으로 재시도합니다.
    - `setMtuCompletable()`은 `requestMtuSingle().ignoreElement()`로 유지하여 호환성을 지켰습니다. engine 추상 메서드는 `setMtuCompletable` → `requestMtuSingle`로 변경했습니다.
    - `VirtualDeviceEngine`은 연결 상태일 때 23~517 범위로 보정한 값을 반환합니다.
    - 테스트: `BleDeviceEngineImplMtuTest`, `VirtualDeviceEngineMtuTest`. 테스트 인프라(MockK, `unitTests.returnDefaultValues`, `GattTestFixture`, `RxTestSchedulerRule`)도 이 브랜치에서 추가했습니다.
    - MTU 요청에 타임아웃이 없던 문제는 아래 "작업 무한 대기" 항목(`feature/fix_gatt_operation_hang`)에서 함께 해결했습니다.
    - 실기기 확인(Fold5 → S23 nRF Connect GATT 서버): `configureMTU(517)` → `onConfigureMTU(517, 0)`을 받은 뒤 517을 반환했습니다. 247을 요청해도 링크의 실제 MTU인 517을 반환했습니다. 이미 교환된 링크라서 요청값과 협상값이 다를 수 있습니다.
  - 문제: `getGattSingle().map { Single... }.ignoreElement()` 구조라서 안쪽 `Single`이 구독되지 않습니다. 결과적으로 MTU 요청은 보내지지 않고 Completable만 바로 완료됩니다. (최근 커밋 `9f18f3d`에서 추가된 기능)
  - 제안: `flatMap`으로 바꾸고, `onMtuChanged` 콜백을 relay로 받아서 **협상된 MTU 값을 `Single<Int>`로 반환**해야 합니다. 현재 `onMtuChanged`(176)는 로그만 남깁니다. Android 14 이상에서는 요청 값과 상관없이 517로 협상을 시도하므로, 실제 협상된 값을 반환하는 것이 중요합니다.

- [x] **[버그] read/write/descriptor 작업이 무한 대기할 수 있음** — `impl/BleDeviceEngineImpl.kt:324-355, 357-401, 467-496`
  - ✅ 해결 (`feature/fix_gatt_operation_hang`)
    - `BleDeviceEngineImpl`에 `guardOperation()`을 추가하여 read/write characteristic, CCCD descriptor write(`setupNotification`), MTU 요청에 적용했습니다.
      - 작업 도중 연결이 끊기면 `BleManager.DisconnectedException`, `BleDevice.operationTimeoutMillis`(기본 `DEFAULT_OPERATION_TIMEOUT_MILLIS` = 10초)를 넘기면 `TimeoutException`으로 종료됩니다.
      - 연결 끊김 감지를 먼저 구독하므로, 이미 끊긴 상태에서는 GATT 호출 없이 바로 실패합니다.
    - `setupNotification`에서는 descriptor write에만 타임아웃이 적용됩니다. 설정이 끝난 뒤의 notification 스트림은 시간이 지나도 끊기지 않습니다.
    - 그 결과 `InitializableBleDevice.initializeCompletable()` 안의 작업이 응답을 받지 못해도 `connectCompletable()`이 에러로 끝나고 연결이 해제됩니다.
    - JVM 테스트에서 `BluetoothGattDescriptor.*_VALUE` static 필드가 null이 되는 문제 때문에, CCCD 값(`0x0001`/`0x0002`/`0x0000`)을 라이브러리 안에 상수로 정의했습니다. Bluetooth 스펙 정의값이므로 실기기 동작은 동일합니다.
    - 테스트: `BleDeviceEngineImplOperationGuardTest`, `InitializableBleDeviceOperationGuardTest`. guard를 비활성화하면 버그 시나리오 테스트 10개가 실패하는 것을 확인했습니다.
    - 실기기 확인(Fold5 → S23 nRF Connect GATT 서버):
      - `operationTimeoutMillis=1`로 read하면 `TimeoutException`이 나고, 다음 작업은 정상 동작했습니다.
      - 연결이 끊긴 뒤 read는 GATT 호출 없이 1ms 만에 `DisconnectedException`으로 끝났습니다.
      - 연결이 끊기면 진행 중인 notification 스트림도 `DisconnectedException`으로 종료됐습니다.
      - 그 밖에 read/write/notify/indicate가 모두 정상 동작했습니다.
  - 문제: GATT 호출 후 `replyRelay`에서 응답이 오기를 기다리는데, **타임아웃이 없고 연결 끊김도 감지하지 않습니다**. `getGattSingle()`은 `firstOrError()`이므로 이후의 상태 변화는 보지 않습니다.
  - 영향: 작업 도중 연결이 끊기거나 콜백이 유실되면 Single이 영원히 끝나지 않습니다. `InitializableBleDevice.initializeCompletable()` 안에서 이런 일이 생기면 `connectCompletable()`도 끝나지 않습니다.
  - 제안: 작업마다 타임아웃을 두고, `stateRelay`에서 CONNECTED가 아닌 상태가 오면 `DisconnectedException`이 나도록 merge합니다.

- [x] **[버그] 블루투스가 꺼진 상태에서 connect를 호출하면 CONNECTING 상태에 영구히 머무름** — `impl/BleDeviceEngineImpl.kt:264-292`
  - ✅ 해결 (`feature/fix_connect_stuck_connecting`)
    - `connectGatt()`가 null을 반환하면 상태를 CONNECTING으로 바꾸기 전에 `BleManager.ConnectFailedException`으로 실패합니다. 상태는 DISCONNECTED로 유지되므로 다시 연결할 수 있습니다.
    - 연결이 완료되기 전에 구독이 dispose되면(연결 취소) `gatt.disconnect()/close()`를 호출하고, DISCONNECTED와 `onDisconnected()`를 알립니다. 연결이 완료된 뒤의 dispose는 무시하여 기존 동작을 유지합니다.
    - 타임아웃과 취소의 정리 로직을 `cancelConnecting()`으로 통합했습니다. 정리는 해당 구독이 직접 `connectGatt`로 만든 gatt가 있을 때만 수행합니다.
    - connect 로그에서 아직 할당되지 않은 `gatt` 대신 `bluetoothDevice`의 이름과 주소를 출력하도록 수정했습니다.
    - 테스트: `BleDeviceEngineImplConnectTest`. 수정 전 코드로 실행하면 null 반환 2건과 취소 1건이 실패하는 것을 확인했습니다.
    - 실기기 확인(SM-F946N, Android 16):
      - BT OFF 상태에서는 `connectGatt()`가 실제로 null을 반환하고, 즉시 `ConnectFailedException`이 납니다. 다시 시도해도 같은 결과여서 상태가 갇히지 않는 것을 확인했습니다.
      - 존재하지 않는 MAC으로 연결하면 10초 뒤 `TimeoutException`이 나고, `cancelOpen()` → `close()`로 정리됩니다.
  - 문제: BT가 꺼져 있으면 `connectGatt()`가 `null`을 반환합니다. 하지만 상태는 `CONNECTING`으로 바뀌고, 타임아웃이 나도 `gatt?.also { ... }`가 실행되지 않아서 `DISCONNECTED`로 되돌아가지 않습니다.
  - 영향: 앱을 재시작하기 전까지 해당 기기의 connect는 모두 `IllegalStateException("Connection state is CONNECTING ...")`로 실패합니다.
  - 제안: `connectGatt()`가 null을 반환하면 즉시 에러를 내고 상태를 복구합니다. 타임아웃 처리 블록에서는 gatt가 null이어도 상태를 복구합니다. 구독자가 dispose했을 때(연결 취소)도 `doOnDispose`로 `disconnect()/close()`를 처리합니다. 지금은 취소한 뒤에도 연결이 뒤늦게 성공할 수 있습니다.

- [x] **[버그] 스캔 중 블루투스를 끄면 앱이 크래시날 가능성이 높음** — `impl/BleScanner.kt:66-77`
  - ✅ 해결 (`feature/fix_scan_crash_on_bluetooth_off`)
    - `stopScan()`은 scanner가 null이면 건너뛰고, `stopScan`이 던지는 예외(`IllegalStateException` 등)는 warning 로그만 남기고 무시합니다. 그래서 `doFinally`에서 `RxJavaPlugins.onError`로 예외가 새어 나가지 않습니다.
    - `startScan()`은 scanner가 null이면 NPE 대신 `SystemServiceException.BluetoothUnavailableException`으로 실패합니다.
    - 테스트: `BleScannerTest`. 수정 전 코드에서는 NPE와 `IllegalStateException`이 `RxJavaPlugins.onError`로 전달되어 테스트가 실패하는 것을 확인했습니다.
    - 실기기 확인(SM-F946N, Android 16): 스캔 중 BT를 꺼도 크래시가 나지 않고, BT를 다시 켜면 샘플 앱의 재시도 로직으로 스캔이 재개됩니다. 다만 이 기기에서는 `stopScan()`이 예외 없이 끝나서 NPE 경로 자체는 재현되지 않았습니다. 이 경로는 타이밍과 OS 버전에 따라 달라집니다.
  - 문제: BT가 `STATE_TURNING_OFF`가 되면 `bluetoothAdapter.bluetoothLeScanner`가 `null`을 반환하는데, `doFinally`에서 호출하는 `stopScan()`이 NPE를 냅니다. `doFinally`에서 던진 예외는 `RxJavaPlugins.onError`로 전달되므로, 앱이 `RxJavaPlugins.setErrorHandler`를 설정하지 않았다면 크래시가 납니다.
  - 제안: `startScan/stopScan`에서 scanner null 체크를 하고 try/catch로 감쌉니다. `startScan`의 `doOnSubscribe`에서도 null이면 명확한 에러를 냅니다.

- [x] **[배포] 배포되는 POM에 의존성이 하나도 없음** — `algorigoble2/build.gradle:53, 131-145`
  - ✅ 해결 (`feature/fix_pom_dependencies`)
    - 수동 `artifact(aar)` 대신 `from components.release`로 publication을 구성했습니다. AGP가 component를 afterEvaluate 시점에 만들기 때문에 publication/signing은 `afterEvaluate`로 감쌌습니다. publish가 `bundleReleaseAar`에 자동으로 의존하므로, 미리 assemble하지 않아도 됩니다.
    - `android.publishing.singleVariant("release") { withSourcesJar(); withJavadocJar() }`를 추가하여 Central 요건인 `-sources.jar`와 `-javadoc.jar`를 함께 배포합니다.
    - 공개 API에 노출되는 의존성을 `api`로 바꿨습니다. `io.reactivex.rxjava3:rxjava:3.1.5`(기존 전이 의존성으로 해석되던 버전과 동일)와 `algorigo-logger`가 해당됩니다.
    - 결과 POM: 이전 0개 → compile 3개(logger, rxjava, kotlin-stdlib) + runtime 5개(core-ktx, rxkotlin, rxandroid, rxrelay, gson)
    - 회귀 방지: `verifyPublicationPom` 태스크를 `check`에 연결했습니다. 선언된 `api`/`implementation` 의존성이 POM에 올바른 scope로 있는지 `./gradlew build`(CI)에서 검사합니다. `from components.release`를 제거하면 실패하는 것을 확인했습니다.
    - 남은 확인 사항: 실제 Central 업로드는 CI의 `v2/master`에서만 가능해서 검증하지 못했습니다. 2.7.0이 정상 배포되었는지도 별도 확인이 필요합니다.
  - 문제: publication이 `artifact("...aar")`로 AAR만 수동 첨부하고 있어서 POM에 dependency가 생성되지 않습니다. 예전에는 `pom.withXml`로 일부를 수동 추가했지만, `190f597`(revert)에서 이 코드도 함께 제거되었습니다.
  - 영향: Maven Central에서 받아 쓰는 앱이 RxJava/RxKotlin/RxRelay/algorigo-logger를 직접 추가하지 않으면 런타임에 `NoClassDefFoundError`가 납니다. 샘플 앱(`app/build.gradle:49-54`)은 이 라이브러리들을 직접 추가하고 있어서 문제가 드러나지 않습니다.
  - 제안: `android { publishing { singleVariant("release") { withSourcesJar(); withJavadocJar() } } }`와 `from components.release`를 사용합니다. 공개 API에 노출되는 의존성(RxJava `Observable/Completable/Single`, 공개 `Ble` Tag가 상속하는 `algorigo-logger`)은 `implementation` 대신 `api`로 선언합니다.
  - 참고: Central Portal 검증 요건상 `-sources.jar`와 `-javadoc.jar`도 필요합니다. 현재 설정으로 2.7.0이 실제로 배포되었는지 확인이 필요합니다.

- [x] **[배포] CircleCI의 Maven Central 업로드 토큰이 치환되지 않음** — `.circleci/config.yml:89`
  - ✅ 해결 (`feature/fix_ci_maven_central_token`)
    - Authorization 헤더를 큰따옴표(`"Authorization: Bearer ${MAVEN_CENTRAL_TOKEN}"`)로 바꿔서 환경변수가 치환되도록 했습니다.
    - `--verbose`를 제거했습니다. 토큰이 치환되면 verbose 출력이 Authorization 헤더를 CI 로그에 그대로 남기기 때문입니다.
    - `--fail-with-body`를 추가했습니다. 업로드가 실패하면(HTTP 4xx/5xx) 응답 본문을 출력하고 job을 실패시킵니다. curl 7.76 이상이 필요하며, `cimg/android:2024.01`(Ubuntu 22.04 기반, curl 7.81)에서 지원되는 것으로 알고 있으나 실제 CI에서 확인이 필요합니다.
    - 검증: YAML 파싱과 job/workflow 구조가 변경 전과 같은지 확인했습니다. 업로드 step의 curl 명령을 stub curl과 더미 토큰으로 실행해 보니, 수정 전에는 `Bearer $MAVEN_CENTRAL_TOKEN` 문자열이 그대로 전달되고 수정 후에는 실제 값으로 치환되었습니다.
    - 남은 확인 사항: 실제 업로드는 `v2/master` CI에서만 확인할 수 있습니다. Central Portal API는 `Bearer` 뒤에 **user token의 `base64(username:password)`**를 요구하므로, CircleCI의 `MAVEN_CENTRAL_TOKEN` 값이 이 형식인지 확인이 필요합니다.
  - 문제: `--header 'Authorization: Bearer $MAVEN_CENTRAL_TOKEN'`가 작은따옴표로 감싸져 있어서 환경변수가 치환되지 않고 문자열 그대로 전송됩니다. 그래서 인증에 실패합니다.
  - 제안: 큰따옴표로 바꿉니다: `--header "Authorization: Bearer ${MAVEN_CENTRAL_TOKEN}"`. `--fail` 옵션도 추가해서 업로드가 실패하면 job이 실패하도록 합니다.

- [x] **[버그/배포] kotlin-reflect가 없는 앱에서 로그 태그를 처음 사용하는 순간 크래시남 (실기기)** — `logging/Tags.kt`, `algorigoble2/build.gradle`
  - ✅ 해결 (`feature/fix_kotlin_reflect_crash`) — 이 저장소에서 할 수 있는 우회 수정입니다.
    - `algorigoble2`에 `implementation "org.jetbrains.kotlin:kotlin-reflect:$kotlin_version"`(2.0.0)을 추가했습니다. POM에 runtime 의존성으로 들어가므로, 사용하는 앱이 kotlin-reflect를 직접 추가하지 않아도 됩니다. 샘플 앱도 `app/build.gradle` 수정 없이 전이 의존성으로 받는 것을 확인했습니다.
    - logger 최신 버전(1.2.4~1.2.6)도 확인했는데, POM 의존성이 0개이고 `KClass.objectInstance`를 그대로 사용해서 버전을 올려도 해결되지 않습니다.
    - 회귀 방지: instrumented test `LoggingTagInstrumentedTest`를 추가했습니다. MockK가 없는 라이브러리의 실제 runtime classpath에서 모든 `Ble.*` 태그 초기화와 `BleManager.getDevice()`(가상 기기)를 검사합니다.
      - 수정 전에는 2건 모두 `KotlinReflectionNotSupportedError`/`NoClassDefFoundError`로 실패했고, 수정 후에는 2/2 통과했습니다(SM-F946N).
      - 실행: `ANDROID_SERIAL=<기기> ./gradlew :algorigoble2:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.algorigo.algorigoble2.LoggingTagInstrumentedTest`. CI(`./gradlew build`)는 기기가 필요한 이 테스트를 실행하지 않습니다.
    - 실기기 확인: 수정 전에 크래시가 나던 시나리오(가상 기기 connect → DeviceActivity read)를 재현해 보니, `I Ble.Device: connected` 로그가 정상 출력되고 크래시 없이 동작했습니다.
    - 참고: kotlin-reflect는 약 3MB입니다. R8을 쓰는 앱에서는 사용하지 않는 부분이 대부분 제거됩니다.
    - 남은 작업: algorigo-logger에서 reflection을 제거하거나(`Class.getField("INSTANCE")` 등) kotlin-reflect를 선언하고, POM 의존성을 바로잡아야 합니다. logger가 수정되면 이 우회 의존성은 제거할 수 있습니다.
  - 문제: `algorigo-logger`의 `Tag`는 생성될 때 부모 태그를 `Class.forName(바깥 클래스).kotlin.objectInstance`로 찾습니다. 이 코드는 **kotlin-reflect가 필요**한데, logger의 POM에 의존성이 0개라서 kotlin-reflect가 함께 들어오지 않습니다. 1.2.3~1.2.6 모두 같은 상태입니다.
    - 그 결과 `Ble.Device`, `Ble.Engine.Default` 같은 중첩 태그가 처음 초기화될 때 `KotlinReflectionNotSupportedError`가 납니다. 최상위 태그인 `Ble`는 부모를 찾지 않으므로 괜찮습니다.
  - 영향(실기기 재현):
    - 가상 기기 connect가 성공하면 `BleDevice.connect()`의 로그에서 Error가 나고, `UndeliverableException`으로 **프로세스가 죽습니다**.
    - `BleManager.getDevice(mac)`는 항상 같은 Error로 실패해서 MAC 연결이 불가능합니다.
    - GATT 콜백 로그(바인더 스레드)와 P0-4의 `stopScan` catch 블록의 로그도 같은 경로를 탑니다. Error는 `catch (Exception)`에 잡히지 않습니다.
  - 단위 테스트는 MockK가 kotlin-reflect를 테스트 classpath에 넣기 때문에 이 문제를 잡지 못합니다.
  - 제안: 근본 수정은 algorigo-logger에서 해야 합니다(reflection 제거 또는 kotlin-reflect 의존성 선언, POM 수정). 이 저장소에서는 `kotlin-reflect`를 runtime 의존성으로 추가해서 사용하는 앱이 자동으로 받도록 합니다. 회귀 방지를 위해 실제 앱 classpath(MockK 없음)에서 동작하는 instrumented test를 추가합니다.

---

## P1 — 높음

- [x] **[버그] GATT 콜백의 `status`를 무시함** — `impl/BleDeviceEngineImpl.kt:89-154`
  - ✅ 해결 (`feature/handle_gatt_status`)
    - 공개 예외 `BleManager.GattOperationFailedException(operation, status)`를 추가했습니다. `operation`은 `READ_CHARACTERISTIC`/`WRITE_CHARACTERISTIC`/`WRITE_DESCRIPTOR`/`DISCOVER_SERVICES`입니다.
    - `ReplyData`에 콜백 status를 포함했습니다. read, write, descriptor write(notification 설정)는 status가 실패이면 값 대신 이 예외로 실패합니다. 실패한 작업 뒤의 큐 작업은 정상적으로 이어서 실행됩니다.
    - **첫 서비스 탐색 실패:** 서비스 없이 CONNECTED로 만들지 않습니다. gatt를 `disconnect()/close()`하고, `connectCompletable()`은 `GattOperationFailedException("DISCOVER_SERVICES", status)`로 실패합니다.
      - 이를 위해 `State.DISCONNECTED`에 `cause`를 추가했습니다. 에러가 전달되는 시점에 이미 DISCONNECTED 상태이므로, `onError`에서 바로 다시 연결할 수 있습니다.
    - **재탐색(`onServiceChanged`) 실패:** 연결을 유지하고 경고 로그만 남깁니다. 서비스 목록이 비면 이후 작업은 characteristic을 찾지 못해 실패합니다.
    - 테스트: `BleDeviceEngineImplGattStatusTest`(7건). 수정 전 엔진에서는 6건이 실패했습니다. 나머지 1건(재탐색 실패 시 연결 유지)은 기존 동작을 보존하는지 확인하는 테스트입니다.
    - 실기기 확인(Fold5 → S23 시스템 GATT의 readable characteristic 35개 읽기): 수정 전과 후의 결과가 같았습니다(정상 read 30건 성공). 즉 status 0인 정상 응답은 영향을 받지 않습니다.
      - 빈 값(0바이트)으로 읽힌 characteristic도 실제로 status 0이었습니다.
      - 다만 S23 시스템 GATT는 실패 status를 돌려주는 characteristic이 없어서, **실패 status 경로 자체는 실기기에서 확인하지 못했습니다**. nRF Connect 서버 등 실패 응답을 만들 수 있는 peripheral로 재검증이 필요합니다.
    - 남은 작업: 연결 중 끊김(`onConnectionStateChange`)의 status(예: 133, 8)는 아직 `DisconnectedException`에 담기지 않습니다. 이 부분은 P2 "예외 계층 정리" 항목에서 처리합니다.
  - read/write/descriptor 콜백이 `status != GATT_SUCCESS`인 경우에도 성공으로 처리합니다. 예를 들어 인증이 부족해서 read가 실패해도 이전 값이 성공으로 반환됩니다.
  - `onServicesDiscovered`도 실패한 경우에 `CONNECTED`로 전환합니다. 이 경우 서비스가 비어 있어서 이후의 모든 작업이 `IllegalStateException`으로 실패합니다.
  - 제안: `ReplyData`에 status를 포함하고, 실패하면 `GattException(status)`로 에러를 냅니다. discovery가 실패하면 연결을 실패로 처리합니다.
  - (실기기) indicate 전용 `aaa2`에 NOTIFICATION CCCD(`0x0100`)를 쓰면 라이브러리는 성공으로 보고합니다. 서버의 응답 status는 로그에 남지 않아서 실제로 거부됐는지는 확인하지 못했습니다. status 실패를 의도적으로 만들 수 있는 peripheral로 재검증이 필요합니다.

- [x] **[버그] 재연결한 뒤에도 이전 연결의 서비스 캐시를 사용함** — `impl/BleDeviceEngineImpl.kt:193, 438-449`
  - ✅ 해결 (`feature/fix_stale_service_cache`)
    - `serviceSingle` 캐시를 제거했습니다. 서비스 목록과 characteristic은 항상 **현재 연결된 `BluetoothGatt`**에서 읽습니다. 그래서 재연결 후 이전(닫힌) gatt의 characteristic 객체를 쓰지 않습니다.
    - `onServiceChanged`(API 31+)를 처리합니다. 현재 연결된 gatt이면 `discoverServices()`를 다시 호출합니다. Android는 `discoverServices()` 호출 즉시 서비스 목록을 비우기 때문에, 재탐색이 끝날 때까지(`onServicesDiscovered`) read/write/notification 설정/`getCharacteristicsSingle()`이 기다립니다. `discoverServices()`가 false를 반환하면 대기하지 않습니다.
    - 재탐색으로 `onServicesDiscovered`가 다시 와도 CONNECTED를 다시 내보내지 않습니다. 다시 내보내면 `setupNotification`이 재구독되어 CCCD를 한 번 더 쓰기 때문입니다.
    - `getCharacteristicsSingle()`에도 `guardOperation()`(타임아웃, 연결 끊김 감지)을 적용했습니다.
    - 테스트: `BleDeviceEngineImplServiceCacheTest`(8건). 수정 전 코드에서는 재연결 후 이전 목록, 이전 characteristic 사용, 재탐색 없음, CONNECTED 중복, CCCD 중복 쓰기, 재탐색 중 작업 진행 등 6건이 실패했습니다.
      - `GattTestFixture`의 mock gatt를 실제처럼 바꿨습니다. `discoverServices()`를 호출하면 목록이 비워지고, `onServicesDiscovered` 시점에 서버 DB가 반영됩니다.
    - 실기기 확인(Fold5 샘플 앱 ↔ S23 nRF Connect GATT 서버):
      - 연결을 유지한 채 S23 서버를 켜자 `onServiceChanged` → `discoverServices()` → `onServicesDiscovered`가 이어졌고, 앱을 재시작하지 않고 화면을 다시 열어도 `aaa1`/`aaa2`/`2a37` 등이 나타났습니다. 새로 나타난 `aaa1` read도 정상 동작했습니다.
      - 연결을 끊고 S23 서버를 끈 뒤 **같은 프로세스에서 재연결**하자, 사라진 서비스가 목록에서도 사라졌습니다. 수정 전에는 이전 목록이 남아 있었습니다.
    - 남은 제한 사항:
      - API 30 이하에는 `onServiceChanged` 콜백이 없어서, 연결 중의 서비스 변경은 재연결해야 반영됩니다.
      - 서비스 변경 전에 설정한 notification 구독은 자동으로 다시 설정하지 않습니다. 핸들이 바뀌는 경우(DFU 등)에는 앱이 다시 구독해야 합니다.
      - 서비스 변경을 앱에 알리는 공개 API는 없습니다.
  - `serviceSingle`은 한 번 설정되면 초기화되지 않습니다. 그래서 재연결 후에도 닫힌 이전 `BluetoothGatt`의 characteristic 객체를 새 gatt에서 사용합니다. 펌웨어 업데이트(DFU) 등으로 핸들이 바뀌면 다른 characteristic에 접근하거나 작업이 실패합니다.
  - 제안: DISCONNECTED가 되면 `serviceSingle = null`로 초기화합니다. `onServiceChanged`(API 31+)에서도 다시 discovery를 수행합니다.
  - (실기기, nRF Connect GATT 서버) 연결 중에 S23(서버)의 GATT DB가 바뀌었습니다.
    - Fold5 OS는 `onServiceChanged`를 받고 서비스를 다시 탐색했지만, 라이브러리는 이 콜백을 처리하지 않습니다.
    - 그래서 연결이 유지되는 동안은 물론, **같은 프로세스에서 끊었다 다시 연결한 뒤에도**(OS가 `onSearchComplete`로 새 목록을 받았는데도) `getCharacteristicsSingle()`이 이전 목록을 반환했습니다. 앱 프로세스를 재시작해야 새 서비스가 보였습니다.
    - 참고: bonded 기기는 robust caching(Database Hash)으로 해시가 같으면 OS가 탐색을 생략합니다. 이것은 정상적인 OS 동작입니다.

- [ ] **[버그] 같은 characteristic을 여러 곳에서 notification 구독하면, 하나만 해제해도 전부 끊김** — `impl/BleDeviceEngineImpl.kt:403-433`
  - 구독할 때마다 CCCD를 활성화하고, 하나라도 dispose되면 `doFinally`에서 CCCD를 비활성화합니다. 그래서 나머지 구독자는 에러 없이 데이터만 받지 못하게 됩니다.
  - `VirtualDevice`(`virtual/VirtualDevice.kt:23`)에는 참조 카운트가 구현되어 있어서 실기기와 동작이 다릅니다.
  - 제안: characteristic별로 공유 Observable(`share()`/`refCount()`)을 사용하거나 참조 카운트를 관리합니다.

- [x] **[구조/버그] GATT 작업 큐가 없음 (busy 상태는 재시도로만 처리)** — `impl/BleDeviceEngineImpl.kt:345-353, 391-399, 485-493`
  - ✅ 해결 (`feature/gatt_operation_queue`)
    - 기기별 직렬 GATT 작업 큐를 도입했습니다(`enqueue()`/`QueuedOperation`, `PublishRelay` + `concatMapCompletable`).
      - read, write, descriptor write(notification 설정/해제), MTU 요청을 하나씩 실행합니다.
      - 앞 작업이 콜백을 받거나 타임아웃/연결 끊김으로 끝나야 다음 작업이 시작됩니다.
    - 타임아웃과 연결 끊김 감지(`guardOperation`)는 큐에서 기다리는 시간이 아니라 **작업이 실제로 시작된 시점부터** 적용됩니다. 연결이 끊기면 대기 중이던 작업도 GATT 호출 없이 바로 `DisconnectedException`으로 끝납니다.
    - 취소 처리:
      - 시작 전에 dispose된 작업은 GATT 호출 없이 건너뜁니다.
      - 실행 중에 dispose돼도 콜백(또는 타임아웃)이 올 때까지 다음 작업을 시작하지 않습니다. Android의 "한 번에 하나" 제약을 지키기 위해서입니다.
    - write-without-response도 `onCharacteristicWrite`를 기다린 뒤 완료합니다. 반환값은 빈 배열 대신 쓴 값입니다.
    - 재시도는 `retryOnCommunicationFailure()`(최대 5회, 500ms)로 통합했습니다. 재시도를 모두 소진하면 `NoSuchElementException` 대신 **`CommunicationFailedException`**으로 실패합니다.
    - 테스트: `BleDeviceEngineImplOperationQueueTest`(8건). 수정 전 엔진에서는 8건 모두 실패하는 것을 확인했습니다.
    - 실기기 비교(Fold5 → S23 시스템 GATT characteristic `2a00`/`2a01`/`2b3a`/`2b2a` 읽기 전용):

      | 시나리오 | 수정 전 | 수정 후 |
      |---|---|---|
      | read 20개 동시 실행(3회) | 5/20 성공, 15건 `NoSuchElementException`, 2.54s | **20/20 성공, 1.23s** |
      | read 20개 순차 실행 | 20/20, 1.20s | 20/20, 1.21s |
      | MTU + read 8개 동시 실행 | 6/9 성공, 2.56s | **9/9 성공, 0.48s** |

      수정 후 실행에서는 `prior command is not finished`(busy) 경고와 재시도가 0건이었습니다.
    - 남은 작업:
      - 응답은 여전히 `uuid + type`으로 매칭합니다. 큐 덕분에 동시에 진행되는 작업은 하나뿐이지만, 타임아웃된 작업의 콜백이 늦게 도착하면 다음 작업은 busy로 재시도합니다.
      - GATT status 처리는 별도 P1 항목입니다.
      - `onServiceChanged`의 `discoverServices()`는 큐 밖에서 실행됩니다. 작업은 재탐색이 끝날 때까지 기다립니다.
  - Android는 GATT 작업을 한 번에 하나만 처리할 수 있습니다. 지금은 GATT 호출이 `false`를 반환하면 500ms 간격으로 최대 5회 재시도하는 방식입니다. 부하가 걸리면 실패하고 지연도 큽니다.
  - 재시도를 모두 소진하면 `retryWhen`의 handler가 complete되어 `CommunicationFailedException` 대신 **`NoSuchElementException`**이 나옵니다.
  - 응답을 `uuid + type`으로만 매칭합니다. 그래서 이전 작업의 늦게 도착한 콜백(예: write-no-response의 `onCharacteristicWrite`)이 다음에 같은 UUID로 보낸 write의 응답으로 잘못 매칭될 수 있습니다.
  - 제안: 기기별 직렬 작업 큐(작업 1개 → 콜백 → 다음 작업)를 도입합니다. 타임아웃, 응답 매칭, 재시도를 큐에서 일괄 처리합니다.
  - (실기기) 다음 두 경우를 확인했습니다.
    - notification 해제와 다른 설정의 CCCD 쓰기가 겹치자 `writeDescriptor() - prior command is not finished`가 발생했고, 500ms 재시도로 복구됐습니다.
    - `operationTimeoutMillis=1`로 타임아웃시킨 직후 다음 read는 이전 요청이 아직 진행 중이라 busy가 되어 579ms(재시도 1회)가 걸렸습니다.

- [ ] **[버그] `connectGatt`에 transport를 지정하지 않음** — `impl/BleDeviceEngineImpl.kt:271`
  - 듀얼모드 기기에서 BR/EDR로 연결을 시도하다가 status 133으로 실패하는 대표적인 원인입니다. minSdk가 24이므로 `connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)`를 바로 사용할 수 있습니다.

- [ ] **[버그] `deviceMap`이 thread-safe하지 않음** — `impl/BleManagerEngineImpl.kt:35, 143-153`
  - 스캔 콜백 스레드와 앱 스레드에서 동시에 `getBleDevice()`가 호출되면, 같은 기기에 대해 `BleDevice`(와 engine)가 두 개 만들어질 수 있습니다. 그러면 GATT 연결이 중복되고 상태가 서로 어긋납니다.
  - 제안: `ConcurrentHashMap.getOrPut` 또는 `synchronized`를 사용합니다.

- [ ] **[버그] `ConnectionState` enum의 `status`가 `var`이어서 전역 상태가 바뀜** — `BleDevice.kt:11`, `InitializableBleDevice.kt:21-22`
  - `ConnectionState.CONNECTING.apply { status = "INITIALING" }`가 enum 싱글턴 자체를 수정합니다. 그래서 이후 **모든 기기**의 CONNECTING 상태가 `"INITIALING"`으로 표시됩니다.
  - 제안: `status`를 `val`로 바꾸고 `INITIALIZING` 상태를 별도로 추가합니다(오타 `INITIALING`도 수정). `distinctUntilChanged()`도 적용합니다.

- [ ] **[버그] `disconnect()`가 CONNECTED 상태에서만 동작함** — `impl/BleDeviceEngineImpl.kt:303-312`
  - CONNECTING 중에는 연결을 취소할 수 없고 로그만 남습니다. `State.DISCONNECTING`(42)은 정의만 되어 있고 사용되지 않습니다. SPP_CONNECTED 상태에서 호출해도 소켓을 닫지 않습니다.
  - 제안: 상태별로 처리합니다. CONNECTING이면 `disconnect()+close()`, SPP면 소켓을 닫습니다. 처리 중에는 DISCONNECTING 상태를 내보냅니다.

- [ ] **[샘플앱] edge-to-edge 미대응으로 주요 UI가 시스템 바에 가려짐 (실기기)** — `app/src/main/res/layout/*.xml`, 각 Activity
  - targetSdk 35에서는 Android 15 이상에서 edge-to-edge가 강제되는데, 레이아웃이 시스템 바 inset을 처리하지 않습니다.
  - 시작 화면: MAIN/TEST 버튼이 상태바와 액션바 뒤에 있어서 **터치로 누를 수 없습니다**. 실기기 테스트에서는 키보드 포커스로 우회했습니다.
  - Main 화면: MAC 입력칸과 CONNECT DEVICE는 상태바/액션바에, STOP SCAN은 내비게이션 바에 가려집니다.
  - Device 화면: 결과 표시(`result_text_view`)와 READ 버튼이 가려져서 결과를 화면에서 볼 수 없습니다.
  - 제안: 루트 뷰에 `ViewCompat.setOnApplyWindowInsetsListener`로 `systemBars` inset을 padding으로 적용하거나, `fitsSystemWindows`를 사용합니다. `StartActivity`에서 import만 하고 쓰지 않는 `enableEdgeToEdge`/`WindowInsetsCompat`를 정리하면서 함께 적용합니다.

- [ ] **[샘플앱] Connect Device 실패 시 백그라운드 스레드에서 Toast를 띄워 크래시남 (실기기)** — `app/.../MainActivity.kt:177-205`
  - MAC 연결 체인에 `observeOn(AndroidSchedulers.mainThread())`가 없습니다. 그래서 연결 타임아웃(`TimeoutException`)의 `onError`가 Rx computation 스레드에서 `Toast.makeText()`를 호출하고, `Can't toast on a thread that has not called Looper.prepare()`로 프로세스가 죽습니다.
  - 제안: `subscribe` 전에 `observeOn(AndroidSchedulers.mainThread())`를 추가하고, 반환된 Disposable을 관리합니다.

---

## P2 — 중간

### 잠재적 버그

- [ ] **[버그] 쓰기 타입 문제** — `impl/BleDeviceEngineImpl.kt:357-401`
  - `WRITE | WRITE_NO_RESPONSE` 속성을 둘 다 가진 characteristic은 Android 기본 `writeType`이 NO_RESPONSE입니다. 그래서 응답을 기다리는 분기로 들어가도 실제로는 응답 없이 전송됩니다. 사용자가 쓰기 타입을 선택할 수도 없습니다.
  - ~~NO_RESPONSE 분기는 `onCharacteristicWrite`를 기다리지 않고 바로 반환합니다. 그래서 연속으로 쓰면 다음 write가 busy가 되고 500ms씩 재시도하므로 처리량이 크게 떨어집니다.~~ → `feature/gatt_operation_queue`에서 해결했습니다. 이제 `onCharacteristicWrite`를 기다리고 큐로 직렬화합니다. 쓰기 타입을 선택하는 기능은 여전히 남아 있습니다.
  - (실기기) 블루투스 스택 로그의 write 요청 → 완료 시간으로 비교했습니다.
    - WRITE 전용 `2a39`는 52ms가 걸렸습니다(Write Response 대기).
    - WRITE+WRITE NO RESPONSE를 가진 `aaa2`는 라이브러리가 응답형 분기로 처리했지만 0ms 만에 완료됐습니다. 즉 실제로는 **Write Command**로 전송됐습니다.
- [ ] **[버그/구조] API 33에서 deprecated된 GATT API 사용** — `impl/BleDeviceEngineImpl.kt:97-154, 366, 377, 476`
  - `characteristic.value = ...` 후 `writeCharacteristic(characteristic)`을 호출하는 방식은 공유된 mutable 필드를 사용합니다. 같은 characteristic으로 notification이 들어오면 쓰려던 값이 덮어써질 수 있습니다(race).
  - API 33 이상에서는 `writeCharacteristic(char, value, writeType)`, `writeDescriptor(desc, value)`와 값을 인자로 받는 새 콜백으로 분기합니다.
  - ⚠️ 새 콜백 오버로드는 기본 구현이 deprecated 콜백을 호출합니다. 그래서 새 오버로드에서 `super`를 호출하면서 relay에도 emit하면 **이벤트가 두 번 전달됩니다**. `stash@{0}`의 WIP 코드가 이 구조이므로 적용하기 전에 수정이 필요합니다.
- [ ] **[버그] `discoverServices()` 반환값을 무시함** — `impl/BleDeviceEngineImpl.kt:78`. false를 반환하면 연결 타임아웃이 날 때까지 의미 없이 대기합니다.
- [ ] **[버그] `stateRelay`가 serialized되어 있지 않음** — `impl/BleDeviceEngineImpl.kt:182`. 바인더 스레드, Rx 스레드, 앱 스레드에서 동시에 `accept`되어 Rx 규약을 위반합니다. `toSerialized()`를 적용하거나 단일 스레드로 처리합니다.
- [ ] **[버그] connect를 동시에 호출하면 `connectGatt`가 두 번 불릴 수 있음** — `impl/BleDeviceEngineImpl.kt:262-272`. `checkConnectionState`와 `CONNECTING`으로 전환하는 동작이 원자적이지 않아서, 먼저 만들어진 gatt가 close되지 않고 누수됩니다.
- [ ] **[버그] `BleDevice.equals()`만 override하고 `hashCode()`는 override하지 않음** — `BleDevice.kt:90`. `HashSet`/`HashMap`/`distinct()`에서 같은 기기가 서로 다른 기기로 취급됩니다. 또 `initEngine()` 전에 `equals/toString`을 호출하면 `UninitializedPropertyAccessException`이 납니다.
- [ ] **[버그] `getConnectedDevices()`가 실제 연결 상태와 맞지 않음** — `impl/BleManagerEngineImpl.kt:120-122`. `bluetoothManager.getConnectedDevices(GATT)`는 시스템 전체(다른 앱, OS가 맺은 연결 포함)를 반환합니다. 그래서 `connected == false`인 `BleDevice`가 목록에 포함됩니다. 라이브러리 engine의 상태를 기준으로 필터링해야 합니다.
- [ ] **[버그] 스캔 결과가 계속 쌓이고 구독이 해제되지 않음(메모리 누수)** — `impl/BleManagerEngineImpl.kt:153-159`. 스캔된 모든 기기가 `deviceMap`에 영구히 남고, 기기마다 만든 connection-state 구독의 Disposable도 버려집니다. 기기가 많은 환경에서 장시간 스캔하면 계속 늘어납니다. 새 기기마다 초기 `DISCONNECTED` 이벤트도 `getConnectionStateObservable()`로 전달됩니다.
- [ ] **[버그] API ≤ 30에서 위치 provider가 바뀌면 스캔이 중복 실행됨** — `impl/BleManagerEngineImpl.kt:84`. `combineLatest`가 `(true, true)`를 다시 내보내면 `flatMap`이 두 번째 스캔을 시작합니다. 그러면 두 스캔의 목록이 번갈아 전달됩니다. `distinctUntilChanged()`와 `switchMap`을 사용해야 합니다.
- [ ] **[버그] `CALLBACK_TYPE_MATCH_LOST`를 처리하지 않음** — `impl/BleScanner.kt:20`. `callbackType`을 무시하므로, 사라진 기기도 발견된 기기로 목록에 추가됩니다.
- [ ] **[버그] API 32 이하에서 `ScanInfo.advertisingDataMap`이 항상 비어 있음** — `ScanInfo.kt:20-27`. `bytes`를 읽기만 하고 파싱하지 않은 채 빈 map을 반환합니다. AD structure(length-type-value)를 직접 파싱해야 합니다.
- [ ] **[버그] `BleScanFilter.Builder` 검증이 파라미터가 아니라 이전 필드 값을 검사함** — `BleScanFilter.kt:59, 103, 134`. 그래서 mask와 data의 길이가 달라도 검증을 통과하고, 스캔을 시작할 때 Android `ScanFilter.Builder`에서 `IllegalArgumentException`이 납니다.
- [ ] **[버그] 런타임 권한을 확인하지 않음** — 전역 `@SuppressLint("MissingPermission")`. API 31 이상에서 `BLUETOOTH_SCAN/CONNECT`가 없으면 `deviceName`, `bonded`, `toString()`, 로그 출력에서도 `SecurityException`이 납니다. 작업 전에 권한을 확인하고 명확한 예외(`PermissionDeniedException`)를 내야 합니다.
- [ ] **[버그] characteristic을 UUID로만 찾음** — `impl/BleDeviceEngineImpl.kt:451-465`. 여러 서비스에 같은 UUID가 있으면 첫 번째 것이 선택됩니다. 찾지 못하면 메시지 없는 `IllegalStateException()`이 납니다. serviceUuid를 받는 오버로드와 `CharacteristicNotFoundException`을 추가해야 합니다.
- [ ] **[버그] CCCD descriptor가 없으면 NPE** — `impl/BleDeviceEngineImpl.kt:475`. 원인을 알기 어려운 에러가 나옵니다.
- [ ] **[버그] `onBluetoothDisabled()`가 CONNECTED 상태만 처리함** — `impl/BleDeviceEngineImpl.kt:528-537`. CONNECTING이나 SPP 상태인 기기는 정리되지 않습니다.
  - (실기기) 연결되지 않은 기기마다 `DisconnectedException` 스택트레이스를 로그로 남깁니다. 테스트에서는 BT를 한 번 끌 때 캐시된 기기 57개에 대해 57개의 스택트레이스가 출력되었습니다. 아래 "스캔 결과가 계속 쌓이는" 문제와 함께 처리해야 합니다.
- [ ] **[버그] SPP 관련 문제** — `impl/BleDeviceEngineImpl.kt:503`, `BleSppSocket.kt:16, 28, 40`
  - `bluetoothDevice.uuids`는 null일 수 있어서 NPE가 날 수 있습니다. 기본 SPP UUID(`00001101-...`)로 fallback하거나 `fetchUuidsWithSdp()`를 사용해야 합니다.
  - `inputStream.read()`의 반환 길이를 확인하지 않습니다. 그래서 일부만 읽혔을 때 뒤쪽이 0으로 채워진 배열이 반환됩니다.
  - 인자 검증의 `size < -2`는 `size < -1`의 오타입니다.
  - 생성자 안에서 블로킹 `connect()`를 호출하고, 연결하기 전에 `cancelDiscovery()`를 호출하지 않습니다.
- [ ] **[버그] VirtualDevice 동작이 실기기와 다름** — `virtual/VirtualDeviceEngine.kt:44`, `virtual/VirtualDevice.kt:77`. `disconnect()`가 `bleDevice.onDisconnected()`를 호출하지 않습니다. 그래서 가상 `InitializableBleDevice`는 재연결 시 초기화하기 전부터 CONNECTED로 보고합니다. `notifyByteArray()`는 notification마다 새 `Thread`를 생성하므로 **순서가 보장되지 않습니다**.
- [x] **[버그] 작업이 타임아웃된 뒤에도 BluetoothGatt가 busy로 남아 다음 작업이 연달아 실패함 (실기기)** — `impl/BleDeviceEngineImpl.kt`(`enqueue`/`guardOperation`)
  - ✅ 해결 (`feature/wait_gatt_release_after_timeout`)
    - GATT 요청을 보낸 뒤 콜백을 받기 전까지 "요청 처리 중"(`gattRequestPendingRelay`)으로 표시합니다. 표시는 `sendGattRequest()`에서 켜고, read/write/descriptor/MTU 콜백에서 끕니다.
    - 작업이 타임아웃되면 호출자에게는 `TimeoutException`을 바로 전달합니다. 다만 보낸 요청이 아직 처리 중이면, 큐는 **늦은 콜백 / 연결 끊김 / 최대 31초**(ATT transaction timeout 30초 + 여유) 중 먼저 오는 시점까지 다음 작업을 시작하지 않습니다(`waitForGattRelease()`).
    - 요청을 보내지 않고 타임아웃된 경우(서비스 재탐색 대기 등)나 요청이 거절된 경우에는 기다리지 않습니다.
    - 부수 효과로, 늦게 도착한 콜백이 다음 작업의 응답으로 잘못 매칭되던 위험도 사라졌습니다.
    - 테스트: `BleDeviceEngineImplTimeoutReleaseTest`(7건). 수정 전 엔진에서는 핵심 대기 동작 4건이 실패했습니다. 나머지 3건은 불필요하게 기다리지 않는지 확인하는 보호 테스트입니다. `OperationQueueTest`의 "타임아웃 직후 다음 작업 시작" 테스트는 새 동작에 맞게 바꿨습니다.
    - 실기기 확인(Fold5 → S23):
      - **응답하지 않는 vendor characteristic**(`00000000-0000-3000-8000-000000000001`) read 후 정상 read 4건을 이어서 실행했습니다.
        - 수정 전: 4건 모두 `CommunicationFailedException`으로 실패했고, 13초 뒤 다시 read해도 실패했습니다.
        - 수정 후: 큐가 대기하다가, 요청 30초 뒤 Android가 `gatt_rsp_timeout`으로 링크를 끊자 4건 모두 GATT 호출 없이 **`DisconnectedException`**(실제 원인)으로 끝났습니다. busy 경고는 0건이었습니다.
        - S23은 이 characteristic에 끝까지 응답하지 않으며, 링크가 끊기는 것은 Android 동작입니다.
      - **늦은 콜백**(`operationTimeoutMillis=20ms`로 정상 characteristic을 강제 타임아웃시킨 뒤 바로 다음 read, 5회):
        - 수정 전: 매회 busy 충돌로 573~605ms가 걸렸습니다(busy 경고 5건).
        - 수정 후: 매회 늦은 콜백을 기다린 뒤 바로 실행되어 98~449ms(보통 219ms)가 걸렸습니다. busy 경고 0건, 대기 로그 5건이었습니다.
  - S23의 일부 vendor characteristic은 read에 응답하지 않았습니다. 라이브러리 타임아웃(기본 10초) 후 큐는 다음 작업으로 넘어가지만, Android `BluetoothGatt`는 콜백이 올 때까지 busy 상태를 유지합니다.
  - 그래서 이어진 read 4건이 각각 6번씩 `prior command is not finished`로 거절됐고, 결국 `CommunicationFailedException`으로 실패했습니다.
  - ATT transaction timeout은 30초이므로, 라이브러리 타임아웃이 이보다 짧으면 이런 일이 생길 수 있습니다.
  - 제안: 타임아웃이 나면 호출자에게는 바로 에러를 주되, 큐는 늦은 콜백이나 연결 끊김(최대 ATT timeout 30초)까지 다음 작업을 막습니다. 또는 타임아웃 시 링크를 끊고 재연결하도록 안내합니다.
- [ ] **[버그] `InitializableBleDevice` 초기화에 타임아웃이 없음** — `InitializableBleDevice.kt:30-46`. 실패하면 `disconnect()`도 두 번 호출됩니다.
- [ ] **[버그] BroadcastReceiver를 `finalize()`에서 해제함** — `impl/BleManagerEngineImpl.kt:69-77`. `finalize()`는 호출 시점이 보장되지 않습니다. `BleManager`를 여러 개 만들면 receiver와 deviceMap도 각각 생기므로, 같은 기기에 대한 객체가 중복됩니다. 싱글턴으로 강제하거나 `close()` API를 제공해야 합니다.
- [ ] **[버그] `getDevice()`가 가상 기기보다 실제 엔진을 먼저 조회함 (실기기)** — `BleManager.kt:88-91`, `impl/BleManagerEngineImpl.kt:125-140`
  - `BleManager.getDeviceInner()`는 `engine.getDevice()`를 먼저 호출하고, 결과가 없을 때만 `virtualDevices`를 봅니다.
  - 샘플 앱의 가상 MAC(`12:34:56:78:90:ab`)은 소문자라서 `getRemoteDevice()`가 `IllegalArgumentException`을 던집니다. 그래서 가상 기기를 조회할 때마다 에러 로그와 스택트레이스가 남습니다(실기기 확인).
  - 가상 MAC이 대문자(유효한 주소)라면 엔진이 실제 `BleDevice`를 만들어 반환하므로, **가상 기기 대신 존재하지 않는 실제 기기가 반환됩니다**. 이 경우는 코드로만 확인했습니다.
  - 제안: `virtualDevices`를 먼저 조회하고, MAC 비교는 대소문자를 무시하도록 정규화합니다.
- [ ] **[샘플앱] DeviceActivity가 종료돼도 notification 구독을 해제하지 않음 (실기기)** — `app/.../DeviceActivity.kt:64-127`. `notificationDisposables`를 `onDestroy`에서 dispose하지 않아서, 화면을 닫은 뒤에도 연결이 끊길 때까지 구독이 살아 있습니다. read/write/특성 조회 구독도 Disposable을 관리하지 않습니다.

### 구조 개선

- [ ] **[구조] 콜백 스레드 정책 정리** — GATT 콜백, notification, 상태 변경이 모두 바인더 스레드에서 사용자 코드로 바로 전달됩니다. 사용자가 무거운 처리를 하면 해당 기기의 모든 GATT 콜백이 지연됩니다. 전용 scheduler로 `observeOn`하거나, 스레드 정책을 문서화해야 합니다.
- [ ] **[구조] 예외 계층 정리** — 현재 `IllegalStateException`, `RuntimeException("Error")`, `NoSuchElementException`, `BleManager.DisconnectedException` 등이 섞여 있습니다. `sealed class BleException`(Disconnected(status), GattError(status), Timeout, CharacteristicNotFound, PermissionDenied, BondFailed(reason), BluetoothDisabled)로 통일하고 **연결 끊김 사유(HCI status)**를 노출합니다.
- [ ] **[구조] 테스트 부재** — 예제 테스트만 있습니다. 순수 로직(`BleScanFilter`, `ScanInfo` 파싱, `BleScanOptionsConverter`, `collectListLastSortedIndex`) 단위 테스트, mock `BluetoothGatt`로 engine 상태 전이 테스트(Robolectric/MockK), `VirtualDevice` 기반 통합 테스트를 추가해야 합니다. 이를 위해 `BleDeviceEngineImpl`의 GATT 의존성을 주입 가능하게 만들어야 합니다.
- [ ] **[구조] BLE와 Classic(SPP) 책임 분리** — `BleSppSocket`은 BLE가 아니라 RFCOMM인데 같은 상태 머신(`stateRelay`)을 공유합니다. 별도 클래스나 모듈로 분리하는 것을 검토합니다.

### 빠진 BLE 기능

- [ ] **[기능] 쓰기 타입 선택 API** — `writeCharacteristicSingle(uuid, bytes, writeType)` (with/without response).
- [ ] **[기능] Connection priority** — `requestConnectionPriority(HIGH/BALANCED/LOW_POWER)`. 처리량과 지연에 큰 영향을 줍니다.
- [ ] **[기능] RSSI 읽기** — `readRemoteRssi()`. 콜백(`impl/BleDeviceEngineImpl.kt:171`)은 있지만 로그만 남깁니다.
- [ ] **[기능] 자동 재연결 / `autoConnect` 옵션** — 현재 `autoConnect=false`로 고정되어 있고, 끊겼을 때 재연결하는 정책이 없습니다. status 133이면 재시도하는 옵션도 필요합니다.
  - (실기기) 이전 연결이 끊긴 직후(1~3초 이내)에 같은 기기로 다시 연결하면, 링크가 정리되는 도중이라 `connectCompletable()`이 `DisconnectedException`으로 실패하는 경우가 2회 있었습니다. 몇 초 뒤에 다시 시도하면 성공했습니다. 연결 재시도 정책을 정할 때 이 경우도 함께 고려해야 합니다.
- [ ] **[기능] 일반 descriptor read/write API** — 현재는 CCCD 쓰기만 내부에서 사용합니다. `Type.READ_DESCRIPTOR`는 정의되어 있지만 쓰이지 않습니다.
- [ ] **[기능] 블루투스 어댑터 상태와 권한 상태 공개 API** — 내부의 `scanAvailableObservable`을 `BleManager`에서 공개하고, BT 활성화 요청과 필요한 권한 목록을 제공하는 helper를 추가합니다.
- [ ] **[기능] 스캔 개선**
  - 일정 시간 보이지 않는 기기를 목록에서 제거(device lost timeout)합니다.
  - `ScanInfo`에 serviceUuids, serviceData, txPower, `isConnectable`(API 26), timestamp를 추가합니다.
  - "30초 내 5회 이상 startScan" 제한(이 경우 결과 없이 조용히 실패)을 감지하거나 방지합니다.
- [ ] **[기능] 서비스/characteristic 트리 조회** — 현재 `BleCharacterisic`은 uuid와 속성 플래그만 제공합니다. 소속 service, descriptor 목록, raw properties를 제공해야 합니다.

---

## P3 — 낮음 / 정리

### 정리

- [ ] **[구조]** `rx_util/RxEnumerated.kt`에 package 선언이 없어서 `Observable.enumerated()`가 루트 패키지로 공개됩니다. 또 `index`가 구독 간에 공유됩니다.
- [ ] **[구조]** 공개 top-level 확장 함수인 `ByteArray.toHexString()`(Kotlin stdlib의 `toHexString`과 이름이 같음)과 `BluetoothDevice.deleteBond()`(`impl/BleDeviceEngineImpl.kt:546-552`)를 `internal`로 바꿉니다. `impl/BleCharacteristicImpl`도 public이므로 `internal`로 바꿉니다.
- [ ] **[구조]** `BleCharacterisic` 오타: 올바른 이름의 타입을 추가하고 기존 이름은 `@Deprecated typealias`로 남겨서 호환성을 유지합니다.
- [ ] **[구조]** 죽은 코드 제거:
  - 코드: `BleManager.Engine` enum(RX_ANDROID_BLE 흔적), `BleScanFilter.isOk`의 LOLLIPOP 분기, `@RequiresApi(LOLLIPOP/M)`, `BleManagerEngineImpl`의 pre-N 분기(minSdk 24), 미사용 `ConnectionStateData`, `Ble.*.Rx`/`Provisioning` Tag, 미사용 `TAG`/`LOG_TAG`
  - 빌드: 미사용 `gson`/`rxkotlin`/`rxandroid` 의존성(현재 POM에 runtime 의존성으로 배포됨), 미사용 `jreleaser` 플러그인, CI의 android-29 system image 설치
- [ ] **[구조]** `BleDevice.connect()`가 Disposable을 버립니다(fire-and-forget). 제거하거나 Disposable을 반환해야 합니다.
- [ ] **[구조]** bond 상태를 1초 간격 polling으로 확인합니다. `ACTION_BOND_STATE_CHANGED` broadcast로 바꾸고 타임아웃을 추가합니다. `removeBond`는 리플렉션으로 숨겨진 API를 호출하므로 향후 막힐 수 있습니다.
- [ ] **[구조]** 라이브러리 manifest에 `uses-feature bluetooth_le required="true"`가 있어서 사용하는 앱에 설치 제한이 강제됩니다. 또 `neverForLocation` 플래그 때문에 비콘 스캔 결과가 필터링됩니다. `required="false"`로 바꾸거나, 앱에서 `tools:replace/remove`로 덮어쓰는 방법을 문서화합니다.
- [ ] **[구조]** notification마다 로그 문자열(hex 변환, `device.name`)을 즉시 생성합니다. 고속 스트리밍에서 비용이 큽니다. 지연 평가로 바꾸거나 별도 verbose 레벨로 분리합니다.
- [ ] **[구조]** `getDevice(mac)`에서 `getRemoteDevice`는 대문자 MAC만 허용합니다. 입력을 정규화해야 합니다.
- [ ] **[구조]** `clazz.newInstance()`(`impl/BleManagerEngineImpl.kt:148`)는 deprecated이고, 사용하는 앱이 R8을 쓰면 no-arg 생성자가 제거될 수 있습니다. factory 람다 방식으로 바꾸거나 consumer proguard rule을 추가합니다.
- [ ] **[배포]** Gradle에서 deprecated된 `buildDir`, `archivesBaseName`을 교체합니다.
- [ ] **[구조]** `ExampleInstrumentedTest`가 항상 실패합니다. 패키지명을 `com.algorigo.algorigoble2.test`로 기대하지만, 실제 namespace는 `com.algorigo.rx.algorigoble2`입니다. 이 때문에 `connectedDebugAndroidTest` 전체 실행이 실패하므로, 수정하거나 삭제해야 합니다(`algorigoble2/src/androidTest/.../ExampleInstrumentedTest.kt`).
- [ ] **[구조]** (실기기) 라이브러리 로그가 2~3번씩 중복 출력되는 경우가 있습니다. 예를 들어 `doOnError` 한 번에 같은 줄이 2~3번 나옵니다. 원인은 확인하지 못했습니다. 샘플 앱의 `BluetoothService`가 `AndroidHandler`를 추가하는 방식과 logger의 handler 전파 구조를 확인해야 합니다.

### 장기 기능

- [ ] **[기능]** MTU 기반 long write 자동 분할 전송(chunking) 및 Reliable Write(`beginReliableWrite/executeReliableWrite`)
- [ ] **[기능]** PHY 설정/조회(`setPreferredPhy`/`readPhy`). 콜백만 있고 로그만 남깁니다.
- [ ] **[기능]** GATT 캐시 강제 갱신(`BluetoothGatt.refresh()` 리플렉션). DFU 이후 필요합니다.
- [ ] **[기능]** 페어링 상세 제어(`ACTION_PAIRING_REQUEST`, PIN/passkey), bond 상태 Observable
- [ ] **[기능]** 백그라운드 스캔(`PendingIntent` 기반 `startScan`, API 26+), Companion Device Manager 연동
- [ ] **[기능]** L2CAP CoC(API 29+), Peripheral 역할(GATT Server, Advertising)
- [ ] **[기능]** Kotlin Coroutines/Flow API 제공(Rx API와 병행)
