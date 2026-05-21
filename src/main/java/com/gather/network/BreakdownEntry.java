package com.gather.network;

import java.util.List;

public record BreakdownEntry(List<String> itemIds, int count) {
    public boolean isSingle() { return itemIds.size() == 1; }
    public String primary() { return itemIds.get(0); }
}
