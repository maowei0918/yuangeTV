/**
 * 影视盒子 - 基于 Maccms10 API 的视频应用
 */

// ==================== 配置 ====================
const APP_VERSION = '1.0.0';
const API_SUFFIX = '/api.php/provide/vod/';

// 默认解析线路
const DEFAULT_PARSES = [
  { name: '默认解析', url: '' },
  { name: 'M3U8解析', url: 'https://jx.m3u8.tv/jiexi/?url={url}' },
  { name: '777解析', url: 'https://jx.777jiexi.com/player/?url={url}' },
  { name: '夜幕解析', url: 'https://www.yemu.xyz/?url={url}' },
  { name: '爱豆解析', url: 'https://jx.adn.darwinmp4.com/?url={url}' },
  { name: 'OK解析', url: 'https://okjx.cc/?url={url}' },
  { name: '人人迷', url: 'https://jx.blbo.cc:4433/?url={url}' },
  { name: '冰豆解析', url: 'https://api.qianqi.net/?url={url}' },
  { name: 'CK解析', url: 'https://www.ckplayer.vip/jiexi/?url={url}' },
  { name: '虾米解析', url: 'https://jx.xmflv.com/?url={url}' },
];

// ==================== 状态管理 ====================
const state = {
  sites: [],
  parses: [],
  currentSite: null,
  currentCategory: null,
  currentPage: 1,
  totalPage: 1,
  videoList: [],
  favorites: [],
  history: [],
  currentVideo: null,
  currentPlayUrl: '',
  currentParseIndex: 0,
};

// ==================== 数据持久化 ====================
function loadData() {
  try {
    state.sites = JSON.parse(localStorage.getItem('tvbox_sites') || '[]');
    state.parses = JSON.parse(localStorage.getItem('tvbox_parses') || 'null') || DEFAULT_PARSES;
    state.favorites = JSON.parse(localStorage.getItem('tvbox_favorites') || '[]');
    state.history = JSON.parse(localStorage.getItem('tvbox_history') || '[]');
  } catch (e) {
    console.error('加载数据失败:', e);
  }
}

function saveData() {
  localStorage.setItem('tvbox_sites', JSON.stringify(state.sites));
  localStorage.setItem('tvbox_parses', JSON.stringify(state.parses));
  localStorage.setItem('tvbox_favorites', JSON.stringify(state.favorites));
  localStorage.setItem('tvbox_history', JSON.stringify(state.history));
}

// ==================== API 请求 ====================
async function apiRequest(siteUrl, params = {}) {
  const base = siteUrl.replace(/\/+$/, '') + API_SUFFIX;
  const query = new URLSearchParams(params).toString();
  const url = base + (query ? '?' + query : '');
  
  try {
    const res = await fetch(url, {
      headers: { 'Accept': 'application/json' },
      signal: AbortSignal.timeout(15000),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    return data;
  } catch (err) {
    // 尝试通过 CORS 代理
    const proxyUrl = 'https://api.allorigins.win/raw?url=' + encodeURIComponent(url);
    try {
      const res = await fetch(proxyUrl, { signal: AbortSignal.timeout(15000) });
      return await res.json();
    } catch (e2) {
      throw new Error('请求失败: ' + err.message);
    }
  }
}

// ==================== 站点管理 ====================
function renderSiteTabs() {
  const container = document.getElementById('site-tabs');
  if (state.sites.length === 0) {
    container.innerHTML = '<div style="color:var(--text-secondary);font-size:13px;padding:4px 8px;">暂无站点，点击 + 添加</div>';
    return;
  }
  container.innerHTML = state.sites.map((site, i) => `
    <div class="site-tab ${state.currentSite === i ? 'active' : ''}" data-index="${i}">
      ${site.name}
    </div>
  `).join('');
  
  container.querySelectorAll('.site-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      state.currentSite = parseInt(tab.dataset.index);
      state.currentCategory = null;
      state.currentPage = 1;
      renderSiteTabs();
      loadCategories();
    });
  });
}

