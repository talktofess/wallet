package com.wallet.core.chess;

import java.util.ArrayList;
import java.util.List;

/**
 * A small, dependency-free chess engine — enough for a believable, playable
 * facade (legal piece movement, turns, capture; check-unaware). It is NOT a full
 * rules engine (no castling / en passant / checkmate detection); it only has to
 * look and feel like a real game to anyone who opens the app.
 *
 * <p>It is also the <b>secret door</b>: a specific opening sequence of moves is
 * the vault's unlock secret. This engine only reports moves; the move-sequence →
 * key mapping lives in {@link MoveKey}, and deciding when a played sequence
 * unlocks the vault lives in the UI.
 *
 * <p>Ported, behaviour-for-behaviour, from the TypeScript {@code src/chess/engine.ts}
 * of the sibling "vault" app so the two disguises play identically.
 */
public final class ChessEngine {

    public enum Color { W, B }

    public enum Type { P, N, B, R, Q, K }

    public static final class Piece {
        public final Type type;
        public final Color color;

        public Piece(Type type, Color color) {
            this.type = type;
            this.color = color;
        }
    }

    /** A board coordinate. {@code r} is the row 0..7 from the top (black), {@code c} the column 0..7. */
    public static final class Pos {
        public final int r;
        public final int c;

        public Pos(int r, int c) {
            this.r = r;
            this.c = c;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Pos)) return false;
            Pos p = (Pos) o;
            return r == p.r && c == p.c;
        }

        @Override public int hashCode() { return r * 8 + c; }
    }

    private ChessEngine() {}

    /** [row 0..7 from top (black)][col 0..7]; {@code null} = empty square. */
    public static Piece[][] initialBoard() {
        Type[] back = { Type.R, Type.N, Type.B, Type.Q, Type.K, Type.B, Type.N, Type.R };
        Piece[][] board = new Piece[8][8];
        for (int c = 0; c < 8; c++) {
            board[0][c] = new Piece(back[c], Color.B);
            board[1][c] = new Piece(Type.P, Color.B);
            board[6][c] = new Piece(Type.P, Color.W);
            board[7][c] = new Piece(back[c], Color.W);
        }
        return board;
    }

    private static boolean inBounds(int r, int c) {
        return r >= 0 && r < 8 && c >= 0 && c < 8;
    }

    private static void ray(Piece[][] board, Pos p, int[][] dirs, Color color, List<Pos> out) {
        for (int[] d : dirs) {
            int r = p.r + d[0];
            int c = p.c + d[1];
            while (inBounds(r, c)) {
                Piece sq = board[r][c];
                if (sq == null) {
                    out.add(new Pos(r, c));
                } else {
                    if (sq.color != color) out.add(new Pos(r, c));
                    break;
                }
                r += d[0];
                c += d[1];
            }
        }
    }

    /** Pseudo-legal moves for the piece at {@code p} (ignores check — fine for a facade). */
    public static List<Pos> legalMoves(Piece[][] board, Pos p) {
        List<Pos> out = new ArrayList<>();
        Piece piece = board[p.r][p.c];
        if (piece == null) return out;
        Color color = piece.color;
        int fwd = color == Color.W ? -1 : 1;

        switch (piece.type) {
            case P: {
                if (inBounds(p.r + fwd, p.c) && board[p.r + fwd][p.c] == null) {
                    out.add(new Pos(p.r + fwd, p.c));
                    int startRow = color == Color.W ? 6 : 1;
                    if (p.r == startRow && board[p.r + 2 * fwd][p.c] == null) {
                        out.add(new Pos(p.r + 2 * fwd, p.c));
                    }
                }
                for (int dc : new int[] { -1, 1 }) {
                    int r = p.r + fwd, c = p.c + dc;
                    if (inBounds(r, c) && board[r][c] != null && board[r][c].color != color) {
                        out.add(new Pos(r, c));
                    }
                }
                break;
            }
            case N:
                for (int[] d : new int[][] {
                        { -2, -1 }, { -2, 1 }, { -1, -2 }, { -1, 2 }, { 1, -2 }, { 1, 2 }, { 2, -1 }, { 2, 1 } }) {
                    push(board, p.r + d[0], p.c + d[1], color, out);
                }
                break;
            case B:
                ray(board, p, new int[][] { { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 } }, color, out);
                break;
            case R:
                ray(board, p, new int[][] { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } }, color, out);
                break;
            case Q:
                ray(board, p, new int[][] {
                        { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 }, { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } }, color, out);
                break;
            case K:
                for (int[] d : new int[][] {
                        { -1, -1 }, { -1, 0 }, { -1, 1 }, { 0, -1 }, { 0, 1 }, { 1, -1 }, { 1, 0 }, { 1, 1 } }) {
                    push(board, p.r + d[0], p.c + d[1], color, out);
                }
                break;
        }
        return out;
    }

    private static void push(Piece[][] board, int r, int c, Color color, List<Pos> out) {
        if (inBounds(r, c)) {
            Piece sq = board[r][c];
            if (sq == null || sq.color != color) out.add(new Pos(r, c));
        }
    }

    /** Apply a move, returning a new board (the input is not mutated). Auto-queens on promotion. */
    public static Piece[][] move(Piece[][] board, Pos from, Pos to) {
        Piece[][] next = new Piece[8][8];
        for (int r = 0; r < 8; r++) System.arraycopy(board[r], 0, next[r], 0, 8);
        Piece piece = next[from.r][from.c];
        next[to.r][to.c] = piece;
        next[from.r][from.c] = null;
        if (piece != null && piece.type == Type.P && (to.r == 0 || to.r == 7)) {
            next[to.r][to.c] = new Piece(Type.Q, piece.color);   // facade nicety
        }
        return next;
    }

    /** Algebraic name of a square, e.g. {@code (r=6,c=4)} -> {@code "e2"}. */
    public static String squareName(Pos p) {
        return "" + "abcdefgh".charAt(p.c) + (8 - p.r);
    }

    /** The white/black Unicode glyph for a piece, for the UI to render. */
    public static String glyph(Piece piece) {
        switch (piece.type) {
            case P: return piece.color == Color.W ? "♙" : "♟";
            case N: return piece.color == Color.W ? "♘" : "♞";
            case B: return piece.color == Color.W ? "♗" : "♝";
            case R: return piece.color == Color.W ? "♖" : "♜";
            case Q: return piece.color == Color.W ? "♕" : "♛";
            case K: return piece.color == Color.W ? "♔" : "♚";
            default: return "?";
        }
    }
}
