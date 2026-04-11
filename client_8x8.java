import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

class Client {
    private static final int SIZE = 8;
    private static final int EMPTY = 0;
    private static final int BLACK = 2;
    private static final int RED = 4;

    private static final int BASE_SEARCH_DEPTH = 4;
    private static final int ENDGAME_SEARCH_DEPTH = 5;
    private static final int TACTICAL_SEARCH_DEPTH = 5;
    private static final int ENDGAME_PIECE_THRESHOLD = 12;
    private static final int MAX_ITERATIVE_DEPTH = 8;
    private static final long SEARCH_TIME_BUDGET_MS = 4300;
    private static final int IMMEDIATE_LOSS_PENALTY = 400_000;
    private static final int MAX_TRANSPOSITION_SIZE = 300_000;

    private static final int WIN_SCORE = 1_000_000;
    private static final int TT_FLAG_EXACT = 0;
    private static final int TT_FLAG_LOWER = 1;
    private static final int TT_FLAG_UPPER = 2;

    private static final Map<Long, TTEntry> TRANSPOSITION_TABLE = new HashMap<>(MAX_TRANSPOSITION_SIZE);
    private static final long[][] ZOBRIST_PIECE = new long[3][SIZE * SIZE];
    private static final long[] ZOBRIST_SIDE = new long[2];

    static {
        Random zobristRandom = new Random(3202026L);
        for (int piece = 1; piece <= 2; piece++) {
            for (int square = 0; square < SIZE * SIZE; square++) {
                ZOBRIST_PIECE[piece][square] = zobristRandom.nextLong();
            }
        }
        ZOBRIST_SIDE[0] = zobristRandom.nextLong();
        ZOBRIST_SIDE[1] = zobristRandom.nextLong();
    }

    private static class Move {
        int fromX;
        int fromY;
        int toX;
        int toY;
        int captured;

        Move(int fromX, int fromY, int toX, int toY) {
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
            this.captured = EMPTY;
        }
    }

    private static class MoveScore {
        Move move;
        int score;

        MoveScore(Move move, int score) {
            this.move = move;
            this.score = score;
        }
    }

    private static class TTEntry {
        int depth;
        int score;
        int flag;
        int bestMoveCode;

        TTEntry(int depth, int score, int flag, int bestMoveCode) {
            this.depth = depth;
            this.score = score;
            this.flag = flag;
            this.bestMoveCode = bestMoveCode;
        }
    }

    public static void main(String[] args) {
        Socket myClient;
        BufferedInputStream input;
        BufferedOutputStream output;
        int[][] board = new int[SIZE][SIZE];
        int myColor = RED;
        Move lastMoveSent = null;
        int invalidMoveIndex = 0;

        try {
            myClient = new Socket("localhost", 8888);
            input = new BufferedInputStream(myClient.getInputStream());
            output = new BufferedOutputStream(myClient.getOutputStream());

            while (true) {
                int cmdValue = input.read();
                if (cmdValue == -1) {
                    break;
                }
                char cmd = (char) cmdValue;

                if (cmd < '1' || cmd > '5') {
                    continue;
                }

                System.out.println("Commande serveur: " + cmd);

                if (cmd == '1') {
                    myColor = RED;
                    invalidMoveIndex = 0;
                    readBoardFromServer(input, board);
                    System.out.println("Nouvelle partie! Vous jouez Rouge.");
                    printBoard(board);
                    System.out.println("Calcul du meilleur coup...");
                    lastMoveSent = playAndSend(output, board, myColor);
                }

                if (cmd == '2') {
                    myColor = BLACK;
                    invalidMoveIndex = 0;
                    readBoardFromServer(input, board);
                    System.out.println("Nouvelle partie! Vous jouez Noir.");
                    printBoard(board);
                }

                if (cmd == '3') {
                    String opponentMoveText = readAvailablePayload(input, 128);
                    System.out.println("Dernier coup adversaire: " + opponentMoveText);

                    Move opponentMove = parseMove(opponentMoveText);
                    int opponentColor = (myColor == RED) ? BLACK : RED;
                    if (opponentMove != null && isMoveLegal(board, opponentMove, opponentColor)) {
                        applyMove(board, opponentMove);
                    } else if (opponentMove != null) {
                        System.out.println("Coup adverse ignore car invalide selon le plateau local.");
                    }
                    invalidMoveIndex = 0;

                    lastMoveSent = playAndSend(output, board, myColor);
                }

                if (cmd == '4') {
                    System.out.println("Coup invalide. Nouvelle tentative.");
                    if (lastMoveSent != null) {
                        undoMove(board, lastMoveSent, myColor, lastMoveSent.captured);
                    }

                    invalidMoveIndex++;
                    Move move = chooseBestMove(board, myColor);
                    if (move == null) {
                        List<Move> legalMoves = generateMoves(board, myColor);
                        if (!legalMoves.isEmpty()) {
                            int idx = invalidMoveIndex % legalMoves.size();
                            move = legalMoves.get(idx);
                        }
                    }

                    if (move != null) {
                        move.captured = board[move.toX][move.toY];
                        applyMove(board, move);
                        lastMoveSent = move;
                        sendMove(output, move);
                    }
                }

                if (cmd == '5') {
                    String payload = readAvailablePayload(input, 128);
                    System.out.println("Partie terminee. Dernier coup: " + payload);
                    break;
                }
            }
        } catch (IOException e) {
            System.out.println(e);
        }
    }

