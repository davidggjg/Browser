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
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        void onNetworkRequest(JSObject networkInfo);
    }

    private static final Pattern MEDIA_URL_PATTERN = Pattern.compile(
            "\\.(mp4|m3u8|mpd|webm|mov|m4v|mkv|mp3|m4a|aac|wav|flac)(\\?.*)?$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Some players (YouTube being the best-known example) stream media from
     * URLs with no recognizable file extension, e.g. "/videoplayback?...".
     * This catches those by path/host keyword instead of extension.
     */
    private static final Pattern KNOWN_STREAM_PATH_PATTERN = Pattern.compile(
            "videoplayback|googlevideo\\.com|\\.vimeocdn\\.com|/hls/|/dash/|manifest\\.(mpd|m3u8)",
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
            "      if (el.poster) { report(el.poster, 'poster'); }" +
            "    } catch (e) {}" +
            "  }" +
            "  function scanPoster() {" +
            "    try {" +
            "      var og = document.querySelector('meta[property=\"og:image\"], meta[name=\"twitter:image\"]');" +
            "      if (og && og.content) { report(og.content, 'poster'); }" +
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
            "    scanPoster();" +
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

    private static final String PAGE_LINKS_FETCH_JS =
            "(function(){" +
            "  try {" +
            "    var seen = {};" +
            "    var out = [];" +
            "    var anchors = document.querySelectorAll('a[href]');" +
            "    for (var i = 0; i < anchors.length; i++) {" +
            "      try {" +
            "        var href = anchors[i].href;" +
            "        if (!href || seen[href] || href.indexOf('http') !== 0) { continue; }" +
            "        seen[href] = true;" +
            "        out.push({ url: href, text: (anchors[i].textContent || '').trim().slice(0, 120) });" +
            "      } catch (e) {}" +
            "    }" +
            "    if (window.ZovexNative) { window.ZovexNative.reportLinks(JSON.stringify(out)); }" +
            "  } catch (e) {" +
            "    if (window.ZovexNative) { window.ZovexNative.reportLinks('[]'); }" +
            "  }" +
            "})();";

    /**
     * A real DevTools-style Network log, built from the page's own Resource
     * Timing API instead of native request interception — gives real
     * transfer sizes (post-compression) for every resource the page loads,
     * continuously, without touching the actual network traffic at all.
     */
    private static final String NETWORK_OBSERVER_JS =
            "(function(){" +
            "  if (window.__zovexNetInstalled) { return; }" +
            "  window.__zovexNetInstalled = true;" +
            "  function report(entry, type) {" +
            "    try {" +
            "      if (!window.ZovexNative || !entry || !entry.name) { return; }" +
            "      window.ZovexNative.reportNetwork(JSON.stringify({" +
            "        url: entry.name," +
            "        type: type || entry.initiatorType || 'other'," +
            "        transferSize: Math.round(entry.transferSize || 0)," +
            "        encodedSize: Math.round(entry.encodedBodySize || 0)," +
            "        decodedSize: Math.round(entry.decodedBodySize || 0)," +
            "        duration: Math.round(entry.duration || 0)" +
            "      }));" +
            "    } catch (e) {}" +
            "  }" +
            "  try {" +
            "    performance.getEntriesByType('resource').forEach(function (e) { report(e); });" +
            "    var nav = performance.getEntriesByType('navigation')[0];" +
            "    if (nav) { report({ name: location.href, initiatorType: 'document', transferSize: nav.transferSize, encodedBodySize: nav.encodedBodySize, decodedBodySize: nav.decodedBodySize, duration: nav.duration }); }" +
            "  } catch (e) {}" +
            "  try {" +
            "    var po = new PerformanceObserver(function (list) {" +
            "      list.getEntries().forEach(function (e) { report(e); });" +
            "    });" +
            "    po.observe({ entryTypes: ['resource'] });" +
            "  } catch (e) {}" +
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
    private final Map<Integer, Runnable> pendingLinksCallbacks = new ConcurrentHashMap<>();
    private final Map<Integer, String> pendingLinksResults = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, LinkedHashMap<String, JSObject>> mediaByTab = new ConcurrentHashMap<>();
    private static final int MAX_NETWORK_ENTRIES = 300;
    private final ConcurrentHashMap<Integer, LinkedList<JSObject>> networkByTab = new ConcurrentHashMap<>();

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
        networkByTab.remove(id);
        pendingSourceCallbacks.remove(id);
        pendingSourceResults.remove(id);
        pendingLinksCallbacks.remove(id);
        pendingLinksResults.remove(id);
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

    public JSArray getNetworkForTab(int tabId) {
        LinkedList<JSObject> list = networkByTab.get(tabId);
        JSArray arr = new JSArray();
        if (list != null) {
            synchronized (list) {
                for (JSObject o : list) {
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

    /** Scans every &lt;a href&gt; currently in the page's DOM, on demand (not tracked continuously). */
    public void requestLinks(int tabId, final LinksCallback callback) {
        Tab tab = tabs.get(tabId);
        if (tab == null) {
            callback.onLinks(new JSArray());
            return;
        }
        pendingLinksCallbacks.put(tabId, new Runnable() {
            @Override
            public void run() {
                String json = pendingLinksResults.remove(tabId);
                JSArray arr;
                try {
                    arr = json == null ? new JSArray() : new JSArray(json);
                } catch (Exception e) {
                    arr = new JSArray();
                }
                callback.onLinks(arr);
            }
        });
        tab.webView.evaluateJavascript(PAGE_LINKS_FETCH_JS, null);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                Runnable pending = pendingLinksCallbacks.remove(tabId);
                if (pending != null) {
                    pendingLinksResults.putIfAbsent(tabId, "[]");
                    pending.run();
                }
            }
        }, 8000);
    }

    public interface LinksCallback {
        void onLinks(JSArray links);
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

        // Google properties (YouTube, Search, etc.) detect the stock WebView's
        // "; wv)" user-agent token and serve a degraded/blank page instead of
        // the real site. Stripping it makes the UA look like plain mobile
        // Chrome — same version, so it keeps updating itself with the device.
        String defaultUa = settings.getUserAgentString();
        if (defaultUa != null && defaultUa.contains("wv")) {
            settings.setUserAgentString(defaultUa.replace("; wv)", ")").replace(" wv", ""));
        }

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

    private void reportNetwork(int tabId, String url, String type, long transferSize, long encodedSize, long decodedSize, long duration) {
        LinkedList<JSObject> list = networkByTab.get(tabId);
        if (list == null) {
            list = new LinkedList<>();
            LinkedList<JSObject> existing = networkByTab.putIfAbsent(tabId, list);
            if (existing != null) {
                list = existing;
            }
        }
        JSObject item = new JSObject();
        item.put("url", url);
        item.put("type", type);
        item.put("transferSize", transferSize);
        item.put("encodedSize", encodedSize);
        item.put("decodedSize", decodedSize);
        item.put("duration", duration);
        synchronized (list) {
            list.addLast(item);
            while (list.size() > MAX_NETWORK_ENTRIES) {
                list.removeFirst();
            }
        }
        if (listener != null) {
            JSObject evt = new JSObject();
            evt.put("tabId", tabId);
            evt.put("url", url);
            evt.put("type", type);
            evt.put("transferSize", transferSize);
            evt.put("encodedSize", encodedSize);
            evt.put("decodedSize", decodedSize);
            evt.put("duration", duration);
            listener.onNetworkRequest(evt);
        }
    }

    private String buildErrorHtml(String message) {
        return "<!DOCTYPE html><html lang='he' dir='rtl'><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
                "<style>" +
                "body{margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;" +
                "background:#020617;color:#e2e8f0;font-family:sans-serif;text-align:center;padding:2rem;box-sizing:border-box;}" +
                "h1{font-size:1.1rem;margin:0 0 .5rem;} p{font-size:.85rem;color:#94a3b8;margin:0;direction:ltr;}" +
                "</style></head><body>" +
                "<h1>לא ניתן לטעון את הדף</h1>" +
                "<p>" + escapeHtmlForError(message) + "</p>" +
                "</body></html>";
    }

    private String escapeHtmlForError(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
                    TabWebViewManager.this.reportMedia(tabId, enforceHttps(url), kind, "element");
                }
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void reportNetwork(String json) {
            try {
                JSObject o = new JSObject(json);
                String url = o.getString("url", null);
                if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    return;
                }
                String type = o.getString("type", "other");
                long transferSize = o.optLong("transferSize", 0);
                long encodedSize = o.optLong("encodedSize", 0);
                long decodedSize = o.optLong("decodedSize", 0);
                long duration = o.optLong("duration", 0);
                TabWebViewManager.this.reportNetwork(tabId, enforceHttps(url), type, transferSize, encodedSize, decodedSize, duration);
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

        @JavascriptInterface
        public void reportLinks(final String json) {
            pendingLinksResults.put(tabId, json);
            final Runnable pending = pendingLinksCallbacks.remove(tabId);
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
                if (MEDIA_URL_PATTERN.matcher(url).find()) {
                    reportMedia(tabId, url, kindForUrl(url), "network");
                } else if (KNOWN_STREAM_PATH_PATTERN.matcher(url).find()) {
                    reportMedia(tabId, url, "video", "network");
                }
            } catch (Exception ignored) {
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request.isForMainFrame()) {
                String message = error.getDescription() == null ? "" : error.getDescription().toString();
                view.loadUrl("data:text/html;charset=utf-8," + Uri.encode(buildErrorHtml(message)));
            }
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
            view.evaluateJavascript(NETWORK_OBSERVER_JS, null);
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
