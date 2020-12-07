package com.algorigo.algorigoblelibrary.util;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

/**
 * 퍼미션 설정을 위한 유틸리티
 * Created by judh on 2017. 7. 21..
 */

public class PermissionUtil {

    private static int checkSelfPermission(@NonNull Context context, String permission) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is null");
        }

        return context.checkPermission(permission, Process.myPid(), Process.myUid());
    }

    public static boolean checkPermissions(Activity activity, String permission) {
        int permissionResult = checkSelfPermission(activity, permission);

        return permissionResult == PackageManager.PERMISSION_GRANTED;
    }

    private static void requestPermissions(final @NonNull Activity activity, final @NonNull String[] permissions, final int requestCode) {
        if (Build.VERSION.SDK_INT >= 23) {
            ActivityCompat.requestPermissions(activity, permissions, requestCode);
        } else if (activity instanceof ActivityCompat.OnRequestPermissionsResultCallback) {
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                @Override
                public void run() {
                    final int[] grantResults = new int[permissions.length];

                    PackageManager packageManager = activity.getPackageManager();
                    String packageName = activity.getPackageName();

                    final int permissionCount = permissions.length;

                    for (int i = 0; i < permissionCount; i++) {
                        grantResults[i] = packageManager.checkPermission(permissions[i], packageName);
                    }

                    ((ActivityCompat.OnRequestPermissionsResultCallback) activity).onRequestPermissionsResult(requestCode, permissions, grantResults);
                }
            });
        }
    }

    public static void requestExternalPermissions(Activity activity, String[] permissions, final int requestCode) {
        requestPermissions(activity, permissions, requestCode);
    }

    public static boolean verifyPermission(int[] grantresults) {
        if (grantresults.length < 1) {
            return false;
        }
        for (int result : grantresults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
