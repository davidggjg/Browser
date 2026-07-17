package com.zovexcore.browser;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns every open browsing tab as a real android.webkit.WebView so tabs keep
 * independent live state (scroll position, playing media) while in the
 * background, the way switching tabs works in a normal mobile browser.
 *
 * Every public method here must be called on the UI thread; callers
 * (the plugin, the native toolbar) are responsible for that.
 */
public class TabWebViewManager {

    public interface Listener {
        void onTabUpdated(JSObject tabInfo);
        void onTabClosed(int tabId);
        void onActiveTabChanged(Integer tabId);
        void onMediaDetected(JSObject mediaInfo);
        void onTabProgress(JSObject progressInfo);
    }

    private static final Pattern MEDIA_URL_PATTERN = Pattern.compile(
            "\\.(mp4|m3u8|mpd|webm|mov|m4v|mkv|mp3|m4a|aac|wav|flac)(\\?.*)?$",
            Pattern.CASE_INSENSITIVE
    );

    private static final String MEDIA_SCANNER_JS =
            "(function(){" +
            "  if (window.__zovexScannerInstalled) { return; }" +
            "  window.__zovexScannerInstalled = true;" +
            "  function report(url, kind) {" +
            "    try {" +
            "      if (!url || url.indexOf('blob:') === 0 || url.indexOf('data:') === 0) { return; }" +
            "      if (window.ZovexNative) { window.ZovexNative.reportMedia(JSON.stringify({url: url, kind: kind || 'video'})); }" +
            "    } catch (e) {}" +
            "  }" +
            "  function scanEl(el) {" +
            "    try {" +
            "      var src = el.currentSrc || el.src;" +
            "      if (src) { report(src, el.tagName.toLowerCase() === 'audio' ? 'audio' : 'video'); }" +
            "    } catch (e) {}" +
            "  }" +
            "  function scanPlayers() {" +
            "    try {" +
            "      if (window.videojs && typeof window.videojs.getPlayers === 'function') {" +
            "        var players = window.videojs.getPlayers();" +
            "        Object.keys(players).forEach(function (k) {" +
            "          var p = players[k];" +
            "          try {" +
            "            var s = (p.currentSource && p.currentSource() && p.currentSource().src) || (p.currentSrc && p.currentSrc());" +
            "            if (s) { report(s, 'video'); }" +
            "          } catch (e) {}" +
            "        });" +
            "      }" +
            "    } catch (e) {}" +
            "  }" +
            "  function scanAll() {" +
            "    var els = document.querySelectorAll('video, audio');" +
            "    for (var i = 0; i < els.length; i++) { scanEl(els[i]); }" +
            "    scanPlayers();" +
            "  }" +
            "  document.addEventListener('loadedmetadata', function (e) { scanEl(e.target); }, true);" +
            "  document.addEventListener('durationchange', function (e) { scanEl(e.target); }, true);" +
            "  document.addEventListener('play', function (e) { scanEl(e.target); }, true);" +
            "  try {" +
            "    var mo = new MutationObserver(function () { scanAll(); });" +
            "    mo.observe(document.documentElement, { childList: true, subtree: true });" +
            "  } catch (e) {}" +
            "  scanAll();" +
            "  setInterval(scanAll, 4000);" +
            "})();";

    private static final String SOURCE_FETCH_JS =
            "(function(){" +
            "  fetch(location.href, {credentials: 'include'})" +
            "    .then(function (r) { return r.text(); })" +
            "    .then(function (t) { if (window.ZovexNative) { window.ZovexNative.reportSource(t); } })" +
            "    .catch(function (e) { if (window.ZovexNative) { window.ZovexNative.reportSource('<!-- שגיאה בשליפת המקור: ' + e + ' -->'); } });" +
            "})();";

    private static class Tab {
        final int id;
        final WebView webView;
        String url;
        String title = "";
        Tab(int id, WebView webView, String url) {
            this.id = id;
            this.webView = webView;
            this.url = url;
        }
    }

    private final Activity activity;
    private final FrameLayout contentContainer;
    private final FrameLayout fullscreenContainer;
    private final WebView bridgeWebView;
    private final Listener listener;

    private final Map<Integer, Tab> tabs = new LinkedHashMap<>();
    private final Map<Integer, Runnable> pendingSourceCallbacks = new ConcurrentHashMap<>();
    private final Map<Integer, String> pendingSourceResults = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, LinkedHashMap<String, JSObject>> mediaByTab = new ConcurrentHashMap<>();

    private int nextId = 1;
    private Integer activeTabId = null;

