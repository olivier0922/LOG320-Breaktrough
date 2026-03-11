import java.io.*;
import java.net.*;
import java.util.*;


class Client {
private static final int SIZE = 8;
private static final int EMPTY = 0;
private static final int RED = 1;
private static final int BLACK = 2;
private static final int SEARCH_DEPTH = 3;

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

public static void main(String[] args) {
Socket myClient;
BufferedInputStream input;
BufferedOutputStream output;
int[][] board = new int[SIZE][SIZE];
int myColor = RED;
Move lastMoveSent = null;

try {
myClient = new Socket("localhost", 8888);
input = new BufferedInputStream(myClient.getInputStream());
output = new BufferedOutputStream(myClient.getOutputStream());

while (true) {
char cmd = (char) input.read();
System.out.println("Commande serveur: " + cmd);

if (cmd == '1') {
myColor = RED;
readBoardFromServer(input, board);
System.out.println("Nouvelle partie! Vous jouez Rouge.");
Move move = chooseBestMove(board, myColor, SEARCH_DEPTH);
if (move != null) {
move.captured = board[move.toX][move.toY];
applyMove(board, move);
lastMoveSent = move;
sendMove(output, move);
}
}

if (cmd == '2') {
myColor = BLACK;
readBoardFromServer(input, board);
System.out.println("Nouvelle partie! Vous jouez Noir.");
}

if (cmd == '3') {
String opponentMoveText = readServerPayload(input, 64);
System.out.println("Dernier coup adversaire: " + opponentMoveText);

Move opponentMove = parseMove(opponentMoveText);
if (opponentMove != null) {
applyMove(board, opponentMove);
}

Move move = chooseBestMove(board, myColor, SEARCH_DEPTH);
if (move != null) {
move.captured = board[move.toX][move.toY];
applyMove(board, move);
lastMoveSent = move;
sendMove(output, move);
}
}

if (cmd == '4') {
System.out.println("Coup invalide. Nouvelle tentative.");
if (lastMoveSent != null) {
undoMove(board, lastMoveSent, myColor, lastMoveSent.captured);
}
List<Move> legalMoves = generateMoves(board, myColor);
if (!legalMoves.isEmpty()) {
Move move = legalMoves.get(0);
move.captured = board[move.toX][move.toY];
applyMove(board, move);
lastMoveSent = move;
sendMove(output, move);
}
}

if (cmd == '5') {
String payload = readServerPayload(input, 64);
System.out.println("Partie terminee. Dernier coup: " + payload);
break;
}
}
} catch (IOException e) {
System.out.println(e);
}
}

private static void readBoardFromServer(BufferedInputStream input, int[][] board) throws IOException {
String boardText = readServerPayload(input, 2048);
String[] boardValues = boardText.trim().split("\\s+");
int x = 0;
int y = 0;
for (int i = 0; i < boardValues.length && y < SIZE; i++) {
if (boardValues[i].isEmpty()) {
continue;
}
board[x][y] = Integer.parseInt(boardValues[i]);
x++;
if (x == SIZE) {
x = 0;
y++;
}
}
}

private static String readServerPayload(BufferedInputStream input, int maxBytes) throws IOException {
try {
Thread.sleep(60);
} catch (InterruptedException e) {
Thread.currentThread().interrupt();
}
int size = input.available();
if (size <= 0) {
return "";
}
int bytesToRead = Math.min(size, maxBytes);
byte[] buffer = new byte[bytesToRead];
input.read(buffer, 0, bytesToRead);
return new String(buffer).trim();
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

private static Move chooseBestMove(int[][] board, int myColor, int depth) {
List<Move> moves = generateMoves(board, myColor);
if (moves.isEmpty()) {
return null;
}

int bestScore = Integer.MIN_VALUE;
Move bestMove = moves.get(0);
int opponent = (myColor == RED) ? BLACK : RED;

for (Move move : moves) {
int captured = board[move.toX][move.toY];
applyMove(board, move);
int score = alphaBeta(board, depth - 1, Integer.MIN_VALUE, Integer.MAX_VALUE, false, myColor, opponent);
undoMove(board, move, myColor, captured);

if (score > bestScore) {
bestScore = score;
bestMove = move;
}
}

return bestMove;
}

private static int alphaBeta(int[][] board, int depth, int alpha, int beta, boolean maximizing, int myColor, int currentColor) {
if (depth == 0 || hasWinner(board, RED) || hasWinner(board, BLACK)) {
return evaluateBoard(board, myColor);
}

List<Move> moves = generateMoves(board, currentColor);
if (moves.isEmpty()) {
return evaluateBoard(board, myColor);
}

int nextColor = (currentColor == RED) ? BLACK : RED;

if (maximizing) {
int value = Integer.MIN_VALUE;
for (Move move : moves) {
int captured = board[move.toX][move.toY];
applyMove(board, move);
value = Math.max(value, alphaBeta(board, depth - 1, alpha, beta, false, myColor, nextColor));
undoMove(board, move, currentColor, captured);
alpha = Math.max(alpha, value);
if (alpha >= beta) {
break;
}
}
return value;
} else {
int value = Integer.MAX_VALUE;
for (Move move : moves) {
int captured = board[move.toX][move.toY];
applyMove(board, move);
value = Math.min(value, alphaBeta(board, depth - 1, alpha, beta, true, myColor, nextColor));
undoMove(board, move, currentColor, captured);
beta = Math.min(beta, value);
if (alpha >= beta) {
break;
}
}
return value;
}
}

private static int evaluateBoard(int[][] board, int myColor) {
int opponent = (myColor == RED) ? BLACK : RED;
int myPieces = 0;
int opponentPieces = 0;
int myAdvance = 0;
int opponentAdvance = 0;

for (int x = 0; x < SIZE; x++) {
for (int y = 0; y < SIZE; y++) {
if (board[x][y] == myColor) {
myPieces++;
myAdvance += (myColor == RED) ? y : (SIZE - 1 - y);
} else if (board[x][y] == opponent) {
opponentPieces++;
opponentAdvance += (opponent == RED) ? y : (SIZE - 1 - y);
}
}
}

if (hasWinner(board, myColor)) {
return 100000;
}
if (hasWinner(board, opponent)) {
return -100000;
}

return (myPieces - opponentPieces) * 100 + (myAdvance - opponentAdvance) * 10;
}

private static boolean hasWinner(int[][] board, int color) {
int targetRow = (color == RED) ? SIZE - 1 : 0;
for (int x = 0; x < SIZE; x++) {
if (board[x][targetRow] == color) {
return true;
}
}

int opponent = (color == RED) ? BLACK : RED;
if (countPieces(board, opponent) == 0) {
return true;
}
return generateMoves(board, opponent).isEmpty();
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
String digits = moveText.replaceAll("[^0-9]", "");
if (digits.length() < 4) {
return null;
}
int fromX = Character.getNumericValue(digits.charAt(0));
int fromY = Character.getNumericValue(digits.charAt(1));
int toX = Character.getNumericValue(digits.charAt(2));
int toY = Character.getNumericValue(digits.charAt(3));

if (fromX < 0 || fromX >= SIZE || toX < 0 || toX >= SIZE || fromY < 0 || fromY >= SIZE || toY < 0 || toY >= SIZE) {
return null;
}
return new Move(fromX, fromY, toX, toY);
}

private static void sendMove(BufferedOutputStream output, Move move) throws IOException {
String formatted = "" + move.fromX + move.fromY + move.toX + move.toY;
System.out.println("Coup joue: " + formatted);
output.write(formatted.getBytes(), 0, formatted.length());
output.flush();
}
}
