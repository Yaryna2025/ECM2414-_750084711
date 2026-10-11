/**
 * A playing card with a fixed number value
 * that can't be changed after it's made.
 */

public final class Card {

    /*
     * private ensures outside classes cannot tamper with the card's value directly,
     * and final guarantees the value can never be modified after creation
     * (making it immutable).
     */
    private final int value;

    /*
     * Creates a card
     * It checks if the passed value is less than 0.
     * If it is negative, it immediately crashes safely by throwing an IllegalArgumentException.
     * Otherwise, it saves the value into the card.
     */
    public Card(int value) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "Card value must be non-negative: " + value);
        }
        this.value = value;
    }

    /*
     * Value is private, so other classes cannot read it directly.
     * Because of this we need to use getValue(), a getter method that allows other classes to look at the value of the card.
     */
    public int getValue() {
        return value;
    }

    /*
     * Converts the card's integer value into a String.
     * For example: 4 into text "4".
     */
    @Override
    public String toString() {
        return Integer.toString(value);
    }

}
