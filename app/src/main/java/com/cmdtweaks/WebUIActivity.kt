package com.cmdtweaks

import android.app.ActivityManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.cmdtweaks.core.PackageRepository
import com.cmdtweaks.core.PrivilegeManager
import com.cmdtweaks.ui.MonetCssEngine
import com.cmdtweaks.webview.WebUIConfigurator
import com.cmdtweaks.webview.WebUISession
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WebUIActivity : ComponentActivity(), PrivilegeManager.Listener {

    private val session = WebUISession()
    internal lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var saveBlobLauncher: ActivityResultLauncher<Intent>
    private var pendingSaveBytes: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MonetCssEngine.refresh(this)
        registerResultLaunchers()

        App.executor.submit {
            PackageRepository.warmUp(this@WebUIActivity)
        }

        val container = WebUIConfigurator.createContainerView(this, session)
        setContentView(container)
        applyTaskDescription()

        PrivilegeManager.connect(this)
    }

    override fun onBackendReady(fs: FileSystemManager, mode: PrivilegeManager.BackendMode) {
        WebUIConfigurator.attachFileSystemAndLoad(this, fs, session)
    }

    override fun onBackendFailed() {
        Toast.makeText(this, R.string.privilege_required, Toast.LENGTH_SHORT).show()
        finish()
    }

    fun setEdgeToEdgeMode(enable: Boolean = true) {
        runOnUiThread {
            if (enable) {
                enableEdgeToEdge()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            session.isEdgeToEdgeEnabled = enable
            (session.webView?.parent as? View)?.let { ViewCompat.requestApplyInsets(it) }

            if (enable) {
                session.webView?.evaluateJavascript(session.insets.jsUpdateScript, null)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        session.webView?.settings?.textZoom = (newConfig.fontScale * 100).toInt()
        MonetCssEngine.refresh(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        PrivilegeManager.removeListener(this)
        session.jsBridge?.release()
        session.webView?.destroy()
    }

    private fun registerResultLaunchers() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val selectedUris: Array<Uri>? = if (result.resultCode == RESULT_OK) {
                result.data?.let { data ->
                    when {
                        data.clipData != null -> Array(data.clipData!!.itemCount) { idx ->
                            data.clipData!!.getItemAt(idx).uri
                        }
                        data.data != null -> arrayOf(data.data!!)
                        else -> null
                    }
                }
            } else {
                null
            }
            session.fileChooserCallback?.onReceiveValue(selectedUris)
            session.fileChooserCallback = null
        }

        saveBlobLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val targetUri = if (result.resultCode == RESULT_OK) result.data?.data else null
            val payload = pendingSaveBytes
            if (targetUri != null && payload != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(targetUri)?.use { it.write(payload) }
                    }
                    pendingSaveBytes = null
                }
            } else {
                pendingSaveBytes = null
            }
        }

        session.onSaveBlobRequest = { bytes, fileName, mimeType ->
            pendingSaveBytes = bytes
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            runCatching { saveBlobLauncher.launch(intent) }
                .onFailure { pendingSaveBytes = null }
        }
    }

    private fun applyTaskDescription() {
        val label = session.moduleName
        if (label.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(label))
        } else {
            setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(label).build())
        }
    }
}
