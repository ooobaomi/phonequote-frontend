package com.local.phonequote;

public final class OcrTextLine {
    public final String text;
    public final int left;
    public final int top;
    public final int right;
    public final int bottom;
    public final int passIndex;

    public OcrTextLine(String text, int left, int top, int right, int bottom, int passIndex) {
        this.text = text == null ? "" : text.trim();
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.passIndex = passIndex;
    }

    public static OcrTextLine plain(String text, int index) {
        return new OcrTextLine(text, 0, index * 100, 0, index * 100 + 60, 0);
    }

    public int width() {
        return Math.max(0, right - left);
    }

    public int height() {
        return Math.max(0, bottom - top);
    }
}
