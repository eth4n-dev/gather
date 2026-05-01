package com.gather.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GatherList {
    public String id   = UUID.randomUUID().toString();
    public String name = "List";
    public List<ListNode> nodes      = new ArrayList<>();
    public List<ListNode> savedGoals = new ArrayList<>();

    public GatherList() {}

    public GatherList(String name) {
        this.id   = UUID.randomUUID().toString();
        this.name = name;
    }
}
