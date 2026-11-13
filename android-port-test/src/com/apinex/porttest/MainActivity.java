package com.apinex.porttest;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.content.res.AssetManager;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class MainActivity extends Activity {
    private static final int PICK_GAME = 1001;
    private LinearLayout rootLayout;
    private TextView titleView;
    private TextView status;
    private SurfaceView surface;
    private FrameLayout gameFrame;
    private Button selectButton;
    private FrameLayout controls;
    private String selectedPath;
    private boolean nativeReady;
    private AudioTrack audioTrack;
    private AudioManager audioManager;
    private Thread audioThread;
    private volatile boolean audioRunning;
    private SharedPreferences controlPreferences;

    private native boolean nativeStart(String corePath, String gamePath, android.view.Surface surface);
    private native void nativeRunFrame();
    private native void nativeStop();
    private native void nativeSetInput(int button, boolean pressed);
    private native void nativeSetAnalog(int stick, int x, int y);
    private native int nativeGetAudioSampleRate();
    private native int nativeReadAudio(short[] output, int maxFrames);
    private native boolean nativeSaveState(String path);
    private native boolean nativeLoadState(String path);

    static {
        try {
            System.loadLibrary("apinex_host");
        } catch (UnsatisfiedLinkError ignored) {
            // O APK de inspeção pode ser instalado sem o core nativo.
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        rootLayout = new LinearLayout(this);
        controlPreferences = getSharedPreferences("control_layout", MODE_PRIVATE);
        LinearLayout layout = rootLayout;
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        titleView = new TextView(this);
        titleView.setText("APInex PS1 port test");
        titleView.setTextSize(26f);
        layout.addView(titleView);

        status = new TextView(this);
        status.setText("SDK 35 / ARM64\nAndroid harness: OK\n\nNúcleo de emulação: próximo passo");
        status.setTextSize(18f);
        status.setPadding(0, 32, 0, 32);
        layout.addView(status);

        gameFrame = new FrameLayout(this);
        gameFrame.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(this);
        surface.setVisibility(View.GONE);
        gameFrame.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { startNativeIfReady(holder); }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                nativeReady = false;
                stopAudio();
                nativeStop();
            }
        });

        controls = buildControls();
        controls.setVisibility(View.GONE);
        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlsParams.gravity = Gravity.BOTTOM;
        gameFrame.addView(controls, controlsParams);
        layout.addView(gameFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        selectButton = new Button(this);
        selectButton.setText("Selecionar imagem .cue");
        selectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, PICK_GAME);
            }
        });
        layout.addView(selectButton);
        setContentView(layout);
        try {
            selectedPath = copyBundledCue();
            if (selectedPath != null) {
                showGameSurface();
                status.setText("Jogo empacotado encontrado.\n\nIniciando o núcleo nativo...");
                startNativeIfReady(surface.getHolder());
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_GAME || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    getContentResolver().openInputStream(uri), "UTF-8"));
            StringBuilder cue = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && cue.length() < 512) {
                cue.append(line).append('\n');
            }
            reader.close();
            status.setText("Imagem selecionada com sucesso\n\n" + cue.toString().trim()
                    + "\n\nValidação concluída. Preparando o núcleo nativo...");
            selectedPath = copyToPrivateStorage(uri);
            showGameSurface();
            startNativeIfReady(surface.getHolder());
        } catch (Exception error) {
            status.setText("Não foi possível ler a imagem selecionada.\n\n" + error.getMessage());
        }
    }

    private String copyToPrivateStorage(Uri uri) throws Exception {
        String name = uri.getLastPathSegment();
        if (name == null || name.length() == 0) name = "selected-game.cue";
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        File directory = new File(getFilesDir(), "games");
        if (!directory.exists() && !directory.mkdirs()) throw new Exception("Não foi possível criar o diretório do jogo");
        File target = new File(directory, name);
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) throw new Exception("O provedor não abriu a imagem");
        FileOutputStream output = new FileOutputStream(target);
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        input.close();
        output.close();
        return target.getAbsolutePath();
    }

    private String copyBundledCue() throws Exception {
        AssetManager assets = getAssets();
        File directory = new File(getFilesDir(), "games");
        if (!directory.exists() && !directory.mkdirs()) throw new Exception("Não foi possível criar o diretório do jogo");
        return copyBundledPath(assets, "games", directory);
    }

    private String copyBundledPath(AssetManager assets, String assetPath, File destination) throws Exception {
        String cue = null;
        String[] names = assets.list(assetPath);
        if (names == null) return null;
        for (String name : names) {
            if (name == null || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) continue;
            String childAsset = assetPath + "/" + name;
            File target = new File(destination, name);
            String[] children = assets.list(childAsset);
            if (children != null && children.length > 0) {
                if (!target.exists() && !target.mkdirs()) throw new Exception("Não foi possível criar o subdiretório do jogo");
                String nestedCue = copyBundledPath(assets, childAsset, target);
                if (nestedCue != null) cue = nestedCue;
                continue;
            }
            InputStream input = assets.open(childAsset);
            FileOutputStream output = new FileOutputStream(target);
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            input.close();
            output.close();
            if (name.toLowerCase().endsWith(".cue")) cue = target.getAbsolutePath();
        }
        return cue;
    }

    private void startNativeIfReady(SurfaceHolder holder) {
        if (selectedPath == null || nativeReady || holder == null || holder.getSurface() == null) return;
        File core = new File(getApplicationInfo().nativeLibraryDir, "libretro.so");
        if (!core.exists()) {
            status.setText(status.getText().toString() + "\n\nCore nativo ainda não está incluído neste APK de teste.");
            return;
        }
        nativeReady = nativeStart(core.getAbsolutePath(), selectedPath, holder.getSurface());
        status.setText(nativeReady ? "Núcleo iniciado.\n\nUse os controles na tela." : "O núcleo não conseguiu abrir a imagem.");
        if (nativeReady) {
            startAudio();
        }
    }

    private void showGameSurface() {
        enterGameplayMode();
        surface.setVisibility(View.VISIBLE);
        controls.setVisibility(View.VISIBLE);
    }

    private void enterGameplayMode() {
        titleView.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        selectButton.setVisibility(View.GONE);
        rootLayout.setPadding(0, 0, 0, 0);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private FrameLayout buildControls() {
        return new ControllerOverlay(this);
    }

    private LinearLayout.LayoutParams controlSize() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(42));
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Button controlButton(String label, final int button) {
        Button control = new Button(this);
        control.setTag("control:" + label);
        control.setText(label);
        control.setTextSize(11f);
        control.setMinWidth(0);
        control.setMinHeight(0);
        control.setPadding(2, 2, 2, 2);
        control.setAlpha(0.82f);
        control.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) {
                int action = event.getActionMasked();
                if (controls instanceof ControllerOverlay
                        && ((ControllerOverlay) controls).isEditMode()) {
                    return ((ControllerOverlay) controls).handleEditorTouch(view, event);
                }
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                    if (nativeReady) nativeSetInput(button, true);
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || action == MotionEvent.ACTION_POINTER_UP) {
                    if (nativeReady) nativeSetInput(button, false);
                    return true;
                }
                return true;
            }
        });
        return control;
    }

    private Button menuButton() {
        Button control = new Button(this);
        control.setTag("control:MENU");
        control.setText("≡");
        control.setTextSize(14f);
        control.setMinWidth(0);
        control.setMinHeight(0);
        control.setPadding(2, 2, 2, 2);
        control.setAlpha(0.82f);
        control.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (controls instanceof ControllerOverlay
                        && ((ControllerOverlay) controls).isEditMode()) {
                    return ((ControllerOverlay) controls).handleEditorTouch(view, event);
                }
                return false;
            }
        });
        control.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View ignored) { showGameMenu(); }
        });
        return control;
    }

    private void showGameMenu() {
        final String[] options = {"Salvar estado", "Carregar estado", "Configurações", "Fechar menu"};
        new AlertDialog.Builder(this)
                .setTitle("Menu do jogo")
                .setItems(options, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) showSaveSlotDialog(false);
                        else if (which == 1) showSaveSlotDialog(true);
                        else if (which == 2) showSettings();
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private File saveSlot(int slot) {
        File directory = new File(getFilesDir(), "saves");
        if (!directory.exists()) directory.mkdirs();
        return new File(directory, "slot" + slot + ".state");
    }

    private void showSaveSlotDialog(final boolean load) {
        final String[] slots = new String[10];
        for (int i = 0; i < slots.length; i++) {
            File file = saveSlot(i + 1);
            slots[i] = "Slot " + (i + 1) + (file.exists() ? "  (ocupado)" : "  (vazio)");
        }
        new AlertDialog.Builder(this)
                .setTitle(load ? "Carregar estado" : "Salvar estado")
                .setItems(slots, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        if (load) loadGameState(which + 1);
                        else saveGameState(which + 1);
                    }
                })
                .show();
    }

    private void saveGameState(int slotNumber) {
        File slot = saveSlot(slotNumber);
        boolean saved = nativeReady && nativeSaveState(slot.getAbsolutePath());
        Toast.makeText(this, saved ? "Estado salvo no slot " + slotNumber : "Não foi possível salvar o estado",
                Toast.LENGTH_SHORT).show();
    }

    private void loadGameState(int slotNumber) {
        File slot = saveSlot(slotNumber);
        boolean loaded = nativeReady && slot.exists() && nativeLoadState(slot.getAbsolutePath());
        Toast.makeText(this, loaded ? "Estado carregado do slot " + slotNumber : "Nenhum estado válido no slot " + slotNumber,
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Some PS1 cores expose a digital pad by default.  Keep the real analog
     * values and mirror the left stick's cardinal directions to the pad so a
     * game such as Metal Slug responds even when it is not in analog mode.
     */
    private void mirrorLeftStickToDpad(float normalizedX, float normalizedY) {
        if (!nativeReady) return;
        final float threshold = 0.28f;
        nativeSetInput(6, normalizedX < -threshold);
        nativeSetInput(7, normalizedX > threshold);
        nativeSetInput(4, normalizedY < -threshold);
        nativeSetInput(5, normalizedY > threshold);
    }

    private void showSettings() {
        final ControllerOverlay overlay = controls instanceof ControllerOverlay
                ? (ControllerOverlay) controls : null;
        if (overlay == null) return;
        final AlertDialog dialog = new AlertDialog.Builder(this).create();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(8), dp(18), dp(8));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        final FrameLayout panel = new FrameLayout(this);
        root.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        root.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));

        String[] tabNames = {"Vídeo", "Áudio", "Controles", "Engine"};
        for (int i = 0; i < tabNames.length; i++) {
            final int tab = i;
            Button tabButton = new Button(this);
            tabButton.setText(tabNames[i]);
            tabButton.setTextSize(11f);
            tabButton.setMinWidth(0);
            tabButton.setMinHeight(0);
            tabButton.setPadding(2, 2, 2, 2);
            tabButton.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View ignored) {
                    populateSettingsPanel(panel, overlay, tab, dialog);
                }
            });
            tabs.addView(tabButton, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        dialog.setTitle("Configurações");
        dialog.setView(root);
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Fechar", (android.content.DialogInterface.OnClickListener) null);
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface ignored) {
                populateSettingsPanel(panel, overlay, 0, dialog);
            }
        });
        dialog.show();
    }

    private void populateSettingsPanel(final FrameLayout panel, final ControllerOverlay overlay,
                                       int tab, final AlertDialog dialog) {
        panel.removeAllViews();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(8), dp(8), dp(4));
        if (tab == 0) {
            addSettingText(content, "Vídeo\n\nProporção: 4:3 (sem esticar)\nOrientação: paisagem\nRenderização: tela cheia");
            addSettingButton(content, "Restaurar vídeo 4:3", new View.OnClickListener() {
                @Override public void onClick(View ignored) {
                    Toast.makeText(MainActivity.this, "Vídeo configurado em 4:3", Toast.LENGTH_SHORT).show();
                }
            });
        } else if (tab == 1) {
            addSettingText(content, "Áudio\n\nSaída: estéreo\nAmostragem: automática pelo core");
            SeekBar volume = new SeekBar(this);
            volume.setMax(100);
            volume.setProgress(100);
            volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (audioTrack != null) audioTrack.setStereoVolume(value / 100f, value / 100f);
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
            content.addView(volume);
        } else if (tab == 2) {
            addSettingText(content, "Controles\n\nUm dedo: arraste os controles.\nDois dedos sobre um controle: pinça para aumentar ou diminuir.\nAs alterações ficam salvas neste aparelho.");
            addSettingButton(content, "EDITAR CONTROLES", new View.OnClickListener() {
                @Override public void onClick(View ignored) {
                    dialog.dismiss();
                    overlay.setEditMode(true);
                    Toast.makeText(MainActivity.this,
                            "Arraste com um dedo. Use dois dedos para redimensionar.", Toast.LENGTH_LONG).show();
                }
            });
            addSettingButton(content, "RESTAURAR LAYOUT", new View.OnClickListener() {
                @Override public void onClick(View ignored) {
                    overlay.resetLayout();
                    Toast.makeText(MainActivity.this, "Layout restaurado", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            addSettingText(content, "Engine\n\nCore: libretro ARM64\nExecução: thread nativa\nPlataforma: PS1\nSalvamento: slot 1 disponível");
        }
        panel.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void addSettingText(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15f);
        parent.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void addSettingButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        parent.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    }

    private final class ControllerOverlay extends FrameLayout {
        private final Button l2 = controlButton("L2", 12);
        private final Button l1 = controlButton("L1", 10);
        private final Button l3 = controlButton("L3", 14);
        private final Button r1 = controlButton("R1", 11);
        private final Button r2 = controlButton("R2", 13);
        private final Button r3 = controlButton("R3", 15);
        private final Button up = controlButton("▲", 4);
        private final Button left = controlButton("◀", 6);
        private final Button down = controlButton("▼", 5);
        private final Button right = controlButton("▶", 7);
        private final Button triangle = controlButton("△", 9);
        private final Button square = controlButton("□", 1);
        private final Button cross = controlButton("×", 0);
        private final Button circle = controlButton("○", 8);
        private final Button select = controlButton("SELECT", 2);
        private final Button start = controlButton("START", 3);
        private final Button menu = menuButton();
        private final Button finishEdit = editorButton();
        private final AnalogStickView leftStick = new AnalogStickView(MainActivity.this, 0);
        private final AnalogStickView rightStick = new AnalogStickView(MainActivity.this, 1);
        private boolean editMode;
        private int selectedIndex;
        private View dragView;
        private float dragX;
        private float dragY;
        private float pinchDistance;
        private View[] dragGroup;
        private View[] pinchGroup;
        private float[] pinchGroupScales;

        ControllerOverlay(android.content.Context context) {
            super(context);
            setClipChildren(false);
            addView(l2); addView(l1); addView(l3);
            addView(r1); addView(r2); addView(r3);
            addView(up); addView(left); addView(down); addView(right);
            addView(triangle); addView(square); addView(cross); addView(circle);
            addView(select); addView(start); addView(menu);
            addView(leftStick); addView(rightStick);
            addView(finishEdit);
            leftStick.setTag("stick:left");
            rightStick.setTag("stick:right");
        }

        private Button editorButton() {
            Button button = new Button(MainActivity.this);
            button.setText("CONCLUIR");
            button.setTextSize(11f);
            button.setMinWidth(0);
            button.setMinHeight(0);
            button.setPadding(2, 2, 2, 2);
            button.setVisibility(View.GONE);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View ignored) { setEditMode(false); }
            });
            return button;
        }

        boolean isEditMode() { return editMode; }
        int getSelectedIndex() { return selectedIndex; }
        void setSelectedIndex(int index) { selectedIndex = Math.max(0, Math.min(18, index)); }

        private View selectedView(int index) {
            return getChildAt(index);
        }

        float getSelectedScale(int index) {
            View view = selectedView(index);
            Object tag = view == null ? null : view.getTag();
            if (!(tag instanceof String)) return 1.0f;
            float scale = controlPreferences.getFloat((String) tag + ".scale", 1.0f);
            return scale < 0.50f || scale > 2.50f ? 1.0f : scale;
        }

        void setSelectedScale(int index, float scale) {
            View view = selectedView(index);
            Object tag = view == null ? null : view.getTag();
            if (!(tag instanceof String)) return;
            scale = Math.max(0.50f, Math.min(2.50f, scale));
            controlPreferences.edit().putFloat((String) tag + ".scale", scale).apply();
            requestLayout();
        }

        private void setViewScale(View view, float scale) {
            Object tag = view.getTag();
            if (!(tag instanceof String)) return;
            scale = Math.max(0.50f, Math.min(2.50f, scale));
            controlPreferences.edit().putFloat((String) tag + ".scale", scale).apply();
            requestLayout();
        }

        void setEditMode(boolean enabled) {
            editMode = enabled;
            finishEdit.setVisibility(enabled ? View.VISIBLE : View.GONE);
            float alpha = enabled ? 0.58f : 0.82f;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child != finishEdit) child.setAlpha(alpha);
            }
            requestLayout();
        }

        void resetLayout() {
            controlPreferences.edit().clear().apply();
            setEditMode(false);
            requestLayout();
        }

        boolean handleEditorTouch(View view, MotionEvent event) {
            if (!editMode) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                dragView = view;
                dragGroup = controlGroup(view);
                dragX = event.getRawX();
                dragY = event.getRawY();
                pinchDistance = 0f;
                return true;
            }
            if (dragView != view) return true;
            if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
                pinchDistance = pointerDistance(event);
                pinchGroup = controlGroup(view);
                pinchGroupScales = new float[pinchGroup.length];
                for (int i = 0; i < pinchGroup.length; i++) {
                    pinchGroupScales[i] = getViewScale(pinchGroup[i]);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() >= 2
                    && pinchDistance > 0f) {
                float distance = pointerDistance(event);
                if (distance > 1f) setGroupScale(pinchGroup, pinchGroupScales,
                        distance / pinchDistance);
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_UP) {
                pinchDistance = 0f;
                dragX = event.getRawX();
                dragY = event.getRawY();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                int dx = Math.round(event.getRawX() - dragX);
                int dy = Math.round(event.getRawY() - dragY);
                moveGroup(dragGroup, dx, dy);
                dragX = event.getRawX();
                dragY = event.getRawY();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragView = null;
                dragGroup = null;
                pinchGroup = null;
                pinchGroupScales = null;
                pinchDistance = 0f;
                return true;
            }
            return true;
        }

        private float pointerDistance(MotionEvent event) {
            if (event.getPointerCount() < 2) return 0f;
            float dx = event.getX(0) - event.getX(1);
            float dy = event.getY(0) - event.getY(1);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        private void setGroupScale(View[] group, float[] initialScales, float factor) {
            if (group == null || initialScales == null) return;
            for (int i = 0; i < group.length; i++) {
                setViewScale(group[i], initialScales[i] * factor);
            }
        }

        private void moveGroup(View[] group, int dx, int dy) {
            if (group == null || group.length == 0) return;
            int minDx = -getWidth();
            int maxDx = getWidth();
            int minDy = -getHeight();
            int maxDy = getHeight();
            for (View member : group) {
                minDx = Math.max(minDx, -member.getLeft());
                maxDx = Math.min(maxDx, getWidth() - member.getRight());
                minDy = Math.max(minDy, -member.getTop());
                maxDy = Math.min(maxDy, getHeight() - member.getBottom());
            }
            dx = Math.max(minDx, Math.min(maxDx, dx));
            dy = Math.max(minDy, Math.min(maxDy, dy));
            for (View member : group) {
                int x = member.getLeft() + dx;
                int y = member.getTop() + dy;
                member.layout(x, y, x + member.getWidth(), y + member.getHeight());
                savePosition(member, x, y);
            }
        }

        private View[] controlGroup(View view) {
            Object tagObject = view.getTag();
            String tag = tagObject instanceof String ? (String) tagObject : "";
            if (tag.equals("control:▲") || tag.equals("control:◀")
                    || tag.equals("control:▼") || tag.equals("control:▶")) {
                return new View[] {up, left, down, right};
            }
            if (tag.equals("control:△") || tag.equals("control:□")
                    || tag.equals("control:×") || tag.equals("control:○")) {
                return new View[] {triangle, square, cross, circle};
            }
            if (tag.equals("control:SELECT") || tag.equals("control:START")
                    || tag.equals("control:MENU")) {
                return new View[] {select, start, menu};
            }
            return new View[] {view};
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(width, height);
            int buttonWidth = Math.max(dp(34), Math.min((int) (width * 0.055f),
                    (int) (height * 0.085f)));
            int buttonHeight = Math.max(dp(28), (int) (height * 0.075f));
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                float scale = getViewScale(child);
                if (child instanceof AnalogStickView) {
                    int stickSize = Math.max(dp(76), Math.min((int) (width * 0.12f * scale),
                            (int) (height * 0.22f * scale)));
                    child.measure(MeasureSpec.makeMeasureSpec(stickSize, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(stickSize, MeasureSpec.EXACTLY));
                } else if (child == select || child == start || child == menu) {
                    int systemWidth = (int) (Math.min(width * 0.09f, buttonWidth * 2) * scale);
                    child.measure(MeasureSpec.makeMeasureSpec(systemWidth, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec((int) (buttonHeight * scale), MeasureSpec.EXACTLY));
                } else {
                    child.measure(MeasureSpec.makeMeasureSpec((int) (buttonWidth * scale), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec((int) (buttonHeight * scale), MeasureSpec.EXACTLY));
                }
            }
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int height = b - t;
            int buttonWidth = l2.getMeasuredWidth();
            int buttonHeight = l2.getMeasuredHeight();
            int gap = Math.max(dp(5), (int) (buttonWidth * 0.30f));
            int leftX = (int) (width * 0.06f);
            // Reference layout: the shoulder rows stay near the outer edges,
            // while the game remains visible in the center.  Do not derive the
            // right group from the left margin: that puts it too far inward on
            // the wide landscape screens used by the phone.
            int rightX = (int) (width * 0.80f);
            int maxRightX = width - buttonWidth * 3 - gap * 2 - (int) (width * 0.025f);
            rightX = Math.min(rightX, maxRightX);
            int shoulderY = (int) (height * 0.31f);
            int dpadTopY = (int) (height * 0.45f);
            int dpadMiddleY = dpadTopY + buttonHeight + gap;
            int dpadBottomY = dpadMiddleY + buttonHeight + gap;
            int faceTopY = dpadTopY;
            int faceMiddleY = dpadMiddleY;
            int faceBottomY = dpadBottomY;

            place(l2, leftX, shoulderY, buttonWidth, buttonHeight);
            place(l1, leftX + buttonWidth + gap, shoulderY, buttonWidth, buttonHeight);
            place(l3, leftX + (buttonWidth + gap) * 2, shoulderY, buttonWidth, buttonHeight);
            place(r1, rightX, shoulderY, buttonWidth, buttonHeight);
            place(r2, rightX + buttonWidth + gap, shoulderY, buttonWidth, buttonHeight);
            place(r3, rightX + (buttonWidth + gap) * 2, shoulderY, buttonWidth, buttonHeight);

            int dpadCenterX = leftX + buttonWidth + gap;
            place(up, dpadCenterX, dpadTopY, buttonWidth, buttonHeight);
            place(left, leftX, dpadMiddleY, buttonWidth, buttonHeight);
            place(right, leftX + (buttonWidth + gap) * 2, dpadMiddleY, buttonWidth, buttonHeight);
            place(down, dpadCenterX, dpadBottomY, buttonWidth, buttonHeight);

            int faceCenterX = rightX + buttonWidth + gap;
            place(triangle, faceCenterX, faceTopY, buttonWidth, buttonHeight);
            place(square, faceCenterX - buttonWidth - gap, faceMiddleY, buttonWidth, buttonHeight);
            place(circle, faceCenterX + buttonWidth + gap, faceMiddleY, buttonWidth, buttonHeight);
            place(cross, faceCenterX, faceBottomY, buttonWidth, buttonHeight);

            int stickSize = leftStick.getMeasuredWidth();
            int stickY = (int) (height * 0.69f);
            place(leftStick, (int) (width * 0.15f) - stickSize / 2, stickY, stickSize, stickSize);
            place(rightStick, (int) (width * 0.89f) - stickSize / 2, stickY, stickSize, stickSize);

            int systemGap = Math.max(dp(6), gap / 2);
            int systemWidth = select.getMeasuredWidth();
            int systemTotal = systemWidth * 3 + systemGap * 2;
            int systemX = (width - systemTotal) / 2;
            int systemY = (int) (height * 0.82f);
            place(select, systemX, systemY, systemWidth, buttonHeight);
            place(start, systemX + systemWidth + systemGap, systemY, systemWidth, buttonHeight);
            place(menu, systemX + (systemWidth + systemGap) * 2, systemY, systemWidth, buttonHeight);
            if (finishEdit.getVisibility() == View.VISIBLE) {
                int editWidth = Math.max(dp(86), (int) (width * 0.10f));
                finishEdit.measure(MeasureSpec.makeMeasureSpec(editWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(buttonHeight, MeasureSpec.EXACTLY));
                place(finishEdit, (width - editWidth) / 2, (int) (height * 0.04f),
                        editWidth, buttonHeight);
            }
        }

        private void place(View view, int x, int y, int width, int height) {
            int[] saved = savedPosition(view);
            if (saved != null && view != finishEdit) {
                x = saved[0];
                y = saved[1];
            }
            // The measured size is per-control, so changing one element does
            // not resize the rest of the overlay.
            int measuredWidth = view.getMeasuredWidth() > 0 ? view.getMeasuredWidth() : width;
            int measuredHeight = view.getMeasuredHeight() > 0 ? view.getMeasuredHeight() : height;
            view.layout(x, y, x + measuredWidth, y + measuredHeight);
        }

        private float getViewScale(View view) {
            Object tag = view.getTag();
            if (!(tag instanceof String)) return 1.0f;
            float scale = controlPreferences.getFloat((String) tag + ".scale", 1.0f);
            return scale < 0.50f || scale > 2.50f ? 1.0f : scale;
        }

        private int[] savedPosition(View view) {
            Object tag = view.getTag();
            if (!(tag instanceof String)) return null;
            String key = (String) tag;
            if (!controlPreferences.contains(key + ".nx")) {
                if (!controlPreferences.contains(key + ".x")) return null;
                int oldX = controlPreferences.getInt(key + ".x", view.getLeft());
                int oldY = controlPreferences.getInt(key + ".y", view.getTop());
                return new int[] {oldX, oldY};
            }
            int x = Math.round(controlPreferences.getFloat(key + ".nx", 0f) * getWidth());
            int y = Math.round(controlPreferences.getFloat(key + ".ny", 0f) * getHeight());
            x = Math.max(0, Math.min(getWidth() - view.getMeasuredWidth(), x));
            y = Math.max(0, Math.min(getHeight() - view.getMeasuredHeight(), y));
            return new int[] {x, y};
        }

        private void savePosition(View view, int x, int y) {
            Object tag = view.getTag();
            if (tag instanceof String) {
                controlPreferences.edit().putFloat((String) tag + ".nx",
                                getWidth() == 0 ? 0f : x / (float) getWidth())
                        .putFloat((String) tag + ".ny",
                                getHeight() == 0 ? 0f : y / (float) getHeight()).apply();
            }
        }
    }

    private final class AnalogStickView extends View {
        private final int stick;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float knobX;
        private float knobY;

        AnalogStickView(android.content.Context context, int stick) {
            super(context);
            this.stick = stick;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2));
            ring.setColor(0xffb7c7ff);
            knob.setStyle(Paint.Style.FILL);
            knob.setColor(0x887b8fff);
            setFocusable(true);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) * 0.38f;
            float x = knobX == 0 && knobY == 0 ? cx : cx + knobX;
            float y = knobX == 0 && knobY == 0 ? cy : cy + knobY;
            canvas.drawCircle(cx, cy, radius, ring);
            canvas.drawCircle(x, y, radius * 0.34f, knob);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (controls instanceof ControllerOverlay
                    && ((ControllerOverlay) controls).isEditMode()) {
                return ((ControllerOverlay) controls).handleEditorTouch(this, event);
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;
                float radius = Math.min(getWidth(), getHeight()) * 0.38f;
                float x = event.getX() - cx;
                float y = event.getY() - cy;
                float distance = (float) Math.sqrt(x * x + y * y);
                if (distance > radius && distance > 0) {
                    x = x * radius / distance;
                    y = y * radius / distance;
                }
                knobX = x;
                knobY = y;
                if (nativeReady) {
                    float normalizedX = x / radius;
                    float normalizedY = y / radius;
                    nativeSetAnalog(stick, (int) (normalizedX * 32767f),
                            (int) (normalizedY * 32767f));
                    if (stick == 0) mirrorLeftStickToDpad(normalizedX, normalizedY);
                }
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                knobX = 0;
                knobY = 0;
                if (nativeReady) {
                    nativeSetAnalog(stick, 0, 0);
                    if (stick == 0) mirrorLeftStickToDpad(0, 0);
                }
                invalidate();
                performClick();
                return true;
            }
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    private void startAudio() {
        stopAudio();
        try {
            int sampleRate = nativeGetAudioSampleRate();
            if (sampleRate <= 0) sampleRate = 44100;
            int minimum = AudioTrack.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) minimum = sampleRate / 5 * 4;
            final int bufferSize = Math.max(minimum, sampleRate / 5 * 4);
            audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            }
            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize, AudioTrack.MODE_STREAM);
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                audioTrack.release();
                audioTrack = null;
                return;
            }
            audioTrack.setStereoVolume(1.0f, 1.0f);
            audioTrack.play();
            audioRunning = true;
            audioThread = new Thread(new Runnable() {
                @Override public void run() {
                    short[] samples = new short[2048 * 2];
                    while (audioRunning) {
                        int frames = nativeReadAudio(samples, 2048);
                        if (frames > 0 && audioTrack != null) {
                            int written = audioTrack.write(samples, 0, frames * 2,
                                    AudioTrack.WRITE_BLOCKING);
                            if (written < 0) SystemClock.sleep(2);
                        } else {
                            SystemClock.sleep(2);
                        }
                    }
                }
            }, "apinex-audio");
            audioThread.start();
        } catch (RuntimeException ignored) {
            audioRunning = false;
            if (audioTrack != null) {
                audioTrack.release();
                audioTrack = null;
            }
        }
    }

    private void stopAudio() {
        audioRunning = false;
        Thread thread = audioThread;
        audioThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(250); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if (audioTrack != null) {
            try { audioTrack.stop(); } catch (IllegalStateException ignored) {}
            audioTrack.release();
            audioTrack = null;
        }
        if (audioManager != null) {
            audioManager.abandonAudioFocus(null);
            audioManager = null;
        }
    }

    private void runFrames() {
        surface.postDelayed(new Runnable() {
            @Override public void run() {
                if (!nativeReady) return;
                nativeRunFrame();
                runFrames();
            }
        }, 16);
    }

    @Override protected void onDestroy() {
        nativeReady = false;
        stopAudio();
        nativeStop();
        super.onDestroy();
    }
}
