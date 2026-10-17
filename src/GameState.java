/**
 * Holds the state shared by all player threads: who has won, and how many
 * players have checked their starting hand.
 * All methods are synchronized, so only one thread can use them at a time.
 */
public class GameState {

    // The winner's player number (0 means nobody has won yet).
    private int winner = 0;

    // How many players have finished checking their starting hand.
    private int checkedPlayers = 0;

    // How many players there are in total.
    private final int numberOfPlayers;

    /**
     * Creates the shared state for the given number of players.
     * Throws IllegalArgumentException if the number is less than 1.
     */
    public GameState(int numberOfPlayers) {
        if (numberOfPlayers < 1) {
            throw new IllegalArgumentException(
                    "Number of players must be at least 1: " + numberOfPlayers);
        }
        this.numberOfPlayers = numberOfPlayers;
    }

    /**
     * Tries to make this player the winner.
     * Returns true if they won, or false if someone else already won first.
     * Throws IllegalArgumentException if the player id is less than 1.
     */
    public synchronized boolean tryDeclareWinner(int playerId) {
        if (playerId < 1) {
            throw new IllegalArgumentException(
                    "Player id must be at least 1: " + playerId);
        }
        //Asks whether the winner box is still empty.
        if (winner == 0) {
            winner = playerId;
            return true;
        }
        // If winner was already non-zero, the if is skipped and return false runs.
        // That player does not print a win message.
        return false;
    }

    /**
     * Returns the winning player's number, or 0 if nobody has won yet.
     */
    public synchronized int getWinner() {
        return winner;
    }

    /**
     * Returns true once a player has won.
     */
    public synchronized boolean isOver() {
        return winner != 0;
    }

    /**
     * Called once by each player after it has checked its starting hand.
     */
    public synchronized void markInitialCheckDone() {
        checkedPlayers++;
    }

    /**
     * Waits until every player has checked its starting hand.
     * Throws InterruptedException if the waiting thread is interrupted.
     */
    public void awaitAllInitialChecks() throws InterruptedException {
        while (!allChecked()) {
            Thread.sleep(1);
        }
    }

    /**
     * Returns true when every player has checked their starting hand.
     */
    private synchronized boolean allChecked() {
        return checkedPlayers >= numberOfPlayers;
    }
}