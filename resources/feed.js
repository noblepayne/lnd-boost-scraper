// Feed page JavaScript - Helipad-style boost feed

document.addEventListener('DOMContentLoaded', function() {
  const feedContainer = document.getElementById('feed-container');
  const loadMoreBtn = document.getElementById('load-more');
  const filterForm = document.getElementById('feed-filters');
  const podcastSelect = document.getElementById('filter-podcast');
  const showSelect = document.getElementById('filter-show');
  
  let currentBefore = null;
  let isLoading = false;
  
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
  
  // Create a boost card HTML
  function createBoostCard(boost) {
    const card = document.createElement('div');
    card.className = 'boost-card';
    card.dataset.time = boost.time;
    
    const messageHtml = boost.message ? 
      `<div class="boost-message">${escapeHtml(boost.message)}</div>` : '';
    
    card.innerHTML = `
      <div class="boost-meta">
        <span class="boost-app">${escapeHtml(boost.app)}</span>
        <span class="boost-time" title="${formatFullDateTime(boost.time)}">${formatRelativeTime(boost.time)}</span>
      </div>
      <div class="boost-amount">
        <span class="sats">${formatSats(boost.sats)} sats</span>
      </div>
      <div class="boost-sender">from ${escapeHtml(boost.sender)}</div>
      <div class="boost-episode">${escapeHtml(boost.podcast)} — ${escapeHtml(boost.episode)}</div>
      ${messageHtml}
    `;
    
    return card;
  }
  
  // Escape HTML to prevent XSS
  function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
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
    if (before) url += `&before=${before}`;
    
    try {
      const response = await fetch(url);
      if (!response.ok) throw new Error('Failed to load boosts');
      
      const boosts = await response.json();
      
      if (!append) {
        feedContainer.innerHTML = '';
      }
      
      if (boosts.length === 0 && !append) {
        feedContainer.innerHTML = `
          <div class="feed-empty">
            <p>No boosts found.</p>
            <p>This screen will automatically refresh when new boosts are received.</p>
          </div>
        `;
        loadMoreBtn.style.display = 'none';
        return;
      }
      
      // Add boost cards
      boosts.forEach(boost => {
        feedContainer.appendChild(createBoostCard(boost));
      });
      
      // Update cursor for next page
      if (boosts.length > 0) {
        currentBefore = boosts[boosts.length - 1].time;
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
      loadBoosts(null, false);
    });
  }
  
  // Show change handler - reload podcasts
  if (showSelect) {
    showSelect.addEventListener('change', function() {
      loadPodcasts().then(() => {
        currentBefore = null;
        loadBoosts(null, false);
      });
    });
  }
  
  // Podcast change handler - reload boosts
  if (podcastSelect) {
    podcastSelect.addEventListener('change', function() {
      currentBefore = null;
      loadBoosts(null, false);
    });
  }
  
  // Initial load
  loadPodcasts().then(() => {
    loadBoosts(null, false);
  });
  
  // Auto-refresh every 60 seconds
  setInterval(function() {
    if (!isLoading) {
      loadBoosts(null, false);
    }
  }, 60000);
});
