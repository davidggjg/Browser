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

  var views = ['home', 'switcher', 'source', 'media', 'links'];
  var tabsCache = [];
  var mediaCache = {};
  var linksCache = {};
  var currentPanelTabId = null;

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

  document.addEventListener('DOMContentLoaded', function () {
    wireSourcePanel();
    wireMediaPanel();
    wireLinksPanel();
    var newTabBtn = document.getElementById('switcherNewTabBtn');
    if (newTabBtn) {
      newTabBtn.addEventListener('click', function () {
        if (isNative()) plugin().showChrome();
        showView('home');
      });
    }
    if (isNative()) {
      registerNativeListeners();
    }
  });

  window.ZovexTabs = {
    isNative: isNative,
    openUrl: openUrl
  };
})();
