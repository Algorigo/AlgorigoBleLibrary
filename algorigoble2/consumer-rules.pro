# BleManager.getDevice(macAddress, Class) 는 BleDevice 하위 클래스를 인자 없는 생성자로 만든다(리플렉션).
# 앱이 생성자를 직접 부르지 않으면 R8 이 지워서 InstantiationException 이 나므로 남긴다.
-keepclassmembers class * extends com.algorigo.algorigoble2.BleDevice {
    public <init>();
}
