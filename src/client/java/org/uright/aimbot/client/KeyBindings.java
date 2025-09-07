package org.uright.aimbot.client;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class KeyBindings {
    public static KeyBinding TOGGLE_AIMBOT;
    public static KeyBinding CLEAR_TARGET;

    public static void register() {
        TOGGLE_AIMBOT = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aimbot.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.aimbot"
        ));

        CLEAR_TARGET = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aimbot.clear_target",
                InputUtil.Type.MOUSE,
                GLFW.GLFW_MOUSE_BUTTON_5,
                "category.aimbot"
        ));
    }
}