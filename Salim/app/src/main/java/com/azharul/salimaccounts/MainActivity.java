package com.azharul.salimaccounts;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * A simple WebView shell for a Google Apps Script web app.
 *
 * - First launch asks for the /exec link and remembers it (SharedPreferences).
 * - The page is always loaded live from the link, so a new Apps Script
 *   deployment shows up without building a new APK.
 * - Back button: goes back inside the WebView if possible, otherwise sends the
 *   app to the background (it is never closed by the back button).
 * - File upload (images) from web forms works.
 * - Blogspot / Google pages open inside the app; other links open outside.
 * - Pull down from the top to refresh.
 * - Print bridge: the web page can call AndroidPrint.printHtml(title, html)
 *   to print / save a PDF (window.print() does nothing inside a WebView).
 */
public class MainActivity extends Activity {

    private static final String PREFS = "app_prefs";
    private static final String KEY_URL = "start_url";
    private static final int REQ_FILE_CHOOSER = 1001;

    /** Hosts (and their sub-domains) that open inside the app. */
    private static final String[] IN_APP_HOSTS = {
            "google.com", "googleusercontent.com", "gstatic.com", "googleapis.com",
            "blogspot.com", "blogger.com"
    };

    private FrameLayout root;
    private PullWebView webView;
    private ProgressBar loadingBar;
    private TextView pullHint;
    private View setupView;

    private String startHost = "";
    private ValueCallback<Uri[]> filePathCallback;
    private WebView printWebView; // kept so it is not garbage-collected while printing

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0F172A"));
        setContentView(root);

        buildWebView();
        buildLoadingBar();
        buildPullHint();

