package org.jellyfin.mobile

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.OrientationEventListener
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.player.cast.Chromecast
import org.jellyfin.mobile.player.cast.IChromecast
import org.jellyfin.mobile.player.ui.PlayerFragment
import org.jellyfin.mobile.setup.ConnectFragment
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.BackPressInterceptor
import org.jellyfin.mobile.utils.BluetoothPermissionHelper
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.PermissionRequestHelper
import org.jellyfin.mobile.utils.SmartOrientationListener
import org.jellyfin.mobile.utils.extensions.replaceFragment
import org.jellyfin.mobile.utils.isWebViewSupported
import org.jellyfin.mobile.webapp.RemotePlayerService
import org.jellyfin.mobile.webapp.WebViewFragment
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.fragment.android.setupKoinFragmentFactory
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : AppCompatActivity() {
    private val activityEventHandler: ActivityEventHandler = get()
    val mainViewModel: MainViewModel by viewModel()
    val bluetoothPermissionHelper: BluetoothPermissionHelper = BluetoothPermissionHelper(this, get())
    val chromecast: IChromecast = Chromecast()
    private val permissionRequestHelper: PermissionRequestHelper by inject()

    var serviceBinder: RemotePlayerService.ServiceBinder? = null
        private set
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            serviceBinder = binder as? RemotePlayerService.ServiceBinder
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            serviceBinder = null
        }
    }

    private val orientationListener: OrientationEventListener by lazy { SmartOrientationListener(this) }

    private val onBackPressedCallback: OnBackPressedCallback.() -> Unit = callback@{
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (currentFragment is BackPressInterceptor && currentFragment.onInterceptBackPressed()) {
            return@callback
        }

        if (!supportFragmentManager.isStateSaved && supportFragmentManager.popBackStackImmediate()) {
            return@callback
        }

        isEnabled = false
        assert(!onBackPressedDispatcher.hasEnabledCallbacks()) {
            "MainActivity should be the lowest onBackPressCallback"
        }
        onBackPressedDispatcher.onBackPressed()
        isEnabled = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Walhalla: eigene SplashActivity ersetzt den Android SplashScreen
        setupKoinFragmentFactory()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!isWebViewSupported()) {
            AlertDialog.Builder(this).apply {
                setTitle(R.string.dialog_web_view_not_supported)
                setMessage(R.string.dialog_web_view_not_supported_message)
                setCancelable(false)
                if (AndroidVersion.isAtLeastN) {
                    setNeutralButton(R.string.dialog_button_open_settings) { _, _ ->
                        startActivity(Intent(Settings.ACTION_WEBVIEW_SETTINGS))
                        Toast.makeText(context, R.string.toast_reopen_after_change, Toast.LENGTH_LONG).show()
                        finishAfterTransition()
                    }
                }
                setNegativeButton(R.string.dialog_button_close_app) { _, _ ->
                    finishAfterTransition()
                }
            }.show()
            return
        }

        bindService(Intent(this, RemotePlayerService::class.java), serviceConnection, Service.BIND_AUTO_CREATE)

        with(activityEventHandler) { subscribe() }

        lifecycleScope.launch {
            mainViewModel.serverState.collectLatest { state ->
                lifecycle.withStarted {
                    handleServerState(state)
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, onBackPressed = onBackPressedCallback)

        chromecast.initializePlugin(this)
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
    }

    private fun handleServerState(state: ServerState) {
        with(supportFragmentManager) {
            val currentFragment = findFragmentById(R.id.fragment_container)
            when (state) {
                ServerState.Pending -> Unit
                is ServerState.Unset -> {
                    if (currentFragment !is ConnectFragment) {
                        replaceFragment<ConnectFragment>()
                    }
                }
                is ServerState.Available -> {
                    if (currentFragment !is WebViewFragment || currentFragment.server != state.server) {
                        replaceFragment<WebViewFragment>(
                            Bundle().apply {
                                putParcelable(Constants.FRAGMENT_WEB_VIEW_EXTRA_SERVER, state.server)
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionRequestHelper.handleRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        for (fragment in supportFragmentManager.fragments) {
            if (fragment is PlayerFragment && fragment.isVisible) {
                fragment.onUserLeaveHint()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
    }

    override fun onDestroy() {
        unbindService(serviceConnection)
        chromecast.destroy()
        super.onDestroy()
    }
}
