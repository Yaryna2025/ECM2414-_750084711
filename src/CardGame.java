import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the multi-threaded card game.
 * Sets up the number of players, then loads the card pack, deals hands and
 * decks,
 * and starts player threads. When finished, it outputs individual player
 * and deck files.
 */
public class CardGame {

    // Create constant static strings.
    // These strings are shared across the class and can't be changed.
    private static final String PLAYERS_PROMPT = "Please enter the number of players:";
    private static final String PACK_PROMPT = "Please enter location of pack to load:";

    private final int numberOfPlayers;
    // Directory where all output log files will be written.
    private final Path outputDirectory;
    // Centralized and thread-safe object to coordinate game-over flags, winner
    // declarations, and the synchronization barrier,
    // as players run on separate threads.
    private final GameState state;
    // Storing all the card decks in the system.
    private final List<CardDeck> decks = new ArrayList<>();
    // Holding all the active player thread objects.
    private final List<Player> players = new ArrayList<>();
    // This guarantees that a CardGame instance can only be played once.
    private final AtomicBoolean played = new AtomicBoolean(false);

    /**
     * Sets up the game by creating all decks and players.
     * Deals four cards to each player one by one in a circle,
     * then deals the remaining cards to the decks in the same round-robin way.
     */
    public CardGame(int numberOfPlayers, List<Card> pack, Path outputDirectory) {
        // Check that the user specified a valid and positive number of players.
        if (numberOfPlayers < 1) {
            throw new IllegalArgumentException(
                    "Number of players must be at least 1: " + numberOfPlayers);
        }
        // make sure that both the input card pack and the outputDirectory path are not
        // null.
        Objects.requireNonNull(pack, "pack must not be null");
        Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");

        // Verify pack size (4 cards per player's hand + 4 cards per deck initially,
        // requiring 8 * n cards total). The (long) cast prevents potential integer
        // overflow.
        if ((long) pack.size() != 8L * numberOfPlayers) {
            throw new IllegalArgumentException("A pack for " + numberOfPlayers
                    + " players must contain " + (8L * numberOfPlayers)
                    + " cards but has " + pack.size());
        }
        this.numberOfPlayers = numberOfPlayers;
        this.outputDirectory = outputDirectory;
        this.state = new GameState(numberOfPlayers);

        // Create the card decks (one per player in a ring).
        for (int i = 1; i <= numberOfPlayers; i++) {
            decks.add(new CardDeck(i));
        }

        // Connect players to their left draw deck and right discard deck.
        for (int i = 1; i <= numberOfPlayers; i++) {
            CardDeck drawDeck = decks.get(i - 1);
            CardDeck discardDeck = decks.get(i % numberOfPlayers);
            players.add(new Player(i, drawDeck, discardDeck, state, outputDirectory));
        }

        // Deal each player the initial hand 4 cards round-robin from the pack.
        int handCards = 4 * numberOfPlayers;
        for (int k = 0; k < handCards; k++) {
            players.get(k % numberOfPlayers).addToHand(pack.get(k));
        }

        // Deal the remaining pack cards into the decks round-robin
        for (int k = handCards; k < pack.size(); k++) {
            decks.get((k - handCards) % numberOfPlayers).addCard(pack.get(k));
        }
    }

    /**
     * Returns the list of players starting with Player 1.
     * Returns a read-only list of the players, so outside classes can look at it
     * but can't change it.
     */
    public List<Player> getPlayers() {
        return Collections.unmodifiableList(players);
    }

    /**
     * Returns the list of decks starting with Deck 1.
     * Returns a read-only list of the players, protecting the decks from being
     * accidentally modified.
     */
    public List<CardDeck> getDecks() {
        return Collections.unmodifiableList(decks);
    }

    /** Returns the total number of players in the game. */
    public int getNumberOfPlayers() {
        return numberOfPlayers;
    }

    /**
     * Plays the game: starts every player thread, waits for all of them to
     * finish, then writes one output file per deck.
     * May be called only once.
     */
    public int play() throws IOException, InterruptedException {

        // Ensure the game can only be played once using an atomic guard.
        if (!played.compareAndSet(false, true)) {
            throw new IllegalStateException("This game has already been played");
        }

        // Wrap each player in their own thread.
        List<Thread> threads = new ArrayList<>();
        for (Player player : players) {
            threads.add(new Thread(player, "player-" + player.getId()));
        }

        // Start all player threads concurrently.
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all player threads to complete their execution.
        for (Thread thread : threads) {
            thread.join();
        }

        // Write the final contents of all decks to files.
        writeDeckFiles();
        return state.getWinner();
    }

