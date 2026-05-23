export function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

export function formatHumanTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '일시 없음';

  const diffSeconds = Math.round((date.getTime() - Date.now()) / 1000);
  const absSeconds = Math.abs(diffSeconds);
  const rtf = new Intl.RelativeTimeFormat('ko', { numeric: 'auto' });

  if (absSeconds < 45) return '방금 전';
  if (absSeconds < 3600) return rtf.format(Math.round(diffSeconds / 60), 'minute');
  if (absSeconds < 86400) return rtf.format(Math.round(diffSeconds / 3600), 'hour');
  if (absSeconds < 604800) return rtf.format(Math.round(diffSeconds / 86400), 'day');

  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: date.getFullYear() === new Date().getFullYear() ? 'medium' : 'long',
    timeStyle: 'short'
  }).format(date);
}

export function shell(content, activeRoute, status) {
  const primaryItems = [
    ['home', '홈'],
    ['compose', '작성'],
    ['ongoing', '진행중'],
    ['history', '지난 결과'],
    ['mypage', '마이페이지']
  ];
  return `
    <main class="app-shell">
      <header class="topbar">
        <div>
          <p class="eyebrow">Serverless local poll</p>
          <h1>Near Vote</h1>
        </div>
        <span class="status-pill">${escapeHtml(status)}</span>
      </header>
      <nav class="topnav" aria-label="주요 화면">
        <div class="primary-tabs">
          ${primaryItems.map(([route, label]) => `
            <button type="button" class="${activeRoute === route ? 'active' : ''}" data-route="${route}">${label}</button>
          `).join('')}
        </div>
      </nav>
      ${content}
    </main>
  `;
}

export function emptyState(message) {
  return `<div class="empty-state">${escapeHtml(message)}</div>`;
}
