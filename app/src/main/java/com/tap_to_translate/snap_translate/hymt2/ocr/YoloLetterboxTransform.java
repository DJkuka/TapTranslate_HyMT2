package com.tap_to_translate.snap_translate.hymt2.ocr;

/** Exact forward/inverse mapping for a resized image centred in a model input canvas. */
public final class YoloLetterboxTransform {
    private final int sourceWidth, sourceHeight;
    private final int inputWidth, inputHeight;
    private final int resizedWidth, resizedHeight;
    private final float padLeft, padTop;
    private final float scaleX, scaleY;

    public YoloLetterboxTransform(
            int sourceWidth, int sourceHeight,
            int inputWidth, int inputHeight,
            int resizedWidth, int resizedHeight,
            float padLeft, float padTop) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || inputWidth <= 0 || inputHeight <= 0
                || resizedWidth <= 0 || resizedHeight <= 0
                || resizedWidth > inputWidth || resizedHeight > inputHeight
                || !Float.isFinite(padLeft) || !Float.isFinite(padTop)
                || padLeft < 0f || padTop < 0f
                || padLeft + resizedWidth > inputWidth + 0.01f
                || padTop + resizedHeight > inputHeight + 0.01f) {
            throw new IllegalArgumentException("Invalid letterbox dimensions or padding");
        }
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.resizedWidth = resizedWidth;
        this.resizedHeight = resizedHeight;
        this.padLeft = padLeft;
        this.padTop = padTop;
        this.scaleX = (float) resizedWidth / sourceWidth;
        this.scaleY = (float) resizedHeight / sourceHeight;
    }

    public float toSourceX(float inputX) { return (inputX - padLeft) / scaleX; }
    public float toSourceY(float inputY) { return (inputY - padTop) / scaleY; }
    public int getInputWidth() { return inputWidth; }
    public int getInputHeight() { return inputHeight; }
    public int getSourceWidth() { return sourceWidth; }
    public int getSourceHeight() { return sourceHeight; }
    public int getResizedWidth() { return resizedWidth; }
    public int getResizedHeight() { return resizedHeight; }
    public float getPadLeft() { return padLeft; }
    public float getPadTop() { return padTop; }
    public float getScaleX() { return scaleX; }
    public float getScaleY() { return scaleY; }
}
