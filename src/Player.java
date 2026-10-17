import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

/**
 * A player in the card game.
 * Each player runs on its own thread, draws from the top of its own deck.
 * Each player also discards a card to the bottom of the next deck in the ring.
 * Then the log of this player's actions is written to playerN_output.txt.
 */
public class Player implements Runnable {

    // Every player must hold exactly 4 cards between turns.
    public static final int HAND_SIZE = 4;

    // Small pause time (1 millisecond) used if a draw deck is temporarily empty.
    private static final long RETRY_PAUSE_NANOS = 1_000_000L;

    /**
     * Represents what happened during one turn (drawing and discarding).
     */
    public record Turn(Card drawn, Card discarded, String handAfter) {
    }

    // The player's number, which is also their preferred card value.
    private final int id;
    private final CardDeck drawDeck;
    private final CardDeck discardDeck;
    // Ordered lock 1 (prevents deadlock)
    private final CardDeck firstLock;
    // Ordered lock 2 (prevents deadlock)
    private final CardDeck secondLock;
    private final GameState state;
    private final Path outputFile;
    // The player's hand of cards
    private final List<Card> hand = new ArrayList<>();

    /**
     * Constructor: Sets up the player, assigns their decks, and establishes
     * a strict lock order to prevent deadlocks.
     */
    public Player(int id, CardDeck drawDeck, CardDeck discardDeck,
            GameState state, Path outputDirectory) {
        if (id < 1) {
            throw new IllegalArgumentException(
                    "Player id must be at least 1: " + id);
        }
        this.id = id;
        this.drawDeck = Objects.requireNonNull(drawDeck, "drawDeck must not be null");
        this.discardDeck = Objects.requireNonNull(discardDeck, "discardDeck must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.outputFile = Objects.requireNonNull(outputDirectory, "outputDirectory must not be null")
                .resolve("player" + id + "_output.txt");

        // DEADLOCK PREVENTION STRATEGY:
        // Always acquire locks on decks in ascending order of their ID numbers.
        // If Player A and Player B try to lock Deck 1 and Deck 2 at the same time,
        // locking the lower ID first ensures they never get stuck waiting on each
        // other.
        if (this.drawDeck.getId() <= this.discardDeck.getId()) {
            this.firstLock = this.drawDeck;
            this.secondLock = this.discardDeck;
        } else {
            this.firstLock = this.discardDeck;
            this.secondLock = this.drawDeck;
        }
    }

    /**
     * This gives back the player's ID number, which is also the card value that
     * they are trying to collect.
     */
    public int getId() {
        return id;
    }

    /**
     * Puts a dealt card into the player's hand during game setup (making sure they
     * don't get more than 4 cards).
     */
    public synchronized void addToHand(Card card) {
        Objects.requireNonNull(card, "card must not be null");
        if (hand.size() >= HAND_SIZE) {
            throw new IllegalStateException("Player " + id + " already holds " + HAND_SIZE + " cards");
        }
        hand.add(card);
    }

    /**
     * Returns a copy of the player's current cards, so callers cannot change the hand.
     */
    public synchronized List<Card> getHand() {
        return List.copyOf(hand);
    }

    /**
     * Returns the hand as values separated by single spaces.
     */
    public synchronized String getHandString() {
        StringBuilder sb = new StringBuilder();
        for (Card card : hand) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(card.getValue());
        }
        return sb.toString();
    }

    /**
     * Checks if the player has won (all 4 cards in hand have the exact same value).
     */
    public synchronized boolean hasWinningHand() {
        if (hand.size() != HAND_SIZE) {
            return false;
        }
        int first = hand.get(0).getValue();
        for (Card card : hand) {
            if (card.getValue() != first) {
                return false;
            }
        }
        return true;
    }

    /**
     * Plays one complete turn by drawing a card from the left deck
     * and discarding a non-preferred card to the right deck.
     * Returns empty (and changes nothing) if the draw deck has no cards.
     */
    public Optional<Turn> takeTurn() {
        // Lock both decks in strict numerical order to guarantee thread safety and
        // atomicity.
        // This ensures nobody else can touch these decks while the player is drawing
        // and discarding.
        synchronized (firstLock) {
            synchronized (secondLock) {
                // Double-check that the player is allowed to take a turn right now.
                requireReadyForTurn();

                // Draw a card from the left deck.
                Card drawn = drawDeck.drawCard();
                if (drawn == null) {
                    // If deck is empty, try again later.
                    return Optional.empty();
                }
                // Add the new card to hand, and choose a non-preferred card to get rid of.
                Card discarded = swapIntoHand(drawn);

                // Drop that discarded card onto the bottom of the right deck.
                discardDeck.addCard(discarded);

                // Return a summary of what happened this turn.
                return Optional.of(new Turn(drawn, discarded, getHandString()));
            }
        }
    }

