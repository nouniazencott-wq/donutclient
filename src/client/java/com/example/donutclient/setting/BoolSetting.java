package com.example.donutclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class BoolSetting extends Setting {
    private boolean value;

    public BoolSetting(String name, boolean def) {
        super(name);
        this.value = def;
    }

    public boolean get() {
        return value;
    }

    public void set(boolean v) {
        value = v;
    }

    public void toggle() {
        value = !value;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement json) {
        value = json.getAsBoolean();
    }

    @Override
    public String display() {
        return value ? "ON" : "OFF";
    }
}
