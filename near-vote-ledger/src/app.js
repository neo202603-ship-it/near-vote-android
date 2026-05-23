const participants = [
  { id: 'proposer', name: '제안자', role: 'miner', joined: true, voted: false },
  { id: 'device-a', name: '근처 사용자 A', role: 'participant', joined: false, voted: false },
  { id: 'device-b', name: '근처 사용자 B', role: 'participant', joined: false, voted: false },
  { id: 'device-c', name: '근처 사용자 C', role: 'participant', joined: false, voted: false },
  { id: 'device-d', name: '근처 사용자 D', role: 'participant', joined: false, voted: false }
];

const TEMPLATE_STORAGE_KEY = 'near-vote-ledger.templates.v1';
const defaultTemplates = [
  {
    id: 'tpl_lunch',
    title: '점심 메뉴',
    question: '점심메뉴는?',
    options: ['한식', '일식', '중식', '분식', '샐러드'],
    duration: 60,
    source: '기본'
  },
  {
    id: 'tpl_dinner',
    title: '오늘 회식',
    question: '오늘 회식은?',
    options: ['진행', '다음으로 연기', '가볍게 커피', '점심 회식으로 변경'],
    duration: 60,
    source: '기본'
  },
  {
    id: 'tpl_agenda',
    title: '회의 안건',
    question: '오늘 회의에서 먼저 결정할 안건은?',
    options: ['예산안', '일정', '역할 분담', '다음 회의'],
    duration: 60,
    source: '기본'
  },
  {
    id: 'tpl_snack',
    title: '간식 선택',
    question: '지금 간식으로 뭐가 좋을까요?',
    options: ['커피', '빵', '과일', '아이스크림'],
    duration: 30,
    source: '기본'
  }
];

const state = {
  poll: null,
  votes: [],
  ledger: [],
  keys: new Map(),
  timerId: null,
  isFinalizing: false,
  templates: []
};

const els = {
  form: document.querySelector('#pollForm'),
  questionInput: document.querySelector('#questionInput'),
  optionsInput: document.querySelector('#optionsInput'),
  durationInput: document.querySelector('#durationInput'),
  saveTemplateButton: document.querySelector('#saveTemplateButton'),
  templateList: document.querySelector('#templateList'),
  templateCount: document.querySelector('#templateCount'),
  inviteButton: document.querySelector('#inviteButton'),
  finalizeButton: document.querySelector('#finalizeButton'),
  participantList: document.querySelector('#participantList'),
  pollView: document.querySelector('#pollView'),
  ledgerView: document.querySelector('#ledgerView'),
  timerLabel: document.querySelector('#timerLabel'),
  networkStatus: document.querySelector('#networkStatus')
};

const encoder = new TextEncoder();

