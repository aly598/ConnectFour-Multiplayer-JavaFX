package com.example.demo.model;

import java.util.ArrayList;
import java.util.List;

/** Connect Four board. Cells: 0 = empty, 1 = RED, 2 = YELLOW.
 *  Supports configurable rows/cols and win-length (4 default, 5 variant). */
public final class Board {
    public static final int EMPTY = 0, RED = 1, YEL = 2;

    public final int rows, cols, winLen;
    private final int[][] g;

    public Board(int rows, int cols, int winLen) {
        this.rows = rows; this.cols = cols; this.winLen = winLen;
        this.g = new int[rows][cols];
    }

    public int cell(int r, int c) { return g[r][c]; }

    /** Drop a disc; returns the row it landed in, or -1 if column full. */
    public int drop(int col, int player) {
        if (col < 0 || col >= cols) return -1;
        for (int r = rows - 1; r >= 0; r--) {
            if (g[r][col] == EMPTY) { g[r][col] = player; return r; }
        }
        return -1;
    }

    /** Power: clear an entire column. */
    public void clearColumn(int col) {
        for (int r = 0; r < rows; r++) g[r][col] = EMPTY;
    }

    /** Returns a winning sequence of (row,col) pairs of length >= winLen
     *  ending at (r,c), or null if none. */
    public List<int[]> findWin(int r, int c) {
        int p = g[r][c]; if (p == EMPTY) return null;
        int[][] dirs = { {0,1}, {1,0}, {1,1}, {1,-1} };
        for (int[] d : dirs) {
            List<int[]> line = new ArrayList<>();
            line.add(new int[]{r,c});
            for (int s = 1; s < winLen; s++) {
                int rr = r + d[0]*s, cc = c + d[1]*s;
                if (in(rr,cc) && g[rr][cc] == p) line.add(new int[]{rr,cc}); else break;
            }
            for (int s = 1; s < winLen; s++) {
                int rr = r - d[0]*s, cc = c - d[1]*s;
                if (in(rr,cc) && g[rr][cc] == p) line.add(0, new int[]{rr,cc}); else break;
            }
            if (line.size() >= winLen) return line;
        }
        return null;
    }

    public boolean isFull() {
        for (int c = 0; c < cols; c++) if (g[0][c] == EMPTY) return false;
        return true;
    }

    private boolean in(int r, int c) { return r>=0 && r<rows && c>=0 && c<cols; }

    /** Encode board as a flat row-major string of digits. */
    public String encode() {
        StringBuilder sb = new StringBuilder(rows*cols);
        for (int r=0;r<rows;r++) for (int c=0;c<cols;c++) sb.append(g[r][c]);
        return sb.toString();
    }
}
