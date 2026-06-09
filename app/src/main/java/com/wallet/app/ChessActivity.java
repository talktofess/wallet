package com.wallet.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import com.wallet.core.chess.ChessEngine;
import com.wallet.core.chess.ChessEngine.Piece;
import com.wallet.core.chess.ChessEngine.Pos;
import com.wallet.core.chess.MoveKey;

/**
 * The disguise <em>and</em> the front door. To anyone who opens the app it is
 * "Offline Chess" — a real, playable board with no password field and nothing to
 * give the vault away. The vault is opened by playing a <b>secret sequence of
 * moves</b> from the opening position; the moves themselves derive the key (see
 * {@link MoveKey}), so there is nothing on disk that says "this is a vault".
 *
 * <p>Three roles, chosen by vault state:
 * <ul>
 *   <li><b>Set up</b> (no vault yet) — record a secret opening (≥ {@value #MIN_MOVES}
 *       moves), replay it to confirm, and create the vault from it.</li>
 *   <li><b>Unlock</b> (vault exists) — after the user plays the stored number of
 *       moves, derive the key and try to open. A wrong line just looks like a game;
 *       "New game" resets for another try. No error is shown — that is the point.</li>
 * </ul>
 *
 * Mirrors the sibling vault app's {@code app/chess.tsx} / {@code chess-setup.tsx}.
 */
public final class ChessActivity extends Activity {

    private static final int MIN_MOVES = 3;

    private static final int LIGHT = Color.parseColor("#ECEDD0");
    private static final int DARK = Color.parseColor("#6F8F57");
    private static final int SELECTED = Color.parseColor("#CDD26A");
    private static final int TARGET_DOT = Color.parseColor("#40000000");
    private static final int BG = Color.parseColor("#1B1C18");
    private static final int MUTED = Color.parseColor("#9AA08C");

    private enum Mode { SETUP_RECORD, SETUP_CONFIRM, UNLOCK }

    private VaultStore vault;
    private Mode mode;

    // Board state.
    private Piece[][] board;
    private ChessEngine.Color turn;
    private Pos sel;
    private List<Pos> targets = new ArrayList<>();
    private final List<String> played = new ArrayList<>();

    // Flow state.
    private int chessLen;                 // moves needed to unlock (UNLOCK mode)
    private List<String> recorded;        // the sequence captured in SETUP_RECORD
    private boolean tried;                // one unlock attempt per game

    // Views we update in place.
    private LinearLayout boardView;
    private TextView status;
    private TextView banner;
    private Button primary;               // "Save" in setup; "New game" otherwise

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        vault = ((App) getApplication()).vault();

