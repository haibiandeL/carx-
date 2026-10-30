package com.carx.snowcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayDeque;
import java.util.Random;

/** 贪吃蛇小游戏（真实可玩） */
public class SnakeGame extends View {
    public interface Listener { void onScore(int score); void onGameOver(int score); }

    private final int COLS = 18, ROWS = 12;
    private final ArrayDeque<Point> snake = new ArrayDeque<>();
    private int dirX = 1, dirY = 0;          // 当前方向
    private int pendingX = 1, pendingY = 0;  // 待应用方向（队列防连按反向）
    private Point food;
    private final Random random = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private boolean gameOver = false;
    private int score = 0;
    private int speedMs = 220;
    private Listener listener;
    private int accentColor = Color.parseColor("#9C5F54");
    private int themeIndex = 0;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (running) step();
            handler.postDelayed(this, speedMs);
        }
    };

    private static class Point { int x, y; Point(int x, int y) { this.x = x; this.y = y; } }

    public SnakeGame(Context context) {
        super(context);
        reset();
    }

    public void setThemeIndex(int index) {
        themeIndex = index;
        accentColor = ThemeManager.accent(index);
        invalidate();
    }

    public void setListener(Listener l) { listener = l; }

    public void reset() {
        snake.clear();
        snake.add(new Point(COLS/2, ROWS/2));
        snake.add(new Point(COLS/2 - 1, ROWS/2));
        snake.add(new Point(COLS/2 - 2, ROWS/2));
        dirX = 1; dirY = 0; pendingX = 1; pendingY = 0;
        score = 0; gameOver = false; speedMs = 220;
        spawnFood();
        if (listener != null) listener.onScore(score);
        invalidate();
    }

    public void start() {
        if (gameOver) reset();
        if (!running) { running = true; handler.removeCallbacks(tick); handler.post(tick); }
    }

    public void pause() {
        running = false;
        handler.removeCallbacks(tick);
    }

    public void setDirection(int dx, int dy) {
        // 不允许直接反向
        if (dx == -dirX && dy == -dirY) return;
        pendingX = dx; pendingY = dy;
    }

    private void spawnFood() {
        while (true) {
            Point p = new Point(random.nextInt(COLS), random.nextInt(ROWS));
            boolean hit = false;
            for (Point s : snake) if (s.x == p.x && s.y == p.y) { hit = true; break; }
            if (!hit) { food = p; break; }
        }
    }

    private void step() {
        dirX = pendingX; dirY = pendingY;
        Point head = snake.peekFirst();
        int nx = head.x + dirX, ny = head.y + dirY;
        // 撞墙
        if (nx < 0 || ny < 0 || nx >= COLS || ny >= ROWS) { onOver(); return; }
        // 撞自己
        for (Point s : snake) if (s.x == nx && s.y == ny) { onOver(); return; }
        snake.addFirst(new Point(nx, ny));
        if (nx == food.x && ny == food.y) {
            score++;
            speedMs = Math.max(110, speedMs - 5);
            if (listener != null) listener.onScore(score);
            spawnFood();
        } else {
            snake.removeLast();
        }
        invalidate();
    }

    private void onOver() {
        running = false;
        gameOver = true;
        if (listener != null) listener.onGameOver(score);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float cellW = w / COLS, cellH = h / ROWS;

        // 背景棋盘
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.parseColor("#141414"));
        canvas.drawRect(0, 0, w, h, bgPaint);
        Paint gridPaint = new Paint();
        gridPaint.setColor(Color.parseColor("#1E1E1E"));
        for (int i = 1; i < COLS; i++) canvas.drawLine(i*cellW, 0, i*cellW, h, gridPaint);
        for (int j = 1; j < ROWS; j++) canvas.drawLine(0, j*cellH, w, j*cellH, gridPaint);

        // 食物
        if (food != null) {
            Paint foodPaint = new Paint();
            foodPaint.setColor(accentColor);
            float fx = food.x * cellW + cellW/2, fy = food.y * cellH + cellH/2;
            canvas.drawCircle(fx, fy, Math.min(cellW, cellH) * 0.32f, foodPaint);
            foodPaint.setColor(Color.WHITE);
            canvas.drawCircle(fx - cellW*0.08f, fy - cellH*0.1f, Math.min(cellW, cellH)*0.07f, foodPaint);
        }

        // 蛇
        Paint bodyPaint = new Paint();
        bodyPaint.setColor(ThemeManager.isDarkTheme(themeIndex) ? Color.parseColor("#3A9E6D") : Color.parseColor("#2E7D5B"));
        Paint headPaint = new Paint();
        headPaint.setColor(accentColor);
        int idx = 0;
        for (Point p : snake) {
            Paint pp = (idx == 0) ? headPaint : bodyPaint;
            float pad = Math.min(cellW, cellH) * 0.08f;
            RectF rect = new RectF(p.x*cellW + pad, p.y*cellH + pad,
                    (p.x+1)*cellW - pad, (p.y+1)*cellH - pad);
            canvas.drawRoundRect(rect, cellW*0.15f, cellH*0.15f, pp);
            idx++;
        }
        // 蛇眼
        if (!snake.isEmpty()) {
            Point head = snake.peekFirst();
            Paint eye = new Paint(); eye.setColor(Color.WHITE);
            float cx = head.x*cellW + cellW/2, cy = head.y*cellH + cellH/2;
            float r = Math.min(cellW, cellH)*0.09f;
            canvas.drawCircle(cx + dirX*r*1.5f - r*0.5f, cy + dirY*r*1.5f - r*0.5f, r, eye);
            canvas.drawCircle(cx + dirX*r*1.5f + r*0.5f, cy + dirY*r*1.5f + r*0.5f, r, eye);
        }

        if (gameOver) {
            Paint dim = new Paint(); dim.setColor(0x88000000);
            canvas.drawRect(0, 0, w, h, dim);
            Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
            txt.setColor(Color.WHITE); txt.setTextSize(h * 0.09f); txt.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("GAME OVER  ·  " + score, w/2, h/2 - h*0.05f, txt);
            txt.setTextSize(h * 0.05f); txt.setColor(Color.parseColor("#AAAAAA"));
            canvas.drawText("tap to restart", w/2, h/2 + h*0.06f, txt);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            if (gameOver) { reset(); start(); return true; }
            float x = event.getX(), y = event.getY();
            float cx = getWidth()/2f, cy = getHeight()/2f;
            float dx = x - cx, dy = y - cy;
            if (Math.abs(dx) > Math.abs(dy)) setDirection(dx > 0 ? 1 : -1, 0);
            else setDirection(0, dy > 0 ? 1 : -1);
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(tick);
        running = false;
    }
}