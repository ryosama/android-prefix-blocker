package com.prefixblocker.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

object Contacts {
    fun contains(context: Context, number: String): Boolean {
        // Android itself excludes contacts from screening without READ_CONTACTS.
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
            ?.use { it.moveToFirst() }
            // If the provider cannot answer, do not risk rejecting a saved contact.
            ?: throw IllegalStateException("Contacts provider unavailable")
    }
}
