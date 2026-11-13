package com.prefixblocker.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.database.sqlite.SQLiteConstraintException
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.prefixblocker.app.core.Decision
import com.prefixblocker.app.core.PhoneNumbers
import com.prefixblocker.app.core.PolicyEngine
import com.prefixblocker.app.core.Reason
import com.prefixblocker.app.data.AppStore
import com.prefixblocker.app.data.CallHistory
import com.prefixblocker.app.data.Contacts
import com.prefixblocker.app.data.HistoryEntry
import com.prefixblocker.app.data.HistoryResult
import com.prefixblocker.app.data.Rule
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val store by lazy { AppStore.get(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var tab = 0
    private var generation = 0
    private var alive = true
    private var observing = false
    private var settingsDialog: AlertDialog? = null
    private val dialogs = mutableSetOf<AlertDialog>()
    private lateinit var scroll: ScrollView
    private var blocks = emptyList<Rule>()
    private var allowed = emptyList<Rule>()
    private var history = HistoryResult(emptyList(), false)
    private var ready = false
    private var error = false
    private val journalChanged = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(reload)
            main.postDelayed(reload, 500)
        }
    }
    private val reload = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab") ?: 0
        render()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (has(Manifest.permission.READ_CALL_LOG) && !observing) {
            try {
                contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, journalChanged)
                observing = true
            } catch (_: SecurityException) { /* The local journal remains available. */ }
        }
    }

    override fun onPause() {
        if (observing) contentResolver.unregisterContentObserver(journalChanged)
        observing = false
        main.removeCallbacks(reload)
        super.onPause()
    }

    override fun onDestroy() {
        alive = false
        generation++
        main.removeCallbacksAndMessages(null)
        dialogs.toList().forEach { it.dismiss() }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab)
        super.onSaveInstanceState(outState)
    }

    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun holdsRole(): Boolean {
        val roles = getSystemService(RoleManager::class.java)
        return roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && roles.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun refresh() {
        val token = ++generation
        worker.execute {
            try {
                val newBlocks = store.rules(false)
                val newAllowed = store.rules(true)
                val newHistory = CallHistory(this, store).load()
                main.post {
                    if (alive && token == generation) {
                        blocks = newBlocks
                        allowed = newAllowed
                        history = newHistory
                        ready = true
                        error = false
                        render()
                    }
                }
            } catch (_: Exception) {
                main.post { if (alive && token == generation) { error = true; render() } }
            }
        }
    }

    private fun render() {
        val oldY = if (::scroll.isInitialized) scroll.scrollY else 0
        val frame = FrameLayout(this).apply { setBackgroundColor(color(R.color.background)) }
        scroll = ScrollView(this).apply {
            id = R.id.content_scroll
            isFillViewport = true
            clipToPadding = false
        }
        val body = column().apply { setPadding(dp(20), dp(20), dp(20), dp(28)) }
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        // Insets work on Android 10–15, including Android 15's enforced edge-to-edge layout.
        frame.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(frame)
        frame.requestApplyInsets()

        val header = row()
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_shield)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
        header.addView(column().apply {
            addView(text(getString(R.string.app_name), 24f, bold = true))
            addView(text(getString(R.string.tagline), 13f, R.color.muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button(R.string.settings, compact = true).apply {
            id = R.id.settings_button
            setOnClickListener { showSettings() }
        })
        body.addView(header)
        body.addView(text(getString(R.string.local_badge), 10f, R.color.muted, true).apply {
            letterSpacing = .08f
        }, spaced(top = 20, bottom = 10))
        body.addView(statusCard())

        val tabs = row().apply {
            background = rounded(R.color.border, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf(R.string.tab_rules, R.string.tab_history, R.string.tab_allowed).forEachIndexed { index, label ->
            tabs.addView(button(label, compact = true).apply {
                id = listOf(R.id.tab_rules, R.id.tab_history, R.id.tab_allowed)[index]
                isSelected = tab == index
                setTextColor(color(if (tab == index) R.color.accent else R.color.muted))
                background = ripple(if (tab == index) R.color.surface else R.color.border, 10)
                setOnClickListener {
                    if (tab != index) {
                        tab = index
                        scroll.scrollTo(0, 0)
                        render()
                    }
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(tabs, spaced(top = 22, bottom = 22))
        if (error) {
            body.addView(text(getString(R.string.load_error), 15f, R.color.danger))
            body.addView(button(R.string.refresh).apply { setOnClickListener { refresh() } })
        } else if (!ready) body.addView(text(getString(R.string.loading), 15f, R.color.muted))
        else when (tab) {
            0 -> renderRules(body, false)
            1 -> renderHistory(body)
            2 -> renderRules(body, true)
        }
        scroll.post { if (alive) scroll.scrollTo(0, oldY) }
    }

    private fun statusCard(): View {
        val selected = holdsRole()
        val enabled = store.enabled
        val active = selected && enabled
        val card = column().apply {
            background = rounded(if (active) R.color.accent_soft else R.color.surface, 20)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val titleRow = row()
        val title = when { !selected -> R.string.status_setup; enabled -> R.string.status_active; else -> R.string.status_paused }
        titleRow.addView(text(getString(title), 20f, if (active) R.color.accent else R.color.ink, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(Switch(this).apply {
            id = R.id.filter_switch
            contentDescription = getString(R.string.enable_filter)
            minHeight = dp(48)
            minWidth = dp(48)
            isChecked = enabled
            setOnCheckedChangeListener { _, checked ->
                store.enabled = checked
                render()
            }
        })
        card.addView(titleRow)
        val detail = when { !selected -> R.string.status_setup_detail; enabled -> R.string.status_active_detail; else -> R.string.status_paused_detail }
        card.addView(text(getString(detail), 14f, R.color.muted), spaced(top = 4))
        if (!selected) card.addView(button(R.string.choose_app, primary = true).apply {
            setOnClickListener { requestRole() }
        }, spaced(top = 14))
        card.addView(text(getString(R.string.contacts_safe), 12f, if (active) R.color.accent else R.color.muted), spaced(top = 12))
        return card
    }

    private fun renderRules(body: LinearLayout, exceptions: Boolean) {
        val rules = if (exceptions) allowed else blocks
        val heading = row()
        heading.addView(text(getString(if (exceptions) R.string.allowed_heading else R.string.rules_heading), 21f, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        if (!exceptions) heading.addView(text(resources.getQuantityString(R.plurals.rules_count, rules.size, rules.size), 12f, R.color.muted))
        body.addView(heading)
        body.addView(text(getString(if (exceptions) R.string.allowed_description else R.string.rules_description), 14f, R.color.muted), spaced(top = 8, bottom = 16))
        body.addView(button(if (exceptions) R.string.add_allowed else R.string.add_rule, primary = true).apply {
            id = R.id.add_entry
            setOnClickListener { editRule(exceptions) }
        }, spaced(bottom = 18))
        if (rules.isEmpty()) {
            body.addView(emptyCard(if (exceptions) R.string.empty_allowed else R.string.empty_rules,
                if (exceptions) R.string.empty_allowed_detail else R.string.empty_rules_detail))
            return
        }
        val table = column().apply {
            id = R.id.rule_list
            background = rounded(R.color.surface, 16, R.color.border)
            setPadding(dp(16), dp(4), dp(10), dp(4))
        }
        val columns = row().apply { setPadding(0, dp(12), dp(6), dp(12)) }
        columns.addView(text(getString(R.string.column_number), 10f, R.color.muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        columns.addView(text(getString(R.string.column_actions), 10f, R.color.muted, true))
        table.addView(columns)
        rules.forEach { rule ->
            table.addView(divider())
            val entry = row().apply { setPadding(0, dp(10), 0, dp(10)) }
            val label = PhoneNumbers.display(rule.canonical)
            entry.addView(column().apply {
                addView(text(label, 22f, bold = true).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                addView(text(getString(if (rule.canonical.endsWith('*')) R.string.prefix_type else R.string.exact_type), 12f, R.color.muted), spaced(top = 3))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            entry.addView(button(R.string.edit, compact = true).apply {
                contentDescription = getString(R.string.rule_actions, label)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle(label)
                        .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, which ->
                            if (which == 0) editRule(exceptions, rule) else deleteRule(rule)
                        }.showTracked()
                }
            })
            table.addView(entry)
        }
        body.addView(table)
        if (!exceptions) body.addView(text(getString(R.string.matching_note), 12f, R.color.muted), spaced(top = 14))
    }

    private fun editRule(exceptions: Boolean, existing: Rule? = null, initial: String = "") {
        val form = column().apply { setPadding(dp(24), dp(12), dp(24), dp(4)) }
        val input = numberInput(if (exceptions) R.string.allowed_hint else R.string.number_hint).apply {
            setText(existing?.let { PhoneNumbers.display(it.canonical) } ?: initial)
            setSelection(text.length)
        }
        form.addView(input)
        form.addView(text(getString(if (exceptions) R.string.allowed_help else R.string.rule_help), 13f, R.color.muted), spaced(top = 12))
        val preview = text("", 14f, R.color.accent)
        form.addView(preview, spaced(top = 12, bottom = 8))
        fun updatePreview() {
            preview.text = try {
                val rule = PhoneNumbers.parseRule(input.text.toString(), !exceptions)
                getString(when { exceptions -> R.string.preview_allowed; rule.endsWith('*') -> R.string.preview_prefix; else -> R.string.preview_exact },
                    PhoneNumbers.display(rule.removeSuffix("*")))
            } catch (_: IllegalArgumentException) { getString(R.string.preview_invalid) }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updatePreview()
        val title = when {
            exceptions && existing == null -> R.string.new_allowed
            exceptions -> R.string.edit_allowed
            existing == null -> R.string.new_rule
            else -> R.string.edit_rule
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(form)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text.toString()
                try { PhoneNumbers.parseRule(raw, !exceptions) }
                catch (_: IllegalArgumentException) {
                    input.error = getString(if (exceptions) R.string.invalid_allowed else R.string.invalid_rule)
                    return@setOnClickListener
                }
                val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                save.isEnabled = false
                worker.execute {
                    val result = runCatching { store.saveRule(raw, exceptions, existing?.id) }
                    main.post {
                        if (alive) {
                            save.isEnabled = true
                            result.fold(onSuccess = {
                                dialog.dismiss()
                                toast(R.string.saved)
                                refresh()
                            }, onFailure = {
                                input.error = getString(if (it is SQLiteConstraintException) R.string.duplicate_rule else R.string.operation_error)
                            })
                        }
                    }
                }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        showTracked(dialog)
    }

    private fun deleteRule(rule: Rule) {
        AlertDialog.Builder(this).setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, PhoneNumbers.display(rule.canonical)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                mutate(R.string.deleted) { store.deleteRule(rule) }
            }.showTracked()
    }

    private fun renderHistory(body: LinearLayout) {
        val title = row()
        title.addView(text(getString(R.string.history_heading), 21f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(button(R.string.refresh, compact = true).apply { setOnClickListener { refresh() } })
        body.addView(title)
        body.addView(text(getString(R.string.history_description), 14f, R.color.muted), spaced(top = 6))
        body.addView(text(getString(if (history.systemJournal) R.string.history_system else R.string.history_local, history.entries.size), 12f, R.color.accent, true), spaced(top = 10, bottom = 16))
        if (!history.systemJournal) {
            body.addView(column().apply {
                background = rounded(R.color.warning_soft, 14)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                addView(text(getString(R.string.history_fallback), 13f, R.color.warning))
                addView(button(R.string.grant_history, compact = true).apply {
                    setOnClickListener { requestPermission(Manifest.permission.READ_CALL_LOG) }
                }, spaced(top = 8))
            }, spaced(bottom = 16))
        }
        if (history.entries.isEmpty()) body.addView(emptyCard(R.string.history_empty, R.string.history_empty_detail))
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        history.entries.forEach { entry ->
            val card = column().apply {
                background = rounded(R.color.surface, 16, R.color.border)
                setPadding(dp(16), dp(14), dp(16), dp(10))
            }
            val canonical = PhoneNumbers.normalizeCall(entry.number)
            val number = canonical?.let { PhoneNumbers.display(it) } ?: entry.number?.takeIf { it.isNotBlank() }
            card.addView(text(number ?: getString(R.string.hidden_number), 19f, bold = true))
            entry.name?.takeIf { it.isNotBlank() && has(Manifest.permission.READ_CONTACTS) }?.let {
                card.addView(text(it, 13f, R.color.muted), spaced(top = 3))
            }
            card.addView(text(date.format(Date(entry.time)), 12f, R.color.muted), spaced(top = 5))
            val footer = row()
            val blocked = entry.decision?.blocked == true || entry.type == CallLog.Calls.BLOCKED_TYPE
            footer.addView(text(historyStatus(entry), 12f, if (blocked) R.color.danger else R.color.accent, true), LinearLayout.LayoutParams(0, -2, 1f))
            if (canonical != null) footer.addView(button(R.string.block_number, compact = true).apply {
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle(R.string.call_actions)
                        .setItems(arrayOf(getString(R.string.block_number), getString(R.string.allow_number))) { _, which ->
                            editRule(which == 1, initial = PhoneNumbers.display(canonical))
                        }.showTracked()
                }
            })
            card.addView(footer, spaced(top = 6))
            body.addView(card, spaced(bottom = 10))
        }
    }

    private fun historyStatus(entry: HistoryEntry): String {
        val decision = entry.decision
        if (decision?.blocked == true) return getString(R.string.status_blocked_rule, PhoneNumbers.display(decision.rule.orEmpty()))
        val type = when (entry.type) {
            CallLog.Calls.INCOMING_TYPE -> R.string.status_incoming
            CallLog.Calls.MISSED_TYPE -> R.string.status_missed
            CallLog.Calls.REJECTED_TYPE -> R.string.status_rejected
            CallLog.Calls.BLOCKED_TYPE -> R.string.status_system_blocked
            CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> R.string.status_external
            else -> null
        }
        return type?.let { getString(it) } ?: decisionText(decision ?: Decision(Reason.NO_MATCH))
    }

    private fun decisionText(decision: Decision): String = when (decision.reason) {
        Reason.BLOCKED -> getString(R.string.test_blocked, PhoneNumbers.display(decision.rule.orEmpty()))
        Reason.CONTACT -> getString(R.string.reason_contact)
        Reason.EXCEPTION -> getString(R.string.reason_exception)
        Reason.PAUSED -> getString(R.string.reason_paused)
        Reason.UNKNOWN -> getString(R.string.reason_unknown)
        Reason.ERROR -> getString(R.string.reason_error)
        Reason.TIMEOUT -> getString(R.string.reason_timeout)
        Reason.NO_MATCH -> getString(R.string.status_allowed)
    }

    private fun showSettings() {
        settingsDialog?.dismiss()
        val content = column().apply { setPadding(dp(24), dp(8), dp(24), dp(16)) }
        fun section(title: Int, description: Int) {
            content.addView(text(getString(title), 17f, bold = true), spaced(top = 20, bottom = 8))
            content.addView(text(getString(description), 14f, R.color.muted))
        }
        section(R.string.setup_filter_title, R.string.setup_filter_body)
        if (holdsRole()) content.addView(text(getString(R.string.setup_filter_ready), 13f, R.color.accent), spaced(top = 8))
        else content.addView(button(R.string.choose_app, primary = true).apply {
            setOnClickListener { settingsDialog?.dismiss(); requestRole() }
        }, spaced(top = 8))
        section(R.string.setup_history_title, R.string.setup_history_body)
        permissionControl(content, Manifest.permission.READ_CALL_LOG)
        section(R.string.setup_contacts_title, R.string.setup_contacts_body)
        permissionControl(content, Manifest.permission.READ_CONTACTS)
        content.addView(button(R.string.test_title).apply {
            setOnClickListener { settingsDialog?.dismiss(); showTest() }
        }, spaced(top = 24))
        section(R.string.privacy_title, R.string.privacy_body)
        section(R.string.limits_title, R.string.limits_body)
        content.addView(button(R.string.android_settings).apply { setOnClickListener { openAndroidSettings() } }, spaced(top = 16))
        content.addView(button(R.string.clear_history).apply {
            setTextColor(color(R.color.danger))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle(R.string.clear_history_title)
                    .setMessage(R.string.clear_history_message).setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ -> mutate(R.string.history_cleared) { store.clearEvents() } }.showTracked()
            }
        }, spaced(top = 8))
        content.addView(text(getString(R.string.version, BuildConfig.VERSION_NAME), 12f, R.color.muted), spaced(top = 20))
        settingsDialog = AlertDialog.Builder(this).setTitle(R.string.setup_title)
            .setView(ScrollView(this).apply { addView(content) }).setPositiveButton(R.string.close, null).showTracked()
    }

    private fun permissionControl(content: LinearLayout, permission: String) {
        if (has(permission)) content.addView(text(getString(R.string.permission_granted), 13f, R.color.accent), spaced(top = 8))
        else content.addView(button(R.string.grant_permission).apply {
            setOnClickListener { settingsDialog?.dismiss(); requestPermission(permission) }
        }, spaced(top = 8))
    }

    @Suppress("DEPRECATION")
    private fun requestRole() {
        val roles = getSystemService(RoleManager::class.java)
        if (!roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) { toast(R.string.role_unavailable); return }
        try { startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 100) }
        catch (_: Exception) { toast(R.string.role_error) }
    }

    private fun requestPermission(permission: String) {
        if (has(permission)) { refresh(); return }
        val prefs = getSharedPreferences("permission_requests", MODE_PRIVATE)
        if (prefs.getBoolean(permission, false) && !shouldShowRequestPermissionRationale(permission)) {
            openAndroidSettings()
        } else {
            prefs.edit().putBoolean(permission, true).apply()
            requestPermissions(arrayOf(permission), 101)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) toast(R.string.permission_denied)
            refresh()
        }
    }

    private fun openAndroidSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { toast(R.string.operation_error) }
    }

    private fun showTest() {
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(text(getString(R.string.test_help), 14f, R.color.muted))
        val input = numberInput(R.string.allowed_hint)
        body.addView(input, spaced(top = 12))
        val result = text("", 15f, R.color.accent, true)
        body.addView(result, spaced(top = 12))
        if (!holdsRole()) body.addView(text(getString(R.string.test_role_note), 12f, R.color.warning), spaced(top = 12))
        val dialog = AlertDialog.Builder(this).setTitle(R.string.test_title).setView(body)
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.test_button, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text.toString()
                if (PhoneNumbers.normalizeCall(raw) == null) { input.error = getString(R.string.invalid_allowed); return@setOnClickListener }
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
                val verify = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                verify.isEnabled = false
                worker.execute {
                    val decision = try {
                        PolicyEngine.decide(raw, store.enabled, store.rules(false).map { it.canonical },
                            store.rules(true).map { it.canonical }) { Contacts.contains(this, it) }
                    } catch (_: Exception) { Decision(Reason.ERROR) }
                    main.post {
                        if (alive) {
                            verify.isEnabled = true
                            result.text = if (decision.reason == Reason.NO_MATCH) getString(R.string.test_allowed) else decisionText(decision)
                            result.setTextColor(color(if (decision.blocked) R.color.danger else R.color.accent))
                        }
                    }
                }
            }
        }
        showTracked(dialog)
    }

    private fun mutate(successMessage: Int, operation: () -> Unit) {
        worker.execute {
            val success = runCatching(operation).isSuccess
            main.post { if (alive) { toast(if (success) successMessage else R.string.operation_error); refresh() } }
        }
    }

    private fun AlertDialog.Builder.showTracked(): AlertDialog = create().also { showTracked(it) }

    private fun showTracked(dialog: AlertDialog) {
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
    }

    private fun numberInput(hint: Int) = EditText(this).apply {
        id = R.id.number_input
        setHint(hint)
        inputType = InputType.TYPE_CLASS_PHONE
        setSingleLine(true)
        textSize = 21f
        setTextColor(color(R.color.ink))
        setHintTextColor(color(R.color.muted))
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        minHeight = dp(56)
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun text(value: String, size: Float, tint: Int = R.color.ink, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color(tint))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
        if (size >= 20 && bold) isAccessibilityHeading = true
    }

    private fun button(label: Int, primary: Boolean = false, compact: Boolean = false) = Button(this).apply {
        setText(label)
        isAllCaps = false
        textSize = if (compact) 12f else 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = if (compact) dp(48) else 0
        minimumWidth = minWidth
        setPadding(dp(if (compact) 10 else 16), dp(8), dp(if (compact) 10 else 16), dp(8))
        setTextColor(color(if (primary) R.color.on_accent else R.color.accent))
        background = ripple(if (primary) R.color.accent else R.color.accent_soft, 12)
        stateListAnimator = null
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun divider() = View(this).apply { setBackgroundColor(color(R.color.border)); layoutParams = LinearLayout.LayoutParams(-1, dp(1)) }
    private fun color(resource: Int) = getColor(resource)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun spaced(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }
    private fun rounded(fill: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(color(fill)); cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), color(it)) }
    }
    private fun ripple(fill: Int, radius: Int) = RippleDrawable(ColorStateList.valueOf(color(R.color.border)), rounded(fill, radius), rounded(fill, radius))
    private fun emptyCard(title: Int, description: Int) = column().apply {
        background = rounded(R.color.surface, 16, R.color.border)
        setPadding(dp(20), dp(26), dp(20), dp(26))
        addView(text(getString(title), 17f, bold = true))
        addView(text(getString(description), 14f, R.color.muted), spaced(top = 8))
    }
    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