        if (vault.isUnlocked()) {        // already open (e.g. returned via back) — go home
            goHome();
            return;
        }
        chessLen = vault.chessLen();
        mode = vault.isInitialised() ? Mode.UNLOCK : Mode.SETUP_RECORD;
        newGame();
        setContentView(buildUi());
        render();
    }

    // --- UI scaffold ---------------------------------------------------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("Offline Chess");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8), 0, dp(4));

        status = new TextView(this);
        status.setTextColor(MUTED);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, dp(8));

        banner = new TextView(this);
        banner.setTextColor(MUTED);
        banner.setTextSize(13);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(8), 0, dp(8), dp(10));

        boardView = new LinearLayout(this);
        boardView.setOrientation(LinearLayout.VERTICAL);

        primary = new Button(this);
        primary.setOnClickListener(v -> onPrimary());

        root.addView(title);
        root.addView(status);
        root.addView(banner);
        root.addView(boardView);
        root.addView(primary);
        return root;
    }

    // --- game logic ----------------------------------------------------------

    private void newGame() {
        board = ChessEngine.initialBoard();
        turn = ChessEngine.Color.W;
        sel = null;
        targets = new ArrayList<>();
        played.clear();
        tried = false;
    }

    private void onSquare(int r, int c) {
        Piece piece = board[r][c];
        if (sel != null) {
            boolean isTarget = contains(targets, r, c);
            if (isTarget) {
                String mv = ChessEngine.squareName(sel) + ChessEngine.squareName(new Pos(r, c));
                board = ChessEngine.move(board, sel, new Pos(r, c));
                turn = turn == ChessEngine.Color.W ? ChessEngine.Color.B : ChessEngine.Color.W;
                sel = null;
                targets = new ArrayList<>();
                played.add(mv);
                render();
                afterMove();
                return;
            }
        }
        if (piece != null && piece.color == turn) {
            sel = new Pos(r, c);
            targets = ChessEngine.legalMoves(board, sel);
        } else {
            sel = null;
            targets = new ArrayList<>();
        }
        render();
    }

    /** React to a completed move depending on which role this screen is playing. */
    private void afterMove() {
        if (mode == Mode.SETUP_CONFIRM && played.size() == recorded.size()) {
            finishSetup(new ArrayList<>(played));
        } else if (mode == Mode.UNLOCK && chessLen > 0 && played.size() == chessLen && !tried) {
            tried = true;
            tryUnlock(new ArrayList<>(played));
        }
    }

    private void onPrimary() {
        switch (mode) {
            case SETUP_RECORD:
                if (played.size() < MIN_MOVES) {
                    toast("Play at least " + MIN_MOVES + " moves first");
                    return;
                }
                recorded = new ArrayList<>(played);
                mode = Mode.SETUP_CONFIRM;
                newGame();
                render();
                break;
            case SETUP_CONFIRM:
            case UNLOCK:
                newGame();         // "New game" / "Start over"
                render();
                break;
        }
    }

    private void finishSetup(List<String> confirm) {
        if (!confirm.equals(recorded)) {
            toast("Those didn't match — let's record it again");
            mode = Mode.SETUP_RECORD;
            recorded = null;
            newGame();
            render();
            return;
        }
        try {
            vault.unlock(MoveKey.movesToSecret(recorded).toCharArray());   // creates the vault
            vault.setChessLen(recorded.size());
            Toast.makeText(this,
                    "Saved. Your vault now opens by playing those " + recorded.size()
                            + " moves. There's no other way in — don't forget them.",
                    Toast.LENGTH_LONG).show();
            goHome();
        } catch (Exception e) {
            toast("Couldn't set it up: " + e.getMessage());
            mode = Mode.SETUP_RECORD;
            recorded = null;
            newGame();
            render();
        }
    }

    private void tryUnlock(List<String> seq) {
        try {
            vault.unlock(MoveKey.movesToSecret(seq).toCharArray());
            goHome();                                  // the line was correct
        } catch (Exception e) {
            // Wrong line. Stay on the board — it just looks like a game in progress.
            // "New game" lets them try again.
        }
    }

    private void goHome() {
        startActivity(new Intent(this, VaultActivity.class));
        finish();
    }

    // --- rendering -----------------------------------------------------------

    private void render() {
        // Status line keeps the chess illusion; setup adds quiet guidance.
        status.setText((turn == ChessEngine.Color.W ? "White" : "Black") + " to move");
        switch (mode) {
            case SETUP_RECORD:
                banner.setVisibility(View.VISIBLE);
                banner.setText("Choose your secret opening: play at least " + MIN_MOVES
                        + " moves, then Save.  (" + played.size() + " played)");
                primary.setText("Save these " + played.size() + " moves");
                primary.setEnabled(played.size() >= MIN_MOVES);
                break;
            case SETUP_CONFIRM:
                banner.setVisibility(View.VISIBLE);
                banner.setText("Play the same moves again to confirm.  ("
                        + played.size() + "/" + recorded.size() + ")");
                primary.setText("Start over");
                primary.setEnabled(true);
                break;
            case UNLOCK:
                banner.setVisibility(View.GONE);       // no tell at all
                primary.setText("New game");
                primary.setEnabled(true);
                break;
        }
        drawBoard();
    }

    private void drawBoard() {
        boardView.removeAllViews();
        int cell = boardCellPx();
        for (int r = 0; r < 8; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 8; c++) {
                row.addView(square(r, c, cell));
            }
            boardView.addView(row);
        }
    }

    private View square(int r, int c, int cell) {
        boolean isSel = sel != null && sel.r == r && sel.c == c;
        boolean isTarget = contains(targets, r, c);
        int base = (r + c) % 2 == 0 ? LIGHT : DARK;

        TextView tv = new TextView(this);
        tv.setLayoutParams(new LinearLayout.LayoutParams(cell, cell));
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(isSel ? SELECTED : base);
        Piece p = board[r][c];
        if (p != null) {
            tv.setText(ChessEngine.glyph(p));
            tv.setTextSize(cell * 0.42f / getResources().getDisplayMetrics().scaledDensity);
        } else if (isTarget) {
            tv.setText("•");
            tv.setTextColor(TARGET_DOT);
            tv.setTextSize(cell * 0.6f / getResources().getDisplayMetrics().scaledDensity);
        }
        final int fr = r, fc = c;
        tv.setOnClickListener(v -> onSquare(fr, fc));
        return tv;
    }

    /** A square edge sized so the 8×8 board fits the screen width with margins. */
    private int boardCellPx() {
        int screen = getResources().getDisplayMetrics().widthPixels;
        int boardMax = Math.min(screen - dp(32), dp(360));
        return boardMax / 8;
    }

    // --- helpers -------------------------------------------------------------

    private static boolean contains(List<Pos> ps, int r, int c) {
        for (Pos p : ps) if (p.r == r && p.c == c) return true;
        return false;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        // Back from the front door just leaves the app (don't expose anything).
        moveTaskToBack(true);
    }
}
