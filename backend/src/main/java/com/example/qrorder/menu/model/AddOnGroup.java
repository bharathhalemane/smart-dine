package com.example.qrorder.menu.model;

import java.util.List;

/** A set of optional extras with a rule: the customer picks between minSelect and maxSelect of them. */
public record AddOnGroup(String id, String name, int minSelect, int maxSelect, List<AddOnOption> options) {
}