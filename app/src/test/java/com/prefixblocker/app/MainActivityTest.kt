package com.prefixblocker.app

import android.Manifest
import android.app.AlertDialog
import android.app.role.RoleManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.provider.CallLog
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.prefixblocker.app.core.Decision
import com.prefixblocker.app.core.Reason
import com.prefixblocker.app.data.AppStore
import com.prefixblocker.app.data.CallHistoryTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "fr-rFR-w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val store get() = AppStore.get(app)

    @Before fun setUp() {
        store.enabled = true
        store.rules(false).forEach { store.deleteRule(it) }
        store.rules(true).forEach { store.deleteRule(it) }
        AppStore.DEFAULT_RULES.forEach { store.saveRule(it, false) }
        store.clearEvents()
        shadowOf(app.getSystemService(RoleManager::class.java)).addHeldRole(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun launch(): ActivityController<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        settle { controller.get().findViewById<View>(R.id.add_entry) != null }
        return controller
    }

    private fun settle(predicate: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        fail("UI did not settle")
    }

    private fun latestDialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle() // Deliver Android's asynchronous onShow callback.
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    @Test fun addingRuleUpdatesPersistentList() {
        val controller = launch()
        try {
            val activity = controller.get()
            activity.findViewById<View>(R.id.add_entry).performClick()
            val dialog = latestDialog()
            dialog.findViewById<EditText>(R.id.number_input).setText("+33 9 99*")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            settle { store.rules(false).size == 6 && !dialog.isShowing }
            assertTrue(store.rules(false).any { it.canonical == "+33999*" })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun malformedRuleKeepsDialogOpenAndShowsError() {
        val controller = launch()
        try {
            controller.get().findViewById<View>(R.id.add_entry).performClick()
            val dialog = latestDialog()
            val input = dialog.findViewById<EditText>(R.id.number_input)
            input.setText("*")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            assertNotNull(input.error)
            assertEquals(5, store.rules(false).size)
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun pauseSwitchChangesActualFilteringPreference() {
        val controller = launch()
        try {
            controller.get().findViewById<Switch>(R.id.filter_switch).isChecked = false
            assertFalse(store.enabled)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun allowedTabStoresAnExactException() {
        val controller = launch()
        try {
            controller.get().findViewById<View>(R.id.tab_allowed).performClick()
            controller.get().findViewById<View>(R.id.add_entry).performClick()
            val dialog = latestDialog()
            dialog.findViewById<EditText>(R.id.number_input).setText("0948123456")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            settle { store.rules(true).size == 1 && !dialog.isShowing }
            assertEquals("+33948123456", store.rules(true).single().canonical)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun existingRuleCanBeEditedAndDeletedFromTable() {
        val controller = launch()
        try {
            val activity = controller.get()
            val first = store.rules(false).first()
            fun allViews(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()
            fun openActions(label: String) {
                allViews(activity.window.decorView).first { it.contentDescription == activity.getString(R.string.rule_actions, label) }.performClick()
            }
            openActions(com.prefixblocker.app.core.PhoneNumbers.display(first.canonical))
            latestDialog().listView.performItemClick(null, 0, 0)
            val edit = latestDialog()
            edit.findViewById<EditText>(R.id.number_input).setText("0999*")
            edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            settle { store.rules(false).any { it.id == first.id && it.canonical == "+33999*" } && !edit.isShowing }
            settle { allViews(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "09 99*" } }
            openActions("09 99*")
            latestDialog().listView.performItemClick(null, 1, 1)
            latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            settle { store.rules(false).size == 4 }
            assertFalse(store.rules(false).any { it.id == first.id })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun missingScreeningRoleShowsActivationPrompt() {
        shadowOf(app.getSystemService(RoleManager::class.java)).removeHeldRole(RoleManager.ROLE_CALL_SCREENING)
        val controller = launch()
        try {
            val scroll = controller.get().findViewById<ScrollView>(R.id.content_scroll)
            fun texts(view: View): List<String> = (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
            assertTrue(texts(scroll).contains(app.getString(R.string.status_setup)))
            assertTrue(texts(scroll).contains(app.getString(R.string.choose_app)))
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "fr-rFR-w393dp-h852dp-night-xhdpi")
    fun darkThemeUsesItsOwnPalette() {
        val controller = launch()
        try {
            val activity = controller.get()
            assertEquals(android.graphics.Color.parseColor("#EBF1F4"), activity.getColor(R.color.ink))
            val destination = System.getProperty("screenshots.dir")
            if (destination != null) capture(activity, destination, "rules-dark.png")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun openEditorIsDismissedWhenActivityIsDestroyed() {
        val controller = launch()
        controller.get().findViewById<View>(R.id.add_entry).performClick()
        val dialog = latestDialog()
        assertTrue(dialog.isShowing)
        controller.pause().stop().destroy()
        assertFalse(dialog.isShowing)
    }

    @Test fun screenshotsUseOnlySyntheticLocalData() {
        val destination = System.getProperty("screenshots.dir") ?: return
        // Fictional examples only. No device, account, or real call history is read.
        val time = Instant.parse("2026-11-13T10:10:00Z").toEpochMilli()
        store.record("0948000001", time, Decision(Reason.BLOCKED, "+33948*"))
        store.record("0270000002", time + 120_000, Decision(Reason.BLOCKED, "+33270*"))
        store.record("0600000003", time + 240_000, Decision(Reason.NO_MATCH))
        val provider = CallHistoryTest.JournalProvider()
        provider.add("0948000001", time, CallLog.Calls.BLOCKED_TYPE)
        provider.add("0270000002", time + 120_000, CallLog.Calls.BLOCKED_TYPE)
        provider.add("0600000003", time + 240_000, CallLog.Calls.INCOMING_TYPE)
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALL_LOG)
        ShadowContentResolver.registerProviderInternal(CallLog.Calls.CONTENT_URI.authority!!, provider)
        val controller = launch()
        try {
            val activity = controller.get()
            capture(activity, destination, "rules.png")
            activity.findViewById<View>(R.id.add_entry).performClick()
            val dialog = latestDialog()
            dialog.findViewById<EditText>(R.id.number_input).setText("09 48*")
            captureView(dialog.window!!.decorView, destination, "edit-rule.png", 720, 760)
            dialog.dismiss()
            activity.findViewById<View>(R.id.tab_history).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            capture(activity, destination, "history.png")
            activity.findViewById<View>(R.id.tab_allowed).performClick()
            capture(activity, destination, "allowed.png")
        } finally { controller.pause().stop().destroy(); provider.db.close() }
    }

    private fun capture(activity: MainActivity, destination: String, name: String, scrollY: Int = 0) {
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(786, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1704, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 786, 1704)
        shadowOf(Looper.getMainLooper()).idle()
        activity.findViewById<ScrollView>(R.id.content_scroll).scrollTo(0, scrollY)
        captureView(root, destination, name, 786, 1704)
    }

    private fun captureView(root: View, destination: String, name: String, width: Int, height: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File(destination, name)
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
