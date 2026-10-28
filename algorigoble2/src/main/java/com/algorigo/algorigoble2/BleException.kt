package com.algorigo.algorigoble2

/**
 * 이 라이브러리가 BLE 작업 실패로 던지는 예외의 공통 타입. `catch (e: BleException)` 으로 라이브러리 오류를 모두 받을 수 있다.
 *
 * 하위 타입은 [BleManager] 안에 있다. (예: [BleManager.DisconnectedException], [BleManager.OperationTimeoutException])
 * 스캔에 필요한 시스템 서비스가 꺼진 경우는 [com.algorigo.algorigoble2.exception.SystemServiceException]이다.
 * 잘못된 인자(IllegalArgumentException)처럼 호출하는 쪽의 실수와 SPP 소켓의 IOException 은 이 타입이 아니다.
 *
 * 라이브러리가 하위 타입을 추가해도 사용하는 코드가 깨지지 않도록 sealed 가 아닌 abstract 로 둔다.
 */
abstract class BleException(message: String?, cause: Throwable? = null) : Exception(message, cause)