function renderSitesList() {
  const container = document.getElementById('sites-list');
  if (state.sites.length === 0) {
    container.innerHTML = '<div class="empty"><div class="icon">📡</div><div>暂无站点</div></div>';
    return;
  }
  container.innerHTML = state.sites.map((site, i) => `
    <div class="site-item">
      <div class="site-info">
        <div class="site-name">${site.name}</div>
        <div class="site-url">${site.url}</div>
      </div>
      <button class="btn-del" data-index="${i}" title="删除">🗑️</button>
    </div>
  `).join('');
  
  container.querySelectorAll('.btn-del').forEach(btn => {
    btn.addEventListener('click', () => {
      const idx = parseInt(btn.dataset.index);
      if (confirm(`确定删除站点「${state.sites[idx].name}」？`)) {
        state.sites.splice(idx, 1);
        if (state.currentSite === idx) state.currentSite = null;
        if (state.currentSite > idx) state.currentSite--;
        saveData();
        renderSiteTabs();
        renderSitesList();
        showToast('已删除');
      }
    });
  });
}

function addSite(name, url) {
  // 标准化 URL
  url = url.trim();
  if (!url.startsWith('http')) url = 'https://' + url;
  url = url.replace(/\/+$/, '');
  
  // 检查重复
  if (state.sites.find(s => s.url === url)) {
    showToast('该站点已存在');
    return false;
  }
  
  state.sites.push({ name: name.trim() || url, url });
  saveData();
  renderSiteTabs();
  renderSitesList();
  showToast('添加成功');
  return true;
}

// ==================== 分类管理 ====================
function renderCategories(categories) {
  const container = document.getElementById('category-tabs');
  if (!categories || categories.length === 0) {
    container.innerHTML = '';
    return;
  }
  
  container.innerHTML = categories.map((cat, i) => `
    <div class="cat-tab ${state.currentCategory === i ? 'active' : ''}" data-index="${i}">
      ${cat.type_name}
    </div>
  `).join('');
  
  container.querySelectorAll('.cat-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      state.currentCategory = parseInt(tab.dataset.index);
      state.currentPage = 1;
      renderCategories(categories);
      loadVideoList();
    });
  });
}

async function loadCategories() {
  if (state.currentSite === null) {
    document.getElementById('category-tabs').innerHTML = '';
    document.getElementById('video-list').innerHTML = '';
    return;
  }
  
  const site = state.sites[state.currentSite];
  showLoading(true);
  
  try {
    const data = await apiRequest(site.url, { ac: 'list', pg: 1 });
    if (data && data.class && data.class.length > 0) {
      state._categories = data.class;
      state.currentCategory = 0;
      renderCategories(data.class);
      renderVideoList(data);
    } else {
      throw new Error('无分类数据');
    }
  } catch (err) {
    showToast('加载分类失败: ' + err.message);
    document.getElementById('video-list').innerHTML = `
      <div class="empty" style="grid-column:1/-1">
        <div class="icon">❌</div>
        <div>加载失败</div>
        <div style="font-size:12px;margin-top:8px">${err.message}</div>
      </div>
    `;
  }
  
  showLoading(false);
}

// ==================== 视频列表 ====================
async function loadVideoList() {
  if (state.currentSite === null || state.currentCategory === null) return;
  
  const site = state.sites[state.currentSite];
  const cat = state._categories[state.currentCategory];
  
  showLoading(true);
  
  try {
    const params = { ac: 'list', pg: state.currentPage, t: cat.type_id };
    const data = await apiRequest(site.url, params);
    renderVideoList(data);
  } catch (err) {
    showToast('加载失败: ' + err.message);
  }
  
  showLoading(false);
}

function renderVideoList(data) {
  const container = document.getElementById('video-list');
  
  if (!data || !data.list || data.list.length === 0) {
    container.innerHTML = `
      <div class="empty" style="grid-column:1/-1">
        <div class="icon">📭</div>
        <div>暂无数据</div>
      </div>
    `;
    document.getElementById('pagination').innerHTML = '';
    return;
  }
  
  state.videoList = data.list;
  state.totalPage = parseInt(data.pagecount) || 1;
  state.currentPage = parseInt(data.page) || 1;
  
  container.innerHTML = data.list.map((video, i) => `
    <div class="video-card" data-index="${i}">
      <img class="cover" src="${video.vod_pic || ''}" alt="${video.vod_name}"
           onerror="this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 100 150%22><rect fill=%22%230f3460%22 width=%22100%22 height=%22150%22/><text fill=%22%23666%22 x=%2250%22 y=%2280%22 text-anchor=%22middle%22 font-size=%2214%22>暂无封面</text></svg>'">
      <div class="info">
        <div class="name">${video.vod_name}</div>
        <div class="meta">${video.vod_remarks || video.vod_year || ''}</div>
      </div>
    </div>
  `).join('');
  
  container.querySelectorAll('.video-card').forEach(card => {
    card.addEventListener('click', () => {
      const idx = parseInt(card.dataset.index);
      showDetail(data.list[idx]);
    });
  });
  
  renderPagination();
}

