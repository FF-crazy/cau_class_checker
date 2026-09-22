package com.ffcrazy.cauclasschecker.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 取一次当前位置，格式化成签到接口要的 `"经度,纬度"`。
 *
 * ## 为什么需要定位
 * 教师端后台有一列 GPS 坐标。不传的话签到**照样成功**，但那一列会是空的 ——
 * 实测过：服务端收下 `"1,User denied Geolocation"` 之后存成了空，
 * 说明它**会解析**这个值、**认坐标格式**，只是解析失败并不阻止签到。
 * 结果就是那几条签到在后台一眼可见地与众不同。
 *
 * ## 取的是真实坐标，不是编的
 * 手机在教室里，坐标就是那间教室的。写死一个坐标也能填满那一列，
 * 但那是伪造位置证据，性质完全不同 —— 这里不做。
 *
 * ## 只取一次
 * 一台设备一个坐标，多个账号共用同一个值 —— 本来就是同一台手机待在同一个位置。
 */
object Position {

    /**
     * 服务端存下来的样子是 `116.353782,40.003695` —— **经度在前**。
     *
     * 写反了在界面上完全看不出来，但教师端后台那一列就成了地球上另一个地方，
     * 所以单独抽成纯函数并配了用例。
     */
    fun format(longitude: Double, latitude: Double): String = "$longitude,$latitude"

    fun format(location: Location): String = format(location.longitude, location.latitude)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 「上次已知位置」最多能有多旧。
     *
     * 太旧的多半是几小时前在家里的坐标 —— 那比没有还糟，等于报了个假位置。
     */
    private const val MAX_LAST_KNOWN_AGE_MS = 2 * 60 * 1000L

    /**
     * 取一次当前位置。拿不到（没授权 / 定位关着 / 超时）返回 null。
     *
     * 调用方拿到 null 时**应当照常签到**，只是那一列会是空的 ——
     * 不能因为取不到坐标就放弃签到。
     */
    suspend fun current(context: Context, timeoutMs: Long = 12_000L): String? {
        if (!hasPermission(context)) return null
        return withContext(Dispatchers.IO) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return@withContext null
            val providers = enabledProviders(lm)
            if (providers.isEmpty()) return@withContext null

            // 先用现成的：多数时候手机上已经有一份几分钟内的定位，瞬间可得，
            // 不必让用户在教室里站着等 GPS 冷启动
            freshLastKnown(lm, providers)?.let { return@withContext format(it) }

            withTimeoutOrNull(timeoutMs) { firstFix(lm, providers) }?.let(::format)
        }
    }

    /** 室内 GPS 常常定不上，所以可用的话优先用融合定位。 */
    private fun enabledProviders(lm: LocationManager): List<String> {
        val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
            )
        } else {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        }
        return wanted.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    }

    private fun freshLastKnown(lm: LocationManager, providers: List<String>): Location? {
        val now = System.currentTimeMillis()
        return providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { (now - it.time) in 0..MAX_LAST_KNOWN_AGE_MS }
            .maxByOrNull { it.time }
    }

    /**
     * 同时向所有可用 provider 要一次位置，**谁先给用谁**。
     *
     * 不按顺序一个个试，是因为室内 GPS 可能几十秒都定不上；
     * 而网络定位往往一秒就回来。并发要，最慢的那个就不必等。
     */
    @SuppressLint("MissingPermission")
    private suspend fun firstFix(lm: LocationManager, providers: List<String>): Location? =
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    // 拿到第一个就收工，别让定位继续跑着耗电
                    runCatching { lm.removeUpdates(this) }
                    if (cont.isActive) cont.resume(location)
                }
            }
            providers.forEach {
                runCatching { lm.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }
            }
            cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
        }
}
