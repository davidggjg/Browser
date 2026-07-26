'use strict';

/**
 * Zovex Core — native multi-tab browsing chrome.
 * Talks to the BrowserTabs Capacitor plugin (Java, android/) when running
 * inside the Android app. Falls back to plain navigation when this page is
 * opened as a regular website, so the standalone web build keeps working.
 */
(function () {
  function isNative() {
    return !!(window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform());
  }

  function plugin() {
    return window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.BrowserTabs;
  }

  var views = ['home', 'switcher', 'source', 'media', 'links', 'network'];
  var tabsCache = [];
  var mediaCache = {};
  var linksCache = {};
  var networkCache = {};
  var currentPanelTabId = null;

  function formatBytes(n) {
    if (!n || n <= 0) return '—';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / (1024 * 1024)).toFixed(1) + ' MB';
  }

  function showView(name) {
    views.forEach(function (v) {
      var el = document.getElementById('view-' + v);
      if (el) el.classList.toggle('hidden', v !== name);
    });
    var footer = document.getElementById('homeFooter');
    if (footer) footer.classList.toggle('hidden', name !== 'home');
  }

  function toast(message) {
    var el = document.getElementById('zovexToast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'zovexToast';
      el.className = 'zovex-toast';
      document.body.appendChild(el);
    }
    el.textContent = message;
    el.classList.add('show');
    clearTimeout(el._hideTimer);
    el._hideTimer = setTimeout(function () {
      el.classList.remove('show');
    }, 2200);
  }

  function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function filenameFromUrl(url, fallbackExt) {
    try {
      var u = new URL(url);
      var host = u.hostname.replace(/^www\./, '');
      var last = u.pathname.split('/').filter(Boolean).pop();
      if (last && last.indexOf('.') > -1) return last;
      return host + '.' + (fallbackExt || 'html');
    } catch (_) {
      return 'zovex-download.' + (fallbackExt || 'html');
    }
  }

  // ---------- Tab switcher ----------
  function renderTabs() {
    var grid = document.getElementById('tabsGrid');
    var empty = document.getElementById('tabsEmptyState');
    if (!grid) return;
    grid.innerHTML = '';
    if (!tabsCache.length) {
      empty.classList.remove('hidden');
      return;
    }
    empty.classList.add('hidden');
    tabsCache.forEach(function (tab) {
      var card = document.createElement('div');
      card.className = 'tab-card';
      card.innerHTML =
        '<span class="tab-card-close" data-close-id="' + tab.id + '">✕</span>' +
        '<span class="tab-card-title">' + escapeHtml(tab.title || 'טוען…') + '</span>' +
        '<span class="tab-card-url">' + escapeHtml(tab.url || '') + '</span>';
      card.addEventListener('click', function (e) {
        if (e.target && e.target.hasAttribute('data-close-id')) return;
        plugin().switchTab({ id: tab.id });
      });
      grid.appendChild(card);
    });
    grid.querySelectorAll('[data-close-id]').forEach(function (btn) {
      btn.addEventListener('click', function (e) {
        e.stopPropagation();
        var id = parseInt(btn.getAttribute('data-close-id'), 10);
        plugin().closeTab({ id: id });
      });
    });
  }

  function refreshTabsFromNative() {
    if (!isNative()) return;
    plugin().listTabs().then(function (res) {
      tabsCache = res.tabs || [];
      renderTabs();
    });
  }

  // ---------- View source ----------
  var lastSourceHtml = '';
  var lastSourceTabId = null;

  function openSourcePanel(tabId) {
    currentPanelTabId = tabId;
    lastSourceTabId = tabId;
    var code = document.getElementById('sourceCode');
    code.textContent = 'טוען את מקור הדף…';
    showView('source');
    plugin().getSource({ id: tabId }).then(function (res) {
      lastSourceHtml = res.html || '';
      code.textContent = lastSourceHtml || '(לא התקבל תוכן)';
    });
  }

  function wireSourcePanel() {
    var copyBtn = document.getElementById('sourceCopyBtn');
    var downloadBtn = document.getElementById('sourceDownloadBtn');
    var closeBtn = document.getElementById('sourceCloseBtn');
    if (copyBtn) {
      copyBtn.addEventListener('click', function () {
        if (!lastSourceHtml) return;
        navigator.clipboard.writeText(lastSourceHtml).then(function () {
          toast('הקוד הועתק');
        });
      });
    }
    if (downloadBtn) {
      downloadBtn.addEventListener('click', function () {
        if (!lastSourceHtml) return;
        var tab = tabsCache.filter(function (t) { return t.id === lastSourceTabId; })[0];
        var filename = tab ? filenameFromUrl(tab.url, 'html') : 'zovex-page-source.html';
        plugin().downloadText({ content: lastSourceHtml, filename: filename }).then(function () {
          toast('הקובץ נשמר בתיקיית ההורדות');
        }).catch(function (e) {
          toast(String((e && e.message) || 'השמירה נכשלה'));
        });
      });
    }
    if (closeBtn) {
      closeBtn.addEventListener('click', function () {
        plugin().showChrome();
        showView('switcher');
      });
    }
  }

  // ---------- Detected media ----------
  function renderMedia(tabId) {
    var list = document.getElementById('mediaList');
    var empty = document.getElementById('mediaEmptyState');
    var items = mediaCache[tabId] || [];
    list.innerHTML = '';
    if (!items.length) {
      empty.classList.remove('hidden');
      return;
    }
    empty.classList.add('hidden');
    items.forEach(function (item) {
      var row = document.createElement('div');
      row.className = 'media-item';
      row.innerHTML =
        '<span class="media-kind">' + escapeHtml(item.kind || 'video') + '</span>' +
        '<span class="media-url" dir="ltr">' + escapeHtml(item.url) + '</span>' +
        '<span class="media-actions">' +
        '<button data-copy>העתק</button>' +
        '<button data-download>הורד</button>' +
        '</span>';
      row.querySelector('[data-copy]').addEventListener('click', function () {
        navigator.clipboard.writeText(item.url).then(function () { toast('הקישור הועתק'); });
      });
      row.querySelector('[data-download]').addEventListener('click', function () {
        var fallbackExt = item.kind === 'poster' ? 'jpg' : (item.kind === 'audio' ? 'mp3' : 'mp4');
        plugin().downloadUrl({ url: item.url, filename: filenameFromUrl(item.url, fallbackExt) }).then(function () {
          toast('ההורדה החלה');
        }).catch(function (e) {
          toast(String((e && e.message) || 'ההורדה נכשלה'));
        });
      });
      list.appendChild(row);
    });
  }

  function openMediaPanel(tabId) {
    currentPanelTabId = tabId;
    showView('media');
    plugin().getDetectedMedia({ id: tabId }).then(function (res) {
      mediaCache[tabId] = res.items || [];
      renderMedia(tabId);
    });
  }

  function wireMediaPanel() {
    var closeBtn = document.getElementById('mediaCloseBtn');
    if (closeBtn) {
      closeBtn.addEventListener('click', function () {
        plugin().showChrome();
        showView('switcher');
      });
    }
  }

  // ---------- Page links (on-demand full scan of <a href> on the page) ----------
  function renderLinks(tabId) {
    var list = document.getElementById('linksList');
    var empty = document.getElementById('linksEmptyState');
    var items = linksCache[tabId] || [];
    list.innerHTML = '';
    if (!items.length) {
      empty.textContent = 'לא נמצאו קישורים בדף הזה.';
      empty.classList.remove('hidden');
      return;
    }
    empty.classList.add('hidden');
    items.forEach(function (item) {
      var row = document.createElement('div');
      row.className = 'media-item';
      row.innerHTML =
        '<span class="media-url" dir="ltr" title="' + escapeHtml(item.url) + '">' +
        (item.text ? escapeHtml(item.text) + ' — ' : '') + escapeHtml(item.url) + '</span>' +
        '<span class="media-actions"><button data-copy>העתק</button></span>';
      row.querySelector('[data-copy]').addEventListener('click', function () {
        navigator.clipboard.writeText(item.url).then(function () { toast('הקישור הועתק'); });
      });
      list.appendChild(row);
    });
  }

  function openLinksPanel(tabId) {
    currentPanelTabId = tabId;
    var empty = document.getElementById('linksEmptyState');
    document.getElementById('linksList').innerHTML = '';
    empty.textContent = 'טוען קישורים…';
    empty.classList.remove('hidden');
    showView('links');
    plugin().getPageLinks({ id: tabId }).then(function (res) {
      linksCache[tabId] = res.links || [];
      renderLinks(tabId);
    });
  }

  function wireLinksPanel() {
    var closeBtn = document.getElementById('linksCloseBtn');
    if (closeBtn) {
      closeBtn.addEventListener('click', function () {
        plugin().showChrome();
        showView('switcher');
      });
    }
  }

  // ---------- Network panel (DevTools-style, live) ----------
  function shortNetworkName(url) {
    try {
      var u = new URL(url);
      var last = u.pathname.split('/').filter(Boolean).pop();
      return (last || u.hostname) + (u.search || '');
    } catch (e) {
      return url;
    }
  }

  function statusClass(status) {
    if (!status || status === 0) return 'net-status-pending';
    if (status >= 200 && status < 400) return 'net-status-ok';
    return 'net-status-fail';
  }

  function renderNetwork(tabId) {
    var list = document.getElementById('networkList');
    var empty = document.getElementById('networkEmptyState');
    var total = document.getElementById('networkTotal');
    var items = networkCache[tabId] || [];
    list.innerHTML = '';
    if (!items.length) {
      empty.classList.remove('hidden');
      total.textContent = '';
      return;
    }
    empty.classList.add('hidden');
    total.textContent = items.length + ' בקשות';
    items.forEach(function (item) {
      var row = document.createElement('div');
      row.className = 'net-row net-data';
      row.title = item.url;
      row.innerHTML =
        '<span class="net-col-name">' + escapeHtml(shortNetworkName(item.url)) + '</span>' +
        '<span class="net-col-method">' + escapeHtml(item.method || 'GET') + '</span>' +
        '<span class="net-col-status ' + statusClass(item.status) + '">' + (item.status || '—') + '</span>' +
        '<span class="net-col-type">' + escapeHtml(item.type || 'other') + '</span>' +
        '<span class="net-col-size">' + formatBytes(item.transferSize) + '</span>' +
        '<span class="net-col-time">' + Math.round(item.duration || 0) + ' ms</span>';
      row.addEventListener('click', function () {
        navigator.clipboard.writeText(item.url).then(function () { toast('הקישור הועתק: ' + item.url); });
      });
      list.appendChild(row);
    });
  }

  function openNetworkPanel(tabId) {
    currentPanelTabId = tabId;
    showView('network');
    plugin().getNetworkLog({ id: tabId }).then(function (res) {
      networkCache[tabId] = res.items || [];
      renderNetwork(tabId);
    });
  }

  function wireNetworkPanel() {
    var closeBtn = document.getElementById('networkCloseBtn');
    if (closeBtn) {
      closeBtn.addEventListener('click', function () {
        plugin().showChrome();
        showView('switcher');
      });
    }
  }

  // ---------- Native events ----------
  function registerNativeListeners() {
    var p = plugin();
    if (!p) return;

    p.addListener('chromeRequested', function (data) {
      var reason = data.reason || '';
      if (reason === 'newtab') {
        showView('home');
        var input = document.getElementById('searchInput');
        if (input) {
          input.value = '';
          try { sessionStorage.removeItem('zovex:searchDraft'); } catch (_) { /* ignore */ }
          setTimeout(function () { input.focus(); }, 50);
        }
      } else if (reason === 'switcher') {
        showView('switcher');
        refreshTabsFromNative();
      } else if (reason.indexOf('source:') === 0) {
        openSourcePanel(parseInt(reason.split(':')[1], 10));
      } else if (reason.indexOf('media:') === 0) {
        openMediaPanel(parseInt(reason.split(':')[1], 10));
      } else if (reason.indexOf('links:') === 0) {
        openLinksPanel(parseInt(reason.split(':')[1], 10));
      } else if (reason.indexOf('network:') === 0) {
        openNetworkPanel(parseInt(reason.split(':')[1], 10));
      }
    });

    p.addListener('tabUpdated', function (info) {
      var idx = tabsCache.findIndex(function (t) { return t.id === info.id; });
      if (idx > -1) {
        tabsCache[idx] = Object.assign({}, tabsCache[idx], info);
      } else {
        tabsCache.push(info);
      }
      renderTabs();
    });

    p.addListener('tabClosed', function (data) {
      tabsCache = tabsCache.filter(function (t) { return t.id !== data.id; });
      delete mediaCache[data.id];
      delete linksCache[data.id];
      delete networkCache[data.id];
      renderTabs();
    });

    p.addListener('mediaDetected', function (info) {
      var list = mediaCache[info.tabId] || [];
      if (!list.some(function (m) { return m.url === info.url; })) {
        list.push({ url: info.url, kind: info.kind, source: info.source });
        mediaCache[info.tabId] = list;
      }
      if (currentPanelTabId === info.tabId) {
        renderMedia(info.tabId);
      }
    });

    p.addListener('networkRequest', function (info) {
      var list = networkCache[info.tabId] || [];
      list.push({
        url: info.url,
        type: info.type,
        method: info.method,
        status: info.status,
        transferSize: info.transferSize,
        encodedSize: info.encodedSize,
        decodedSize: info.decodedSize,
        duration: info.duration
      });
      if (list.length > 300) {
        list.shift();
      }
      networkCache[info.tabId] = list;
      if (currentPanelTabId === info.tabId) {
        renderNetwork(info.tabId);
      }
    });

    p.addListener('torStatus', handleTorStatus);
  }

  function openUrl(url) {
    if (!isNative()) {
      window.location.href = url;
      return;
    }
    plugin().open({ url: url }).then(function () {
      refreshTabsFromNative();
    });
  }

  var TOR_STATUS_TEXT = {
    STARTING: 'מתחבר לרשת Tor…',
    ON: 'מתחבר לרשת Tor… כמעט מוכן',
    READY: 'מצב רשת אפלה פעיל — הגלישה מנותבת דרך Tor',
    OFF: 'מצב רשת אפלה פעיל — הניתוב מוצפן ומוסתר',
    ERROR: 'שגיאה בהפעלת Tor — הגלישה אינה מנותבת דרכו',
    UNSUPPORTED: 'המכשיר לא תומך בניתוב Tor אמיתי — מוצג עיצוב בלבד'
  };

  function setOnionModeNative(enabled) {
    if (!isNative()) return;
    plugin().setOnionMode({ enabled: enabled });
  }

  function handleTorStatus(data) {
    var text = document.getElementById('onionBannerText');
    var label = TOR_STATUS_TEXT[data.status] || data.status;
    if (data.status === 'ERROR' && data.message) {
      label = 'שגיאת Tor: ' + data.message;
    }
    if (text) {
      text.textContent = label;
    }
    if (data.status === 'ERROR' || data.status === 'UNSUPPORTED') {
      toast(label);
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    wireSourcePanel();
    wireMediaPanel();
    wireLinksPanel();
    wireNetworkPanel();
    var newTabBtn = document.getElementById('switcherNewTabBtn');
    if (newTabBtn) {
      newTabBtn.addEventListener('click', function () {
        if (isNative()) plugin().showChrome();
        showView('home');
      });
    }
    if (isNative()) {
      registerNativeListeners();
      document.documentElement.classList.add('native-app');
    }
  });

  window.ZovexTabs = {
    isNative: isNative,
    openUrl: openUrl,
    setOnionMode: setOnionModeNative
  };
})();
