package com.example.donutclient.setting;

import com.google.gson.JsonElement;

/** A named, persisted value that belongs to a module and shows up in the click GUI. */
public abstract class Setting {
    private final String name;

    protected Setting(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getId() {
        return name.toLowerCase().replace(' ', '_');
    }

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement json);

    /** Text shown on the right side of the setting row. */
    public abstract String display();
}
