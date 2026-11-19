# AlgorigoBleLibrary

RxJava3 기반 Android BLE 라이브러리입니다. 스캔, 연결, characteristic/descriptor 읽기·쓰기, notification/indication, 본딩, MTU, RSSI, Classic 블루투스 검색과 SPP(RFCOMM) 연결을 `Observable`/`Single`/`Completable`로 제공합니다. 실제 기기 없이 동작을 흉내 내는 가상 기기도 지원합니다.

- Maven 좌표: `com.algorigo.rx:algorigoble2` (Maven Central)
- 패키지: `com.algorigo.algorigoble2`
- 최소 Android 버전: 7.0 (API 24), Java 11

## 설치

라이브러리와 전이 의존성(`algorigo-logger`, RxJava3 등)은 모두 Maven Central에 있습니다.

```groovy
repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation 'com.algorigo.rx:algorigoble2:2.8.1'
    // 결과를 main thread 로 옮길 때 (라이브러리는 rxandroid 를 API 로 노출하지 않습니다)
    implementation 'io.reactivex.rxjava3:rxandroid:3.0.2'
}
```

RxJava3(`io.reactivex.rxjava3:rxjava`)와 `com.algorigo.library:algorigo-logger`는 `api` 의존성이므로 따로 추가하지 않아도 됩니다.

### Manifest와 권한

라이브러리 manifest가 앱에 병합되면서 블루투스 권한이 추가됩니다.

- API 31 이상: `BLUETOOTH_SCAN`(`neverForLocation`), `BLUETOOTH_CONNECT`
- API 30 이하: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`
- `uses-feature android.hardware.bluetooth_le`는 `required="false"`입니다. BLE가 없는 기기에 설치되지 않게 하려면 앱 manifest에서 `required="true"`로 선언하세요.
- 스캔 결과로 위치를 알아내야 하는 앱(비콘 등)은 `neverForLocation` 플래그를 지워야 합니다. 방법은 `BleManager` KDoc에 있습니다.

런타임 권한은 앱에서 요청합니다. `BleManager.getRequiredPermissions()`가 현재 Android 버전에 필요한 권한을, `BleManager.getMissingPermissions(context)`가 아직 허용되지 않은 권한을 돌려줍니다. 권한이 없으면 작업이 `BleManager.PermissionDeniedException`으로 실패합니다.

## 사용 방법

### 1. BleManager 만들기와 스캔

```kotlin
val bleManager = BleManager(
    context,
    delegate = object : BleManager.BleDeviceDelegate() {
        // 기기마다 어떤 BleDevice 하위 클래스로 만들지 정한다. null 을 돌려주면 그 기기는 무시한다
        // (getDevice(mac) 로 처음 조회할 때도 불리며, 이때 scanRecord 는 null 이다)
        override fun createBleDevice(bluetoothDevice: BluetoothDevice, scanRecord: ScanRecord?): BleDevice? =
            MySensor()

        override fun getBleScanFilters(): Array<BleScanFilter> =
            arrayOf(BleScanFilter.Builder().setDeviceName("SC40").build())
    },
)

// 구독하는 동안 스캔하고, 발견된 기기 목록(List<Pair<BleDevice, ScanInfo>>)을 main thread 에서 내보낸다
val scanDisposable = bleManager.scanObservable()
    .subscribe({ devices -> /* 목록 갱신 */ }, { error -> /* SystemServiceException, PermissionDeniedException 등 */ })

// 더 이상 쓰지 않을 때 receiver 를 해제하고 연결된 기기를 끊는다
bleManager.close()
```

- `scanObservable(settings, *filters)`로 `BleScanSettings`/`BleScanFilter`를 직접 넘길 수도 있습니다.
- 이미 알고 있는 기기는 `getDevice(mac)` / `getDevice(mac, MySensor::class.java)`로 가져옵니다. MAC 주소는 대소문자와 구분자(`:`, `-`, 없음)에 관계없이 받습니다.
- 블루투스/위치 상태는 `getScanAvailabilityObservable()`로 구독하고, `createEnableBluetoothIntent()` / `createLocationSettingsIntent()`로 설정 화면을 엽니다.
- BLE 광고를 하지 않는 Classic 기기(SPP 모듈 등)는 `discoveryObservable()`로 찾습니다.

### 2. 연결과 GATT 작업

```kotlin
device.connectCompletable()                       // 기본 timeout 10초
    .andThen(device.readCharacteristicSingle(READ_UUID))
    .flatMap { device.writeCharacteristicSingle(WRITE_UUID, byteArrayOf(0x01)) }
    .subscribe({ /* 쓴 값 */ }, { /* BleException */ })

