package com.wallet.core.chess;

import java.util.List;

/**
 * The unlock secret derived from a played sequence of chess moves (the user's
 * "variation"). Each move is {@code "<from><to>"} in algebraic squares, e.g.
 * {@code "e2e4"}. The canonical secret is those moves joined and
 * domain-separated; it is fed to the same PBKDF2 key derivation a passphrase
 * would use, so nothing in the crypto changes — only the input does.
 *
 * <p>Promotions / castling notation aren't needed: from+to squares uniquely
 * identify each move on the disguise board. Kept byte-for-byte compatible with
 * the sibling vault app's {@code src/chess/movekey.ts} so a vault created on one
 * platform's secret would derive the same key on the other.
 */
public final class MoveKey {

    private MoveKey() {}

    public static String movesToSecret(List<String> moves) {
        StringBuilder sb = new StringBuilder("chesskey-v1|");
        for (int i = 0; i < moves.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(moves.get(i).toLowerCase());
        }
        return sb.toString();
    }
}
