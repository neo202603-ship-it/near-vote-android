import { navigate } from '../core/router.js';
import { castVote, participants, state } from '../core/store.js';
import { emptyState, escapeHtml } from '../core/view.js';

export function voteScreen() {
  if (!state.activePoll) return `<section class="panel">${emptyState('투표할 설문이 없습니다.')}</section>`;
  const proposer = participants.find((participant) => participant.id === 'proposer');

  return `
    <section class="panel focus-panel">
      <div class="panel-heading">
        <h2>내 투표</h2>
        <span>${proposer.voted ? '서명 완료' : '선택 대기'}</span>
      </div>
      <div class="poll-card">
        <h2>${escapeHtml(state.activePoll.question)}</h2>
        <div class="choice-grid">
          ${state.activePoll.options.map((option) => `
            <button type="button" ${proposer.voted ? 'disabled' : ''} data-choice="${escapeHtml(option)}">${escapeHtml(option)}</button>
          `).join('')}
        </div>
      </div>
    </section>
  `;
}

export function bindVote() {
  document.querySelectorAll('[data-choice]').forEach((button) => {
    button.addEventListener('click', async () => {
      await castVote('proposer', button.dataset.choice);
      navigate('wait');
    });
  });
}

