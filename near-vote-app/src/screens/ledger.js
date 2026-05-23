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
        ${state.ledgerHistory.map((block, index) => `
          <article class="history-card">
            <div>
              <div class="card-kicker">
                <span>${escapeHtml(formatHumanTime(block.createdAt))}</span>
                <span class="verify-icon small ${block.verified ? 'ok' : 'fail'}" title="${block.verified ? '검증 완료' : '검증 실패'}" aria-label="${block.verified ? '검증 완료' : '검증 실패'}">${block.verified ? '✓' : '!'}</span>
              </div>
              <h3>${escapeHtml(block.question)}</h3>
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
            <div class="history-footer">
              <small>참여 ${participantCount(block)}명 · 투표 ${block.voteCount}표 · ${escapeHtml(block.blockHash.slice(0, 16))}</small>
              <button class="ghost-button compact" type="button" data-participants-index="${index}">참여자 보기</button>
            </div>
          </article>
        `).join('')}
      </div>
      <div id="participantLayer"></div>
    </section>
  `;
}

export function bindLedger(render) {
  document.querySelectorAll('[data-participants-index]').forEach((button) => {
    button.addEventListener('click', () => {
      const block = state.ledgerHistory[Number(button.dataset.participantsIndex)];
      showParticipantLayer(block);
    });
  });
}

function showParticipantLayer(block) {
  const layer = document.querySelector('#participantLayer');
  const ids = block.participantIds || block.replicatedTo || [];
  layer.innerHTML = `
    <div class="modal-backdrop" data-close-participants>
      <section class="modal-panel" role="dialog" aria-modal="true" aria-label="참여자 목록">
        <div class="modal-heading">
          <h2>참여자 목록</h2>
          <button class="ghost-button compact" type="button" data-close-participants>닫기</button>
        </div>
        <div class="participant-list-layer">
          ${ids.map((id) => `<span>${escapeHtml(id)}</span>`).join('') || '<span>참여자 없음</span>'}
        </div>
      </section>
    </div>
  `;

  layer.querySelectorAll('[data-close-participants]').forEach((item) => {
    item.addEventListener('click', (event) => {
      if (event.target === item || item.tagName === 'BUTTON') {
        layer.innerHTML = '';
      }
    });
  });
}

function participantCount(block) {
  return block.participantCount || (block.participantIds || block.replicatedTo || []).length || block.voteCount || 0;
}

function percent(count, total) {
  if (!total) return 0;
  return Math.round((count / total) * 100);
}