    // Saves the final state of each card deck into output files.
    private void writeDeckFiles() throws IOException {
        for (CardDeck deck : decks) {
            String contents = deck.getContents();
            String line = "deck" + deck.getId() + " contents:"
                    + (contents.isEmpty() ? "" : " " + contents);
            Files.writeString(
                    outputDirectory.resolve("deck" + deck.getId() + "_output.txt"),
                    line + System.lineSeparator());
        }
    }

    // ------------------------------------------------------------------
    // Input handling
    // ------------------------------------------------------------------

    /**
     * Parses and validates the text typed by the user to determine the number of
     * players.
     * Ensures the input is a positive whole number of at least 1.
     * Throws IllegalArgumentException if the text is invalid.
     */
    static int parseNumberOfPlayers(String text) {

        // Check for null input.
        if (text == null) {
            throw new IllegalArgumentException("No number of players given");
        }

        // Remove whitespace and validate.
        String trimmed = text.trim();
        if (!trimmed.matches("[0-9]+")) {
            throw new IllegalArgumentException(
                    "'" + trimmed + "' is not a positive whole number");
        }

        // Convert string to integer with safety check for overflow.
        int n;
        try {
            n = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + trimmed + "' is too large");
        }

        // Enforce game rules that there must be at least 1 player.
        if (n < 1) {
            throw new IllegalArgumentException("There must be at least 1 player");
        }
        return n;
    }

    /**
     * Reads and checks the pack file to make sure it has the correct 8n lines.
     * Ensures every line contains a valid positive number.
     * Returns the cards in order, starting from the top of the pack.
     * Throws IOException if the file cannot be read.
     * Throws IllegalArgumentException if the contents are not a valid pack.
     */
    static List<Card> loadPack(Path file, int n) throws IOException {
        // Read all lines from the text file using UTF-8 encoding
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        // Verify the file has the exact total of 8 * n lines.
        if ((long) lines.size() != 8L * n) {
            throw new IllegalArgumentException("a pack for " + n + " players needs "
                    + (8L * n) + " lines but this file has " + lines.size());
        }
        List<Card> pack = new ArrayList<>();

        // Loop through each line of the file to validate and parse individual card
        // values.
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            // Check that the line only contains valid numbers.
            if (!line.matches("[0-9]+")) {
                throw new IllegalArgumentException("line " + (i + 1) + " ('" + line
                        + "') is not a non-negative integer");
            }

            // Convert the text into a number and create a Card object.
            try {
                pack.add(new Card(Integer.parseInt(line)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "line " + (i + 1) + " ('" + line + "') is too large");
            }
        }

        // Return the fully validated list of cards in file order.
        return pack;
    }

    /**
     * Asks for the number of players until a valid answer is given.
     */
    static int promptForPlayers(Scanner in, PrintStream out) {
        while (true) {
            // Print the prompt asking for player count.
            out.println(PLAYERS_PROMPT);
            String line = in.nextLine();
            try {
                // Attempt to parse the input, will return successfully if valid.
                return parseNumberOfPlayers(line);
            } catch (IllegalArgumentException e) {
                // If invalid, catch the error, print a message, and then loop again.
                out.println("Invalid number of players: " + e.getMessage() + ".");
            }
        }
    }

    /**
     * Asks for a pack location until a valid pack file is given.
     */
    static List<Card> promptForPack(Scanner in, PrintStream out, int n) {
        while (true) {
            // Print the prompt asking for pack location.
            out.println(PACK_PROMPT);
            String line = in.nextLine().trim();
            try {
                // Attempt to load and validate the pack file.
                return loadPack(Path.of(line), n);
            } catch (IOException e) {
                // Catch file reading errors and ask the user to try again.
                out.println("Could not read that pack file (" + e.getMessage()
                        + "). Please try again.");
            } catch (IllegalArgumentException e) {
                // Catch validation errors if the file content is incorrect.
                out.println("Invalid pack: " + e.getMessage()
                        + ". Please enter a valid pack file.");
            }
        }
    }

    /**
     * Starts the program.
     * Asks the user for the number of players and the pack file location.
     * Sets up and begins the game.
     */
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        try {
            // Ask the user for the number of players and a valid card pack.
            int n = promptForPlayers(in, System.out);
            List<Card> pack = promptForPack(in, System.out, n);
            // Set up the game and start playing.
            new CardGame(n, pack, Path.of(".")).play();
        } catch (NoSuchElementException e) {
            System.err.println("Input ended before a valid game could be set up.");
        } catch (IOException e) {
            System.err.println("Could not write the output files: " + e.getMessage());
        } catch (InterruptedException e) {
            // Mark the thread as interrupted again if it got cut off.
            Thread.currentThread().interrupt();
        }
    }
}
