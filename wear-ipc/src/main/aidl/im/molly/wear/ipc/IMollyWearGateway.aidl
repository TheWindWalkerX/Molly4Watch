/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.ipc;

/**
 * Molly's MollyGateway, exposed to the relay app. Only apps holding the signature-level
 * im.molly.wear.permission.BRIDGE permission (same signing key as Molly) can bind.
 * Structured values travel as JSON from WearIpc; outcomes are MollyGateway.Outcome ordinals.
 */
interface IMollyWearGateway {
    int ipcVersion();
    boolean isLocked();
    /** A Snapshot. Molly returns a locked snapshot itself if it is locked. */
    byte[] loadSnapshot(long now);
    /** A HistoryPage, or null if the thread doesn't exist (or Molly is locked). */
    byte[] loadHistory(long threadId, int offset, int limit);
    /** quoteMessageId: send as a quoted reply to that message; 0 = a plain message. */
    int sendText(long threadId, String body, long quoteMessageId);
    int react(long threadId, long messageId, String emoji);
    int removeReaction(long threadId, long messageId);
    int markRead(long threadId);
}
