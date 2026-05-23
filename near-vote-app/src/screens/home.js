import { navigate } from '../core/router.js';
import { notify, setUserId, state, suggestUserId } from '../core/store.js';
import { escapeHtml } from '../core/view.js';

export function homeScreen() {
  const identitySetup = state.hasUserId ? '' : `
    <form class="identity-form first-identity" id="identityForm">
      <label>내 아이디
        <input name="userId" value="${escapeHtml(state.userId)}" autocomplete="off" />
      </label>
      <div class="identity-actions">
        <button class="secondary-action" type="button" id="suggestIdentity">제안</button>
        <button class="primary-action" type="submit">시작</button>
      </div>
    </form>
  `;

  return `
    <section class="split-layout">
      <div class="hero-panel">
        <p class="eyebrow">Quick start</p>
        <h2>근처 사람들과 서버 없이 바로 결정하기</h2>
        <p>질문을 고르고 게시하면 주변 참여자가 투표하고, 제한 시간 뒤 모두가 같은 결과 원장을 받습니다.</p>
        <div class="action-row">
          <button class="primary-action" type="button" data-route="compose">설문 만들기</button>
          <button class="secondary-action" type="button" data-route="ongoing">진행중인 투표</button>
        </div>
        ${identitySetup}
      </div>
      <div class="panel">
        <div class="panel-heading">
          <h2>지난 결과</h2>
          <button class="ghost-button" type="button" data-route="history">전체</button>
        </div>
        <div class="list">
          ${state.ledgerHistory.length ? state.ledgerHistory.slice(0, 3).map((block) => `
            <article class="list-row">
              <strong>${escapeHtml(block.question)}</strong>
              <span>${block.voteCount}표 · ${block.verified ? '검증 완료' : '검증 실패'}</span>
            </article>
          `).join('') : '<div class="empty-state">아직 저장된 원장이 없습니다.</div>'}
        </div>
      </div>
    </section>
  `;
}

export function bindHome() {
  document.querySelectorAll('[data-route]').forEach((button) => {
    button.addEventListener('click', () => navigate(button.dataset.route));
  });

  const form = document.querySelector('#identityForm');
  form?.addEventListener('submit', (event) => {
    event.preventDefault();
    setUserId(new FormData(form).get('userId'));
    notify('아이디 저장 완료');
    window.dispatchEvent(new CustomEvent('routechange'));
  });

  document.querySelector('#suggestIdentity')?.addEventListener('click', () => {
    form.userId.value = suggestUserId();
  });
}
