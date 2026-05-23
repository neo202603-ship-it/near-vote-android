import { navigate } from '../core/router.js';
import { inviteNearbyParticipants, participants, state } from '../core/store.js';
import { emptyState, escapeHtml } from '../core/view.js';

export function discoverScreen() {
  if (!hasOngoingPoll()) {
    return `
      <section class="panel">
        <div class="panel-heading">
          <h2>진행중인 투표</h2>
          <button class="ghost-button" type="button" data-route="compose">새 투표</button>
        </div>
        ${emptyState('진행중인 투표가 없습니다.')}
      </section>
    `;
  }

  const votedCount = participants.filter((participant) => participant.voted).length;
  const joinedCount = participants.filter((participant) => participant.joined).length;
  const proposer = participants.find((participant) => participant.id === 'proposer');

  return `
    <section class="mesh-layout">
      <div class="panel focus-panel">
        <div class="panel-heading">
          <h2>진행중인 투표</h2>
          <span class="timer">${secondsLeft()}s</span>
        </div>
        <div class="poll-card">
          <h2>${escapeHtml(state.activePoll.question)}</h2>
          <p>${escapeHtml(state.activePoll.options.join(' · '))}</p>
          <div class="status-grid">
            <article><span>참여</span><strong>${joinedCount}</strong></article>
            <article><span>투표</span><strong>${votedCount}</strong></article>
            <article><span>내 투표</span><strong>${proposer.voted ? '완료' : '대기'}</strong></article>
          </div>
          <div class="action-row">
            <button class="primary-action" id="joinPoll" type="button">${proposer.voted ? '내 투표 보기' : '참여하고 투표하기'}</button>
            <button class="secondary-action" id="simulateNearby" type="button">주변 참여자 초대</button>
            <button class="secondary-action" type="button" data-route="wait">결과 대기</button>
          </div>
        </div>
      </div>

      <div class="panel">
        <div class="panel-heading"><h2>참여 상태</h2></div>
        <div class="list">
          ${participants.map((participant) => `
            <article class="list-row">
              <strong>${escapeHtml(participant.name)}</strong>
              <span>${participant.joined ? participant.voted ? '투표 서명' : '참여' : '대기'}</span>
            </article>
          `).join('')}
        </div>
      </div>
    </section>
  `;
}

export function bindDiscover() {
  document.querySelector('#joinPoll')?.addEventListener('click', () => navigate('vote'));
  document.querySelector('#simulateNearby')?.addEventListener('click', async () => {
    await inviteNearbyParticipants();
    navigate('wait');
  });
  document.querySelectorAll('[data-route]').forEach((button) => {
    button.addEventListener('click', () => navigate(button.dataset.route));
  });
}

function secondsLeft() {
  if (!state.activePoll) return 0;
  return Math.max(0, Math.ceil((new Date(state.activePoll.deadline).getTime() - Date.now()) / 1000));
}

function hasOngoingPoll() {
  return Boolean(state.activePoll && !state.resultBlock && secondsLeft() > 0);
}
