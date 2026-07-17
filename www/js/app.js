'use strict';

/**
 * Zovex Core — client logic.
 * Vanilla JS only. No frameworks, no build step.
 */
(function () {
  const SEARCH_STORAGE_KEY = 'zovex:searchDraft';
  const ONION_STORAGE_KEY = 'zovex:onionMode';
  // The JS-heavy main duckduckgo.com results page can fail to render in a
  // bare WebView (same class of issue as Google/YouTube) — the lightweight
  // server-rendered HTML endpoint always works, no JS required.
  const SEARCH_ENGINE = 'https://html.duckduckgo.com/html/?q=';

  const html = document.documentElement;
  const searchForm = document.getElementById('searchForm');
  const searchInput = document.getElementById('searchInput');
  const onionToggle = document.getElementById('onionToggle');
  const onionBanner = document.getElementById('onionBanner');

  // Global flag other scripts/wrapper code can read.
  window.isOnionMode = false;

  // ---------- Onion / Tor mode ----------
  function setOnionMode(enabled) {
    window.isOnionMode = enabled;
    html.setAttribute('data-mode', enabled ? 'onion' : 'standard');
    onionToggle.checked = enabled;
    onionBanner.classList.toggle('show', enabled);
    onionBanner.classList.toggle('hidden', !enabled);
    const bannerText = document.getElementById('onionBannerText');
    if (bannerText) {
      bannerText.textContent = enabled
        ? 'מתחבר לרשת Tor…'
        : 'מצב רשת אפלה פעיל — הניתוב מוצפן ומוסתר';
    }
    if (window.ZovexTabs && window.ZovexTabs.setOnionMode) {
      window.ZovexTabs.setOnionMode(enabled);
    }
    try {
      sessionStorage.setItem(ONION_STORAGE_KEY, enabled ? '1' : '0');
    } catch (_) { /* storage unavailable — ignore, mode still works for this load */ }
  }

  onionToggle.addEventListener('change', () => setOnionMode(onionToggle.checked));

  // Restore onion mode across refreshes.
  try {
    setOnionMode(sessionStorage.getItem(ONION_STORAGE_KEY) === '1');
  } catch (_) {
    setOnionMode(false);
  }

  // ---------- Search draft retention (sessionStorage) ----------
  try {
    const savedDraft = sessionStorage.getItem(SEARCH_STORAGE_KEY);
    if (savedDraft) searchInput.value = savedDraft;
  } catch (_) { /* ignore */ }

  searchInput.addEventListener('input', () => {
    try {
      sessionStorage.setItem(SEARCH_STORAGE_KEY, searchInput.value);
    } catch (_) { /* ignore */ }
  });

  // ---------- URL detection & strict-HTTPS routing ----------
  function looksLikeUrl(raw) {
    if (/^https?:\/\//i.test(raw)) return true;
    if (/\s/.test(raw)) return false;
    // bare domain, e.g. "ynet.co.il", "sub.example.com/path", "example.com:8080"
    return /^[a-z0-9-]+(\.[a-z0-9-]+)+(:\d+)?([/?#].*)?$/i.test(raw);
  }

  function toStrictHttpsUrl(raw) {
    let url;
    try {
      url = new URL(/^https?:\/\//i.test(raw) ? raw : `https://${raw}`);
    } catch (_) {
      return null;
    }
    // Enforce HTTPS unconditionally — never allow a plain-http navigation.
    url.protocol = 'https:';
    return url.toString();
  }

  function openUrl(url) {
    if (window.ZovexTabs) {
      window.ZovexTabs.openUrl(url);
    } else {
      window.location.href = url;
    }
  }

  function navigate(raw) {
    const query = raw.trim();
    if (!query) return;

    if (looksLikeUrl(query)) {
      const safeUrl = toStrictHttpsUrl(query);
      if (safeUrl) {
        try {
          sessionStorage.removeItem(SEARCH_STORAGE_KEY);
        } catch (_) { /* ignore */ }
        searchInput.value = '';
        openUrl(safeUrl);
        return;
      }
    }

    try {
      sessionStorage.removeItem(SEARCH_STORAGE_KEY);
    } catch (_) { /* ignore */ }
    searchInput.value = '';
    openUrl(SEARCH_ENGINE + encodeURIComponent(query));
  }

  searchForm.addEventListener('submit', (event) => {
    event.preventDefault();
    navigate(searchInput.value);
  });

  // ---------- Bookmarks: same strict-HTTPS routing ----------
  document.querySelectorAll('.bookmark-card').forEach((card) => {
    card.addEventListener('click', (event) => {
      event.preventDefault();
      const target = card.getAttribute('data-url') || card.getAttribute('href');
      const safeUrl = toStrictHttpsUrl(target);
      if (safeUrl) openUrl(safeUrl);
    });
  });
})();
