package com.gather.client;

public class ListNode {
    public String itemId;
    public int needed;
    public int depth;
    public boolean broken;
    public boolean inventoryCraftable;
    public int baseline   = 0;
    public boolean collapsed = false;

    public ListNode() {}

    public ListNode(String itemId, int needed, int depth) {
        this.itemId = itemId;
        this.needed = needed;
        this.depth = depth;
        this.broken = false;
    }

    public ListNode copy() {
        ListNode c = new ListNode(itemId, needed, depth);
        c.broken            = broken;
        c.inventoryCraftable = inventoryCraftable;
        c.baseline          = baseline;
        c.collapsed         = collapsed;
        return c;
    }
}
