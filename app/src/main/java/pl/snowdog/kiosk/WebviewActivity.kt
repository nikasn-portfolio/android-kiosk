package pl.snowdog.kiosk

import android.Manifest
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.http.SslError
import android.os.*
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import pl.snowdog.kiosk.databinding.ActivityMainBinding


class WebviewActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var reloadOnConnected: ReloadOnConnected
    private lateinit var adminComponentName: ComponentName
    private lateinit var policyManager: DevicePolicyManager
    private lateinit var mDevicePolicyManager: DevicePolicyManager
    private lateinit var sharedPref: SharedPreferences
    private lateinit var binding: ActivityMainBinding
    private var pin: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        setContentView(R.layout.activity_webview)
        binding = ActivityMainBinding.inflate(layoutInflater)

        supportActionBar?.hide()

        val defaultUrl = "https://on-system.net"
        sharedPref = getSharedPreferences(getString(R.string.storage_key), Context.MODE_PRIVATE)?: return
        val url = sharedPref.getString(getString(R.string.url_key), defaultUrl)
        pin = sharedPref.getString(getString(R.string.pin_key), "1234")

        initVars()
        setKioskPolicies(isAdmin())
        showInFullScreen(findViewById(R.id.root))
        setupWebView(url?:defaultUrl)
        listenToConnectionChange()

        val fab: View = findViewById(R.id.fab)
        fab.setOnClickListener {
            showDialog()
        }