    private View customFullscreenView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    public TabWebViewManager(Activity activity, FrameLayout contentContainer, FrameLayout fullscreenContainer, WebView bridgeWebView, Listener listener) {
        this.activity = activity;
        this.contentContainer = contentContainer;
        this.fullscreenContainer = fullscreenContainer;
        this.bridgeWebView = bridgeWebView;
        this.listener = listener;
    }

    public Integer getActiveTabId() {
        return activeTabId;
    }

    public int openNewTab(String url) {
        int id = nextId++;
        WebView webView = new WebView(activity);
        configureWebView(webView, id);
        Tab tab = new Tab(id, webView, url);
        tabs.put(id, tab);
        contentContainer.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setVisibility(View.GONE);
        webView.loadUrl(enforceHttps(url));
        showTab(id);
        return id;
    }

    public boolean showTab(int id) {
        Tab target = tabs.get(id);
        if (target == null) {
            return false;
        }
        for (Tab t : tabs.values()) {
            t.webView.setVisibility(t.id == id ? View.VISIBLE : View.GONE);
        }
        contentContainer.setVisibility(View.VISIBLE);
        bridgeWebView.setVisibility(View.GONE);
        activeTabId = id;
        if (listener != null) {
            listener.onActiveTabChanged(id);
        }
        return true;
    }

    public void showChrome() {
        contentContainer.setVisibility(View.GONE);
        bridgeWebView.setVisibility(View.VISIBLE);
        activeTabId = null;
        if (listener != null) {
            listener.onActiveTabChanged(null);
        }
    }

    public boolean closeTab(int id) {
        Tab tab = tabs.remove(id);
        if (tab == null) {
            return false;
        }
        contentContainer.removeView(tab.webView);
        tab.webView.destroy();
        mediaByTab.remove(id);
        pendingSourceCallbacks.remove(id);
        pendingSourceResults.remove(id);
        if (activeTabId != null && activeTabId == id) {
            if (!tabs.isEmpty()) {
                showTab(tabs.keySet().iterator().next());
            } else {
                showChrome();
            }
        }
        if (listener != null) {
            listener.onTabClosed(id);
        }
        return true;
    }

    public JSArray listTabs() {
        JSArray arr = new JSArray();
        for (Tab t : tabs.values()) {
            JSObject o = new JSObject();
            o.put("id", t.id);
            o.put("url", t.url);
            o.put("title", t.title);
            o.put("active", activeTabId != null && activeTabId == t.id);
            arr.put(o);
        }
        return arr;
    }

    public void goBackActive() {
        Tab t = activeTab();
        if (t != null && t.webView.canGoBack()) {
            t.webView.goBack();
        }
    }

    public void goForwardActive() {
        Tab t = activeTab();
        if (t != null && t.webView.canGoForward()) {
            t.webView.goForward();
        }
    }

    public void reloadActive() {
        Tab t = activeTab();
        if (t != null) {
            t.webView.reload();
        }
    }

