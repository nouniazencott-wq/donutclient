package com.example.donutclient.module;

public enum Category {
    WORLD("World"),
    RENDER("Render"),
    MOVEMENT("Movement"),
    HUD("HUD");

    public final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }
}