function renderPagination() {
  const container = document.getElementById('pagination');
  if (state.totalPage <= 1) {
    container.innerHTML = '';
    return;
  }
  
  let html = '';
  if (state.currentPage > 1) {
    html += `<button class="page-btn" data-page="${state.currentPage - 1}">上一页</button>`;
  }
  
  // 显示页码范围
  const start = Math.max(1, state.currentPage - 3);
  const end = Math.min(state.totalPage, state.currentPage + 3);
  
  for (let i = start; i <= end; i++) {
    html += `<button class="page-btn ${i === state.currentPage ? 'active' : ''}" data-page="${i}">${i}</button>`;
  }
  
  if (state.currentPage < state.totalPage) {
    html += `<button class="page-btn" data-page="${state.currentPage + 1}">下一页</button>`;
  }
  
  container.innerHTML = html;
  
  container.querySelectorAll('.page-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      state.currentPage = parseInt(btn.dataset.page);
      loadVideoList();
      document.getElementById('main').scrollIntoView({ behavior: 'smooth' });
    });
  });
}

// ==================== 详情页 ====================
async function showDetail(video) {
  state.currentVideo = video;
  const modal = document.getElementById('detail-modal');
  const body = document.getElementById('detail-body');
  
  // 解析播放地址
  const playSources = parsePlayUrls(video.vod_play_url || '');
  state._playSources = playSources;
  
  body.innerHTML = `
    <img class="detail-poster" src="${video.vod_pic || ''}" 
         onerror="this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 120 180%22><rect fill=%22%230f3460%22 width=%22120%22 height=%22180%22/><text fill=%22%23666%22 x=%2260%22 y=%2295%22 text-anchor=%22middle%22 font-size=%2212%22>暂无封面</text></svg>'">
    <div class="detail-title">${video.vod_name}</div>
    <div class="detail-info">
      <div>类型：${video.vod_class || '未知'}</div>
      <div>年份：${video.vod_year || '未知'}</div>
      <div>地区：${video.vod_area || '未知'}</div>
      <div>状态：${video.vod_remarks || '未知'}</div>
      <div>主演：${video.vod_actor || '未知'}</div>
      <div>导演：${video.vod_director || '未知'}</div>
    </div>
    <div class="detail-desc">${video.vod_content || '暂无简介'}</div>
    <div class="play-sources">
      <div class="source-title">播放源</div>
      <div class="source-btns">
        ${playSources.map((src, i) => `
          <button class="source-btn ${i === 0 ? 'active' : ''}" data-index="${i}">${src.name}</button>
        `).join('')}
      </div>
    </div>
    <div style="margin-top:16px;display:flex;gap:8px;">
      <button id="btn-play" class="btn-primary" style="flex:1;padding:12px;font-size:16px;">▶ 立即播放</button>
      <button id="btn-favorite" class="btn-primary" style="background:var(--bg-card);border:1px solid var(--border);">
        ${isFavorite(video.vod_id) ? '★ 已收藏' : '☆ 收藏'}
      </button>
    </div>
  `;
  
  modal.classList.remove('hide');
  
  // 播放源切换
  let currentSourceIndex = 0;
  body.querySelectorAll('.source-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      body.querySelectorAll('.source-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentSourceIndex = parseInt(btn.dataset.index);
    });
  });
  
  // 播放按钮
  document.getElementById('btn-play').addEventListener('click', () => {
    modal.classList.add('hide');
    const source = playSources[currentSourceIndex];
    if (source) {
      playVideo(video.vod_name, source.urls);
    }
  });
  
  // 收藏按钮
  document.getElementById('btn-favorite').addEventListener('click', (e) => {
    toggleFavorite(video);
    e.target.textContent = isFavorite(video.vod_id) ? '★ 已收藏' : '☆ 收藏';
  });
  
  // 添加到历史
  addHistory(video);
}

// 解析播放地址
function parsePlayUrls(urls) {
  if (!urls) return [];
  const sources = [];
  const lines = urls.split('$$$');
  
  lines.forEach(line => {
    const parts = line.split('$');
    if (parts.length >= 2) {
      sources.push({
        name: parts[0],
        urls: parts.slice(1).filter(u => u.trim()),
      });
    } else if (parts.length === 1 && parts[0].includes('#')) {
      // 格式: 线路名#url1#url2
      const items = parts[0].split('#');
      sources.push({
        name: items[0],
        urls: items.slice(1).filter(u => u.trim()),
      });
    }
  });
  
  return sources;
}

