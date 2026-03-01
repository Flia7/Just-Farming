package com.justfarming.visitor;

/** Represents a single item requirement from a garden visitor offer. */
public class VisitorRequirement {

    /** Display name of the item as reported by the visitor GUI (e.g. "Wheat"). */
    public final String itemName;

    /** Amount the visitor requires. */
    public final int amount;

    public VisitorRequirement(String itemName, int amount) {
        this.itemName = itemName;
        this.amount   = amount;
    }

    @Override
    public String toString() {
        return amount + "x " + itemName;
    }
}