    private static Move playAndSend(BufferedOutputStream output, int[][] board, int myColor) throws IOException {
        Move move = chooseBestMove(board, myColor);
        if (move == null) {
            System.out.println("ERREUR: aucun coup disponible!");
            return null;
        }

        move.captured = board[move.toX][move.toY];
        applyMove(board, move);
        sendMove(output, move);
        return move;
    }

    private static void readBoardFromServer(BufferedInputStream input, int[][] board) throws IOException {
        String boardText = readAvailablePayload(input, 2048);
        Matcher numberMatcher = Pattern.compile("(-?\\d+)").matcher(boardText);
        List<Integer> values = new ArrayList<>();
        while (numberMatcher.find()) {
            values.add(Integer.valueOf(numberMatcher.group(1)));
        }

        if (values.size() < SIZE * SIZE) {
            initBoard(board);
            return;
        }

        int idx = values.size() - (SIZE * SIZE);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                board[x][SIZE - 1 - y] = normalizePiece(values.get(idx++));
            }
        }
    }

    private static int normalizePiece(int rawValue) {
        if (rawValue == EMPTY || rawValue == BLACK || rawValue == RED) {
            return rawValue;
        }

        if (rawValue == 1) {
            return RED;
        }

        return EMPTY;
    }

    private static void initBoard(int[][] board) {
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                board[x][y] = EMPTY;
            }
        }
        for (int x = 0; x < SIZE; x++) {
            board[x][0] = RED;
            board[x][1] = RED;
            board[x][SIZE - 2] = BLACK;
            board[x][SIZE - 1] = BLACK;
        }
    }

    private static void printBoard(int[][] board) {
        System.out.println("Etat du plateau:");
        for (int y = SIZE - 1; y >= 0; y--) {
            for (int x = 0; x < SIZE; x++) {
                char c = '.';
                if (board[x][y] == RED)
                    c = 'R';
                else if (board[x][y] == BLACK)
                    c = 'N';
                System.out.print(c + " ");
            }
            System.out.println();
        }
    }

    private static String readAvailablePayload(BufferedInputStream input, int maxBytes) throws IOException {
        try {
            Thread.sleep(80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int idleLoops = 0;

        while (out.size() < maxBytes && idleLoops < 5) {
            int size = input.available();
            if (size <= 0) {
                idleLoops++;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            idleLoops = 0;
            int bytesToRead = Math.min(size, maxBytes - out.size());
            byte[] buffer = new byte[bytesToRead];
            int read = input.read(buffer, 0, bytesToRead);
            if (read > 0) {
                out.write(buffer, 0, read);
            }
        }

        return out.toString().trim();
    }

    private static List<Move> generateMoves(int[][] board, int color) {
        List<Move> moves = new ArrayList<>();
        int direction = (color == RED) ? 1 : -1;

        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                if (board[x][y] != color) {
                    continue;
                }

                int nextY = y + direction;
                if (nextY < 0 || nextY >= SIZE) {
                    continue;
                }

                if (board[x][nextY] == EMPTY) {
                    moves.add(new Move(x, y, x, nextY));
                }

                int leftX = x - 1;
                if (leftX >= 0 && board[leftX][nextY] != color) {
                    moves.add(new Move(x, y, leftX, nextY));
                }

                int rightX = x + 1;
                if (rightX < SIZE && board[rightX][nextY] != color) {
                    moves.add(new Move(x, y, rightX, nextY));
                }
            }
        }

        return moves;
    }

    private static int countMobility(int[][] board, int color) {
        int mobility = 0;
        int direction = (color == RED) ? 1 : -1;

        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                if (board[x][y] != color) {
                    continue;
                }

                int nextY = y + direction;
                if (nextY < 0 || nextY >= SIZE) {
                    continue;
                }

                if (board[x][nextY] == EMPTY) {
                    mobility++;
                }

                int leftX = x - 1;
                if (leftX >= 0 && board[leftX][nextY] != color) {
                    mobility++;
                }

                int rightX = x + 1;
                if (rightX < SIZE && board[rightX][nextY] != color) {
                    mobility++;
                }
            }
        }

        return mobility;
    }

    private static boolean isMoveLegal(int[][] board, Move move, int color) {
        if (move.fromX < 0 || move.fromX >= SIZE || move.toX < 0 || move.toX >= SIZE || move.fromY < 0
                || move.fromY >= SIZE || move.toY < 0 || move.toY >= SIZE) {
            return false;
        }

        if (board[move.fromX][move.fromY] != color) {
            return false;
        }

        if (board[move.toX][move.toY] == color) {
            return false;
        }

        int direction = (color == RED) ? 1 : -1;
        int dy = move.toY - move.fromY;
        int dx = Math.abs(move.toX - move.fromX);

        if (dy != direction || dx > 1) {
            return false;
        }

        if (dx == 0) {
            return board[move.toX][move.toY] == EMPTY;
        }

        return true;
    }

    private static Move chooseBestMove(int[][] board, int myColor) {
        List<Move> moves = generateMoves(board, myColor);
        if (moves.isEmpty()) {
            return null;
        }

        if (TRANSPOSITION_TABLE.size() > MAX_TRANSPOSITION_SIZE) {
            TRANSPOSITION_TABLE.clear();
        }

        List<Move> winningMoves = new ArrayList<>();
        for (Move move : moves) {
            if (reachesGoal(move, myColor)) {
                winningMoves.add(move);
            }
        }
        if (!winningMoves.isEmpty()) {
            return winningMoves.get(0);
        }

        long deadlineNanos = System.nanoTime() + SEARCH_TIME_BUDGET_MS * 1_000_000L;
        int opponent = (myColor == RED) ? BLACK : RED;
        long boardHash = computeBoardHash(board, myColor);

        moves = filterMovesThatAvoidImmediateLoss(board, moves, myColor, opponent);
        if (moves.isEmpty()) {
            return null;
        }

        List<Move> rootMoves = orderMoves(board, moves, myColor);
        int baseDepth = computeSearchDepth(board, myColor);
        int startDepth = Math.max(2, baseDepth - 1);
        int maxDepth = Math.min(MAX_ITERATIVE_DEPTH, baseDepth + 2);

        int bestScore = Integer.MIN_VALUE;
        List<MoveScore> scoredMoves = null;

        for (int depth = startDepth; depth <= maxDepth; depth++) {
            if (System.nanoTime() >= deadlineNanos) {
                break;
            }

            List<MoveScore> iterationScores = new ArrayList<>();
            int iterationBest = Integer.MIN_VALUE;
            boolean completedIteration = true;

            for (Move move : rootMoves) {
                if (System.nanoTime() >= deadlineNanos) {
                    completedIteration = false;
                    break;
                }

                int captured = board[move.toX][move.toY];
                long childHash = applyMoveToHash(boardHash, move, myColor, captured, opponent);
                applyMove(board, move);
                int score = alphaBeta(board, depth - 1, Integer.MIN_VALUE, Integer.MAX_VALUE, false, myColor, opponent,
                        deadlineNanos, childHash);

                if (hasImmediateWinningMove(board, opponent)) {
                    score -= IMMEDIATE_LOSS_PENALTY;
                }

                undoMove(board, move, myColor, captured);

                iterationScores.add(new MoveScore(move, score));
                if (score > iterationBest) {
                    iterationBest = score;
                }
            }

            if (!iterationScores.isEmpty() && (completedIteration || scoredMoves == null)) {
                scoredMoves = iterationScores;
                bestScore = iterationBest;
                rootMoves = sortRootMovesByScore(rootMoves, iterationScores);
            }

            if (!completedIteration) {
                break;
            }
        }

        if (scoredMoves == null || scoredMoves.isEmpty()) {
            return rootMoves.get(0);
        }

        List<Move> bestMoves = new ArrayList<>();
        for (MoveScore scored : scoredMoves) {
            if (scored.score == bestScore) {
                bestMoves.add(scored.move);
            }
        }

        if (!bestMoves.isEmpty()) {
            return bestMoves.get(0);
        }

        return rootMoves.get(0);
    }

    private static List<Move> sortRootMovesByScore(List<Move> rootMoves, List<MoveScore> iterationScores) {
        Map<Move, Integer> scoreByMove = new IdentityHashMap<>();
        for (MoveScore ms : iterationScores) {
            scoreByMove.put(ms.move, ms.score);
        }

        List<Move> sorted = new ArrayList<>(rootMoves);
        sorted.sort((a, b) -> Integer.compare(scoreByMove.getOrDefault(b, Integer.MIN_VALUE),
                scoreByMove.getOrDefault(a, Integer.MIN_VALUE)));
        return sorted;
    }

    private static int sideIndex(int color) {
        return (color == BLACK) ? 0 : 1;
    }

    private static int pieceIndex(int piece) {
        if (piece == BLACK) {
            return 1;
        }
        if (piece == RED) {
            return 2;
        }
        return 0;
    }

    private static int squareIndex(int x, int y) {
        return y * SIZE + x;
    }

    private static long computeBoardHash(int[][] board, int sideToMove) {
        long hash = 0L;
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                int piece = board[x][y];
                if (piece == BLACK || piece == RED) {
                    hash ^= ZOBRIST_PIECE[pieceIndex(piece)][squareIndex(x, y)];
                }
            }
        }
        hash ^= ZOBRIST_SIDE[sideIndex(sideToMove)];
        return hash;
    }

    private static long applyMoveToHash(long currentHash, Move move, int movingColor, int capturedPiece,
            int nextColor) {
        long hash = currentHash;
        int fromIdx = squareIndex(move.fromX, move.fromY);
        int toIdx = squareIndex(move.toX, move.toY);
        int movingIdx = pieceIndex(movingColor);

        hash ^= ZOBRIST_SIDE[sideIndex(movingColor)];
        hash ^= ZOBRIST_SIDE[sideIndex(nextColor)];

        hash ^= ZOBRIST_PIECE[movingIdx][fromIdx];
        if (capturedPiece == BLACK || capturedPiece == RED) {
            hash ^= ZOBRIST_PIECE[pieceIndex(capturedPiece)][toIdx];
        }
        hash ^= ZOBRIST_PIECE[movingIdx][toIdx];

        return hash;
    }

    private static int encodeMove(Move move) {
        return move.fromX | (move.fromY << 3) | (move.toX << 6) | (move.toY << 9);
    }

    private static void prioritizeMove(List<Move> moves, int encodedMove) {
        for (int i = 0; i < moves.size(); i++) {
            if (encodeMove(moves.get(i)) == encodedMove) {
                if (i != 0) {
                    Collections.swap(moves, 0, i);
                }
                return;
            }
        }
    }

    private static void storeTransposition(long hash, int depth, int score, int alphaStart, int betaStart,
            int bestMoveCode) {
        int flag;
        if (score <= alphaStart) {
            flag = TT_FLAG_UPPER;
        } else if (score >= betaStart) {
            flag = TT_FLAG_LOWER;
        } else {
            flag = TT_FLAG_EXACT;
        }
        TRANSPOSITION_TABLE.put(hash, new TTEntry(depth, score, flag, bestMoveCode));
    }

    private static List<Move> filterMovesThatAvoidImmediateLoss(int[][] board, List<Move> moves, int myColor,
            int opponentColor) {
        if (!hasImmediateWinningMove(board, opponentColor)) {
            return moves;
        }

        List<Move> safeMoves = new ArrayList<>();
        for (Move move : moves) {
            int captured = board[move.toX][move.toY];
            applyMove(board, move);
            boolean stillLosingNextTurn = hasImmediateWinningMove(board, opponentColor);
            undoMove(board, move, myColor, captured);

            if (!stillLosingNextTurn) {
                safeMoves.add(move);
            }
        }

        return safeMoves.isEmpty() ? moves : safeMoves;
    }

    private static boolean hasImmediateWinningMove(int[][] board, int color) {
        for (Move move : generateMoves(board, color)) {
            if (reachesGoal(move, color)) {
                return true;
            }

            int captured = board[move.toX][move.toY];
            applyMove(board, move);
            boolean winsNow = hasWinner(board, color);
            undoMove(board, move, color, captured);
            if (winsNow) {
                return true;
            }
        }
        return false;
    }

    private static int computeSearchDepth(int[][] board, int myColor) {
        int totalPieces = countPieces(board, RED) + countPieces(board, BLACK);
        int depth = (totalPieces <= ENDGAME_PIECE_THRESHOLD) ? ENDGAME_SEARCH_DEPTH : BASE_SEARCH_DEPTH;

        int opponent = (myColor == RED) ? BLACK : RED;
        if (hasImmediatePromotionThreat(board, myColor) || hasImmediatePromotionThreat(board, opponent)) {
            depth = Math.max(depth, TACTICAL_SEARCH_DEPTH);
        }

        return depth;
    }

    private static boolean hasImmediatePromotionThreat(int[][] board, int color) {
        int threatRow = (color == RED) ? SIZE - 2 : 1;

        for (int x = 0; x < SIZE; x++) {
            if (board[x][threatRow] != color) {
                continue;
            }

            Move straight = new Move(x, threatRow, x, (color == RED) ? SIZE - 1 : 0);
            if (isMoveLegal(board, straight, color)) {
                return true;
            }

            if (x > 0) {
                Move leftDiag = new Move(x, threatRow, x - 1, (color == RED) ? SIZE - 1 : 0);
                if (isMoveLegal(board, leftDiag, color)) {
                    return true;
                }
            }

            if (x < SIZE - 1) {
                Move rightDiag = new Move(x, threatRow, x + 1, (color == RED) ? SIZE - 1 : 0);
                if (isMoveLegal(board, rightDiag, color)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean reachesGoal(Move move, int color) {
        return (color == RED && move.toY == SIZE - 1) || (color == BLACK && move.toY == 0);
    }

    private static List<Move> orderMoves(int[][] board, List<Move> moves, int color) {
        List<Move> ordered = new ArrayList<>(moves);
        ordered.sort(
                (a, b) -> Integer.compare(scoreMoveForOrdering(board, b, color), scoreMoveForOrdering(board, a, color)));
        return ordered;
    }

    private static int scoreMoveForOrdering(int[][] board, Move move, int color) {
        int score = 0;
        int opponent = (color == RED) ? BLACK : RED;

        if (board[move.toX][move.toY] == opponent) {
            score += 300;
        }
        if (reachesGoal(move, color)) {
            score += 20_000;
        }

        int advance = (color == RED) ? move.toY : (SIZE - 1 - move.toY);
        score += advance * 15;

        if (move.toX >= 2 && move.toX <= 5) {
            score += 8;
        }

        if (isSquareAttackedBy(board, move.toX, move.toY, opponent)) {
            score -= 50;
        }

        return score;
    }

    private static int alphaBeta(int[][] board, int depth, int alpha, int beta, boolean maximizing, int myColor,
            int currentColor, long deadlineNanos, long boardHash) {
        if (System.nanoTime() >= deadlineNanos) {
            return evaluateBoard(board, myColor);
        }

        if (depth == 0 || hasWinner(board, RED) || hasWinner(board, BLACK)) {
            return evaluateBoard(board, myColor);
        }

        TTEntry ttEntry = TRANSPOSITION_TABLE.get(boardHash);
        if (ttEntry != null && ttEntry.depth >= depth) {
            if (ttEntry.flag == TT_FLAG_EXACT) {
                return ttEntry.score;
            }
            if (ttEntry.flag == TT_FLAG_LOWER) {
                alpha = Math.max(alpha, ttEntry.score);
            } else if (ttEntry.flag == TT_FLAG_UPPER) {
                beta = Math.min(beta, ttEntry.score);
            }
            if (alpha >= beta) {
                return ttEntry.score;
            }
        }

        int alphaStart = alpha;
        int betaStart = beta;

        List<Move> moves = orderMoves(board, generateMoves(board, currentColor), currentColor);
        if (ttEntry != null && ttEntry.bestMoveCode >= 0) {
            prioritizeMove(moves, ttEntry.bestMoveCode);
        }

        if (moves.isEmpty()) {
            return evaluateBoard(board, myColor);
        }

        int nextColor = (currentColor == RED) ? BLACK : RED;

        if (maximizing) {
            int value = Integer.MIN_VALUE;
            int bestMoveCode = -1;
            for (Move move : moves) {
                if (System.nanoTime() >= deadlineNanos) {
                    break;
                }
                int captured = board[move.toX][move.toY];
                long nextHash = applyMoveToHash(boardHash, move, currentColor, captured, nextColor);
                applyMove(board, move);
                int childValue = alphaBeta(board, depth - 1, alpha, beta, false, myColor, nextColor, deadlineNanos,
                        nextHash);
                undoMove(board, move, currentColor, captured);

                if (childValue > value) {
                    value = childValue;
                    bestMoveCode = encodeMove(move);
                }

                alpha = Math.max(alpha, value);
                if (alpha >= beta) {
                    break;
                }
            }

            storeTransposition(boardHash, depth, value, alphaStart, betaStart, bestMoveCode);
            return value;
        } else {
            int value = Integer.MAX_VALUE;
            int bestMoveCode = -1;
            for (Move move : moves) {
                if (System.nanoTime() >= deadlineNanos) {
                    break;
                }
                int captured = board[move.toX][move.toY];
                long nextHash = applyMoveToHash(boardHash, move, currentColor, captured, nextColor);
                applyMove(board, move);
                int childValue = alphaBeta(board, depth - 1, alpha, beta, true, myColor, nextColor, deadlineNanos,
                        nextHash);
                undoMove(board, move, currentColor, captured);

                if (childValue < value) {
                    value = childValue;
                    bestMoveCode = encodeMove(move);
                }

                beta = Math.min(beta, value);
                if (alpha >= beta) {
                    break;
                }
            }

            storeTransposition(boardHash, depth, value, alphaStart, betaStart, bestMoveCode);
            return value;
        }
    }

    private static int evaluateBoard(int[][] board, int myColor) {
        int opponent = (myColor == RED) ? BLACK : RED;
        if (hasWinner(board, myColor)) {
            return WIN_SCORE;
        }
        if (hasWinner(board, opponent)) {
            return -WIN_SCORE;
        }

        int tacticalPressure = 0;
        if (hasImmediatePromotionThreat(board, myColor)) {
            tacticalPressure += 120_000;
        }
        if (hasImmediatePromotionThreat(board, opponent)) {
            tacticalPressure -= 120_000;
        }

        int myMaterial = countPieces(board, myColor);
        int opponentMaterial = countPieces(board, opponent);
        int myAdvance = getAdvance(board, myColor);
        int opponentAdvance = getAdvance(board, opponent);
        int myMobility = countMobility(board, myColor);
        int opponentMobility = countMobility(board, opponent);

        int myPositional = evaluateSide(board, myColor);
        int opponentPositional = evaluateSide(board, opponent);

        return tacticalPressure
            + (myMaterial - opponentMaterial) * 140
            + (myAdvance - opponentAdvance) * 14
            + (myMobility - opponentMobility) * 4
            + (myPositional - opponentPositional);
    }

    private static int evaluateSide(int[][] board, int color) {
        int opponent = (color == RED) ? BLACK : RED;
        int score = 0;

        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                if (board[x][y] != color) {
                    continue;
                }

                int progress = (color == RED) ? y : (SIZE - 1 - y);
                score += progress * 12;

                if (x >= 2 && x <= 5) {
                    score += 6;
                }

                if (isPieceSupported(board, x, y, color)) {
                    score += 10;
                }

                if (isSquareAttackedBy(board, x, y, opponent)) {
                    score -= 18;
                }
            }
        }

        score += countPromotionThreats(board, color) * 60;
        return score;
    }

    private static int getAdvance(int[][] board, int color) {
        int total = 0;
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                if (board[x][y] == color) {
                    total += (color == RED) ? y : (SIZE - 1 - y);
                }
            }
        }
        return total;
    }

    private static boolean isSquareAttackedBy(int[][] board, int targetX, int targetY, int attackerColor) {
        int direction = (attackerColor == RED) ? 1 : -1;
        int sourceY = targetY - direction;
        if (sourceY < 0 || sourceY >= SIZE) {
            return false;
        }

        int leftSourceX = targetX - 1;
        if (leftSourceX >= 0 && board[leftSourceX][sourceY] == attackerColor) {
            return true;
        }

        int rightSourceX = targetX + 1;
        return rightSourceX < SIZE && board[rightSourceX][sourceY] == attackerColor;
    }

    private static boolean isPieceSupported(int[][] board, int x, int y, int color) {
        int direction = (color == RED) ? 1 : -1;
        int supportY = y - direction;
        if (supportY < 0 || supportY >= SIZE) {
            return false;
        }

        int leftSupportX = x - 1;
        if (leftSupportX >= 0 && board[leftSupportX][supportY] == color) {
            return true;
        }

        int rightSupportX = x + 1;
        return rightSupportX < SIZE && board[rightSupportX][supportY] == color;
    }

    private static int countPromotionThreats(int[][] board, int color) {
        int threatRow = (color == RED) ? SIZE - 2 : 1;
        int count = 0;

        for (int x = 0; x < SIZE; x++) {
            if (board[x][threatRow] == color) {
                count++;
            }
        }

        return count;
    }

    private static boolean hasWinner(int[][] board, int color) {
        int targetRow = (color == RED) ? SIZE - 1 : 0;
        for (int x = 0; x < SIZE; x++) {
            if (board[x][targetRow] == color) {
                return true;
            }
        }
        int opponent = (color == RED) ? BLACK : RED;
        return countPieces(board, opponent) == 0;
    }

    private static int countPieces(int[][] board, int color) {
        int count = 0;
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                if (board[x][y] == color) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void applyMove(int[][] board, Move move) {
        int piece = board[move.fromX][move.fromY];
        board[move.fromX][move.fromY] = EMPTY;
        board[move.toX][move.toY] = piece;
    }

    private static void undoMove(int[][] board, Move move, int color, int captured) {
        board[move.toX][move.toY] = captured;
        board[move.fromX][move.fromY] = color;
    }

    private static Move parseMove(String moveText) {
        if (moveText == null) {
            return null;
        }

        Matcher coordMatcher = Pattern.compile("([A-Ha-h][1-8])").matcher(moveText);
        if (!coordMatcher.find()) {
            return null;
        }
        String from = coordMatcher.group(1).toUpperCase();

        if (!coordMatcher.find()) {
            return null;
        }
        String to = coordMatcher.group(1).toUpperCase();

        int fromX = from.charAt(0) - 'A';
        int fromY = from.charAt(1) - '1';
        int toX = to.charAt(0) - 'A';
        int toY = to.charAt(1) - '1';

        if (fromX < 0 || fromX >= SIZE || toX < 0 || toX >= SIZE || fromY < 0 || fromY >= SIZE || toY < 0
                || toY >= SIZE) {
            return null;
        }
        return new Move(fromX, fromY, toX, toY);
    }

    private static void sendMove(BufferedOutputStream output, Move move) throws IOException {
        char fromCol = (char) ('A' + move.fromX);
        char fromRow = (char) ('1' + move.fromY);
        char toCol = (char) ('A' + move.toX);
        char toRow = (char) ('1' + move.toY);
        String formatted = "" + fromCol + fromRow + "-" + toCol + toRow;
        System.out.println("Coup joue: " + formatted);
        output.write(formatted.getBytes(), 0, formatted.length());
        output.flush();
    }
}