// 바깥 Observable 의 첫 값 = CCCD 쓰기 완료. 구독을 해제하면 notification 이 꺼진다
val notifyDisposable = device.setupNotification(BleDevice.NotificationType.NOTIFICATION, NOTIFY_UUID)
    .flatMap { it }
    .subscribe { bytes -> /* 수신 데이터 */ }

device.disconnect()
```

- GATT 작업은 기기마다 큐에서 하나씩 처리되며, 작업마다 `operationTimeoutMillis`(기본 10초)가 적용됩니다.
- `connectRetryCount`, `connectRetryDelayMillis`, `autoReconnect`, `autoConnect`로 재시도와 자동 재연결을 설정합니다. `disconnect()`는 자동 재연결도 멈춥니다.
- 이 밖에 `bondCompletable(timeout, pin)`, `requestMtuSingle(mtu)`, `readRssiSingle()`, `requestConnectionPriorityCompletable(priority)`, descriptor 읽기·쓰기, `connectSppSocket(uuid)`를 제공합니다.
- 상태는 `device.getConnectionStateObservable()`(기기 하나) 또는 `bleManager.getConnectionStateObservable()`(`Pair<BleDevice, ConnectionState>`)로 구독합니다.

### 3. 기기별 클래스 만들기

기기 프로토콜은 `BleDevice`를 상속해서 구현합니다. 연결 직후 초기화가 필요하면 `InitializableBleDevice`를 상속합니다. 초기화가 끝날 때까지 상태는 `INITIALIZING`이고, 초기화가 실패하거나 `initializeTimeoutMillis`(기본 30초)를 넘기면 연결을 끊습니다. 재시도와 자동 재연결 때도 초기화를 다시 실행합니다.

```kotlin
class MySensor : InitializableBleDevice() {
    override fun initializeCompletable(): Completable =
        requestMtuSingle(247).ignoreElement()
            .andThen(writeCharacteristicSingle(CONFIG_UUID, byteArrayOf(0x01)).ignoreElement())
}
```

- 하위 클래스는 **인자 없는 생성자**가 있어야 하고, 생성자에서 `deviceId` 등 엔진을 쓰는 속성에 접근하면 안 됩니다. 엔진은 생성 후에 주입됩니다.
- `getDevice(mac, Class)`는 리플렉션으로 생성자를 부릅니다. 라이브러리의 consumer ProGuard 규칙이 R8에서 이 생성자를 남깁니다.

### 4. 콜백 스레드와 예외

- `BleDevice`/`BleManager`가 돌려주는 결과는 `Schedulers.io()` 스레드에서 전달됩니다. UI를 바꾸려면 `observeOn(AndroidSchedulers.mainThread())`를 붙이세요. 스캔·검색 목록만 main thread에서 전달됩니다.
- 라이브러리가 내는 에러는 모두 `BleException`의 하위 타입입니다(`BleManager.DisconnectedException(status)`, `OperationTimeoutException`, `GattOperationFailedException`, `CharacteristicNotFoundException`, `BondFailedException` 등). 잘못된 인자는 `IllegalArgumentException`, SPP 소켓 I/O는 `IOException`입니다.

### 5. 로그

라이브러리는 `algorigo-logger`(java.util.logging)로 로그를 남기고, handler는 붙이지 않습니다. logcat에서 보려면 앱에서 한 번 설정합니다.

```kotlin
BleManager.getLogger().apply {
    level = Level.DEBUG.level
    if (handlers.none { it is AndroidHandler }) addHandler(AndroidHandler())
}
LogManager.removeRootAndroidHandler()   // 지우지 않으면 NOTICE 이상 로그가 두 번씩 출력됩니다
```

### 6. 가상 기기

`VirtualDevice`를 상속해 characteristic 읽기·쓰기·notification을 구현하고 `BleManager` 생성자에 `arrayOf(virtualDevice to BleDevice())`로 넘기면, 스캔 결과와 `getDevice`, 연결/본딩 목록에 실제 기기처럼 나타납니다. 예시는 `app/.../VirtualBleDevice.kt`를 참고하세요. 가상 기기는 SPP와 Classic 검색을 지원하지 않습니다.

## 빌드와 테스트

```bash
./gradlew build                                   # CI 와 동일 (assemble + 단위 테스트 + lint + POM 검사)
./gradlew :algorigoble2:assembleRelease           # AAR 빌드
./gradlew :algorigoble2:testDebugUnitTest         # JVM 단위 테스트
./gradlew :algorigoble2:testDebugUnitTest --tests "*BleDeviceEngineImplConnectTest"
./gradlew :algorigoble2:connectedDebugAndroidTest # 계측 테스트 (기기 필요)
./gradlew :app:installDebug                       # 샘플 앱 설치
```

- master가 아닌 브랜치에서는 `~/.gradle/gradle.properties`에 `NEXUS_SNAPSHOT_REPOSITORY_URL`이 있어야 Gradle 설정 단계를 통과합니다. (`nexusUsername`, `nexusPassword`는 배포할 때 필요합니다)
- `./gradlew build`의 `check` 단계에서 `verifyPublicationPom`이 배포 POM에 `api`/`implementation` 의존성이 빠지지 않았는지 검사합니다.

## 배포

CircleCI(`.circleci/config.yml`)가 브랜치에 따라 배포합니다. 버전은 `algorigoble2/build.gradle`의 `versionStr`입니다.

| 브랜치 | 배포 버전 | 배포 위치 |
| --- | --- | --- |
| `v2/master` | `<versionStr>` | Maven Central (`build/staging-deploy`를 zip으로 묶어 Central Publisher API로 업로드) |
| `v2/release/*`, `v2/hotfix/*` (`release/*`, `hotfix/*` 포함) | `<versionStr>-<user.name>-SNAPSHOT` | `NEXUS_SNAPSHOT_REPOSITORY_URL` 저장소 |

- 브랜치 이름에 `master`가 들어가면 정식 버전으로 취급합니다. release/hotfix 브랜치 이름에 `master`를 넣지 마세요.
- SNAPSHOT 버전 가운데의 `user.name`은 `publish`를 실행한 계정입니다. CI에서는 `circleci`, 로컬에서 실행하면 본인 OS 계정명이 됩니다.
- CI는 환경 변수 `GRADLE_PROPERTY_PATH`, `SECRET_KEY_RING_PATH`에서 `~/.gradle/gradle.properties`와 서명 키(`algorigoble2/secret-keys.gpg`)를 내려받고, Central 업로드에는 `MAVEN_CENTRAL_TOKEN`을 씁니다.
- 배포물은 `signing` 플러그인으로 서명되므로, 로컬에서 `publish`하려면 서명 키 설정(`signing.*` 속성)도 필요합니다.
- 정식 버전 업로드 결과는 Central Portal의 Deployments 페이지에서 확인합니다.

### SNAPSHOT 배포 확인과 사용

SNAPSHOT 업로드는 저장소의 `maven-metadata.xml`로 확인합니다. 저장소 주소는 `NEXUS_SNAPSHOT_REPOSITORY_URL` 값입니다.

```bash
BASE=<NEXUS_SNAPSHOT_REPOSITORY_URL>/com/algorigo/rx/algorigoble2
VERSION=2.8.1-circleci-SNAPSHOT   # 배포한 브랜치의 versionStr 에 맞춘다

curl -fsS "$BASE/maven-metadata.xml"             # 올라가 있는 SNAPSHOT 버전 목록
curl -fsS "$BASE/$VERSION/maven-metadata.xml"    # 마지막 업로드 시각과 빌드 번호
```

두 번째 응답의 `<snapshot>`을 CI job 실행 시각과 비교합니다. `<timestamp>`는 UTC(`yyyyMMdd.HHmmss`)이고, `<buildNumber>`는 같은 버전을 올릴 때마다 1씩 늘어납니다. 404가 나오면 업로드되지 않았거나 `VERSION`이 틀린 것입니다.

```groovy
repositories {
    maven {
        url = '<NEXUS_SNAPSHOT_REPOSITORY_URL>'
        content { includeModule('com.algorigo.rx', 'algorigoble2') }
    }
    mavenCentral()
}

dependencies {
    implementation 'com.algorigo.rx:algorigoble2:2.8.1-circleci-SNAPSHOT'
}
```

## 브랜치 전략

git-flow를 `v2/` 접두어로 사용합니다. `v2/develop`(통합), `v2/master`(릴리즈), `v2/feature/*`, `v2/release/*`, `v2/hotfix/*`.

## 프로젝트 구조

```
algorigoble2/   라이브러리
  BleManager, BleDevice, InitializableBleDevice ...  공개 API
  impl/          실제 블루투스 엔진 (BluetoothGatt, 스캐너, SPP 연결, 권한)
  virtual/       가상 기기와 엔진
  logging/       로그 태그(Ble, Ble.Engine, Ble.Device ...)와 지연 로그
app/            라이브러리를 실기기에서 확인하는 샘플 앱 (project(':algorigoble2') 사용)
TODO.md         정적 분석과 실기기 테스트로 찾은 개선 항목과 처리 내역
```

공개 클래스는 동작을 `internal` 엔진(`BleManagerEngine`, `BleDeviceEngine`)에 맡기므로 실제 엔진과 가상 엔진을 바꿔 쓸 수 있습니다. 자세한 구조와 내부 동작은 [CLAUDE.md](CLAUDE.md)를 참고하세요.
