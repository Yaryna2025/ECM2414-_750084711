import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/*
 * A thread-safe FIFO deck of cards.
 * Cards are drawn from the top and added to the bottom.
 * Methods are synchronized so only one player thread can access the deck at a time, preventing conflicts.
 */
public class CardDeck {
    //Stores the unique number of this deck.
    private final int id;
    //Creates an empty ArrayDeque to hold the actual Card objects in a FIFO line (First-In, First-Out).
    private final Deque<Card> cards = new ArrayDeque<>();

    /*
     * Runs when a new deck is created to validate that its ID is 1 or greater.
     * If ID is not valid, the program is stopped safely by throwing IllegalArgumentException.
     */
    public CardDeck(int id) {
        if (id < 1) {
            throw new IllegalArgumentException("Deck id must be at least 1: " + id);
        }
        this.id = id;
    }
    //Returns deck number from 1 to n.
    public int getId() {
        return id;
    }
    /*
    * Adds a card to the bottom (back) of the deck.
    * If card is null (empty), it crashes safely with NullPointerException.
     */
    public synchronized void addCard(Card card) {
        cards.addLast(Objects.requireNonNull(card, "card must not be null"));
    }


     // Removes and returns the card at the top (front) of the deck.
    public synchronized Card drawCard() {
        return cards.pollFirst();
    }

    //Returns how many cards are currently in the deck.
    public synchronized int size() {
        return cards.size();
    }

    //The deck has run out of cards, holds no cards anymore.
    public synchronized boolean isEmpty() {
        return cards.isEmpty();
    }

    /**
     * Returns the card values from top to bottom, separated by a single space.
     * For example: "2 4 5 5" or an empty string if the deck is empty.
     */
    public synchronized String getContents() {
        StringBuilder sb = new StringBuilder();
        for (Card card : cards) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(card.getValue());
        }
        return sb.toString();
    }
}
