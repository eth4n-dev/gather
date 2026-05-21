package com.gather.client;

import java.util.List;

public class ListNode {
    public String itemId;
    public int needed;
    public int depth;
    public boolean broken;
    public boolean inventoryCraftable;
    public int baseline   = 0;
    public boolean collapsed = false;
    /** Non-null when this node represents an alternatives group (any item counts toward goal). */
    public List<String> alternatives;
    /** Index of the currently selected alternative shown in the HUD (0 = first). */
    public int selectedAlt = 0;
    /** When true, children of this node are excluded from HUD goals (node acts as effective leaf). */
    public boolean childrenDisabled = false;
    /** When true, this goal accepts any wood type (counts all variants via isSameWoodFamily). */
    public boolean anyWoodType = false;
    /** Item IDs for rotating icon display when anyWoodType=true. */
    public List<String> woodVariants;
    /** Human-readable label when anyWoodType=true, e.g. "Any Log". */
    public String woodDisplayName;

    public ListNode() {}

    public ListNode(String itemId, int needed, int depth) {
        this.itemId = itemId;
        this.needed = needed;
        this.depth = depth;
        this.broken = false;
    }

    public boolean hasAlternatives() {
        return alternatives != null && alternatives.size() > 1;
    }

    public ListNode copy() {
        ListNode c = new ListNode(itemId, needed, depth);
        c.broken            = broken;
        c.inventoryCraftable = inventoryCraftable;
        c.baseline          = baseline;
        c.collapsed         = collapsed;
        c.alternatives      = alternatives;
        c.selectedAlt       = selectedAlt;
        c.childrenDisabled  = childrenDisabled;
        c.anyWoodType       = anyWoodType;
        c.woodVariants      = woodVariants;
        c.woodDisplayName   = woodDisplayName;
        return c;
    }
}
