import { state } from '../core/store.js';
import { auditGossipAgainstBlock, isReceiptIncluded } from '../core/protocol.js';
import { emptyState, escapeHtml } from '../core/view.js';

export function resultScreen() {
  const block = state.resultBlock || state.ledgerHistory[0];
  if (!block) return `<section class="panel">${emptyState('아직 결과 블록이 없습니다.')}</section>`;
  const participantIds = block.participantIds || block.replicatedTo || [];
  const includedVoterIds = block.includedVoterIds || block.includedVoters || [];
  const participantCount = block.participantCount || participantIds.length || block.voteCount || 0;
  const myReceipt = state.receipts.find((receipt) => receipt.pollId === block.pollId && receipt.voterId === 'proposer');
  const receiptIncluded = isReceiptIncluded(myReceipt, block);
  const gossipAudit = auditGossipAgainstBlock(state.gossipMessages, block);

  return `
    <section class="panel result-panel">
      <div class="panel-heading">
        <h2>결과 원장</h2>
        <span class="verify-icon ${block.verified ? 'ok' : 'fail'}" title="${block.verified ? '검증 완료' : '검증 실패'}" aria-label="${block.verified ? '검증 완료' : '검증 실패'}">${block.verified ? '✓' : '!'}</span>
      </div>
      <div class="result-summary">
        <article>
          <span>참여자</span>
          <strong>${participantCount}</strong>
        </article>
        <article>
          <span>투표</span>
          <strong>${block.voteCount}</strong>
        </article>
        <article>
          <span>제안자</span>
          <strong>${escapeHtml(block.proposerDisplayId || block.proposerId)}</strong>
        </article>
      </div>
      ${myReceipt ? `
        <div class="receipt-card result-receipt">
          <span>내 투표 접수증</span>
          <strong>${receiptIncluded ? '최종 블록 포함' : '최종 블록 미포함'}</strong>
          <small>${escapeHtml(myReceipt.voteHash.slice(0, 24))}</small>
        </div>
      ` : ''}
      <div class="receipt-card result-receipt ${gossipAudit.ok ? '' : 'warning'}">
        <span>투표 해시 공유 감사</span>
        <strong>${gossipAudit.ok ? '누락 없음' : '누락 감지'}</strong>
        <small>관찰 ${gossipAudit.observedHashCount}개 · 누락 ${gossipAudit.missingHashes.length}개</small>
      </div>
      <div class="result-grid">
        ${Object.entries(block.result).map(([option, count]) => `
          <article class="result-row">
            <strong>${escapeHtml(option)}</strong>
            <meter min="0" max="${Math.max(1, participantCount)}" value="${count}"></meter>
            <span>${count}명 (${percent(count, participantCount)}%)</span>
          </article>
        `).join('')}
      </div>
      <pre>${escapeHtml(JSON.stringify({
        pollId: block.pollId,
        votesRoot: block.votesRoot.slice(0, 24),
        blockHash: block.blockHash.slice(0, 24),
        participantIds,
        includedVoterIds,
        gossipAudit
      }, null, 2))}</pre>
    </section>
  `;
}

export function bindResult() {}

function percent(count, total) {
  if (!total) return 0;
  return Math.round((count / total) * 100);
}