//        val result = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
//        if (result == PackageManager.PERMISSION_GRANTED) {
//            Log.d("KioskApp", "Camera permission granted!")
//        } else {
//            Log.e("KioskApp", "Camera permission NOT granted!")
//        }
    }

    private fun showDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.dialog_title)
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.width = 0
        builder.setView(input)

        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            val inputPin = input.text.toString().toInt()
            if (inputPin == pin?.toInt()) {
                goToHome()
            } else {
                Toast.makeText(
                    applicationContext,
                    R.string.dialog_wrong, Toast.LENGTH_SHORT
                ).show()
            }
        }

        val dialog = builder.show()
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        button.isEnabled = false

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable) {
                button.isEnabled = !TextUtils.isEmpty(s)
            }
        })
    }

    private fun goToHome() {
        with (sharedPref.edit()) {
            putBoolean(getString(R.string.edit_key), true)
            commit()
        }
        super.onBackPressed()
    }

    private fun setKeyGuardEnabled() {
        mDevicePolicyManager.setKeyguardDisabled(adminComponentName, false)
    }

    private fun setAsHomeApp() {
        val intentFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        mDevicePolicyManager.addPersistentPreferredActivity(
            adminComponentName, intentFilter, ComponentName(packageName, MainActivity::class.java.name)
        )
    }

    private fun setUserRestriction(restriction: String) = mDevicePolicyManager.addUserRestriction(adminComponentName, restriction)

    private fun enableStayOnWhilePluggedIn() =
        mDevicePolicyManager.setGlobalSetting(
            adminComponentName,
            Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
            (BatteryManager.BATTERY_PLUGGED_AC
                    or BatteryManager.BATTERY_PLUGGED_USB
                    or BatteryManager.BATTERY_PLUGGED_WIRELESS).toString()
        )

    private fun setUpdatePolicy() {
            mDevicePolicyManager.setSystemUpdatePolicy(
                adminComponentName,
                SystemUpdatePolicy.createWindowedInstallPolicy(60, 120)
            )
    }

    private fun setRestrictions() {
        setUserRestriction(UserManager.DISALLOW_SAFE_BOOT)
        setUserRestriction(UserManager.DISALLOW_FACTORY_RESET)
        setUserRestriction(UserManager.DISALLOW_ADD_USER)
        setUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
        setUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME)
        mDevicePolicyManager.setCameraDisabled(adminComponentName, false)
        mDevicePolicyManager.setStatusBarDisabled(adminComponentName, true)
    }

    private fun setKioskPolicies(isAdmin: Boolean) {
        if (isAdmin) {
            setRestrictions()
            enableStayOnWhilePluggedIn()
            setUpdatePolicy()
            setAsHomeApp()
            setKeyGuardEnabled()
            setCameraPermission()
            keepScreenBright()
        }

        setLockTask(isAdmin)
        setImmersiveMode(window)
    }

    private fun setImmersiveMode(window: android.view.Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            }
        } else {
            @Suppress("DEPRECATION") // handle the older API
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    private fun setLockTask(isAdmin: Boolean) {
        if (isAdmin) {
            mDevicePolicyManager.setLockTaskPackages(
                adminComponentName, arrayOf(packageName)
            )
        }
        startLockTask()
    }

    private fun isAdmin() = mDevicePolicyManager.isDeviceOwnerApp(packageName)

    override fun onResume() {
        super.onResume()
        setupKiosk()
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()){
            webView.goBack()
        }
    }

    override fun onDestroy() {
        if (::reloadOnConnected.isInitialized)
            webView.loadUrl("about:blank")
            reloadOnConnected.onActivityDestroy()
        super.onDestroy()
    }

    private fun setupKiosk() {
        if (policyManager.isDeviceOwnerApp(packageName)) {
            setupAutoStart()
            stayAwake()
            policyManager.setLockTaskPackages(adminComponentName, arrayOf(packageName))
        }
        startLockTask()
    }

    private fun setupAutoStart() {
        val intentFilter = IntentFilter(Intent.ACTION_MAIN)
        intentFilter.addCategory(Intent.CATEGORY_HOME)
        intentFilter.addCategory(Intent.CATEGORY_DEFAULT)
        policyManager.addPersistentPreferredActivity(
            adminComponentName,
            intentFilter, ComponentName(packageName, WebviewActivity::class.java.name)
        )
        policyManager.setKeyguardDisabled(adminComponentName, true)
    }

    private fun stayAwake() {
        policyManager.setGlobalSetting(
            adminComponentName,
            Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
            (BatteryManager.BATTERY_PLUGGED_AC
                    or BatteryManager.BATTERY_PLUGGED_USB
                    or BatteryManager.BATTERY_PLUGGED_WIRELESS).toString()
        )
    }

    private fun keepScreenBright() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun listenToConnectionChange() = reloadOnConnected.onActivityCreate(this)

    private fun setupWebView(url: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1);
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.proceed() // Ignore SSL certificate errors
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                    request.grant(request.resources)
            }
        }
        Log.i("ThreadCheck", "Is UI Thread: ${Looper.getMainLooper().isCurrentThread}")
        val defaultUserAgent = WebSettings.getDefaultUserAgent(this)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            //cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = defaultUserAgent
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        webView.setInitialScale(100)
        webView.scrollBarStyle = WebView.SCROLLBARS_OUTSIDE_OVERLAY
        webView.isScrollbarFadingEnabled = false
        val isCameraDisabled = policyManager.getCameraDisabled(adminComponentName)
        Log.i("KioskApp", "Camera disabled by DPM? $isCameraDisabled")
        webView.loadUrl(url)
    }

    private fun initVars() {
        webView = findViewById(R.id.webView)
        reloadOnConnected = ReloadOnConnected(webView)
        adminComponentName = MyDeviceAdminReceiver.getComponentName(this)
        mDevicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        policyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private fun showInFullScreen(rootView: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, rootView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun setCameraPermission() {
        if (policyManager.isDeviceOwnerApp(packageName)) {
            policyManager.setPermissionGrantState(
                adminComponentName,
                packageName,
                Manifest.permission.CAMERA,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )

            policyManager.setPermissionGrantState(
                adminComponentName,
                packageName,
                Manifest.permission.RECORD_AUDIO,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )

            policyManager.setPermissionGrantState(
                adminComponentName,
                packageName,
                Manifest.permission.CAPTURE_AUDIO_OUTPUT,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )

            // Ensure the camera is enabled
            policyManager.setCameraDisabled(adminComponentName, false)


            Log.i("KioskApp", "Camera & Microphone permissions granted successfully!")
        } else {
            Log.e("KioskApp", "App is NOT a Device Owner. Cannot grant permissions.")
        }
    }



}