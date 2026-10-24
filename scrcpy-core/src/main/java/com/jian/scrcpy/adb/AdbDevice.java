package com.jian.scrcpy.adb;

public record AdbDevice(String serial, String state, String model, String transport) {
    public boolean ready() {
        return "device".equals(state);
    }

    public String displayName() {
        String label = model == null || model.isBlank() ? serial : model;
        return label + " [" + serial + "]";
    }
}