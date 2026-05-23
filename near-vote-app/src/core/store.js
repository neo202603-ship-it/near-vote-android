import { createKeyPair, digest, exportPublicKey, signPayload } from './crypto.js';

const TEMPLATE_KEY = 'near-vote-app.templates.v1';
const LEDGER_KEY = 'near-vote-app.ledgers.v1';
const USER_ID_KEY = 'near-vote-app.user-id.v1';

export const participants = [
  { id: 'proposer', name: '제안자', displayId: '', role: 'miner', joined: true, voted: false },
  { id: 'device-a', name: '근처 사용자 A', displayId: '고요한연필', role: 'participant', joined: false, voted: false },
  { id: 'device-b', name: '근처 사용자 B', displayId: '빠른가방', role: 'participant', joined: false, voted: false },
  { id: 'device-c', name: '근처 사용자 C', displayId: '따뜻한머그컵', role: 'participant', joined: false, voted: false }
];

const adjectives = ['고요한', '빠른', '따뜻한', '명랑한', '선명한', '단단한', '푸른', '차분한', '용감한', '반짝이는'];
const objects = ['연필', '가방', '머그컵', '시계', '노트', '전등', '열쇠', '의자', '지도', '라디오'];

export const defaultTemplates = [
  { id: 'tpl_lunch', title: '점심 메뉴', question: '점심메뉴는?', options: ['한식', '일식', '중식', '분식'], duration: 60, source: '기본' },
  { id: 'tpl_dinner', title: '오늘 회식', question: '오늘 회식은?', options: ['진행', '연기', '커피로 대체', '점심 회식'], duration: 60, source: '기본' },
  { id: 'tpl_agenda', title: '회의 안건', question: '오늘 회의에서 먼저 결정할 안건은?', options: ['예산안', '일정', '역할 분담', '다음 회의'], duration: 60, source: '기본' },
  { id: 'tpl_snack', title: '간식 선택', question: '지금 간식으로 뭐가 좋을까요?', options: ['커피', '빵', '과일', '아이스크림'], duration: 30, source: '기본' }
];

export const state = {
  route: 'home',
  status: '근거리 대기',
  toast: null,
  userId: '',
  hasUserId: false,
  templates: [],
  editingTemplateId: null,
  draftTemplate: null,
  templatePickerOpen: false,
  ledgerHistory: [],
  activePoll: null,
  votes: [],
  resultBlock: null,
  keys: new Map(),
  timerId: null
};

export function createId(prefix) {
  return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
}

export function notify(message) {
  state.toast = {
    id: createId('toast'),
    message
  };
}

export function suggestUserId() {
  const adjective = adjectives[Math.floor(Math.random() * adjectives.length)];
  const object = objects[Math.floor(Math.random() * objects.length)];
  return `${adjective}${object}`;
}

export function setUserId(userId) {
  state.userId = String(userId || '').trim() || suggestUserId();
  state.hasUserId = true;
  localStorage.setItem(USER_ID_KEY, state.userId);
  syncProposerIdentity();
}

export function syncProposerIdentity() {
  const proposer = participants.find((participant) => participant.id === 'proposer');
  proposer.displayId = state.userId;
}

export function parseOptions(value) {
  return String(value)
    .split(',')
    .map((option) => option.trim())
    .filter(Boolean);
}

export function normalizeTemplate(template) {
  const options = Array.isArray(template.options) ? template.options : parseOptions(template.options);
  const source = template.source || '내 템플릿';
  const isDefault = template.isDefault ?? source === '기본';
  const timestamp = new Date().toISOString();
  return {
    id: template.id || createId('tpl'),
    title: String(template.title || template.question || '새 질문').trim(),
    question: String(template.question || '').trim(),
    options: options.length > 1 ? options : ['찬성', '반대'],
    duration: Number(template.duration || 60),
    source,
    isDefault,
    createdAt: template.createdAt || timestamp,
    updatedAt: template.updatedAt || template.createdAt || timestamp
  };
}

export function canDeleteTemplate(template) {
  return Boolean(template && !template.isDefault);
}

