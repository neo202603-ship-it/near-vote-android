import { setUserId, state, suggestUserId } from '../core/store.js';
import { escapeHtml } from '../core/view.js';

export function myPageScreen() {
  return `
    <section class="panel mypage-panel">
      <div class="panel-heading">
        <h2>마이페이지</h2>
        <span>${state.hasUserId ? '아이디 설정 완료' : '아이디 설정 필요'}</span>
      </div>
      <form class="identity-form mypage-identity" id="identityForm">
        <label>내 아이디
          <input name="userId" value="${escapeHtml(state.userId)}" autocomplete="off" />
        </label>
        <div class="identity-actions">
          <button class="secondary-action" type="button" id="suggestIdentity">제안</button>
          <button class="primary-action" type="submit">저장</button>
        </div>
      </form>
      <div class="profile-note">
        결과 원장에는 이 아이디가 참여자 아이디로 표시됩니다.
      </div>
    </section>
  `;
}

export function bindMyPage() {
  const form = document.querySelector('#identityForm');
  form?.addEventListener('submit', (event) => {
    event.preventDefault();
    setUserId(new FormData(form).get('userId'));
    state.status = '아이디 저장 완료';
    window.dispatchEvent(new CustomEvent('routechange'));
  });

  document.querySelector('#suggestIdentity')?.addEventListener('click', () => {
    form.userId.value = suggestUserId();
  });
}
