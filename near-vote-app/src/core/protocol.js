import { digest, signPayload } from './crypto.js';

export function createPoll({ id, template, proposer, publicKey, now = new Date() }) {
  return {
    pollId: id,
    question: template.question,
    options: template.options,
    deadline: new Date(now.getTime() + Number(template.duration) * 1000).toISOString(),
    proposerId: proposer.id,
    proposerDisplayId: proposer.displayId,
    proposerPublicKey: publicKey
  };
}

export async function createSignedVote({ poll, participant, key, choice, now = new Date() }) {
  const selectedChoice = choice || poll.options[Math.floor(Math.random() * poll.options.length)];
  const unsignedVote = {
    pollId: poll.pollId,
    voterId: participant.id,
    voterDisplayId: participant.displayId,
    voterPublicKey: key.publicKey,
    choice: selectedChoice,
    createdAt: now.toISOString()
  };

  return {
    ...unsignedVote,
    voteHash: await digest(unsignedVote),
    signature: await signPayload(key.pair.privateKey, unsignedVote)
  };
}

export function createVoteReceipt({ vote, receivedBy, now = new Date() }) {
  return {
    receiptId: `receipt_${vote.voteHash.slice(0, 16)}`,
    pollId: vote.pollId,
    voterId: vote.voterId,
    voterDisplayId: vote.voterDisplayId,
    choice: vote.choice,
    voteHash: vote.voteHash,
    receivedBy,
    receivedAt: now.toISOString()
  };
}

export function createVoteHashGossip({ poll, observer, votes, now = new Date() }) {
  return {
    gossipId: `gossip_${observer.id}_${now.getTime().toString(36)}`,
    pollId: poll.pollId,
    observerId: observer.id,
    observerDisplayId: observer.displayId,
    observedVoteHashes: votes.map((vote) => vote.voteHash),
    observedAt: now.toISOString()
  };
}

export async function createResultBlock({ poll, votes, participants, proposer, proposerKey, now = new Date() }) {
  const joinedParticipants = participants.filter((participant) => participant.joined);
  const result = Object.fromEntries(poll.options.map((option) => [option, 0]));

  for (const vote of votes) {
    result[vote.choice] = (result[vote.choice] || 0) + 1;
  }

  const blockBody = {
    index: 1,
    pollId: poll.pollId,
    question: poll.question,
    previousHash: 'GENESIS',
    createdAt: now.toISOString(),
    proposerId: proposer.id,
    proposerDisplayId: proposer.displayId,
    votesRoot: await digest(votes.map((vote) => vote.voteHash)),
    result,
    voteCount: votes.length,
    participantCount: joinedParticipants.length,
    participantIds: joinedParticipants.map((participant) => participant.displayId)
  };
  const blockHash = await digest(blockBody);

  return {
    ...blockBody,
    blockHash,
    proposerSignature: await signPayload(proposerKey.pair.privateKey, blockBody),
    replicatedTo: joinedParticipants.map((participant) => participant.id),
    includedVoters: votes.map((vote) => vote.voterId),
    includedVoterIds: votes.map((vote) => vote.voterDisplayId),
    includedVoteHashes: votes.map((vote) => vote.voteHash),
    verified: await verifyResultBlock(blockBody, blockHash)
  };
}

export async function verifyResultBlock(blockBody, blockHash) {
  return blockHash === await digest(blockBody);
}

export function hasPollExpired(poll, now = new Date()) {
  return new Date(poll.deadline).getTime() <= now.getTime();
}

export function isReceiptIncluded(receipt, block) {
  if (!receipt || !block) return false;
  return block.includedVoters.includes(receipt.voterId);
}

export function auditGossipAgainstBlock(gossipMessages, block) {
  if (!block) {
    return {
      observedHashCount: 0,
      missingHashes: [],
      ok: false
    };
  }

  const observedHashes = [...new Set(gossipMessages.flatMap((message) => message.observedVoteHashes))];
  const missingHashes = observedHashes.filter((hash) => !block.includedVoteHashes?.includes(hash));

  return {
    observedHashCount: observedHashes.length,
    missingHashes,
    ok: missingHashes.length === 0
  };
}