export function loadPersistentState() {
  const savedUserId = localStorage.getItem(USER_ID_KEY);
  state.hasUserId = Boolean(savedUserId);
  state.userId = savedUserId || suggestUserId();
  syncProposerIdentity();
  state.templates = loadJson(TEMPLATE_KEY, defaultTemplates).map(normalizeTemplate);
  state.ledgerHistory = loadJson(LEDGER_KEY, []);
  saveTemplates();
}

export function saveTemplates() {
  localStorage.setItem(TEMPLATE_KEY, JSON.stringify(state.templates));
}

export function saveLedgerHistory() {
  localStorage.setItem(LEDGER_KEY, JSON.stringify(state.ledgerHistory));
}

function loadJson(key, fallback) {
  try {
    return JSON.parse(localStorage.getItem(key)) || fallback;
  } catch {
    return fallback;
  }
}

export async function ensureKeys() {
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

export function resetParticipants() {
  participants.forEach((participant) => {
    participant.joined = participant.id === 'proposer';
    participant.voted = false;
  });
}

export async function publishPoll(template) {
  await ensureKeys();
  resetParticipants();
  clearInterval(state.timerId);

  const proposerKey = state.keys.get('proposer');
  state.activePoll = {
    pollId: createId('poll'),
    question: template.question,
    options: template.options,
    deadline: new Date(Date.now() + Number(template.duration) * 1000).toISOString(),
    proposerId: 'proposer',
    proposerDisplayId: state.userId,
    proposerPublicKey: proposerKey.publicKey
  };
  state.votes = [];
  state.resultBlock = null;
  notify('근거리 게시 완료');
}

export async function inviteNearbyParticipants() {
  for (const participant of participants.filter((item) => item.role === 'participant')) {
    participant.joined = true;
    await castVote(participant.id);
  }
  notify('주변 투표 수집 중');
}

export async function castVote(participantId, selectedChoice = null) {
  if (!state.activePoll) return;
  const participant = participants.find((item) => item.id === participantId);
  const key = state.keys.get(participantId);
  const choice = selectedChoice || state.activePoll.options[Math.floor(Math.random() * state.activePoll.options.length)];
  const unsignedVote = {
    pollId: state.activePoll.pollId,
    voterId: participantId,
    voterDisplayId: participant.displayId,
    voterPublicKey: key.publicKey,
    choice,
    createdAt: new Date().toISOString()
  };
  const vote = {
    ...unsignedVote,
    voteHash: await digest(unsignedVote),
    signature: await signPayload(key.pair.privateKey, unsignedVote)
  };

  participant.voted = true;
  state.votes = state.votes.filter((item) => item.voterId !== participantId).concat(vote);
}

export async function finalizePoll() {
  if (!state.activePoll || state.resultBlock) return state.resultBlock;
  if (new Date(state.activePoll.deadline).getTime() > Date.now()) {
    notify('제한 시간 진행 중');
    return null;
  }

  const counts = Object.fromEntries(state.activePoll.options.map((option) => [option, 0]));
  for (const vote of state.votes) {
    counts[vote.choice] = (counts[vote.choice] || 0) + 1;
  }

  const blockBody = {
    index: 1,
    pollId: state.activePoll.pollId,
    question: state.activePoll.question,
    previousHash: 'GENESIS',
    createdAt: new Date().toISOString(),
    proposerId: 'proposer',
    proposerDisplayId: state.userId,
    votesRoot: await digest(state.votes.map((vote) => vote.voteHash)),
    result: counts,
    voteCount: state.votes.length,
    participantCount: participants.filter((participant) => participant.joined).length,
    participantIds: participants.filter((participant) => participant.joined).map((participant) => participant.displayId)
  };
  const blockHash = await digest(blockBody);
  const proposerKey = state.keys.get('proposer');
  state.resultBlock = {
    ...blockBody,
    blockHash,
    proposerSignature: await signPayload(proposerKey.pair.privateKey, blockBody),
    replicatedTo: participants.filter((participant) => participant.joined).map((participant) => participant.id),
    includedVoters: state.votes.map((vote) => vote.voterId),
    includedVoterIds: state.votes.map((vote) => vote.voterDisplayId),
    verified: blockHash === await digest(blockBody)
  };
  state.ledgerHistory = [state.resultBlock, ...state.ledgerHistory].slice(0, 20);
  saveLedgerHistory();
  notify('결과 원장 공유 완료');
  return state.resultBlock;
}
