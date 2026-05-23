import { state } from '../core/store.js';
import { emptyState, escapeHtml, formatHumanTime } from '../core/view.js';

export function ledgerScreen() {
  if (!state.ledgerHistory.length) {
    return `
      <section class="panel">
        <div class="panel-heading"><h2>지난 결과</h2></div>
        ${emptyState('아직 지난 결과가 없습니다.')}
      </section>
    `;
  }

  return `
    <section class="panel">
      <div class="panel-heading"><h2>지난 결과</h2></div>
      <div class="history-grid">
        ${state.ledgerHistory.map((block) => `
          <article class="history-card">
            <div>
              <div class="card-kicker">
                <span>${escapeHtml(formatHumanTime(block.createdAt))}</span>
                <span class="verify-icon small ${block.verified ? 'ok' : 'fail'}" title="${block.verified ? '검증 완료' : '검증 실패'}" aria-label="${block.verified ? '검증 완료' : '검증 실패'}">${block.verified ? '✓' : '!'}</span>
              </div>
              <h3>${escapeHtml(block.question)}</h3>
            </div>
            <div class="participant-chipline">
              ${(block.participantIds || block.replicatedTo || []).map((id) => `<span>${escapeHtml(id)}</span>`).join('')}
            </div>
            <div class="mini-results">
              ${Object.entries(block.result).map(([option, count]) => `
                <div>
                  <strong>${escapeHtml(option)}</strong>
                  <meter min="0" max="${Math.max(1, participantCount(block))}" value="${count}"></meter>
                  <span>${count}명 (${percent(count, participantCount(block))}%)</span>
                </div>
              `).join('')}
            </div>
            <small>참여 ${participantCount(block)}명 · 투표 ${block.voteCount}표 · ${escapeHtml(block.blockHash.slice(0, 16))}</small>
          </article>
        `).join('')}
      </div>
    </section>
  `;
}

export function bindLedger() {}

function participantCount(block) {
  return block.participantCount || (block.participantIds || block.replicatedTo || []).length || block.voteCount || 0;
}

function percent(count, total) {
  if (!total) return 0;
  return Math.round((count / total) * 100);
}
