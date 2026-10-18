package com.example.donutclient.module;

public enum Category {
    HUD("HUD"),
    RENDER("Render"),
    MISC("Misc");

    public final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }
}