    /**
     * Safety check: Ensures the player has the correct number of cards
     * and hasn't already won before letting them take a turn.
     */
    private synchronized void requireReadyForTurn() {
        if (hand.size() != HAND_SIZE) {
            throw new IllegalStateException("Player " + id
                    + " must hold exactly " + HAND_SIZE + " cards to take a turn");
        }
        if (hasWinningHand()) {
            throw new IllegalStateException(
                    "Player " + id + " already holds a winning hand");
        }
    }

    /**
     * Adds the drawn card to the hand, then discards the first card (scanning
     * from the start of the hand) that is not the player's preferred value,
     * so the player still has exactly 4 cards.
     */
    private synchronized Card swapIntoHand(Card drawn) {
        // Temporarily add the newly drawn card to the hand.
        hand.add(drawn);
        // Loop through all cards in the hand one by one.
        Iterator<Card> it = hand.iterator();
        while (it.hasNext()) {
            Card candidate = it.next();
            // If the card value does NOT match the player's preferred ID, discard it.
            if (candidate.getValue() != id) {
                it.remove();
                return candidate;
            }
        }

        // Safety guard: if all cards match the preferred ID, throw an error,
        // because a player holding only preferred cards should already have won.
        throw new IllegalStateException(
                "Player " + id + " has no non-preferred card to discard");
    }

    /**
     * Plays the game on the current thread until some player has won.
     */
    @Override
    public void run() {
        boolean initialCheckReported = false;
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile)) {
            // Log initial dealt hand.
            write(writer, "player " + id + " initial hand " + getHandString());

            // Check if player won immediately upon receiving initial cards.
            if (hasWinningHand()) {
                declareWinIfFirst();
            }

            // Signal that this player has finished initial setup.
            state.markInitialCheckDone();
            initialCheckReported = true;

            // Wait until all players in the game finish their initial setup before starting
            // turns.
            state.awaitAllInitialChecks();

            while (!state.isOver()) {
                Optional<Turn> result = takeTurn();
                if (result.isEmpty()) {
                    LockSupport.parkNanos(RETRY_PAUSE_NANOS);
                    continue;
                }
                Turn turn = result.get();

                // Log turn actions to personal output file.
                write(writer, "player " + id + " draws a " + turn.drawn()
                        + " from deck " + drawDeck.getId());
                write(writer, "player " + id + " discards a " + turn.discarded()
                        + " to deck " + discardDeck.getId());
                write(writer, "player " + id + " current hand is "
                        + turn.handAfter());

                // Check if this turn resulted in a winning hand.
                if (hasWinningHand()) {
                    declareWinIfFirst();
                }
            }

            // Write final game-over summary to file.
            writeEnding(writer);
        } catch (IOException e) {
            System.err.println("player " + id + " could not write "
                    + outputFile + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Never leave the other players waiting at the start gate.
            if (!initialCheckReported) {
                state.markInitialCheckDone();
            }
        }
    }

    /**
     * Tries to claim victory for this player.
     * If nobody else has won yet, this player becomes the winner and a winning message is printed.
     */
    private void declareWinIfFirst() {
        // Try to register this player as the winner in the shared game state.
        if (state.tryDeclareWinner(id)) {
            System.out.println("player " + id + " wins");
        }
    }

    /**
     * Writes the final log entries to the player's output file when the game ends,
     * depending on whether this player won or someone else did.
     */
    private void writeEnding(BufferedWriter writer) throws IOException {
        int winner = state.getWinner();

        if (winner == id) {
            write(writer, "player " + id + " wins");
            write(writer, "player " + id + " exits");
            write(writer, "player " + id + " final hand: " + getHandString());
        } else {
            write(writer, "player " + winner + " has informed player " + id
                    + " that player " + winner + " has won");
            write(writer, "player " + id + " exits");
            write(writer, "player " + id + " hand: " + getHandString());
        }
    }

    /**
     * Writes one line to the file, then starts a new line and saves it to disk immediately.
     */
    private static void write(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.newLine();
        writer.flush();
    }
}
