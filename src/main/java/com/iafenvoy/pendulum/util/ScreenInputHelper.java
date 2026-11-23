package com.iafenvoy.pendulum.util;

import com.iafenvoy.pendulum.script.ScriptEngine;
import com.mojang.logging.LogUtils;
import com.sun.jna.platform.win32.User32;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Mouse/keyboard injection through Minecraft's own GLFW callback handlers (MouseHandler,
 * KeyboardHandler), on the game thread. Works without window focus and never moves the OS
 * cursor or sends input to other applications.
 * Used by MCP GUI interaction tools and JS gui.click / gui.pressKey / gui.typeText.
 */
public final class ScreenInputHelper {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ScreenInputHelper() {
    }

    // ==================== Thread marshalling ====================

    /**
     * All Minecraft input handling (keyboardHandler/mouseHandler callbacks, {@code setScreen},
     * GL state) is render-thread only. Injecting a key from the MCP/JS threads makes
     * {@code Minecraft.setScreen} throw after it has already assigned {@code this.screen} but
     * before {@code Screen.init()} ran, so the screen is rendered with a null {@code minecraft}
     * field and the game crashes in {@code Screen.renderBackground} (issue #2).
     * Waits for the task to finish on the game thread.
     */
    private static void onGameThread(Runnable task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) task.run();
        else ScriptEngine.submitToGameThread(task);
    }

    /**
     * Same as {@link #onGameThread} but does not block for a game tick. The task queue is FIFO,
     * so ordering is kept; used for ordered bursts (typing) where one tick per key event would
     * make it needlessly slow.
     */
    private static void onGameThreadLater(Runnable task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) task.run();
        else ScriptEngine.runOnGameThread(task);
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    // ==================== Mouse ====================
    //
    // All mouse coordinates are GUI coordinates: the same space as gui/enumerateWidgets,
    // Screen.mouseClicked and widget x/y (window pixels divided by the GUI scale).
    //
    // Input goes through Minecraft's own MouseHandler callbacks (onMove/onPress/onScroll) on
    // the game thread, exactly as GLFW would call them. That keeps vanilla and loader events
    // (Forge ScreenEvent, JEI overlays, slot hover, tooltips) and needs neither window focus
    // nor the OS cursor: glfwSetCursorPos silently does nothing while the window is unfocused,
    // and an AWT Robot would click the real desktop at the wrong place.

    private static final String[] ON_MOVE = {"onMove", "m_91561_"};
    private static final String[] ON_PRESS = {"onPress", "m_91530_"};
    private static final String[] ON_SCROLL = {"onScroll", "m_91526_"};

    /**
     * Inject a mouse button press/release at the current cursor position.
     *
     * @param button 0=left, 1=right, 2=middle (GLFW button codes)
     * @param action 1=press, 0=release
     */
    public static void injectMouseButton(int button, int action) {
        onGameThread(() -> injectMouseButtonNow(button, action));
    }

    private static void injectMouseButtonNow(int button, int action) {
        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().getWindow();
        if (handle == 0) return;
        try {
            Method m = findMethod(mc.mouseHandler.getClass(), ON_PRESS, long.class, int.class, int.class, int.class);
            if (m != null) m.invoke(mc.mouseHandler, handle, button, action, 0);
            else LOGGER.warn("[Pendulum] MouseHandler.onPress not found");
        } catch (Exception e) {
            LOGGER.warn("[Pendulum] Mouse button injection failed", e);
        }
    }

    /**
     * Move the in-game cursor to GUI coordinates. Hover states and tooltips follow it.
     */
    public static void moveCursor(double guiX, double guiY) {
        onGameThread(() -> moveCursorNow(guiX, guiY));
    }

    private static void moveCursorNow(double guiX, double guiY) {
        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().getWindow();
        if (handle == 0) return;
        double px = guiX * mc.getWindow().getScreenWidth() / (double) mc.getWindow().getGuiScaledWidth();
        double py = guiY * mc.getWindow().getScreenHeight() / (double) mc.getWindow().getGuiScaledHeight();
        try {
            Method m = findMethod(mc.mouseHandler.getClass(), ON_MOVE, long.class, double.class, double.class);
            if (m != null) m.invoke(mc.mouseHandler, handle, px, py);
            else LOGGER.warn("[Pendulum] MouseHandler.onMove not found");
        } catch (Exception e) {
            LOGGER.warn("[Pendulum] Cursor move failed", e);
        }
    }

    /**
     * Click at GUI coordinates (moves the cursor there, then press + release).
     *
     * @param button 0=left, 1=right, 2=middle
     */
    public static void clickAt(int x, int y, int button) {
        moveCursor(x, y);
        injectMouseButton(button, 1);
        sleep(30);
        injectMouseButton(button, 0);
    }

    /**
     * Drag the mouse between two GUI points.
     */
    public static void mouseDrag(int x1, int y1, int x2, int y2, int button) {
        moveCursor(x1, y1);
        injectMouseButton(button, 1);
        int steps = Math.max(5, Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)) / 5);
        for (int i = 1; i <= steps; i++) {
            moveCursor(x1 + (x2 - x1) * (double) i / steps, y1 + (y2 - y1) * (double) i / steps);
            sleep(10);
        }
        injectMouseButton(button, 0);
    }

    /**
     * Scroll the mouse wheel at the current cursor position.
     *
     * @param clicks positive=up, negative=down
     */
    public static void scroll(int clicks) {
        onGameThread(() -> scrollNow(clicks));
    }

    private static void scrollNow(int clicks) {
        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().getWindow();
        if (handle == 0) return;
        try {
            Method m = findMethod(mc.mouseHandler.getClass(), ON_SCROLL, long.class, double.class, double.class);
            if (m != null) m.invoke(mc.mouseHandler, handle, 0.0, (double) clicks);
            else LOGGER.warn("[Pendulum] MouseHandler.onScroll not found");
        } catch (Exception e) {
            LOGGER.warn("[Pendulum] Scroll failed", e);
        }
    }

    /**
     * Find a declared method by any of its names (Mojang name first, then the SRG name a
     * normal Forge 1.20.1 game uses) and exact parameter types; made accessible.
     */
    static Method findMethod(Class<?> clazz, String[] names, Class<?>... params) {
        for (String name : names) {
            try {
                Method m = clazz.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /** Same as {@link #findMethod} for fields, searching superclasses too. */
    public static Field findField(Class<?> clazz, String... names) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (String name : names) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                }
            }
        }
        return null;
    }

    // ==================== Keyboard ====================

    /**
     * Inject a keyboard key via Minecraft's keyboard handler + Win32 PostMessage fallback.
     * NO AWT Robot — prevents keystroke leakage to other applications.
     */
    public static void injectKey(String keyName, int action) {
        int glfwKey = parseKeyName(keyName);
        if (glfwKey < 0) {
            LOGGER.warn("[Pendulum] Unknown key: {}", keyName);
            return;
        }

        // No focus change: KeyboardHandler.keyPress works while the window is in the background,
        // and stealing focus would interrupt whatever the user is doing.
        onGameThread(() -> injectKeyNow(keyName, glfwKey, action));
    }

    private static void injectKeyNow(String keyName, int glfwKey, int action) {
        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().getWindow();

        // Method 1: MC keyboardHandler.keyPress (preferred)
        if (handle != 0) {
            try {
                Object kb = getKeyboardHandler(mc);
                if (kb != null) {
                    Method m = findKeyPressMethod(kb.getClass());
                    if (m != null) {
                        m.setAccessible(true);
                        m.invoke(kb, handle, glfwKey, 0, action, 0);
                        return;
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("[Pendulum] keyPress injection failed for {}", keyName, e);
            }
        }

        // Method 2: Win32 PostMessage (sends WM_KEYDOWN/UP to MC window only)
        if (handle != 0) {
            try {
                int vk = glfwToWin32Vk(glfwKey);
                if (vk > 0) {
                    Class<?> user32 = Class.forName("com.sun.jna.platform.win32.User32");
                    Object instance = user32.getField("INSTANCE").get(null);
                    user32.getMethod("PostMessage", long.class, int.class, long.class, long.class)
                            .invoke(instance, handle, action == 1 ? 0x0100 : 0x0101, (long) vk, 0L);
                    return;
                }
            } catch (Exception e) {
                LOGGER.debug("[Pendulum] PostMessage key injection failed for {}", keyName, e);
            }
        }

        LOGGER.warn("[Pendulum] Keyboard injection failed for key: {}", keyName);
    }

    private static int glfwToWin32Vk(int k) {
        if (k >= 65 && k <= 90) return k;
        if (k >= 48 && k <= 57) return k;
        return switch (k) {
            case 256 -> 0x1B;
            case 257 -> 0x0D;
            case 258 -> 0x09;
            case 259 -> 0x08;
            case 261 -> 0x2E;
            case 262 -> 0x27;
            case 263 -> 0x25;
            case 264 -> 0x28;
            case 265 -> 0x26;
            case 32 -> 0x20;
            case 340, 344 -> 0x10;
            case 341, 345 -> 0x11;
            case 342, 346 -> 0x12;
            default -> -1;
        };
    }

    /**
     * Press and release a key.
     */
    public static void pressKey(String keyName, float holdSeconds) {
        injectKey(keyName, 1);
        sleep((long) (holdSeconds * 1000));
        injectKey(keyName, 0);
    }

    /** Type text via keyboard injection (no AWT Robot). */
    public static void typeText(String text) {
        for (char c : text.toCharArray()) typeChar(c);
    }

    private static void typeChar(char c) {
        // Text fields only take characters from charTyped (the GLFW char callback); keyPress
        // alone never inserts text. The key event is sent too, for screens that react to keys.
        int glfwKey = charToGlfwKey(c);
        if (glfwKey > 0) injectKeyRaw(glfwKey, 1);
        injectCharRaw(c);
        if (glfwKey > 0) injectKeyRaw(glfwKey, 0);
        sleep(10);
    }

    private static void injectCharRaw(char c) {
        onGameThreadLater(() -> {
            Minecraft mc = Minecraft.getInstance();
            long handle = mc.getWindow().getWindow();
            if (handle == 0) return;
            try {
                Method m = findCharTypedMethod(mc.keyboardHandler.getClass());
                if (m != null) m.invoke(mc.keyboardHandler, handle, (int) c, 0);
            } catch (Exception e) {
                LOGGER.debug("[Pendulum] char injection failed for {}", c, e);
            }
        });
    }

    private static void injectKeyRaw(int glfwKey, int action) {
        onGameThreadLater(() -> {
            Minecraft mc = Minecraft.getInstance();
            long handle = mc.getWindow().getWindow();
            if (handle != 0) {
                try {
                    Object kb = getKeyboardHandler(mc);
                    if (kb != null) {
                        Method m = findKeyPressMethod(kb.getClass());
                        if (m != null) {
                            m.setAccessible(true);
                            m.invoke(kb, handle, glfwKey, 0, action, 0);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("[Pendulum] raw key injection failed for {}", glfwKey, e);
                }
            }
        });
    }

    private static int charToGlfwKey(char c) {
        if (c >= 'a' && c <= 'z') return (c - 'a') + 65;
        if (c >= 'A' && c <= 'Z') return (c - 'A') + 65;
        if (c >= '0' && c <= '9') return (c - '0') + 48;
        return switch (c) {
            case ' ' -> 32;
            case '-' -> 45;
            case '=' -> 61;
            case '[' -> 91;
            case ']' -> 93;
            case '\\' -> 92;
            case ';' -> 59;
            case '\'' -> 39;
            case ',' -> 44;
            case '.' -> 46;
            case '/' -> 47;
            default -> -1;
        };
    }

    // ==================== Direct access (Mojang mappings: mouseHandler/keyboardHandler are public) ====================

    private static Object getMouseHandler(Minecraft mc) {
        return mc.mouseHandler;
    }

    private static Object getKeyboardHandler(Minecraft mc) {
        return mc.keyboardHandler;
    }

    private static Method findKeyPressMethod(Class<?> clazz) {
        return findMethod(clazz, new String[]{"keyPress", "m_90893_"}, long.class, int.class, int.class, int.class, int.class);
    }

    private static Method findCharTypedMethod(Class<?> clazz) {
        return findMethod(clazz, new String[]{"charTyped", "m_90889_"}, long.class, int.class, int.class);
    }

    // ==================== Key name parsing ====================

    /**
     * Parse various key name formats to GLFW key code.
     * Supports: "key.keyboard.w", "GLFW_KEY_W", "W", "Enter", "ESC", "SPACE", etc.
     */
    private static int parseKeyName(String name) {
        if (name == null || name.isEmpty()) return -1;

        String upper = name.toUpperCase().trim();

        // Strip "key.keyboard." prefix
        if (upper.startsWith("KEY.KEYBOARD.")) upper = upper.substring(13);
        if (upper.startsWith("GLFW_KEY_")) upper = upper.substring(9);

        // Common aliases
        switch (upper) {
            case "ESC":
            case "ESCAPE":
                return 256;
            case "ENTER":
            case "RETURN":
                return 257;
            case "TAB":
                return 258;
            case "BACKSPACE":
                return 259;
            case "DELETE":
                return 261;
            case "RIGHT":
                return 262;
            case "LEFT":
                return 263;
            case "DOWN":
                return 264;
            case "UP":
                return 265;
            case "PAGE_UP":
                return 266;
            case "PAGE_DOWN":
                return 267;
            case "HOME":
                return 268;
            case "END":
                return 269;
            case "SPACE":
                return 32;
            case "LEFT_SHIFT":
                return 340;
            case "LEFT_CONTROL":
            case "LEFT_CTRL":
                return 341;
            case "LEFT_ALT":
                return 342;
            case "RIGHT_SHIFT":
                return 344;
            case "RIGHT_CONTROL":
            case "RIGHT_CTRL":
                return 345;
            case "RIGHT_ALT":
                return 346;
            case "F1":
                return 290;
            case "F2":
                return 291;
            case "F3":
                return 292;
            case "F4":
                return 293;
            case "F5":
                return 294;
            case "F6":
                return 295;
            case "F7":
                return 296;
            case "F8":
                return 297;
            case "F9":
                return 298;
            case "F10":
                return 299;
            case "F11":
                return 300;
            case "F12":
                return 301;
        }

        // Single letter
        if (upper.length() == 1) {
            char c = upper.charAt(0);
            if (c >= 'A' && c <= 'Z') return c - 'A' + 65;
            if (c >= '0' && c <= '9') return c - '0' + 48;
        }

        // Try GLFW constant lookup via reflection
        try {
            Class<?> glfwClass = Class.forName("org.lwjgl.glfw.GLFW");
            Field f = glfwClass.getField("GLFW_KEY_" + upper);
            return f.getInt(null);
        } catch (Exception ignored) {
        }

        return -1;
    }

    // ==================== GLFW to AWT key mapping ====================

    private static int glfwToAwtKey(int glfwKey) {
        if (glfwKey >= 65 && glfwKey <= 90) return glfwKey; // A-Z
        if (glfwKey >= 48 && glfwKey <= 57) return glfwKey; // 0-9
        return switch (glfwKey) {
            case 256 -> KeyEvent.VK_ESCAPE;
            case 257 -> KeyEvent.VK_ENTER;
            case 258 -> KeyEvent.VK_TAB;
            case 259 -> KeyEvent.VK_BACK_SPACE;
            case 261 -> KeyEvent.VK_DELETE;
            case 262 -> KeyEvent.VK_RIGHT;
            case 263 -> KeyEvent.VK_LEFT;
            case 264 -> KeyEvent.VK_DOWN;
            case 265 -> KeyEvent.VK_UP;
            case 32 -> KeyEvent.VK_SPACE;
            case 340 -> KeyEvent.VK_SHIFT;
            case 341 -> KeyEvent.VK_CONTROL;
            case 342 -> KeyEvent.VK_ALT;
            default -> -1;
        };
    }

    private static int charToAwtKey(char c) {
        switch (c) {
            case ' ':
                return KeyEvent.VK_SPACE;
            case '-':
                return KeyEvent.VK_MINUS;
            case '=':
                return KeyEvent.VK_EQUALS;
            case '[':
                return KeyEvent.VK_OPEN_BRACKET;
            case ']':
                return KeyEvent.VK_CLOSE_BRACKET;
            case '\\':
                return KeyEvent.VK_BACK_SLASH;
            case ';':
                return KeyEvent.VK_SEMICOLON;
            case '\'':
                return KeyEvent.VK_QUOTE;
            case ',':
                return KeyEvent.VK_COMMA;
            case '.':
                return KeyEvent.VK_PERIOD;
            case '/':
                return KeyEvent.VK_SLASH;
            default:
                if (c >= '0' && c <= '9') return c;
                if (c >= 'a' && c <= 'z') return Character.toUpperCase(c);
                if (c >= 'A' && c <= 'Z') return c;
                return -1;
        }
    }

    private static boolean needsShift(char c) {
        return "!@#$%^&*()_+{}|:\"<>?~".indexOf(c) >= 0;
    }
}
