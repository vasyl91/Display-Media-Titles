package vasyl.titles.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellCommandsTest {

    private val pkg = "vasyl.titles.phone"
    private val listener = "vasyl.titles.NotificationListener"

    @Test
    fun everyOperationTouchesOnlyThisApp() {
        assertEquals(
            listOf("/system/bin/pm", "grant", pkg, "android.permission.WRITE_SECURE_SETTINGS"),
            ShellCommands.argv(ShellOp.GRANT_WRITE_SECURE_SETTINGS, pkg, listener),
        )
        assertEquals(
            listOf("/system/bin/cmd", "notification", "allow_listener", "$pkg/$listener"),
            ShellCommands.argv(ShellOp.ALLOW_NOTIFICATION_LISTENER, pkg, listener),
        )
        assertEquals(
            listOf("/system/bin/cmd", "deviceidle", "whitelist", "+$pkg"),
            ShellCommands.argv(ShellOp.BATTERY_WHITELIST, pkg, listener),
        )
        assertEquals(
            listOf("/system/bin/appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow"),
            ShellCommands.argv(ShellOp.ALLOW_OVERLAY, pkg, listener),
        )
        assertEquals(
            listOf("/system/bin/appops", "set", pkg, "MANAGE_EXTERNAL_STORAGE", "allow"),
            ShellCommands.argv(ShellOp.ALLOW_STORAGE, pkg, listener),
        )
    }

    @Test
    fun theObscuringLimitIsTheOnlySettingAndStaysWithinZeroAndOne() {
        assertEquals(
            listOf("/system/bin/settings", "put", "global", "maximum_obscuring_opacity_for_touch", "1"),
            ShellCommands.argv(ShellOp.SET_OBSCURING_LIMIT, pkg, listener, "1"),
        )
        assertEquals(
            listOf("/system/bin/settings", "delete", "global", "maximum_obscuring_opacity_for_touch"),
            ShellCommands.argv(ShellOp.DELETE_OBSCURING_LIMIT, pkg, listener),
        )
        assertNull(ShellCommands.argv(ShellOp.SET_OBSCURING_LIMIT, pkg, listener, "1.5"))
        assertNull(ShellCommands.argv(ShellOp.SET_OBSCURING_LIMIT, pkg, listener, "1; reboot"))
        assertNull(ShellCommands.argv(ShellOp.SET_OBSCURING_LIMIT, pkg, listener, null))
    }

    @Test
    fun limitsAreWrittenPlainly() {
        assertEquals("1", ShellCommands.limitValue("1.0"))
        assertEquals("0.8", ShellCommands.limitValue("0.8"))
        assertEquals("0", ShellCommands.limitValue("0"))
        assertNull(ShellCommands.limitValue("-0.1"))
        assertNull(ShellCommands.limitValue("NaN"))
    }

    @Test
    fun nothingButPlainNamesReachesTheShell() {
        assertNull(ShellCommands.argv(ShellOp.ALLOW_OVERLAY, "vasyl.titles;rm -rf /", listener))
        assertNull(ShellCommands.argv(ShellOp.ALLOW_OVERLAY, "notapackage", listener))
        assertNull(ShellCommands.argv(ShellOp.ALLOW_NOTIFICATION_LISTENER, pkg, "a.B\$C"))
        assertEquals(
            "/system/bin/cmd deviceidle whitelist +$pkg",
            ShellCommands.commandLine(ShellCommands.argv(ShellOp.BATTERY_WHITELIST, pkg, listener)!!),
        )
    }
}
