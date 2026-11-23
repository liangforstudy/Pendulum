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

    /**
     * "Window on top" option: keep the game window above other windows without giving it focus,
     * so the user can watch an agent play while working elsewhere. GLFW_FLOATING sets
     * HWND_TOPMOST with SWP_NOACTIVATE on Windows, so it never steals focus and, together with
     * the focus check below, never traps the cursor.
     */
    private static void applyOnTop(long handle) {
        boolean wanted = com.iafenvoy.pendulum.config.PendulumConfig.INSTANCE.windowOnTop.getValue();
        boolean current = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FLOATING) == GLFW.GLFW_TRUE;
        if (wanted != current) GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_FLOATING, wanted ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
    }

    /** Call once per client tick, on the game thread. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) return;
        long handle = mc.getWindow().getWindow();
        if (handle == 0) return;
        applyOnTop(handle);
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