        String saved = getPrefs().getString(KEY_URL, "");
        if (saved == null || saved.trim().isEmpty()) {
            showSetupScreen();
        } else {
            openStartUrl(saved.trim());
        }
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------------------------
    // WebView
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private void buildWebView() {
        webView = new PullWebView(this);
        webView.setBackgroundColor(Color.parseColor("#0F172A"));

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // sessionStorage / localStorage used by the web app
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT); // always follows the server, so new deployments show up
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true); // Apps Script runs inside nested Google iframes

        webView.addJavascriptInterface(new PrintBridge(), "AndroidPrint");
        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new AppChromeClient());

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void buildLoadingBar() {
        loadingBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loadingBar.setMax(100);
        loadingBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP);
        root.addView(loadingBar, lp);
    }

    private void buildPullHint() {
        pullHint = new TextView(this);
        pullHint.setTextColor(Color.WHITE);
        pullHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pullHint.setTypeface(Typeface.DEFAULT_BOLD);
        pullHint.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#4338CA"));
        bg.setCornerRadius(dp(20));
        pullHint.setBackground(bg);
        pullHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(18);
        root.addView(pullHint, lp);
    }

    private void openStartUrl(String url) {
        Uri uri = Uri.parse(url);
        startHost = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (setupView != null) {
            root.removeView(setupView);
            setupView = null;
        }
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    // ------------------------------------------------------------------
    // First-launch setup screen (asks for the /exec link once)
    // ------------------------------------------------------------------

    private void showSetupScreen() {
        webView.setVisibility(View.GONE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(48), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("Salim Accounts");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView info = new TextView(this);
        info.setText("প্রথমবার আপনার ওয়েব অ্যাপের লিংক দিন (Apps Script-এর /exec লিংক)।\n"
                + "একবার দিলেই অ্যাপ এটা মনে রাখবে, পরে আর চাইবে না।");
        info.setTextColor(Color.parseColor("#CBD5E1"));
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        info.setPadding(0, dp(12), 0, dp(16));
        box.addView(info);

        final EditText input = new EditText(this);
        input.setHint("https://script.google.com/macros/s/.../exec");
        input.setHintTextColor(Color.parseColor("#64748B"));
        input.setTextColor(Color.WHITE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setSingleLine(false);
        input.setMaxLines(4);
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Color.parseColor("#1E293B"));
        inputBg.setCornerRadius(dp(10));
        inputBg.setStroke(dp(2), Color.parseColor("#4338CA"));
        input.setBackground(inputBg);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button save = new Button(this);
        save.setText("সেভ করে অ্যাপ খুলুন");
        save.setAllCaps(false);
        save.setTextColor(Color.WHITE);
        save.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#4338CA"));
        btnBg.setCornerRadius(dp(10));
        save.setBackground(btnBg);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        btnLp.topMargin = dp(16);
        box.addView(save, btnLp);

        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = input.getText().toString().trim();
                if (!url.startsWith("https://") || Uri.parse(url).getHost() == null) {
                    Toast.makeText(MainActivity.this,
                            "সঠিক লিংক দিন। লিংক https:// দিয়ে শুরু হতে হবে।",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                getPrefs().edit().putString(KEY_URL, url).apply();
                openStartUrl(url);
            }
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor("#0F172A"));
        scroll.addView(box);
        setupView = scroll;
        root.addView(setupView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ------------------------------------------------------------------
    // Link handling: Google / Blogspot stay inside the app, the rest opens outside
    // ------------------------------------------------------------------

    private boolean isInAppUrl(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (!startHost.isEmpty() && host.equals(startHost)) return true;
        for (String allowed : IN_APP_HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
        }
        return false;
    }

    private void openOutside(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "এই লিংক খোলার মতো কোনো অ্যাপ পাওয়া যায়নি।", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, "লিংকটি খোলা যায়নি।", Toast.LENGTH_SHORT).show();
        }
    }

    private class AppWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // Frames inside the page (Apps Script uses nested iframes) load normally.
            if (!request.isForMainFrame()) return false;
            Uri uri = request.getUrl();
            if (isInAppUrl(uri)) return false;
            openOutside(uri);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            loadingBar.setVisibility(View.GONE);
        }
    }

    private class AppChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress >= 100) {
                loadingBar.setVisibility(View.GONE);
            } else {
                loadingBar.setVisibility(View.VISIBLE);
                loadingBar.setProgress(newProgress);
            }
        }

        // File upload from <input type="file"> (images etc.)
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
            }

            Intent intent = null;
            try {
                intent = params.createIntent();
            } catch (RuntimeException ignored) {
                // fall through to the generic picker below
            }
            if (intent == null) {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
            }
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }

            try {
                filePathCallback = callback;
                startActivityForResult(Intent.createChooser(intent, "ফাইল বাছুন"), REQ_FILE_CHOOSER);
                return true;
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                Toast.makeText(MainActivity.this, "ফাইল বাছার অ্যাপ পাওয়া যায়নি।", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE_CHOOSER) return;
        if (filePathCallback == null) return;

        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                results = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    results[i] = clip.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    // ------------------------------------------------------------------
    // Back button: never closes the app
    // ------------------------------------------------------------------

    @Override
    public void onBackPressed() {
        if (setupView == null && webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    // ------------------------------------------------------------------
    // Print / PDF bridge (window.print() is not supported inside a WebView)
    // ------------------------------------------------------------------

    private class PrintBridge {
        @JavascriptInterface
        public void printHtml(final String title, final String html) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    startPrint(title, html);
                }
            });
        }
    }

    private void startPrint(String title, String html) {
        if (html == null || html.trim().isEmpty()) return;
        final String jobName = (title == null || title.trim().isEmpty())
                ? "Salim Accounts" : title.trim();

        final WebView pw = new WebView(this);
        pw.getSettings().setJavaScriptEnabled(false);
        pw.setWebViewClient(new WebViewClient() {
            private boolean started = false;

            @Override
            public void onPageFinished(final WebView view, String url) {
                if (started) return;
                started = true;
                // Small delay so the embedded fonts are laid out before printing.
                view.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                            if (pm != null) {
                                PrintAttributes attrs = new PrintAttributes.Builder()
                                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                        .build();
                                pm.print(jobName, view.createPrintDocumentAdapter(jobName), attrs);
                            }
                        } catch (RuntimeException e) {
                            Toast.makeText(MainActivity.this, "প্রিন্ট শুরু করা যায়নি।", Toast.LENGTH_SHORT).show();
                        }
                    }
                }, 400);
            }
        });
        printWebView = pw;
        pw.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null);
    }

    // ------------------------------------------------------------------
    // Pull-down-to-refresh (no external library)
    // ------------------------------------------------------------------

    private void showPullHint(boolean ready) {
        pullHint.setText(ready ? "ছেড়ে দিন, পেজ রিফ্রেশ হবে" : "রিফ্রেশ করতে আরও টানুন");
        pullHint.setAlpha(ready ? 1f : 0.75f);
        pullHint.setVisibility(View.VISIBLE);
    }

    private void hidePullHint() {
        pullHint.setVisibility(View.GONE);
    }

    /**
     * WebView with a simple pull-to-refresh gesture.
     *
     * An Apps Script page scrolls inside nested iframes, so the WebView's own
     * scroll position is always 0 and cannot tell us whether the page is at
     * the top. The gesture is therefore accepted only when
     *   (a) the WebView reports that the page could not scroll up any further
     *       (over-scroll at the top) during this touch, or
     *   (b) the finger started in the top strip of the screen,
     * and the finger was then dragged down a long way.
     */
    private class PullWebView extends WebView {
        private final int topZonePx;
        private final int thresholdPx;
        private float downY;
        private boolean tracking;
        private boolean startedInTopZone;
        private boolean pulledPastTop;
        private boolean armed;

        PullWebView(Context context) {
            super(context);
            topZonePx = dp(72);
            thresholdPx = dp(150);
        }

        @Override
        protected boolean overScrollBy(int deltaX, int deltaY, int scrollX, int scrollY,
                                       int scrollRangeX, int scrollRangeY,
                                       int maxOverScrollX, int maxOverScrollY, boolean isTouchEvent) {
            if (deltaY < 0 && scrollY + deltaY < 0) {
                pulledPastTop = true; // tried to scroll above the top of the page
            }
            return super.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY,
                    maxOverScrollX, maxOverScrollY, isTouchEvent);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = ev.getY();
                    tracking = true;
                    armed = false;
                    pulledPastTop = false;
                    startedInTopZone = downY <= topZonePx;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    // second finger (pinch zoom): not a pull gesture
                    tracking = false;
                    armed = false;
                    hidePullHint();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (tracking) {
                        float dy = ev.getY() - downY;
                        boolean eligible = getScrollY() == 0 && (pulledPastTop || startedInTopZone);
                        if (eligible && dy > thresholdPx / 3f) {
                            armed = dy >= thresholdPx;
                            showPullHint(armed);
                        } else {
                            armed = false;
                            hidePullHint();
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    hidePullHint();
                    if (tracking && armed) {
                        reload();
                    }
                    tracking = false;
                    armed = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    hidePullHint();
                    tracking = false;
                    armed = false;
                    break;
                default:
                    break;
            }
            return super.onTouchEvent(ev);
        }
    }

    @Override
    protected void onDestroy() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
