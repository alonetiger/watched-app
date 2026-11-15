package cx.watched.app

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Single-screen shell around watched.cx: the site's own player runs inside the
 * WebView, fullscreen video is hosted natively (landscape + immersive + PiP),
 * and the system chrome uses the site's colour tokens.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var lastSiteUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        // We draw behind the system bars ourselves (Android 15 enforces this anyway)
        // and pad the content so the site's glass nav never slides under the status bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // It's a video app: never dim while it's in front.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(siteUrlFrom(intent) ?: HOME)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        siteUrlFrom(intent)?.let { webView.loadUrl(it) }
    }

    private fun siteUrlFrom(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == "https" && isSiteHost(it.host) }?.toString()

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(getColor(R.color.bg_base))
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(false)          // no pop-up windows
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false                    // assets stay reachable
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            // Present as a normal mobile Chrome so the site serves its regular player.
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // The site is already dark; never let WebView re-tint it.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }
        webView.webViewClient = SiteClient()
        webView.webChromeClient = SiteChromeClient()
    }

    // ---------------------------------------------------------------- navigation

    private inner class SiteClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url
            val scheme = url.scheme ?: return true
            if (scheme == "file") return false
            if ((scheme == "http" || scheme == "https") && isSiteHost(url.host)) return false

            // Anything that would take the main frame off watched.cx: only honour it when
            // it came from a real tap, and open it outside the app. Scripted redirects
            // and pop-unders are simply dropped.
            if (request.hasGesture()) openExternally(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (!url.startsWith(ASSET_PREFIX)) lastSiteUrl = url
            progress.progress = 0
            progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String) {
            progress.visibility = View.GONE
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val failed = request.url.toString()
            if (failed.startsWith(ASSET_PREFIX)) return
            showOffline(failed)
        }
    }

    private fun showOffline(failedUrl: String) {
        val retry = if (isSiteHost(Uri.parse(failedUrl).host)) failedUrl else (lastSiteUrl ?: HOME)
        webView.loadUrl(ASSET_PREFIX + "offline.html?u=" + Uri.encode(retry))
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Nothing on the device handles it; ignore silently.
        }
    }

    // ---------------------------------------------------------------- fullscreen video

    private inner class SiteChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            fullscreenContainer.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            fullscreenContainer.visibility = View.VISIBLE
            webView.visibility = View.INVISIBLE // keep it alive so playback state survives
            setVideoImmersive(true)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        override fun onHideCustomView() = hideCustomView()

        // A transparent 1x1 poster instead of the stock grey play button.
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onPermissionRequest(request: PermissionRequest) {
            // Only media-key access (needed by some players); never camera/mic.
            val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.visibility = View.VISIBLE
        setVideoImmersive(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun setVideoImmersive(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------------------------------------------------------------- picture in picture

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (customView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                )
            } catch (_: IllegalStateException) {
                // PiP not permitted right now; nothing to do.
            }
        }
    }

    private val inPip: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        if (!inPip) webView.onPause() // keep playing while in the PiP window
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.apply {
            stopLoading()
            loadUrl("about:blank")
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val HOME = "https://watched.cx/home"
        private const val ASSET_PREFIX = "file:///android_asset/"

        fun isSiteHost(host: String?): Boolean =
            host != null && (host == "watched.cx" || host.endsWith(".watched.cx"))
    }
}
