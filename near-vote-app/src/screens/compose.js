import { navigate } from '../core/router.js';
import { canDeleteTemplate, createId, normalizeTemplate, notify, publishPoll, saveTemplates, state } from '../core/store.js';
import { escapeHtml, formatHumanTime } from '../core/view.js';

export function composeScreen() {
  const current = state.templates.find((template) => template.id === state.editingTemplateId) || state.templates[0];
  const isEditingMine = canDeleteTemplate(current) && current.id === state.editingTemplateId;
  const formTemplate = state.draftTemplate || (state.editingTemplateId ? current : null);

  return `
    <section class="compose-layout">
      <form class="panel compose-form" id="composeForm">
        <div class="panel-heading">
          <h2>${isEditingMine ? '템플릿 수정' : '설문 작성'}</h2>
          <div class="form-heading-actions">
            <button class="secondary-action compact" id="openTemplates" type="button">템플릿</button>
            <button class="primary-action compact" type="submit">근거리 게시</button>
          </div>
        </div>
        <label>질문
          <input name="question" value="${escapeHtml(formTemplate?.question || '')}" placeholder="질문을 입력하세요" autocomplete="off" />
        </label>
        <label>선택지
          <input name="options" value="${escapeHtml(formTemplate?.options.join(', ') || '')}" placeholder="쉼표로 선택지를 구분하세요" autocomplete="off" />
        </label>
        <label>제한 시간
          <select name="duration">
            <option value="30" ${formTemplate?.duration === 30 ? 'selected' : ''}>30초</option>
            <option value="60" ${!formTemplate || formTemplate.duration === 60 ? 'selected' : ''}>1분</option>
            <option value="180" ${formTemplate?.duration === 180 ? 'selected' : ''}>3분</option>
          </select>
        </label>
        <button class="secondary-action" id="saveTemplate" type="button">${isEditingMine ? '변경 저장' : '템플릿으로 저장'}</button>
      </form>
      <div id="templateLayer">
        ${state.templatePickerOpen ? templateLayer() : ''}
      </div>
    </section>
  `;
}

export function bindCompose(render) {
  const form = document.querySelector('#composeForm');
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const template = templateFromForm(form);
    if (!isValidTemplate(template)) {
      notify('질문과 선택지를 확인하세요');
      render();
      return;
    }
    await publishPoll(template);
    state.draftTemplate = null;
    state.templatePickerOpen = false;
    navigate('ongoing');
  });

  document.querySelector('#openTemplates').addEventListener('click', () => {
    state.templatePickerOpen = true;
    render();
  });

  document.querySelector('#saveTemplate').addEventListener('click', () => {
    const editingTemplate = state.templates.find((template) => template.id === state.editingTemplateId);
    const nextTemplate = templateFromForm(form, editingTemplate);
    if (!isValidTemplate(nextTemplate)) {
      notify('질문과 선택지를 확인하세요');
      render();
      return;
    }

    if (canDeleteTemplate(editingTemplate)) {
      state.templates = state.templates.map((template) => template.id === editingTemplate.id ? nextTemplate : template);
    } else {
      state.templates = [nextTemplate, ...state.templates];
    }

    state.editingTemplateId = nextTemplate.id;
    state.draftTemplate = null;
    state.templatePickerOpen = false;
    saveTemplates();
    render();
  });

  document.querySelectorAll('[data-close-templates]').forEach((item) => {
    item.addEventListener('click', (event) => {
      if (event.target === item || item.tagName === 'BUTTON') {
        state.templatePickerOpen = false;
        render();
      }
    });
  });

  document.querySelectorAll('[data-select-template]').forEach((button) => {
    button.addEventListener('click', () => {
      const template = state.templates.find((item) => item.id === button.dataset.selectTemplate);
      state.editingTemplateId = null;
      state.draftTemplate = normalizeTemplate({ ...template, id: createId('tpl'), source: '내 템플릿', isDefault: false, createdAt: null, updatedAt: null });
      state.templatePickerOpen = false;
      render();
    });
  });

  document.querySelectorAll('[data-delete-template]').forEach((button) => {
    button.addEventListener('click', () => {
      state.templates = state.templates.filter((template) => template.id !== button.dataset.deleteTemplate);
      if (state.editingTemplateId === button.dataset.deleteTemplate) {
        state.editingTemplateId = null;
        state.draftTemplate = null;
      }
      saveTemplates();
      render();
    });
  });

}

function templateLayer() {
  return `
    <div class="modal-backdrop" data-close-templates>
      <section class="modal-panel template-modal" role="dialog" aria-modal="true" aria-label="템플릿 선택">
        <div class="modal-heading">
          <h2>템플릿</h2>
          <button class="ghost-button compact" type="button" data-close-templates>닫기</button>
        </div>
        <div class="template-grid template-picker-grid">
          ${state.templates.map((template) => `
            <article class="template-card">
              <div>
                <div class="template-path">${escapeHtml(template.source)} &gt; ${escapeHtml(template.title)}</div>
                <h3>${escapeHtml(template.question)}</h3>
                <div class="template-option-list">
                  ${template.options.map((option) => `<span>${escapeHtml(option)}</span>`).join('')}
                </div>
                ${template.isDefault ? '' : `
                  <div class="template-meta">
                    <small>생성 ${escapeHtml(formatHumanTime(template.createdAt))}</small>
                    <small>수정 ${escapeHtml(formatHumanTime(template.updatedAt))}</small>
                  </div>
                `}
              </div>
              <div class="template-actions">
                <button type="button" data-select-template="${template.id}">선택</button>
                ${canDeleteTemplate(template) ? `<button class="danger-action" type="button" data-delete-template="${template.id}">삭제</button>` : ''}
              </div>
            </article>
          `).join('')}
        </div>
      </section>
    </div>
  `;
}

function templateFromForm(form, currentTemplate = null) {
  const data = new FormData(form);
  const question = String(data.get('question')).trim();
  const now = new Date().toISOString();
  return normalizeTemplate({
    id: canDeleteTemplate(currentTemplate) ? currentTemplate.id : createId('tpl'),
    title: question.replace(/[?？]$/, '') || '새 질문',
    question,
    options: String(data.get('options')),
    duration: Number(data.get('duration')),
    source: '내 템플릿',
    isDefault: false,
    createdAt: canDeleteTemplate(currentTemplate) ? currentTemplate.createdAt : now,
    updatedAt: now
  });
}

function isValidTemplate(template) {
  return template.question.length > 0 && template.options.length > 1;
}
