package com.example.donutclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** Numeric slider setting, snapped to a step. */
public class NumSetting extends Setting {
    private final double min, max, step;
    private double value;

    public NumSetting(String name, double def, double min, double max, double step) {
        super(name);
        this.min = min;
        this.max = max;
        this.step = step;
        set(def);
    }

    public double get() {
        return value;
    }

    public int getInt() {
        return (int) Math.round(value);
    }

    public void set(double v) {
        v = Math.round(v / step) * step;
        value = Math.max(min, Math.min(max, v));
    }

    /** 0..1 position of the value on the slider. */
    public double fraction() {
        return (value - min) / (max - min);
    }

    public void setFraction(double f) {
        set(min + (max - min) * Math.max(0, Math.min(1, f)));
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement json) {
        set(json.getAsDouble());
    }

    @Override
    public String display() {
        return step >= 1 ? Integer.toString(getInt()) : String.format("%.2f", value);
    }
}
