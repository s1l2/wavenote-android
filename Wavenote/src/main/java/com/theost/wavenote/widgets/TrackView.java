package com.theost.wavenote.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.theost.wavenote.R;

import java.util.List;

public class TrackView extends View {

    private int waveColor;
    private int middleLineColor;
    private int playLineColor;
    private int dividerLineColor;

    private Paint paint;
    private List<Double> frameGains;
    private int playingFramePos = 0;

    private int playLineWidth;
    private int waveMinHeight;
    private int waveMinWidth;
    private int waveSpaceHeight;
    private int waveSpaceWidth;

    private boolean isRecording;

    public TrackView(Context context) {
        this(context, null);
    }

    public TrackView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TrackView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        paint = new Paint();
        dividerLineColor = context.getResources().getColor(R.color.divider_track_color);
        waveColor = context.getResources().getColor(R.color.wave_line_color_primary);
        middleLineColor = context.getResources().getColor(R.color.wave_middle_line_color);
        playLineColor = context.getResources().getColor(R.color.wave_play_line_color);
        isRecording = false;
        playLineWidth = 1;
        waveMinHeight = 0;
        waveMinWidth = 0;
        waveSpaceWidth = 0;
    }

    public void setStyle(int waveMinHeight, int waveMinWidth, int waveSpaceHeight, int waveSpaceWidth, int playLineWidth) {
        this.waveMinHeight = waveMinHeight;
        this.waveMinWidth = waveMinWidth;
        this.waveSpaceHeight = waveSpaceHeight;
        this.waveSpaceWidth = waveSpaceWidth;
        this.playLineWidth = playLineWidth;
    }

    public void setWaveData(List<Double> frameGains) {
        this.frameGains = frameGains;
        invalidate();
    }

    public void setPlayingFrame(int position) {
        playingFramePos = position;
        invalidate();
    }

    public void setRecording(boolean isRecording) {
        this.isRecording = isRecording;
        int color = R.color.wave_line_color_primary;
        if (isRecording) color = R.color.wave_line_recording_color;
        waveColor = getContext().getResources().getColor(color);
        invalidate();
    }

    public int frameCount() {
        return frameGains.size();
    }

    public int playingFramePosition() {
        return playingFramePos;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        //draw bottom line
        paint.setColor(dividerLineColor);
        canvas.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1, paint);

        int frameWidth = frameGains.size();

        int canvasHeight = getHeight();
        int canvasWidth = getWidth();

        paint.setColor(middleLineColor);

        int lineStartX = getPaddingLeft();
        int lineStartY = getHeight() / 2;

        // draw middle line
        canvas.drawLine(lineStartX, lineStartY, getWidth() - getPaddingRight(), lineStartY, paint);

        if (frameGains == null || frameGains.size() == 0) {
            return;
        }

        final int playingLineStopX = canvasWidth - 10;
        final int playingX = Math.min(playingFramePos, playingLineStopX);

        // draw waveform
        paint.setColor(waveColor);
        final double halfCanvasHeight = 0.5 * canvasHeight;

        int waveOffset, waveLen;

        if (playingFramePos < frameWidth) {
            if (playingFramePos <= playingLineStopX) {
                waveOffset = 0;
                waveLen = Math.min(frameWidth, canvasWidth);
            } else {
                waveOffset = ((playingFramePos - playingLineStopX) / (waveMinWidth + waveSpaceWidth)) * (waveMinWidth + waveSpaceWidth);
                waveLen = (frameWidth - playingFramePos) > playingLineStopX ? canvasWidth : (frameWidth - playingFramePos + playingLineStopX);
            }
        } else {
            if (frameWidth + canvasWidth > playingFramePos) {
                if (playingFramePos <= canvasWidth) {
                    waveOffset = 0;
                    waveLen = frameWidth;
                } else {
                    waveOffset = ((playingFramePos - playingLineStopX) / (waveMinWidth + waveSpaceWidth)) * (waveMinWidth + waveSpaceWidth);
                    waveLen = frameWidth - waveOffset;
                }
            } else {
                waveOffset = frameWidth;
                waveLen = 0;
            }
        }

        if (!isRecording) waveLen = canvasWidth;

        double prev_am = waveMinHeight;
        if (waveOffset > 0 && waveOffset < frameGains.size()) frameGains.get(waveOffset - waveMinWidth - waveSpaceWidth);

        for (int i = 0; i < waveLen; i += waveMinWidth + waveSpaceWidth) {
            boolean isAudio = waveOffset + i < frameGains.size();
            double am;
            if (isAudio) {
                am = halfCanvasHeight * frameGains.get(waveOffset + i);
                if (am < waveMinHeight) am = waveMinHeight;
            } else {
                isAudio = false;
                am = waveSpaceHeight;
            }
            am = (prev_am + am) / 2;
            prev_am = am;

            int waveX = getPaddingLeft() + i;
            int startY = (int) (halfCanvasHeight - am);
            int stopY = (int) (halfCanvasHeight + am);
            canvas.drawRoundRect(waveX, startY, waveMinWidth + waveX, (float) (halfCanvasHeight + am), 50.0f, 50.0f, paint);
            if (!isAudio) prev_am = waveSpaceHeight;
        }

        //draw playing line
        paint.setColor(playLineColor);
        canvas.drawRect(playingX, 0, playingX + playLineWidth, canvasHeight, paint);
    }
}
