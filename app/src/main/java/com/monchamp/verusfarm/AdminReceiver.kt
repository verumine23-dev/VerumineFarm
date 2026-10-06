package com.monchamp.verusfarm

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

// Administrateur de l'appareil : tant qu'il est actif, Android refuse de désinstaller l'application.
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Sans cette protection, VerusFarm pourra être désinstallé et le minage s'arrêtera."
}
