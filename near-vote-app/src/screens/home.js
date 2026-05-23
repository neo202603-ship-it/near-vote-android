import { navigate } from '../core/router.js';
import { notify, setUserId, state, suggestUserId } from '../core/store.js';
import { escapeHtml, formatHumanTime } from '../core/view.js';

export function homeScreen() {
  const latestResult = state.ledgerHistory[0];
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
          <h2>최근 결과</h2>
          <button class="ghost-button" type="button" data-route="history">전체</button>
        </div>
        ${latestResult ? recentResultCard(latestResult) : '<div class="empty-state compact-empty">아직 저장된 결과가 없습니다.</div>'}
      </div>
    </section>
  `;
}

function recentResultCard(block) {
  const winner = topResult(block);
  const total = block.participantCount || block.voteCount || 0;
  const percent = total ? Math.round((winner.count / total) * 100) : 0;

  return `
    <article class="recent-result-card">
      <span>${escapeHtml(formatHumanTime(block.createdAt))}</span>
      <h3>${escapeHtml(block.question)}</h3>
      <div class="recent-winner">
        <strong>${escapeHtml(winner.option)}</strong>
        <meter min="0" max="${Math.max(1, total)}" value="${winner.count}"></meter>
        <em>${winner.count}명 (${percent}%)</em>
      </div>
      <small>참여 ${total}명 · 투표 ${block.voteCount}표 · ${block.verified ? '검증 완료' : '검증 실패'}</small>
    </article>
  `;
}

function topResult(block) {
  const [option, count] = Object.entries(block.result)
    .sort((first, second) => second[1] - first[1])[0] || ['결과 없음', 0];
  return { option, count };
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
