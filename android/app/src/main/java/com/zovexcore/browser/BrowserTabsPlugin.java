package com.zovexcore.browser;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Bridges the web chrome UI to a native multi-tab WebView browsing engine.
 * All the native UI (content container, fullscreen video layer, bottom
 * toolbar) is built here in load(), which Capacitor guarantees runs after
 * the bridge WebView already exists — building it in MainActivity.onCreate
 * would race against that.
 */
@CapacitorPlugin(name = "BrowserTabs")
public class BrowserTabsPlugin extends Plugin implements TabWebViewManager.Listener {

    private static BrowserTabsPlugin instance;

    private TabWebViewManager manager;
    private TextView tabsButton;
    private int tabCount = 0;

    public static boolean handleBackPressedStatic() {
        return instance != null && instance.manager != null && instance.manager.handleBackPressed();
    }

    @Override
    public void load() {
        instance = this;
        AppCompatActivity activity = getActivity();
        WebView bridgeWebView = getBridge().getWebView();

        FrameLayout contentContainer = new FrameLayout(activity);
        activity.addContentView(contentContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        contentContainer.setVisibility(View.GONE);

        FrameLayout fullscreenContainer = new FrameLayout(activity);
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        activity.addContentView(fullscreenContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullscreenContainer.setVisibility(View.GONE);

        manager = new TabWebViewManager(activity, contentContainer, fullscreenContainer, bridgeWebView, this);

        LinearLayout toolbar = buildToolbar(activity);
        FrameLayout.LayoutParams toolbarParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toolbarParams.gravity = Gravity.BOTTOM;
        activity.addContentView(toolbar, toolbarParams);
    }

    // ---------- JS-facing methods ----------

    @PluginMethod
    public void open(final PluginCall call) {
        final String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("Missing url");
            return;
        }
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                int id = manager.openNewTab(url);
                tabCount++;
                updateTabsBadge();
                JSObject ret = new JSObject();
                ret.put("id", id);
                call.resolve(ret);
            }
        });
    }

    @PluginMethod
    public void switchTab(final PluginCall call) {
        final Integer id = call.getInt("id");
        if (id == null) {
            call.reject("Missing id");
            return;
        }
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                JSObject ret = new JSObject();
                ret.put("ok", manager.showTab(id));
                call.resolve(ret);
            }
        });
    }

    @PluginMethod
    public void closeTab(final PluginCall call) {
        final Integer id = call.getInt("id");
        if (id == null) {
            call.reject("Missing id");
            return;
        }
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                JSObject ret = new JSObject();
                ret.put("ok", manager.closeTab(id));
                call.resolve(ret);
            }
        });
    }

    @PluginMethod
    public void listTabs(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                JSObject ret = new JSObject();
                ret.put("tabs", manager.listTabs());
                call.resolve(ret);
            }
        });
    }

    @PluginMethod
    public void goBack(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                manager.goBackActive();
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void goForward(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                manager.goForwardActive();
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void reload(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                manager.reloadActive();
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void showChrome(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                manager.showChrome();
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void shareActive(final PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                manager.shareActiveUrl();
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void getSource(final PluginCall call) {
        final Integer id = call.getInt("id");
        if (id == null) {
            call.reject("Missing id");
            return;
        }
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                manager.requestSource(id, new TabWebViewManager.SourceCallback() {
                    @Override
                    public void onSource(String html) {
                        JSObject ret = new JSObject();
                        ret.put("html", html == null ? "" : html);
                        call.resolve(ret);
                    }
                });
            }
        });
    }

    @PluginMethod
    public void getDetectedMedia(final PluginCall call) {
        final Integer id = call.getInt("id");
        if (id == null) {
            call.reject("Missing id");
            return;
        }
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                JSObject ret = new JSObject();
                ret.put("items", manager.getMediaForTab(id));
                call.resolve(ret);
            }
        });
    }

    @PluginMethod
    public void getPageLinks(final PluginCall call) {
        final Integer id = call.getInt("id");
        if (id == null) {
            call.reject("Missing id");
            return;
        }
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                manager.requestLinks(id, new TabWebViewManager.LinksCallback() {
                    @Override
                    public void onLinks(JSArray links) {
                        JSObject ret = new JSObject();
                        ret.put("links", links);
                        call.resolve(ret);
                    }
                });
            }
        });
    }

    @PluginMethod
    public void downloadUrl(final PluginCall call) {
        final String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("Missing url");
            return;
        }
        final String filename = call.getString("filename", guessFilenameFromUrl(url));
        try {
            DownloadManager dm = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            req.setTitle(filename);
            req.setAllowedOverMetered(true);
            req.setAllowedOverRoaming(true);
            dm.enqueue(req);
            JSObject ret = new JSObject();
            ret.put("started", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("ההורדה נכשלה: " + e.getMessage());
        }
    }

    @PluginMethod
    public void downloadText(final PluginCall call) {
        String content = call.getString("content");
        String filename = call.getString("filename", "zovex-page-source.html");
        if (content == null) {
            call.reject("Missing content");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            call.reject("שמירת קבצים דורשת אנדרואיד 10 ומעלה");
            return;
        }
        OutputStream out = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/html");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            ContentResolver resolver = getContext().getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                call.reject("יצירת הקובץ נכשלה");
                return;
            }
            out = resolver.openOutputStream(uri);
            out.write(content.getBytes(StandardCharsets.UTF_8));
            JSObject ret = new JSObject();
            ret.put("saved", true);
            ret.put("uri", uri.toString());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("השמירה נכשלה: " + e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ---------- TabWebViewManager.Listener ----------

    @Override
    public void onTabUpdated(JSObject tabInfo) {
        notifyListeners("tabUpdated", tabInfo);
    }

    @Override
    public void onTabClosed(int tabId) {
        tabCount = Math.max(0, tabCount - 1);
        updateTabsBadge();
        JSObject o = new JSObject();
        o.put("id", tabId);
        notifyListeners("tabClosed", o);
    }

    @Override
    public void onActiveTabChanged(Integer tabId) {
        JSObject o = new JSObject();
        o.put("id", tabId);
        notifyListeners("activeTabChanged", o);
    }

    @Override
    public void onMediaDetected(JSObject mediaInfo) {
        notifyListeners("mediaDetected", mediaInfo);
    }

    @Override
    public void onTabProgress(JSObject progressInfo) {
        notifyListeners("tabProgress", progressInfo);
    }

    // ---------- native bottom toolbar ----------

    private void emitChromeRequested(String reason) {
        JSObject o = new JSObject();
        o.put("reason", reason);
        notifyListeners("chromeRequested", o);
    }

    private void updateTabsBadge() {
        if (tabsButton != null) {
            tabsButton.setText(tabCount > 0 ? ("▤ " + tabCount) : "▤");
        }
    }

    private int dp(AppCompatActivity activity, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics());
    }

    private TextView makeToolbarButton(AppCompatActivity activity, String glyph) {
        TextView tv = new TextView(activity);
        tv.setText(glyph);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setPadding(0, dp(activity, 14), 0, dp(activity, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(lp);
        return tv;
    }

    private LinearLayout buildToolbar(final AppCompatActivity activity) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.parseColor("#E6060B18"));

        final TextView back = makeToolbarButton(activity, "‹");
        final TextView forward = makeToolbarButton(activity, "›");
        final TextView reload = makeToolbarButton(activity, "⟳");
        final TextView newTab = makeToolbarButton(activity, "＋");
        tabsButton = makeToolbarButton(activity, "▤");
        final TextView menu = makeToolbarButton(activity, "⋮");

        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.goBackActive();
            }
        });
        forward.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.goForwardActive();
            }
        });
        reload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.reloadActive();
            }
        });
        newTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.showChrome();
                emitChromeRequested("newtab");
            }
        });
        tabsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manager.showChrome();
                emitChromeRequested("switcher");
            }
        });
        menu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOverflowMenu(activity, menu);
            }
        });

        bar.addView(back);
        bar.addView(forward);
        bar.addView(reload);
        bar.addView(newTab);
        bar.addView(tabsButton);
        bar.addView(menu);
        return bar;
    }

    private void showOverflowMenu(AppCompatActivity activity, View anchor) {
        final Integer activeId = manager.getActiveTabId();
        if (activeId == null) {
            return;
        }
        PopupMenu popup = new PopupMenu(activity, anchor);
        popup.getMenu().add(0, 1, 0, "הצג מקור דף");
        popup.getMenu().add(0, 2, 1, "מדיה שזוהתה");
        popup.getMenu().add(0, 3, 2, "קישורים בדף");
        popup.getMenu().add(0, 4, 3, "שתף קישור");
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        manager.showChrome();
                        emitChromeRequested("source:" + activeId);
                        return true;
                    case 2:
                        manager.showChrome();
                        emitChromeRequested("media:" + activeId);
                        return true;
                    case 3:
                        manager.showChrome();
                        emitChromeRequested("links:" + activeId);
                        return true;
                    case 4:
                        manager.shareActiveUrl();
                        return true;
                    default:
                        return false;
                }
            }
        });
        popup.show();
    }

    private String guessFilenameFromUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String last = uri.getLastPathSegment();
            if (last != null && last.length() > 0) {
                return last;
            }
        } catch (Exception ignored) {
        }
        return "zovex-download-" + System.currentTimeMillis();
    }
}
