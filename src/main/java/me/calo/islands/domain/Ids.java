package me.calo.islands.domain;

public final class Ids {
    private Ids() {}

    public static void require(String id) {
        if (id == null || !id.matches("[a-z][a-z0-9_]{1,63}")) {
            throw new IllegalArgumentException("ID must contain 2-64 lowercase letters, digits or underscores, starting with a letter");
        }
    }
}