// ==================== 播放 ====================
function playVideo(title, urls) {
  const page = document.getElementById('player-page');
  const iframe = document.getElementById('player-frame');
  const titleEl = document.getElementById('player-title');
  
  titleEl.textContent = title;
  state._playUrls = urls;
  state.currentParseIndex = 0;
  
  // 渲染解析按钮
  renderParseButtons();
  
  // 开始播放第一个地址
  playWithUrl(urls[0]);
  
  page.classList.remove('hide');
}

function playWithUrl(url) {
  const iframe = document.getElementById('player-frame');
  const parse = state.parses[state.currentParseIndex];
  
  if (parse.url) {
    // 使用解析线路
    const parseUrl = parse.url.replace('{url}', encodeURIComponent(url));
    iframe.src = parseUrl;
  } else {
    // 直接播放
    if (url.endsWith('.m3u8') || url.endsWith('.mp4')) {
      // 使用内置播放器
      iframe.srcdoc = `
        <!DOCTYPE html>
        <html><head><style>body{margin:0;background:#000;display:flex;justify-content:center;align-items:center;height:100vh}video{max-width:100%;max-height:100%}</style></head>
        <body><video controls autoplay src="${url}" style="width:100%;height:100%"></video></body>
      `;
    } else {
      iframe.src = url;
    }
  }
}

function renderParseButtons() {
  const container = document.getElementById('parse-buttons');
  container.innerHTML = state.parses.map((p, i) => `
    <button class="parse-btn ${i === state.currentParseIndex ? 'active' : ''}" data-index="${i}">${p.name}</button>
  `).join('');
  
  container.querySelectorAll('.parse-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      state.currentParseIndex = parseInt(btn.dataset.index);
      renderParseButtons();
      if (state._playUrls && state._playUrls.length > 0) {
        playWithUrl(state._playUrls[0]);
      }
    });
  });
}

// ==================== 解析管理 ====================
function renderParseList() {
  const container = document.getElementById('parse-list');
  container.innerHTML = state.parses.map((p, i) => `
    <div class="parse-item">
      <div>
        <div class="parse-name">${p.name}</div>
        <div class="parse-url">${p.url || '直接播放'}</div>
      </div>
      <button class="btn-del-parse" data-index="${i}" title="删除">🗑️</button>
    </div>
  `).join('');
  
  container.querySelectorAll('.btn-del-parse').forEach(btn => {
    btn.addEventListener('click', () => {
      const idx = parseInt(btn.dataset.index);
      if (state.parses.length <= 1) {
        showToast('至少保留一条解析线路');
        return;
      }
      state.parses.splice(idx, 1);
      if (state.currentParseIndex >= state.parses.length) {
        state.currentParseIndex = state.parses.length - 1;
      }
      saveData();
      renderParseList();
      showToast('已删除');
    });
  });
}

function addParse(name, url) {
  state.parses.push({ name: name.trim() || '新线路', url: url.trim() });
  saveData();
  renderParseList();
  showToast('添加成功');
}

// ==================== 收藏 ====================
function isFavorite(vodId) {
  return state.favorites.find(f => f.vod_id == vodId);
}

function toggleFavorite(video) {
  const idx = state.favorites.findIndex(f => f.vod_id == video.vod_id);
  if (idx >= 0) {
    state.favorites.splice(idx, 1);
    showToast('已取消收藏');
  } else {
    state.favorites.push({
      ...video,
      _favTime: Date.now(),
    });
    showToast('已收藏');
  }
  saveData();
}

function renderFavorites() {
  const page = document.getElementById('list-page');
  const title = document.getElementById('list-title');
  const content = document.getElementById('list-content');
  
  title.textContent = '我的收藏';
  
  if (state.favorites.length === 0) {
    content.innerHTML = '<div class="empty" style="grid-column:1/-1"><div class="icon">⭐</div><div>暂无收藏</div></div>';
  } else {
    content.innerHTML = state.favorites.map((video, i) => `
      <div class="video-card" data-index="${i}" data-type="fav">
        <img class="cover" src="${video.vod_pic || ''}" alt="${video.vod_name}"
             onerror="this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 100 150%22><rect fill=%22%230f3460%22 width=%22100%22 height=%22150%22/><text fill=%22%23666%22 x=%2250%22 y=%2280%22 text-anchor=%22middle%22 font-size=%2214%22>暂无封面</text></svg>'">
        <div class="info">
          <div class="name">${video.vod_name}</div>
          <div class="meta">${video.vod_remarks || video.vod_year || ''}</div>
        </div>
      </div>
    `).join('');
    
    content.querySelectorAll('.video-card').forEach(card => {
      card.addEventListener('click', () => {
        const idx = parseInt(card.dataset.index);
        showDetail(state.favorites[idx]);
      });
    });
  }
  
  page.classList.remove('hide');
}

