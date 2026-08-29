// Feed page JavaScript - Helipad-style boost feed with WebSocket live updates

document.addEventListener('DOMContentLoaded', function() {
  const feedContainer = document.getElementById('feed-container');
  const loadMoreBtn = document.getElementById('load-more');
  const filterForm = document.getElementById('feed-filters');
  const podcastSelect = document.getElementById('filter-podcast');
  const showSelect = document.getElementById('filter-show');
  
  let currentBefore = null; // {time: epoch, id: identifier string}
  let isLoading = false;
  let ws = null;
  let reconnectDelay = 1000;
  let reconnectTimer = null;
  let seenBoosts = new Set(); // Dedup: track time+sender+sats+podcast keys
  
  // Format sat amount with commas
  function formatSats(sats) {
    return new Intl.NumberFormat().format(sats);
  }
  
  // Format timestamp to relative time
  function formatRelativeTime(epochSeconds) {
    const now = Math.floor(Date.now() / 1000);
    const diff = now - epochSeconds;
    
    if (diff < 0) return 'Just now';
    if (diff < 60) return 'Just now';
    if (diff < 120) return '1 minute ago';
    if (diff < 3600) return Math.floor(diff / 60) + ' minutes ago';
    if (diff < 7200) return '1 hour ago';
    if (diff < 86400) return Math.floor(diff / 3600) + ' hours ago';
    if (diff < 172800) return 'Yesterday';
    if (diff < 604800) return Math.floor(diff / 86400) + ' days ago';
    if (diff < 1209600) return '1 week ago';
    if (diff < 2678400) return Math.floor(diff / 604800) + ' weeks ago';
    
    // For older dates, show actual date
    const date = new Date(epochSeconds * 1000);
    return date.toLocaleDateString();
  }
  
  // Format full datetime for tooltip
  function formatFullDateTime(epochSeconds) {
    const date = new Date(epochSeconds * 1000);
    return date.toLocaleString();
  }
  
  // Format compact absolute date+time for inline display
  function formatAbsDateTime(epochSeconds) {
    const date = new Date(epochSeconds * 1000);
    const now = new Date();
    const diffMs = now - date;
    const diffDays = Math.floor(diffMs / 86400000);
    
    // Today: show time only
    if (diffDays === 0) {
      return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }
    // Yesterday: "Yesterday 3:45 PM"
    if (diffDays === 1) {
      return 'Yesterday ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }
    // This year: "Aug 28, 3:45 PM"
    if (date.getFullYear() === now.getFullYear()) {
      return date.toLocaleDateString([], { month: 'short', day: 'numeric' }) + 
             ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }
    // Older: "Aug 28, 2025 3:45 PM"
    return date.toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' }) + 
           ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
  
  // Escape HTML to prevent XSS
  function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }
  
  // Generate dedup key for a boost — prefer content_id (matches server-side
  // dedup-by-content-id in feed.clj). When two entities share a content_id
  // (e.g. a Zaprite order + its nodecan invoice for the same payment), the
  // HTTP feed already collapses them to one row. But the WebSocket broadcasts
  // each entity separately with different identifiers. Using identifier-first
  // would produce distinct keys → duplicate cards in the DOM. content_id is
  // the stable identity across entity sources.
  function boostKey(boost) {
    if (boost.content_id) return 'cid:' + boost.content_id;
    if (boost.identifier) return 'id:' + boost.identifier;
    if (boost.id) return 'eid:' + boost.id;
    const sender = boost.sender || '';
    const sats = boost.sats || 0;
    const podcast = boost.podcast || '';
    const message = (boost.message || '').substring(0, 80);
    return `raw:${boost.time}|${sender}|${sats}|${podcast}|${message}`;
  }
  
  // Create a boost card HTML
  function createBoostCard(boost, isWebSocket = false) {
    const key = boostKey(boost);
    if (seenBoosts.has(key)) return null; // Skip duplicate
    seenBoosts.add(key);
    
    // Cap seen set to prevent memory leak (keep last 500)
    if (seenBoosts.size > 500) {
      const first = seenBoosts.values().next().value;
      seenBoosts.delete(first);
    }
    const card = document.createElement('div');
    card.className = 'boost-card';
    card.dataset.time = boost.time || boost['invoice/creation_date'];
    
    const sender = boost.sender || boost['boostagram/sender_name_normalized'] || 'Anonymous';
    const sats = boost.sats || boost['boostagram/value_sat_total'] || 0;
    const app = boost.app || boost['boostagram/app_name'] || 'Unknown';
    const podcast = boost.podcast || boost['boostagram/podcast'] || '';
    const episode = boost.episode || boost['boostagram/episode'] || '';
    const message = boost.message || boost['boostagram/message'] || '';
    const time = boost.time || boost['invoice/creation_date'] || 0;
    const fiatCents = boost.fiat_cents || boost['boostagram/amount_fiat_cents'] || 0;
    const paymentRail = boost.payment_rail || boost['boostagram/payment_rail'] || '';
    const fiatCurrency = boost.fiat_currency || boost['boostagram/amount_fiat_currency'] || '';
    
    // Determine boost type and value display
    let valueHtml = '';
    let cardType = '';
    if (paymentRail === 'member-free') {
      valueHtml = '<span class="boost-badge badge-member">Member Boost</span>';
      cardType = 'member-free';
    } else if (fiatCents > 0) {
      const dollars = (fiatCents / 100).toFixed(2);
      const rail = escapeHtml(paymentRail || 'card');
      valueHtml = `<span class="boost-fiat">$${dollars}</span> <span class="boost-rail">(${rail})</span>`;
      cardType = 'fiat';
    } else if (sats > 0) {
      valueHtml = `<span class="sats">${formatSats(sats)} sats</span>`;
      cardType = 'sats';
    } else {
      valueHtml = '<span class="sats">0 sats</span>';
    }
    
    // Boost type class for card styling
    if (cardType) card.classList.add('type-' + cardType);
    
    const messageHtml = message ? 
      `<div class="boost-message">${escapeHtml(message)}</div>` : '';
    
    card.innerHTML = `
      <div class="boost-meta">
        <span class="boost-app">${escapeHtml(app)}</span>
        <span class="boost-time" title="${formatFullDateTime(time)}">${formatRelativeTime(time)} · ${formatAbsDateTime(time)}</span>
      </div>
      <div class="boost-amount">${valueHtml}</div>
      <div class="boost-sender">from ${escapeHtml(sender)}</div>
      <div class="boost-episode">${escapeHtml(podcast)} — ${escapeHtml(episode)}</div>
      ${messageHtml}
    `;
    
    return card;
  }
  
  // Show connection status indicator
  function showConnectionStatus(status) {
    let indicator = document.getElementById('ws-status');
    if (!indicator) {
      indicator = document.createElement('div');
      indicator.id = 'ws-status';
      indicator.style.cssText = 'position:fixed;top:8px;right:8px;padding:4px 12px;border-radius:4px;font-size:12px;z-index:1000;transition:opacity 0.3s;';
      document.body.appendChild(indicator);
    }
    
    switch (status) {
      case 'connected':
        indicator.style.background = '#2d5a2d';
        indicator.style.color = '#8f8';
        indicator.textContent = 'Live';
        indicator.style.opacity = '1';
        setTimeout(() => { indicator.style.opacity = '0.5'; }, 2000);
        break;
      case 'connecting':
        indicator.style.background = '#5a5a2d';
        indicator.style.color = '#ff8';
        indicator.textContent = 'Connecting...';
        indicator.style.opacity = '1';
        break;
      case 'disconnected':
        indicator.style.background = '#5a2d2d';
        indicator.style.color = '#f88';
        indicator.textContent = 'Reconnecting...';
        indicator.style.opacity = '1';
        break;
    }
  }
  
  // WebSocket connection with auto-reconnect
  function connectWebSocket() {
    if (ws && (ws.readyState === WebSocket.CONNECTING || ws.readyState === WebSocket.OPEN)) {
      return;
    }
    
    showConnectionStatus('connecting');
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(`${protocol}//${location.host}/ws/boosts`);
    
    ws.onopen = function() {
      console.log('WebSocket connected');
      showConnectionStatus('connected');
      reconnectDelay = 1000; // Reset backoff on successful connect
    };
    
    ws.onmessage = function(event) {
      try {
        const boost = JSON.parse(event.data);
        const card = createBoostCard(boost, true);
        if (!card) return; // Duplicate, skip
        
        // Remove empty state if present
        const emptyState = feedContainer.querySelector('.feed-empty');
        if (emptyState) emptyState.remove();
        
        // Prepend new boost to feed
        feedContainer.insertBefore(card, feedContainer.firstChild);
        
        // Flash the card to draw attention
        card.style.background = '#2a3a2a';
        setTimeout(() => { card.style.background = ''; }, 1000);
      } catch (e) {
        console.error('Error processing WebSocket message:', e);
      }
    };
    
    ws.onclose = function() {
      console.log('WebSocket disconnected, reconnecting in ' + reconnectDelay + 'ms');
      showConnectionStatus('disconnected');
      scheduleReconnect();
    };
    
    ws.onerror = function(error) {
      console.error('WebSocket error:', error);
      ws.close();
    };
  }
  
  // Schedule reconnection with exponential backoff
  function scheduleReconnect() {
    if (reconnectTimer) clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(function() {
      reconnectDelay = Math.min(reconnectDelay * 2, 30000);
      connectWebSocket();
    }, reconnectDelay);
  }
  
  // Load podcasts for the current show
  async function loadPodcasts() {
    const show = showSelect.value;
    if (!show) return;
    
    try {
      const response = await fetch(`/api/v1/feed/podcasts?show=${encodeURIComponent(show)}`);
      if (!response.ok) throw new Error('Failed to load podcasts');
      
      const data = await response.json();
      const podcasts = data.podcasts || [];
      
      // Save current selection
      const currentPodcast = podcastSelect.value;
      
      // Clear and repopulate
      podcastSelect.innerHTML = '<option value="">All Podcasts</option>';
      
      podcasts.forEach(podcast => {
        const option = document.createElement('option');
        option.value = podcast;
        option.textContent = podcast;
        podcastSelect.appendChild(option);
      });
      
      // Restore selection if still exists
      if (currentPodcast) {
        podcastSelect.value = currentPodcast;
      }
    } catch (error) {
      console.error('Error loading podcasts:', error);
    }
  }
  
  // Load boosts from API
  async function loadBoosts(before = null, append = false) {
    if (isLoading) return;
    isLoading = true;
    
    const show = showSelect.value;
    const podcast = podcastSelect.value;
    const since = document.getElementById('filter-since').value;
    const limit = 100;
    
    let url = `/api/v1/feed?show=${encodeURIComponent(show)}&limit=${limit}`;
    if (podcast) url += `&podcast=${encodeURIComponent(podcast)}`;
    if (since) url += `&since=${since}`;
    if (before && before.time != null && (before.id != null || before.index != null)) {
      const bid = before.id != null ? before.id : before.index;
      // Prefer before_id (identifier string), fallback to before_index for legacy
      if (typeof bid === 'string') {
        url += `&before_time=${before.time}&before_id=${encodeURIComponent(bid)}`;
      } else {
        url += `&before_time=${before.time}&before_index=${before.index}`;
      }
    }
    
    try {
      const response = await fetch(url);
      if (!response.ok) throw new Error('Failed to load boosts');
      
      const boosts = await response.json();
      
      if (!append) {
        feedContainer.innerHTML = '';
        seenBoosts.clear();
      }
      
      if (boosts.length === 0 && !append) {
        feedContainer.innerHTML = `
          <div class="feed-empty">
            <p>No boosts found.</p>
            <p>Waiting for live boosts...</p>
          </div>
        `;
        loadMoreBtn.style.display = 'none';
        return;
      }
      
      // Add boost cards
      boosts.forEach(boost => {
        const card = createBoostCard(boost);
        if (card) feedContainer.appendChild(card);
      });
      
      // Update cursor for next page — use stable identifier
      if (boosts.length > 0) {
        const lastBoost = boosts[boosts.length - 1];
        currentBefore = {time: lastBoost.time, id: lastBoost.identifier || lastBoost.content_id || lastBoost.index};
        loadMoreBtn.style.display = 'block';
      }
      
      // Hide load more if we got fewer than requested
      if (boosts.length < limit) {
        loadMoreBtn.style.display = 'none';
      }
      
    } catch (error) {
      console.error('Error loading boosts:', error);
      if (!append) {
        feedContainer.innerHTML = `
          <div class="feed-empty">
            <p>Error loading boosts. Please try again.</p>
          </div>
        `;
      }
    } finally {
      isLoading = false;
    }
  }
  
  // Load more handler
  if (loadMoreBtn) {
    loadMoreBtn.addEventListener('click', function(e) {
      e.preventDefault();
      if (currentBefore) {
        loadBoosts(currentBefore, true);
      }
    });
  }
  
  // Filter form handler
  if (filterForm) {
    filterForm.addEventListener('submit', function(e) {
      e.preventDefault();
      currentBefore = null;
      seenBoosts.clear();
      loadBoosts(null, false);
    });
  }
  
  // Show change handler - reload podcasts
  if (showSelect) {
    showSelect.addEventListener('change', function() {
      loadPodcasts().then(() => {
        currentBefore = null;
        seenBoosts.clear();
        loadBoosts(null, false);
      });
    });
  }
  
  // Podcast change handler - reload boosts
  if (podcastSelect) {
    podcastSelect.addEventListener('change', function() {
      currentBefore = null;
      seenBoosts.clear();
      loadBoosts(null, false);
    });
  }
  
  // Initial load
  loadPodcasts().then(() => {
    loadBoosts(null, false);
  });
  
  // Connect WebSocket for live updates
  connectWebSocket();
  
  // Polling fallback every 60 seconds (for degraded mode / WebSocket failure)
  setInterval(function() {
    if (!isLoading && (!ws || ws.readyState !== WebSocket.OPEN)) {
      loadBoosts(null, false);
    }
  }, 60000);
});
