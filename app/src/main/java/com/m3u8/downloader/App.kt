package com.m3u8.downloader

import android.app.Application
import android.os.Build

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Dbg.init(this)
        val v = packageManager.getPackageInfo(packageName, 0)
        Dbg.i("App", "시작 v${v.versionName}(${v.longVersionCode}) ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Dbg.e("Crash", "처리 안 된 예외 (thread ${t.name})", e)
            prev?.uncaughtException(t, e)
        }
        Repo.init(this)
        Dbg.i("App", "목록 ${Repo.items.value.size}개 로드, convertMp4=${Repo.convertMp4} mergeSubs=${Repo.mergeSubs}")
    }
}
