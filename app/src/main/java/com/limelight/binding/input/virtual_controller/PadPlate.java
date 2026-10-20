package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;

/** The see-through plate under a skin's face buttons. Only drawn: touches go to the keys or the game. */
public class PadPlate extends VirtualControllerElement {
    public PadPlate(VirtualController controller, Context context, int elementId) {
        super(controller, context, elementId);
    }

    @Override
    protected void onElementDraw(Canvas canvas) {
        if (skin() != null) skin().plate(canvas, getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f * .97f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        return false;
    }
}
