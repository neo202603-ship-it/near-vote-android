import { navigate } from '../core/router.js';
import { finalizePoll, participants, state } from '../core/store.js';
import { emptyState, escapeHtml } from '../core/view.js';

export function waitScreen() {
  if (!state.activePoll) return `<section class="panel">${emptyState('진행 중인 설문이 없습니다.')}</section>`;
  const left = Math.max(0, Math.ceil((new Date(state.activePoll.deadline).getTime() - Date.now()) / 1000));
  const myReceipt = state.receipts.find((receipt) => receipt.voterId === 'proposer');

  return `
    <section class="mesh-layout">
      <div class="panel focus-panel">
        <div class="panel-heading">
          <h2>결과 대기</h2>
          <span class="timer">${left}s</span>
        </div>
        <div class="poll-card">
          <h2>${escapeHtml(state.activePoll.question)}</h2>
          <p>${state.votes.length}개의 서명된 투표를 수집했습니다.</p>
          ${myReceipt ? receiptCard(myReceipt) : ''}
          <button class="primary-action" id="finalizeNow" type="button" ${left > 0 ? 'disabled' : ''}>${left > 0 ? '제한 시간 진행 중' : '결과 블록 보기'}</button>
        </div>
      </div>
      <div class="panel">
        <div class="panel-heading"><h2>참여자</h2></div>
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

function receiptCard(receipt) {
  return `
    <div class="receipt-card">
      <span>투표 접수증</span>
      <strong>${escapeHtml(receipt.choice)}</strong>
      <small>${escapeHtml(receipt.voteHash.slice(0, 24))}</small>
    </div>
  `;
}

export function bindWait(render) {
  document.querySelector('#finalizeNow')?.addEventListener('click', async () => {
    const block = await finalizePoll();
    if (block) navigate('result');
    else render();
  });

  if (!state.resultBlock) {
    clearInterval(state.timerId);
    state.timerId = setInterval(async () => {
      if (!state.activePoll) return;
      if (new Date(state.activePoll.deadline).getTime() <= Date.now()) {
        clearInterval(state.timerId);
        await finalizePoll();
        navigate('result');
      } else {
        render();
      }
    }, 1000);
  }
}