function stableStringify(value) {
  if (Array.isArray(value)) {
    return `[${value.map(stableStringify).join(',')}]`;
  }
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

async function digest(value) {
  const buffer = await crypto.subtle.digest('SHA-256', encoder.encode(stableStringify(value)));
  return [...new Uint8Array(buffer)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

async function createKeyPair() {
  return crypto.subtle.generateKey(
    { name: 'ECDSA', namedCurve: 'P-256' },
    true,
    ['sign', 'verify']
  );
}

async function exportPublicKey(key) {
  const raw = await crypto.subtle.exportKey('raw', key);
  return btoa(String.fromCharCode(...new Uint8Array(raw)));
}

async function signPayload(privateKey, payload) {
  const signature = await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' },
    privateKey,
    encoder.encode(stableStringify(payload))
  );
  return btoa(String.fromCharCode(...new Uint8Array(signature)));
}

function nowIso() {
  return new Date().toISOString();
}

function createId(prefix) {
  return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function parseOptions(value) {
  return String(value)
    .split(',')
    .map((option) => option.trim())
    .filter(Boolean);
}

function normalizeTemplate(template) {
  const options = Array.isArray(template.options) ? template.options : parseOptions(template.options);
  return {
    id: template.id || createId('tpl'),
    title: String(template.title || template.question || '새 질문').trim(),
    question: String(template.question || '').trim(),
    options: options.length > 1 ? options : ['찬성', '반대'],
    duration: Number(template.duration || 60),
    source: template.source || '내 템플릿'
  };
}

function loadTemplates() {
  const saved = localStorage.getItem(TEMPLATE_STORAGE_KEY);
  if (!saved) {
    state.templates = defaultTemplates.map(normalizeTemplate);
    saveTemplates();
    return;
  }

  try {
    const parsed = JSON.parse(saved);
    state.templates = parsed.map(normalizeTemplate);
  } catch {
    state.templates = defaultTemplates.map(normalizeTemplate);
    saveTemplates();
  }
}

function saveTemplates() {
  localStorage.setItem(TEMPLATE_STORAGE_KEY, JSON.stringify(state.templates));
}

function readTemplateFromForm() {
  const question = els.questionInput.value.trim();
  const options = parseOptions(els.optionsInput.value);
  return normalizeTemplate({
    id: createId('tpl'),
    title: question.replace(/[?？]$/, '') || '새 질문',
    question,
    options,
    duration: Number(els.durationInput.value),
    source: '내 템플릿'
  });
}

function applyTemplate(template) {
  els.questionInput.value = template.question;
  els.optionsInput.value = template.options.join(', ');
  els.durationInput.value = String(template.duration);
  els.networkStatus.textContent = '템플릿 수정 중';
}

function saveCurrentTemplate() {
  const template = readTemplateFromForm();
  state.templates = [template, ...state.templates];
  saveTemplates();
  els.networkStatus.textContent = '템플릿 저장';
  renderTemplates();
}

function copyTemplate(templateId) {
  const template = state.templates.find((item) => item.id === templateId);
  if (!template) return;

  const copy = normalizeTemplate({
    ...template,
    id: createId('tpl'),
    title: `${template.title} 복사본`,
    source: '복사'
  });
  state.templates = [copy, ...state.templates];
  saveTemplates();
  applyTemplate(copy);
  els.networkStatus.textContent = '복사본 편집 중';
  renderTemplates();
}

function publishTemplate(templateId) {
  const template = state.templates.find((item) => item.id === templateId);
  if (!template) return;
  applyTemplate(template);
  els.form.requestSubmit();
}

function handleTemplateAction(event) {
  const button = event.target.closest('button[data-template-action]');
  if (!button) return;

  const templateId = button.dataset.templateId;
  const action = button.dataset.templateAction;
  const template = state.templates.find((item) => item.id === templateId);
  if (!template) return;

  if (action === 'edit') applyTemplate(template);
  if (action === 'copy') copyTemplate(templateId);
  if (action === 'publish') publishTemplate(templateId);
}

function resetParticipants() {
  participants.forEach((participant) => {
    participant.joined = participant.id === 'proposer';
    participant.voted = false;
  });
}

async function ensureKeys() {
  for (const participant of participants) {
    if (!state.keys.has(participant.id)) {
      const pair = await createKeyPair();
      state.keys.set(participant.id, {
        pair,
        publicKey: await exportPublicKey(pair.publicKey)
      });
    }
  }
}

async function startPoll(event) {
  event.preventDefault();
  await ensureKeys();
  clearInterval(state.timerId);

  const form = new FormData(els.form);
  const options = parseOptions(form.get('options'));

  const durationSeconds = Number(form.get('duration'));
  const proposerKey = state.keys.get('proposer');

  state.poll = {
    pollId: `poll_${Date.now().toString(36)}`,
    question: String(form.get('question')).trim(),
    options: options.length > 1 ? options : ['찬성', '반대'],
    deadline: new Date(Date.now() + durationSeconds * 1000).toISOString(),
    proposerId: 'proposer',
    proposerPublicKey: proposerKey.publicKey
  };
  state.votes = [];
  state.ledger = [];
  state.isFinalizing = false;
  resetParticipants();

  els.networkStatus.textContent = '초대 준비';
  render();
  tickTimer();
  state.timerId = setInterval(tickTimer, 500);
}

function tickTimer() {
  if (!state.poll) {
    els.timerLabel.textContent = '--';
    return;
  }

  const remaining = Math.max(0, Math.ceil((new Date(state.poll.deadline).getTime() - Date.now()) / 1000));
  els.timerLabel.textContent = `${remaining}s`;
  if (remaining === 0) {
    els.networkStatus.textContent = '집계 가능';
    clearInterval(state.timerId);
    finalizePoll();
  }
}

async function broadcastInvite() {
  if (!state.poll) return;
  els.networkStatus.textContent = '초대 전파 중';

  for (const participant of participants.filter((item) => item.role === 'participant')) {
    if (Math.random() > 0.18) {
      participant.joined = true;
    }
  }

  await autoCastVotes();
  els.networkStatus.textContent = '투표 수집 중';
  render();
}

async function autoCastVotes() {
  const joined = participants.filter((participant) => participant.role === 'participant' && participant.joined && !participant.voted);
  for (const participant of joined) {
    await castVote(participant.id);
  }
}

async function castVote(participantId, selectedChoice = null) {
  if (!state.poll) return;
  const participant = participants.find((item) => item.id === participantId);
  const key = state.keys.get(participantId);
  const choice = selectedChoice || state.poll.options[Math.floor(Math.random() * state.poll.options.length)];
  const unsignedVote = {
    pollId: state.poll.pollId,
    voterId: participantId,
    voterPublicKey: key.publicKey,
    choice,
    createdAt: nowIso()
  };
  const vote = {
    ...unsignedVote,
    voteHash: await digest(unsignedVote),
    signature: await signPayload(key.pair.privateKey, unsignedVote)
  };

  participant.voted = true;
  state.votes = state.votes.filter((item) => item.voterId !== participantId).concat(vote);
}

async function finalizePoll() {
  if (!state.poll || state.ledger.length > 0 || state.isFinalizing) return;

  const remaining = new Date(state.poll.deadline).getTime() - Date.now();
  if (remaining > 0) {
    els.networkStatus.textContent = '제한 시간 진행 중';
    return;
  }

  state.isFinalizing = true;
  const counts = Object.fromEntries(state.poll.options.map((option) => [option, 0]));
  for (const vote of state.votes) {
    counts[vote.choice] = (counts[vote.choice] ?? 0) + 1;
  }

  const blockBody = {
    index: 1,
    pollId: state.poll.pollId,
    previousHash: 'GENESIS',
    createdAt: nowIso(),
    proposerId: 'proposer',
    votesRoot: await digest(state.votes.map((vote) => vote.voteHash)),
    result: counts,
    voteCount: state.votes.length
  };
  const blockHash = await digest(blockBody);
  const proposerKey = state.keys.get('proposer');
  const block = {
    ...blockBody,
    blockHash,
    proposerSignature: await signPayload(proposerKey.pair.privateKey, blockBody),
    replicatedTo: participants.filter((participant) => participant.joined).map((participant) => participant.id),
    includedVoters: state.votes.map((vote) => vote.voterId),
    verified: blockHash === await digest(blockBody)
  };

  state.ledger = [block];
  state.isFinalizing = false;
  els.networkStatus.textContent = '원장 공유 완료';
  render();
}

async function handleProposerVote(event) {
  const button = event.target.closest('button[data-proposer-choice]');
  if (!button) return;

  const proposer = participants.find((participant) => participant.id === 'proposer');
  if (!state.poll || proposer.voted || state.ledger.length > 0) return;

  await castVote('proposer', button.dataset.proposerChoice);
  els.networkStatus.textContent = '제안자 투표 서명';
  render();
}

function renderParticipants() {
  els.participantList.innerHTML = participants.map((participant) => {
    const status = participant.joined ? (participant.voted ? '투표 서명' : '참여') : '대기';
    return `
      <div class="participant-row">
        <div>
          <strong>${participant.name}</strong>
          <span>${participant.role === 'miner' ? '블록 생성자' : '참여자'}</span>
        </div>
        <small class="${participant.joined ? 'ok' : ''}">${status}</small>
      </div>
    `;
  }).join('');
}

function renderPoll() {
  if (!state.poll) {
    els.pollView.className = 'poll-view empty-state';
    els.pollView.textContent = '설문을 시작하면 주변 참여자에게 초대가 전파됩니다.';
    return;
  }

  els.pollView.className = 'poll-view';
  const proposer = participants.find((participant) => participant.id === 'proposer');
  const canProposerVote = proposer.joined && !proposer.voted && state.ledger.length === 0;
  els.pollView.innerHTML = `
    <h3>${escapeHtml(state.poll.question)}</h3>
    ${canProposerVote ? `
      <div class="proposer-vote">
        ${state.poll.options.map((option) => `
          <button type="button" data-proposer-choice="${escapeHtml(option)}">${escapeHtml(option)}</button>
        `).join('')}
      </div>
    ` : ''}
    <div class="option-stack">
      ${state.poll.options.map((option) => {
        const count = state.votes.filter((vote) => vote.choice === option).length;
        return `
          <div class="option-row">
            <span>${escapeHtml(option)}</span>
            <meter min="0" max="${Math.max(1, state.votes.length)}" value="${count}"></meter>
            <strong>${count}</strong>
          </div>
        `;
      }).join('')}
    </div>
    <p class="hash-line">Poll ID ${state.poll.pollId}</p>
  `;
}

function renderLedger() {
  if (!state.ledger.length) {
    els.ledgerView.innerHTML = `
      <div class="empty-state">
        아직 생성된 블록이 없습니다.
      </div>
    `;
    return;
  }

  const block = state.ledger[0];
  els.ledgerView.innerHTML = `
    <div class="block-view">
      <div class="block-metric">
        <span>Block</span>
        <strong>#${block.index}</strong>
      </div>
      <div class="block-metric">
        <span>Votes</span>
        <strong>${block.voteCount}</strong>
      </div>
      <div class="block-metric">
        <span>Replicas</span>
        <strong>${block.replicatedTo.length}</strong>
      </div>
      <div class="block-metric verified">
        <span>Verify</span>
        <strong>${block.verified ? 'OK' : 'FAIL'}</strong>
      </div>
      <pre>${JSON.stringify({
        pollId: block.pollId,
        previousHash: block.previousHash,
        votesRoot: block.votesRoot.slice(0, 24),
        blockHash: block.blockHash.slice(0, 24),
        includedVoters: block.includedVoters,
        result: block.result
      }, null, 2)}</pre>
    </div>
  `;
}

function renderTemplates() {
  els.templateCount.textContent = `${state.templates.length}개`;
  els.templateList.innerHTML = state.templates.map((template) => `
    <article class="template-card">
      <div class="template-copy">
        <span>${escapeHtml(template.source)}</span>
        <h3>${escapeHtml(template.title)}</h3>
        <p>${escapeHtml(template.question)}</p>
        <small>${escapeHtml(template.options.join(' · '))}</small>
      </div>
      <div class="template-actions">
        <button type="button" data-template-action="edit" data-template-id="${template.id}">수정</button>
        <button type="button" data-template-action="copy" data-template-id="${template.id}">복사</button>
        <button type="button" data-template-action="publish" data-template-id="${template.id}">게시</button>
      </div>
    </article>
  `).join('');
}

function render() {
  renderTemplates();
  renderParticipants();
  renderPoll();
  renderLedger();
}

els.form.addEventListener('submit', startPoll);
els.saveTemplateButton.addEventListener('click', saveCurrentTemplate);
els.templateList.addEventListener('click', handleTemplateAction);
els.pollView.addEventListener('click', handleProposerVote);
els.inviteButton.addEventListener('click', broadcastInvite);
els.finalizeButton.addEventListener('click', finalizePoll);

loadTemplates();
ensureKeys().then(render);
