// ver 1.1.0 | 2026-11-05 22:50
package app.motioncare.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import app.motioncare.data.DiagLog
import app.motioncare.data.RestAssets
import app.motioncare.data.Store
import app.motioncare.service.CareService

class MainActivity : ComponentActivity() {

    private val speedPermission = "android.car.permission.CAR_SPEED"
    private val missingSpeed = mutableStateOf(false)

    private fun refreshMissing() {
        missingSpeed.value = checkSelfPermission(speedPermission) != PackageManager.PERMISSION_GRANTED
    }

    private fun requestMissing() {
        val list = mutableListOf<String>()
        if (checkSelfPermission(speedPermission) != PackageManager.PERMISSION_GRANTED) list += speedPermission
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) list += Manifest.permission.POST_NOTIFICATIONS
        if (list.isEmpty()) return
        DiagLog.w("권한 요청: $list")
        requestPermissions(list.toTypedArray(), 77)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        RestAssets.ensureLoaded(this)
        val dm = resources.displayMetrics
        DiagLog.w("화면 생성 · 표시 ${dm.widthPixels}x${dm.heightPixels} density=${dm.density}")
        refreshMissing()
        setContent {
            AppRoot(
                needsPermission = missingSpeed.value,
                onRequestPermission = { requestMissing() }
            )
        }
        CareService.start(this)
        // permissions are asked on introduction screen 2 (first run) or with the "권한 허용" button on home
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshMissing()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { i, p ->
            DiagLog.w("권한 $p = ${if (grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED) "허용" else "거부"}")
        }
        refreshMissing()
        // the car-signal connection is made once at service start, so restart it now that speed is allowed
        if (!missingSpeed.value) {
            stopService(Intent(this, CareService::class.java))
            CareService.start(this)
        }
    }
}