    public void shareActiveUrl() {
        Tab t = activeTab();
        if (t == null) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, t.url);
        Intent chooser = Intent.createChooser(send, "שתף קישור");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(chooser);
    }

    public boolean handleBackPressed() {
        if (activeTabId == null) {
            return false;
        }
        Tab t = tabs.get(activeTabId);
        if (t != null && t.webView.canGoBack()) {
            t.webView.goBack();
            return true;
        }
        showChrome();
        return true;
    }

    public JSArray getMediaForTab(int tabId) {
        LinkedHashMap<String, JSObject> map = mediaByTab.get(tabId);
        JSArray arr = new JSArray();
        if (map != null) {
            synchronized (map) {
                for (JSObject o : map.values()) {
                    arr.put(o);
                }
            }
        }
        return arr;
    }

    /** Fetches the tab's raw page source via a same-origin fetch() injected into the page itself. */
    public void requestSource(int tabId, final SourceCallback callback) {
        Tab tab = tabs.get(tabId);
        if (tab == null) {
            callback.onSource(null);
            return;
        }
        pendingSourceCallbacks.put(tabId, new Runnable() {
            @Override
            public void run() {
                callback.onSource(pendingSourceResults.remove(tabId));
            }
        });
        tab.webView.evaluateJavascript(SOURCE_FETCH_JS, null);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                Runnable pending = pendingSourceCallbacks.remove(tabId);
                if (pending != null) {
                    pendingSourceResults.put(tabId, pendingSourceResults.containsKey(tabId)
                            ? pendingSourceResults.get(tabId)
                            : "<!-- זמן המתנה הסתיים בעת שליפת מקור הדף -->");
                    pending.run();
                }
            }
        }, 8000);
    }

    public interface SourceCallback {
        void onSource(String html);
    }

    private Tab activeTab() {
        return activeTabId == null ? null : tabs.get(activeTabId);
    }

    private String enforceHttps(String url) {
        if (url == null) {
            return url;
        }
        if (url.regionMatches(true, 0, "http://", 0, 7)) {
            return "https://" + url.substring(7);
        }
        return url;
    }

    private void configureWebView(WebView webView, final int tabId) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.addJavascriptInterface(new NativeBridge(tabId), "ZovexNative");
        webView.setWebViewClient(new TabWebViewClient(tabId));
        webView.setWebChromeClient(new TabWebChromeClient(tabId));
    }

    private void notifyTabUpdated(Tab tab) {
        JSObject o = new JSObject();
        o.put("id", tab.id);
        o.put("url", tab.url);
        o.put("title", tab.title);
        o.put("canGoBack", tab.webView.canGoBack());
        o.put("canGoForward", tab.webView.canGoForward());
        if (listener != null) {
            listener.onTabUpdated(o);
        }
    }

    private void reportMedia(int tabId, String url, String kind, String source) {
        LinkedHashMap<String, JSObject> map = mediaByTab.get(tabId);
        if (map == null) {
            map = new LinkedHashMap<>();
            LinkedHashMap<String, JSObject> existing = mediaByTab.putIfAbsent(tabId, map);
            if (existing != null) {
                map = existing;
            }
        }
        boolean isNew;
        synchronized (map) {
            isNew = !map.containsKey(url);
            if (isNew) {
                JSObject item = new JSObject();
                item.put("url", url);
                item.put("kind", kind);
                item.put("source", source);
                map.put(url, item);
            }
        }
        if (isNew && listener != null) {
            JSObject evt = new JSObject();
            evt.put("tabId", tabId);
            evt.put("url", url);
            evt.put("kind", kind);
            evt.put("source", source);
            listener.onMediaDetected(evt);
        }
    }

    private String kindForUrl(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".m3u8")) {
            return "hls";
        }
        if (lower.contains(".mpd")) {
            return "dash";
        }
        if (lower.contains(".mp3") || lower.contains(".m4a") || lower.contains(".aac") || lower.contains(".wav") || lower.contains(".flac")) {
            return "audio";
        }
        return "video";
    }

    private class NativeBridge {
        private final int tabId;
        NativeBridge(int tabId) {
            this.tabId = tabId;
        }

        @JavascriptInterface
        public void reportMedia(String json) {
            try {
                JSObject o = new JSObject(json);
                String url = o.getString("url", null);
                String kind = o.getString("kind", "video");
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    reportMedia(tabId, enforceHttps(url), kind, "element");
                }
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void reportSource(final String html) {
            pendingSourceResults.put(tabId, html);
            final Runnable pending = pendingSourceCallbacks.remove(tabId);
            if (pending != null) {
                activity.runOnUiThread(pending);
            }
        }
    }

    private class TabWebViewClient extends WebViewClient {
        final int tabId;
        TabWebViewClient(int tabId) {
            this.tabId = tabId;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            if (scheme.equalsIgnoreCase("http")) {
                view.loadUrl(uri.buildUpon().scheme("https").build().toString());
                return true;
            }
            if (!scheme.equalsIgnoreCase("https")) {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }
            return false;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                String url = request.getUrl().toString();
                Matcher m = MEDIA_URL_PATTERN.matcher(url);
                if (m.find()) {
                    reportMedia(tabId, url, kindForUrl(url), "network");
                }
            } catch (Exception ignored) {
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Tab tab = tabs.get(tabId);
            if (tab != null) {
                tab.url = url;
                notifyTabUpdated(tab);
            }
            view.evaluateJavascript(MEDIA_SCANNER_JS, null);
        }
    }

    private class TabWebChromeClient extends WebChromeClient {
        final int tabId;
        TabWebChromeClient(int tabId) {
            this.tabId = tabId;
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            Tab tab = tabs.get(tabId);
            if (tab != null) {
                tab.title = title == null ? "" : title;
                notifyTabUpdated(tab);
            }
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (listener != null) {
                JSObject o = new JSObject();
                o.put("tabId", tabId);
                o.put("progress", newProgress);
                listener.onTabProgress(o);
            }
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customFullscreenView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customFullscreenView = view;
            customViewCallback = callback;
            fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fullscreenContainer.setVisibility(View.VISIBLE);
            fullscreenContainer.bringToFront();
            setImmersive(true);
        }

        @Override
        public void onHideCustomView() {
            if (customFullscreenView == null) {
                return;
            }
            fullscreenContainer.setVisibility(View.GONE);
            fullscreenContainer.removeView(customFullscreenView);
            customFullscreenView = null;
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }
            setImmersive(false);
        }
    }

    private void setImmersive(boolean on) {
        View decor = activity.getWindow().getDecorView();
        if (on) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }
}
