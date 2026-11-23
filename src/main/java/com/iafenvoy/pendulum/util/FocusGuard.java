package com.iafenvoy.pendulum.util;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * Keeps Minecraft's "window is focused" flag in line with the real GLFW focus state.
 * <p>
 * Minecraft's constructor calls {@code setWindowActive(true)} right after creating the window,
 * assuming it got focus. When the game is launched from a background process (Gradle started by
 * an agent, a launcher in the background), Windows does not give the new window focus, so GLFW
 * never reports a focus loss and the flag stays true. Joining a world or closing a menu then
 * grabs the mouse: GLFW hides the cursor and clips it to the game window's rectangle with
 * {@code ClipCursor}, trapping the user's cursor in an invisible box while they work in another
 * window. With the flag corrected, {@code MouseHandler.grabMouse()} does nothing until the user
 * actually focuses the game.
 */
public final class FocusGuard {
    private static final Logger LOGGER = LogUtils.getLogger();

    private FocusGuard() {
    }

    private static volatile boolean raiseRequested = false;
    private static boolean raisedAfterLoad = false;

    /**
     * Ask for the game window to be brought to the front on the next tick, without focus.
     * Safe to call from any thread.
     */
    public static void requestRaise() {
        raiseRequested = true;
    }

    /**
     * Bring the window to the front of the normal window stack without activating it: make it
     * topmost and immediately not topmost again (on Windows, SetWindowPos with SWP_NOACTIVATE
     * both times). It then behaves like any other window: the next window the user clicks goes
     * in front of it. Focus stays where it was, so the mouse is never captured.
     */
    private static void raise(long handle) {
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_FLOATING, GLFW.GLFW_TRUE);
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_FLOATING, GLFW.GLFW_FALSE);
    }

    /** Call once per client tick, on the game thread. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) return;
        long handle = mc.getWindow().getWindow();
        if (handle == 0) return;

        // "Bring game window to front" option: once when loading finishes, and on request
        // (the window/raise tool). Never on its own while an agent is driving the game.
        if (!raisedAfterLoad && mc.getOverlay() == null) {
            raisedAfterLoad = true;
            if (com.iafenvoy.pendulum.config.PendulumConfig.INSTANCE.raiseWindow.getValue()) raiseRequested = true;
        }
        if (raiseRequested) {
            raiseRequested = false;
            raise(handle);
        }

        if (!mc.isWindowActive()) return;
        if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE) return;

        mc.setWindowActive(false);
        // If the mouse was already grabbed in the stale state, release it: that removes the
        // cursor clip (it moves the cursor to the window centre once, as any release does).
        if (mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
            LOGGER.info("[Pendulum] Released a mouse grab made while the game window was not focused");
        }
    }
}