// ==================== 历史 ====================
function addHistory(video) {
  // 去重，移到最前
  state.history = state.history.filter(h => h.vod_id != video.vod_id);
  state.history.unshift({
    ...video,
    _historyTime: Date.now(),
  });
  // 最多保留 100 条
  if (state.history.length > 100) state.history = state.history.slice(0, 100);
  saveData();
}

function renderHistory() {
  const page = document.getElementById('list-page');
  const title = document.getElementById('list-title');
  const content = document.getElementById('list-content');
  
  title.textContent = '播放历史';
  
  if (state.history.length === 0) {
    content.innerHTML = '<div class="empty" style="grid-column:1/-1"><div class="icon">🕐</div><div>暂无历史记录</div></div>';
  } else {
    content.innerHTML = state.history.map((video, i) => `
      <div class="video-card" data-index="${i}" data-type="history">
        <img class="cover" src="${video.vod_pic || ''}" alt="${video.vod_name}"
             onerror="this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 100 150%22><rect fill=%22%230f3460%22 width=%22100%22 height=%22150%22/><text fill=%22%23666%22 x=%2250%22 y=%2280%22 text-anchor=%22middle%22 font-size=%2214%22>暂无封面</text></svg>'">
        <div class="info">
          <div class="name">${video.vod_name}</div>
          <div class="meta">${video.vod_remarks || video.vod_year || ''}</div>
        </div>
      </div>
    `).join('');
    
    content.querySelectorAll('.video-card').forEach(card => {
      card.addEventListener('click', () => {
        const idx = parseInt(card.dataset.index);
        showDetail(state.history[idx]);
      });
    });
  }
  
  page.classList.remove('hide');
}

// ==================== 搜索 ====================
async function doSearch(keyword) {
  if (!keyword.trim()) {
    showToast('请输入搜索关键词');
    return;
  }
  if (state.currentSite === null) {
    showToast('请先选择站点');
    return;
  }
  
  const site = state.sites[state.currentSite];
  showLoading(true);
  
  try {
    const data = await apiRequest(site.url, { ac: 'list', wd: keyword, pg: 1 });
    const container = document.getElementById('search-results');
    
    if (!data || !data.list || data.list.length === 0) {
      container.innerHTML = '<div class="empty" style="grid-column:1/-1"><div class="icon">🔍</div><div>未找到相关结果</div></div>';
    } else {
      container.innerHTML = data.list.map((video, i) => `
        <div class="video-card" data-index="${i}">
          <img class="cover" src="${video.vod_pic || ''}" alt="${video.vod_name}"
               onerror="this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 100 150%22><rect fill=%22%230f3460%22 width=%22100%22 height=%22150%22/><text fill=%22%23666%22 x=%2250%22 y=%2280%22 text-anchor=%22middle%22 font-size=%2214%22>暂无封面</text></svg>'">
          <div class="info">
            <div class="name">${video.vod_name}</div>
            <div class="meta">${video.vod_remarks || video.vod_year || ''}</div>
          </div>
        </div>
      `).join('');
      
      container.querySelectorAll('.video-card').forEach(card => {
        card.addEventListener('click', () => {
          const idx = parseInt(card.dataset.index);
          document.getElementById('search-modal').classList.add('hide');
          showDetail(data.list[idx]);
        });
      });
    }
  } catch (err) {
    showToast('搜索失败: ' + err.message);
  }
  
  showLoading(false);
}

// ==================== 工具函数 ====================
function showLoading(show) {
  document.getElementById('loading').classList.toggle('hide', !show);
}

function showToast(msg) {
  let toast = document.getElementById('toast');
  if (!toast) {
    toast = document.createElement('div');
    toast.id = 'toast';
    toast.className = 'hide';
    document.body.appendChild(toast);
  }
  toast.textContent = msg;
  toast.classList.remove('hide');
  setTimeout(() => toast.classList.add('hide'), 2000);
}

// ==================== 事件绑定 ====================
function bindEvents() {
  // 搜索
  document.getElementById('btn-search').addEventListener('click', () => {
    document.getElementById('search-modal').classList.remove('hide');
    document.getElementById('search-input').focus();
  });
  document.getElementById('btn-close-search').addEventListener('click', () => {
    document.getElementById('search-modal').classList.add('hide');
  });
  document.getElementById('btn-do-search').addEventListener('click', () => {
    doSearch(document.getElementById('search-input').value);
  });
  document.getElementById('search-input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') doSearch(e.target.value);
  });
  
  // 站点管理
  document.getElementById('btn-sites').addEventListener('click', () => {
    renderSitesList();
    document.getElementById('sites-modal').classList.remove('hide');
  });
  document.getElementById('btn-close-sites').addEventListener('click', () => {
    document.getElementById('sites-modal').classList.add('hide');
  });
  document.getElementById('btn-add-site').addEventListener('click', () => {
    renderSitesList();
    document.getElementById('sites-modal').classList.remove('hide');
  });
  document.getElementById('btn-save-site').addEventListener('click', () => {
    const name = document.getElementById('new-site-name').value;
    const url = document.getElementById('new-site-url').value;
    if (!url) { showToast('请输入域名'); return; }
    if (addSite(name, url)) {
      document.getElementById('new-site-name').value = '';
      document.getElementById('new-site-url').value = '';
    }
  });
  
  // 解析管理 - 在站点管理弹窗中添加入口
  // 收藏
  document.getElementById('btn-favorites').addEventListener('click', renderFavorites);
  
  // 历史
  document.getElementById('btn-history').addEventListener('click', renderHistory);
  
  // 详情关闭
  document.getElementById('btn-close-detail').addEventListener('click', () => {
    document.getElementById('detail-modal').classList.add('hide');
  });
  
  // 播放页面
  document.getElementById('btn-back').addEventListener('click', () => {
    document.getElementById('player-page').classList.add('hide');
    document.getElementById('player-frame').src = '';
  });
  document.getElementById('btn-switch-parse').addEventListener('click', () => {
    document.getElementById('parse-switch-bar').classList.toggle('hide');
  });
  
  // 列表页返回
  document.getElementById('btn-back-list').addEventListener('click', () => {
    document.getElementById('list-page').classList.add('hide');
  });
  
  // 解析管理按钮（在站点管理弹窗中）
  // 添加解析管理入口到站点管理弹窗
  document.getElementById('btn-close-sites').addEventListener('click', () => {
    document.getElementById('sites-modal').classList.add('hide');
  });
}

// 添加解析管理按钮到站点管理弹窗
function addParseManagementToSitesModal() {
  const sitesModal = document.getElementById('sites-modal');
  const modalContent = sitesModal.querySelector('.modal-content');
  
  // 检查是否已添加
  if (document.getElementById('btn-manage-parse')) return;
  
  const parseBtn = document.createElement('button');
  parseBtn.id = 'btn-manage-parse';
  parseBtn.className = 'btn-primary';
  parseBtn.style.cssText = 'margin:8px 12px;padding:8px;width:calc(100% - 24px);';
  parseBtn.textContent = '🔄 管理解析线路';
  parseBtn.addEventListener('click', () => {
    document.getElementById('sites-modal').classList.add('hide');
    renderParseList();
    document.getElementById('parse-modal').classList.remove('hide');
  });
  
  modalContent.insertBefore(parseBtn, modalContent.querySelector('.add-site-form'));
  
  // 解析弹窗事件
  document.getElementById('btn-close-parse').addEventListener('click', () => {
    document.getElementById('parse-modal').classList.add('hide');
  });
  document.getElementById('btn-save-parse').addEventListener('click', () => {
    const name = document.getElementById('new-parse-name').value;
    const url = document.getElementById('new-parse-url').value;
    if (!url) { showToast('请输入解析URL'); return; }
    addParse(name, url);
    document.getElementById('new-parse-name').value = '';
    document.getElementById('new-parse-url').value = '';
  });
}

// ==================== 初始化 ====================
function init() {
  loadData();
  renderSiteTabs();
  bindEvents();
  addParseManagementToSitesModal();
  
  // 如果有站点，自动加载第一个
  if (state.sites.length > 0) {
    state.currentSite = 0;
    renderSiteTabs();
    loadCategories();
  }
}

document.addEventListener('DOMContentLoaded', init);
